package com.avoqado.pos.cashdrawer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 LA VENTA QUE DESAPARECE DE LA PANTALLA JUSTO AL CERRAR LA CAJA.
 *
 * `CASH_SALE` es un tipo del que el SERVER es dueño: lo crea él al cobrar
 * (`shared/cashDrawerPosting.postCashSaleToDrawer`) y el POS sólo guarda una copia
 * provisional para no quedarse mudo entre el cobro y el siguiente sync. Por eso, en
 * cuanto el sync trae la lista confirmada, las copias locales se borran — si no, la
 * MISMA venta sumaría dos veces.
 *
 * El agujero: una venta cobrada **sin red** todavía no existe en el server. Su cobro
 * vive en una cola (intent `PAY_CASH` del outbox, o `pending_payments`) esperando
 * reproducirse. Si en esa ventana alguien abre la pantalla de caja —que es
 * exactamente lo que hace el cajero para cerrar su turno, y lo que dispara
 * `syncFromApi()`— la copia local se borraba por "no confirmada" y al cajero le
 * desaparecían de la pantalla los $300 que sí están en el cajón.
 *
 * Es transitorio y se cura solo… siempre que la cola avance. No siempre avanza: un
 * `RETRY` (VERSION_CONFLICT) o un rechazo por delante bloquean el FIFO, y mientras
 * tanto el arqueo dice menos de lo que hay. El cajero cuenta, le sobra dinero que
 * nadie le explica, y deja de creerle al número. Ése es el daño real.
 *
 * 🔴 **La señal es real, no una inferencia:** el cobro está literalmente PENDIENTE en
 * la cola, nombrado con el MISMO `orderId` que lleva la fila del cajón. Mientras esté
 * ahí, el gemelo del server no existe **por construcción** — así que conservar la
 * copia local no puede duplicar nada. En cuanto se reproduce, la protección se suelta
 * sola y el siguiente sync borra la copia como siempre.
 *
 * Residuo asumido y dicho en voz alta: una venta de mostrador cobrada sin red **antes
 * de que existiera la orden** entra al cajón con `orderId = null` (ver
 * `PaymentFlowViewModel`, los `recordCashSale(total, null)`). Ésa no tiene con qué
 * emparejarse y se sigue borrando como hoy. Es la dirección MENOS dañina de las dos:
 * al cajero le sobra dinero, no le falta — nadie lo acusa de un faltante que no hizo.
 */
class CashDrawerPendingCashSaleTest {

    private val abrioHace = haceMinutos(60)

    /**
     * 🔴 EL NÚMERO QUE VE EL CAJERO. Venta de $300 cobrada sin red, cobro todavía en
     * la cola. El arqueo tiene que decir $5,300.00 — que es lo que hay en el cajón.
     * Antes decía $5,000.00 y le sobraban $300 sin explicación.
     */
    @Test
    fun `la venta cuyo cobro sigue en la cola NO desaparece del arqueo`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = "local-order-9"),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                // El server confirma su apertura y NADA más: la venta no le ha llegado.
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = abrioHace),
            ),
            pendingCashSales = cobrosEnCola("local-order-9"),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "al cajero le desaparecieron del arqueo los \$300 que sí están en el cajón " +
                "(fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull("se borró la fila de una venta cuyo cobro sigue encolado", dao.events["local-venta"])
    }

    /**
     * El contrapeso, y la razón por la que el borrado existe: en cuanto el cobro se
     * reprodujo, el server tiene SU `CASH_SALE` y la copia local es la misma venta con
     * otro id. Conservarla contaría $300 de más y le inventaría al cajero un FALTANTE
     * — la dirección que sí acusa a alguien.
     */
    @Test
    fun `la venta YA reproducida se borra y no se cuenta dos veces`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = "order-9"),
        )

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
            // La cola ya está vacía: el cobro se reprodujo.
            pendingCashSales = sinCobrosEnCola(),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la venta se contó dos veces y el cajero cierra con un faltante inventado " +
                "(fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("la copia local sobrevivió a su confirmación", dao.events["local-venta"])
    }

    /**
     * 🔴 EL CASO QUE DE VERDAD DISTINGUE UN GUARD BUENO DE UNO PEREZOSO: en el mismo
     * sync conviven una venta ya reproducida y otra todavía encolada. Un guard que
     * apague el borrado "porque hay algo pendiente" duplicaría la primera; uno que
     * borre todo perdería la segunda. Sólo el pareo por `orderId` acierta en las dos.
     *
     * fondo $5,000 + venta vieja $200 (una vez) + venta encolada $300 = **$5,500.00**
     */
    @Test
    fun `en el mismo sync conviven la venta reproducida y la encolada, cada una contada UNA vez`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta-vieja", "srv-1", "CASH_SALE", 20_000, orderId = "order-vieja", createdAt = haceMinutos(40)),
        )
        dao.insertEvent(
            eventoLocal("local-venta-nueva", "srv-1", "CASH_SALE", 30_000, orderId = "local-order-nueva", createdAt = haceMinutos(5)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-vieja", "CASH_SALE", "200.00", orderId = "order-vieja", createdAt = haceMinutos(40)),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola("local-order-nueva"),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el arqueo no cuadra (fondo 5000 + vieja 200 + encolada 300)",
            550_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull("la copia local de la venta ya confirmada sobrevivió", dao.events["local-venta-vieja"])
        assertNotNull("la venta encolada se borró del arqueo", dao.events["local-venta-nueva"])
    }

    /**
     * El `OPEN` provisional del POS lleva `orderId = null` y tiene que seguir
     * borrándose cuando llega el del server: si no, la apertura se pinta dos veces en
     * el detalle del corte. El guard nuevo NO puede volverse una excusa para dejar de
     * limpiar lo que sí está confirmado.
     */
    @Test
    fun `la apertura provisional se sigue borrando aunque haya cobros en la cola`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-ev-open", "srv-1", "OPEN", FONDO_CENTS, createdAt = abrioHace))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = abrioHace),
            ),
            pendingCashSales = cobrosEnCola("local-order-9"),
        )
        repo.syncFromApi()

        assertNull("la apertura provisional quedó duplicada con la del server", dao.events["local-ev-open"])
        assertNotNull(dao.events["srv-ev-open"])
    }
}
