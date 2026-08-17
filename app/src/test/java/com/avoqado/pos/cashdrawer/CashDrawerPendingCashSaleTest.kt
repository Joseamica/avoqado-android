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
 * 🔴 **El agujero que dejaba abierto el pareo por `orderId` solo, y que estos tests
 * cierran:** el flujo MÁS común de una tienda en apagón —cobrar de mostrador sin red—
 * entra al cajón con `orderId = null` (ver `PaymentFlowViewModel`, los cuatro
 * `recordCashSale(total, null)` de las líneas 935, 1219, 1256 y 1285). Una fila sin
 * orden no aparecía en el conjunto de órdenes pendientes, así que no se protegía y se
 * borraba **aunque su `PAY_CASH` siguiera esperando en el outbox**. Medido: 500000
 * donde el cajón tiene 530000.
 *
 * La salida es la de iOS (`PendingCashSales.swift`, commit `f85f4c6`): esas filas se
 * parean por **MONTO TOTAL**, de la más reciente hacia atrás. Cada cobro pendiente
 * protege UNA fila y sólo una — por eso es una lista y no un conjunto: proteger de más
 * reintroduce el doble conteo que el barrido vino a evitar.
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
            pendingCashSales = cobrosEnCola(cobroDeOrden("local-order-9", 30_000)),
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
            pendingCashSales = cobrosEnCola(cobroDeOrden("local-order-nueva", 30_000)),
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
            pendingCashSales = cobrosEnCola(cobroDeOrden("local-order-9", 30_000)),
        )
        repo.syncFromApi()

        assertNull("la apertura provisional quedó duplicada con la del server", dao.events["local-ev-open"])
        assertNotNull(dao.events["srv-ev-open"])
    }

    // MARK: - La venta de MOSTRADOR, que no tiene orden con que nombrarse

    /**
     * 🔴 EL DEFECTO MEDIDO. Venta de mostrador de $300 cobrada SIN RED: la orden aún
     * no existe, así que la fila del cajón nace con `orderId = null` y su `PAY_CASH`
     * se queda esperando en el outbox. El arqueo tiene que decir $5,300.00 — que es lo
     * que hay en el cajón.
     *
     * Antes decía **$5,000.00**: el pareo por `orderId` no tenía con qué emparejar una
     * fila sin orden, así que la borraba. Es el flujo más común de una tienda en un
     * apagón, y cubre los cuatro `recordCashSale(total, null)` de
     * `PaymentFlowViewModel` (:935, :1219, :1256, :1285).
     */
    @Test
    fun `la venta de MOSTRADOR cobrada sin red NO desaparece del arqueo`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null),
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
            "al cajero le desaparecieron del arqueo los \$300 de mostrador que sí están " +
                "en el cajón (fondo 5000 + venta 300)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull(
            "se borró la venta de mostrador aunque su cobro sigue encolado",
            dao.events["local-venta"],
        )
    }

    /**
     * 🔴 EL INTENTO DE ROMPER EL PAREO POR MONTO, caso (a): **dos ventas de $300**, una
     * ya reproducida (el server la confirma con su propia fila) y otra todavía
     * encolada. El pareo por monto es un multiconjunto: hay UN cobro pendiente, así que
     * protege UNA fila — la más reciente, que es la que sigue esperando.
     *
     * fondo $5,000 + la del server $300 + la encolada $300 = **$5,600.00**.
     * Proteger las dos daría $5,900 e inventaría un faltante; no proteger ninguna daría
     * $5,300 e inventaría un sobrante.
     */
    @Test
    fun `dos ventas del MISMO monto, se borra la reproducida y sobrevive la encolada`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(
            eventoLocal(
                "local-venta-vieja", "srv-1", "CASH_SALE", 30_000,
                orderId = "order-A", createdAt = haceMinutos(40),
            ),
        )
        dao.insertEvent(
            eventoLocal(
                "local-venta-nueva", "srv-1", "CASH_SALE", 30_000,
                orderId = null, createdAt = haceMinutos(5),
            ),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-A", "CASH_SALE", "300.00", orderId = "order-A", createdAt = haceMinutos(40)),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola(cobroSinOrden(30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el arqueo no cuadra (fondo 5000 + la del server 300 + la encolada 300)",
            560_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNull(
            "la copia local de la venta YA reproducida sobrevivió y se contó dos veces",
            dao.events["local-venta-vieja"],
        )
        assertNotNull(
            "la venta encolada se borró: el pareo se quedó con la más vieja",
            dao.events["local-venta-nueva"],
        )
    }

    /**
     * 🔴 EL CONTRAPESO DEL PAREO POR MONTO: **un cobro pendiente protege UNA fila, no
     * todas las que compartan su monto.** Dos ventas de $300 sin orden, un solo cobro
     * en la cola: la otra ya la confirmó el server y tiene que borrarse.
     *
     * Si el pareo usara un CONJUNTO de montos en vez de una lista, protegería las dos y
     * el cajero cerraría con un faltante inventado de $300 — exactamente el defecto que
     * el barrido existe para evitar.
     */
    @Test
    fun `un solo cobro encolado no puede proteger DOS ventas del mismo monto`() = runTest {
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
                    eventoJson("srv-ev-A", "CASH_SALE", "300.00", orderId = "order-A", createdAt = haceMinutos(40)),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola(cobroSinOrden(30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "una sola venta encolada protegió DOS filas y el cajero cierra con un " +
                "faltante inventado (fondo 5000 + la del server 300 + la encolada 300)",
            560_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    /**
     * 🔴 LA ORDEN GANA SOBRE EL MONTO, y aquí las dos apuntan a filas DISTINTAS.
     *
     * El monto es una heurística; la orden es un hecho. Usar la heurística donde hay
     * identidad sería degradarse a propósito — y ésta es la divergencia deliberada
     * contra iOS, que parea TODO por monto porque nunca tuvo la pasada por orden.
     *
     * El caso donde se nota: un cobro DIVIDIDO. El cajón registra la parte que se
     * cobró ($250) pero la cola guarda el total del carrito ($300), así que los dos
     * números no coinciden. Con identidad se protege la fila correcta —la del cobro
     * encolado— y el arqueo dice fondo $5,000 + la del server $300 + la encolada $250
     * = **$5,550.00**. Pareando sólo por monto se protegería la fila de $300, que el
     * server YA tiene, y saldrían $5,600: $50 de más y la venta encolada perdida.
     */
    @Test
    fun `la ORDEN gana sobre el monto cuando apuntan a filas distintas`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        // Su cobro sigue en la cola: es la que hay que salvar.
        dao.insertEvent(
            eventoLocal(
                "local-venta-orden", "srv-1", "CASH_SALE", 25_000,
                orderId = "local-order-9", createdAt = haceMinutos(40),
            ),
        )
        // Ésta ya se reprodujo y el server la confirma con su propia fila.
        dao.insertEvent(
            eventoLocal(
                "local-venta-mostrador", "srv-1", "CASH_SALE", 30_000,
                orderId = null, createdAt = haceMinutos(5),
            ),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-Z", "CASH_SALE", "300.00", orderId = "order-Z", createdAt = haceMinutos(5)),
                    openedAt = abrioHace,
                ),
            ),
            pendingCashSales = cobrosEnCola(cobroDeOrden("local-order-9", 30_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el pareo protegió por monto la fila que el server YA tiene y perdió la " +
                "encolada (fondo 5000 + la del server 300 + la encolada 250)",
            555_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertNotNull("se borró la venta cuyo cobro sigue encolado", dao.events["local-venta-orden"])
        assertNull("sobrevivió la copia de una venta que el server ya confirmó", dao.events["local-venta-mostrador"])
    }

    /**
     * 🔴 EL CRITERIO DE LA PROPINA, fijado con un test porque las dos puntas tienen que
     * escribir el MISMO número o el pareo nunca acierta.
     *
     * El arqueo suma el total CON propina: `recordCashSale(total, …)` se llama con
     * `currentBaseAmount() + currentTipCents` (`PaymentFlowViewModel`). Y la cola
     * guarda `amountCents` y `tipCents` por separado, así que el monto que parea es la
     * SUMA. Una venta de $250 + $50 de propina protege una fila de $300, no de $250.
     */
    @Test
    fun `el monto que parea INCLUYE la propina`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        // $250 de venta + $50 de propina = los $300 que entraron al cajón.
        dao.insertEvent(eventoLocal("local-venta", "srv-1", "CASH_SALE", 30_000, orderId = null))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = abrioHace),
            ),
            // El cobro encolado trae 25000 + 5000: si el pareo mirara sólo la base,
            // buscaría 25000 y esta fila de 30000 se borraría.
            pendingCashSales = cobrosEnCola(cobroSinOrden(25_000 + 5_000)),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el pareo ignoró la propina y borró una venta que sí está en el cajón " +
                "(fondo 5000 + venta 250 + propina 50)",
            530_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }
}
