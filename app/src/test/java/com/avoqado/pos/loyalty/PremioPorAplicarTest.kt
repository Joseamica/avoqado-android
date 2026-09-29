package com.avoqado.pos.loyalty

import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.loyalty.data.ScannedReward
import com.avoqado.pos.loyalty.data.WalletScanResponse
import com.avoqado.pos.loyalty.data.comoPremioPorAplicar
import com.avoqado.pos.loyalty.data.premioEstimadoCents
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.presentation.cart.CartState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 DINERO — cuánto baja la cuenta el premio de la cartilla, ANTES de cobrar.
 *
 * Estándar de Square y Toast: el premio aparece como descuento en la cuenta antes de
 * elegir cómo paga el cliente, así que efectivo, tarjeta y pago dividido ven el MISMO
 * total. El monto es un ESTIMADO con la regla del servidor (`calcularDescuento` de
 * `redeemStampReward.service.ts`): el que manda es el que el servidor confirma al crear la
 * venta.
 */
class PremioPorAplicarTest {

    private fun linea(precio: Int, cantidad: Int = 1, id: String = "l-$precio") = CartItem(
        id = id,
        type = CartItemType.ProductItem("p-$precio"),
        name = "Producto $precio",
        unitPrice = precio,
        quantity = cantidad,
    )

    private fun premio(tipo: String?, valor: Double?) = PremioPorAplicar(id = "rw1", etiqueta = "Premio", tipo = tipo, valor = valor)

    @Test
    fun `un premio de monto fijo baja ese monto`() {
        assertEquals(3000, premioEstimadoCents(premio("FIXED_AMOUNT", 30.0), listOf(linea(10000)), baseCents = 10000))
    }

    @Test
    fun `P1 un premio de monto fijo nunca baja mas que la cuenta`() {
        // El servidor lo topa contra la base: sin tope la cuenta quedaría en negativo.
        assertEquals(4000, premioEstimadoCents(premio("FIXED_AMOUNT", 50.0), listOf(linea(4000)), baseCents = 4000))
    }

    @Test
    fun `P1 un porcentaje se aplica a la cuenta ya descontada, no al precio de lista`() {
        // $100 con $20 de descuento de cuenta: el 10% es de $80, no de $100.
        assertEquals(800, premioEstimadoCents(premio("PERCENTAGE", 10.0), listOf(linea(10000)), baseCents = 8000))
    }

    @Test
    fun `un producto gratis descuenta el articulo mas caro de la cuenta`() {
        // Decisión D10 (como Square): si pide algo más caro que su café gratis, no paga la diferencia.
        val lineas = listOf(linea(2500, id = "pan"), linea(4000, id = "cafe"), linea(1500, cantidad = 3, id = "galleta"))
        assertEquals(4000, premioEstimadoCents(premio("FREE_PRODUCT", null), lineas, baseCents = 10500))
    }

    @Test
    fun `sin tipo o sin valor no se inventa un descuento - el servidor lo confirma al cobrar`() {
        assertEquals(0, premioEstimadoCents(premio(null, null), listOf(linea(10000)), baseCents = 10000))
        assertEquals(0, premioEstimadoCents(premio("FIXED_AMOUNT", null), listOf(linea(10000)), baseCents = 10000))
    }

    @Test
    fun `con el carrito vacio el premio vale cero - se escanea antes de agregar productos`() {
        assertEquals(0, premioEstimadoCents(premio("FIXED_AMOUNT", 30.0), emptyList(), baseCents = 0))
    }

    @Test
    fun `P1 el carrito resta el premio de su total y el boton de cobrar ya lo trae`() {
        val carrito = CartState(items = listOf(linea(10000)), pendingStampReward = premio("FIXED_AMOUNT", 30.0))

        assertEquals(3000, carrito.stampRewardCents)
        assertEquals(7000, carrito.totalCents)
        assertEquals("rw1", carrito.pendingStampRewardId)
    }

    @Test
    fun `P1 el premio se calcula sobre la cuenta despues del descuento de cuenta`() {
        val carrito = CartState(
            items = listOf(linea(10000)),
            orderDiscount = Discount(id = "d1", name = "20 pesos", value = 20.0, type = "FIXED"),
            pendingStampReward = premio("PERCENTAGE", 10.0),
        )

        assertEquals(2000, carrito.discountCents)
        assertEquals(800, carrito.stampRewardCents)
        assertEquals(7200, carrito.totalCents)
    }

    @Test
    fun `sin premio el carrito queda exactamente como antes`() {
        val carrito = CartState(items = listOf(linea(10000)))

        assertEquals(0, carrito.stampRewardCents)
        assertEquals(10000, carrito.totalCents)
        assertNull(carrito.pendingStampRewardId)
    }

    @Test
    fun `el escaneo trae tipo y valor del premio y se lee en pesos`() {
        val json = """{"found":true,"customer":{"id":"c1","firstName":"Ana","lastName":null},
            "stampsEarned":7,"stampsRequired":7,
            "rewardsToClaim":[{"id":"rw1","rewardLabel":"${'$'}30 de premio","rewardType":"FIXED_AMOUNT","rewardValue":30}]}"""
        val r = Json { ignoreUnknownKeys = true }.decodeFromString(WalletScanResponse.serializer(), json)

        assertEquals(
            PremioPorAplicar(id = "rw1", etiqueta = "\$30 de premio", tipo = "FIXED_AMOUNT", valor = 30.0),
            r.rewardsToClaim.single().comoPremioPorAplicar(),
        )
    }

    @Test
    fun `P1 efectivo corto por el premio - dice que paso, el total real y cuanto falta`() {
        val rechazado = com.avoqado.pos.payment.data.model.avisoDeEfectivoCortoPorPremio(
            com.avoqado.pos.payment.data.model.StampRewardOnOrder(applied = false, reason = "Este premio ya venció."),
            totalRealCents = 10000,
            recibidoCents = 7000,
        )
        val porMenos = com.avoqado.pos.payment.data.model.avisoDeEfectivoCortoPorPremio(
            com.avoqado.pos.payment.data.model.StampRewardOnOrder(applied = true, discountAmount = 25.0),
            totalRealCents = 7500,
            recibidoCents = 7000,
        )

        assertEquals("No se aplicó el premio: Este premio ya venció. El total es \$100.00: faltan \$30.00.", rechazado)
        assertEquals("El premio se aplicó por \$25.00. El total es \$75.00: faltan \$5.00.", porMenos)
    }

    @Test
    fun `P1 producto gratis con promociones en la cuenta no se estima - lo calcula el servidor`() {
        // Una línea de promoción guarda su precio YA repartido, y el servidor usa el de lista:
        // 2 cafés de $50 en 2×1 estimarían $25 contra los $50 del servidor (hallazgo P1 de Codex).
        val lineaDePromo = linea(2500, id = "cafe-promo").copy(promotionInstanceId = "inst-1", promotionId = "p-2x1")
        assertEquals(0, premioEstimadoCents(premio("FREE_PRODUCT", null), listOf(lineaDePromo, linea(2000)), baseCents = 4500))
        // Los otros tipos no dependen del precio unitario: se siguen estimando.
        assertEquals(3000, premioEstimadoCents(premio("FIXED_AMOUNT", 30.0), listOf(lineaDePromo), baseCents = 4500))
    }

    @Test
    fun `un servidor viejo que no manda tipo ni valor no rompe el escaneo`() {
        val premioViejo = ScannedReward(id = "rw1", rewardLabel = "Un café gratis")

        assertEquals(PremioPorAplicar("rw1", "Un café gratis", null, null), premioViejo.comoPremioPorAplicar())
    }
}
