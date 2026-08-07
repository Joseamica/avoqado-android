package com.avoqado.pos.pos.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * 🔴 Paridad con el server y con iOS — vectores COMPARTIDOS.
 *
 * Spec: Avoqado-HQ/specs/upsell-pantalla-cliente-2026-08-03.md (decisión R5)
 *
 * El fallo que este test existe para impedir NO truena nada. Si Kotlin sortea el
 * grupo de control distinto que TypeScript, el POS y el server creen estar viendo
 * el mismo experimento y no lo están: el reporte de "cuánto subió el ticket"
 * compara dos poblaciones diferentes y da un número que se ve perfectamente
 * normal. Nadie lo detecta leyendo la pantalla.
 *
 * El archivo es el MISMO en los tres repos. La autoridad es TypeScript.
 */
class UpsellVectorParityTest {

    private val vectors = Json.parseToJsonElement(
        File("src/test/resources/upsell-test-vectors.json").readText(),
    ).jsonObject

    @Test
    fun `el bucket del holdout es idéntico al del server`() {
        val cases = vectors["holdout"]!!.jsonObject["cases"]!!.jsonArray
        // Si el archivo llegara vacío el test pasaría sin probar nada.
        assert(cases.size >= 10) { "Se esperaban vectores de holdout, llegaron ${cases.size}" }

        cases.forEach { case ->
            val o = case.jsonObject
            val id = o["impressionId"]!!.jsonPrimitive.content
            assertEquals("bucket de \"$id\"", o["bucket"]!!.jsonPrimitive.int, UpsellHoldout.bucket(id))
            assertEquals(
                "holdout de \"$id\" al 10%",
                o["holdoutAt10"]!!.jsonPrimitive.boolean,
                UpsellHoldout.isHoldout(id, 10),
            )
        }
    }

    @Test
    fun `los vectores traen casos de AMBOS lados del sorteo`() {
        // Un set donde nadie cae en el grupo de control no probaría la mitad
        // interesante: un `return false` constante lo pasaría entero.
        val cases = vectors["holdout"]!!.jsonObject["cases"]!!.jsonArray
        val dentro = cases.count { it.jsonObject["holdoutAt10"]!!.jsonPrimitive.boolean }
        assert(dentro >= 3) { "Sólo $dentro vectores caen en el grupo de control" }
        assert(dentro < cases.size) { "TODOS los vectores caen en el grupo de control" }
    }

    @Test
    fun `la ventana de días y horas coincide con los vectores`() {
        val cases = vectors["timeWindow"]!!.jsonObject["cases"]!!.jsonArray
        cases.forEach { case ->
            val o = case.jsonObject
            val rule = com.avoqado.pos.pos.data.model.UpsellRule(
                id = "v",
                suggestedProductId = "p",
                daysOfWeek = (o["daysOfWeek"] as JsonArray).map { it.jsonPrimitive.int },
                timeFrom = o["timeFrom"].takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                timeUntil = o["timeUntil"].takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            )
            val now = LocalDateTime.parse(o["nowLocal"]!!.jsonPrimitive.content)

            assertEquals(
                o["name"]!!.jsonPrimitive.content,
                o["expected"]!!.jsonPrimitive.boolean,
                rule.isWithinWindow(now),
            )
        }
    }

    @Test
    fun `el externalId de una línea aceptada coincide con los vectores`() {
        vectors["externalId"]!!.jsonObject["cases"]!!.jsonArray.forEach { case ->
            val o = case.jsonObject
            assertEquals(
                o["expected"]!!.jsonPrimitive.content,
                UpsellHoldout.externalId(o["impressionId"]!!.jsonPrimitive.content, o["index"]!!.jsonPrimitive.int),
            )
        }
    }
}
