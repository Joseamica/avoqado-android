package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.MonoRaster
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.receiptlayout.Align
import com.avoqado.pos.printing.receiptlayout.CanonicalLayout
import com.avoqado.pos.printing.receiptlayout.LogoSize
import com.avoqado.pos.printing.receiptlayout.ReceiptBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReceiptBrandingLogoTest {

    private val canonica = CanonicalLayout.BLOCKS

    @Test
    fun `sin bloque de logo no se carga ninguna imagen`() {
        var cargas = 0
        assertNull(printableLogo(canonica.filterNot { it is ReceiptBlock.Logo }, PaperWidth.MM80) { cargas++; MonoRaster(8, 1, byteArrayOf(0)) })
        assertEquals(0, cargas)
    }

    @Test
    fun `el logo se pide al ancho que dice la receta`() {
        val pedidos = mutableListOf<Int>()
        val raster = MonoRaster(widthDots = 8, heightDots = 1, bits = byteArrayOf(0))
        val chico = canonica.map { if (it is ReceiptBlock.Logo) ReceiptBlock.Logo(size = LogoSize.S, align = Align.RIGHT) else it }
        assertSame(raster, printableLogo(chico, PaperWidth.MM58) { pedidos += it; raster })
        assertEquals(listOf(153), pedidos) // 384 puntos × 40 % = 153
    }

    @Test
    fun `P2 un logo que no cabe en el papel o que no se pudo convertir es lo mismo que no tener logo`() {
        assertNull(printableLogo(canonica, PaperWidth.MM58) { MonoRaster(widthDots = 400, heightDots = 1, bits = ByteArray(50)) })
        assertNull(printableLogo(canonica, PaperWidth.MM58) { null })
    }
}
