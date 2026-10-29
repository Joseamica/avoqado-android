package com.avoqado.pos.giftcards

import org.junit.Assert.*
import org.junit.Test

class CreadoresDeCuentaDeMostradorTest {
    @Test fun todoCreadorEstaClasificado() {
        val creadores = mapOf(
            "com/avoqado/pos/giftcards/data/CobroConSaldoCoordinator.kt:cobrarNuevaCuenta:cobrarNuevaCuenta" to "DIARIO",
            "com/avoqado/pos/giftcards/data/CobroConSaldoCoordinator.kt:enviar:cobrarNuevaCuenta" to "DIARIO",
            "com/avoqado/pos/giftcards/data/ResolutorDeSolicitudesDeTarjeta.kt:resolver:vender" to "DIARIO",
            "com/avoqado/pos/giftcards/presentation/VenderTarjetaViewModel.kt:cobrar:vender" to "DIARIO",
            "com/avoqado/pos/payment/presentation/PaymentFlowViewModel.kt:cobrarConSaldo:cobrarNuevaCuenta" to "DIARIO",
            "com/avoqado/pos/payment/presentation/PaymentFlowViewModel.kt:confirmPayment:createOrder" to "C1_C2",
            "com/avoqado/pos/payment/presentation/PaymentFlowViewModel.kt:processCashPayment:createOrder" to "C1_C2",
            "com/avoqado/pos/payment/presentation/PaymentFlowViewModel.kt:processCashPayment:recordFastCashPayment" to "T10",
            "com/avoqado/pos/payment/presentation/PaymentFlowViewModel.kt:materializeAreaTicketCheckout:materialize" to "T10",
            // «Pagar después» de develop (27-oct): sin premio la venta sale del carrito ANTES de mandarse; con premio se queda y se marca.
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:pagarDespues:createPayLaterOrder" to "C3",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:diferirConFila:mandar" to "C3_FUERA",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:diferirConPremio:mandar" to "C3",
            "com/avoqado/pos/payment/data/PayLaterQueue.kt:procesar:mandar" to "C4",
            "com/avoqado/pos/payment/data/PayLaterQueue.kt:mandar:enviarPagarDespues" to "C4",
            // `mandar` también nombra la impresión de etiquetas: no crea cuentas.
            "com/avoqado/pos/inventory/presentation/PriceLabelViewModel.kt:imprimirComprobante:mandar" to "NO_CREA",
            "com/avoqado/pos/inventory/presentation/PriceLabelViewModel.kt:imprimirLista:mandar" to "NO_CREA",
            "com/avoqado/pos/tables/data/TableServiceRepository.kt:openTable:openTable" to "T8",
            "com/avoqado/pos/tables/data/TableServiceRepository.kt:mergeOrders:mergeOrders" to "T8",
            "com/avoqado/pos/tables/data/TableServiceRepository.kt:splitOrderBySeat:splitOrderBySeat" to "T8",
            "com/avoqado/pos/tables/data/TableServiceRepository.kt:splitOrder:splitOrder" to "T8",
            "com/avoqado/pos/tables/presentation/TableOrderViewModel.kt:mergeFrom:mergeOrders" to "T8",
            "com/avoqado/pos/tables/presentation/TableOrderViewModel.kt:splitBySeat:splitOrderBySeat" to "T8",
            "com/avoqado/pos/tables/presentation/TableOrderViewModel.kt:splitItems:splitOrder" to "T8",
            "com/avoqado/pos/tables/presentation/TablesViewModel.kt:openTableAndStartOrdering:openTable" to "T8",
            "com/avoqado/pos/tables/presentation/TablesViewModel.kt:openTableAndStartOrdering:openTableOffline" to "T8",
            "com/avoqado/pos/tables/presentation/TablesViewModel.kt:bulkUnir:mergeOrders" to "T8"
        )
        val reales = InventarioDeFuentes6.llamadas("\\b(createOrder|createPayLaterOrder|materialize|openTable|openTableOffline|splitOrderBySeat|splitOrder|mergeOrders|cobrarNuevaCuenta|recordFastCashPayment|vender|mandar|enviarPagarDespues)\\s*\\(")
        assertEquals("Creador añadido o retirado: " + reales.joinToString { "${it.key}:${it.linea}" },creadores.keys,reales.map { it.key }.toSet())
        for (c in reales.filter { creadores[it.key] == "C1_C2" || creadores[it.key] == "C3" && (it.key.endsWith(":createOrder") || it.key.endsWith(":mandar")) }) {
            val cuerpo = c.texto.substring(c.inicio,c.posicion)
            assertTrue("Marca ANTES del POST: ${c.key}:${c.linea}", cuerpo.contains(if (creadores[c.key] == "C1_C2") "onCreacionIncierta(" else "marcarCreacionIncierta("))
        }
        for (c in reales.filter { creadores[it.key] == "C3_FUERA" }) {
            assertTrue("La venta sale del carrito ANTES del POST: ${c.key}:${c.linea}", c.texto.substring(c.inicio,c.posicion).contains("separarDelCarrito("))
        }
        // C4 manda el payload durable directamente: no vuelve a tomar el carrito.
        val cola = java.io.File("src/main/java/com/avoqado/pos/payment/data/PaymentSyncService.kt").readText()
        assertTrue(cola.contains("private suspend fun resolveMissingOrderId("))
        assertTrue(cola.contains(".post(pedidoConLaCaja(orderRequestJson, cajaParaElServidor).toRequestBody(JSON_MEDIA))"))
    }
}

internal object InventarioDeFuentes6 {
    data class Llamada(val key: String, val linea: Int, val inicio: Int, val fin: Int, val posicion: Int, val texto: String)
    private val comentariosYCadenas = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"")
    fun llamadas(pattern: String): List<Llamada> = java.io.File("src/main/java").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val original = file.readText()
            val fuente = comentariosYCadenas.replace(original) { it.value.map { c -> if (c == '\n') c else ' ' }.joinToString("") }
            val funciones = Regex("\\bfun\\s+(?:<[^>]+>\\s*)?(?:[\\w<>?.]+\\.)?(\\w+)\\s*\\(").findAll(fuente).toList()
            val regiones = funciones.mapIndexedNotNull { index, f ->
                var p = f.range.last + 1; var parentesis = 1
                while (p < fuente.length && parentesis > 0) { if (fuente[p]=='(') parentesis++; if (fuente[p]==')') parentesis--; p++ }
                val inicio = fuente.indexOf('{',p)
                if (inicio < 0 || inicio > (funciones.getOrNull(index+1)?.range?.first ?: fuente.length) || Regex("\\b(?:class|interface)\\b").containsMatchIn(fuente.substring(p,inicio))) null
                else {
                    var fin = inicio + 1; var depth = 1
                    while (fin < fuente.length && depth > 0) { if (fuente[fin]=='{') depth++; if (fuente[fin]=='}') depth--; fin++ }
                    Triple(inicio,fin,f.groupValues[1])
                }
            }
            Regex(pattern).findAll(fuente).filterNot {
                Regex("\\b(?:fun|class)\\s+(?:\\w+\\.)?$").containsMatchIn(fuente.substring((it.range.first-40).coerceAtLeast(0),it.range.first))
            }.map { m ->
                val region = regiones.filter { m.range.first in it.first until it.second }.maxByOrNull { it.first }
                val call = m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m.value.replace(Regex("\\s+"), " ").trim()
                val relative = file.relativeTo(java.io.File("src/main/java")).invariantSeparatorsPath
                Llamada("$relative:${region?.third ?: "<fuera>"}:$call", fuente.take(m.range.first).count { it=='\n' }+1,
                    region?.first ?: 0, region?.second ?: fuente.length, m.range.first, original)
            }.toList().asSequence()
        }.toList()
}
