package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerDao
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * 🔴 EL ID DE LA CAJA: la tablet lo INVENTA, y el del server nunca lo adopta.
 *
 * Medido con sqlite3 sobre los datos reales (2026-08-16): `openSession()` crea la
 * sesión con un `UUID.randomUUID()` y el sync inserta DESPUÉS otra sesión OPEN con
 * el id del server. Room queda con DOS cajas abiertas del mismo local, y
 * `getOpenSession` —`LIMIT 1` sin `ORDER BY`— devuelve siempre la local. Como
 * `computeExpectedAmount` suma POR sessionId, los movimientos que manda el server
 * (el reembolso, entre ellos) caen en la sesión que nadie lee:
 *
 *   - la tablet que ABRIÓ la caja  → no ve el PAY_OUT del reembolso → sobra $150
 *   - la tablet que NO la abrió    → ve su CASH_SALE local Y el del server → sobra la venta
 *
 * O sea: el server ya resta bien el reembolso (`08a3fe6f`), pero en la pantalla del
 * cajero el sobrante no se movió ni un peso.
 *
 * El arreglo es el patrón que este repo YA usa para las órdenes offline: la fila
 * local nace con un id provisional y **se promueve** al id real cuando el server
 * confirma (`TableSession.promoteProvisional`, `localOrderId → orderId`). Aquí es lo
 * mismo, sólo que la promoción vive en Room: la sesión adopta el id del server y sus
 * eventos se mudan con ella.
 *
 * Los números de estos tests son los del caso real:
 *   fondo $5,000.00 · venta en efectivo $280.00 · reembolso $150.00 → esperado $5,130.00
 */
class CashDrawerSessionPromotionTest {

    private val venueId = "venue-1"

    private val fondoCents = 500_000 // $5,000.00
    private val ventaCents = 28_000 // $280.00
    private val reembolsoCents = 15_000 // $150.00

    // MARK: - Fake DAO (semántica de Room/SQLite, a propósito hostil)

    /**
     * Réplica en memoria del DAO real. Dos detalles NO son cosméticos:
     *
     * 1. `getOpenSession` devuelve la PRIMERA fila abierta en orden de inserción —
     *    es lo que hace hoy `LIMIT 1` sin `ORDER BY` sobre el rowid.
     * 2. `insertSession/insertEvent` son `INSERT OR REPLACE`: SQLite BORRA la fila
     *    vieja y mete una nueva, o sea que reemplazar **manda la fila al final**.
     *
     * Así, un test que pase aquí no puede estar apoyándose en que Room "casualmente"
     * devuelva la fila buena.
     */
    private class FakeCashDrawerDao : CashDrawerDao {
        val sessions = LinkedHashMap<String, CashDrawerSessionEntity>()
        val events = LinkedHashMap<String, CashDrawerEventEntity>()

        override suspend fun getOpenSession(venueId: String): CashDrawerSessionEntity? =
            sessions.values.firstOrNull { it.venueId == venueId && it.status == "OPEN" }

        override suspend fun getOpenSessions(venueId: String): List<CashDrawerSessionEntity> =
            sessions.values.filter { it.venueId == venueId && it.status == "OPEN" }

        override suspend fun insertSession(session: CashDrawerSessionEntity) {
            sessions.remove(session.id)
            sessions[session.id] = session
        }

        override suspend fun updateSession(session: CashDrawerSessionEntity) {
            if (sessions.containsKey(session.id)) sessions[session.id] = session
        }

        override suspend fun deleteSession(sessionId: String) {
            sessions.remove(sessionId)
        }

        override suspend fun getClosedSessions(venueId: String): List<CashDrawerSessionEntity> =
            sessions.values.filter { it.venueId == venueId && it.status == "CLOSED" }

        override suspend fun getSessionEvents(sessionId: String): List<CashDrawerEventEntity> =
            events.values.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

        override suspend fun getEvent(eventId: String): CashDrawerEventEntity? = events[eventId]

        override suspend fun insertEvent(event: CashDrawerEventEntity) {
            events.remove(event.id)
            events[event.id] = event
        }

        override suspend fun deleteEvent(eventId: String) {
            events.remove(eventId)
        }

        override suspend fun repointEvents(fromSessionId: String, toSessionId: String) {
            events.values.filter { it.sessionId == fromSessionId }.forEach {
                events[it.id] = it.copy(sessionId = toSessionId)
            }
        }

        override suspend fun deleteUnconfirmedEvents(
            sessionId: String,
            serverOwnedTypes: List<String>,
            confirmedIds: List<String>,
        ) {
            events.values
                .filter { it.sessionId == sessionId && it.type in serverOwnedTypes && it.id !in confirmedIds }
                .map { it.id }
                .forEach { events.remove(it) }
        }

        override suspend fun sumEventsByType(sessionId: String, type: String): Int =
            events.values.filter { it.sessionId == sessionId && it.type == type }.sumOf { it.amountCents }
    }

    // MARK: - HTTP falso, ruteado por path

    private fun response(code: Int, body: String, url: String) = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    /**
     * Devuelve la respuesta cuyo path COINCIDE con el sufijo pedido. Un path sin
     * respuesta configurada revienta con IOException — o sea, "sin red" para esa
     * llamada, que es justo lo que hace falta para probar el modo isla.
     */
    private fun client(vararg rutas: Pair<String, String>): OkHttpClient {
        val porRuta = rutas.toMap()
        return mockk {
            every { newCall(any()) } answers {
                val request = firstArg<Request>()
                val path = request.url.encodedPath
                val cuerpo = porRuta.entries.firstOrNull { path.endsWith(it.key) }?.value
                val call = mockk<Call>()
                if (cuerpo == null) {
                    every { call.execute() } throws IOException("sin red: $path")
                } else {
                    every { call.execute() } returns response(200, cuerpo, request.url.toString())
                }
                call
            }
        }
    }

    private fun secureStorage(): SecureStorage = mockk(relaxed = true) {
        every { this@mockk.venueId } returns "venue-1"
        every { venueName } returns "Testarudo Cafe"
        every { userId } returns "staff-1"
        every { userFirstName } returns "Ana"
        every { userLastName } returns "Ruiz"
    }

    private fun repo(dao: CashDrawerDao, client: OkHttpClient) =
        CashDrawerRepository(dao = dao, secureStorage = secureStorage(), client = client)

    // MARK: - Payloads del server (forma real de `formatSession`/`formatEvent`)

    private fun eventoJson(
        id: String,
        type: String,
        amount: String,
        note: String? = null,
        createdAt: String = "2026-08-16T18:00:00.000Z",
    ) = """
        {"id":"$id","type":"$type","amount":$amount,
         "note":${note?.let { "\"$it\"" } ?: "null"},
         "staffId":"staff-1","staffName":"Ana Ruiz","orderId":null,"createdAt":"$createdAt"}
    """.trimIndent()

    private fun sesionJson(id: String, vararg eventos: String) = """
        {"success":true,"data":{
          "id":"$id","venueId":"venue-1","deviceName":"Sunmi D3","status":"OPEN",
          "openedByStaffId":"staff-1","openedByName":"Ana Ruiz",
          "openedAt":"2026-08-16T17:00:00.000Z","startingAmount":5000.00,
          "closedByStaffId":null,"closedByName":null,"closedAt":null,
          "actualAmount":null,"overShort":null,"closingNote":null,
          "events":[${eventos.joinToString(",")}]
        }}
    """.trimIndent()

    private val aperturaDelServer = eventoJson("srv-ev-open", "OPEN", "5000.00", "Caja abierta con \$5000.00")

    // MARK: - 1. Abrir con red

    /**
     * La tablet abre la caja, el server contesta con SU id. Al terminar tiene que
     * quedar UNA sola caja abierta en Room, y con el id del server — si no, todo lo
     * que el server mande después (el reembolso, la venta de otra tablet) aterriza
     * en una sesión que esta pantalla no lee.
     */
    @Test
    fun `abrir la caja con red deja UNA sola sesion, con el id del server`() = runTest {
        val dao = FakeCashDrawerDao()
        val repo = repo(dao, client("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer)))

        repo.openSession(fondoCents)

        assertEquals(
            "Room quedó con más de una caja abierta: ${dao.sessions.keys}",
            1,
            dao.sessions.size,
        )
        assertEquals("srv-1", dao.sessions.keys.first())
        assertEquals("srv-1", repo.getOpenSession()?.id)
    }

    // MARK: - 2. Abrir sin red (no se puede romper)

    @Test
    fun `abrir la caja SIN red sigue funcionando`() = runTest {
        val dao = FakeCashDrawerDao()
        val repo = repo(dao, client()) // nada responde

        val sesion = repo.openSession(fondoCents)

        assertEquals(1, dao.sessions.size)
        assertEquals("OPEN", sesion.status)
        assertEquals(fondoCents, sesion.startingAmountCents)
        assertEquals(sesion.id, repo.getOpenSession()?.id)
    }

    /**
     * Y al reconectar, la caja que nació local ADOPTA el id del server en vez de
     * dejar una segunda fila abierta.
     */
    @Test
    fun `la caja abierta sin red se promueve al reconectar y sigue habiendo UNA`() = runTest {
        val dao = FakeCashDrawerDao()
        val sinRed = repo(dao, client())
        val local = sinRed.openSession(fondoCents)

        val conRed = repo(dao, client("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer)))
        conRed.syncFromApi()

        assertEquals(
            "la sesión local no se promovió: ${dao.sessions.keys}",
            1,
            dao.sessions.size,
        )
        assertEquals("srv-1", dao.sessions.keys.first())
        assertTrue("la fila provisional sigue viva", !dao.sessions.containsKey(local.id))
    }

    // MARK: - 3. Los eventos se mudan con la sesión

    /**
     * Un retiro registrado SIN red vive contra el id local. Si la promoción no se
     * lleva los eventos, ese dinero deja de contar y el cajón "aparece" con $200 de
     * más al cerrar.
     */
    @Test
    fun `los eventos creados contra el id local siguen contando despues de la promocion`() = runTest {
        val dao = FakeCashDrawerDao()
        val sinRed = repo(dao, client())
        sinRed.openSession(fondoCents)
        sinRed.addPayOut(20_000, "Compra de hielo") // $200.00, sin red: no llega al server

        val conRed = repo(dao, client("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer)))
        conRed.syncFromApi()

        val sesion = conRed.getOpenSession()
        assertNotNull(sesion)
        assertEquals("srv-1", sesion!!.id)
        assertEquals(
            "el retiro registrado sin red se perdió al promover la sesión",
            fondoCents - 20_000,
            conRed.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - 4. 🔴 EL NÚMERO QUE VE EL CAJERO

    /**
     * El caso completo, con los números medidos: fondo $5,000 + venta $280 −
     * reembolso $150 = **$5,130.00**.
     *
     * El reembolso lo resta el SERVER (`postCashRefundToDrawer`) y baja como PAY_OUT
     * por el sync. Si la tablet sigue leyendo su sesión local, ese PAY_OUT no existe
     * para ella y el corte dice $5,280 — los $150 de sobrante que el cajero no puede
     * explicar.
     */
    @Test
    fun `el esperado del corte CUADRA con lo que dice el server`() = runTest {
        val dao = FakeCashDrawerDao()
        val alAbrir = repo(dao, client("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer)))
        alAbrir.openSession(fondoCents)
        alAbrir.addCashSale(ventaCents, "order-9") // el POS pinta la venta al instante

        val alSincronizar = repo(
            dao,
            client(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "280.00"),
                    eventoJson("srv-ev-reembolso", "PAY_OUT", "150.00", note = "Reembolso: Producto defectuoso"),
                ),
            ),
        )
        alSincronizar.syncFromApi()

        val sesion = alSincronizar.getOpenSession()!!
        assertEquals(
            "el esperado no cuadra con el server (fondo 5000 + venta 280 − reembolso 150)",
            513_000,
            alSincronizar.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - 5. La tablet que NO abrió la caja

    /**
     * Esta tablet nunca tuvo sesión local: lee la del server desde el primer sync.
     * Su venta en efectivo se escribe local Y vuelve confirmada por el server con
     * otro id. Sin reconciliación, la MISMA venta se cuenta dos veces.
     */
    @Test
    fun `la tablet que NO abrio la caja no cuenta la venta dos veces`() = runTest {
        val dao = FakeCashDrawerDao()
        val primerSync = repo(dao, client("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer)))
        primerSync.syncFromApi()

        primerSync.addCashSale(ventaCents, "order-9")

        val segundoSync = repo(
            dao,
            client(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "280.00"),
                ),
            ),
        )
        segundoSync.syncFromApi()

        val sesion = segundoSync.getOpenSession()!!
        assertEquals(
            "la venta de \$280 se contó dos veces",
            fondoCents + ventaCents,
            segundoSync.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - 6. Los movimientos que sí son del cliente

    /**
     * Un retiro CON red lo escribe la tablet y lo confirma el server con su propio
     * id. Es el mismo problema de identidad que la sesión, un nivel abajo: si la
     * fila local no adopta el id confirmado, el eco del sync la duplica y el cajón
     * resta $100 por un retiro de $50.
     */
    @Test
    fun `un retiro confirmado por el server no se cuenta dos veces`() = runTest {
        val dao = FakeCashDrawerDao()
        val abrir = repo(dao, client("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer)))
        abrir.openSession(fondoCents)

        val retirar = repo(
            dao,
            client(
                "/cash-drawer/pay-out" to
                    """{"success":true,"data":${eventoJson("srv-ev-retiro", "PAY_OUT", "50.00", note = "Propinas")}}""",
            ),
        )
        retirar.addPayOut(5_000, "Propinas")

        val sincronizar = repo(
            dao,
            client(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-retiro", "PAY_OUT", "50.00", note = "Propinas"),
                ),
            ),
        )
        sincronizar.syncFromApi()

        val sesion = sincronizar.getOpenSession()!!
        assertEquals(
            "el retiro de \$50 se restó dos veces",
            fondoCents - 5_000,
            sincronizar.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    /**
     * El contrapeso del test anterior: la limpieza NO puede borrar un movimiento que
     * el server todavía no conoce. Un retiro hecho sin red tiene que seguir
     * restando, o el cajero cierra con un faltante que sí existe físicamente.
     */
    @Test
    fun `un retiro que el server aun no conoce NO se borra al sincronizar`() = runTest {
        val dao = FakeCashDrawerDao()
        val abrir = repo(dao, client("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer)))
        abrir.openSession(fondoCents)

        val sinRed = repo(dao, client()) // el POST del retiro no sale
        sinRed.addPayOut(5_000, "Compra de hielo")

        val sincronizar = repo(dao, client("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer)))
        sincronizar.syncFromApi()

        val sesion = sincronizar.getOpenSession()!!
        assertEquals(
            "el retiro pendiente de sincronizar se borró",
            fondoCents - 5_000,
            sincronizar.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - 7. Las tablets que YA quedaron con las dos filas

    /**
     * 🔴 El estado en el que están los aparatos HOY: dos sesiones OPEN del mismo
     * local y los eventos repartidos entre las dos.
     *
     * La decisión es NO migrar la base: ninguna migración puede saber cuál fila es
     * la del server ni qué eventos locales ya viajaron, así que cualquier fusión a
     * ciegas o pierde movimientos o los cuenta doble. Se reconcilia en el PRIMER
     * sync, que es cuando la única fuente que puede desempatar —el server— está
     * disponible. Este test es esa promesa.
     */
    @Test
    fun `una tablet que ya tiene las DOS filas se reconcilia en el siguiente sync`() = runTest {
        val dao = FakeCashDrawerDao()

        // Estado heredado: la fila local (rowid más bajo: gana el LIMIT 1) …
        val local = CashDrawerSessionEntity(
            id = "local-uuid",
            venueId = venueId,
            deviceName = "Sunmi D3",
            openedByStaffId = "staff-1",
            openedByName = "Ana Ruiz",
            openedAt = 1_000L,
            startingAmountCents = fondoCents,
            status = "OPEN",
        )
        dao.insertSession(local)
        dao.insertEvent(
            CashDrawerEventEntity(
                id = "local-ev-open", sessionId = local.id, venueId = venueId, type = "OPEN",
                amountCents = fondoCents, note = null, staffId = "staff-1", staffName = "Ana Ruiz",
                createdAt = 1_000L,
            ),
        )
        dao.insertEvent(
            CashDrawerEventEntity(
                id = "local-ev-venta", sessionId = local.id, venueId = venueId, type = "CASH_SALE",
                amountCents = ventaCents, note = null, staffId = "staff-1", staffName = "Ana Ruiz",
                orderId = "order-9", createdAt = 2_000L,
            ),
        )
        // … y la del server, insertada después por el sync viejo.
        dao.insertSession(local.copy(id = "srv-1", openedAt = 900L))

        val repo = repo(
            dao,
            client(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "280.00"),
                    eventoJson("srv-ev-reembolso", "PAY_OUT", "150.00", note = "Reembolso: Producto defectuoso"),
                ),
            ),
        )
        repo.syncFromApi()

        assertEquals("quedó más de una caja abierta: ${dao.sessions.keys}", 1, dao.sessions.size)
        assertEquals("srv-1", dao.sessions.keys.first())
        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la venta heredada se contó dos veces tras la reconciliación",
            513_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - 8. Cinturón y tirantes: la consulta tiene que ser determinista

    /**
     * `LIMIT 1` sin `ORDER BY` deja que SQLite escoja: hoy devuelve el rowid más
     * bajo, pero eso es un detalle de implementación, no un contrato — un `VACUUM`
     * o un índice nuevo lo cambian sin avisar. Aunque la promoción ya evite el
     * duplicado, la consulta que decide QUÉ CAJA está abierta no puede depender del
     * azar. Criterio: la más reciente por `openedAt`, con `id` como desempate — el
     * mismo orden que ya usa iOS (`CashDrawerStore`, `.order(Column("openedAt").desc)`),
     * porque una caja vieja pegaría las ventas de hoy al fondo de ayer.
     */
    @Test
    fun `la consulta de la caja abierta es determinista`() {
        val dao = leerFuente("app/src/main/java/com/avoqado/pos/cashdrawer/data/CashDrawerDao.kt")
        val consulta = dao.substringAfter("status = 'OPEN'").substringBefore("suspend fun getOpenSession(")
        assertTrue(
            "getOpenSession sigue siendo LIMIT 1 sin ORDER BY: qué caja está abierta lo decide SQLite.",
            consulta.contains("ORDER BY"),
        )
    }

    private fun leerFuente(rutaRelativa: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidato = File(dir, rutaRelativa)
            if (candidato.isFile) return candidato.readText()
            val sinModulo = File(dir, rutaRelativa.removePrefix("app/"))
            if (sinModulo.isFile) return sinModulo.readText()
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró $rutaRelativa desde ${System.getProperty("user.dir")}")
    }
}
