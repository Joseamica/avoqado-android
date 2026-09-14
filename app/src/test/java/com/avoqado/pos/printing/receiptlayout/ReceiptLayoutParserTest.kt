package com.avoqado.pos.printing.receiptlayout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Cómo lee la app la receta que manda el servidor (spec § 9): elemento por elemento, descartando
 * lo que no reconoce; si tras descartar la receta ya no es íntegra, se imprime la canónica embebida.
 */
class ReceiptLayoutParserTest {

    private fun canonical(): MutableList<JsonElement> = Json.parseToJsonElement(CanonicalLayout.JSON).jsonArray.toMutableList()
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `P1 la canonica embebida es la del servidor - mismo hash`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(CanonicalLayout.JSON.toByteArray(Charsets.UTF_8))
        val hex = sha.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(CanonicalLayout.TEMPLATE_HASH, hex.take(16))
    }

    @Test
    fun `P1 la canonica embebida coincide bloque a bloque con la del golden repartido`() {
        val golden = File(javaClass.classLoader!!.getResource("receipt-layout/golden/canonical.48.json")!!.toURI())
        val blocks = Json.parseToJsonElement(golden.readText()).jsonObject["blocks"]
        assertEquals(ReceiptLayoutParser.parseTolerant(blocks).blocks, CanonicalLayout.BLOCKS)
        assertEquals(20, CanonicalLayout.BLOCKS.size)
        assertTrue(ReceiptLayoutParser.isIntact(CanonicalLayout.BLOCKS))
    }

    @Test
    fun `los defaults se llenan y las llaves desconocidas se ignoran`() {
        val r = ReceiptLayoutParser.parseTolerant(json("""[{"type":"logo"},{"type":"staff","color":"red"}]"""))
        assertEquals(listOf(ReceiptBlock.Logo(LogoSize.M, Align.CENTER), ReceiptBlock.Staff), r.blocks)
        assertEquals(0, r.dropped)
    }

    @Test
    fun `P1 un tipo desconocido se descarta y la receta sigue siendo la del negocio`() {
        val raw = canonical().apply { add(2, json("""{"type":"hologram"}""")) }
        val r = ReceiptLayoutParser.effective(JsonArray(raw))
        assertEquals(1, r.dropped)
        assertFalse(r.usedFallback)
        assertEquals(CanonicalLayout.BLOCKS, r.blocks)
    }

    @Test
    fun `P1 un tipo conocido con forma invalida se descarta, igual que en Zod`() {
        val largo = "x".repeat(49)
        val malos = listOf(
            """{"type":"separator","style":"wavy"}""",
            """{"type":"totals","showTax":null}""",
            """{"type":"totals","showTax":"true"}""",
            """{"type":"text","lines":[]}""",
            """{"type":"text","lines":["1","2","3","4","5","6","7"]}""",
            """{"type":"text","lines":["$largo"]}""",
            """{"type":"text"}""",
            """{"type":"qr","caption":7}""",
            """"no soy objeto"""",
            """{"type":null}""",
        )
        for (m in malos) assertEquals(m, 1, ReceiptLayoutParser.parseTolerant(JsonArray(listOf(json(m)))).dropped)
    }

    @Test
    fun `P1 sin un obligatorio, con la firma fuera del final o con un ESC en el texto, se imprime la canonica`() {
        val sinTotales = canonical().filterNot { it.jsonObject["type"] == JsonPrimitive("totals") }
        assertTrue(ReceiptLayoutParser.effective(JsonArray(sinTotales)).usedFallback)

        val firmaEnMedio = canonical().also { l -> val firma = l.removeAt(l.size - 1); l.add(3, firma) }
        assertTrue(ReceiptLayoutParser.effective(JsonArray(firmaEnMedio)).usedFallback)

        val conEsc = canonical().also { l ->
            l.add(0, buildJsonObject { put("type", "text"); put("lines", buildJsonArray { add(JsonPrimitive("a" + Char(0x1B) + "b")) }) })
        }
        assertTrue(ReceiptLayoutParser.effective(JsonArray(conEsc)).usedFallback)
    }

    @Test
    fun `sin receta o con algo que no es lista, la canonica sin descartes`() {
        for (raw in listOf(null, JsonNull, json("""{"type":"logo"}"""))) {
            val r = ReceiptLayoutParser.effective(raw)
            assertTrue(r.usedFallback)
            assertEquals(0, r.dropped)
            assertEquals(CanonicalLayout.BLOCKS, r.blocks)
        }
    }

    @Test
    fun `los topes por tipo se respetan - 9 textos no son una receta integra`() {
        val nueve = canonical().also { l -> repeat(9) { l.add(0, json("""{"type":"text","lines":["hola"]}""")) } }
        assertTrue(ReceiptLayoutParser.effective(JsonArray(nueve)).usedFallback)
    }
}
