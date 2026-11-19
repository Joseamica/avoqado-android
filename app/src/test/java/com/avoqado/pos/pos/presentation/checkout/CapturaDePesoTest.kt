package com.avoqado.pos.pos.presentation.checkout

import com.avoqado.pos.pos.data.model.Product
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D7: la etiqueta de báscula con la captura de peso abierta (Codex D7 r1). */
class CapturaDePesoTest {
    private val queso = Product(id = "queso", name = "Queso Oaxaca", priceValue = 180.0, soldByWeight = true)
    private val jamon = Product(id = "jamon", name = "Jamón", priceValue = 220.0, soldByWeight = true)

    @Test fun `la etiqueta del producto que se esta pesando avisa en su captura`() {
        val abierta = CapturaDePeso(queso, apertura = 1)
        assertTrue(etiquetaEsDeLaCaptura(alLeer = abierta, ahora = abierta, productoDeLaEtiqueta = "queso"))
    }

    @Test fun `la de otro producto no avisa`() {
        val abierta = CapturaDePeso(queso, apertura = 1)
        assertFalse(etiquetaEsDeLaCaptura(abierta, abierta, productoDeLaEtiqueta = "jamon"))
    }

    @Test fun `P1 una etiqueta tardia no avisa en la captura siguiente del mismo producto`() {
        // r1 #2: la cajera cerró la primera captura y abrió otra del mismo queso (con su peso tecleado) mientras se leía.
        assertFalse(etiquetaEsDeLaCaptura(CapturaDePeso(queso, 1), CapturaDePeso(queso, 2), "queso"))
    }

    @Test fun `sin captura al leer no avisa nada`() {
        assertFalse(etiquetaEsDeLaCaptura(alLeer = null, ahora = CapturaDePeso(jamon, 3), productoDeLaEtiqueta = "jamon"))
        assertFalse(etiquetaEsDeLaCaptura(alLeer = CapturaDePeso(jamon, 3), ahora = null, productoDeLaEtiqueta = "jamon"))
    }

    @Test fun `el aviso dice que entro y cuanto peso`() {
        org.junit.Assert.assertEquals("Ya entró Queso Oaxaca 0.435 kg por su etiqueta", avisoDeEtiqueta("Queso Oaxaca", 0.435))
    }
}
