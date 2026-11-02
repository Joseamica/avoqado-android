package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.transactions.PantallaConAlta
import com.avoqado.pos.transactions.clientesConAltaEnVuelo
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * «Quitar cliente» del carrito (founder, 23-oct): lo que respondió TARDE no puede volver a poner al cliente quitado ni
 * enseñar lo de otro (Codex, cliente en cobrar r3 #1 y #2). Estática a propósito, como [AgregarImpuestoRetiradoTest]:
 * esas tres puertas viven en Composables y lo que se vigila es que nadie quite el candado. El alta, además, se prueba
 * por su comportamiento con el estado del carrito (`PantallaConAlta`, saldo a favor r5-r6).
 */
class ClienteQuitadoNoRegresaTest {
    @get:Rule val main = MainDispatcherRule()

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
        // Codex apps r5: el desenlace del alta es ESTADO con su número. El carrito guarda el número del alta que pidió SU
        // formulario con la misma persistencia que su bandera, lo borra al abrir otro formulario, y el formulario sólo aplica el
        // desenlace con ese número (`desenlaceDelFormulario`).
        val checkout = fuente("pos/presentation/checkout/CheckoutScreen.kt")
        assertTrue(checkout.contains("var showCreateCustomer by remember { mutableStateOf(false) }"))
        assertTrue(checkout.contains("var altaDelFormulario by remember { mutableStateOf<Long?>(null) }"))
        assertTrue(checkout.contains("alta = altaDelFormulario,") && checkout.contains("onAlta = { altaDelFormulario = it },"))
        assertTrue(
            "abrir el alta borra el número del formulario anterior",
            Regex("""createCustomerSearchText = searchText\s+altaDelFormulario = null\s+showCreateCustomer = true""").containsMatchIn(checkout),
        )
        assertTrue(fuente("customers/presentation/CreateCustomerView.kt").contains("desenlaceDelFormulario(terminada, alta)"))

        // Y el comportamiento: Ana responde con el formulario ya cerrado (o con otro formulario abierto) y no se liga a la venta.
        val llegaAna = CompletableDeferred<Unit>()
        val ligados = mutableListOf<String>()
        val carrito = PantallaConAlta(clientesConAltaEnVuelo(llegaAna, "ana", "Ana"), sobreviveAlGiro = false) {
            ligados += it.id; cerrarFormulario()
        }
        carrito.abrirFormulario(); carrito.guardar("Ana")
        carrito.cerrarFormulario()
        llegaAna.complete(Unit)
        carrito.componer(); carrito.abrirFormulario(); carrito.componer()
        assertEquals(emptyList<String>(), ligados)
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
