package com.avoqado.pos.cashdrawer

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
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
 * **A. El movimiento cuya respuesta se perdió se contaba dos veces.** Un
 * `PAY_IN`/`PAY_OUT` que el server SÍ aplicó pero cuya respuesta no llegó (WiFi caído
 * a media respuesta, server reiniciado) se queda en Room con su id local: la limpieza
 * por tipo excluye esos dos A PROPÓSITO —un retiro sin red tiene que sobrevivir— y
 * `promoteEvent` al escribir no llegó a correr porque no hubo respuesta que leer. El
 * eco del sync entraba entonces como una fila NUEVA y el mismo movimiento contaba dos
 * veces. Medido: un PAY_IN de $100 deja el esperado en **$5,330.00 en vez de
 * $5,230.00 — +$100**, el tamaño exacto del movimiento. Con un PAY_OUT el error va al
 * otro lado: faltante inventado. Era PERMANENTE.
 *
 * La llave ya existía en la base del server (`CashDrawerEvent.localId`, con
 * `@@unique([venueId, localId])`) y desde hoy sale en `formatEvent`. Pero el server
 * sólo puede devolver una llave que alguien le haya dado: `payIn`/`payOut` creaban la
 * fila **sin** `localId` porque el POS nunca lo mandaba. Por eso el contrato tiene DOS
 * mitades y las dos se prueban aquí — el POS manda la llave al registrar (A0), y el
 * sync fusiona con ella (A). Con las dos puestas la fusión es exacta: `localId` que
 * reconozco → es MÍO, mi fila adopta el id del server (UNA fila); `localId`
 * desconocido o nulo → es de otro aparato o lo escribió el server, entra por su id.
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

    // MARK: - A0. La llave tiene que SALIR del POS, o nada de lo demás existe

    /**
     * 🔴 LA TUBERÍA SIN AGUA. La fusión por llave de abajo sólo puede encenderse si
     * el POS **manda** la llave al registrar el movimiento: `POST /cash-drawer/pay-in`
     * es lo único que crea un `PAY_IN` en el server, y hasta hoy su cuerpo era
     * `{amount, note}` a secas. Sin `localId` en ESE cuerpo, la fila del server nace
     * sin llave y ninguna cantidad de código de fusión puede reconocerla después.
     *
     * El valor no se inventa aquí: es el MISMO id con el que la fila quedó en Room.
     * Si se generara uno nuevo dejaría de ser una llave de idempotencia — dos
     * reintentos del mismo movimiento producirían dos llaves y el server los
     * insertaría dos veces.
     */
    @Test
    fun `el pay-in manda al server la MISMA llave con la que quedo en Room`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = haceMinutos(60)))
        val enElCable = mutableListOf<LlamadaCapturada>()
        // Sin ruta configurada para /pay-in: la llamada SALE y luego se cae, que es
        // justo el caso donde la llave hace falta (el server aplicó, se perdió la
        // respuesta). El cuerpo se captura igual.
        val repo = cashDrawerRepo(dao, cashDrawerClient(capturadas = enElCable))

        repo.addPayIn(PAY_IN_CENTS, "Fondo extra")

        val filaEnRoom = dao.events.values.single { it.type == "PAY_IN" }
        val cuerpo = enElCable.single { it.path.endsWith("/cash-drawer/pay-in") }.body
        assertEquals(
            "el pay-in salió sin `localId`: la fila del server nace sin llave y la fusión nunca podrá reconocerla",
            filaEnRoom.id,
            llaveDe(cuerpo),
        )
    }

    /** El mismo contrato para el retiro: el que se lleva dinero del cajón. */
    @Test
    fun `el pay-out manda al server la MISMA llave con la que quedo en Room`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal("srv-1", openedAt = haceMinutos(60)))
        val enElCable = mutableListOf<LlamadaCapturada>()
        val repo = cashDrawerRepo(dao, cashDrawerClient(capturadas = enElCable))

        repo.addPayOut(8_000, "Compra de hielo")

        val filaEnRoom = dao.events.values.single { it.type == "PAY_OUT" }
        val cuerpo = enElCable.single { it.path.endsWith("/cash-drawer/pay-out") }.body
        assertEquals(
            "el pay-out salió sin `localId`: la fila del server nace sin llave",
            filaEnRoom.id,
            llaveDe(cuerpo),
        )
    }

    /**
     * 🔴 EL CICLO COMPLETO, que es lo único que demuestra que las dos mitades encajan:
     * el POS registra el movimiento y **se pierde la respuesta** (el server SÍ lo
     * aplicó); la fila local se queda con su id; el siguiente sync trae el gemelo del
     * server con esa MISMA llave y las dos filas se vuelven una.
     *
     * Sin el ciclo entero, cada mitad se ve bien por separado y el dinero igual se
     * cuenta dos veces: $5,330.00 donde el cajero debe leer $5,230.00.
     */
    @Test
    fun `un pay-in cuya respuesta se perdio se fusiona por su llave y cuenta UNA vez`() = runTest {
        val dao = FakeCashDrawerDao()
        val abrioHace = haceMinutos(60)
        dao.insertSession(sesionLocal("srv-1", openedAt = abrioHace))

        // 1) El POS registra el pay-in; la respuesta nunca llega.
        val enElCable = mutableListOf<LlamadaCapturada>()
        cashDrawerRepo(dao, cashDrawerClient(capturadas = enElCable))
            .addPayIn(PAY_IN_CENTS, "Fondo extra")
        val llave = llaveDe(enElCable.single { it.path.endsWith("/cash-drawer/pay-in") }.body)

        // 2) El sync trae el gemelo del server, que devuelve ESA llave.
        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "280.00"),
                    eventoJson("srv-ev-reembolso", "PAY_OUT", "150.00", note = "Reembolso: Producto defectuoso"),
                    eventoJson("srv-ev-payin", "PAY_IN", "100.00", note = "Fondo extra", localId = llave),
                    openedAt = abrioHace,
                ),
            ),
        )
        repo.syncFromApi()

        val sesion = repo.getOpenSession()!!
        assertEquals(
            "el pay-in se contó dos veces (fondo 5000 + venta 280 − reembolso 150 + pay-in 100)",
            523_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
        assertEquals("quedó más de una fila del mismo pay-in", 1, dao.events.values.count { it.type == "PAY_IN" })
    }

    // MARK: - A. La fusión por llave, vista desde el sync

    /**
     * 🔴 EL NÚMERO QUE VE EL CAJERO. El PAY_IN de $100 vive en Room con el id local
     * que el POS ya le mandó al server como llave; el server lo devuelve con SU id y
     * esa llave. Si el cliente no la usa, suma los dos y el arqueo dice $5,330.
     */
    @Test
    fun `un PAY_IN cuya llave reconoce el server deja de contarse dos veces`() = runTest {
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
            "el pay-in se contó dos veces (fondo 5000 + venta 280 − reembolso 150 + pay-in 100)",
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
     * 🔴 DEGRADACIÓN OBLIGATORIA **y el residuo que hay que decir en voz alta.**
     *
     * Un gemelo del server SIN llave llega por dos caminos distintos, y el cliente
     * tiene que comportarse igual en los dos:
     *  1. **Server viejo** que todavía no expone `localId` en `formatEvent`.
     *  2. **Fila heredada:** un `PAY_IN`/`PAY_OUT` escrito por una versión ANTERIOR de
     *     esta app. Su gemelo del server se creó sin llave y **ninguna versión futura
     *     puede inventársela**: para esas filas la fusión por llave no puede servir
     *     nunca. Siguen contando dos veces hasta que se cierre la caja en la que
     *     viven — el arqueo es por sesión, así que el residuo muere en el siguiente
     *     corte y no se arrastra al turno que sigue.
     *
     * ⚠️ Los $5,330.00 de este test NO son el número correcto — son el número de HOY.
     * Se fija a propósito: lo que no se puede permitir es que la ausencia de la llave
     * cambie el comportamiento en la dirección PELIGROSA, o sea que el cliente borre
     * la fila local que el server aún no reconoce. Un retiro sin red borrado le
     * inventaría al cajero un faltante que sí existe físicamente; contar de más se
     * corrige solo en cuanto el movimiento nace ya con llave.
     */
    @Test
    fun `un gemelo sin llave -server viejo o fila heredada- no borra la fila local`() = runTest {
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
     * La llave tal como viajó en el cuerpo del POST. Se lee del JSON crudo a
     * propósito: es un CONTRATO de cable, y leerlo con el mismo modelo que lo
     * serializa no probaría nada.
     */
    private fun llaveDe(cuerpo: String): String? =
        (kotlinx.serialization.json.Json.parseToJsonElement(cuerpo) as kotlinx.serialization.json.JsonObject)["localId"]
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.contentOrNull

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
