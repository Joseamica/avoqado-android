package com.avoqado.pos.pos.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** D6 (vales-ciclo): la lista de «Carritos guardados» decía «Helado de Vainilla x1» para 0.435 kg. */
class SavedCartItemTest {

    private fun item(name: String, quantity: Int, weightKg: Double? = null) = SavedCartItem(
        productId = "p1", name = name, unitPrice = 5000, quantity = quantity, weightKg = weightKg,
    )

    @Test
    fun `una linea por peso muestra el peso y no x1`() {
        assertEquals("Helado de Vainilla 0.435 kg", item("Helado de Vainilla", 1, 0.435).resumenEnLista)
    }

    @Test
    fun `el peso sale con tres decimales como en el carrito`() {
        assertEquals("Queso 1.500 kg", item("Queso", 1, 1.5).resumenEnLista)
    }

    @Test
    fun `una linea por pieza sigue mostrando la cantidad`() {
        assertEquals("Café x2", item("Café", 2).resumenEnLista)
    }

    @Test
    fun `un carrito guardado antes del peso abre con su cantidad`() {
        val viejo = Json { ignoreUnknownKeys = true }.decodeFromString(
            SavedCartItem.serializer(),
            """{"productId":"p1","name":"Café","unitPrice":3500,"quantity":3}""",
        )
        assertEquals("Café x3", viejo.resumenEnLista)
    }
}
