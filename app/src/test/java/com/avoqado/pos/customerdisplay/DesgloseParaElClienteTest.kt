package com.avoqado.pos.customerdisplay

import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.presentation.cart.CartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo que el cliente lee en SU pantalla tiene que explicar cada peso: si un Latte de
 * $55 aparece en $65 sin decir «Leche de almendra», o un combo sale como dos productos
 * sueltos, el cliente desconfía del cobro aunque el total sea correcto.
 */
class DesgloseParaElClienteTest {

    private fun producto(
        nombre: String,
        precio: Int,
        cantidad: Int = 1,
        id: String = nombre,
    ) = CartItem(id = id, type = CartItemType.ProductItem("p-$id"), name = nombre, unitPrice = precio, quantity = cantidad)

    private fun modificador(nombre: String, precio: Int) =
        SelectedModifier(groupId = "g", groupName = "Grupo", modifierId = nombre, modifierName = nombre, priceInCents = precio)

    @Test
    fun `P1 los modificadores se nombran debajo del producto`() {
        val latte = producto("Latte", 5_500).copy(
            selectedModifiers = listOf(modificador("Leche de almendra", 1_000), modificador("Extra shot", 0)),
        )

        val renglon = renglonesParaElCliente(listOf(latte)).single()

        assertEquals("Latte", renglon.nombre)
        assertEquals(6_500, renglon.precioCents)
        assertEquals("Leche de almendra, Extra shot", renglon.detalle)
    }

    @Test
    fun `P1 un combo sale con su nombre y sus productos debajo sin precio, como en el ticket`() {
        val hamburguesa = producto("Hamburguesa", 8_500).copy(
            promotionInstanceId = "i1", promotionName = "Combo del día",
            selectedModifiers = listOf(modificador("Sin cebolla", 0)),
        )
        val refresco = producto("Refresco", 1_500).copy(promotionInstanceId = "i1", promotionName = "Combo del día")
        val agua = producto("Agua", 2_000)

        val renglones = renglonesParaElCliente(listOf(hamburguesa, agua, refresco))

        assertEquals(listOf("Combo del día", "Hamburguesa", "Refresco", "Agua"), renglones.map { it.nombre })
        assertEquals(listOf(10_000, null, null, 2_000), renglones.map { it.precioCents })
        assertEquals(listOf(false, true, true, false), renglones.map { it.esComponente })
        assertEquals("Sin cebolla", renglones[1].detalle)
        // El total que suman los renglones con precio es el mismo dinero del carrito.
        assertEquals(12_000, renglones.sumOf { it.precioCents ?: 0 })
    }

    @Test
    fun `P1 dos combos iguales son dos renglones, no uno`() {
        val a = producto("Hamburguesa", 8_500, id = "a").copy(promotionInstanceId = "i1", promotionName = "Combo")
        val b = producto("Hamburguesa", 8_500, id = "b").copy(promotionInstanceId = "i2", promotionName = "Combo")

        val encabezados = renglonesParaElCliente(listOf(a, b)).filter { !it.esComponente }

        assertEquals(2, encabezados.size)
    }

    @Test
    fun `P1 el descuento de un producto dice cual es y cuanto`() {
        val pan = producto("Pan dulce", 3_000).copy(
            itemDiscountId = "d1", itemDiscountType = "PERCENTAGE", itemDiscountValue = 20.0, itemDiscountName = "Happy hour",
        )

        val renglon = renglonesParaElCliente(listOf(pan)).single()

        // El precio va BRUTO: el descuento se resta abajo, en su propio renglón.
        assertEquals(3_000, renglon.precioCents)
        assertEquals("Happy hour −${money(600)}", renglon.detalle)
    }

    @Test
    fun `P2 cortesia, peso y nota se explican`() {
        val cortesia = producto("Galleta", 2_500).copy(isCortesia = true)
        val arrachera = CartItem(
            id = "arr", type = CartItemType.ProductItem("arr"), name = "Arrachera",
            unitPrice = 42_000, weightKg = 0.75, itemNote = "término medio",
        )

        val (galleta, carne) = renglonesParaElCliente(listOf(cortesia, arrachera))

        assertEquals("Cortesía", galleta.detalle)
        assertEquals(0, galleta.precioCents)
        assertEquals("${arrachera.weightSummary} · Nota: término medio", carne.detalle)
    }

    @Test
    fun `P2 un producto sin nada extra no lleva detalle`() {
        assertNull(renglonesParaElCliente(listOf(producto("Agua", 2_000))).single().detalle)
    }

    @Test
    fun `P1 los descuentos se separan por origen y suman lo mismo que el carrito`() {
        val pan = producto("Pan", 10_000).copy(
            itemDiscountId = "d1", itemDiscountType = "FIXED_AMOUNT", itemDiscountValue = 10.0, itemDiscountName = "Promo pan",
        )
        val cart = CartState(
            items = listOf(pan, producto("Café", 5_000)),
            orderDiscount = Discount(id = "o", name = "Cliente frecuente", value = 10.0, type = "PERCENTAGE"),
            pendingStampReward = PremioPorAplicar(id = "r", etiqueta = "Café gratis", tipo = "FIXED_AMOUNT", valor = 20.0),
        )

        val descuentos = descuentosParaElCliente(cart, premioCents = 2_000)

        assertEquals(
            listOf(
                DescuentoCliente("Descuento en productos", 1_000),
                DescuentoCliente("Descuento (Cliente frecuente)", 1_400),
                DescuentoCliente("Premio: Café gratis", 2_000),
            ),
            descuentos,
        )
        assertEquals(cart.discountCents + 2_000, descuentos.sumOf { it.cents })
    }

    @Test
    fun `P1 cuenta dividida - el cliente ve SU parte, no el total de la cuenta`() {
        val cart = CartState(items = listOf(producto("Comida", 80_000)))

        val pantalla = desgloseDelCobro(
            cart = cart, tipCents = 4_000, premioCents = 0,
            esPagoCompleto = false, montoACobrarCents = 40_000,
        )

        assertEquals(44_000, pantalla.totalCents)
        assertEquals("Tu parte", pantalla.etiqueta)
        // Sin renglones: la lista entera con «Total $440» al pie diría que se cobra todo.
        assertEquals(emptyList<RenglonCliente>(), pantalla.renglones)
    }

    @Test
    fun `P1 cuenta entera - desglose completo con el premio que confirmo el servidor`() {
        val cart = CartState(
            items = listOf(producto("Latte", 6_000), producto("Pan", 4_000)),
            pendingStampReward = PremioPorAplicar(id = "r", etiqueta = "Pan gratis", tipo = "FIXED_AMOUNT", valor = 40.0),
        )

        val pantalla = desgloseDelCobro(
            cart = cart, tipCents = 1_000, premioCents = 3_000,
            esPagoCompleto = true, montoACobrarCents = cart.totalCents,
        )

        // El carrito restó su ESTIMADO; manda lo confirmado ($30): 100 − 30 + 10 de propina.
        assertEquals(8_000, pantalla.totalCents)
        assertEquals("Total", pantalla.etiqueta)
        assertEquals(listOf(DescuentoCliente("Premio: Pan gratis", 3_000)), pantalla.descuentos)
        assertEquals(10_000, pantalla.subtotalCents)
        assertEquals(1_000, pantalla.tipCents)
        assertEquals(2, pantalla.renglones.size)
        assertEquals(
            pantalla.totalCents,
            pantalla.subtotalCents - pantalla.descuentos.sumOf { it.cents } + pantalla.taxCents + pantalla.tipCents,
        )
    }

    @Test
    fun `P2 monto sin productos - total en grande`() {
        val pantalla = desgloseDelCobro(
            cart = null, tipCents = 0, premioCents = 0,
            esPagoCompleto = true, montoACobrarCents = 12_300,
        )

        assertEquals(12_300, pantalla.totalCents)
        assertEquals("Total", pantalla.etiqueta)
    }

    @Test
    fun `P2 sin descuentos no hay renglones de descuento`() {
        assertEquals(emptyList<DescuentoCliente>(), descuentosParaElCliente(CartState(items = listOf(producto("Agua", 2_000))), 0))
    }
}
