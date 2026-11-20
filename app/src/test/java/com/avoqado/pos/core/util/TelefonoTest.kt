package com.avoqado.pos.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Flautas Coyoacán (19-nov-2026): en «Vender tarjeta» escribió «+52 5591873922» y el campo nunca pasó, porque sólo
 * aceptaba 10 dígitos pelones. Lo que la gente escribe de verdad tiene que quedar en los 10 dígitos.
 */
class TelefonoTest {

    @Test fun `P1 lo que la gente escribe queda en los 10 digitos de Mexico`() {
        val escritos = listOf(
            "5591873922",
            "+52 5591873922",
            "+525591873922",
            "52 5591873922",
            "525591873922",
            "+52 1 55 9187 3922",
            "5215591873922",
            "55 9187 3922",
            "55-9187-3922",
            "(55) 9187-3922",
            "+52 (55) 9187-3922",
            "55.9187.3922",
            "  5591873922  ",
        )
        for (texto in escritos) {
            assertEquals("«$texto»", "5591873922", digitosNacionales(texto))
            assertEquals("«$texto»", "+525591873922", telefonoMx(texto))
        }
    }

    @Test fun `P1 lo que no es un celular de Mexico no pasa`() {
        val malos = listOf(
            "",
            "559187392",          // 9 dígitos
            "55918739221",        // 11 dígitos que no empiezan con 52
            "+1 202 555 0123",    // otra lada: la tarjeta sólo entrega a México
            "55 9187 39a2",       // una letra
            "５５91873922",       // dígitos de ancho completo
            "52559187392",        // 52 + 9 dígitos
            "+52 55 9187 39221",  // 52 + 11 dígitos
        )
        for (texto in malos) assertNull("«$texto»", telefonoMx(texto))
    }

    @Test fun `P1 Codex r1 un numero con otra lada o el +52 incompleto no se vuelve mexicano`() {
        // Antes del arreglo de r1: «+53 5 123 4567» salía como «+525351234567» y «+52 55 9187 39» como «+525255918739».
        for (texto in listOf("+53 5 123 4567", "+1 555 123 4567", "+52 55 9187 39", "+5255123456", "+44 20 7946 0958"))
            assertNull("«$texto»", telefonoMx(texto))
    }

    @Test fun `P1 Codex r1 espacios y marcas invisibles de copiar y pegar no tumban el numero`() {
        val nbsp = "\u00A0"
        val escritos = listOf(
            "(55)${nbsp}9187-3922",                  // espacio no separable
            "\u202A+52 55 9187 3922\u202C",          // marcas de dirección que mete WhatsApp al copiar un contacto
            "\u200E+52 55 9187 3922",
            "55\u20119187\u20113922",                // guion que no se parte
            "55 9187–3922",                          // guion largo
        )
        for (texto in escritos) assertEquals("«$texto»", "+525591873922", telefonoMx(texto))
    }

    @Test fun `P1 Codex r1 teclear de uno en uno da lo mismo que pegar en el campo con bandera`() {
        // AvoqadoPhoneInput sólo guarda dígitos: cada tecla llega con lo que ya quedó guardado. Se simula tecla por tecla.
        fun teclear(texto: String, lada: String): String =
            texto.fold("") { guardado, tecla -> digitosDelCampoConBandera(guardado + tecla, lada) }
        for ((texto, lada, esperado) in listOf(
            Triple("+52 1 55 9187 3922", "52", "5591873922"),
            Triple("+52 55 9187 3922", "52", "5591873922"),
            Triple("52 5591873922", "52", "5591873922"),
            Triple("+1 202 555 0123", "1", "2025550123"),
            Triple("55 9187 3922", "52", "5591873922"),
        )) {
            assertEquals("tecleado «$texto»", esperado, teclear(texto, lada))
            assertEquals("pegado «$texto»", esperado, digitosDelCampoConBandera(texto, lada))
        }
        // Otra lada: pegado y tecleado dan lo mismo (como antes del cambio, no se adivina la lada repetida).
        assertEquals(teclear("+49 151 23456789", "49"), digitosDelCampoConBandera("+49 151 23456789", "49"))
    }

    @Test fun `P1 un numero de 10 digitos que empieza con 52 no pierde su principio`() {
        assertEquals("5255123456", digitosNacionales("5255123456"))
        assertEquals("+525255123456", telefonoMx("5255123456"))
    }

    @Test fun `P2 al teclear de uno en uno la lada se quita en cuanto sobra`() {
        // El campo con bandera (AvoqadoPhoneInput) sólo guarda dígitos: el «+» no llega, así que manda el largo.
        assertEquals("52559187392", digitosNacionales("52559187392"))
        assertEquals("5591873922", digitosNacionales("525591873922"))
    }

    @Test fun `P2 con otra lada se quita la lada que la persona repitio`() {
        assertEquals("2025550123", digitosNacionales("+1 202 555 0123", "1"))
        assertEquals("2025550123", digitosNacionales("12025550123", "1"))
        assertEquals("2025550123", digitosNacionales("202-555-0123", "1"))
        // Alemania: con «+49» delante se quita; sin el «+», 12 dígitos todavía caben como número de Alemania.
        assertEquals("1512345678", digitosNacionales("+49 151 2345678", "49"))
        assertEquals("491512345678", digitosNacionales("491512345678", "49"))
    }
}
