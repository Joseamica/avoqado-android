package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.data.model.buildOrderItemRequests
import com.avoqado.pos.tables.data.buildRepeatedTableLines
import com.avoqado.pos.tables.data.buildTableRoundRequests
import com.avoqado.pos.tables.data.OrderDetailItem
import com.avoqado.pos.tables.data.OrderDetailModifier
import com.avoqado.pos.tables.data.AddOrderItemRequest
import com.avoqado.pos.areatickets.data.IssueAreaTicketLineRequest
import com.avoqado.pos.areatickets.presentation.intentoDeVale
import com.avoqado.pos.pos.presentation.cart.CartState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D40 (Flautas Coyoacán): la nota de cada parte del paquete viaja como `modifierNotes: { modifierId: nota }` en la venta de
 * Mostrador (en línea, efectivo sin red y «Pagar después» arman este MISMO cuerpo) y en la ronda de Mesas. Sin notas, el cuerpo
 * queda byte a byte como antes. `buildCreateOrderPayload` arma el JSON a mano: un campo del modelo que no se escribe ahí se pierde.
 */
class OrderPayloadNotaPorParteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun paquete(vararg partes: SelectedModifier) = CartItem(
        type = CartItemType.ProductItem("paquete"), name = "Paquete Completo", unitPrice = 15000, selectedModifiers = partes.toList(),
    )

    private val consome = SelectedModifier("g-sopa", "Sopa", "consome", "Consomé", 0, note = "sin cebolla")
    private val cochinita = SelectedModifier("g-flautas", "Flautas", "cochinita", "Flautas Cochinita", 0, note = "salsa combinada")
    private val agua = SelectedModifier("g-bebida", "Bebida", "agua", "Agua", 0)

    private fun renglonEnElCable(item: CartItem): JsonObject {
        val body = OrderRepository.buildCreateOrderPayload(
            request = CreateOrderRequest(items = buildOrderItemRequests(listOf(item)), subtotal = 15000, total = 15000, paymentMethod = "CASH"),
            staffId = "staff-1",
        )
        return json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray.single().jsonObject
    }

    @Test
    fun `P1 Mostrador manda la nota de cada parte junto a sus ids`() {
        val renglon = renglonEnElCable(paquete(consome, cochinita, cochinita, agua))
        assertEquals(listOf("consome", "cochinita", "cochinita", "agua"), renglon["modifierIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        val notas = renglon["modifierNotes"]!!.jsonObject
        assertEquals(mapOf("consome" to "sin cebolla", "cochinita" to "salsa combinada"), notas.mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun `P1 sin notas la llave no viaja y el cuerpo es el de siempre`() {
        val renglon = renglonEnElCable(paquete(consome.copy(note = null), agua))
        assertFalse(renglon.containsKey("modifierNotes"))
    }

    /** El `Json` del Retrofit compartido (`NetworkModule.provideJson`): manda los nulos y los default explícitos. */
    private val jsonDeRetrofit = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Test
    fun `P1 Mesas manda la nota de cada parte en la ronda y sin notas la llave no viaja ni como null (Codex r2 #5)`() {
        val ronda = buildTableRoundRequests(listOf(paquete(consome, agua))).single()
        assertEquals(mapOf("consome" to "sin cebolla"), ronda.modifierNotes)
        val conNota = jsonDeRetrofit.encodeToJsonElement(AddOrderItemRequest.serializer(), ronda).jsonObject
        assertEquals("sin cebolla", conNota["modifierNotes"]!!.jsonObject["consome"]!!.jsonPrimitive.content)

        val sinNotas = buildTableRoundRequests(listOf(paquete(agua))).single()
        assertNull(sinNotas.modifierNotes)
        assertFalse(jsonDeRetrofit.encodeToJsonElement(AddOrderItemRequest.serializer(), sinNotas).jsonObject.containsKey("modifierNotes"))
    }

    @Test
    fun `P1 vales - la comanda del vale lleva la nota y Recuperar no toma por igual un carrito que solo cambio la nota (Codex r1 #3)`() {
        val conNota = intentoDeVale(CartState(items = listOf(paquete(consome, agua))))
        assertEquals(listOf("Consomé\nNota: sin cebolla", "Agua"), conNota.comanda.single().modificadores)
        assertEquals(mapOf("consome" to "sin cebolla"), conNota.lineas.single().modifierNotes)
        val otraNota = intentoDeVale(CartState(items = listOf(paquete(consome.copy(note = "con cebolla"), agua))))
        assertFalse(conNota.mismaMercanciaQue(otraNota.lineas))
        // Codex r3 #2: los mismos renglones con los extras elegidos en otro orden son la misma mercancía.
        val cochinita = SelectedModifier("g-flautas", "Flautas", "cochinita", "Flautas Cochinita", 0, note = "salsa")
        val dos = listOf(paquete(consome, cochinita).copy(id = "a"), paquete(agua).copy(id = "b"))
        val alReves = listOf(paquete(cochinita, consome).copy(id = "c"), paquete(agua).copy(id = "d"))
        assertTrue(intentoDeVale(CartState(items = dos)).mismaMercanciaQue(intentoDeVale(CartState(items = alReves)).lineas))
        // Codex r4 #2: notas con texto que imita el mapa («x, b=y») no hacen empatar renglones distintos al ordenar.
        val a = SelectedModifier("g", "G", "a", "A", 0); val b = SelectedModifier("g", "G", "b", "B", 0)
        val uno = listOf(paquete(a.copy(note = "x, b=y"), b.copy(note = "z")).copy(id = "1"), paquete(a.copy(note = "x"), b.copy(note = "y, b=z")).copy(id = "2"))
        val otro = listOf(paquete(b.copy(note = "y, b=z"), a.copy(note = "x")).copy(id = "3"), paquete(b.copy(note = "z"), a.copy(note = "x, b=y")).copy(id = "4"))
        assertTrue(intentoDeVale(CartState(items = uno)).mismaMercanciaQue(intentoDeVale(CartState(items = otro)).lineas))
        val sinNotas = intentoDeVale(CartState(items = listOf(paquete(consome.copy(note = null), agua))))
        assertFalse(jsonDeRetrofit.encodeToJsonElement(IssueAreaTicketLineRequest.serializer(), sinNotas.lineas.single()).jsonObject.containsKey("modifierNotes"))
    }

    @Test
    fun `P2 repetir un tiempo de Mesas conserva la nota que devolvio el SERVIDOR`() {
        val linea = buildRepeatedTableLines(listOf(OrderDetailItem(
            id = "oi-1", productId = "paquete", productName = "Paquete Completo", quantity = 1, unitPrice = 150.0,
            modifiers = listOf(OrderDetailModifier("consome", "Consomé", 0.0, notes = "sin cebolla"), OrderDetailModifier("agua", "Agua", 0.0)),
        ))).single()
        assertEquals(listOf("sin cebolla", null), linea.selectedModifiers.map { it.note })
    }
}
