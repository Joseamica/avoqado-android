package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.AliasDeCaja
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingCashSales
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.aliasTrasPodar
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A3 (efectivo sin red en el turno correcto, 21-oct-2026) — el alias «caja local → caja del servidor».
 *
 * Un cobro guarda la caja con el id que el aparato tenía al cobrar. Si la caja se abrió sin red, es el id LOCAL, y el
 * servidor no lo conoce: al reproducir tiene que viajar el del servidor, pero SÓLO si es la misma apertura (la llave del
 * eco regresa igual). Adoptar la caja de OTRO aparato nunca escribe alias: el cobro de A no se puede volver de B (Codex r3
 * #3). Y la poda nunca tira el alias de algo que todavía puede subir, FAILED incluido (Codex r3 #4).
 */
class CashDrawerAliasDeCajaTest {

    /** Un `SecureStorage` que recuerda la cola, los avisos y los alias, y anota el ORDEN en que se escriben. */
    private class Almacen {
        var cola: String? = null
        var avisos: String? = null
        var alias: String? = null
        val escrituras = mutableListOf<Pair<String, String?>>()
        val st: SecureStorage = mockk<SecureStorage>(relaxed = true).also { st ->
            every { st.venueId } returns VENUE_ID
            every { st.userId } returns "staff-1"
            every { st.userFirstName } returns "Ana"
            every { st.userLastName } returns "Ruiz"
            every { st.pendingDrawerOpsJson(any()) } answers { cola }
            every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg(); escrituras += "cola" to cola }
            every { st.drawerAdoptionNoticesJson(any()) } answers { avisos }
            every { st.setDrawerAdoptionNoticesJson(any(), any()) } answers { avisos = secondArg() }
            every { st.drawerSessionAliasesJson(any()) } answers { alias }
            every { st.setDrawerSessionAliasesJson(any(), any()) } answers { alias = secondArg(); escrituras += "alias" to alias }
        }
        fun aliasGuardados(): List<AliasDeCaja> = alias
            ?.let { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(AliasDeCaja.serializer()), it) } ?: emptyList()
        fun laCola(): List<PendingDrawerOp> = cola
            ?.let { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), it) } ?: emptyList()
    }

    private fun respuesta(code: Int, body: String, url: String) = Response.Builder()
        .request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1).code(code)
        .message(if (code in 200..299) "OK" else "Error").body(body.toResponseBody("application/json".toMediaType())).build()

    /** Responde por sufijo de path; lo que no está configurado truena como «sin red». */
    private fun cliente(vararg rutas: Pair<String, Pair<Int, () -> String>>): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            val request = firstArg<Request>()
            val path = request.url.encodedPath
            val configurada = rutas.firstOrNull { path.endsWith(it.first) }?.second
            mockk<Call>().also { call ->
                if (configurada == null) every { call.execute() } throws IOException("sin red: $path")
                else every { call.execute() } answers { respuesta(configurada.first, configurada.second(), request.url.toString()) }
            }
        }
    }

    private fun repo(
        almacen: Almacen,
        client: OkHttpClient,
        dao: FakeCashDrawerDao,
        cobros: PendingCashSales = sinCobrosEnCola(),
    ) = CashDrawerRepository(dao = dao, secureStorage = almacen.st, client = client, pendingCashSales = cobros,
        conectividad = conectividadDePrueba())

    /** La llave (localId) con la que quedó encolada la apertura de [sessionId]. */
    private fun llaveDeLaApertura(almacen: Almacen, sessionId: String) =
        almacen.laCola().first { it.kind == "OPEN" && it.sessionId == sessionId }.localId!!

    @Test
    fun `P1 la apertura propia confirmada escribe el alias ANTES de reapuntar la cola y es durable`() = runTest {
        val almacen = Almacen()
        val dao = FakeCashDrawerDao()
        // Sin red: la caja nace LOCAL y su apertura y un ingreso quedan en la cola.
        val sinRed = repo(almacen, cliente(), dao)
        val local = sinRed.openSession(100_000).id
        sinRed.addPayIn(5_000, "cambio")
        val llave = llaveDeLaApertura(almacen, local)
        assertNull("una caja local sin alias no viaja", sinRed.idDeCajaParaElServidor(local))

        // Vuelve la red: el servidor CREA la caja de esta apertura (eco de la llave).
        almacen.escrituras.clear()
        val conRed = repo(almacen, cliente(
            "/cash-drawer/open" to (201 to { sesionJson("srv-1", cajaCreada = true, localId = llave, startingAmount = 1000.00) }),
            "/cash-drawer/pay-in" to (201 to { eventoJson("srv-e1", "PAY_IN", "50.00", localId = "x") }),
        ), dao)
        conRed.reproducirPendientes()

        assertEquals("srv-1", conRed.idDeCajaParaElServidor(local))
        val primerAlias = almacen.escrituras.indexOfFirst { it.first == "alias" && it.second?.contains("srv-1") == true }
        val primerReapunte = almacen.escrituras.indexOfFirst { it.first == "cola" && it.second?.contains("srv-1") == true }
        assertTrue("no se escribió el alias: ${almacen.escrituras}", primerAlias >= 0)
        assertTrue("el alias se escribió DESPUÉS de reapuntar la cola: ${almacen.escrituras}", primerReapunte < 0 || primerAlias < primerReapunte)

        // La app se reinicia: una reserva escrita con el id local sigue saliendo con el del servidor.
        val reiniciada = repo(almacen, cliente(), dao)
        assertEquals("srv-1", reiniciada.idDeCajaParaElServidor(local))
    }

    @Test
    fun `P1 adoptar la caja de OTRO aparato no escribe alias y el cobro de la caja local no se vuelve de la otra`() = runTest {
        val almacen = Almacen()
        val dao = FakeCashDrawerDao()
        val local = repo(almacen, cliente(), dao).openSession(100_000).id

        // El servidor LIGA a una caja que abrió otro aparato: su llave NO es la de esta apertura.
        val conRed = repo(almacen, cliente(
            "/cash-drawer/open" to (201 to { sesionJson("srv-OTRA", cajaCreada = false, localId = "llave-de-otro-aparato", startingAmount = 300.00) }),
        ), dao)
        conRed.reproducirPendientes()

        assertNull("el cobro de la caja local no puede viajar con la caja de otro aparato", conRed.idDeCajaParaElServidor(local))
        assertTrue(almacen.aliasGuardados().none { it.servidor == "srv-OTRA" })
        // Un cobro capturado DESPUÉS de adoptar (la abierta ya es la del servidor) viaja con ese id (Review Focus #5).
        assertEquals("srv-OTRA", conRed.idDeCajaParaElServidor("srv-OTRA"))
    }

    /**
     * 🔴 Codex apps r1 #3: el servidor LIGA la apertura a una caja SIN eco de la llave (una caja que abrió una app vieja) y esa caja
     * se PARECE a la mía: mismo aparato, misma persona y mismo fondo. La heurística dice «es la propia», pero sólo la llave igual o
     * una caja que mi POST creó prueban que es la MISMA apertura. Sin eso no hay alias: los cobros de A no pueden viajar como B.
     * (Como iOS, `esLaMismaApertura`.)
     */
    @Test
    fun `P1 ligar SIN eco a una caja que solo se parece a la mia no escribe alias`() = runTest {
        val almacen = Almacen()
        val dao = FakeCashDrawerDao()
        val local = repo(almacen, cliente(), dao).openSession(100_000).id
        val parecida = sesionJson("srv-PARECIDA", cajaCreada = false, localId = null, startingAmount = 1000.00)
            .replace("Sunmi D3", "null null") // el aparato que el repositorio compone en una prueba JVM
        val conRed = repo(almacen, cliente("/cash-drawer/open" to (201 to { parecida })), dao)

        conRed.reproducirPendientes()

        assertNull("el cobro de la caja local no puede viajar con una caja que sólo se le parece", conRed.idDeCajaParaElServidor(local))
        assertTrue(almacen.aliasGuardados().none { it.servidor == "srv-PARECIDA" })
    }

    /**
     * 🔴 Codex apps r2 #3: el servidor CREÓ mi caja y se perdió la respuesta; el reintento de la apertura falla y luego la consulta
     * normal (`/current`, al entrar a Caja) devuelve MI caja con la llave de mi apertura. Promueve la provisional y reapunta su
     * apertura: el alias tiene que quedar, o los cobros guardados con el id local suben sin caja. Con OTRA llave, no hay alias.
     */
    @Test
    fun `P1 la consulta normal que devuelve MI caja con mi llave escribe el alias, y con otra llave no`() = runTest {
        for ((llaveDeLaCaja, esperado) in listOf<Pair<String?, String?>>(null to "srv-MIA", "llave-de-otro-aparato" to null)) {
            val almacen = Almacen()
            val dao = FakeCashDrawerDao()
            val local = repo(almacen, cliente(), dao).openSession(100_000).id
            val llave = llaveDeCajaOMia(llaveDeLaCaja, llaveDeLaApertura(almacen, local))
            val conRed = repo(almacen, cliente(
                "/cash-drawer/current" to (200 to {
                    sesionJson("srv-MIA", eventoJson("srv-ev-open", "OPEN", "1000.00", localId = llave), startingAmount = 1000.00)
                }),
            ), dao)

            conRed.syncFromApi()

            assertEquals("llave $llave", esperado, conRed.idDeCajaParaElServidor(local))
        }
    }

    private fun llaveDeCajaOMia(otra: String?, mia: String) = otra ?: mia

    /**
     * 🔴 Codex apps r3 #2: la apertura A llegó al servidor pero se perdió la respuesta; sin red el cajero cierra A, abre B y cobra en
     * B. La consulta normal devuelve A con la llave de A. Antes «promovía» la única caja local ABIERTA —B— dentro de A: B perdía su
     * barrera y su cobro salía sin caja con A todavía abierta en el servidor. Ahora la llave del eco identifica la apertura de A en
     * TODA la cola (aunque A ya esté cerrada aquí): A queda ligada a su caja del servidor y B sigue siendo B, esperando su apertura.
     */
    @Test
    fun `P1 la consulta normal reconoce por la llave la apertura de A cerrada y no mete B en A`() = runTest {
        val almacen = Almacen()
        val dao = FakeCashDrawerDao()
        val sinRed = repo(almacen, cliente(), dao)
        val cajaA = sinRed.openSession(100_000).id
        val llaveA = llaveDeLaApertura(almacen, cajaA)
        sinRed.closeSession(100_000, null)
        val cajaB = sinRed.openSession(50_000).id
        val conRed = repo(almacen, cliente(
            "/cash-drawer/current" to (200 to {
                sesionJson("srv-A", eventoJson("srv-ev-open", "OPEN", "1000.00", localId = llaveA), startingAmount = 1000.00)
            }),
        ), dao)

        conRed.syncFromApi()

        assertEquals("A queda ligada a su caja del servidor", "srv-A", conRed.idDeCajaParaElServidor(cajaA))
        assertNull("B no se vuelve A: su apertura sigue pendiente", conRed.idDeCajaParaElServidor(cajaB))
        assertTrue("B sigue esperando su apertura", cajaB in conRed.cajasQueEsperanSuApertura())
    }

    @Test
    fun `P1 adoptar por GET current la caja B con la caja A del servidor abierta aqui no vuelve B a los cobros de A`() = runTest {
        val almacen = Almacen()
        val dao = FakeCashDrawerDao()
        dao.sessions["srv-A"] = sesionLocal("srv-A", openedAt = haceMinutos(120))
        val conRed = repo(almacen, cliente(
            "/cash-drawer/current" to (200 to { sesionJson("srv-B", aperturaDelServer, openedAt = haceMinutos(10)) }),
        ), dao)

        conRed.syncFromApi()

        assertEquals("el cobro de A sigue saliendo con A", "srv-A", conRed.idDeCajaParaElServidor("srv-A"))
        assertTrue(almacen.aliasGuardados().none { it.local == "srv-A" })
    }

    @Test
    fun `P1 mas de 200 adopciones no podan el alias de una venta retenida ni el de una FAILED`() = runTest {
        val almacen = Almacen()
        val fallida = cobroEncolado("p-failed", 10_000, syncStatus = PaymentSyncStatus.FAILED.name).copy(cashDrawerSessionId = "L-failed")
        val retenida = cobroEncolado("p-q16", 10_000).copy(q15Reason = ConciliacionDelCobro.PRICE_PROOF_PENDING, cashDrawerSessionId = "L-q16")
        val repo = repo(almacen, cliente(), FakeCashDrawerDao(), colaDeCobros(cobros = listOf(fallida, retenida)))
        var ahora = 1_000L
        repo.reloj = { ahora++ }

        repo.guardarAliasDeCaja("L-failed", "S-failed")
        repo.guardarAliasDeCaja("L-q16", "S-q16")
        repeat(250) { repo.guardarAliasDeCaja("L-$it", "S-$it") }

        assertEquals("S-failed", repo.idDeCajaParaElServidor("L-failed"))
        assertEquals("S-q16", repo.idDeCajaParaElServidor("L-q16"))
        assertEquals("S-249", repo.idDeCajaParaElServidor("L-249"))
        assertEquals("los 2 referenciados + los 200 más recientes sin uso", 202, almacen.aliasGuardados().size)
        assertTrue("el más viejo sin uso se podó", almacen.aliasGuardados().none { it.local == "L-0" })
    }

    @Test
    fun `la poda conserva lo referenciado y los 200 mas recientes sin uso, en orden`() {
        val lista = listOf(AliasDeCaja("viejo-usado", "S", en = 1)) + (0 until 205).map { AliasDeCaja("L-$it", "S-$it", en = 10L + it) }

        val podada = aliasTrasPodar(lista, referenciadas = setOf("viejo-usado"))

        assertEquals(201, podada.size)
        assertEquals("viejo-usado", podada.first().local)
        assertEquals((5 until 205).map { "L-$it" }, podada.drop(1).map { it.local })
    }

    @Test
    fun `una caja que el servidor ya conoce viaja con su mismo id y un id vacio no viaja`() {
        val repo = repo(Almacen(), cliente(), FakeCashDrawerDao())
        assertEquals("srv-9", repo.idDeCajaParaElServidor("srv-9"))
        assertNull(repo.idDeCajaParaElServidor(null))
        assertNull(repo.idDeCajaParaElServidor(""))
        assertNotNull(repo.idDeCajaParaElServidor("srv-9"))
    }
}
