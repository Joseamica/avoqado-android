package com.avoqado.pos.escritorio.teclado

import androidx.lifecycle.SavedStateHandle
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import com.avoqado.pos.escritorio.Inyector
import com.avoqado.pos.pos.data.LectorHidBus
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El otro extremo del cable: lo que la pistola emite en Windows llega a la pantalla de cobro por el MISMO bus que en
 * Android (`LectorHidBus` → `CartViewModel.codigosEscaneados` → `manejarCodigo`). Si el inyector de escritorio diera un
 * bus distinto al del carrito, el código se emitiría a nadie y la caja seguiría «sin leer» — el síntoma del 7-oct.
 */
class PistolaAlCobroTest {
    @Test fun `P1 el codigo emitido llega al carrito de la pantalla de cobro`() = runBlocking {
        val carpeta = Files.createTempDirectory("Avoqado POS pistola ñ")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        Escritorio.instalar(actividad, Inyector.crear(actividad))

        val carrito = Escritorio.crearViewModel(CartViewModel::class.java, SavedStateHandle())
        val bus = Escritorio.inyector.getInstance(LectorHidBus::class.java)

        val recibido = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { carrito.codigosEscaneados.first() } }
        emisorDelBus(bus)("9040586673")

        assertEquals("9040586673", recibido.await())
    }
}
