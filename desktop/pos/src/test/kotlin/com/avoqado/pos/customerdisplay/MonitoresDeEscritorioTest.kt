package com.avoqado.pos.customerdisplay

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonitoresDeEscritorioTest {

    private val principal = Monitor("\\\\Display1", Rectangle(0, 0, 1920, 1080), principal = true)
    private val derecha = Monitor("\\\\Display2", Rectangle(1920, 0, 1024, 768), principal = false)
    private val izquierda = Monitor("\\\\Display3", Rectangle(-1280, 0, 1280, 800), principal = false)

    @Test fun `un solo monitor no tiene pantalla del cliente ni se puede invertir`() {
        val roles = rolesDeEscritorio(numerar(listOf(principal)), invertido = false)
        assertEquals(DisplayRoles(cashierDisplayId = 0, customerDisplayId = null, invertible = false), roles)
    }

    @Test fun `con dos monitores la caja va en el principal y el cliente en el otro`() {
        val numerados = numerar(listOf(derecha, principal))
        assertEquals(principal, numerados[0])
        assertEquals(derecha, numerados[1])
        assertEquals(DisplayRoles(cashierDisplayId = 0, customerDisplayId = 1, invertible = true), rolesDeEscritorio(numerados, invertido = false))
    }

    @Test fun `invertido cambia los papeles`() {
        val roles = rolesDeEscritorio(numerar(listOf(principal, derecha)), invertido = true)
        assertEquals(DisplayRoles(cashierDisplayId = 1, customerDisplayId = 0, invertible = true), roles)
    }

    @Test fun `invertido con un solo monitor no mueve nada`() {
        val roles = rolesDeEscritorio(numerar(listOf(principal)), invertido = true)
        assertEquals(0, roles.cashierDisplayId)
        assertNull(roles.customerDisplayId)
    }

    @Test fun `con tres monitores el cliente es el secundario de la izquierda`() {
        val numerados = numerar(listOf(derecha, principal, izquierda))
        assertEquals(izquierda, numerados[1])
        assertEquals(derecha, numerados[2])
        assertEquals(1, rolesDeEscritorio(numerados, invertido = false).customerDisplayId)
    }

    @Test fun `sin monitor principal declarado el primero hace de principal`() {
        val a = principal.copy(principal = false)
        val numerados = numerar(listOf(a, derecha))
        assertEquals(a, numerados[0])
        assertEquals(derecha, numerados[1])
    }

    @Test fun `sin monitores no hay nada`() {
        assertTrue(numerar(emptyList()).isEmpty())
        assertFalse(rolesDeEscritorio(emptyMap(), invertido = false).invertible)
    }

    @Test fun `la ventana pertenece al monitor que contiene su centro`() {
        val numerados = numerar(listOf(principal, derecha))
        assertEquals(0, monitorDe(Rectangle(100, 100, 800, 600), numerados))
        assertEquals(1, monitorDe(Rectangle(2000, 50, 600, 400), numerados))
    }

    @Test fun `el modo de prueba parte el principal en dos y deja los demas`() {
        val partidos = dividirEnDos(listOf(principal, derecha))
        assertEquals(Rectangle(0, 0, 960, 1080), partidos[0].limites)
        assertTrue(partidos[0].principal)
        assertEquals(Rectangle(960, 0, 960, 1080), partidos[1].limites)
        assertFalse(partidos[1].principal)
        assertEquals(derecha, partidos[2])
        // Una caja maximizada sobre la pantalla entera tiene el centro en la mitad del cliente: el manager la regresa a su mitad.
        assertEquals(1, monitorDe(Rectangle(0, 0, 1920, 1040), numerar(partidos)))
    }

    @Test fun `centro fuera de todo monitor - gana el de mayor interseccion`() {
        val numerados = numerar(listOf(principal, derecha))
        // Centro en (3100, 300): fuera de los dos; la mayor parte cae en el derecho.
        assertEquals(1, monitorDe(Rectangle(2600, 0, 1000, 600), numerados))
        // Totalmente fuera: null.
        assertNull(monitorDe(Rectangle(9000, 9000, 10, 10), numerados))
    }
}
