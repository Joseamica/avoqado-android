package com.avoqado.pos.cashdrawer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 FUSIONAR POR LLAVE, NO POR INFERENCIA — y qué caja es "la de hoy".
 *
 * Ayer la caja dejó de abrirse dos veces (la sesión adopta el id del server), pero
 * quedaron DOS agujeros de dinero con la misma raíz: el cliente adivinaba qué evento
 * del server era suyo, porque el server guardaba la llave y no la devolvía.
 *
 * **A. El movimiento heredado se contaba dos veces.** Un `PAY_IN`/`PAY_OUT` que ya
 * estaba en Room antes de actualizar la app no lo alcanza nada: la limpieza por tipo
 * los excluye A PROPÓSITO (un retiro sin red tiene que sobrevivir) y `promoteEvent`
 * sólo corre al ESCRIBIR el evento. Así que el eco del sync entraba como una fila
 * NUEVA y el mismo movimiento contaba dos veces. Medido: un PAY_IN de $100 heredado
 * deja el esperado en **$5,330.00 en vez de $5,230.00 — +$100**, el tamaño exacto del
 * movimiento. Con un PAY_OUT el error va al otro lado: faltante inventado. Era
 * PERMANENTE.
 *
 * La llave ya existía en la base del server (`CashDrawerEvent.localId`, con
 * `@@unique([venueId, localId])`) y desde hoy sale en `formatEvent`. Con ella la
 * fusión es exacta: `localId` que reconozco → es MÍO, mi fila adopta el id del
 * server (UNA fila); `localId` desconocido o nulo → es de otro aparato o lo escribió
 * el server, entra por su id.
 *
 * **B. La caja de ayer contaminaba la de hoy.** La promoción se llevaba TODOS los
 * eventos de cualquier otra sesión abierta, incluida una de un turno anterior que
 * este aparato nunca vio cerrar. El `CASH_SALE` de ayer sí lo borraba la limpieza por
 * tipo, pero **el retiro a mano de ayer se colaba**: medido, **$5,050.00 en vez de
 * $5,130.00 — −$80**, un sobrante inventado del tamaño del retiro de ayer.
 *
 * Los números son los del caso real:
 *   fondo $5,000 + venta $280 − reembolso $150 = **$5,130.00**
 *   … y con el pay-in heredado de $100 = **$5,230.00**
 */
class CashDrawerEventKeyMergeTest {

    // MARK: - A. El movimiento heredado y su llave

    /**
     * 🔴 EL NÚMERO QUE VE EL CAJERO. El PAY_IN de $100 vive en Room con un id local
     * desde antes del upgrade; el server lo devuelve con SU id y la llave que lo
     * identifica. Si el cliente no la usa, suma los dos y el arqueo dice $5,330.
     */
    @Test
    fun `un PAY_IN heredado deja de contarse dos veces cuando el server manda su llave`() = runTest {
        val dao = FakeCashDrawerDao()
        val abrioHace = haceMinutos(60)
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-ev-payin", "srv-1", "PAY_IN", PAY_IN_CENTS, note = "Fondo extra"))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "280.00"),
                    eventoJson("srv-ev-reembolso", "PAY_OUT", "150.00", note = "Reembolso: Producto defectuoso"),
                    eventoJson("srv-ev-payin", "PAY_IN", "100.00", note = "Fondo extra", localId = "local-ev-payin"),
                    openedAt = abrioHace,
                ),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el pay-in heredado se contó dos veces (fondo 5000 + venta 280 − reembolso 150 + pay-in 100)",
            523_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    /**
     * El mismo caso visto desde la base: no basta con que la suma cuadre, tiene que
     * quedar UNA sola fila — y con el id del server, que es como la volverá a nombrar
     * el próximo sync. Dos filas que "casualmente" se compensen volverían a divergir.
     */
    @Test
    fun `la fusion por llave deja UNA sola fila del movimiento, con el id del server`() = runTest {
        val dao = FakeCashDrawerDao()
        val abrioHace = haceMinutos(60)
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-ev-payin", "srv-1", "PAY_IN", PAY_IN_CENTS, note = "Fondo extra"))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-payin", "PAY_IN", "100.00", note = "Fondo extra", localId = "local-ev-payin"),
                    openedAt = abrioHace,
                ),
            ),
        )
        repo.syncFromApi()

        val payIns = dao.events.values.filter { it.type == "PAY_IN" }
        assertEquals("quedó más de una fila del mismo pay-in: ${payIns.map { it.id }}", 1, payIns.size)
        assertEquals("srv-ev-payin", payIns.first().id)
        assertEquals("srv-1", payIns.first().sessionId)
    }

    /**
     * 🔴 DEGRADACIÓN OBLIGATORIA. Un server viejo no manda `localId`, y entonces el
     * cliente tiene que comportarse EXACTAMENTE como antes: la llave mejora la
     * fusión, no es requisito para funcionar.
     *
     * ⚠️ Los $5,330.00 de este test NO son el número correcto — son el número de HOY,
     * el defecto A todavía vivo. Se fija a propósito: lo que no se puede permitir es
     * que la ausencia de la llave cambie el comportamiento en la dirección PELIGROSA,
     * o sea que el cliente borre la fila local que el server aún no reconoce. Un
     * retiro sin red borrado le inventaría al cajero un faltante que sí existe
     * físicamente; contar de más se corrige solo en cuanto el server manda la llave.
     */
    @Test
    fun `un server VIEJO sin llave se comporta identico a hoy y no borra la fila local`() = runTest {
        val dao = FakeCashDrawerDao()
        val abrioHace = haceMinutos(60)
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))
        dao.insertEvent(eventoLocal("local-ev-payin", "srv-1", "PAY_IN", PAY_IN_CENTS, note = "Fondo extra"))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    eventoJsonSinLlave("srv-ev-open", "OPEN", "5000.00", note = "Caja abierta", createdAt = abrioHace),
                    eventoJsonSinLlave("srv-ev-venta", "CASH_SALE", "280.00"),
                    eventoJsonSinLlave("srv-ev-reembolso", "PAY_OUT", "150.00", note = "Reembolso"),
                    eventoJsonSinLlave("srv-ev-payin", "PAY_IN", "100.00", note = "Fondo extra"),
                    openedAt = abrioHace,
                ),
            ),
        )
        repo.syncFromApi()

        assertNotNull(
            "el server sin llave borró la fila local: un retiro sin red desaparecería igual",
            dao.events["local-ev-payin"],
        )
        val sesion = repo.getOpenSession()!!
        assertEquals(
            "sin `localId` el comportamiento cambió respecto al de hoy",
            533_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - B. La caja de un turno anterior

    /**
     * 🔴 EL NÚMERO QUE VE EL CAJERO. Este aparato se quedó con la caja de ayer
     * abierta —nunca la vio cerrar— y hoy sincroniza la de hoy. El retiro a mano de
     * ayer ($80) NO puede aterrizar en el arqueo de hoy: sería un sobrante inventado
     * de $80 que el cajero no tiene forma de explicar.
     *
     * La cota es la ventana de la caja del server (`openedAt`), que es la MISMA con
     * la que el server calcula su esperado. Cliente y server no pueden divergir.
     */
    @Test
    fun `la caja de un turno anterior no contamina el arqueo de hoy`() = runTest {
        val dao = FakeCashDrawerDao()
        val hoyAbrioHace = haceMinutos(60)
        prepararCajaDeAyer(dao)

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "280.00"),
                    eventoJson("srv-ev-reembolso", "PAY_OUT", "150.00", note = "Reembolso: Producto defectuoso"),
                    openedAt = hoyAbrioHace,
                ),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals("el sync leyó la caja equivocada", "srv-1", sesion.id)
        assertEquals(
            "el retiro de ayer se coló al arqueo de hoy (fondo 5000 + venta 280 − reembolso 150)",
            513_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    /**
     * El contrapeso: no contaminar NO puede significar destruir. El retiro de ayer es
     * dinero que salió de verdad; sigue existiendo y sigue colgado de la caja de
     * ayer, que es donde el server también lo tiene.
     */
    @Test
    fun `el retiro del turno anterior sigue existiendo, colgado de su propia caja`() = runTest {
        val dao = FakeCashDrawerDao()
        prepararCajaDeAyer(dao)

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer, openedAt = haceMinutos(60)),
            ),
        )
        repo.syncFromApi()

        val retiroDeAyer = dao.events["ayer-ev-retiro"]
        assertNotNull("el retiro de ayer se borró: eso es destruir dinero registrado", retiroDeAyer)
        assertEquals("ayer-uuid", retiroDeAyer!!.sessionId)
        assertTrue("la caja de ayer se borró con todo y sus movimientos", dao.sessions.containsKey("ayer-uuid"))
    }

    /**
     * La precedencia, cuando las dos reglas hablan del mismo movimiento: **la llave
     * gana sobre la ventana**. Si el server dice "este evento de MI caja es el tuyo",
     * es un hecho, no una inferencia — aunque mi fila esté mal archivada en la caja
     * vieja. Al revés perderíamos un movimiento que el server sí está contando.
     */
    @Test
    fun `un movimiento que el server reconoce como mio se absorbe aunque este archivado en la caja vieja`() = runTest {
        val dao = FakeCashDrawerDao()
        prepararCajaDeAyer(dao)
        dao.insertEvent(
            eventoLocal("ayer-ev-retiro-90", "ayer-uuid", "PAY_OUT", 9_000, note = "Gasolina", createdAt = haceMinutos(25 * 60)),
        )

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "280.00"),
                    eventoJson("srv-ev-reembolso", "PAY_OUT", "150.00", note = "Reembolso: Producto defectuoso"),
                    eventoJson("srv-ev-gasolina", "PAY_OUT", "90.00", note = "Gasolina", localId = "ayer-ev-retiro-90"),
                    openedAt = haceMinutos(60),
                ),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el movimiento confirmado por llave no se absorbió, o se contó dos veces",
            504_000, // 5000 + 280 − 150 − 90
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertTrue("quedó la fila local además de la del server", !dao.events.containsKey("ayer-ev-retiro-90"))
    }

    /**
     * La caja de ayer con TODO su contenido: fondo, una venta y un retiro a mano de
     * $80 — el que se colaba al turno de hoy.
     */
    private suspend fun prepararCajaDeAyer(dao: FakeCashDrawerDao) {
        val ayer = haceMinutos(26 * 60)
        dao.insertSession(sesionLocal("ayer-uuid", openedAt = ayer))
        dao.insertEvent(eventoLocal("ayer-ev-open", "ayer-uuid", "OPEN", FONDO_CENTS, createdAt = ayer))
        dao.insertEvent(eventoLocal("ayer-ev-venta", "ayer-uuid", "CASH_SALE", 50_000, orderId = "order-ayer", createdAt = haceMinutos(25 * 60)))
        dao.insertEvent(eventoLocal("ayer-ev-retiro", "ayer-uuid", "PAY_OUT", 8_000, note = "Compra de hielo", createdAt = haceMinutos(25 * 60)))
    }
}
