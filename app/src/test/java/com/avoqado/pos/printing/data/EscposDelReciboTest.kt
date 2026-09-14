package com.avoqado.pos.printing.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 La regla del TICKET EN BLANCO: el `ESCPOSPrinter` de un recibo SIEMPRE sale de
 * `PrinterService.escposFor(printer)`. Construirlo a mano pierde el `FS .` que la integrada de
 * Sunmi necesita antes del code page, y el papel sale **vacío** — sin error, sin log, sin nada
 * (defecto medido: memoria `project_ticket-blanco-sunmi`).
 *
 * Ninguna prueba de comportamiento la vigilaba: sustituir `escposFor(printer)` por un
 * `ESCPOSPrinter(printer.paperWidth)` a mano dentro de `printReceipt` dejaba las **2 332** pruebas
 * de la app en verde (medido en la revisión de la Task 9, sabotaje S5). Es una comprobación
 * ESTRUCTURAL de la FUENTE —mandar bytes a una Sunmi pide hardware— y se declara como tal:
 * demuestra que el cableado está escrito, no que la impresora lo imprima.
 *
 * Mismo patrón que `PrinterServiceReconnectTest.alguien llama a disconnectAll`, con su misma
 * lección: se mira el CUERPO de la función, no el archivo entero concatenado, porque un `contains`
 * suelto pasa aunque la llamada quede en otra función o comentada.
 */
class EscposDelReciboTest {

    private val printingSrc = File("src/main/java/com/avoqado/pos/printing")

    @Test
    fun `P1 printReceipt pide su ESCPOSPrinter a escposFor (si no, el papel de la Sunmi sale en blanco)`() {
        val archivo = File(printingSrc, "data/PrinterService.kt")
        assertTrue("no encontre ${archivo.path} — el test necesita ajustar la ruta", archivo.exists())

        val cuerpo = cuerpoDe(archivo.readText(), "suspend fun printReceipt(")
        assertTrue(
            "printReceipt dejo de pedirle el ESCPOSPrinter a escposFor(printer)",
            cuerpo.contains("escposFor(printer)"),
        )
        assertTrue(
            "printReceipt construye el ESCPOSPrinter a mano: pierde el `FS .` de la Sunmi y el ticket sale EN BLANCO",
            !cuerpo.contains("ESCPOSPrinter("),
        )
        // 🔴 El atajo hermano: conservar `escposFor` intacto (así la guarda de arriba no salta) y
        // mandar a imprimir con `escpos.generateReceipt(receipt)`. Compila, y el ticket vuelve a
        // salir con la canónica y con `info = null` — o sea SIN el RFC del negocio.
        assertTrue(
            "printReceipt volvio a imprimir por generateReceipt: el ticket sale con la canonica y SIN el RFC del negocio",
            !cuerpo.contains("generateReceipt("),
        )
        // 🔴 I3 (revisión de conjunto): sin exigir el embudo COMPLETO, alguien puede dejar
        // `escposFor` intacto y reescribir el resto con `printLine` directo — las 2 348 pruebas
        // siguen en verde y el ticket sale sin receta y sin el RFC del negocio (espejo de la
        // guarda de iOS, `PrinterServiceEmbudoGuardTests`).
        assertTrue(
            "printReceipt dejo de pedir el plan a ReceiptBranding: el ticket sale sin la receta del negocio",
            cuerpo.contains("receiptBranding.plan("),
        )
        assertTrue(
            "printReceipt dejo de pasar por ReceiptLayoutInterpreter.interpret: el ticket sale sin la receta del negocio",
            cuerpo.contains("ReceiptLayoutInterpreter.interpret("),
        )
    }

    @Test
    fun `P1 nadie mas en printing construye un ESCPOSPrinter a mano`() {
        assertEquals(
            "un ESCPOSPrinter construido fuera de escposFor imprime sin el `FS .` de la Sunmi: papel en blanco",
            emptyList<String>(),
            // Las dos ÚNICAS legítimas: la declaración de la clase y la fábrica del embudo.
            lineasProhibidas("ESCPOSPrinter(", listOf("class ESCPOSPrinter(", "fun escposFor(")),
        )
    }

    @Test
    fun `P1 ningun archivo de produccion de printing llama a generateReceipt`() {
        assertEquals(
            "generateReceipt imprime con la receta canonica y con `info = null`: el ticket sale sin la receta " +
                "del negocio y SIN SU RFC, que en un comprobante lo exige la ley. El camino de produccion es " +
                "printReceipt -> ReceiptBranding.plan -> ReceiptLayoutInterpreter -> renderReceipt",
            emptyList<String>(),
            // La ÚNICA legítima: su propia declaración. Las pruebas sí pueden llamarla (viven en
            // src/test, fuera de este barrido) y `CorteTicketBuilder` está en cashdrawer/, no aquí.
            lineasProhibidas("generateReceipt(", listOf("fun generateReceipt(")),
        )
    }

    /** Las líneas de PRODUCCIÓN de `printing/` que contienen [patron] y no están permitidas. */
    private fun lineasProhibidas(patron: String, permitidas: List<String>): List<String> {
        assertTrue("no encontre ${printingSrc.path}", printingSrc.isDirectory)
        return printingSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { archivo ->
                archivo.readText().lineSequence().withIndex()
                    .filter { (_, linea) -> patron in linea }
                    .filter { (_, linea) -> permitidas.none { it in linea } }
                    .map { (i, linea) -> "${archivo.name}:${i + 1} ${linea.trim()}" }
            }
            .toList()
    }

    @Test
    fun `P2 generateReceipt sigue marcada como solo para pruebas`() {
        val archivo = File(printingSrc, "data/ESCPOSPrinter.kt")
        val texto = archivo.readText()
        // Sin la marca, cualquiera la vuelve a llamar desde produccion y el ticket sale sin el
        // encabezado fiscal del negocio (pasa `info = null`) y con la receta canonica.
        assertTrue(
            "generateReceipt perdio su @VisibleForTesting: nada impide volver a llamarla desde produccion",
            Regex("""@(androidx\.annotation\.)?VisibleForTesting\s*\n\s*fun generateReceipt\(""").containsMatchIn(texto),
        )
    }

    /** El cuerpo de la función que empieza en [firma], por conteo de llaves. */
    private fun cuerpoDe(fuente: String, firma: String): String {
        val inicio = fuente.indexOf(firma)
        assertTrue("no encontre la firma `$firma`", inicio >= 0)
        val abre = fuente.indexOf('{', inicio)
        assertTrue("`$firma` no tiene cuerpo con llaves", abre >= 0)
        var nivel = 0
        for (i in abre until fuente.length) {
            when (fuente[i]) {
                '{' -> nivel++
                '}' -> {
                    nivel--
                    if (nivel == 0) return fuente.substring(abre, i + 1)
                }
            }
        }
        throw AssertionError("no encontre el cierre del cuerpo de `$firma`")
    }
}
