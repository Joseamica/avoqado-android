package com.avoqado.escritorio.red

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/*
 * Diagnóstico del Hub LAN (PruebaDeRedLocal.bat): ¿esta PC encuentra a las demás del local y le llegan por TCP? Anuncia un
 * servicio de PRUEBA (nunca el real `_avoqado-pos._tcp`), lista lo que encuentra y se conecta a cada uno. Correrlo a la vez en
 * dos equipos de la misma red: cada uno tiene que ver al otro y decir «contestó».
 */

private const val TIPO_DE_PRUEBA = "_avoqado-prueba._tcp"

/**
 * `PruebaDeRedLocalKt [segundos] [archivo]`: con [archivo], además de la consola, lo deja ahí (el .bat lo corre con
 * javaw.exe —el MISMO programa que la app, para el firewall son reglas distintas— y lo abre en el Bloc de notas).
 */
fun main(args: Array<String>) {
    val segundos = args.firstOrNull()?.toLongOrNull() ?: 30L
    val archivo = args.getOrNull(1)?.let(::File)?.also { runCatching { it.writeText("") } }
    fun decir(texto: String) {
        println(texto)
        archivo?.let { runCatching { it.appendText(texto + "\r\n") } }
    }
    val lan = RedLocalMdns.interfazDelLocalDeEsteEquipo()
    decir("Tarjeta del local: " + (lan?.let { "${it.second.hostAddress} (${it.first.displayName})" } ?: "NINGUNA (sin WiFi ni cable con dirección privada)"))
    if (lan == null) exitProcess(1)

    val yo = "Prueba-" + runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("equipo").take(20)
    val servidor = ServerSocket(0)
    Thread {
        while (true) {
            val c = runCatching { servidor.accept() }.getOrNull() ?: break
            runCatching { c.use { it.getOutputStream().write("hola de $yo\n".toByteArray()) } }
        }
    }.apply { isDaemon = true; start() }

    val red = RedLocalMdns.compartida
    val propio = ConcurrentHashMap.newKeySet<String>()
    red.anunciar(TIPO_DE_PRUEBA, yo, servidor.localPort, mapOf("quien" to yo),
        { final -> propio += final; decir("Anunciado como «$final» en el puerto ${servidor.localPort}") },
        { decir("NO se pudo anunciar: ${it.message}") })
    red.buscar(TIPO_DE_PRUEBA,
        { nombre ->
            if (nombre in propio || nombre == yo) return@buscar
            decir("Encontré: $nombre")
            red.resolver(TIPO_DE_PRUEBA, nombre,
                { r ->
                    val respuesta = runCatching {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(r.host, r.puerto), 2_000)
                            s.soTimeout = 2_000
                            BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                        }
                    }
                    decir("  $nombre está en ${r.host.hostAddress}:${r.puerto} txt=${r.txt} → " +
                        respuesta.fold({ "contestó: «$it»" }, { "NO contestó por TCP (¿firewall?): ${it.message}" }))
                },
                { decir("  $nombre: no se pudo resolver") })
        },
        { decir("Se fue: $it") })
    Thread.sleep(segundos * 1_000)
    red.cerrar()
    Thread.sleep(500)
    decir("Fin de la prueba.")
    exitProcess(0)   // jmDNS deja hilos que no son daemon: sin esto la ventana del .bat podría no cerrarse
}
