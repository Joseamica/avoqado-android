package com.avoqado.pos.auth.data

import java.net.URLDecoder

// Entrar con Google en WINDOWS (hz:6bfcd3a1:D1): la vuelta que Google manda a 127.0.0.1 (la URL y el PKCE viven en
// app/…/auth/data/PkceDeGoogle.kt, compartidos con ANDROID). Sin red ni sockets aquí: sólo cadenas, para probarlas solas.

sealed interface Vuelta {
    data class Codigo(val code: String) : Vuelta
    data object Cancelado : Vuelta
    data object Ajena : Vuelta
}

/**
 * La primera línea de una petición que llegó a 127.0.0.1. Sólo cuenta `GET /?…` con NUESTRO `state`: con `code` es
 * [Vuelta.Codigo]; con `error` (`access_denied` u otro: Google ya no va a mandar código) es [Vuelta.Cancelado]. Todo lo
 * demás (el favicon, otro `state`, sin parámetros) es [Vuelta.Ajena] y el intento sigue esperando.
 */
fun leerVuelta(lineaDePeticion: String, stateEsperado: String): Vuelta {
    val partes = lineaDePeticion.split(' ')
    if (partes.size != 3 || partes[0] != "GET") return Vuelta.Ajena
    val ruta = partes[1]
    if (ruta.substringBefore('?') != "/" || '?' !in ruta) return Vuelta.Ajena
    val parametros = parametrosDe(ruta.substringAfter('?'))
    if (parametros["state"] != stateEsperado) return Vuelta.Ajena
    if (parametros["error"] != null) return Vuelta.Cancelado
    return parametros["code"]?.takeIf { it.isNotBlank() }?.let { Vuelta.Codigo(it) } ?: Vuelta.Ajena
}

/** `a=1&b=%2F` ⇒ {a=1, b=/}. Un par mal codificado se ignora (no truena la conexión). */
internal fun parametrosDe(query: String?): Map<String, String> = query.orEmpty().split('&')
    .filter { '=' in it }
    .mapNotNull { par ->
        runCatching {
            URLDecoder.decode(par.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(par.substringAfter('='), Charsets.UTF_8)
        }.getOrNull()
    }
    .toMap()
