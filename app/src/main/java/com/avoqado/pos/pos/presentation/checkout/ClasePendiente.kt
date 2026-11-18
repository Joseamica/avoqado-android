package com.avoqado.pos.pos.presentation.checkout

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.presentation.cart.CartViewModel

/**
 * D13 (18-nov): una clase de «Inscribir y cobrar» que no pudo entrar en su momento ya no entra sola. Aquí se enseña y la
 * cajera decide: «Agregar al carrito» (sólo a una venta nueva), «La agrego después» (vuelve a salir al terminar la venta
 * actual) o «Ya no la cobro aquí» (recepción la cobró o la canceló; la reserva no se toca).
 */
@Composable
fun ClasePendiente(cart: CartViewModel) {
    val enEspera by cart.clasesPendientes.collectAsState()
    val cartState by cart.cartState.collectAsState()
    val cliente by cart.selectedCustomer.collectAsState()
    val catalogo by cart.products.collectAsState()
    var vistas by remember { mutableStateOf(setOf<ClassCheckoutSeed.Seed>()) }
    // Al terminar la venta actual vuelve a avisar.
    LaunchedEffect(cartState.isEmpty) { if (cartState.isEmpty) vistas = emptySet() }
    val pendiente = remember(enEspera, vistas) { cart.clasesPendientesDeEsteNegocio().firstOrNull { it !in vistas } } ?: return
    var mensaje by remember(pendiente) { mutableStateOf<String?>(null) }
    val clase = catalogo.firstOrNull { it.id == pendiente.productId }?.name ?: "Clase"
    val lugares = if (pendiente.quantity > 1) " (${pendiente.quantity} lugares)" else ""
    val libre = cartState.isEmpty && cliente == null
    AvoqadoDialog(title = "Clase pendiente de cobrar", onDismiss = { vistas = vistas + pendiente }) {
        Text(listOfNotNull(clase, pendiente.persona).joinToString(" · ") + lugares)
        if (!libre) Text("Va en su propia venta: termina o guarda la venta actual para agregarla.")
        mensaje?.let { Text(it) }
        PrimaryButton(text = "Agregar al carrito", enabled = libre, onClick = { mensaje = cart.agregarClasePendiente(pendiente) })
        TextButton(onClick = { vistas = vistas + pendiente }) { Text("La agrego después") }
        TextButton(onClick = { cart.quitarClasePendiente(pendiente) }) { Text("Ya no la cobro aquí") }
    }
}
