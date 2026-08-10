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

    // MARK: - La otra cara: el attach se hace cargo de lo que heredó
    //
    // 🔴 Esto toca el MODO NORMAL, el que corre hoy en producción. Al hacer el
    // detach consciente de instancia, el detach tardío de la Activity vieja se
    // ignora ENTERO — incluido su dismiss(). La Presentation cuelga de la
    // Activity que la creó, así que tras una recreación (rotación en tablet,
    // cambio automático a tema oscuro) la ventana del cliente queda atada a una
    // Activity DESTRUIDA, y showPresentation() corta en seco por isShowing==true:
    // nunca se re-ata, e isPresenting miente. Con "el cliente elige propina"
    // activado eso es el cajero esperando un toque sobre una pantalla negra.

    @Test
    fun `tras la recreacion, el attach de la nueva desmonta la Presentation heredada`() {
        val vieja = FakeHost("caja")
        val nueva = FakeHost("caja")
        assertTrue(
            shouldRebuildInheritedPresentation(
                previousHost = vieja,
                newHost = nueva,
                hasPresentation = true,
            ),
        )
    }

    @Test
    fun `un onStop-onStart de la MISMA instancia no reconstruye nada`() {
        // No hay nada heredado: su propio detach ya desmontó y volvió a montar.
        // Reconstruir aquí sería un parpadeo gratis en la cara del cliente.
        val host = FakeHost("caja")
        assertFalse(
            shouldRebuildInheritedPresentation(
                previousHost = host,
                newHost = host,
                hasPresentation = true,
            ),
        )
    }

    @Test
    fun `sin Presentation viva no hay nada que reconstruir`() {
        // Arranque en frío y modo invertido (ahí el cliente es una Activity
        // propia, que NO cuelga del anfitrión y no se toca).
        assertFalse(
            shouldRebuildInheritedPresentation(
                previousHost = null,
                newHost = FakeHost("caja"),
                hasPresentation = false,
            ),
        )
        assertFalse(
            shouldRebuildInheritedPresentation(
                previousHost = FakeHost("vieja"),
                newHost = FakeHost("nueva"),
                hasPresentation = false,
            ),
        )
    }

    @Test
    fun `la comparacion del attach tambien es por INSTANCIA, no por igualdad`() {
        // Mismo motivo que en el detach: dos Activity distintas pueden ser
        // `equals`. Si se comparara por igualdad, la Presentation huérfana
        // pasaría desapercibida y volveríamos al bug.
        val vieja = FakeHost("caja")
        val nueva = FakeHost("caja")
        assertTrue(vieja == nueva)
        assertTrue(shouldRebuildInheritedPresentation(vieja, nueva, hasPresentation = true))
    }
}
