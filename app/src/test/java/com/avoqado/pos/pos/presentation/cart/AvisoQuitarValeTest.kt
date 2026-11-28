package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketLine
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hz:7c75e287:D2. En la caja, quitar UN renglón de un vale escaneado quita el vale completo ([CartViewModel.removeItem]). Antes se
 * iba sin avisar; ahora la pantalla pregunta antes, como con el combo, por deslizar, el panel de detalle, el carrito del teléfono y
 * «Anular artículos». La cantidad a cero no aplica: un renglón de vale es fijo y no cambia de cantidad.
 */
class AvisoQuitarValeTest {

    private fun vale(id: String, vararg productos: String) = AreaTicket(
        id = id, code = "V-$id", status = "ISSUED", fulfillmentArea = AreaTicketArea("area-1", "Panadería", "PICKUP"),
        subtotal = "0.00", total = "0.00", issuedAt = "2026-11-27T10:00:00Z",
        lines = productos.mapIndexed { i, nombre ->
            AreaTicketLine(id = "l-$id-$i", clientLineId = "c-$id-$i", productNameSnapshot = nombre, quantity = "1", unitPrice = "10.00", total = "10.00")
        },
    )

    private fun renglones(ticket: AreaTicket) = ticket.lines.map { renglonDelVale(ticket, it) }

    private val normal = CartItem(id = "normal", type = CartItemType.CustomAmount, name = "Café", unitPrice = 3000)
    private val siete = renglones(vale("a", "2 CROISSANT", "Concha", "Dona", "Bolillo", "Telera", "Baguette", "Pan de muerto"))
    private val carrito = siete + normal

    @Test
    fun `el aviso sale para un renglon de un vale con su codigo y cuantos productos se van`() {
        val aviso = valeAQuitar(carrito, siete[0].id)!!
        assertEquals(siete[0].id, aviso.itemId)
        assertEquals(7, aviso.productos)
        assertEquals(
            "Este producto es del vale V-a. Se quitarán los 7 productos del vale; si el cliente sí se lleva alguno, agrégalo a mano.",
            aviso.aviso,
        )
    }

    @Test
    fun `el aviso no sale para un renglon normal`() {
        assertNull(valeAQuitar(carrito, normal.id))
    }

    @Test
    fun `el aviso no sale para un vale de un solo producto porque no se va nada de mas`() {
        val uno = renglones(vale("b", "Concha"))
        assertNull(valeAQuitar(uno + normal, uno[0].id))
    }

    @Test
    fun `anular articulos avisa si se marco parte del vale y no si se marco completo o nada de vale`() {
        assertEquals(7, valeQueSeVaCompleto(carrito, setOf(siete[0].id, normal.id))?.productos)
        assertNull(valeQueSeVaCompleto(carrito, siete.map { it.id }.toSet()))
        assertNull(valeQueSeVaCompleto(carrito, setOf(normal.id)))
    }

    // Aquí no hay `createComposeRule`: el cableado de la pantalla se revisa en la fuente, como en RebajaFijaTest.
    private val checkout = java.io.File("src/main/java/com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt").readText()
    private val atajos = java.io.File("src/main/java/com/avoqado/pos/pos/presentation/checkout/ShortcutsGridView.kt").readText()

    private fun dialogo(fuente: String): String {
        val inicio = fuente.indexOf("valeAQuitar?.let { vale ->")
        assertTrue("falta el diálogo del vale", inicio >= 0)
        return fuente.substring(inicio, fuente.indexOf("content = {},", inicio))
    }

    @Test
    fun `quitar desde el carrito pasa por el aviso del vale antes de quitar`() {
        val quitar = checkout.substringAfter("val quitarLineaDelCarrito").substringBefore("LaunchedEffect")
        assertTrue(quitar.contains("valeAQuitar(cartState.items, itemId)"))
        assertTrue(quitar.contains("vale != null -> valeAQuitar = vale"))
        // Deslizar, el carrito del teléfono y el panel de detalle llegan todos por aquí.
        assertEquals(3, Regex("""onRemoveItem = quitarLineaDelCarrito|quitarLineaDelCarrito\(item\.id\)""").findAll(checkout).count())
    }

    @Test
    fun `cancelar no quita nada y confirmar quita el vale en los dos dialogos`() {
        for (d in listOf(dialogo(checkout), dialogo(atajos))) {
            assertTrue(d.contains("title = \"Quitar el vale completo\""))
            assertTrue(d.contains("text = \"Quitar vale\""))
            // Cancelar (la X, afuera o atrás) sólo cierra.
            assertTrue(d.contains("onDismiss = { valeAQuitar = null }"))
        }
        assertTrue(dialogo(checkout).contains("cartViewModel.quitarPorElCajero(vale.itemId)"))
        assertTrue(dialogo(atajos).contains("anular()"))
        assertTrue(atajos.contains("valeQueSeVaCompleto(cartState.items, selectedItemIds)"))
        assertTrue(atajos.contains("if (valeAQuitar == null) anular()"))
    }

    @Test
    fun `al deslizar un renglon de vale no se da por quitado para que la X no lo deje barrido`() {
        assertEquals(false, seDaPorQuitadaAlDeslizar(siete[0]))
        assertEquals(true, seDaPorQuitadaAlDeslizar(normal))
    }
}
