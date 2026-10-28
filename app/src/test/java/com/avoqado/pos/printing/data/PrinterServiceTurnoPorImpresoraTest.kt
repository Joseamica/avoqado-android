package com.avoqado.pos.printing.data

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.printing.data.model.SavedPrinter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * D2 (27-oct-2026): con 4 comandas atrasadas la Samsung las mandó a la vez a la EPSON; salió UNA y las otras tronaron con
 * «Socket is closed» y esperaron 3 minutos cada una. Todas compartían el socket de la impresora y la primera lo cerraba al
 * terminar. Con turno por impresora salen todas, una tras otra.
 */
class PrinterServiceTurnoPorImpresoraTest {

    private fun servicio(): PrinterService {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return PrinterService(context, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `cuatro comandas a la vez en la misma impresora salen las cuatro`() = runBlocking {
        val ticketera = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val recibidas = Collections.synchronizedList(mutableListOf<List<Byte>>())
        Thread {
            while (!ticketera.isClosed) {
                val s = runCatching { ticketera.accept() }.getOrNull() ?: break
                Thread {
                    s.use {
                        it.getOutputStream().apply { write(0x12); flush() } // estado del rollo: con papel
                        recibidas += it.getInputStream().readBytes().toList()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        val service = servicio()
        val epson = SavedPrinter(id = "epson", name = "EPSON", connectionType = "wifi", address = "127.0.0.1", port = ticketera.localPort)
        service.savePrinter(epson)

        try {
            val resultados = (1..4).map { n ->
                async(Dispatchers.IO) { runCatching { service.sendPrintData(byteArrayOf((0x40 + n).toByte()), epson) } }
            }.awaitAll()

            assertEquals("ninguna comanda debe fallar", emptyList<Throwable>(), resultados.mapNotNull { it.exceptionOrNull() })
            Thread.sleep(500)
            val comandas = recibidas.flatMap { it.filter { b -> b in 0x41..0x44 } }.sorted()
            assertEquals("las cuatro llegan, una vez cada una", listOf<Byte>(0x41, 0x42, 0x43, 0x44), comandas)
        } finally {
            ticketera.close()
        }
    }
}
