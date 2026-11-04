package com.avoqado.pos.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * D22 (diseño r2 §A, prueba de guardia `r1-1b`): la única escritura que deja un cobro de `pending_payments` en `SYNCED` es
 * `registrarResultadoQ15` con `aplicado` verdadero. Cada llamada que puede hacerlo tiene que avisar a
 * [AvisosDeVentasSubidas], o Ventas e Informes no se enteran de que el cobro subió. Si aparece una llamada nueva, esta
 * prueba truena: agrégale el aviso y anótala aquí.
 */
class ConfirmadoresAvisanTest {
    private val fuentes = File("src/main/java/com/avoqado/pos")

    /** (archivo, tercer argumento) de cada llamada a `registrarResultadoQ15` de los cobros que NO es un `false` literal. */
    private fun llamadasQuePuedenConfirmar(): List<Pair<String, String>> = fuentes.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.name != "SyncIntentEntity.kt" } // ésa es la de las operaciones (ACK)
        .flatMap { archivo ->
            val texto = archivo.readText()
            Regex("""(?<!fun )registrarResultadoQ15\(""").findAll(texto).map { coincidencia ->
                archivo.name to argumentos(texto, coincidencia.range.last + 1).getOrElse(2) { "?" }
            }
        }
        .filter { (_, aplicado) -> aplicado != "false" }
        .toList()
        .sortedWith(compareBy({ it.first }, { it.second }))

    /** Los argumentos de la llamada que abre en [desde] (después del paréntesis), separados por las comas de primer nivel. */
    private fun argumentos(texto: String, desde: Int): List<String> {
        val partes = mutableListOf<String>()
        var nivel = 0
        val actual = StringBuilder()
        for (c in texto.substring(desde)) {
            when {
                c == '(' -> { nivel++; actual.append(c) }
                c == ')' && nivel == 0 -> { partes += actual.toString().trim(); return partes }
                c == ')' -> { nivel--; actual.append(c) }
                c == ',' && nivel == 0 -> { partes += actual.toString().trim(); actual.clear() }
                else -> actual.append(c)
            }
        }
        return partes
    }

    @Test fun `r1-1b - toda escritura que puede dejar un cobro SYNCED tiene su aviso`() {
        assertEquals(
            "Si sumaste una llamada a registrarResultadoQ15 que puede confirmar un cobro, avisa a AvisosDeVentasSubidas (D22)",
            listOf(
                "OrderRepository.kt" to "true",
                "PaymentSyncService.kt" to "evaluation.aplicado",
                "PendingPaymentDao.kt" to "true", // recuperarResultadoPorLlaveTxQ15 ← PaymentSyncService.recuperarResultadoPorLlaveQ15
            ),
            llamadasQuePuedenConfirmar(),
        )
    }

    @Test fun `r1-1 - cada confirmador real avisa`() {
        fun avisos(ruta: String) = Regex("""avisosDeVentas\.subio\(""").findAll(File(fuentes, ruta).readText()).count()
        assertEquals("handleSyncResult y recuperarResultadoPorLlaveQ15", 2, avisos("payment/data/PaymentSyncService.kt"))
        assertEquals("el cobro en línea con fila", 1, avisos("payment/data/OrderRepository.kt"))
        assertEquals("conservarAck: cualquier ACK de la cola", 1, avisos("core/data/sync/SyncOutbox.kt"))
        val porLlave = Regex("""dao\.recuperarResultadoPorLlaveQ15\(""").findAll(File(fuentes, "payment/data/PaymentSyncService.kt").readText()).count()
        assertEquals("la recuperación por llave sólo la llama la cola", 1, porLlave)
    }

    @Test fun `el contador solo crece y es por sucursal`() {
        val avisos = AvisosDeVentasSubidas()
        avisos.subio("a")
        avisos.subio("a")
        avisos.subio("b")
        avisos.subio("")
        assertEquals(2L, avisos.de("a"))
        assertEquals(1L, avisos.de("b"))
        assertEquals(mapOf("a" to 2L, "b" to 1L), avisos.porSucursal.value)
    }
}
