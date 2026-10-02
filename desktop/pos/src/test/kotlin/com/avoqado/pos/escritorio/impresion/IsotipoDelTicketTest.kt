package com.avoqado.pos.escritorio.impresion

import android.graphics.BitmapFactory
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.RecursosDeImagen
import com.avoqado.pos.R
import com.avoqado.pos.escritorio.rutaDeRecurso
import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.RasterImages
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.printing.receiptlayout.CanonicalLayout
import com.avoqado.pos.printing.receiptlayout.ImageRef
import com.avoqado.pos.printing.receiptlayout.ReceiptInputMapper
import com.avoqado.pos.printing.receiptlayout.ReceiptLayoutInterpreter
import java.nio.file.Files
import java.util.Date
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** El isotipo «Powered by Avoqado» del pie del ticket: el ticket de Windows ya no sale sin él. */
class IsotipoDelTicketTest {
    private val registroOriginal = RecursosDeImagen.rutaDe
    private val contexto = ContextoDeEscritorio(Files.createTempDirectory("isotipo"))

    // Como Main.kt: se registra la tabla antes de abrir la app.
    @BeforeTest fun registrar() { RecursosDeImagen.rutaDe = ::rutaDeRecurso }
    @AfterTest fun restaurar() { RecursosDeImagen.rutaDe = registroOriginal }

    @Test fun `decodeResource del isotipo no es null y mide lo mismo que el PNG`() {
        val png = checkNotNull(javaClass.classLoader.getResourceAsStream("android-res/drawable-nodpi/avoqado_logo_mark.png")).use { ImageIO.read(it) }
        val bitmap = assertNotNull(BitmapFactory.decodeResource(null, R.drawable.avoqado_logo_mark))
        assertEquals(png.width, bitmap.width)
        assertEquals(png.height, bitmap.height)
    }

    @Test fun `un id desconocido da null sin lanzar`() {
        assertNull(BitmapFactory.decodeResource(null, 123456789))
    }

    @Test fun `sin tabla registrada tambien da null sin lanzar`() {
        RecursosDeImagen.rutaDe = { null }
        assertNull(BitmapFactory.decodeResource(null, R.drawable.avoqado_logo_mark))
    }

    @Test fun `una ruta que no existe o un recurso que no es imagen dan null sin lanzar`() {
        RecursosDeImagen.rutaDe = { "android-res/no-existe.png" }
        assertNull(BitmapFactory.decodeResource(null, R.drawable.avoqado_logo_mark))
        RecursosDeImagen.rutaDe = { "android-res/drawable/ic_whatsapp.xml" }   // un vector: ImageIO no lo lee
        assertNull(BitmapFactory.decodeResource(null, R.drawable.ic_whatsapp))
    }

    @Test fun `RasterImages avoqadoMark con el codigo real de la app da un raster que cabe`() {
        val raster = assertNotNull(RasterImages.avoqadoMark(contexto, widthDots = 384))
        assertTrue(raster.widthDots in 1..384, "${raster.widthDots}")
        assertTrue(raster.heightDots > 0 && raster.bits.any { it.toInt() != 0 }, "el raster está vacío")
    }

    @Test fun `el ticket de venta con el pie Powered by Avoqado trae el raster GS v 0`() {
        val papel = PaperWidth.MM80
        val receipt = ReceiptData(
            orderNumber = "42", orderType = "En tienda",
            items = listOf(ReceiptItem(name = "Galleta", quantity = 2, unitPrice = 2250, totalPrice = 4500)),
            subtotal = 4500, taxAmount = 621, total = 4500, paymentMethod = "Efectivo",
            venueName = "Tienda de prueba", date = Date(1_788_372_300_000L),
        )
        val input = ReceiptInputMapper.map(receipt = receipt, info = null, hasLogo = false, timezone = "America/Mexico_City", appVersion = null)
        val lineas = ReceiptLayoutInterpreter.interpret(CanonicalLayout.BLOCKS, input, papel.charsPerLine)
        // Mismo cableado que ReceiptBranding.plan para ImageRef.AVOQADO_MARK.
        val bytes = ESCPOSPrinter(papel).renderReceipt(lineas) { ref, pct ->
            if (ref == ImageRef.AVOQADO_MARK) RasterImages.avoqadoMark(contexto, widthDots = papel.dots * pct / 100) else null
        }
        val gsv0 = byteArrayOf(0x1D, 0x76, 0x30)
        assertTrue(bytes.indexOfSubarray(gsv0) >= 0, "el ticket no trae el ráster GS v 0 del isotipo")
        // Contraste: sin el registro (como era antes) el ticket sale sin él.
        RecursosDeImagen.rutaDe = { null }
        val sinTabla = ESCPOSPrinter(papel).renderReceipt(lineas) { ref, pct ->
            if (ref == ImageRef.AVOQADO_MARK) RasterImages.avoqadoMark(contexto, widthDots = papel.dots * pct / 100 + 1) else null
        }
        assertEquals(-1, sinTabla.indexOfSubarray(gsv0), "sin tabla no debería haber ráster")
    }

    private fun ByteArray.indexOfSubarray(sub: ByteArray): Int {
        outer@ for (i in 0..size - sub.size) {
            for (j in sub.indices) if (this[i + j] != sub[j]) continue@outer
            return i
        }
        return -1
    }
}
