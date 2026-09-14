package com.avoqado.pos.printing.receiptlayout

import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.MonoRaster
import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hex legible del golden de bytes: minúsculas y un renglón por cada salto de línea impreso. */
fun escPosHex(bytes: ByteArray): String = buildString {
    for (b in bytes) {
        append("%02x".format(b.toInt() and 0xFF))
        if (b.toInt() == 0x0A) append('\n')
    }
    if (isEmpty() || last() != '\n') append('\n')
}

/**
 * El golden de BYTES (spec § 6): Android los genera desde las líneas del golden lógico e iOS
 * tiene que producir exactamente los mismos. Las imágenes usan un ráster FIJO: escalar un logo
 * depende del decodificador de cada sistema operativo y no es lo que se prueba aquí.
 *
 * Cada corrida escribe lo que produjo en `app/build/receipt-escpos-golden/`; así se genera el golden
 * la primera vez (Task 8, Step 7).
 */
class ReceiptEscPosGoldenTest {

    private val fixedLogo = MonoRaster(widthDots = 16, heightDots = 2, bits = byteArrayOf(0xF0.toByte(), 0x0F, 0x0F, 0xF0.toByte()))
    private val fixedMark = MonoRaster(widthDots = 8, heightDots = 1, bits = byteArrayOf(0xAA.toByte()))

    @Test
    fun `P1 los bytes del ticket coinciden con el golden de bytes en cada caso y ancho`() {
        // Se vacía antes: un .hex rancio de una corrida vieja (un caso renombrado) no debe llegar al servidor.
        val outDir = File("build/receipt-escpos-golden").apply { deleteRecursively(); mkdirs() }
        val expectedDir = javaClass.classLoader!!.getResource("receipt-layout/escpos")?.toURI()?.let(::File)
        val fallas = mutableListOf<String>()
        for (file in GoldenFixtures.files()) {
            val golden = GoldenFixtures.read(file)
            val paper = if (golden.width == 48) PaperWidth.MM80 else PaperWidth.MM58
            val bytes = ESCPOSPrinter(paper).renderReceipt(golden.lines) { ref, _ -> if (ref == ImageRef.LOGO) fixedLogo else fixedMark }
            val hex = escPosHex(bytes)
            val name = "${golden.name}.hex"
            File(outDir, name).writeText(hex)
            val expected = expectedDir?.let { File(it, name) }?.takeIf { it.exists() }?.readText()
            if (expected != hex) fallas += name
        }
        assertTrue("Sin golden de bytes o distinto: $fallas. Lo producido quedó en ${outDir.absolutePath}", fallas.isEmpty())
    }
}
