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

    /** El campo con bandera tecla por tecla, como AvoqadoPhoneInput: guarda lo escrito y entrega los dígitos. */
    private fun teclear(texto: String, lada: String): String {
        var escrito = ""
        var digitos = ""
        for (tecla in texto) {
            escrito = filtrarTecleoDelCampoConBandera(textoVisibleDelCampo(escrito, digitos, lada) + tecla)
            digitos = digitosDelCampo(escrito, lada)   // lo mismo que entrega el campo (Codex r1 del 24-nov)
        }
        return digitos
    }

    @Test fun `P1 Codex r1 y r2 teclear de uno en uno da lo mismo que pegar en el campo con bandera`() {
        for ((texto, lada, esperado) in listOf(
            Triple("+52 1 55 9187 3922", "52", "5591873922"),
            Triple("+52 55 9187 3922", "52", "5591873922"),
            Triple("52 5591873922", "52", "5591873922"),
            Triple("52 1 55 9187 3922", "52", "5591873922"),
            Triple("+1 202 555 0123", "1", "2025550123"),
            Triple("55 9187 3922", "52", "5591873922"),
            Triple("+34 612 345 678", "34", "612345678"),     // Codex r2: antes «+3434612345678»
            Triple("+49 151 23456789", "49", "15123456789"),
            Triple("612 345 678", "34", "612345678"),
        )) {
            assertEquals("tecleado «$texto»", esperado, teclear(texto, lada))
            assertEquals("pegado «$texto»", esperado, digitosNacionales(filtrarTecleoDelCampoConBandera(texto), lada))
        }
    }

    @Test fun `P1 Codex r2 el campo enseña lo escrito y vuelve a los digitos si quien lo usa los cambia`() {
        assertEquals("+52 55 9187 3922", textoVisibleDelCampo("+52 55 9187 3922", "5591873922", "52"))
        assertEquals("", textoVisibleDelCampo("+52 55 9187 3922", "", "52"))            // lo vaciaron al enviar
        assertEquals("5512345678", textoVisibleDelCampo("", "5512345678", "52"))        // llegó un teléfono guardado
    }

    @Test fun `P1 Codex r2 un mas fuera de lugar o la lada entre parentesis`() {
        assertEquals("+525591873922", telefonoMx("(+52) 55 9187 3922"))
        assertNull(telefonoMx("(+53) 5 123 4567"))
        assertNull(telefonoMx("55+9187-3922"))
        assertNull(telefonoMx("++52 55 9187 3922"))
    }

    @Test fun `P1 Codex r2 lo que se escribe no esconde marcas que volteen lo que se ve ni se corta antes de tiempo`() {
        assertEquals("55 9187 3922", filtrarTecleoDeTelefono("55 9187 \u202E3922\u202C"))
        val largo = "\u202A+52  1  (55)  9187 - 3922\u202C"
        assertEquals("+525591873922", telefonoMx(filtrarTecleoDeTelefono(largo)))
        // Una letra: en las tarjetas se queda a la vista y no pasa; en el campo con bandera no se deja escribir.
        assertEquals("+52 55 9187 3922abc", filtrarTecleoDeTelefono("+52 55 9187 3922abc"))
        assertNull(telefonoMx(filtrarTecleoDeTelefono("+52 55 9187 3922abc")))
        assertEquals("+52 55 9187 3922", filtrarTecleoDelCampoConBandera("+52 55 9187 3922abc"))
    }

    @Test fun `P1 Codex r3 nada que cambie el numero se borra ni se corta en silencio`() {
        // Ancho completo: se convierte, no se borra.
        assertNull(telefonoMx(filtrarTecleoDeTelefono("＋53 5 123 4567")))                 // antes: «+525351234567»
        assertEquals("+525591873922", telefonoMx(filtrarTecleoDeTelefono("55９1873922")))
        assertEquals("+525591873922", telefonoMx("５５９１８７３９２２"))
        assertEquals("5591873922", digitosNacionales(filtrarTecleoDelCampoConBandera("55９1873922")))
        // Sin tope: ni se pierde el último dígito ni se esconde el que sobra.
        val espacios = " ".repeat(31)
        assertEquals("+525591873922", telefonoMx(filtrarTecleoDeTelefono(espacios + "5591873922")))
        assertEquals("5591873922", digitosNacionales(filtrarTecleoDelCampoConBandera(espacios + "5591873922")))
        assertNull(telefonoMx(filtrarTecleoDeTelefono("5591873922" + " ".repeat(30) + "1")))
    }

    @Test fun `P1 Codex r3 ningun celular de Mexico empieza con 0 o 1`() {
        assertNull(telefonoMx("+52 1 (55) 9187-392"))   // «+52 1» con un dígito de menos: antes «+521559187392»
        assertNull(telefonoMx("1559187392"))
        assertNull(telefonoMx("0559187392"))
    }

    @Test fun `P1 Codex r4 el signo menos copiado y el 1 viejo con un digito de menos`() {
        assertEquals("+525591873922", telefonoMx(filtrarTecleoDeTelefono("55−9187−3922")))   // U+2212
        assertEquals("5591873922", digitosNacionales(filtrarTecleoDelCampoConBandera("55−9187−3922")))
        // El campo con bandera ya no cuenta el «1» viejo como parte del número: quedan los 9 que se escribieron.
        assertEquals("559187392", digitosNacionales(filtrarTecleoDelCampoConBandera("+52 1 (55) 9187-392")))
        assertNull(telefonoMx("+52 1 (55) 9187-392"))
    }

    @Test fun `P1 Codex r3 al cambiar de pais los digitos se sacan otra vez de lo escrito`() {
        // Lo que hace AvoqadoPhoneInput al elegir otra bandera: con EE. UU. «15123456789» da «5123456789»; con Alemania,
        // de lo escrito sale «15123456789» (antes se quedaba «5123456789» y se mandaba «+495123456789»).
        val escrito = "15123456789"
        assertEquals("5123456789", digitosNacionales(escrito, "1"))
        val visible = textoVisibleDelCampo(escrito, digitosNacionales(escrito, "1"), "1")
        assertEquals("15123456789", digitosNacionales(visible, "49"))
        assertEquals("612345678", digitosNacionales("+34 612 345 678", "34"))
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
