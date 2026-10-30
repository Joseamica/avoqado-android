package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.SavedCart
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D13: la foto del descuento en el renglón lleva el TOPE máximo del descuento de catálogo (`itemDiscountMax`, en pesos),
 * se llena donde se llena el resto de la foto, sobrevive al carrito guardado y un carrito guardado viejo (sin el campo)
 * se lee como «sin tope». Gemela de iOS: mismo nombre de campo.
 */
class TopeDeDescuentoEnElCarritoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun carrito(repo: SavedCartsRepository): CartViewModel =
        CartPromotionTest().apply { setup() }.createViewModel(repo)

    private val playera = Product(id = "playera", name = "Playera", priceValue = 100.0)
    private val mitadMaximoDiez = Discount(
        id = "d50", name = "50 % máx $10", value = 50.0, type = "PERCENTAGE", scope = "ITEM", maxDiscountAmount = 10.0,
    )

    @Test
    fun `al aplicar un descuento con tope la foto del renglon lleva el tope y se cobra 90`() {
        val prefs = PrefsDeDosCapas()
        val vm = carrito(SavedCartsRepository(prefs.context))
        vm.addProductWithModifiers(playera, modifiers = emptyList(), discount = mitadMaximoDiez)

        val renglon = vm.cartState.value.items.single()
        assertEquals(10.0, renglon.itemDiscountMax!!, 0.0)
        assertEquals(9000, renglon.totalPrice)
    }

    @Test
    fun `sin descuento o sin tope la foto no lleva tope`() {
        val prefs = PrefsDeDosCapas()
        val vm = carrito(SavedCartsRepository(prefs.context))
        vm.addProductWithModifiers(playera, modifiers = emptyList())
        vm.addProductWithModifiers(
            playera.copy(id = "gorra", name = "Gorra"), modifiers = emptyList(),
            discount = mitadMaximoDiez.copy(maxDiscountAmount = null),
        )

        assertTrue(vm.cartState.value.items.all { it.itemDiscountMax == null })
        assertEquals(5000, vm.cartState.value.items[1].itemDiscountCents)
    }

    @Test
    fun `un carrito guardado con tope lo conserva al restaurarse, tambien tras reiniciar`() {
        val prefs = PrefsDeDosCapas()
        val vm = carrito(SavedCartsRepository(prefs.context))
        vm.addProductWithModifiers(playera, modifiers = emptyList(), discount = mitadMaximoDiez)
        assertTrue(vm.saveCurrentCart("Mesa 1"))

        val guardado = SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single()
        assertEquals(10.0, guardado.items.single().itemDiscountMax!!, 0.0)

        val otro = carrito(SavedCartsRepository(PrefsDeDosCapas().context))
        otro.restoreSavedCart(guardado)
        val restaurado = otro.cartState.value.items.single()
        assertEquals(10.0, restaurado.itemDiscountMax!!, 0.0)
        assertEquals(9000, restaurado.totalPrice)
    }

    @Test
    fun `un carrito guardado por una version vieja, sin el campo, se restaura con null`() {
        val viejo = Json { ignoreUnknownKeys = true }.decodeFromString<SavedCart>(
            """{"id":"v1","name":"Viejo","items":[{"productId":"playera","name":"Playera","unitPrice":10000,"quantity":1,
            "itemDiscountId":"d50","itemDiscountType":"PERCENTAGE","itemDiscountValue":50.0,"itemDiscountName":"50 %"}]}""",
        )
        assertNull(viejo.items.single().itemDiscountMax)

        val vm = carrito(SavedCartsRepository(PrefsDeDosCapas().context))
        vm.restoreSavedCart(viejo)
        val restaurado = vm.cartState.value.items.single()
        assertNull(restaurado.itemDiscountMax)
        // Como hoy: sin tope conocido, el descuento es el calculado.
        assertEquals(5000, restaurado.itemDiscountCents)
    }

    @Test
    fun `el descuento de cuenta con tope viaja en el carrito guardado y se restaura con su tope`() {
        val prefs = PrefsDeDosCapas()
        val vm = carrito(SavedCartsRepository(prefs.context))
        vm.addProductWithModifiers(playera, modifiers = emptyList())
        vm.applyOrderDiscount(Discount(id = "dc", name = "20 % máx $10", value = 20.0, type = "PERCENTAGE", scope = "ORDER", maxDiscountAmount = 10.0))
        assertEquals(1000, vm.cartState.value.orderDiscountCents)
        assertTrue(vm.saveCurrentCart("Mesa 2"))

        val guardado = SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single()
        assertEquals(10.0, guardado.orderDiscount!!.maxDiscountAmount!!, 0.0)

        val otro = carrito(SavedCartsRepository(PrefsDeDosCapas().context))
        otro.restoreSavedCart(guardado)
        assertEquals(1000, otro.cartState.value.orderDiscountCents)
    }
}
