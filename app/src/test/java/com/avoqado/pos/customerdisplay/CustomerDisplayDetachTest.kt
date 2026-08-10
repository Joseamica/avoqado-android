package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regresión que protegen estos tests se MIDIÓ en un D3 físico y ninguna de
 * las cuatro rondas de revisión de código la encontró: al invertir las
 * pantallas, `CashierDisplayGuard` relanza la caja y Android la RECREA. En una
 * recreación el `onStart()` de la instancia NUEVA corre ANTES del `onStop()` de
 * la VIEJA, así que el orden real es `attach(nueva)` → `detach(vieja)`. Con un
 * detach que desmontara a ciegas, la instancia vieja mataba al morir la pantalla
 * del cliente que la nueva acababa de montar — y no volvía sola: el cliente se
 * quedaba viendo el launcher de Android el resto del turno.
 *
 * Depende de la temporización, o sea que es intermitente: en un local aparece "a
 * veces". Nadie la va a reproducir a mano dos veces; por eso vive aquí.
 */
class CustomerDisplayDetachTest {

    /** Sustituye a la Activity: la decisión es pura, no necesita Android. */
    private data class FakeHost(val name: String)

    @Test
    fun `el detach del anfitrion vigente si desmonta`() {
        val host = FakeHost("caja")
        assertTrue(shouldTearDownOnDetach(currentHost = host, caller = host))
    }

    @Test
    fun `tras la recreacion, el detach tardio de la instancia vieja NO desmonta`() {
        // El caso medido: attach(nueva) ya corrió, y ahora llega el onStop de la vieja.
        val vieja = FakeHost("caja")
        val nueva = FakeHost("caja")
        assertFalse(shouldTearDownOnDetach(currentHost = nueva, caller = vieja))
    }

    @Test
    fun `la comparacion es por INSTANCIA, no por igualdad`() {
        // Dos Activity distintas pueden ser `equals` (aquí lo son: mismo data class,
        // mismos campos). Si se comparara por igualdad, la vieja pasaría el filtro y
        // volveríamos exactamente al bug.
        val vieja = FakeHost("caja")
        val nueva = FakeHost("caja")
        assertTrue(vieja == nueva)
        assertFalse(shouldTearDownOnDetach(currentHost = nueva, caller = vieja))
    }

    @Test
    fun `sin anfitrion no hay nada que desmontar`() {
        // detach() repetido, o el de una instancia posterior a un desmontaje real.
        assertFalse(shouldTearDownOnDetach(currentHost = null, caller = FakeHost("caja")))
    }
}
