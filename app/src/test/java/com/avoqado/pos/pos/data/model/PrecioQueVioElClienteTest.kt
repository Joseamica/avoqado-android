package com.avoqado.pos.pos.data.model

import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.pos.presentation.cart.CartState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 DINERO — «se cobra el precio que vio el cliente» (17-oct-2026), la parte pura de la caja: corregir por identidad de línea,
 * decirlo con las palabras exactas de iOS y no recalcular el descuento de cuenta que ya viajó. Literales idénticos a
 * `PrecioQueVioElClienteTests.swift` (Task 12).
 */
class PrecioQueVioElClienteTest {

    private fun linea(id: String, precio: Int) =
        CartItem(id = id, type = CartItemType.ProductItem("p1"), name = "Café", unitPrice = precio, catalogAt = "2026-10-17T15:00:00.000Z")

    private fun cambio(id: String?, de: Int, a: Int) = CambioDePrecio(id, "p1", "Café", "2026-10-17T15:00:00.000Z", de, a)

    private val diezPorCiento = Discount(id = "d10", name = "Diez", value = 10.0, type = "PERCENTAGE", scope = "ORDER")

    // MARK: - Corregir y decir

    @Test fun `corrige sólo la línea que nombró el servidor`() {
        val items = listOf(linea("e1", 4500), linea("e2", 4500))
        val r = aplicarCambiosDePrecio(items, listOf(cambio("e1", 4500, 5000)))
        assertEquals(listOf(5000, 4500), r.map { it.unitPrice })
        assertEquals(items[1], r[1])
    }

    @Test fun `una línea que no es de producto no se corrige aunque se llame igual`() {
        val importe = CartItem(id = "e1", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = 4500)
        assertEquals(listOf(importe), aplicarCambiosDePrecio(listOf(importe), listOf(cambio("e1", 4500, 5000))))
    }

    @Test fun `bajó`() = assertEquals("Café bajó de precio: ahora cuesta \$45.00.", avisoDeCambioDePrecio(listOf(cambio("e1", 5000, 4500))))

    @Test fun `bajó con el efectivo ya recibido`() = assertEquals(
        "Café bajó de precio: ahora cuesta \$45.00.\nDevuelve \$5.00 más de cambio.",
        avisoDeCambioDePrecio(listOf(cambio("e1", 5000, 4500)), cambioDeMasCents = 500),
    )

    @Test fun `subió y no se reconoce`() = assertEquals(
        "Café cambió de precio: ahora cuesta \$1,250.00.\nTotal nuevo: \$2,500.00.",
        avisoDeCambioDePrecio(listOf(cambio("e1", 4500, 125000)), totalNuevoCents = 250000),
    )

    @Test fun `dos líneas del mismo cambio se dicen una vez`() =
        assertEquals(1, avisoDeCambioDePrecio(listOf(cambio("e1", 5000, 4500), cambio("e2", 5000, 4500))).lines().size)

    @Test fun `la huella es la misma que en iOS`() =
        assertEquals("1|4500|null|null|null|false", DescuentoCongelado.huella(linea("e1", 4500)))

    // MARK: - El descuento de cuenta que viajó

    @Test fun `el descuento de cuenta del 10 por ciento queda fijo al corregir`() {
        val antes = CartState(items = listOf(linea("e1", 10000)), orderDiscount = diezPorCiento)
        assertEquals(1000, antes.orderDiscountCents)

        val corregido = antes.conPreciosDelServidor(listOf(cambio("e1", 10000, 8000)))

        assertEquals(8000, corregido.items.single().unitPrice)
        assertEquals("el monto que viajó, no el 10 % de 80", 1000, corregido.orderDiscountCents)
        assertEquals(7000, corregido.totalCents)
    }

    @Test fun `sin cambios no se congela nada`() {
        val antes = CartState(items = listOf(linea("e1", 10000)), orderDiscount = diezPorCiento)
        val igual = antes.conPreciosDelServidor(emptyList())
        assertSame(antes, igual)
        val dos = igual.copy(items = listOf(igual.items.single().copy(quantity = 2)))
        assertEquals(2000, dos.orderDiscountCents)
    }

    @Test fun `editar la venta suelta el congelado, pagar una parte no`() {
        val corregido = CartState(items = listOf(linea("e1", 5000), linea("e2", 5000)), orderDiscount = diezPorCiento)
            .conPreciosDelServidor(listOf(cambio("e1", 5000, 4500), cambio("e2", 5000, 4500)))
        assertEquals(1000, corregido.orderDiscountCents)

        val sinE1 = corregido.copy(items = corregido.items.filter { it.id == "e2" })
        assertEquals("pago parcial: lo que queda sigue siendo esa venta", 1000, sinE1.orderDiscountCents)
        assertEquals(3500, sinE1.totalCents)

        val e2Doble = sinE1.copy(items = listOf(sinE1.items.single().copy(quantity = 2)))
        assertEquals("editar vuelve al 10 % del neto", 900, e2Doble.orderDiscountCents)

        val otraLineaCara = corregido.copy(items = corregido.items + linea("e3", 11000))
        assertEquals("una línea nueva vuelve al 10 %", 2000, otraLineaCara.orderDiscountCents)
    }

    @Test fun `un 409 no congela - el 10 por ciento de 100 que sube a 125 cobra 112 con 50`() {
        val corregido = CartState(items = listOf(linea("e1", 10000)), orderDiscount = diezPorCiento)
            .conPreciosDelServidor(listOf(cambio("e1", 10000, 12500)), congelar = false)
        assertNull(corregido.descuentoDeCuentaCongelado)
        assertEquals(1250, corregido.orderDiscountCents)
        assertEquals(11250, corregido.totalCents)
    }

    @Test fun `una cortesía con un 409 sigue en cero`() {
        val cortesia = Discount(id = "cortesia_1", name = "Cortesía", value = 100.0, type = "PERCENTAGE", scope = "ORDER")
        val corregido = CartState(items = listOf(linea("e1", 10000)), orderDiscount = cortesia)
            .conPreciosDelServidor(listOf(cambio("e1", 10000, 12500)), congelar = false)
        assertEquals(12500, corregido.orderDiscountCents)
        assertEquals(0, corregido.totalCents)
    }

    @Test fun `un 201 que baja congela y una segunda corrección vuelve a sellar el mismo monto`() {
        val primera = CartState(items = listOf(linea("e1", 10000)), orderDiscount = diezPorCiento)
            .conPreciosDelServidor(listOf(cambio("e1", 10000, 8000)))
        assertEquals(1000, primera.descuentoDeCuentaCongelado?.cents)

        val segunda = primera.conPreciosDelServidor(listOf(cambio("e1", 8000, 7000)))

        assertEquals(1000, segunda.descuentoDeCuentaCongelado?.cents)
        assertTrue("sellado con las líneas ya corregidas", segunda.descuentoDeCuentaCongelado!!.vigentePara(segunda.items))
        assertEquals(1000, segunda.orderDiscountCents)
        assertEquals(6000, segunda.totalCents)
    }

    @Test fun `quitar el descuento de cuenta no deja el congelado cobrando`() {
        val corregido = CartState(items = listOf(linea("e1", 10000)), orderDiscount = diezPorCiento)
            .conPreciosDelServidor(listOf(cambio("e1", 10000, 8000)))
        assertEquals(0, corregido.copy(orderDiscount = null).orderDiscountCents)
    }

    // MARK: - El renglón del descuento dice lo que se cobra

    @Test fun `el renglón del descuento dice lo que se cobra`() {
        val corregido = CartState(items = listOf(linea("e1", 10000)), orderDiscount = diezPorCiento)
            .conPreciosDelServidor(listOf(cambio("e1", 10000, 8000)))
        assertEquals("\$10.00", corregido.orderDiscountDisplay)
        assertEquals("\$16.00", corregido.copy(items = listOf(corregido.items.single().copy(quantity = 2))).orderDiscountDisplay)
    }

    @Test fun `con descuento de artículo y de cuenta los renglones suman lo descontado`() {
        val conDescuentoDeLinea = linea("e1", 10000).copy(itemDiscountId = "dl", itemDiscountType = "PERCENTAGE", itemDiscountValue = 10.0)
        val corregido = CartState(items = listOf(conDescuentoDeLinea), orderDiscount = diezPorCiento)
            .conPreciosDelServidor(listOf(cambio("e1", 10000, 8000)))

        assertEquals("\$8.00", corregido.itemDiscountDisplay)
        assertEquals("el de cuenta que viajó (10 % de 90)", "\$9.00", corregido.orderDiscountDisplay)
        assertEquals(corregido.subtotalCents - corregido.totalCents, corregido.itemDiscountCents + corregido.orderDiscountCents)
        assertEquals(6300, corregido.totalCents)
    }

    // MARK: - Lo que dice el servidor

    @Test fun `el 409 estructurado trae los cambios, un 409 cualquiera no`() {
        val cambios = """{"externalId":"e1","productId":"p1","productName":"Café","catalogAt":"2026-10-17T15:00:00.000Z",
            "displayedUnitPriceCents":4500,"unitPriceCents":5000}"""
        val estructurado = """{"message":"El precio de algunos productos cambió.","code":"ORDER_PRICE_CHANGED",
            "details":{"priceChanges":[$cambios,{"externalId":"e2","productName":"Sin producto"}]}}"""

        assertEquals(listOf(cambio("e1", 4500, 5000)), OrderRepository.leerCambiosDePrecio(estructurado))
        assertEquals(emptyList<CambioDePrecio>(), OrderRepository.leerCambiosDePrecio("""{"code":"ORDER_CANCELLED","details":{"priceChanges":[$cambios]}}"""))
        assertEquals(emptyList<CambioDePrecio>(), OrderRepository.leerCambiosDePrecio("""{"message":"Conflicto"}"""))
        assertEquals(emptyList<CambioDePrecio>(), OrderRepository.leerCambiosDePrecio("<html>proxy</html>"))
    }

    @Test fun `el 201 trae los cambios en la orden`() {
        val json = """{"id":"order-1","total":90,"priceChanges":[{"externalId":"e1","productId":"p1","productName":"Café",
            "catalogAt":null,"displayedUnitPriceCents":5000,"unitPriceCents":4500}]}"""
        val orden = Json { ignoreUnknownKeys = true }.decodeFromString(OrderData.serializer(), json)
        assertEquals(listOf(CambioDePrecio("e1", "p1", "Café", null, 5000, 4500)), orden.priceChanges)
        assertEquals(emptyList<CambioDePrecio>(), Json { ignoreUnknownKeys = true }.decodeFromString(OrderData.serializer(), """{"id":"o"}""").priceChanges)
    }

    // MARK: - Varias correcciones de la misma venta

    @Test fun `si el 409 y el 201 corrigen la misma línea se dice sólo el último`() {
        val acumulados = acumularCambiosDePrecio(acumularCambiosDePrecio(emptyList(), listOf(cambio("e1", 4500, 5000))), listOf(cambio("e1", 5000, 4800)))
        assertEquals(listOf(cambio("e1", 4500, 4800)), acumulados)
        assertEquals(
            "Café cambió de precio: ahora cuesta \$48.00.\nTotal nuevo: \$48.00.",
            avisoDePrecioDeLaCreacion(acumulados, totalNuevoCents = 4800, totalAntesCents = 4500),
        )
    }

    @Test fun `un cambio que se deshizo no se dice`() {
        val acumulados = acumularCambiosDePrecio(listOf(cambio("e1", 4500, 5000)), listOf(cambio("e1", 5000, 4500)))
        assertNull(avisoDePrecioDeLaCreacion(acumulados, totalNuevoCents = 4500, totalAntesCents = 4500))
    }

    @Test fun `un cambio que se deshizo con 10 por ciento de cuenta sólo dice el total nuevo`() {
        val inicio = CartState(items = listOf(linea("e1", 4500)), orderDiscount = diezPorCiento)
        assertEquals(4050, inicio.totalCents)
        val tras409 = inicio.conPreciosDelServidor(listOf(cambio("e1", 4500, 5000)), congelar = false)
        val tras201 = tras409.conPreciosDelServidor(listOf(cambio("e1", 5000, 4500)))
        assertEquals("el 201 congela el 10 % de 50 que viajó en el reintento", 4000, tras201.totalCents)

        val acumulados = acumularCambiosDePrecio(listOf(cambio("e1", 4500, 5000)), listOf(cambio("e1", 5000, 4500)))
        assertEquals("Total nuevo: \$40.00.", avisoDePrecioDeLaCreacion(acumulados, totalNuevoCents = tras201.totalCents, totalAntesCents = inicio.totalCents))
    }

    @Test fun `sin un 409 no hay total nuevo`() = assertEquals(
        "Café bajó de precio: ahora cuesta \$45.00.",
        avisoDePrecioDeLaCreacion(listOf(cambio("e1", 5000, 4500)), totalNuevoCents = 4500, totalAntesCents = null),
    )
}
