package com.avoqado.pos.cashdrawer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 EL OTRO LADO DE LA PROTECCIÓN: LA VENTA QUE **SÍ** ATERRIZÓ EN EL SERVER.
 *
 * `PendingCashSales` existe para que una venta cobrada sin red no desaparezca del
 * arqueo mientras su cobro espera en la cola. Pero la cola dice "todavía no lo he
 * reproducido YO" — **no** dice "el server no lo tiene**. Y hay un caso, medido en
 * producción, en el que el server SÍ lo tiene aunque la cola siga llena: la respuesta
 * se perdió DESPUÉS del commit.
 *
 * Está documentado en el propio repo, en `CashPaymentRepository.queueCashPayment`:
 * *"Medido el 2026-08-09 con el log del backend: el pago quedó guardado con la llave
 * 65fb7769…, hubo 6 reintentos y CERO deduplicaciones."* Durante esos 6 reintentos el
 * server ya tenía la venta y el cobro local seguía `PENDING`.
 *
 * El POS no puede fusionarlas por llave: el `CASH_SALE` que crea el server lleva
 * `localId = "srv-cash-sale:<paymentId>"` (`shared/cashDrawerPosting.ts`), un id que
 * este aparato nunca vio. Así que la copia local queda "no confirmada", el pareo por
 * monto la salva por error, y la MISMA venta suma dos veces: **560000 en pantalla con
 * 530000 en el cajón**. Y esta vez la dirección es la fea — al cajero le FALTAN $300 y
 * eso es lo que hace que acusen a alguien de robar.
 *
 * La regla que lo cierra: **una venta confirmada que el server trae POR PRIMERA VEZ y
 * que no es una fila mía renombrada RECLAMA una copia local** — por orden si la hay,
 * si no por monto y de la más VIEJA hacia adelante. Una fila reclamada deja de ser
 * candidata a protegerse: el server ya la tiene, la copia sobra.
 *
 * El contrapeso, que es lo que impide que este arreglo se coma al de `82fda27`: sólo
 * reclama lo que llega POR PRIMERA VEZ. Una venta del server que un sync anterior ya
 * copió vuelve en cada payload para siempre; si reclamara cada vez, se comería la
 * protección de una venta distinta y le inventaría al cajero el faltante por el otro
 * lado.
 */
class CashDrawerVentaQueSiAterrizoTest {

    private val abrioHace = haceMinutos(60)

    // MARK: - M3a · la respuesta se perdió DESPUÉS de que el server ya cobró

    /**
     * 🔴 EL NÚMERO MEDIDO. Venta de mostrador de $300 cobrada por cobro rápido; el
     * server la registró y el 503 se comió la respuesta, así que el cobro sigue
     * `PENDING` en la cola. El cajón tiene $5,300.00.
     *
     * Antes de este arreglo la pantalla decía **$5,600.00**: la fila local protegida
     * por el cobro encolado + la fila que el server acaba de confirmar. $300 de
     * faltante inventado.
     */
    @Test
    fun `la venta que el server SI cobro deja de contarse dos veces`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    // La llave que el POS nunca vio, y la orden que creó el server.
                    eventoJson(
                        "srv-ev-venta", "CASH_SALE", "300.00",
                        localId = "srv-cash-sale:pay-1",
                        orderId = "order-que-creo-el-server",
                        createdAt = haceMinutos(5),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            // El cobro SIGUE en la cola: 6 reintentos, cero deduplicaciones.
            pendingCashSales = cobrosEnCola(cobroSinOrden(30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la venta se contó dos veces y el cajero cierra con un faltante inventado " +
                "de \$300 (fondo 5000 + la del server 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("sobrevivió la copia local de una venta que el server YA tiene", dao.events["local-venta"])
    }

    /**
     * 🔴 EL CASO COMÚN DE `82fda27`, QUE NO SE PUEDE MOVER NI UN CENTAVO: el server NO
     * tiene la venta. Sin nada que reclame, la protección funciona exactamente igual
     * que antes y los $300 del cajón siguen en pantalla.
     */
    @Test
    fun `sin venta confirmada, la de mostrador encolada sigue protegida`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = abrioHace),
            ),
            pendingCashSales = cobrosEnCola(cobroSinOrden(30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "se movió el caso común: al cajero le desaparecieron los \$300 que sí están " +
                "en el cajón (fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull(dao.events["local-venta"])
    }

    /**
     * 🔴 EL CONTRAPESO, y el test que impide que el arreglo de arriba se convierta en
     * un faltante por el otro lado: **la venta del server que un sync ANTERIOR ya copió
     * no vuelve a reclamar nada.**
     *
     * El payload del server la trae en CADA sync, para siempre. Si reclamara cada vez,
     * se comería la protección de la venta que sigue encolada — que es otra venta, con
     * su dinero aparte, físicamente en el cajón.
     *
     * fondo $5,000 + la del server $300 (ya copiada) + la encolada $300 = **$5,600.00**
     */
    @Test
    fun `la venta del server ya copiada en un sync anterior no reclama de nuevo`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        // Ya está en Room desde el sync pasado, con el id del server.
        dao.insertEvent(
            eventoLocal("srv-ev-A", "srv-1", "CASH_SALE", 30_000, orderId = "order-A", createdAt = haceMinutos(40)),
        )
        // Otra venta, cobrada después y todavía encolada.
        dao.insertEvent(
            eventoLocal("local-venta-B", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson(
                        "srv-ev-A", "CASH_SALE", "300.00",
                        localId = "srv-cash-sale:pay-A", orderId = "order-A", createdAt = haceMinutos(40),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola(cobroSinOrden(30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la venta repetida del server se comió la protección de la que sigue " +
                "encolada (fondo 5000 + la del server 300 + la encolada 300)",
            560_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull("se borró la venta cuyo cobro sigue en la cola", dao.events["local-venta-B"])
    }

    /**
     * 🔴 EL OTRO CONTRAPESO, y el que casi se me escapa: **una fila MÍA que adoptó el id
     * del server tampoco reclama.** Es la misma fila con otro nombre, no una venta
     * nueva; si contara como confirmación nueva se llevaría por delante a OTRA copia
     * local del mismo monto — la que sigue esperando en la cola — y el cajero cerraría
     * con un sobrante que nadie le explica.
     *
     * (Hoy el server nunca manda un `CASH_SALE` con la llave de una fila del cliente
     * —`postCashSaleToDrawer` usa la suya, `srv-cash-sale:<paymentId>`— así que este
     * camino está apagado por el contrato. El guard existe igual: la ablación de quitarlo
     * no tumbaba NADA, o sea que sin este test la condición no estaba probada, y el día
     * que el server empiece a devolver la llave del cliente el defecto entra sin ruido.)
     *
     * fondo $5,000 + la adoptada $300 + la que sigue encolada $300 = **$5,600.00**
     */
    @Test
    fun `la fila que adopto el id del server no reclama ademas otra copia local`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta-A", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(40)),
        )
        dao.insertEvent(
            eventoLocal("local-venta-B", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    // La llave ES la de mi fila: el server confirma que `local-venta-A`
                    // es suya, así que esa fila cambia de nombre en vez de duplicarse.
                    eventoJson(
                        "srv-ev-A", "CASH_SALE", "300.00",
                        localId = "local-venta-A", createdAt = haceMinutos(40),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola(cobroSinOrden(30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la fila adoptada reclamó ADEMÁS la copia que sigue encolada (fondo 5000 + " +
                "la adoptada 300 + la encolada 300)",
            560_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull("se borró la venta cuyo cobro sigue en la cola", dao.events["local-venta-B"])
        assertNull("la fila adoptada conservó su id viejo", dao.events["local-venta-A"])
    }

    // MARK: - M3b · la ventana de la caja

    /**
     * 🔴 UN COBRO ATORADO DEL TURNO ANTERIOR NO PUEDE PROTEGER UNA VENTA DE HOY.
     *
     * `sinReproducir` no tenía cota: un cobro que quedó `PENDING` ayer —o de una venta
     * hecha con la caja cerrada— pareaba por monto una venta de hoy que el server ya
     * confirmó, y la copia local sobrevivía. Es la MISMA fuga que cerró `729b0a8` ("el
     * retiro de ayer no se cuela a hoy"), reabierta por otra puerta.
     *
     * La cota es la misma que ya se aceptó allá: `createdAt >= session.openedAt` — la
     * ventana con la que el server calcula su esperado, así que cliente y server no
     * pueden divergir por construcción.
     *
     * fondo $5,000 + la venta del server $300 = **$5,300.00**. Con el cobro de ayer
     * protegiendo salían $5,600 y $300 de faltante inventado.
     */
    @Test
    fun `un cobro atorado de la caja anterior NO protege una venta de hoy`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        // La venta de hoy que el server YA confirmó en un sync anterior…
        dao.insertEvent(
            eventoLocal("srv-ev-hoy", "srv-1", "CASH_SALE", 30_000, orderId = "order-hoy", createdAt = haceMinutos(20)),
        )
        // …y su copia local, que sigue viva sólo porque el cobro de ayer la protege.
        dao.insertEvent(
            eventoLocal("local-venta-hoy", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(20)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson(
                        "srv-ev-hoy", "CASH_SALE", "300.00",
                        localId = "srv-cash-sale:pay-hoy", orderId = "order-hoy", createdAt = haceMinutos(20),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            // Cobro de la caja ANTERIOR: se encoló 4 horas antes de que abriera ésta.
            pendingCashSales = colaDeCobros(
                cobros = listOf(cobroEncolado("cobro-de-ayer", 30_000, createdAt = haceMinutos(240))),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "un cobro atorado del turno anterior protegió una venta de hoy y el cajero " +
                "cierra con un faltante inventado (fondo 5000 + la del server 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("sobrevivió la copia local que el cobro de ayer protegía", dao.events["local-venta-hoy"])
    }

    /**
     * El contrapeso de la cota: un cobro de ESTA caja sigue protegiendo. Si la ventana
     * se cerrara de más, volveríamos al defecto de `82fda27` con otro disfraz.
     */
    @Test
    fun `un cobro de ESTA caja sigue protegiendo su venta`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = abrioHace),
            ),
            pendingCashSales = colaDeCobros(
                cobros = listOf(cobroEncolado("cobro-de-hoy", 25_000, tipCents = 5_000, createdAt = haceMinutos(5))),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la cota se comió un cobro de esta misma caja (fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull(dao.events["local-venta"])
    }

    // MARK: - M3c · paridad con iOS: sin ventas del server no se suelta nada

    /**
     * 🔴 PARIDAD ANDROID ↔ iOS (P1). El cobro está en CUARENTENA, así que no protege
     * nada — y el payload del server no trae NI UNA venta.
     *
     * iOS no suelta nada en ese estado (`CashDrawerServerMerge.ventasLocalesQueElServidorYaCubre`
     * abre con `guard servidorConfirmaVentas`), Android sí barría: el mismo cajón daba
     * **500000 en la tablet y 530000 en el iPad**. Es el estado normal de una tienda
     * que lleva rato sin red, no un caso de laboratorio.
     *
     * Un payload sin ventas no es prueba de que la mía no exista: aquí el fail-safe no
     * puede ser desaparecerle dinero al cajero (mismo criterio que la config de
     * impresoras, que no se pisa con un refresh fallido).
     */
    @Test
    fun `sin ventas confirmadas el server no autoriza a soltar ninguna venta local`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = abrioHace),
            ),
            // Cuarentena: NO protege. Lo único que salva la fila es el guard.
            pendingCashSales = colaDeCobros(
                cobros = listOf(
                    cobroEncolado(
                        "cobro-en-cuarentena", 30_000,
                        syncStatus = com.avoqado.pos.core.data.local.database.PaymentSyncStatus.FAILED.name,
                    ),
                ),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "Android soltó una venta que el iPad conserva: el mismo cajón daría 500000 " +
                "aquí y 530000 allá (fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull(dao.events["local-venta"])
    }

    /**
     * P2 de la misma paridad: en cuanto el server SÍ confirma una venta, las dos
     * plataformas dan el mismo número — y la copia local se suelta, que es para lo que
     * el barrido existe. El guard no puede volverse una excusa para no limpiar.
     */
    @Test
    fun `con una venta confirmada el barrido vuelve a correr y las dos plataformas coinciden`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson(
                        "srv-ev-venta", "CASH_SALE", "300.00",
                        localId = "srv-cash-sale:pay-1", createdAt = haceMinutos(5),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = colaDeCobros(
                cobros = listOf(
                    cobroEncolado(
                        "cobro-en-cuarentena", 30_000,
                        syncStatus = com.avoqado.pos.core.data.local.database.PaymentSyncStatus.FAILED.name,
                    ),
                ),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la venta se contó dos veces (fondo 5000 + la del server 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("la copia local sobrevivió a su confirmación", dao.events["local-venta"])
    }
}
