package android.net

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** En el paquete de Android a propósito: usa el constructor de paquete (sonda e intervalo inyectables). */
class ConnectivityManagerSondaTest {
    @Test fun `arriba abajo arriba avisa en orden y un callback que truena no apaga al vigilante`() {
        val hayRed = AtomicBoolean(true)
        val cm = ConnectivityManager(BooleanSupplier { hayRed.get() }, 50L)
        val roto = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { throw IllegalStateException("callback roto a propósito") }
            override fun onLost(network: Network) { throw IllegalStateException("callback roto a propósito") }
        }
        val eventos = LinkedBlockingQueue<String>()
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), roto)
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { eventos.add("disponible") }
            override fun onLost(network: Network) { eventos.add("perdida") }
        })

        assertEquals("disponible", eventos.poll(2, SECONDS))
        hayRed.set(false)
        assertEquals("perdida", eventos.poll(2, SECONDS))
        hayRed.set(true)
        assertEquals("disponible", eventos.poll(2, SECONDS))
        assertNull(eventos.poll(300, MILLISECONDS), "no debe repetir avisos")
    }
}
