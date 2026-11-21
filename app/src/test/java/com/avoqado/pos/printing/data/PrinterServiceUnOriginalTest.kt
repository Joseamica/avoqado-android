package com.avoqado.pos.printing.data

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.SavedPrinter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex código r3 #11 (máquina de efectivo): con dos impresoras de recibos, una sola llamada mandaba el ORIGINAL a las dos: dos
 * comprobantes del mismo dinero. El original sale en UNA (la primera que lo imprime) y las demás reciben una copia marcada.
 */
class PrinterServiceUnOriginalTest {

    private fun servicio(): PrinterService {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return spyk(PrinterService(context, mockk(relaxed = true), mockk(relaxed = true)))
    }

    private val recibo = ReceiptData(orderNumber = "AQ0000000000A1", orderType = "", items = emptyList(), subtotal = 0, taxAmount = 0, total = 0, venueName = "LG")
    private val caja = SavedPrinter(id = "caja", name = "Caja", connectionType = "wifi", address = "10.0.0.1", port = 9100)
    private val barra = caja.copy(id = "barra", name = "Barra")

    @Test
    fun `P1 Codex r3 #11 con dos impresoras de recibos el original sale en UNA y la otra recibe una copia marcada`() = runBlocking {
        val service = servicio()
        every { service.getPrinters(PrinterRole.RECEIPT) } returns listOf(caja, barra)
        val impresos = mutableListOf<Pair<String, ReceiptData>>()
        coEvery { service.printReceipt(any(), any()) } answers { impresos += secondArg<SavedPrinter>().id to firstArg<ReceiptData>() }

        assertEquals(PrinterService.PrintOutcome.Printed(2), service.imprimirOriginal(recibo))

        assertEquals(listOf("caja", "barra"), impresos.map { it.first })
        assertNull("el original, una vez", impresos[0].second.reprintedAt)
        assertNotNull("la otra impresora recibe una COPIA", impresos[1].second.reprintedAt)
    }

    @Test
    fun `si la primera no tiene papel, el original sale en la siguiente y no queda ninguna copia de mas`() = runBlocking {
        val service = servicio()
        every { service.getPrinters(PrinterRole.RECEIPT) } returns listOf(caja, barra)
        val impresos = mutableListOf<Pair<String, ReceiptData>>()
        coEvery { service.printReceipt(any(), caja) } throws com.avoqado.pos.printing.data.model.PrinterException.OutOfPaper()
        coEvery { service.printReceipt(any(), barra) } answers { impresos += "barra" to firstArg<ReceiptData>() }

        assertEquals(PrinterService.PrintOutcome.Printed(1), service.imprimirOriginal(recibo))

        assertNull("el original salió en la barra", impresos.single().second.reprintedAt)
    }

    /**
     * Codex código POS r5 #1: Windows acepta TODOS los bytes del original y falla al cerrar el trabajo (EndDocPrinter; el texto es el de
     * `ProtocoloDeWindows`). El papel pudo salir: la segunda impresora recibe COPIA, nunca otro original del mismo folio.
     */
    @Test
    fun `P1 Codex POS r5 #1 Windows falla al cerrar el trabajo del original - la otra impresora recibe COPIA, no otro original`() = runBlocking {
        val service = servicio()
        every { service.getPrinters(PrinterRole.RECEIPT) } returns listOf(caja, barra)
        val impresos = mutableListOf<Pair<String, ReceiptData>>()
        coEvery { service.printReceipt(any(), caja) } answers {
            impresos += "caja" to firstArg<ReceiptData>()   // los bytes ya salieron…
            throw com.avoqado.pos.printing.data.model.PrinterException.PrintFailed("La impresora «Caja» no recibió el ticket completo")
        }
        coEvery { service.printReceipt(any(), barra) } answers { impresos += "barra" to firstArg<ReceiptData>() }

        assertEquals(PrinterService.PrintOutcome.Printed(1), service.imprimirOriginal(recibo))

        assertNull("el original pudo salir en la caja", impresos[0].second.reprintedAt)
        assertNotNull("la barra recibe una COPIA marcada", impresos[1].second.reprintedAt)
    }

    @Test
    fun `P1 Codex POS r5 #1 un original que pudo salir es Failed aunque la otra impresora no tenga papel`() = runBlocking {
        val service = servicio()
        every { service.getPrinters(PrinterRole.RECEIPT) } returns listOf(caja, barra)
        val impresos = mutableListOf<Pair<String, ReceiptData>>()
        coEvery { service.printReceipt(any(), caja) } answers {
            impresos += "caja" to firstArg<ReceiptData>()
            throw com.avoqado.pos.printing.data.model.PrinterException.PrintFailed("La impresora «Caja» no recibió el ticket completo")
        }
        coEvery { service.printReceipt(any(), barra) } throws com.avoqado.pos.printing.data.model.PrinterException.OutOfPaper()

        val r = service.imprimirOriginal(recibo)

        assertTrue("dudoso, no «sin papel»: $r", r is PrinterService.PrintOutcome.Failed)
        // La reimpresión manual de siempre no cambia: ahí gana «sin papel», el motivo accionable.
        coEvery { service.printReceipt(any(), caja) } throws com.avoqado.pos.printing.data.model.PrinterException.PrintFailed("x")
        assertEquals(PrinterService.PrintOutcome.OutOfPaper, service.manualPrintReceipt(recibo))
    }

    @Test
    fun `la reimpresion manual de siempre no cambia`() = runBlocking {
        val service = servicio()
        every { service.getPrinters(PrinterRole.RECEIPT) } returns listOf(caja, barra)
        val impresos = mutableListOf<ReceiptData>()
        coEvery { service.printReceipt(any(), any()) } answers { impresos += firstArg<ReceiptData>() }

        service.manualPrintReceipt(recibo)

        assertTrue(impresos.size == 2 && impresos.all { it.reprintedAt == null })
    }
}
