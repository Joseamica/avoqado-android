package com.avoqado.escritorio.red

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * «Buscar impresoras» de red en Windows: muchas térmicas baratas no se anuncian por mDNS, así que se toca el 9100 (el
 * mismo puerto al que imprime PrinterService) de cada IP de las subredes privadas del equipo. Rescatado de
 * avoqado-desktop (NetworkScan + JvmPrinterPort.discoverNetworkPrinters): ≤ 254 hosts por subred, 64 a la vez, 350 ms.
 */
object BarridoDeImpresoras {
    const val PUERTO_CRUDO = 9100
    internal const val CONCURRENCIA = 64
    private const val ESPERA_MS = 350
    /** Tope de direcciones por barrido, sumando TODAS las interfaces (Ethernet + WiFi + VPN = varias /24). */
    const val TOPE_DE_HOSTS = 1_024

    @Volatile var puerto: Int = PUERTO_CRUDO
    @Volatile var hostsLocales: () -> List<String> = ::hostsDeEsteEquipo
    /** ¿Contesta `host` en `puerto`? Entra por aquí para que las pruebas controlen una sonda EN CURSO. */
    @Volatile var sonda: (host: String, puerto: Int) -> Boolean = ::tocar

    fun hostsDeLaSubred(ipv4: String, prefijo: Int): List<String> {
        if (prefijo !in 8..30) return emptyList()
        val ip = leerIpv4(ipv4) ?: return emptyList()
        val efectivo = maxOf(prefijo, 24)
        val mascara = (0xFFFFFFFFL shl (32 - efectivo)) and 0xFFFFFFFFL
        val red = ip and mascara
        val difusion = red or mascara.inv().and(0xFFFFFFFFL)
        val hosts = ArrayList<String>()
        var candidato = red + 1
        while (candidato < difusion) { if (candidato != ip) hosts += puntos(candidato); candidato++ }
        return hosts
    }

    fun iniciar(alEncontrar: (InetAddress) -> Unit, alTerminar: () -> Unit): () -> Unit {
        val vivo = AtomicBoolean(true)
        val hilos = Executors.newFixedThreadPool(CONCURRENCIA) { r -> Thread(r, "barrido-9100").apply { isDaemon = true } }
        val hosts = hostsAProbar(runCatching { hostsLocales() }.getOrDefault(emptyList()))
        val puertoAhora = puerto
        val sondaAhora = sonda
        for (host in hosts) {
            hilos.execute {
                if (!vivo.get()) return@execute
                val contesta = runCatching { sondaAhora(host, puertoAhora) }.getOrDefault(false)
                // Una sonda que ya iba (un connect no se corta con shutdownNow) puede contestar después de detener: no avisa.
                if (contesta && vivo.get()) runCatching { alEncontrar(InetAddress.getByName(host)) }
            }
        }
        hilos.shutdown()
        Thread({
            runCatching { hilos.awaitTermination(2, TimeUnit.MINUTES) }
            alTerminar()
        }, "barrido-9100-fin").apply { isDaemon = true }.start()
        Log.d("Escritorio", "Buscando impresoras de red: ${hosts.size} direcciones en el puerto $puertoAhora")
        return { vivo.set(false); hilos.shutdownNow() }
    }

    /** Sin repetidos y a lo mucho [TOPE_DE_HOSTS] en total; si recorta, lo dice en la bitácora. */
    fun hostsAProbar(hosts: List<String>): List<String> {
        val unicos = hosts.distinct()
        if (unicos.size > TOPE_DE_HOSTS) {
            Log.w("Escritorio", "Buscar impresoras de red: ${unicos.size} direcciones en las redes del equipo; se tocan sólo las primeras $TOPE_DE_HOSTS")
        }
        return unicos.take(TOPE_DE_HOSTS)
    }

    private fun tocar(host: String, puerto: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(host, puerto), ESPERA_MS) }; true }.getOrDefault(false)

    private fun hostsDeEsteEquipo(): List<String> = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
        .getOrDefault(emptyList())
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { runCatching { it.interfaceAddresses }.getOrDefault(emptyList()) }
        .mapNotNull { dir ->
            val ip = dir.address as? Inet4Address ?: return@mapNotNull null
            if (!ip.isSiteLocalAddress) return@mapNotNull null          // 10/8, 172.16/12, 192.168/16; Tailscale (100.64/10) no
            hostsDeLaSubred(ip.hostAddress, dir.networkPrefixLength.toInt())
        }
        .flatten()

    private fun leerIpv4(texto: String): Long? {
        val partes = texto.split(".")
        if (partes.size != 4) return null
        var valor = 0L
        for (p in partes) {
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return null
            val octeto = p.toInt(); if (octeto > 255) return null
            valor = (valor shl 8) or octeto.toLong()
        }
        return valor
    }

    private fun puntos(a: Long) = "${(a shr 24) and 0xFF}.${(a shr 16) and 0xFF}.${(a shr 8) and 0xFF}.${a and 0xFF}"
}
