package com.avoqado.pos.articles

import com.avoqado.pos.articles.data.model.AdminCoupon
import com.avoqado.pos.articles.data.model.AdminDiscount
import com.avoqado.pos.articles.data.model.ArticleModifier
import com.avoqado.pos.articles.data.model.ArticleProduct
import com.avoqado.pos.articles.data.model.CreditBalanceItem
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.articles.data.model.CreditPackItem
import com.avoqado.pos.articles.data.model.CreditPurchaseBalance
import com.avoqado.pos.articles.data.model.DiscountType
import com.avoqado.pos.articles.data.model.ModifierGroup
import com.avoqado.pos.articles.data.model.ProductType
import com.avoqado.pos.articles.data.model.textoDelSaldo
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.articles.presentation.renglonesDelPaquete
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticlesModelsTest {

    // MARK: - ArticleProduct

    @Test
    fun `product initials takes first 2 chars uppercase`() {
        val product = ArticleProduct(id = "1", name = "burger")
        assertEquals("BU", product.initials)
    }

    @Test
    fun `product initials of two words follow the POS tile rule`() {
        // D16 (3-nov): una sola regla de iniciales en ANDROID, la de las tarjetas (`inicialesDeTile`): antes decía «AG».
        assertEquals("AM", ArticleProduct(id = "1", name = "Agua mineral").initials)
        assertEquals("CA", ArticleProduct(id = "2", name = "Café americano grande").initials)
    }

    @Test
    fun `product displayPrice shows Variable for VARIABLE priceType`() {
        val product = ArticleProduct(id = "1", name = "item", priceType = "VARIABLE", price = 10.0)
        assertEquals("Variable", product.displayPrice)
    }

    @Test
    fun `product displayPrice formats fixed price with dollar sign`() {
        val product = ArticleProduct(id = "1", name = "item", priceType = "FIXED", price = 12.5)
        assertEquals("$12.50", product.displayPrice)
    }

    @Test
    fun `product productType maps FOOD_AND_BEV correctly`() {
        val product = ArticleProduct(id = "1", name = "item", type = "FOOD_AND_BEV")
        assertEquals(ProductType.FOOD_AND_BEV, product.productType)
    }

    @Test
    fun `product productType defaults to REGULAR for unknown type`() {
        val product = ArticleProduct(id = "1", name = "item", type = "UNKNOWN_TYPE")
        assertEquals(ProductType.REGULAR, product.productType)
    }

    // MARK: - ArticleModifier

    @Test
    fun `modifier displayPrice shows plus sign for positive price`() {
        val modifier = ArticleModifier(id = "1", name = "extra cheese", price = 2.0)
        assertEquals("+$2.00", modifier.displayPrice)
    }

    @Test
    fun `modifier displayPrice is empty for zero price`() {
        val modifier = ArticleModifier(id = "1", name = "no charge", price = 0.0)
        assertEquals("", modifier.displayPrice)
    }

    // MARK: - AdminDiscount

    @Test
    fun `discount discountType maps FIXED_AMOUNT to FIXED`() {
        val discount = AdminDiscount(id = "1", name = "Flat off", type = "FIXED_AMOUNT")
        assertEquals(DiscountType.FIXED, discount.discountType)
    }

    @Test
    fun `discount discountType maps COMP correctly`() {
        val discount = AdminDiscount(id = "1", name = "Comp", type = "COMP")
        assertEquals(DiscountType.COMP, discount.discountType)
    }

    @Test
    fun `discount formattedValue shows percentage for PERCENTAGE type`() {
        val discount = AdminDiscount(id = "1", name = "10% off", type = "PERCENTAGE", value = 10.0)
        assertEquals("10%", discount.formattedValue)
    }

    @Test
    fun `discount formattedValue shows Cortesia for COMP type`() {
        val discount = AdminDiscount(id = "1", name = "Cortesia", type = "COMP", value = 0.0)
        assertEquals("Cortesía", discount.formattedValue)
    }

    @Test
    fun `discount emoji returns gift for comp keyword`() {
        val discount = AdminDiscount(id = "1", name = "cortesia del chef", type = "PERCENTAGE")
        assertEquals("🎁", discount.emoji)
    }

    @Test
    fun `discount emoji returns default label for unknown name`() {
        val discount = AdminDiscount(id = "1", name = "random discount", type = "PERCENTAGE")
        assertEquals("💰", discount.emoji)
    }

    // MARK: - AdminCoupon

    @Test
    fun `coupon usageText shows current over max uses`() {
        val coupon = AdminCoupon(id = "1", currentUses = 5, maxUses = 100)
        assertEquals("5 / 100 usos", coupon.usageText)
    }

    @Test
    fun `coupon usageText shows just current when no max`() {
        val coupon = AdminCoupon(id = "1", currentUses = 3, maxUses = null)
        assertEquals("3 usos", coupon.usageText)
    }

    // MARK: - ModifierGroup

    @Test
    fun `modifier group modifierCount returns size of modifiers list`() {
        val modifiers = listOf(
            ArticleModifier(id = "m1", name = "extra"),
            ArticleModifier(id = "m2", name = "sauce"),
            ArticleModifier(id = "m3", name = "cheese"),
        )
        val group = ModifierGroup(id = "g1", name = "Extras", modifiers = modifiers)
        assertEquals(3, group.modifierCount)
    }

    // MARK: - CreditPack

    @Test
    fun `credit pack displayPrice formats correctly`() {
        val pack = CreditPack(id = "1", name = "Basic Pack", price = 99.99)
        assertEquals("$99.99", pack.displayPrice)
    }

    @Test
    fun `credit pack itemCount sums quantities`() {
        val items = listOf(
            CreditPackItem(id = "i1", productId = "p1", quantity = 2),
            CreditPackItem(id = "i2", productId = "p2", quantity = 1),
        )
        val pack = CreditPack(id = "1", name = "Pack", items = items)
        assertEquals(2, pack.itemCount)
    }

    // MARK: - D679 · paquete Ilimitado (por dentro 99,999 con su marca; el POS nunca enseña ese número)

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodifica unlimited del renglon del paquete y del saldo`() {
        val pack = json.decodeFromString<CreditPack>(
            """{"id":"p1","name":"Mensual","items":[{"id":"i1","productId":"lagree","quantity":99999,"unlimited":true}]}""",
        )
        assertTrue(pack.items!!.single().unlimited)
        val compra = json.decodeFromString<CreditPurchaseBalance>(
            """{"id":"c1","expiresAt":"2026-12-23T05:59:59.000Z","itemBalances":[{"id":"b1","originalQuantity":99999,"remainingQuantity":99996,"unlimited":true}]}""",
        )
        assertTrue(compra.itemBalances.single().unlimited)
    }

    @Test
    fun `sin el campo unlimited queda false (servidor viejo)`() {
        val pack = json.decodeFromString<CreditPack>(
            """{"id":"p1","name":"5 clases","items":[{"id":"i1","productId":"lagree","quantity":5}]}""",
        )
        assertFalse(pack.items!!.single().unlimited)
        assertFalse(pack.isUnlimited)
        val saldo = json.decodeFromString<CreditBalanceItem>("""{"id":"b1","originalQuantity":5,"remainingQuantity":3}""")
        assertFalse(saldo.unlimited)
    }

    @Test
    fun `isUnlimited y creditsLabel dicen Ilimitado o los creditos`() {
        val ilimitado = CreditPack(
            id = "p1",
            items = listOf(
                CreditPackItem(id = "i1", productId = "lagree", quantity = 99_999, unlimited = true),
                CreditPackItem(id = "i2", productId = "shake", quantity = 2),
            ),
        )
        assertTrue(ilimitado.isUnlimited)
        assertEquals("Ilimitado", ilimitado.creditsLabel)

        val normal = CreditPack(id = "p2", items = listOf(CreditPackItem(id = "i1", productId = "lagree", quantity = 5)))
        assertFalse(normal.isUnlimited)
        assertEquals("5 créditos", normal.creditsLabel)
    }

    @Test
    fun `el saldo de un Ilimitado dice Ilimitado y su vencimiento en la zona del negocio, nunca 99999`() {
        val compra = CreditPurchaseBalance(
            id = "c1",
            // 23-dic 05:59 UTC = 22-dic 23:59 en Ciudad de México.
            expiresAt = "2026-12-23T05:59:59.000Z",
            itemBalances = listOf(
                CreditBalanceItem(id = "b1", originalQuantity = 99_999, remainingQuantity = 99_996, unlimited = true),
                CreditBalanceItem(id = "b2", originalQuantity = 5, remainingQuantity = 3),
            ),
        )
        val (ilimitado, normal) = compra.itemBalances
        assertEquals("Ilimitado · vence el 22 de diciembre", compra.textoDelSaldo(ilimitado, timezone = "America/Mexico_City"))
        assertEquals("Ilimitado · vence el 22 de diciembre", compra.textoDelSaldo(ilimitado, conTotal = false, timezone = "America/Mexico_City"))
        assertEquals("3 de 5 disponibles", compra.textoDelSaldo(normal))
        assertEquals("3 disponibles", compra.textoDelSaldo(normal, conTotal = false))
        assertEquals("Ilimitado", compra.copy(expiresAt = null).textoDelSaldo(ilimitado))
    }

    @Test
    fun `el JSON de guardar lleva unlimited en cada renglon y el Ilimitado sin cantidad`() {
        val renglones = renglonesDelPaquete(
            listOf(
                ArticlesViewModel.CreditPackItemInput(productId = "lagree", quantity = 99_999, unlimited = true),
                ArticlesViewModel.CreditPackItemInput(productId = "shake", quantity = 2),
                ArticlesViewModel.CreditPackItemInput(productId = "", quantity = 1),
            ),
        )
        assertEquals(2, renglones.size)
        val (ilimitado, normal) = renglones.map { it.jsonObject }
        assertEquals("true", ilimitado["unlimited"].toString())
        assertNull(ilimitado["quantity"])
        assertEquals("false", normal["unlimited"].toString())
        assertEquals("2", normal["quantity"].toString())
    }
}
