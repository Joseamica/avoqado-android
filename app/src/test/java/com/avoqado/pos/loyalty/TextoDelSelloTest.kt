package com.avoqado.pos.loyalty

import com.avoqado.pos.loyalty.data.SaleStampOutcome
import com.avoqado.pos.loyalty.data.SaleStampReason
import com.avoqado.pos.loyalty.domain.RenglonDelSello
import com.avoqado.pos.loyalty.domain.TextosDelSello
import com.avoqado.pos.loyalty.domain.textoDelSello
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El renglón del sello en «Cobro exitoso» (sellos tardíos, Testarudo 14-oct). Los textos son los del contrato
 * compartido con iOS, letra por letra: aquí se fijan para que nadie los «mejore» de un solo lado.
 */
class TextoDelSelloTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun sello(
        stamped: Boolean = false,
        reason: String? = null,
        earned: Int = 0,
        required: Int = 7,
        reward: Boolean = false,
        enabled: Boolean = true,
    ) = SaleStampOutcome(
        stampsEnabled = enabled,
        stamped = stamped,
        reason = reason,
        stampsEarned = earned,
        stampsRequired = required,
        rewardReady = reward,
    )

    @Test
    fun `sello sumado dice cuantos lleva de cuantos`() {
        assertEquals("✓ +1 sello · 3 de 7", textoDelSello(sello(stamped = true, earned = 3)))
    }

    @Test
    fun `tarjeta completa con premio por cobrar`() {
        assertEquals(
            "🎁 ¡Completó su tarjeta! Tiene un premio por cobrar",
            textoDelSello(sello(stamped = true, earned = 0, reward = true)),
        )
    }

    @Test
    fun `un premio viejo sin cobrar no hace decir que completo la tarjeta`() {
        assertEquals("✓ +1 sello · 1 de 7", textoDelSello(sello(stamped = true, earned = 1, reward = true)))
    }

    @Test
    fun `cada motivo tiene el texto exacto del contrato`() {
        assertEquals("Esta venta ya sumó su sello a otro cliente", textoDelSello(sello(reason = "OTHER_CUSTOMER")))
        assertEquals("Ya recibió su sello de hoy", textoDelSello(sello(reason = "DAILY_LIMIT")))
        assertEquals("Pasaron más de 24 h: esta venta no suma sello", textoDelSello(sello(reason = "WINDOW_EXPIRED")))
        assertEquals("Esta venta no está pagada completa: no suma sello", textoDelSello(sello(reason = "NOT_PAID")))
    }

    @Test
    fun `pendiente, error o un motivo que esta version no conoce no pintan nada`() {
        assertNull(textoDelSello(sello(reason = "PENDING")))
        assertNull(textoDelSello(sello(reason = "ERROR")))
        assertNull(textoDelSello(sello(reason = "ALGO_NUEVO")))
        assertNull(sello(reason = "ALGO_NUEVO").reasonCode)
        assertEquals(SaleStampReason.PENDING, sello(reason = "PENDING").reasonCode)
    }

    @Test
    fun `P1 programa de sellos apagado o servidor viejo sin el campo no pintan nada`() {
        assertNull(textoDelSello(sello(stamped = true, earned = 3, enabled = false)))
        assertNull(textoDelSello(null))
    }

    @Test
    fun `P1 un objeto a medias o con nulos se lee con sus defaults en vez de romper`() {
        val leido = json.decodeFromString(
            SaleStampOutcome.serializer(),
            """{"stampsEnabled":true,"stamped":false,"reason":"DAILY_LIMIT","stampsEarned":null,"campoNuevo":1}""",
        )
        assertEquals(SaleStampOutcome(stampsEnabled = true, reason = "DAILY_LIMIT"), leido)
        assertEquals("Ya recibió su sello de hoy", textoDelSello(leido))
    }

    @Test
    fun `el renglon es verde solo cuando el sello si se sumo`() {
        assertEquals(
            RenglonDelSello("✓ +1 sello · 3 de 7", positivo = true),
            RenglonDelSello.desde(sello(stamped = true, earned = 3)),
        )
        assertEquals(
            RenglonDelSello("Ya recibió su sello de hoy", positivo = false),
            RenglonDelSello.desde(sello(reason = "DAILY_LIMIT")),
        )
        assertNull(RenglonDelSello.desde(sello(reason = "PENDING")))
        assertEquals(
            RenglonDelSello("El sello se suma al sincronizar", positivo = false),
            RenglonDelSello.SE_SUMA_AL_SINCRONIZAR,
        )
    }

    @Test
    fun `sin red al agregar cliente el aviso lleva el plazo de 24 h`() {
        assertEquals(
            "Sin conexión: agrégalo cuando vuelva la red. Tienes 24 h para que sume el sello.",
            TextosDelSello.SIN_RED_AL_AGREGAR_CLIENTE,
        )
    }
}
