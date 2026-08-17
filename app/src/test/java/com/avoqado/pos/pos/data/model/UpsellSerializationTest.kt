package com.avoqado.pos.pos.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 El CONTRATO con el server, capturado de una respuesta REAL.
 *
 * `upsell_rules_response.json` no está inventado: salió literalmente de
 * `GET /mobile/venues/:id/upsell-rules` contra la base, con una regla creada
 * desde el dashboard.
 *
 * Por qué importa: el POS decodifica con `ignoreUnknownKeys = true`, así que si
 * el server renombra un campo, Kotlin NO truena — simplemente deja el valor en su
 * default y la sugerencia deja de aparecer, en silencio, en cada mostrador. Este
 * test es el que hace ruido en su lugar.
 */
class UpsellSerializationTest {

    // Mismo config que `UpsellRepository` — probar con un Json distinto al que
    // corre en producción sería probar otra cosa.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun fixture(name: String) = File("src/test/resources/fixtures/$name").readText()

    @Test
    fun `decodifica la respuesta REAL del server sin perder campos`() {
        val decoded = json.decodeFromString(
            UpsellRulesResponse.serializer(),
            fixture("upsell_rules_response.json"),
        )

        assertTrue(decoded.success)
        assertEquals(1, decoded.data.rules.size)

        val rule = decoded.data.rules[0]
        assertEquals("cmsf6ozsh0007c942sqqomsd5", rule.id)
        assertEquals("ALWAYS", rule.triggerType)
        assertEquals("cmpe651og00jz9k92545uyz0a", rule.suggestedProductId)
        assertEquals("¿Le agregamos un agua bien fría?", rule.headline)
        assertEquals(0, rule.priority)
        assertNull(rule.lift)
        assertTrue(rule.daysOfWeek.isEmpty())
        assertNull(rule.timeFrom)

        assertTrue(decoded.data.surfaces.counter)
        assertTrue(decoded.data.surfaces.tableOrdering)
        assertTrue(decoded.data.surfaces.tablePaying)
        assertEquals(10, decoded.data.holdoutPercent)
    }

    @Test
    fun `un server VIEJO que no manda las perillas no rompe el cobro`() {
        // Compatibilidad hacia atrás: los tres defaults en true y el holdout en 10.
        // Si esto fallara, un backend sin desplegar dejaría al POS sin sugerencias
        // o —peor— con una excepción en la ruta del cobro.
        val decoded = json.decodeFromString(
            UpsellRulesResponse.serializer(),
            """{"success":true,"data":{"rules":[]}}""",
        )
        assertTrue(decoded.data.surfaces.counter)
        assertEquals(10, decoded.data.holdoutPercent)
        assertTrue(decoded.data.rules.isEmpty())
    }

    @Test
    fun `un campo NUEVO del server no tumba la decodificación`() {
        // El server puede agregar campos sin publicar un APK nuevo — y debe poder,
        // porque el POS tarda días en llegar a los locales.
        val decoded = json.decodeFromString(
            UpsellRulesResponse.serializer(),
            """{"success":true,"data":{"rules":[{"id":"r1","suggestedProductId":"p1","campoDelFuturo":42}],"holdoutPercent":5}}""",
        )
        assertEquals("r1", decoded.data.rules[0].id)
        assertEquals(5, decoded.data.holdoutPercent)
    }

    @Test
    fun `🟡 un 'null' EXPLÍCITO en suggestedModifiers no truena — cae al default`() {
        // Un default sólo cubre la llave AUSENTE; un `null` presente sobre una
        // propiedad no-nulable revienta la decodificación SIN `coerceInputValues`.
        // El server de hoy nunca manda este `null` (lo fuerza a `[]`), pero el
        // KDoc de `UpsellRule.suggestedModifiers` lo promete tolerar — este test
        // respalda esa promesa en vez de dejarla en el aire.
        val decoded = json.decodeFromString(
            UpsellRulesResponse.serializer(),
            """{"success":true,"data":{"rules":[{"id":"r1","suggestedProductId":"p1","suggestedModifiers":null}]}}""",
        )
        assertTrue(decoded.data.rules[0].suggestedModifiers.isEmpty())
    }
}
