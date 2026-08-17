package com.avoqado.pos.cashdrawer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 LOS INTENTOS DE ROMPER LA REGLA NUEVA, Y LOS RESIDUOS QUE QUEDAN MEDIDOS.
 *
 * La regla ("una venta que el server confirma por primera vez RECLAMA una copia local,
 * y una fila reclamada ya no se protege") se probó con los casos que tenía que
 * arreglar. Esta suite es lo contrario: los casos escogidos para tumbarla.
 *
 * Los que NO se pudieron arreglar quedan aquí igual, asertando el número REAL de hoy y
 * diciendo en qué dirección falla. Un residuo medido y anclado es honesto; uno que sólo
 * vive en un reporte se vuelve a descubrir dentro de tres meses. Si alguien lo arregla
 * río arriba, estos tests se ponen rojos y quien pase por ahí tiene que actualizarlos a
 * conciencia — que es exactamente lo que se busca.
 *
 * 🔑 **La dirección manda sobre la frecuencia.** De más = al cajero le FALTA dinero, y
 * es la que hace que acusen a una persona de robar. De menos = al cajero le SOBRA, y
 * nadie acusa a nadie por eso. Donde no se puede tener las dos, se elige sobrante.
 */
class CashDrawerIntentosDeRomperloTest {

    private val abrioHace = haceMinutos(60)

    // MARK: - (a) La venta reembolsada

    /**
     * La venta aterrizó, el cliente la devolvió, y el server manda las dos cosas: su
     * `CASH_SALE` y el `PAY_OUT` del reembolso. El cobro de la venta sigue atorado en la
     * cola.
     *
     * El riesgo era que el reembolso —mismo monto, misma caja— entrara en el pareo y
     * desordenara la cuenta. No puede: reclamar y proteger sólo miran `CASH_SALE`, y el
     * `PAY_OUT` ni siquiera es de los tipos que el barrido toca.
     *
     * fondo $5,000 + venta $300 − reembolso $300 = **$5,000.00**, que es lo que hay
     * físicamente en el cajón.
     */
    @Test
    fun `la venta reembolsada no descuadra el pareo`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(20)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson(
                        "srv-ev-venta", "CASH_SALE", "300.00",
                        localId = "srv-cash-sale:pay-1", orderId = "order-srv", createdAt = haceMinutos(20),
                    ),
                    eventoJson(
                        "srv-ev-reembolso", "PAY_OUT", "300.00",
                        orderId = "order-srv", createdAt = haceMinutos(10),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola(cobroSinOrden(30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el reembolso descuadró el pareo (fondo 5000 + venta 300 − reembolso 300)",
            500_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("sobrevivió la copia local de la venta que el server ya tiene", dao.events["local-venta"])
    }

    // MARK: - (b) Dos cobros del mismo monto: uno aterrizó, el otro no

    /**
     * 🔴 EL CASO QUE MÁS FÁCIL SE ROMPE. Dos ventas de mostrador de $300 y dos cobros
     * atorados en la cola; el server sólo tiene UNA de las dos, y llega por primera vez.
     *
     * Reclamar de más borraría las dos y le inventaría al cajero un sobrante de $300;
     * proteger de más las salvaría las dos y le inventaría un faltante de $300. Sólo
     * acierta si la venta confirmada reclama UNA fila —la más VIEJA, que es la que se
     * cobró primero y por tanto la que el server pudo alcanzar— y la otra sigue
     * protegida.
     *
     * fondo $5,000 + la del server $300 + la que sigue encolada $300 = **$5,600.00**
     */
    @Test
    fun `dos ventas del mismo monto, una aterrizo y la otra sigue encolada`() = runTest {
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
                    eventoJson(
                        "srv-ev-A", "CASH_SALE", "300.00",
                        localId = "srv-cash-sale:pay-A", orderId = "order-A", createdAt = haceMinutos(40),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola(cobroSinOrden(30_000), cobroSinOrden(30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el arqueo no cuadra (fondo 5000 + la del server 300 + la encolada 300)",
            560_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("la copia de la venta que el server ya tiene sobrevivió", dao.events["local-venta-A"])
        assertNotNull("se borró la venta cuyo cobro sigue en la cola", dao.events["local-venta-B"])
    }

    // MARK: - (c) La caja recién abierta con cobros de la anterior — por el OUTBOX

    /**
     * La misma fuga que el cobro atorado de `pending_payments`, pero por la otra cola:
     * un `PAY_CASH` del outbox que quedó pendiente en el turno anterior. Las dos colas
     * tienen que respetar la ventana de la caja o la cota no sirve de nada.
     *
     * fondo $5,000 + la venta del server $300 = **$5,300.00**
     */
    @Test
    fun `un PAY_CASH atorado del turno anterior tampoco protege una venta de hoy`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("srv-ev-hoy", "srv-1", "CASH_SALE", 30_000, orderId = "order-hoy", createdAt = haceMinutos(20)),
        )
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
            pendingCashSales = colaDeCobros(
                intents = listOf(
                    intentPayCash("local-order-de-ayer", 25_000, tipCents = 5_000, createdAt = haceMinutos(240)),
                ),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "un PAY_CASH del turno anterior protegió una venta de hoy y el cajero cierra " +
                "con un faltante inventado (fondo 5000 + la del server 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull(dao.events["local-venta-hoy"])
    }

    /** Y el contrapeso: el `PAY_CASH` de ESTA caja sigue protegiendo. */
    @Test
    fun `un PAY_CASH de esta caja sigue protegiendo su venta`() = runTest {
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
                intents = listOf(
                    intentPayCash("local-order-9", 25_000, tipCents = 5_000, createdAt = haceMinutos(5)),
                ),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la cota se comió un PAY_CASH de esta misma caja (fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull(dao.events["local-venta"])
    }

    // MARK: - RESIDUOS DECLARADOS (fallan a propósito hacia un lado u otro)

    /**
     * 🔴 **LA VENTA CONFIRMADA CON OTRO MONTO, PERO CON ORDEN: aquí sí se cierra.** El
     * server confirma la venta de la orden `order-9` por $305 mientras el cajón guardó
     * $300 (redondeó la propina), y el cobro de esa MISMA orden sigue atorado en la cola.
     *
     * Por eso el reclamo tiene una pasada por ORDEN antes que la de monto: la orden es un
     * hecho y el monto una heurística, así que cuando los dos extremos escriben números
     * distintos la orden es lo único que queda. Sin esa pasada, la confirmación no
     * reclamaría nada, el cobro encolado protegería una fila que el server SÍ tiene, y
     * saldrían $5,605.00 — **$305 de faltante inventado**. Con ella salen **$5,305.00**,
     * y los $5 de diferencia contra el cajón son un SOBRANTE que viene del redondeo del
     * propio server.
     */
    @Test
    fun `la venta confirmada con otro monto SI se reclama cuando comparte la orden`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = "order-9", createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson(
                        "srv-ev-venta", "CASH_SALE", "305.00",
                        localId = "srv-cash-sale:pay-1", orderId = "order-9", createdAt = haceMinutos(5),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola(cobroDeOrden("order-9", 30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la venta se contó dos veces por \$5 de diferencia en el total " +
                "(fondo 5000 + la del server 305)",
            530_500,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("sobrevivió la copia de una venta que el server ya tiene", dao.events["local-venta"])
    }

    /**
     * ⚠️ **RESIDUO DECLARADO — dirección FALTANTE.** El mismo desajuste de monto, pero en
     * la venta de MOSTRADOR: sin orden con que nombrarse, el monto es lo único que hay, y
     * si los dos extremos escriben números distintos no queda con qué parear — la
     * confirmación no reclama nada y el cobro encolado protege una fila que el server SÍ
     * tiene.
     *
     * Da **$5,605.00** con $5,300.00 en el cajón. **No es una regresión** —hoy, antes de
     * este cambio, daba exactamente lo mismo— y su causa raíz es la misma que la del
     * cobro dividido de abajo: el cajón y la cola guardan totales distintos. El arreglo
     * de verdad vive río arriba (que las dos puntas escriban el MISMO número), no en más
     * heurística aquí: parear "por monto parecido" sería inventar una regla difusa
     * encima del dinero.
     *
     * 🔴 NO se intentó tapar con una consumición a ciegas ("una confirmación que no
     * reclamó nada se come un cobro pendiente cualquiera"). Eso arreglaría este caso y
     * rompería uno peor: en un local con dos POS, cada venta que hace el aparato EN LÍNEA
     * se comería una protección del aparato SIN red, y el arqueo del segundo perdería
     * todo lo que lleva cobrado.
     */
    @Test
    fun `RESIDUO — la venta confirmada con OTRO monto no reclama y se cuenta dos veces`() = runTest {
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
                        "srv-ev-venta", "CASH_SALE", "305.00",
                        localId = "srv-cash-sale:pay-1", orderId = "order-srv", createdAt = haceMinutos(5),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola(cobroSinOrden(30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "RESIDUO CONOCIDO: cajón y cola guardan totales distintos, así que la " +
                "confirmación no reclama y la venta se cuenta dos veces (faltante de \$305)",
            560_500,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    /**
     * ⚠️ **RESIDUO DECLARADO — dirección SOBRANTE, y consecuencia directa de la regla
     * nueva.** En un local con DOS POS compartiendo caja, la venta que hace el otro
     * aparato llega por primera vez y —si coincide en monto— reclama MI copia local, que
     * es otra venta con su dinero aparte.
     *
     * Da **$5,300.00** con $5,600.00 en el cajón: al cajero le SOBRAN $300, que es la
     * dirección buena. Y se cura solo: en el siguiente sync esa venta ajena ya no llega
     * por primera vez, y cuando mi cobro se reproduce el server manda la mía.
     *
     * Se acepta a conciencia. La alternativa —no reclamar nunca por monto— reabre M3a,
     * que falla hacia el faltante.
     */
    @Test
    fun `RESIDUO — la venta de OTRO POS del mismo monto reclama la mia`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta-mia", "srv-1", "CASH_SALE", 30_000, orderId = null, createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson(
                        "srv-ev-del-otro-pos", "CASH_SALE", "300.00",
                        localId = "srv-cash-sale:pay-otro", orderId = "order-del-otro", createdAt = haceMinutos(3),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola(cobroSinOrden(30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "RESIDUO CONOCIDO: la venta del otro POS reclamó la mía. Dirección sobrante " +
                "(\$5,300 en pantalla con \$5,600 en el cajón) y se cura al siguiente sync",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - El residual del cobro DIVIDIDO, vuelto a medir

    /**
     * 🔴 **EL RESIDUAL DEL COBRO DIVIDIDO SE CAE SOLO EN SU CASO REAL.** El cajón
     * registra la PARTE cobrada ($250) y la cola guarda el total del carrito ($300)
     * (`CashPaymentRepository.queueCashPayment` usa `orderRequest.total`, mientras
     * `recordCashSale` recibe `currentBaseAmount() + propina` con el override del
     * dividido), así que el pareo por monto nunca puede acertar.
     *
     * Antes daba **500000** con $5,250.00 en el cajón. Ya no: el local que cobra
     * dividido sin red tiene un server que no confirma NI UNA venta, y ahí el guard
     * portado de iOS impide soltar nada. **525000**, que es lo que hay en el cajón.
     */
    @Test
    fun `el cobro DIVIDIDO de mostrador ya no se pierde cuando el server no confirma ventas`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-parte", "srv-1", "CASH_SALE", 25_000, orderId = null, createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = abrioHace),
            ),
            // La cola guarda el total del CARRITO, no la parte cobrada: no puede parear.
            pendingCashSales = colaDeCobros(
                cobros = listOf(cobroEncolado("cobro-dividido", 30_000, createdAt = haceMinutos(5))),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la parte cobrada del dividido volvió a desaparecer (fondo 5000 + parte 250)",
            525_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull(dao.events["local-parte"])
    }

    /**
     * ⚠️ **RESIDUO DECLARADO — dirección SOBRANTE.** El mismo dividido, pero con el
     * server confirmando OTRA venta: el barrido vuelve a correr y la parte de $250, que
     * ningún cobro puede parear, se pierde de la pantalla.
     *
     * Da **$5,200.00** con $5,450.00 en el cajón: sobrante de $250. Se declara y no se
     * arregla a medias — la causa raíz es la misma de siempre (cajón y cola guardan
     * números distintos) y el arreglo vive río arriba.
     */
    @Test
    fun `RESIDUO — el cobro DIVIDIDO sigue perdiendose si el server confirma otra venta`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-parte", "srv-1", "CASH_SALE", 25_000, orderId = null, createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson(
                        "srv-ev-otra", "CASH_SALE", "200.00",
                        localId = "srv-cash-sale:pay-otra", orderId = "order-otra", createdAt = haceMinutos(30),
                    ),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = colaDeCobros(
                cobros = listOf(cobroEncolado("cobro-dividido", 30_000, createdAt = haceMinutos(5))),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "RESIDUO CONOCIDO: la parte del dividido (\$250) no tiene con qué parearse. " +
                "Dirección sobrante (\$5,200 en pantalla con \$5,450 en el cajón)",
            520_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }
}
