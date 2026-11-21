package com.avoqado.pos.printing.data

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.printing.data.model.PrinterStatus
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.sunmi.peripheral.printer.SunmiPrinterService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * D661 · Ronda 3 · Familia 3 (Ruling 50): el permiso del primer byte ([GuardaDelPrimerByte]) se pregunta DESPUÉS de esperar el turno de
 * la impresora (y la conexión), justo antes de escribir. Sin permiso no llega ni un byte. Sin guarda (recibos, cortes, todo lo demás) nada
 * cambia.
 */
class PrinterServiceGuardaDelPrimerByteTest {

    private fun servicio(): PrinterService {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return PrinterService(context, mockk(relaxed = true), mockk(relaxed = true))
    }

    /** Una ticketera de red falsa: contesta «con papel» y guarda lo que recibe; la primera conexión espera a [soltarPrimera]. */
    private class Ticketera(val soltarPrimera: CountDownLatch = CountDownLatch(0)) {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val recibidas: MutableList<Byte> = Collections.synchronizedList(mutableListOf())
        private var conexiones = 0

        init {
            Thread {
                while (!socket.isClosed) {
                    val s = runCatching { socket.accept() }.getOrNull() ?: break
                    val primera = conexiones++ == 0
                    Thread {
                        s.use {
                            if (primera) soltarPrimera.await(5, TimeUnit.SECONDS)
                            it.getOutputStream().apply { write(0x12); flush() } // estado del rollo: con papel
                            recibidas += it.getInputStream().readBytes().toList()
                        }
                    }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }
    }

    @Test
    fun `R3 Family 3 without permission not a single byte reaches the printer, without a guard nothing changes`() = runBlocking {
        val ticketera = Ticketera()
        val service = servicio()
        val epson = SavedPrinter(id = "epson", name = "EPSON", connectionType = "wifi", address = "127.0.0.1", port = ticketera.socket.localPort)
        service.savePrinter(epson)
        try {
            val vencido = runCatching {
                withContext(GuardaDelPrimerByte { false }) { service.sendPrintData(byteArrayOf(0x41), epson) }
            }.exceptionOrNull()
            assertTrue("se dice que el permiso venció: $vencido", vencido is PermisoParaImprimirVencido)
            service.sendPrintData(byteArrayOf(0x42), epson) // un recibo: sin guarda
            Thread.sleep(500)
            assertFalse("ni un byte de la comanda vencida", 0x41.toByte() in ticketera.recibidas)
            assertTrue("lo demás sale como siempre", 0x42.toByte() in ticketera.recibidas)
        } finally {
            ticketera.socket.close()
        }
    }

    @Test
    fun `R3 Family 3 the permission is asked after waiting for the printer's turn`() = runBlocking {
        val soltar = CountDownLatch(1)
        val ticketera = Ticketera(soltarPrimera = soltar)
        val service = servicio()
        val epson = SavedPrinter(id = "epson", name = "EPSON", connectionType = "wifi", address = "127.0.0.1", port = ticketera.socket.localPort)
        service.savePrinter(epson)
        try {
            // A tiene la impresora (su ticketera tarda en contestar, como un permiso USB sin contestar).
            val a = async(Dispatchers.IO) { runCatching { service.sendPrintData(byteArrayOf(0x41), epson) } }
            Thread.sleep(300)
            val preguntado = java.util.concurrent.atomic.AtomicBoolean(false)
            val b = async(Dispatchers.IO) {
                runCatching { withContext(GuardaDelPrimerByte { preguntado.set(true); false }) { service.sendPrintData(byteArrayOf(0x42), epson) } }
            }
            Thread.sleep(500)
            assertFalse("B espera su turno: todavía no se le pregunta", preguntado.get())
            soltar.countDown()
            assertTrue(a.await().isSuccess)
            assertTrue(b.await().exceptionOrNull() is PermisoParaImprimirVencido)
            assertTrue("se le preguntó al tener la impresora", preguntado.get())
            Thread.sleep(500)
            assertEquals("sólo la de A llegó", listOf<Byte>(0x41), ticketera.recibidas.filter { it == 0x41.toByte() || it == 0x42.toByte() })
        } finally {
            ticketera.socket.close()
        }
    }

    // MARK: - Ronda 5 · Familia 3 (Ruling 81): con la preparación del transporte hecha, justo antes del primer byte

    /**
     * La integrada Sunmi se inicializa antes de cada trabajo (`printerInit`, y si el servicio se colgó, se re-liga y reintenta). Si en eso el
     * reclamo vence, no manda nada: el permiso se pregunta después de inicializar y antes de `sendRAWData`.
     */
    @Test
    fun `R5 Family 3 the integrated printer asks after its init and sends nothing when the claim expired meanwhile`() = runBlocking {
        val servicio = mockk<SunmiPrinterService>(relaxed = true)
        var edadDelReclamo = 70_000L
        every { servicio.printerInit(any()) } answers { edadDelReclamo += 25_000 } // el servicio tarda en inicializar
        every { servicio.updatePrinterState() } returns 1
        every { servicio.sendRAWData(any(), any()) } throws AssertionError("mandó el primer byte con el reclamo vencido")
        val integrada = SunmiInnerPrinter(mockk(relaxed = true)).also { it.attachServiceForTest(servicio) }
        val error = runCatching { integrada.printRaw(byteArrayOf(0x41), antesDelPrimerByte = { edadDelReclamo < 75_000 }) }.exceptionOrNull()
        assertTrue("sale la negativa del permiso: $error", error is PermisoParaImprimirVencido)
        verify(exactly = 0) { servicio.sendRAWData(any(), any()) }
    }

    /**
     * USB y la integrada preparan su propio transporte: `PrinterService` no pregunta antes (el primer «sí» se quedaría) sino que les pasa el
     * permiso, y su negativa sale tal cual: ni «impresora caída» ni la impresora en error.
     */
    @Test
    fun `R5 Family 3 PrinterService lets the integrated printer ask the permission itself and keeps its refusal a refusal`() = runBlocking {
        val integrada = mockk<SunmiInnerPrinter>(relaxed = true)
        every { integrada.isAvailable } returns true
        every { integrada.hasPhysicalPrinter } returns true
        coEvery { integrada.ensureBound() } returns true
        var preguntas = 0
        var preguntasAlEntrar = -1
        coEvery { integrada.printRaw(any(), any()) } coAnswers {
            preguntasAlEntrar = preguntas
            if (!secondArg<() -> Boolean>().invoke()) throw PermisoParaImprimirVencido()
        }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val service = PrinterService(context, integrada, mockk(relaxed = true))
        val interna = SavedPrinter(id = "interna", name = "Impresora integrada", connectionType = "internal", address = "internal")

        val error = runCatching {
            withContext(GuardaDelPrimerByte { preguntas++; false }) { service.sendPrintData(byteArrayOf(0x41), interna) }
        }.exceptionOrNull()

        assertTrue("sale la negativa del permiso: $error", error is PermisoParaImprimirVencido)
        assertEquals("PrinterService no lo preguntó antes de la integrada", 0, preguntasAlEntrar)
        assertEquals(1, preguntas)
        assertFalse("no es una impresora caída", service.printerStatuses.value["interna"] is PrinterStatus.Error)
    }
}
