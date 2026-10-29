package com.avoqado.pos.giftcards
import com.avoqado.pos.giftcards.data.PruebaDeTarjeta.*
import com.avoqado.pos.giftcards.data.aJson
import com.avoqado.pos.giftcards.domain.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class ReconocedorDeTarjetaTest {
    @Test fun `Q12 tabla literal de 16 entradas`() {
        val t = "Xk3pQ9vLabcdefghij12"
        val casos = listOf(
            "https://pay.avoqado.io/tarjeta/$t" to PageToken(t),
            "  https://pay.avoqado.io/tarjeta/$t\n" to PageToken(t),
            "HTTPS://PAY.AVOQADO.IO/tarjeta/$t/" to PageToken(t),
            "http://localhost:5173/tarjeta/Xk3p_Q9-vLabcdefghij12" to PageToken("Xk3p_Q9-vLabcdefghij12"),
            "https://pay.avoqado.io/tarjeta/Xk3pQ9vLabcdefghij1" to null,
            "https://pay.avoqado.io/TARJETA/$t" to null,
            "https://pay.avoqado.io/tarjeta/$t?x=1" to null,
            "https://pay.avoqado.io/r/abc" to null,
            "K7QZ-ABCD-EFGH-JKMN-P" to Codigo("K7QZABCDEFGHJKMNP"),
            "k7qz abcd efgh jkmn p" to Codigo("K7QZABCDEFGHJKMNP"),
            "K7QZABCDEFGHJKMNP\r\n" to Codigo("K7QZABCDEFGHJKMNP"),
            "K7QZ-ABCD-EFGH-JKMN" to null,
            "K7QZ-ABCD-EFGH-JKMN-PQ" to null,
            "7501234567890" to null,
            "a1".repeat(24) to null,
            "" to null)
        assertEquals(16, casos.size)
        casos.forEachIndexed { i, (entrada, esperado) -> assertEquals("caso $i", esperado, ReconocedorDeTarjeta.reconocer(entrada)) }
    }
    @Test fun `unionF minusculas y mayusculas son la misma prueba y llave`() {
        val mayusculas = ReconocedorDeTarjeta.reconocer("K7QZABCDEFGHJKMNP")!!
        for (texto in listOf("k7qzabcdefghjkmnp", "k7Qz-AbCd-EfGh-JkMn-P", " k7qz abcd efgh jkmn p\r\n")) {
            val prueba = ReconocedorDeTarjeta.reconocer(texto)!!
            assertEquals(Codigo("K7QZABCDEFGHJKMNP"), prueba)
            assertEquals(mayusculas, prueba)
            assertEquals(mayusculas.hashCode(), prueba.hashCode())
            assertEquals("misma llave de prueba", 1, setOf(mayusculas, prueba).size)
            assertEquals(mayusculas.aJson(), prueba.aJson())
        }
    }
    @Test fun `scanner y paquete no escriben secretos`() {
        val root = File("src/main/java/com/avoqado/pos").takeIf { it.exists() } ?: File("app/src/main/java/com/avoqado/pos")
        val archivos = root.resolve("giftcards").walkTopDown().filter { it.extension == "kt" }.toList() + root.resolve("pos/presentation/scanner/BarcodeScannerView.kt")
        val malas = archivos.flatMap { f -> f.readLines().filter { Regex("Log\\.[dewiv]\\(").containsMatchIn(it) && Regex("\\$[a-zA-Z]|\\$\\{").containsMatchIn(it) } }
        assertEquals(emptyList<String>(), malas)
        val desktop = generateSequence(root.absoluteFile.toPath()) { it.parent }.firstOrNull { it.resolve("desktop/pos/src").toFile().exists() }
        assertNotNull(desktop)
        val scanners = desktop!!.resolve("desktop/pos/src").toFile().walkTopDown().filter { it.name == "BarcodeScannerView.kt" }.toList()
        assertEquals(1, scanners.size)
        assertFalse(scanners.single().readLines().any { "Log." in it && ("$" + "code" in it || "$" + "value" in it) })
    }
}
