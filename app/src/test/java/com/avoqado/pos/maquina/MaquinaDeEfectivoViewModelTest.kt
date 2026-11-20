package com.avoqado.pos.maquina

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.maquina.AjustesDeLaMaquinaTest.Companion.contextoConPreferencias
import com.avoqado.pos.maquina.AjustesDeLaMaquinaTest.Companion.fila
import com.avoqado.pos.maquina.ui.MaquinaDeEfectivoViewModel
import com.avoqado.pos.maquina.ui.PruebaDeConexion
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * La hoja «Máquina de efectivo» (C6): emparejar guarda la credencial en la bóveda ANTES de prender la casilla; sin internet
 * lo dice; con cobros de la máquina sin subir o en duda no se cambia la IP ni se desempareja (R5#2); y una PC cuya
 * credencial no se puede leer (perfil copiado, R5#3) se vuelve a emparejar con su misma IP.
 */
class MaquinaDeEfectivoViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val servidor = MockWebServer().apply { start() }
    private val okHttp = OkHttpClient()
    private val boveda = BovedaEnMemoria()
    private val contexto = contextoConPreferencias()
    private val prefs = MaquinaDeEfectivoPrefs(contexto)
    private val cobros = CobroEnMaquinaSobreSqlite()

    @After
    fun apagar() {
        runCatching { servidor.shutdown() }
        cobros.close()
        okHttp.dispatcher.executorService.shutdown()
    }

    private fun vm() = MaquinaDeEfectivoViewModel(
        prefs,
        boveda,
        EmparejamientoDeLaMaquina(mockk { every { venueId } returns "venue-1" }, okHttp)
            .apply { baseUrl = servidor.url("/api/v1").toString().trimEnd('/') },
        cobros,
    )

    private fun MaquinaDeEfectivoViewModel.esperar(que: String, condicion: (com.avoqado.pos.maquina.ui.EstadoDeLaMaquina) -> Boolean) =
        runBlocking { withTimeout(5_000) { estado.first { condicion(it) } } }.also { assertTrue(que, condicion(it)) }

    private fun MaquinaDeEfectivoViewModel.abierta() = apply {
        abrir()
        esperar("cargó") { it.cargado && !it.ocupado }
    }

    @Test
    fun `P1 emparejar guarda la credencial en la boveda y deja la maquina activa con su IP`() {
        servidor.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"bindingId":"b1","credential":"cred-1"}"""))
        val vm = vm().abierta()

        vm.cambiarIp(" 192.168.1.50 ")
        vm.emparejar()

        val listo = vm.esperar("emparejada") { it.emparejada && !it.ocupado }
        assertEquals("cred-1", boveda.leer())
        assertEquals("192.168.1.50", prefs.ip)
        assertTrue(prefs.activa)
        assertEquals("192.168.1.50", listo.ipGuardada)
        assertEquals("¡Máquina emparejada!", listo.listo)
        assertEquals("192.168.1.50", JSONObject(servidor.takeRequest().body.readUtf8()).getString("machineIp"))
    }

    @Test
    fun `sin internet emparejar lo explica y no guarda nada`() {
        servidor.shutdown()
        val vm = vm().abierta()

        vm.cambiarIp("192.168.1.50")
        vm.emparejar()

        val fin = vm.esperar("aviso") { it.aviso != null && !it.ocupado }
        assertEquals("Se necesita internet para emparejar", fin.aviso)
        assertNull(boveda.leer())
        assertFalse(prefs.activa)
    }

    @Test
    fun `una IP mal escrita no sale al servidor`() {
        val vm = vm().abierta()

        vm.cambiarIp("192.168.1")
        vm.emparejar()

        assertEquals(TEXTO_IP_INVALIDA, vm.estado.value.aviso)
        assertFalse(vm.estado.value.puedeEmparejar)
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `P1 con cobros de la maquina sin subir o en duda no se cambia la IP ni se desempareja, y se dice por que`() {
        listOf(
            fila("AQ00000000000A", EstadoDelIntento.EN_CURSO),
            fila("AQ00000000000B", EstadoDelIntento.ESPERANDO, dudaSubida = true),
            fila("AQ00000000000C", EstadoDelIntento.PAGADO),
        ).forEach { pendiente ->
            cobros.db.createStatement().use { it.executeUpdate("DELETE FROM cobros_en_maquina") }
            runBlocking { cobros.insertar(pendiente) }
            prefs.guardar("192.168.1.50", activa = true)
            boveda.guardar("cred-1")
            val vm = vm().abierta()
            val abierta = vm.estado.value
            assertTrue(pendiente.estado, abierta.hayPendientes)
            assertTrue(pendiente.estado, abierta.ipBloqueada)
            assertFalse(pendiente.estado, abierta.puedeDesemparejar)

            vm.cambiarIp("192.168.1.77")
            vm.emparejar()
            vm.esperar("aviso al cambiar la IP") { it.aviso == TEXTO_PENDIENTES_DE_LA_MAQUINA && !it.ocupado }
            vm.desemparejar()
            vm.esperar("aviso al desemparejar") { it.aviso == TEXTO_PENDIENTES_DE_LA_MAQUINA && !it.ocupado }

            assertEquals(pendiente.estado, 0, servidor.requestCount)
            assertEquals("cred-1", boveda.leer())
            assertEquals("192.168.1.50", prefs.ip)
            assertTrue(prefs.activa)
        }
    }

    @Test
    fun `P1 el freno se vuelve a contar al tocar, no sale del estado con que se abrio la hoja`() {
        prefs.guardar("192.168.1.50", activa = true)
        boveda.guardar("cred-1")
        val vm = vm().abierta()
        assertFalse(vm.estado.value.hayPendientes)
        runBlocking { cobros.insertar(fila("AQ00000000000A", EstadoDelIntento.ESPERANDO)) }   // otro cobro quedó en duda

        vm.desemparejar()

        vm.esperar("aviso") { it.aviso == TEXTO_PENDIENTES_DE_LA_MAQUINA && !it.ocupado }
        assertEquals(0, servidor.requestCount)
        assertEquals("cred-1", boveda.leer())
    }

    @Test
    fun `desemparejar avisa al servidor y borra la credencial y la casilla`() {
        servidor.enqueue(MockResponse().setBody("""{"success":true,"revoked":true}"""))
        prefs.guardar("192.168.1.50", activa = true)
        boveda.guardar("cred-1")
        val vm = vm().abierta()
        assertTrue(vm.estado.value.emparejada)

        vm.desemparejar()

        val fin = vm.esperar("desemparejada") { !it.activa && !it.ocupado }
        assertEquals("DELETE", servidor.takeRequest().method)
        assertNull(boveda.leer())
        assertFalse(prefs.activa)
        assertFalse(fin.emparejada)
    }

    @Test
    fun `sin internet desemparejar no borra la credencial`() {
        prefs.guardar("192.168.1.50", activa = true)
        boveda.guardar("cred-1")
        servidor.shutdown()
        val vm = vm().abierta()

        vm.desemparejar()

        assertEquals("Se necesita internet para desemparejar", vm.esperar("aviso") { it.aviso != null && !it.ocupado }.aviso)
        assertEquals("cred-1", boveda.leer())
        assertTrue(prefs.activa)
    }

    @Test
    fun `P1 una PC que perdio su credencial (perfil copiado) se vuelve a emparejar con su IP aunque haya pendientes`() {
        servidor.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"bindingId":"b2","credential":"cred-2"}"""))
        prefs.guardar("192.168.1.50", activa = true)                                   // la bóveda está vacía
        runBlocking { cobros.insertar(fila("AQ00000000000A", EstadoDelIntento.PAGADO)) }
        val vm = vm().abierta()
        assertTrue(vm.estado.value.credencialPerdida)
        assertTrue(vm.estado.value.puedeEmparejar)

        vm.emparejar()

        vm.esperar("emparejada") { it.emparejada && !it.ocupado }
        assertEquals("cred-2", boveda.leer())
    }

    @Test
    fun `probar conexion enseña Conectada o No responde`() {
        var responde = true
        val vm = vm().abierta()
        vm.probador = { responde }

        vm.cambiarIp("192.168.1.50")
        vm.probar()
        vm.esperar("conectada") { it.prueba == PruebaDeConexion.CONECTADA }

        responde = false
        vm.probar()
        vm.esperar("no responde") { it.prueba == PruebaDeConexion.NO_RESPONDE }
    }

    /** La bóveda de las pruebas: en memoria. La de verdad es DPAPI de máquina en WINDOWS (pruebas de `desktop/`). */
    private class BovedaEnMemoria : BovedaDeLaMaquina {
        @Volatile private var credencial: String? = null
        override fun guardar(credencial: String) { this.credencial = credencial }
        override fun leer(): String? = credencial
        override fun borrar() { credencial = null }
    }
}
