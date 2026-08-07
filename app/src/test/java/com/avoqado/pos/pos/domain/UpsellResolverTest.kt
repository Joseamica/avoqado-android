package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.LinkedDiscount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.UpsellRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Upsell — la decisión de qué tarjetas mostrar.
 *
 * Cada exclusión de aquí existe por una razón concreta, no por completitud:
 *  - peso y modificadores obligatorios: la tarjeta abriría un formulario en vez de
 *    agregar; deja de ser sugerencia y se vuelve trámite.
 *  - agotado: vender lo que no hay.
 *  - ya en el carrito: ofrecer lo que ya llevan quema la tarjeta.
 *  - veto del dueño: gana sobre las cuatro capas, incluida la IA.
 *  - ventana horaria: el desayuno no se ofrece a las 11 de la noche.
 *
 * Y el caso que más duele si se rompe: la ventana que CRUZA MEDIANOCHE. Una regla
 * de viernes 22:00–02:00 debe seguir viva a la 1 de la mañana del sábado.
 */
class UpsellResolverTest {

    // ── ayudas ────────────────────────────────────────────────────────────────

    private fun product(
        id: String,
        name: String = id,
        price: Double = 20.0,
        upsellEnabled: Boolean? = true,
        active: Boolean? = true,
        soldByWeight: Boolean = false,
        trackInventory: Boolean? = null,
        availableQuantity: Int? = null,
    ) = Product(
        id = id,
        name = name,
        priceValue = price,
        upsellEnabled = upsellEnabled,
        active = active,
        soldByWeight = soldByWeight,
        trackInventory = trackInventory,
        availableQuantity = availableQuantity,
    )

    private fun rule(
        id: String = "r1",
        suggested: String = "galleta",
        triggerType: String = "ALWAYS",
        triggerProductIds: List<String> = emptyList(),
        priority: Int = 0,
        lift: Double? = null,
        daysOfWeek: List<Int> = emptyList(),
        timeFrom: String? = null,
        timeUntil: String? = null,
        linkedDiscount: LinkedDiscount? = null,
    ) = UpsellRule(
        id = id,
        suggestedProductId = suggested,
        triggerType = triggerType,
        triggerProductIds = triggerProductIds,
        priority = priority,
        lift = lift,
        daysOfWeek = daysOfWeek,
        timeFrom = timeFrom,
        timeUntil = timeUntil,
        linkedDiscount = linkedDiscount,
    )

    /** Un lunes cualquiera a mediodía. */
    private val lunesMediodia: LocalDateTime = LocalDateTime.of(2026, 8, 3, 12, 0)

    private fun resolve(
        rules: List<UpsellRule>,
        catalog: List<Product>,
        cart: Set<String> = emptySet(),
        cartCategories: Set<String> = emptySet(),
        now: LocalDateTime = lunesMediodia,
    ) = resolveUpsellSuggestions(rules, cart, cartCategories, catalog.associateBy { it.id }, now)

    // ── el camino feliz ───────────────────────────────────────────────────────

    @Test
    fun `una regla ALWAYS con producto habilitado se sugiere`() {
        val cards = resolve(listOf(rule()), listOf(product("galleta", "Galleta de nuez")))
        assertEquals(1, cards.size)
        assertEquals("Galleta de nuez", cards[0].name)
    }

    // ── las exclusiones ───────────────────────────────────────────────────────

    @Test
    fun `🔴 el veto del dueño gana aunque la regla esté activa`() {
        val cards = resolve(listOf(rule()), listOf(product("galleta", upsellEnabled = false)))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `un producto sin el campo (server viejo) se trata como vetado`() {
        val cards = resolve(listOf(rule()), listOf(product("galleta", upsellEnabled = null)))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `no se sugiere lo que ya está en el carrito`() {
        val cards = resolve(listOf(rule()), listOf(product("galleta")), cart = setOf("galleta"))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `no se sugiere un producto vendido por peso`() {
        val cards = resolve(listOf(rule()), listOf(product("galleta", soldByWeight = true)))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `no se sugiere un producto agotado`() {
        val cards = resolve(
            listOf(rule()),
            listOf(product("galleta", trackInventory = true, availableQuantity = 0)),
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `no se sugiere un producto inactivo`() {
        val cards = resolve(listOf(rule()), listOf(product("galleta", active = false)))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `una regla huérfana (producto fuera del catálogo) no truena`() {
        val cards = resolve(listOf(rule(suggested = "fantasma")), listOf(product("galleta")))
        assertTrue(cards.isEmpty())
    }

    // ── disparadores ──────────────────────────────────────────────────────────

    @Test
    fun `un disparador por producto sólo aplica si ese producto está en el carrito`() {
        val r = rule(triggerType = "PRODUCT", triggerProductIds = listOf("cafe"))
        val catalog = listOf(product("galleta"))

        assertTrue(resolve(listOf(r), catalog, cart = setOf("te")).isEmpty())
        assertEquals(1, resolve(listOf(r), catalog, cart = setOf("cafe")).size)
    }

    // ── orden, tope y duplicados ──────────────────────────────────────────────

    @Test
    fun `nunca se pintan más de 3 tarjetas`() {
        val rules = (1..6).map { rule(id = "r$it", suggested = "p$it") }
        val catalog = (1..6).map { product("p$it") }
        assertEquals(MAX_UPSELL_CARDS, resolve(rules, catalog).size)
    }

    @Test
    fun `manda la prioridad, y el lift desempata`() {
        val rules = listOf(
            rule(id = "baja", suggested = "a", priority = 0, lift = 9.0),
            rule(id = "alta", suggested = "b", priority = 5, lift = 1.0),
            rule(id = "media", suggested = "c", priority = 0, lift = 2.0),
        )
        val cards = resolve(rules, listOf(product("a"), product("b"), product("c")))
        assertEquals(listOf("b", "a", "c"), cards.map { it.productId })
    }

    @Test
    fun `🔴 dos capas que sugieren el MISMO producto dan UNA tarjeta, no dos`() {
        val rules = listOf(
            rule(id = "de-datos", suggested = "galleta", priority = 0),
            rule(id = "del-dueño", suggested = "galleta", priority = 5),
        )
        val cards = resolve(rules, listOf(product("galleta")))

        assertEquals(1, cards.size)
        assertEquals("del-dueño", cards[0].ruleId) // gana la de mayor prioridad
    }

    // ── ventana de días y horas ───────────────────────────────────────────────

    @Test
    fun `sin ventana, siempre aplica`() {
        assertTrue(rule().isWithinWindow(lunesMediodia))
    }

    @Test
    fun `fuera del horario no aplica`() {
        val desayuno = rule(timeFrom = "07:00", timeUntil = "11:00")
        assertTrue(desayuno.isWithinWindow(LocalDateTime.of(2026, 8, 3, 8, 0)))
        assertFalse(desayuno.isWithinWindow(LocalDateTime.of(2026, 8, 3, 23, 0)))
    }

    @Test
    fun `el día se cuenta con 0=domingo, como en Discount`() {
        val soloDomingo = rule(daysOfWeek = listOf(0))
        assertTrue(soloDomingo.isWithinWindow(LocalDateTime.of(2026, 8, 2, 12, 0))) // domingo
        assertFalse(soloDomingo.isWithinWindow(LocalDateTime.of(2026, 8, 3, 12, 0))) // lunes
    }

    @Test
    fun `🔴 una ventana que cruza medianoche sigue viva de madrugada`() {
        // Viernes 22:00 a 02:00. daysOfWeek = [5] = viernes.
        val nocturna = rule(daysOfWeek = listOf(5), timeFrom = "22:00", timeUntil = "02:00")

        // Viernes 23:00: dentro.
        assertTrue(nocturna.isWithinWindow(LocalDateTime.of(2026, 8, 7, 23, 0)))
        // Sábado 01:00: sigue siendo la jornada del viernes.
        assertTrue(nocturna.isWithinWindow(LocalDateTime.of(2026, 8, 8, 1, 0)))
        // Sábado 23:00: eso ya es la jornada del sábado, NO aplica.
        assertFalse(nocturna.isWithinWindow(LocalDateTime.of(2026, 8, 8, 23, 0)))
        // Viernes 12:00: fuera del horario.
        assertFalse(nocturna.isWithinWindow(LocalDateTime.of(2026, 8, 7, 12, 0)))
    }

    @Test
    fun `una hora con formato inválido no tumba el cobro`() {
        val rota = rule(timeFrom = "no soy hora", timeUntil = "tampoco")
        assertTrue(rota.isWithinWindow(lunesMediodia)) // se ignora la ventana
    }

    // ── precio mostrado ───────────────────────────────────────────────────────

    @Test
    fun `el descuento porcentual se refleja en el precio de la tarjeta`() {
        val r = rule(linkedDiscount = LinkedDiscount("d1", "PERCENTAGE", 50.0, "-50%"))
        val cards = resolve(listOf(r), listOf(product("galleta", price = 40.0)))

        assertEquals(2000, cards[0].displayPriceCents) // $40 → $20
        assertEquals("-50%", cards[0].badge)
    }

    @Test
    fun `un descuento fijo mayor al precio no deja la tarjeta en negativo`() {
        val r = rule(linkedDiscount = LinkedDiscount("d1", "FIXED_AMOUNT", 15.0, "-\$15"))
        val cards = resolve(listOf(r), listOf(product("galleta", price = 10.0)))

        assertEquals(0, cards[0].displayPriceCents)
    }

    @Test
    fun `sin descuento, la tarjeta muestra el precio de lista`() {
        val cards = resolve(listOf(rule()), listOf(product("galleta", price = 35.0)))
        assertEquals(3500, cards[0].displayPriceCents)
        assertEquals(null, cards[0].badge)
    }
}
