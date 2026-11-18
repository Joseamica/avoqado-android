package com.avoqado.pos.pos.presentation.checkout

import com.avoqado.pos.pos.data.model.Product
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D7: la etiqueta de báscula con la captura de peso abierta (Codex D7 r1). */
class CapturaDePesoTest {
    private val queso = Product(id = "queso", name = "Queso Oaxaca", priceValue = 180.0, soldByWeight = true)
    private val jamon = Product(id = "jamon", name = "Jamón", priceValue = 220.0, soldByWeight = true)

    @Test fun `la etiqueta de esa misma pesada cierra la captura`() {
        val abierta = CapturaDePeso(queso, apertura = 1)
        assertTrue(etiquetaCierraLaCaptura(alLeer = abierta, ahora = abierta, productoDeLaEtiqueta = "queso"))
    }

    @Test fun `la de otro producto deja la captura abierta`() {
        val abierta = CapturaDePeso(queso, apertura = 1)
        assertFalse(etiquetaCierraLaCaptura(abierta, abierta, productoDeLaEtiqueta = "jamon"))
    }

    @Test fun `P1 una etiqueta tardia no cierra la captura siguiente del mismo producto`() {
        // r1 #2: la cajera cerró la primera captura y abrió otra del mismo queso (con su peso tecleado) mientras se leía.
        assertFalse(etiquetaCierraLaCaptura(CapturaDePeso(queso, 1), CapturaDePeso(queso, 2), "queso"))
    }

    @Test fun `sin captura al leer no se cierra nada`() {
        assertFalse(etiquetaCierraLaCaptura(alLeer = null, ahora = CapturaDePeso(jamon, 3), productoDeLaEtiqueta = "jamon"))
        assertFalse(etiquetaCierraLaCaptura(alLeer = CapturaDePeso(jamon, 3), ahora = null, productoDeLaEtiqueta = "jamon"))
    }

    @Test fun `cualquier codigo de 13 numeros hace esperar a Agregar`() {
        assertTrue(puedeSerEtiquetaDeBascula("2000123004358"))
        assertTrue("con los ajustes viejos de la pantalla también (r2 #1)", puedeSerEtiquetaDeBascula("7501000000012"))
        assertFalse("SKU", puedeSerEtiquetaDeBascula("PIZZAS-003"))
        assertFalse("12 números", puedeSerEtiquetaDeBascula("750100000001"))
    }
}
