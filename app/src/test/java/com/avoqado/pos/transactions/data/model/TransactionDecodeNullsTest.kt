package com.avoqado.pos.transactions.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 UN MODIFICADOR SIN NOMBRE NO PUEDE COSTAR LA VENTA ENTERA.
 *
 * Medido en la Sunmi el 2026-09-11, con el logcat en la mano:
 *
 *     ❌ Transaction detail fetch error: Unexpected JSON token at offset 647:
 *        Expected string literal but 'null' literal was found
 *        at path: $.transaction.items[0].modifiers[0].name
 *
 * El servidor respondió **200** y la app no pudo abrir el detalle: el cajero se quedaba en
 * «Selecciona una transacción», sin poder llegar siquiera al botón de reembolsar. En
 * kotlinx.serialization un valor por defecto (`= ""`) sólo cubre que la llave FALTE; un
 * `null` explícito en un campo no anulable lanza y se lleva la decodificación completa.
 *
 * Producción está limpia hoy (medido: 0 de 4 006 `OrderItemModifier` con nombre nulo), así
 * que esto es endurecimiento, no una urgencia. Pero el decodificador no puede ser más
 * estricto que el contrato del servidor.
 */
class TransactionDecodeNullsTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** El JSON es el que se capturó del servidor, recortado a lo que importa. */
    private val respuestaConModificadorSinNombre = """
        {
          "id": "cmtwfpkye06c5c996cohq3pfr",
          "amount": 77.91,
          "tipAmount": 17.91,
          "method": "DEBIT_CARD",
          "status": "COMPLETED",
          "items": [
            {
              "id": "cmtwfpky206c1c9963zyd7mtj",
              "productName": "Té Helado",
              "quantity": 2,
              "unitPrice": 30,
              "total": 60,
              "modifiers": [{ "name": null, "price": 0 }]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `una venta con un modificador sin nombre se decodifica igual`() {
        val transaccion = json.decodeFromString<Transaction>(respuestaConModificadorSinNombre)

        assertEquals("cmtwfpkye06c5c996cohq3pfr", transaccion.id)
        assertEquals(1, transaccion.items.size)
        assertEquals("Té Helado", transaccion.items[0].productName)
        assertNull(transaccion.items[0].modifiers[0].name)
    }

    @Test
    fun `un modificador con nombre sigue llegando entero`() {
        val conNombre = respuestaConModificadorSinNombre.replace("\"name\": null", "\"name\": \"Extra queso\"")

        val transaccion = json.decodeFromString<Transaction>(conNombre)

        assertEquals("Extra queso", transaccion.items[0].modifiers[0].name)
    }

    /** La llave ausente y la llave en `null` significan lo mismo y ninguna puede lanzar. */
    @Test
    fun `un modificador sin la llave name tampoco rompe`() {
        val sinLlave = respuestaConModificadorSinNombre.replace("\"name\": null, ", "")

        val transaccion = json.decodeFromString<Transaction>(sinLlave)

        assertNull(transaccion.items[0].modifiers[0].name)
    }
}
