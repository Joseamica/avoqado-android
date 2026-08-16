package com.avoqado.pos.pos.presentation.promotions

import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.PromotionGroup
import com.avoqado.pos.pos.data.model.PromotionOption
import com.avoqado.pos.tpvsettings.data.PanelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

/**
 * La caída automática del panel lateral — lógica PURA, sin Compose.
 *
 * 🔴 El umbral y su cálculo se espejan en iOS (Task 5). Si cambia aquí, cambia
 * allá en el MISMO trabajo: si divergen, el mismo local ve el panel en un lado
 * y no en el otro y nadie entiende por qué.
 */
class PromotionsPanelLayoutTest {
    // Con panel lateral, la columna de entrada se queda con el 37.5% del ancho.
    // Una celda de producto necesita ~120dp y son 3 columnas -> 360dp -> el
    // lateral sólo cabe a partir de ~960dp. Debajo de eso es ilegible.
    @Test
    fun `el panel lateral cae a pestana bajo el umbral`() {
        assertEquals(PanelMode.TAB, resolverModoPanel(PanelMode.SIDE_PANEL, anchoDp = 800))
        assertEquals(PanelMode.SIDE_PANEL, resolverModoPanel(PanelMode.SIDE_PANEL, anchoDp = 1370))
    }

    @Test
    fun `pestana se respeta en cualquier ancho`() {
        assertEquals(PanelMode.TAB, resolverModoPanel(PanelMode.TAB, anchoDp = 1370))
    }

    @Test
    fun `oculto NUNCA se convierte en visible`() {
        assertEquals(PanelMode.HIDDEN, resolverModoPanel(PanelMode.HIDDEN, anchoDp = 1370))
    }

    // ── El gancho de la tarjeta: es dinero en pantalla ──────────────────────

    @Test
    fun `el 2x1 sale de quantity y chargedQuantity, no del precio`() {
        assertEquals("2x1", ganchoDePromocion(promocion(pricingMode = "PER_UNIT", quantity = 2, chargedQuantity = 1)))
        assertEquals("3x2", ganchoDePromocion(promocion(pricingMode = "PER_UNIT", quantity = 3, chargedQuantity = 2)))
    }

    @Test
    fun `un PER_UNIT jamas pinta priceCents — ahi el precio sale del producto`() {
        // priceCents sólo existe en FIXED_TOTAL (schema.prisma). Pintarlo en un
        // PER_UNIT pondría "$0.00" en la cara del cliente.
        val sinRazon = promocion(pricingMode = "PER_UNIT", quantity = 1, chargedQuantity = 1, priceCents = 9900)
        assertEquals(GANCHO_SIN_DATO, ganchoDePromocion(sinRazon))
    }

    @Test
    fun `un FIXED_TOTAL pinta su precio, y nunca un cero`() {
        assertEquals("$99.00", ganchoDePromocion(promocion(pricingMode = "FIXED_TOTAL", priceCents = 9900)))
        assertEquals(GANCHO_SIN_DATO, ganchoDePromocion(promocion(pricingMode = "FIXED_TOTAL", priceCents = 0)))
    }

    // ── La hora del negocio, nunca la del aparato ───────────────────────────

    @Test
    fun `la hora se escribe como la lee el cajero`() {
        assertEquals("6:00 pm", horaLegible("18:00"))
        assertEquals("12:30 am", horaLegible("00:30"))
        assertEquals("12:00 pm", horaLegible("12:00"))
        assertEquals("9:05 am", horaLegible("09:05"))
    }

    @Test
    fun `una hora ilegible no se inventa`() {
        assertNull(horaLegible(null))
        assertNull(horaLegible(""))
        assertNull(horaLegible("mañana"))
        assertNull(horaLegible("25:00"))
    }

    @Test
    fun `la tarjeta apagada dice a que hora abre y cuanto falta`() {
        assertEquals(
            "Empieza a las 6:00 pm · Faltan 40 minutos",
            etiquetaProxima("18:00", ahora = LocalTime.of(17, 20)),
        )
        assertEquals(
            "Empieza a las 6:00 pm · Faltan 2 h 30 min",
            etiquetaProxima("18:00", ahora = LocalTime.of(15, 30)),
        )
    }

    @Test
    fun `una promo que abre pasada la medianoche no dice que ya paso`() {
        // El server sólo manda lo que abre dentro de 4h: una hora "anterior" es
        // de mañana, no de ayer. Sin esto la cuenta saldría negativa y la
        // tarjeta no diría nada.
        assertEquals(60, minutosFaltantes("00:30", ahora = LocalTime.of(23, 30)))
    }

    @Test
    fun `sin startsAt la tarjeta no escribe hora`() {
        assertNull(etiquetaProxima(null, ahora = LocalTime.of(17, 20)))
    }

    private fun promocion(
        pricingMode: String,
        quantity: Int = 1,
        chargedQuantity: Int = 1,
        priceCents: Int = 0,
    ) = Promotion(
        id = "promo-1",
        name = "Promo de prueba",
        pricingMode = pricingMode,
        priceCents = priceCents,
        groups = listOf(
            PromotionGroup(
                id = "g1",
                name = "Grupo",
                options = listOf(
                    PromotionOption(
                        id = "o1",
                        productId = "p1",
                        quantity = quantity,
                        chargedQuantity = chargedQuantity,
                        productName = "Cerveza",
                        productPriceCents = 4500,
                    ),
                ),
            ),
        ),
    )
}
