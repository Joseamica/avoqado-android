package com.avoqado.pos.pos.presentation.promotions

import com.avoqado.pos.pos.data.EstadoCatalogo
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.PromotionGroup
import com.avoqado.pos.pos.data.model.PromotionOption
import com.avoqado.pos.pos.presentation.checkout.InputTab
import com.avoqado.pos.tpvsettings.data.PanelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    // La columna de entrada se queda con el 50% del ancho TAMBIÉN con el lateral
    // abierto (quien paga la tercera columna es el carrito), así que el piso que
    // impone la cuadrícula son 3 celdas de 120dp dentro de ese 50% = 720dp. El
    // umbral que usamos, 960, es ese piso MÁS un margen elegido a mano: a 720
    // cada columna lateral cae a ~180dp y la tarjeta se ve apretada.
    // Ver ANCHO_MINIMO_PANEL_LATERAL_DP y el test del piso, más abajo.
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

    @Test
    fun `el umbral elegido nunca puede bajar del piso que impone la cuadricula`() {
        // La entrada se queda con el 50% también con el lateral abierto, así que
        // el piso estricto son 3 celdas de 120dp dentro de ese 50% = 720dp.
        assertEquals(720, ANCHO_ESTRICTO_PANEL_LATERAL_DP)
        // 960 es ese piso MÁS un margen de legibilidad elegido a mano. El margen
        // se puede ajustar con una tablet enfrente; bajar del piso no: ahí la
        // cuadrícula de productos deja de caber en 3 columnas.
        assertTrue(ANCHO_MINIMO_PANEL_LATERAL_DP >= ANCHO_ESTRICTO_PANEL_LATERAL_DP)
    }

    // ── "No hay" vs "no pude preguntar" ────────────────────────────────────

    @Test
    fun `mientras no sabemos, el panel no afirma nada`() {
        assertNull(mensajeSinTarjetas(EstadoCatalogo.SIN_CARGAR))
        assertNull(mensajeSinTarjetas(EstadoCatalogo.CARGANDO))
    }

    @Test
    fun `solo con respuesta del server se dice que no hay promociones`() {
        assertEquals(TEXTO_SIN_PROMOCIONES, mensajeSinTarjetas(EstadoCatalogo.CARGADO))
    }

    @Test
    fun `si no se pudo preguntar se habla de conexion, no de crear promociones`() {
        val mensaje = mensajeSinTarjetas(EstadoCatalogo.NO_SE_PUDO)
        assertEquals(TEXTO_NO_SE_PUDO_CARGAR, mensaje)
        // 🔴 Este es el test que falla si alguien vuelve a colapsar los dos casos
        // en un solo booleano: mandar a "crearlas desde el dashboard" cuando lo
        // que falló fue la red es mandar a REHACER algo que ya existe.
        assertNotEquals(mensajeSinTarjetas(EstadoCatalogo.CARGADO), mensaje)
        assertFalse(mensaje!!.contains("dashboard"))
        assertFalse(mensaje.contains("Créalas"))
    }

    // ── Qué pestañas se pintan ─────────────────────────────────────────────

    @Test
    fun `la pestana de promociones sale SOLO en modo pestana`() {
        assertTrue(InputTab.PROMOS in pestanasVisibles(PanelMode.TAB))
        // Con el panel lateral el cajero tendría DOS entradas a lo mismo.
        assertFalse(InputTab.PROMOS in pestanasVisibles(PanelMode.SIDE_PANEL))
        assertFalse(InputTab.PROMOS in pestanasVisibles(PanelMode.HIDDEN))
    }

    @Test
    fun `en el layout de un solo panel cualquier modo visible es pestana`() {
        // El teléfono no tiene tercera columna: SIDE_PANEL no puede significar
        // "no se ve", o el panel desaparecería en silencio.
        assertTrue(InputTab.PROMOS in pestanasVisibles(PanelMode.TAB, siempreComoPestana = true))
        assertTrue(InputTab.PROMOS in pestanasVisibles(PanelMode.SIDE_PANEL, siempreComoPestana = true))
        // HIDDEN sigue siendo HIDDEN en los DOS layouts: lo apagó el propio local.
        assertFalse(InputTab.PROMOS in pestanasVisibles(PanelMode.HIDDEN, siempreComoPestana = true))
    }

    @Test
    fun `esconder promociones no se lleva ninguna otra pestana`() {
        val conPromos = pestanasVisibles(PanelMode.TAB)
        val sinPromos = pestanasVisibles(PanelMode.HIDDEN)
        assertEquals(InputTab.entries.toList(), conPromos)
        assertEquals(InputTab.entries.filter { it != InputTab.PROMOS }, sinPromos)
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

    @Test
    fun `un pricingMode que esta app no conoce no pinta un precio`() {
        // Un modo nuevo del server no puede caer al camino de FIXED_TOTAL: ahí
        // pintaríamos un priceCents cuya semántica desconocemos.
        assertEquals(
            GANCHO_SIN_DATO,
            ganchoDePromocion(promocion(pricingMode = "MODO_QUE_NO_EXISTE_AUN", priceCents = 9900)),
        )
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
