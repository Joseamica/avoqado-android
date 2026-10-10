package com.avoqado.pos.pos.presentation.cart

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * D13 del plan 7 de IVA: al consumidor, sumar el IVA encima del precio mostrado va contra la LFPC (art. 7 Bis). Estática a
 * propósito: comprueba que nadie devuelva el botón ni una puerta para volver a prender el impuesto aparte.
 */
class AgregarImpuestoRetiradoTest {

    private fun fuente(ruta: String): String = File(raizDelRepo(), "app/src/main/java/com/avoqado/pos/$ruta").readText()

    @Test
    fun `P1 el carrito ya no ofrece Agregar impuesto`() {
        val panel = fuente("pos/presentation/cart/CartPanelView.kt")
        assertFalse(panel.contains("Agregar impuesto"))
        assertFalse(panel.contains("onApplyTaxPercent"))
    }

    @Test
    fun `P1 nadie puede volver a prender el impuesto aparte desde la app`() {
        assertFalse(fuente("pos/presentation/cart/CartViewModel.kt").contains("fun applyOrderTaxPercent"))
        val checkout = fuente("pos/presentation/checkout/CheckoutScreen.kt")
        assertFalse(checkout.contains("applyOrderTaxPercent"))
        // El carrito de teléfono (`IPhoneCartSheet`) tampoco recibe ni reenvía la acción.
        assertFalse(checkout.contains("onApplyTaxPercent"))
    }

    private fun raizDelRepo(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró la raíz del repo desde ${System.getProperty("user.dir")}")
    }
}
