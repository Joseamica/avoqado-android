package com.avoqado.pos.cashdrawer

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * 🔴 D9 (iPad, 24-oct-2026; Codex apps r14 #1 y r15). `/cash-drawer/current` es del NEGOCIO (`findFirst status OPEN`): si dice que
 * no hay ninguna caja abierta, la que este aparato sigue viendo abierta con id del servidor la cerró otro aparato. Antes sólo se
 * soltaba si salía en la PRIMERA página del historial (20): con más cierres de por medio se quedaba abierta para siempre.
 *
 * Y las tres carreras de r15: una caja confirmada MIENTRAS viajaba el «ninguna» no se cierra; un conteo escrito mientras tanto no
 * se pisa; la respuesta de otra sucursal no cierra nada.
 */
class CashDrawerCerradaEnOtroAparatoTest {

    private val sinCajaAbierta = """{"success":true,"data":null}"""
    private val historialSinEsaCaja = """{"success":true,"sessions":[]}"""
    private val cerradaEnOtro = "cmsrvcajacerradaenotro0001"

    /** El servidor falso: `/current` contesta «ninguna» DESPUÉS de correr [mientrasViaja] (lo que pasa en el aparato en medio). */
    private fun servidor(mientrasViaja: () -> Unit = {}): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            val request = firstArg<Request>()
            val path = request.url.encodedPath
            val call = mockk<Call>()
            when {
                path.endsWith("/cash-drawer/current") ->
                    every { call.execute() } answers { mientrasViaja(); respuesta(sinCajaAbierta, request) }
                path.endsWith("/cash-drawer/history") -> every { call.execute() } answers { respuesta(historialSinEsaCaja, request) }
                else -> every { call.execute() } throws IOException("sin red: $path")
            }
            call
        }
    }

    private fun respuesta(body: String, request: Request) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test
    fun `la caja que cerro otro aparato deja de verse abierta aunque ya no salga en el historial`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal(cerradaEnOtro, openedAt = haceMinutos(60)))

        cashDrawerRepo(dao, servidor()).syncFromApi()

        assertNull("la caja que el servidor ya no tiene abierta sigue abierta en el aparato", dao.getOpenSession(VENUE_ID))
        assertEquals("Cerrada en otro aparato", dao.getSession(cerradaEnOtro)?.closingNote)
    }

    @Test
    fun `la caja abierta sin red que el servidor todavia no conoce sigue abierta`() = runTest {
        val dao = FakeCashDrawerDao()
        val provisional = java.util.UUID.randomUUID().toString()
        dao.insertSession(sesionLocal(provisional, openedAt = haceMinutos(5)))

        cashDrawerRepo(dao, servidor()).syncFromApi()

        assertEquals(provisional, dao.getOpenSession(VENUE_ID)?.id)
    }

    /** Codex apps r15 #2: el cajero abre J y el servidor la confirma mientras viaja un «ninguna» viejo: J no se cierra. */
    @Test
    fun `la caja confirmada mientras viajaba la respuesta sigue abierta`() = runTest {
        val dao = FakeCashDrawerDao()
        dao.insertSession(sesionLocal(cerradaEnOtro, openedAt = haceMinutos(60)))
        val nueva = "cmsrvcajarecienabierta0001"

        cashDrawerRepo(dao, servidor { runBlocking { dao.insertSession(sesionLocal(nueva, openedAt = haceMinutos(0))) } }).syncFromApi()

        assertEquals("OPEN", dao.getSession(nueva)?.status)
        assertEquals("CLOSED", dao.getSession(cerradaEnOtro)?.status)
    }

    /** Codex apps r15 #3: el cajero guarda su conteo de H mientras viaja la respuesta: el conteo no se pisa. */
    @Test
    fun `un conteo guardado mientras viajaba la respuesta no se pisa`() = runTest {
        val dao = FakeCashDrawerDao()
        val caja = sesionLocal(cerradaEnOtro, openedAt = haceMinutos(60))
        dao.insertSession(caja)
        val cerradaAqui = caja.copy(status = "CLOSED", closedAt = haceMinutos(1), actualAmountCents = 512_340, closingNote = "conteo del cajero")

        cashDrawerRepo(dao, servidor { runBlocking { dao.updateSession(cerradaAqui) } }).syncFromApi()

        val despues = dao.getSession(cerradaEnOtro)
        assertEquals(512_340, despues?.actualAmountCents)
        assertEquals("conteo del cajero", despues?.closingNote)
    }
}
