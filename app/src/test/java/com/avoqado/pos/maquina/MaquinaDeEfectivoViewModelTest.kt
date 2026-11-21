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

    private var sucursal = "venue-1"
    private val sesion = mockk<com.avoqado.pos.core.data.local.SecureStorage> {
        every { venueId } answers { sucursal }
        every { venueName } answers { if (sucursal == "venue-1") "La Galeterie Centro" else "La Galeterie Sur" }
    }

    private fun vm(maquina: MaquinaDeEfectivo? = null) = MaquinaDeEfectivoViewModel(
        prefs,
        boveda,
        EmparejamientoDeLaMaquina(sesion, okHttp).apply { baseUrl = servidor.url("/api/v1").toString().trimEnd('/') },
        cobros,
        maquina,
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
        assertEquals("Codex código r1 #10: con su sucursal", "venue-1" to "La Galeterie Centro", prefs.venueId to prefs.nombreDeLaSucursal)
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
    fun `C7 la hoja enseña lo que la maquina le debe a clientes y reimprime su comprobante`() {
        val maquina = mockk<MaquinaDeEfectivo>()
        val fila = CobroEnMaquinaEntity("AQ000000000001", "venue-1", "venta-1", "{}", 18_000, 0, 18_000, null, "q", 1, EstadoDelIntento.PAGADO)
        io.mockk.coEvery { maquina.comprobantes() } returns listOf(fila to 500)
        io.mockk.coEvery { maquina.imprimirComprobante("AQ000000000001") } returns
            com.avoqado.pos.printing.data.PrinterService.PrintOutcome.Printed(1) andThen
            com.avoqado.pos.printing.data.PrinterService.PrintOutcome.OutOfPaper
        io.mockk.coEvery { maquina.dudasDeEstaPc(any(), any()) } returns (emptyList<CobroEnMaquinaEntity>() to 0)
        io.mockk.coEvery { maquina.comprobantes(100) } returns listOf(fila to 500, fila.copy(referenceCC = "AQ000000000002") to 300)
        io.mockk.coEvery { maquina.cuantosComprobantes() } returns 2
        every { maquina.puedeResolver() } returns false
        val vm = vm(maquina).abierta()
        assertEquals("Codex r1 #13: hay más deudas que las de la página", 2, vm.estado.value.comprobantesTotal)

        assertEquals(listOf(com.avoqado.pos.maquina.ui.ComprobantePorImprimir("AQ000000000001", 500)), vm.estado.value.comprobantes)
        vm.imprimirComprobante("AQ000000000001")
        vm.esperar("impreso") { it.listo == "¡Comprobante impreso!" }
        vm.imprimirComprobante("AQ000000000001")
        vm.esperar("sin papel, con el resultado real") { it.aviso == "La impresora no tiene papel" }
        vm.verMasComprobantes()
        vm.esperar("la siguiente página") { it.comprobantes.size == 2 }
    }

    @Test
    fun `P1 Codex r1 #10 desde otra sucursal la hoja dice con cual esta emparejada y con pendientes no se empareja aqui`() {
        prefs.guardar("192.168.1.50", activa = true, venueId = "venue-1", nombreDeLaSucursal = "La Galeterie Centro")
        boveda.guardar("cred-1")
        runBlocking { cobros.insertar(fila("AQ00000000000A", EstadoDelIntento.PAGADO)) }   // sin subir, de la sucursal 1
        sucursal = "venue-2"
        val vm = vm().abierta()

        assertEquals("La Galeterie Centro", vm.estado.value.emparejadaConOtra)
        assertFalse("la misma IP en otra sucursal dejaría los cobros de la 1 sin dueño", vm.estado.value.puedeEmparejar)
        vm.emparejar()
        vm.esperar("aviso") { it.aviso == TEXTO_PENDIENTES_DE_LA_MAQUINA && !it.ocupado }
        assertEquals(0, servidor.requestCount)
        assertEquals("venue-1", prefs.venueId)
    }

    @Test
    fun `Codex r1 #10 sin pendientes se puede emparejar la nueva sucursal y queda la de la sesion`() {
        servidor.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"bindingId":"b2","credential":"cred-2"}"""))
        prefs.guardar("192.168.1.50", activa = true, venueId = "venue-1", nombreDeLaSucursal = "La Galeterie Centro")
        boveda.guardar("cred-1")
        sucursal = "venue-2"
        val vm = vm().abierta()
        assertTrue(vm.estado.value.puedeEmparejar)

        vm.emparejar()

        vm.esperar("emparejada aquí") { it.emparejadaConOtra == null && it.emparejada && !it.ocupado }
        assertEquals("venue-2" to "La Galeterie Sur", prefs.venueId to prefs.nombreDeLaSucursal)
        assertEquals("cred-2", boveda.leer())
    }

    @Test
    fun `P1 Codex r1 #7 Ajustes enseña las dudas de esta PC y las resuelve con gerente`() {
        val maquina = mockk<MaquinaDeEfectivo>()
        val duda = fila("AQ00000000000D", EstadoDelIntento.ESPERANDO)
        io.mockk.coEvery { maquina.comprobantes() } returns emptyList()
        io.mockk.coEvery { maquina.dudasDeEstaPc(false, any()) } returns (listOf(duda) to 1) andThen (emptyList<CobroEnMaquinaEntity>() to 0)
        every { maquina.puedeResolver() } returns true
        val resolucion = ResolucionDelGerente(Desenlace.CANCELADO, 5_000, 5_000, null, "1", "pantalla: devolvió todo")
        io.mockk.coEvery { maquina.resolver("AQ00000000000D", resolucion) } returns ResultadoDeResolver.Resuelto(Desenlace.CANCELADO, 5_000, 5_000)
        val vm = vm(maquina).abierta()
        assertEquals(listOf("AQ00000000000D" to 18_000), vm.estado.value.enEspera.map { it.referenceCC to it.dueCents })
        assertTrue(vm.estado.value.puedeResolver)

        vm.resolver("AQ00000000000D", resolucion)

        vm.esperar("resuelta, ya no está") { it.enEspera.isEmpty() && it.listo == "¡Cobro resuelto!" }
        io.mockk.coVerify(exactly = 1) { maquina.resolver("AQ00000000000D", resolucion) }
    }

    @Test
    fun `P1 Codex r2 H6 la bandeja del gerente lista las dudas del NEGOCIO desde el servidor, con total y Ver mas, mas las de esta PC`() {
        val maquina = mockk<MaquinaDeEfectivo>(relaxed = true)
        val delKiosco = CobroEnEspera("AQ0000000KIOS1", 9_000, 1, version = 2, evidencia = "Entró \$100.00")
        val siguiente = CobroEnEspera("AQ0000000KIOS2", 5_000, 2)
        io.mockk.coEvery { maquina.dudasDelNegocio(null, MaquinaDeEfectivo.KIND_ESPERANDO) } returns
            PaginaDeDudas(listOf(delKiosco), total = 103, siguiente = "c-2")
        io.mockk.coEvery { maquina.dudasDelNegocio("c-2", MaquinaDeEfectivo.KIND_ESPERANDO) } returns
            PaginaDeDudas(listOf(siguiente), total = 103, siguiente = null)
        io.mockk.coEvery { maquina.dudasDelNegocio(null, MaquinaDeEfectivo.KIND_POR_TERMINAR) } returns PaginaDeDudas(emptyList(), 0, null)
        io.mockk.coEvery { maquina.dudasDeEstaPc(true, any()) } returns (listOf(fila("AQ00000000000D", EstadoDelIntento.ESPERANDO)) to 1)
        val vm = vm(maquina).abierta()

        assertEquals(listOf("AQ0000000KIOS1", "AQ00000000000D"), vm.estado.value.enEspera.map { it.referenceCC })
        assertEquals("las del servidor más la de esta PC que él todavía no tiene", 104, vm.estado.value.enEsperaTotal)
        assertTrue(vm.estado.value.hayMasDudas)

        vm.verMasDudas()

        vm.esperar("trajo la siguiente página") { it.enEspera.size == 3 && !it.hayMasDudas }
        assertEquals("AQ0000000KIOS2", vm.estado.value.enEspera.last().referenceCC)
    }

    @Test
    fun `P1 Codex r2 H7 la bandeja firma la version que enseña, y con evidencia nueva enseña la del servidor`() {
        val maquina = mockk<MaquinaDeEfectivo>(relaxed = true)
        val vista = CobroEnEspera("AQ0000000KIOS1", 9_000, 1, version = 2, evidencia = "Entró \$100.00")
        val alDia = vista.copy(version = 3, evidencia = "Entró \$150.00")
        io.mockk.coEvery { maquina.dudasDelNegocio(null, MaquinaDeEfectivo.KIND_ESPERANDO) } returns PaginaDeDudas(listOf(vista), total = 1, siguiente = null)
        io.mockk.coEvery { maquina.dudasDelNegocio(null, MaquinaDeEfectivo.KIND_POR_TERMINAR) } returns PaginaDeDudas(emptyList(), 0, null)
        io.mockk.coEvery { maquina.dudasDeEstaPc(any(), any()) } returns (emptyList<CobroEnMaquinaEntity>() to 0)
        val resolucion = ResolucionDelGerente(Desenlace.PAGADO, 10_000, 1_000, null, null, "comprobante", version = 2)
        io.mockk.coEvery { maquina.resolver("AQ0000000KIOS1", resolucion) } returns ResultadoDeResolver.EvidenciaNueva("Llegó evidencia nueva", alDia)
        io.mockk.every { maquina.puedeResolver() } returns true
        val vm = vm(maquina).abierta()

        vm.resolver("AQ0000000KIOS1", resolucion)

        vm.esperar("enseña la nueva") { it.enEspera.single() == alDia && it.avisoDeResolver == "Llegó evidencia nueva" && !it.resolviendo }
    }

    @Test
    fun `P1 Codex r2 #12 los tickets que no salieron tambien se imprimen desde Ajustes, con la maquina apagada`() {
        val maquina = mockk<MaquinaDeEfectivo>(relaxed = true)
        io.mockk.coEvery { maquina.cuantosTicketsSinImprimir() } returns 2 andThen 0
        io.mockk.coEvery { maquina.dudasDelNegocio(any(), any()) } returns null
        io.mockk.coEvery { maquina.dudasDeEstaPc(any(), any()) } returns (emptyList<CobroEnMaquinaEntity>() to 0)
        io.mockk.coEvery { maquina.imprimirTicketsPendientes() } returns 2
        prefs.guardar("192.168.1.50", activa = false, venueId = "venue-1")
        val vm = vm(maquina).abierta()
        assertEquals(2, vm.estado.value.ticketsSinImprimir)

        vm.imprimirTicketsPendientes()

        vm.esperar("salieron") { it.ticketsSinImprimir == 0 && it.listo == "¡2 tickets impresos!" }
    }

    @Test
    fun `P1 D3 emparejar guarda su emparejamiento y avisa que la PC anterior se dio de baja`() {
        servidor.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"success":true,"bindingId":"cbind0000000000000000000a","credential":"cred-1","replaced":[{"deviceId":"cterm1","machineIp":"192.168.1.60"}]}""",
            ),
        )
        val vm = vm().abierta()

        vm.cambiarIp("192.168.1.50")
        vm.emparejar()

        val fin = vm.esperar("emparejada") { it.emparejada && !it.ocupado }
        assertEquals("Se dio de baja la PC anterior (192.168.1.60): desconéctala de la máquina", fin.aviso)
        assertEquals("cbind0000000000000000000a", prefs.bindingId)
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
            fila("AQ00000000000B", EstadoDelIntento.ESPERANDO),
            // Codex código r2 H1: una duda que el SERVIDOR ya tiene sigue frenando: con el dinero en duda, la PC conserva su máquina.
            fila("AQ00000000000E", EstadoDelIntento.ESPERANDO, dudaSubida = true),
            fila("AQ00000000000C", EstadoDelIntento.PAGADO),
        ).forEach { pendiente ->
            cobros.db.createStatement().use { it.executeUpdate("DELETE FROM cobros_en_maquina") }
            runBlocking { cobros.insertar(pendiente) }
            prefs.guardar("192.168.1.50", activa = true, venueId = "venue-1")
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
        prefs.guardar("192.168.1.50", activa = true, venueId = "venue-1")
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
        prefs.guardar("192.168.1.50", activa = true, venueId = "venue-1")
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
        prefs.guardar("192.168.1.50", activa = true, venueId = "venue-1")
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
        prefs.guardar("192.168.1.50", activa = true, venueId = "venue-1")              // la bóveda está vacía
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

    // MARK: - Codex código r3

    @Test
    fun `P1 Codex r3 #7 con resolver y sin configurar se entra a la bandeja, pero emparejar queda en gris y no manda nada`() {
        val maquina = mockk<MaquinaDeEfectivo>(relaxed = true)
        every { maquina.puedeResolver() } returns true
        every { maquina.puedeConfigurar() } returns false
        io.mockk.coEvery { maquina.dudasDelNegocio(any(), any()) } returns null
        io.mockk.coEvery { maquina.dudasDeEstaPc(any(), any()) } returns (listOf(fila("AQ00000000000D", EstadoDelIntento.ESPERANDO)) to 1)
        val vm = vm(maquina).abierta()

        assertTrue(vm.estado.value.puedeResolver)
        assertEquals(listOf("AQ00000000000D"), vm.estado.value.enEspera.map { it.referenceCC })
        vm.cambiarIp("192.168.1.50")
        assertFalse(vm.estado.value.puedeEmparejar)
        vm.emparejar()
        vm.desemparejar()
        assertEquals("configurar sigue pidiendo su permiso", 0, servidor.requestCount)
    }

    @Test
    fun `P1 Codex r3 #8 sin el servidor la bandeja ensena TODAS las dudas de esta PC, con su total y Ver mas`() {
        val maquina = mockk<MaquinaDeEfectivo>(relaxed = true)
        val dudas = (1..103).map { fila("AQ%012d".format(it), EstadoDelIntento.ESPERANDO) }
        io.mockk.coEvery { maquina.dudasDelNegocio(any(), any()) } returns null
        io.mockk.coEvery { maquina.dudasDeEstaPc(false, any()) } answers { dudas.take(secondArg<Int>()) to dudas.size }
        val vm = vm(maquina).abierta()

        assertEquals(50, vm.estado.value.enEspera.size)
        assertEquals("el total real, no el tope de la consulta", 103, vm.estado.value.enEsperaTotal)
        assertTrue(vm.estado.value.hayMasDudas)
        vm.verMasDudas()
        vm.esperar("la segunda página") { it.enEspera.size == 100 && it.hayMasDudas }
        vm.verMasDudas()
        vm.esperar("todas") { it.enEspera.size == 103 && !it.hayMasDudas }
    }

    @Test
    fun `P1 Codex r3 #8 con el servidor lista los POR_TERMINAR y no cuenta dos veces una duda de esta PC que el ya tiene`() {
        val maquina = mockk<MaquinaDeEfectivo>(relaxed = true)
        val porTerminar = CobroEnEspera("AQ00000000RELE", 18_000, 1, version = 3, creditCents = 20_000, porTerminar = true, outcome = "PAGADO")
        io.mockk.coEvery { maquina.dudasDelNegocio(null, MaquinaDeEfectivo.KIND_ESPERANDO) } returns
            PaginaDeDudas(listOf(CobroEnEspera("AQ00000000000D", 18_000, 1, version = 2)), total = 1, siguiente = null)
        io.mockk.coEvery { maquina.dudasDelNegocio(null, MaquinaDeEfectivo.KIND_POR_TERMINAR) } returns PaginaDeDudas(listOf(porTerminar), 1, null)
        // Sólo se piden las que el SERVIDOR nunca acusó (`soloSinSubir`): las demás ya vienen en su lista.
        io.mockk.coEvery { maquina.dudasDeEstaPc(true, any()) } returns (emptyList<CobroEnMaquinaEntity>() to 0)
        val vm = vm(maquina).abierta()

        assertEquals(listOf("AQ00000000000D", "AQ00000000RELE"), vm.estado.value.enEspera.map { it.referenceCC })
        assertEquals(2, vm.estado.value.enEsperaTotal)
        assertTrue(vm.estado.value.enEspera.last().porTerminar)
        io.mockk.coVerify(exactly = 0) { maquina.dudasDeEstaPc(false, any()) }
    }
}
