package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La regresión que estos tests protegen: con AnyDesk conectado hay DOS pantallas
 * "de presentación" (la real del cliente y la captura de AnyDesk), y elegir la
 * equivocada deja la pantalla del cliente en negro mientras alguien ve la caja
 * por remoto. Antes se elegía por orden de enumeración — o sea por suerte.
 */
class CustomerDisplaySelectionTest {

    private val hints = listOf("anydesk", "teamviewer", "rustdesk", "vnc", "screencap")

    @Test
    fun `sin candidatas no monta`() {
        assertNull(chooseCustomerDisplayId(emptyList(), hints))
    }

    @Test
    fun `la fisica gana aunque AnyDesk tenga id menor`() {
        // El caso que fallaba: AnyDesk enumera PRIMERO (id 2), la física es id 3.
        val chosen = chooseCustomerDisplayId(
            listOf(
                CandidateDisplay(2, "com.anydesk.anydeskandroid"),
                CandidateDisplay(3, null), // física, sin dueño
            ),
            hints,
        )
        assertEquals(3, chosen)
    }

    @Test
    fun `una sola fisica se elige`() {
        assertEquals(2, chooseCustomerDisplayId(listOf(CandidateDisplay(2, null)), hints))
    }

    @Test
    fun `T3 Pro - la virtual de Sunmi es valida cuando no hay fisica`() {
        // El cliente del T3 Pro es virtual (com.sunmi.usbscreen): NO se descarta.
        val chosen = chooseCustomerDisplayId(
            listOf(CandidateDisplay(3, "com.sunmi.usbscreen")),
            hints,
        )
        assertEquals(3, chosen)
    }

    @Test
    fun `solo AnyDesk y nada mas - no monta en la captura`() {
        // Mejor NADA que montar dentro de la captura de AnyDesk.
        assertNull(
            chooseCustomerDisplayId(listOf(CandidateDisplay(5, "com.anydesk.anydeskandroid")), hints),
        )
    }

    @Test
    fun `Sunmi valido conviviendo con AnyDesk - se elige Sunmi`() {
        val chosen = chooseCustomerDisplayId(
            listOf(
                CandidateDisplay(5, "com.anydesk.anydeskandroid"),
                CandidateDisplay(3, "com.sunmi.usbscreen"),
            ),
            hints,
        )
        assertEquals(3, chosen)
    }
}
