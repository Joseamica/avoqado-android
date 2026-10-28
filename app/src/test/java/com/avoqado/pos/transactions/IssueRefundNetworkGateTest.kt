package com.avoqado.pos.transactions

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.transactions.data.AssociatedRefundData
import com.avoqado.pos.transactions.data.AssociatedRefundItem
import com.avoqado.pos.transactions.data.RefundRepository
import com.avoqado.pos.transactions.data.model.puedeEnviarDevolucion
import com.avoqado.pos.transactions.presentation.ConexionDeDevolucionViewModel
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private class InicioDeDevolucion {
    val online = MutableStateFlow(true)
    val servidor = MutableStateFlow(true)
    val refunds = mockk<RefundRepository>()
    val terminal = mockk<TerminalPaymentService>()
    val conexion = ConexionDeDevolucionViewModel(mockk<ConnectivityMonitor> {
        every { isConnected } returns online
        every { isServerReachable } returns servidor
    }, mockk<SecureStorage>(relaxed = true))
    var vigente = true
    init {
        coEvery { terminal.requestRefundOnTerminal(any(), any(), any()) } returns Result.success(Unit)
        coEvery { terminal.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        coEvery { refunds.issueAssociatedRefund(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(AssociatedRefundData("refund", 5.01, 0.0, "COMPLETED"))
    }
    suspend fun llamar(punto: String) {
        when (punto) {
            "I1" -> terminal.requestRefundOnTerminal("terminal", "pago", "Productos devueltos").getOrThrow()
            "I2" -> assertTrue(terminal.fetchOnlineTerminals() is TerminalListResult.Success)
            "I3" -> refunds.issueAssociatedRefund(paymentId = "pago", reason = "RETURNED_GOODS",
                items = listOf(AssociatedRefundItem("articulo", 1)), refundMethod = "GIFT_CARD").getOrThrow()
            "I4" -> refunds.issueAssociatedRefund(paymentId = "pago", reason = "RETURNED_GOODS",
                amountCents = 501, refundMethod = "GIFT_CARD").getOrThrow()
            else -> error(punto)
        }
    }
    fun llamadas(punto: String, cantidad: Int) {
        coVerify(exactly = if (punto == "I1") cantidad else 0) { terminal.requestRefundOnTerminal(any(), any(), any()) }
        coVerify(exactly = if (punto == "I2") cantidad else 0) { terminal.fetchOnlineTerminals(any()) }
        coVerify(exactly = if (punto in listOf("I3", "I4")) cantidad else 0) {
            refunds.issueAssociatedRefund(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class IssueRefundNetworkGateTest(private val punto: String, private val perdida: String) {
    @Test fun despachoRetenidoReleeMonitorOContextoAntesDelClienteYRecuperaUnaVez() = runTest {
        val f = InicioDeDevolucion()
        var submitting = true
        var aviso: String? = null
        var resultado: Unit? = null
        val envio = launch(StandardTestDispatcher(testScheduler)) {
            resultado = f.conexion.iniciar(true, { f.vigente }) { f.llamar(punto) }
            submitting = false
            if (f.vigente && resultado == null) aviso = TextosDeTarjetas.SIN_RED
        }
        when (perdida) {
            "online" -> f.online.value = false
            "servidor" -> f.servidor.value = false
            "contexto" -> f.vigente = false
        }
        runCurrent(); envio.join()
        f.llamadas(punto, 0)
        assertNull(resultado); assertFalse(submitting)
        if (perdida == "contexto") assertNull(aviso) else assertEquals(TextosDeTarjetas.SIN_RED, aviso)
        f.online.value = true; f.servidor.value = true; f.vigente = true
        assertEquals(Unit, f.conexion.iniciar(true, { f.vigente }) { f.llamar(punto) })
        f.llamadas(punto, 1)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}")
        fun casos(): List<Array<String>> = listOf("I1", "I2", "I3", "I4").flatMap { punto ->
            listOf("online", "servidor", "contexto").map { arrayOf(punto, it) }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IssueRefundNetworkGateControlsTest {
    @Test fun legacySinBarreraConservaLosCuatroIniciosConMonitorCaido() = runTest {
        for (punto in listOf("I1", "I2", "I3", "I4")) {
            val f = InicioDeDevolucion(); f.online.value = false; f.servidor.value = false
            assertEquals(Unit, f.conexion.iniciar(false, { f.vigente }) { f.llamar(punto) })
            f.llamadas(punto, 1)
        }
    }

    @Test fun dobleToqueAntesDeDespachoYMientrasEsperaConservaUnSoloEnvio() = runTest {
        val f = InicioDeDevolucion()
        val respuesta = CompletableDeferred<Unit>()
        coEvery { f.terminal.requestRefundOnTerminal(any(), any(), any()) } coAnswers { respuesta.await(); Result.success(Unit) }
        var submitting = false
        fun tocar(): Job? {
            if (!puedeEnviarDevolucion(true, false, f.online.value && f.servidor.value, submitting)) return null
            submitting = true
            return launch(StandardTestDispatcher(testScheduler)) {
                try { f.conexion.iniciar(true, { f.vigente }) { f.llamar("I1") } }
                finally { submitting = false }
            }
        }
        val uno = tocar()
        assertNotNull(uno); assertNull(tocar())
        runCurrent(); assertNull(tocar()); f.llamadas("I1", 1)
        respuesta.complete(Unit); uno!!.join(); assertFalse(submitting)
    }

    @Test fun contextoReemplazadoDuranteRespuestaNoPublicaExito() = runTest {
        val f = InicioDeDevolucion(); val respuesta = CompletableDeferred<Unit>()
        coEvery { f.terminal.requestRefundOnTerminal(any(), any(), any()) } coAnswers { respuesta.await(); Result.success(Unit) }
        var exito = false; var submitting = true
        val envio = launch {
            val r = f.conexion.iniciar(true, { f.vigente }) { f.llamar("I1") }
            submitting = false
            if (f.vigente && r != null) exito = true
        }
        runCurrent(); f.llamadas("I1", 1)
        f.vigente = false; respuesta.complete(Unit); envio.join()
        assertFalse(exito); assertFalse(submitting)
    }

    @Test fun losCuatroIniciosDeComposeUsanElDuenoProbadoYLiberanOcupacionAntesDePublicar() {
        val fuente = fuente()
        val inicio = fuente.indexOf("fun mandarATerminal(")
        val lista = fuente.indexOf("fun abrirEnTerminal()")
        val devolver = fuente.indexOf("text = \"Reembolsar\"")
        val trozos = listOf(
            Triple(fuente.substring(inicio, lista), "enviandoATerminal = false", "requestRefundOnTerminal("),
            Triple(fuente.substring(lista, fuente.indexOf("// Include-tip", lista)), "enviandoATerminal = false", "fetchOnlineTerminals()"),
            Triple(fuente.substring(devolver, fuente.indexOf("enabled = canSubmit", devolver)), "submitting = false", "issueAssociatedRefund("))
        for ((trozo, salida, llamada) in trozos) {
            assertTrue(trozo.indexOf("if (!puedeEnviar()") < trozo.indexOf("scope.launch"))
            assertTrue(trozo.indexOf("conexion.iniciar(necesitaRed, ::vigente)") in 1 until trozo.indexOf(llamada))
            assertTrue(trozo.indexOf(salida) in 1 until trozo.indexOf("if (!vigente()) return@launch"))
            assertTrue(trozo.contains("TextosDeTarjetas.SIN_RED; return@launch"))
        }
        assertEquals(2, Regex("refundRepository\\.issueAssociatedRefund\\(").findAll(trozos.last().first).count())
        assertTrue(fuente.contains("submitting = true\n                        errorMsg = null\n\n                        scope.launch"))
        assertTrue(fuente.contains("submitting || enviandoATerminal"))
    }

    private fun fuente(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        val ruta = "app/src/main/java/com/avoqado/pos/transactions/presentation/IssueRefundSheet.kt"
        while (dir != null) {
            val archivo = File(dir, ruta)
            if (archivo.isFile) return archivo.readText()
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró $ruta")
    }
}
