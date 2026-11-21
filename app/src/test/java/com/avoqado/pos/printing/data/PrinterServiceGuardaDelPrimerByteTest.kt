package com.avoqado.pos.printing.data

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.printing.data.model.SavedPrinter
import io.mockk.every
import io.mockk.mockk
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
}
