package android.net

import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las redes de la computadora una por interfaz, para la app que barre la red del local buscando la ticketera que cambió
 * de IP (`BuscadorDeImpresoraEnLan`). En el paquete de Android a propósito: la clasificación es de paquete.
 */
class RedesPorInterfazTest {
    private val wifi = setOf(NetworkCapabilities.TRANSPORT_WIFI)
    private val cable = setOf(NetworkCapabilities.TRANSPORT_ETHERNET)

    private fun clase(nombre: String, descripcion: String = "", virtual: Boolean = false, conMac: Boolean = true) =
        ConnectivityManager.transportesDe(nombre, descripcion, virtual, conMac)

    @Test fun `los nombres de conexion de Windows que pidio el brief`() {
        assertEquals(wifi, clase("Wi-Fi"))
        assertEquals(cable, clase("Ethernet"))
        for (virtual in listOf("vEthernet (WSL)", "Tailscale", "Bluetooth Network Connection", "VirtualBox Host-Only")) {
            assertEquals(emptySet(), clase(virtual), virtual)
        }
    }

    @Test fun `lo que Java ve en Windows - nombre corto y la descripcion del adaptador`() {
        assertEquals(wifi, clase("wlan0", "Intel(R) Wi-Fi 6 AX201 160MHz"))
        assertEquals(wifi, clase("wlan1", "Qualcomm Atheros QCA9377 Wireless Network Adapter"))
        assertEquals(wifi, clase("net5", "Realtek RTL8821CE 802.11ac PCIe Adapter"))
        assertEquals(cable, clase("eth3", "Intel(R) Ethernet Connection (7) I219-V"))
        assertEquals(cable, clase("eth1", "Realtek PCIe GbE Family Controller"))
        assertEquals(cable, clase("eth2", "Realtek USB GbE Family Controller"))   // un adaptador USB a cable es físico
        val virtuales = listOf(
            "eth4" to "Hyper-V Virtual Ethernet Adapter",
            "eth9" to "Hyper-V Virtual Ethernet Adapter #2",
            "net7" to "Tailscale Tunnel",
            "net2" to "Bluetooth Device (Personal Area Network)",
            "eth6" to "VirtualBox Host-Only Ethernet Adapter",
            "eth7" to "VMware Virtual Ethernet Adapter for VMnet8",
            "eth8" to "TAP-Windows Adapter V9",
            "net9" to "WireGuard Tunnel",
            "lo1" to "Npcap Loopback Adapter",
            "wlan2" to "Microsoft Wi-Fi Direct Virtual Adapter #2",   // dice Wi-Fi, pero es virtual: no se barre
        )
        for ((nombre, descripcion) in virtuales) assertEquals(emptySet(), clase(nombre, descripcion), descripcion)
    }

    @Test fun `una subinterfaz virtual o una tarjeta sin direccion fisica no es la red del local`() {
        assertEquals(emptySet(), clase("eth0:1", "Intel(R) Ethernet Connection I219-V", virtual = true))
        assertEquals(emptySet(), clase("utun3", "utun3", conMac = false))           // un túnel de VPN en la Mac
        assertEquals(emptySet(), clase("Wi-Fi", "Intel(R) Wi-Fi 6 AX201", conMac = false))
    }

    @Test fun `TAP y WSL cuentan como palabra, no como pedazo de otra`() {
        assertEquals(cable, clase("eth1", "Startap Gigabit Controller"))
        assertEquals(cable, clase("eth1", "Diswsl Networks Controller"))
    }

    @Test fun `en esta maquina - una red por interfaz activa con IPv4, sin loopback, con prefijo de 1 a 32`() {
        val cm = ConnectivityManager()
        val esperadas = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .associate { i ->
                i.name to i.interfaceAddresses
                    .filter { it.address is Inet4Address && !it.address.isLoopbackAddress && it.networkPrefixLength in 1..32 }
                    .map { "${it.address.hostAddress}/${it.networkPrefixLength}" }.toSet()
            }
            .filterValues { it.isNotEmpty() }
        val redes = cm.allNetworks
        assertEquals(esperadas.size, redes.size, "redes: ${redes.toList()} esperadas: $esperadas")
        val vistas = redes.associate { red ->
            assertNotNull(cm.getNetworkCapabilities(red), "$red")
            val propiedades = assertNotNull(cm.getLinkProperties(red), "$red")
            red.toString() to propiedades.linkAddresses.map { d ->
                assertTrue(d.address is Inet4Address && !d.address.isLoopbackAddress, "$d")
                assertTrue(d.prefixLength in 1..32, "$d")
                "${d.address.hostAddress}/${d.prefixLength}"
            }.toSet()
        }
        assertEquals(esperadas.values.flatten().toSet(), vistas.values.flatten().toSet())
        println("Redes de esta máquina: " + redes.joinToString { "$it ${vistas[it.toString()]} wifi=" +
            cm.getNetworkCapabilities(it)!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) + " cable=" +
            cm.getNetworkCapabilities(it)!!.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) })
    }

    /**
     * La red activa dice lo que es la tarjeta del LOCAL (la del Hub LAN): WiFi o cable, nunca las dos; sin tarjeta del local,
     * cable como antes. Sin depender de la máquina donde corre: se compara contra la misma elección (RedLocalMdns).
     */
    @Test fun `la red activa - internet, sin propiedades de enlace, y WiFi o cable segun la tarjeta del local`() {
        val cm = ConnectivityManager { true }
        val activa = cm.getNetworkCapabilities(Network.UNICA)!!
        val wifi = activa.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val cable = activa.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        assertTrue(wifi != cable, "WiFi o cable, uno solo")
        val lan = com.avoqado.escritorio.red.RedLocalMdns.interfazDelLocalDeEsteEquipo()
        val esperado = lan?.let { ConnectivityManager.capacidadesDeLaInterfaz(it.first).hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } ?: false
        assertEquals(esperado, wifi)
        assertTrue(activa.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertNull(cm.getNetworkCapabilities(null))
        assertNull(cm.getLinkProperties(Network.UNICA))
    }
}
