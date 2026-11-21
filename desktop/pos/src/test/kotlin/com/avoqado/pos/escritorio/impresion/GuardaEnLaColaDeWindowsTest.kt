package com.avoqado.pos.escritorio.impresion

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.pos.escritorio.Inyector
import com.avoqado.pos.printing.data.GuardaDelPrimerByte
import com.avoqado.pos.printing.data.PermisoParaImprimirVencido
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterStatus
import com.avoqado.pos.printing.data.model.SavedPrinter
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * D661 · Ruling 81, de PrinterService a winspool: una comanda de tarjeta de «Nadie» llega a una impresora de cola de WINDOWS con su reclamo
 * de 70 s; el spooler detiene StartDocPrinter 25 s y el reclamo vence mientras. Al soltarse: cero WritePrinter, el documento vacío se
 * cancela, y la negativa sale como `PermisoParaImprimirVencido` (ni «no salió» ni la impresora en error).
 */
class GuardaEnLaColaDeWindowsTest {
    /** Un winspool falso cuyo StartDocPrinter espera al spooler. */
    private class ColaLenta(private val alIniciarDocumento: () -> Unit) : LlamadasDeCola<String> {
        val llamadas = mutableListOf<String>()
        override fun abrir(nombre: String): String { llamadas += "OpenPrinter"; return "h-$nombre" }
        override fun iniciarDocumento(h: String, documento: String): Boolean { llamadas += "StartDocPrinter"; alIniciarDocumento(); return true }
        override fun iniciarPagina(h: String): Boolean { llamadas += "StartPagePrinter"; return true }
        override fun escribir(h: String, bytes: ByteArray): Int { llamadas += "WritePrinter"; return bytes.size }
        override fun terminarPagina(h: String): Boolean { llamadas += "EndPagePrinter"; return true }
        override fun terminarDocumento(h: String): Boolean { llamadas += "EndDocPrinter"; return true }
        override fun abortar(h: String): Boolean { llamadas += "AbortPrinter"; return true }
        override fun cerrar(h: String) { llamadas += "ClosePrinter" }
        override fun ultimoError(): Int = 0
    }

    /** Un WINDOWS con una cola, «POS-80», que imprime con el protocolo de verdad sobre la cola falsa. */
    private class WindowsConUnaCola(private val cola: ColaLenta) : SistemaDeImpresion {
        private val pos80 = ColaInfo("POS-80", "USB001", "Generic / Text Only", 0, 0)
        override fun colas() = listOf(pos80)
        override fun cola(nombre: String) = pos80.takeIf { it.nombre == nombre }
        override fun puertos() = emptyList<PuertoInfo>()
        override fun imprimirEnCola(nombre: String, documento: String, bytes: ByteArray, antesDelPrimerByte: () -> Boolean) =
            imprimirTrabajoCrudo(cola, nombre, documento, bytes, antesDelPrimerByte)
        override fun abrirPuerto(puerto: String, baudios: Int?): CanalAbierto = throw UnsupportedOperationException("sin puertos")
    }

    private lateinit var original: SistemaDeImpresion

    @BeforeTest fun antes() { original = ImpresionDeEscritorio.sistema }
    @AfterTest fun despues() { ImpresionDeEscritorio.sistema = original }

    @Test fun `el reclamo vence mientras StartDocPrinter espera - cero WritePrinter y sale la negativa del permiso`() = runBlocking {
        var edadDelReclamo = 70_000L
        val cola = ColaLenta { edadDelReclamo += 25_000 }
        ImpresionDeEscritorio.sistema = WindowsConUnaCola(cola)
        val carpeta = Files.createTempDirectory("Avoqado POS guarda en la cola")
        Bitacora.iniciar(carpeta)
        val impresoras = Inyector.crear(ActividadDeEscritorio(carpeta)).getInstance(PrinterService::class.java)
        val pos80 = SavedPrinter(id = "pos80", name = "POS-80", connectionType = "usb", address = "usb:cola:POS-80")

        val error = runCatching {
            withContext(GuardaDelPrimerByte { edadDelReclamo < 75_000 }) { impresoras.sendPrintData(byteArrayOf(0x41), pos80) }
        }.exceptionOrNull()

        assertTrue(error is PermisoParaImprimirVencido, "sale la negativa del permiso, no una impresora caída: $error")
        assertFalse("WritePrinter" in cola.llamadas, "ni un byte: ${cola.llamadas}")
        assertTrue("AbortPrinter" in cola.llamadas, "el documento vacío se cancela: ${cola.llamadas}")
        assertFalse(impresoras.printerStatuses.value["pos80"] is PrinterStatus.Error, "la impresora no queda en error")
    }
}
