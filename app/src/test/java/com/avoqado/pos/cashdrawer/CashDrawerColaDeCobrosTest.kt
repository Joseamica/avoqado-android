package com.avoqado.pos.cashdrawer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 QUÉ COBRO DE LA COLA PUEDE SALVAR UNA VENTA, Y CUÁL NO.
 *
 * La suite hermana ([CashDrawerPendingCashSaleTest]) prueba el PAREO dándole a la
 * caja una lista de cobros ya resuelta. Ésta prueba el escalón de antes: qué llega a
 * esa lista. Aquí el `PendingCashSales` es el de verdad —sólo se ponen a mano las dos
 * colas— porque las decisiones que importan (sólo efectivo, sólo cobros vivos, el
 * total con propina) viven DENTRO del componente: mockearlo las saltaría enteras y el
 * test sólo se probaría a sí mismo.
 *
 * Todas asertan el número que el cajero ve en el arqueo, en centavos.
 */
class CashDrawerColaDeCobrosTest {

    private val abrioHace = haceMinutos(60)

    /** El caso base: el cobro en efectivo encolado SÍ protege su venta. */
    @Test
    fun `un cobro en efectivo encolado protege la venta de mostrador`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = abrioHace),
            ),
            pendingCashSales = colaDeCobros(
                cobros = listOf(cobroEncolado("cobro-1", amountCents = 30_000)),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el cobro en efectivo encolado no protegió su venta (fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    /**
     * 🔴 INTENTO DE ROMPERLO, caso (b): **una tarjeta pendiente del MISMO monto.**
     *
     * Un cobro declarado a mano (terminal ajena, transferencia) también se encola,
     * pero NUNCA entró al cajón: `recordCashSale` se corta en seco cuando hay
     * `manualMethod`. Si protegiera, esos $300 se contarían dos veces —la fila local
     * más la del server— y el cajero cerraría con un faltante inventado.
     *
     * Aquí la venta en efectivo SÍ la tiene el server: el arqueo es fondo + 300, y la
     * copia local se borra.
     */
    @Test
    fun `un cobro con TARJETA pendiente del mismo monto NO salva una venta en efectivo`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "300.00", orderId = "order-9"),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = colaDeCobros(
                cobros = listOf(cobroEncolado("cobro-tarjeta", amountCents = 30_000, method = "CARD")),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "una tarjeta pendiente protegió una venta en efectivo y se contó dos veces " +
                "(fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("la copia local sobrevivió gracias a un cobro que no es efectivo", dao.events["local-venta"])
    }

    /**
     * 🔴 INTENTO DE ROMPERLO, caso (c): **un cobro en CUARENTENA.**
     *
     * `FAILED` es ambiguo: pudo haber aterrizado en el server ("Order is already paid"
     * es un 400 permanente) o no. Ante la duda se conserva el comportamiento de borrar
     * la copia local, que le deja al cajero un SOBRANTE aparente en vez de un faltante
     * — y encima ese cobro ya es visible en cuarentena, así que el gerente puede
     * explicarlo. Espejo exacto de iOS.
     */
    @Test
    fun `un cobro en CUARENTENA no protege la copia local`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "300.00", orderId = "order-9"),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = colaDeCobros(
                cobros = listOf(cobroEncolado("cobro-muerto", amountCents = 30_000, syncStatus = "FAILED")),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "un cobro en cuarentena protegió la copia local y la venta se contó dos veces " +
                "(fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("la copia local sobrevivió a un cobro que ya está en cuarentena", dao.events["local-venta"])
    }

    /**
     * 🔴 EL CRITERIO DE LA PROPINA, leído de la cola de verdad: la fila guarda
     * `amountCents` y `tipCents` por separado y el arqueo suma el TOTAL. Si el pareo
     * mirara sólo la base buscaría 25000 y borraría una fila de 30000 que sí está en
     * el cajón.
     */
    @Test
    fun `el total que protege suma la propina, no sólo la base`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = abrioHace),
            ),
            pendingCashSales = colaDeCobros(
                cobros = listOf(cobroEncolado("cobro-1", amountCents = 25_000, tipCents = 5_000)),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el pareo ignoró la propina (fondo 5000 + venta 250 + propina 50)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - La otra cola: el outbox de intents

    /**
     * El `PAY_CASH` del outbox también protege, y por su MONTO además de por su
     * orden: el importe viaja partido en `amountCents` + `tipCents`, igual que en la
     * otra cola. Aquí la fila del cajón nació sin orden, así que sólo el monto puede
     * salvarla.
     */
    @Test
    fun `el PAY_CASH del outbox protege por monto una fila sin orden`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = abrioHace),
            ),
            pendingCashSales = colaDeCobros(
                intents = listOf(
                    intentPayCash("local-order-9", amountCents = 25_000, tipCents = 5_000),
                ),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el intent encolado no protegió la venta (fondo 5000 + venta 250 + propina 50)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull(dao.events["local-venta"])
    }

    /**
     * Y el intent de un cobro declarado A MANO no protege, por la misma razón que su
     * gemelo de la otra cola: ese dinero nunca entró al cajón. El payload lo delata
     * con su campo `method` — mismo criterio que iOS.
     */
    @Test
    fun `un PAY_CASH declarado a mano no protege ninguna fila`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = "local-order-9"))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "300.00", orderId = "order-9"),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = colaDeCobros(
                intents = listOf(
                    intentPayCash("local-order-9", amountCents = 30_000, method = "CREDIT_CARD"),
                ),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "un cobro con terminal ajena protegió una venta en efectivo (fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("la copia local sobrevivió gracias a un cobro que no entró al cajón", dao.events["local-venta"])
    }

    /**
     * 🔴 EL CONTRAPESO DE ESTA SUITE: con las dos colas VACÍAS, el barrido se comporta
     * como siempre y la copia local se borra. Sin esto, todos los tests de arriba
     * podrían estar pasando por accidente sobre un barrido que ya no borra nada.
     */
    @Test
    fun `sin nada en las colas la copia local se borra como siempre`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "300.00", orderId = "order-9"),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = colaDeCobros(),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la venta se contó dos veces (fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("la copia local sobrevivió sin que nada la protegiera", dao.events["local-venta"])
    }
}
