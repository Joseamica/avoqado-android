package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.pos.data.model.*
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FotoDeLaVentaTest {
    private val linea = CartItem(
        id = "linea-peso", type = CartItemType.ProductItem("producto"), name = "Tela", subtitle = "Lino",
        unitPrice = 40000, quantity = 1, imageUrl = "imagen", colorHex = "#123456", categoryId = "telas",
        areaTicketId = "vale", areaTicketLineId = "renglon", locked = true,
        promotionInstanceId = "instancia", promotionName = "Paquete", promotionId = "promo",
        promotionGroupId = "grupo", promotionOptionId = "opcion",
        serviceCourse = ServiceCourseSnapshot("mains", "Principales", "STANDARD", 3, 2),
        selectedModifiers = listOf(SelectedModifier("g", "Acabado", "m", "Corte", 100)),
        itemNote = "Nota ñ", priceAdjustment = 36000, isCortesia = true, cortesiaReason = "Muestra",
        itemDiscountId = "d", itemDiscountType = "FIXED_AMOUNT", itemDiscountValue = 5.5,
        itemDiscountName = "Rebaja", weightKg = 0.5, catalogAt = "2026-10-25T10:00:00Z",
    )
    private fun carrito() = CartState(
        items = listOf(linea, CartItem(id = "libre", type = CartItemType.CustomAmount, name = "Arreglo", unitPrice = 1000),
            CartItem(id = "paquete", type = CartItemType.CreditPack("pack"), name = "Clases", unitPrice = 2000)),
        pendingStampReward = PremioPorAplicar("premio", "Sello", "FIXED_AMOUNT", 2.0),
        orderDiscount = Discount("descuento", "10%", 10.0, source = "MANUAL"),
        orderNote = "Entrega mañana", orderTaxPercent = 16, orderType = "PICKUP",
        selectedStaffId = "staff", selectedStaffName = "Ana", reservationId = "reserva",
        carritoGuardadoId = "guardado", vinoDeCarritoGuardado = true, creacionIncierta = "external",
        identidadDeVenta = "venta-anterior", descuentoDeCuentaCongelado = DescuentoCongelado(150, mapOf("linea-peso" to "huella")),
    )

    // Omitir peso/premio/precio/descuento o restaurar con una identidad vieja debe romper esta prueba.
    @Test fun idaYVueltaConTodosLosCampos() {
        val original = carrito()
        val foto = original.aFoto(CartViewModel.SelectedCustomer("cliente", "Luz"))
        val recuperada = Json.decodeFromString<FotoDeLaVenta>(Json.encodeToString(foto))
        assertEquals("cliente", recuperada.clienteId)
        assertEquals("Luz", recuperada.clienteNombre)
        assertEquals(original.copy(identidadDeVenta = "comparacion"), recuperada.aCartState().copy(identidadDeVenta = "comparacion"))
        assertNotEquals("venta-anterior", recuperada.aCartState().identidadDeVenta)
        assertNotEquals(recuperada.aCartState().identidadDeVenta, recuperada.aCartState().identidadDeVenta)
    }

    @Test fun medioKiloVuelveComoMedioKiloYNoComoUnaPieza() {
        val original = CartState(items = listOf(CartItem(id = "peso", type = CartItemType.ProductItem("p"), name = "Granel", unitPrice = 40000, weightKg = 0.5)))
        val foto = Json.decodeFromString<FotoDeLaVenta>(Json.encodeToString(original.aFoto(null)))
        val vuelta = foto.aCartState()
        assertEquals(1, vuelta.items.size)
        assertEquals(0.5, vuelta.items.single().weightKg!!, 0.0)
        assertEquals(20000, vuelta.totalCents)
        assertNull(foto.clienteId)
    }

    @Test fun fotoNoComparteLineasMutablesConElCarrito() {
        val original = carrito()
        val foto = original.aFoto(null)
        original.items.first().quantity = 8
        original.items.first().itemNote = "cambio posterior"
        assertEquals(1, foto.items.firstOrNull()?.quantity)
        assertEquals("Nota ñ", foto.items.firstOrNull()?.itemNote)
    }

    @Test fun restauracionesNoCompartenLineasMutablesEntreSi() {
        val foto = carrito().aFoto(null)
        val primera = foto.aCartState()
        primera.items.firstOrNull()?.quantity = 9
        val segunda = foto.aCartState()
        assertEquals(1, segunda.items.firstOrNull()?.quantity)
        assertEquals(1, foto.items.firstOrNull()?.quantity)
    }

    @Test fun todoCampoDelCarritoViajaSalvoLaIdentidadQueSeEstrena() {
        val delCarrito = CartState::class.java.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet() - "identidadDeVenta"
        val deFoto = FotoDeLaVenta::class.java.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet() - setOf("clienteId", "clienteNombre")
        assertEquals(delCarrito, deFoto)
    }
}
