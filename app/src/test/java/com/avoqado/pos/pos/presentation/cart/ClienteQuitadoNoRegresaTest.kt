package com.avoqado.pos.pos.presentation.cart

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Quitar cliente» del carrito (founder, 23-oct): lo que respondió TARDE no puede volver a poner al cliente quitado ni
 * enseñar lo de otro (Codex, cliente en cobrar r3 #1 y #2). Estática a propósito, como [AgregarImpuestoRetiradoTest]:
 * esas tres puertas viven en Composables y lo que se vigila es que nadie quite el candado.
 */
class ClienteQuitadoNoRegresaTest {

    private fun fuente(ruta: String): String = File(raizDelRepo(), "app/src/main/java/com/avoqado/pos/$ruta").readText()

    @Test
    fun `P1 la tarjeta escaneada sólo liga al cliente si nadie lo cambió mientras respondía`() {
        val checkout = fuente("pos/presentation/checkout/CheckoutScreen.kt")
        assertTrue(checkout.contains("val clienteAlEscanear = cartViewModel.selectedCustomer.value?.id"))
        assertTrue(
            checkout.contains(
                "is ScannedBarcodeResult.CustomerCardFound -> if (cartViewModel.selectedCustomer.value?.id == clienteAlEscanear)",
            ),
        )
    }

    @Test
    fun `P1 el alta de cliente que responde con el formulario cerrado no lo liga a la venta`() {
        val checkout = fuente("pos/presentation/checkout/CheckoutScreen.kt")
        assertTrue(
            checkout.contains(
                "if (!showCustomersSheet || !showCreateCustomer || aperturaDeEsteAlta != aperturaDelAlta) return@creado",
            ),
        )
        // Cada apertura del alta sube el número (Codex r4 #2: cerrar y abrir otro formulario no revive la respuesta vieja).
        assertTrue(checkout.contains("aperturaDelAlta += 1"))
    }

    @Test
    fun `P1 los créditos del carrito son de UN cliente`() {
        val tarjeta = fuente("pos/presentation/cart/CartCreditsCard.kt")
        assertTrue(tarjeta.contains("hiltViewModel(key = \"creditos-del-carrito-\$customerId\")"))
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
