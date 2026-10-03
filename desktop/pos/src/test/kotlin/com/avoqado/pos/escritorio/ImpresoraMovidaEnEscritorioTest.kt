package com.avoqado.pos.escritorio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.printing.data.BuscadorDeImpresoraEnLan
import com.avoqado.pos.printing.data.DireccionLocal
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El código REAL de la app que busca la ticketera que cambió de IP (`BuscadorDeImpresoraEnLan`), corriendo sobre los
 * sustitutos de escritorio. No se exige que haya direcciones: depende de la máquina.
 */
class ImpresoraMovidaEnEscritorioTest {
    @Test fun `direccionesPropias no truena y da solo IPv4 de WiFi o cable, sin loopback`() {
        val carpeta = Files.createTempDirectory("Avoqado POS impresora movida ñ")
        Bitacora.iniciar(carpeta)
        val contexto = ContextoDeEscritorio(carpeta)

        val propias = BuscadorDeImpresoraEnLan(contexto).direccionesPropias()

        // La app se traga cualquier excepción y devuelve vacío: que NO haya pasado por ahí.
        val bitacora = Files.list(carpeta.resolve("logs")).use { it.toList() }.singleOrNull()?.readText().orEmpty()
        assertFalse("No se pudo leer la dirección de la tablet" in bitacora, bitacora)
        for (d in propias) {
            val ip = InetAddress.getByName(d.ip)
            assertTrue(ip is Inet4Address && !ip.isLoopbackAddress, "$d")
            assertTrue(d.prefijo in 1..32, "$d")
        }
        // Lo mismo que dicen los sustitutos: sólo las redes con transporte WiFi o cable (las virtuales y VPN no).
        val cm = contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val esperadas = cm.allNetworks.filter { red ->
            cm.getNetworkCapabilities(red)!!.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
        }.flatMap { red -> cm.getLinkProperties(red)!!.linkAddresses.map { DireccionLocal(it.address.hostAddress, it.prefixLength) } }.distinct()
        assertEquals(esperadas, propias)
        println("Direcciones propias en esta máquina: $propias")
    }
}
