package com.avoqado.pos.core.data.network

import com.avoqado.pos.core.data.sync.FotosDeMesas
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * D5 · B2b, punto de paso ① (piloto `MESAS_SIN_RED`): toda escritura del cliente HTTP principal que mencione en su URL o en su
 * cuerpo una mesa vigilada ([FotosDeMesas]) borra su foto del disco ANTES de salir; si el disco falla, la petición no sale. No
 * cambia ninguna petición ni respuesta y no registra el cuerpo. Sin fotos ni lecturas vigiladas, no lee nada. Con el piloto
 * apagado nunca frena: una foto que no se pudo borrar queda muerta en memoria (`FotosDeMesas`).
 *
 * Excepciones: la ronda (`POST …/orders/{id}/items`) borra al VOLVER, con 2xx o 4xx (un 4xx descarta la fila aunque un intento
 * anterior quizá se aplicó); sin respuesta, con 409 o 5xx la ronda sigue en la cola y la foto se conserva. Y lo que sólo
 * reproduce o consulta: `/sync/intents` (la cola ya decide en ②), `/sync/recovery` y `/print-jobs/sync`.
 */
class PuertaDeLaFoto(private val fotos: FotosDeMesas) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val ruta = request.url.encodedPath
        if (request.method == "GET" || request.method == "HEAD" || EXCEPCIONES.any { ruta.endsWith(it) }) return chain.proceed(request)
        val ronda = request.method == "POST" && RONDA.matches(ruta)
        val escritura = fotos.empezarEscritura(diferida = ronda) { textoDe(request) }
        // B3: la ruta (nunca el cuerpo) de cada escritura que tocó una mesa vigilada.
        if (escritura.ids.isNotEmpty()) android.util.Log.d(TAG, "${if (ronda) "Ronda en vuelo sobre" else "Foto borrada por"} ${request.method} $ruta")
        val response = try { chain.proceed(request) } catch (e: Throwable) { fotos.terminarEscritura(escritura); throw e }
        val borrar = ronda && (response.code in 200..299 || (response.code in 400..499 && response.code != 409))
        // Lo que ya pasó no se convierte en falla de red: si el disco no deja borrar, la foto queda muerta y la respuesta sigue
        // tal cual (un 4xx es rechazo y la ronda no queda pendiente sumando).
        fotos.terminarEscritura(escritura, borrar)
        if (borrar && escritura.ids.isNotEmpty()) android.util.Log.d(TAG, "Foto borrada al volver la ronda (${response.code}) $ruta")
        return response
    }

    /** URL + cuerpo. Null = no se puede leer sin consumirlo o pasa de 1 MB: cuenta como que menciona todo. */
    private fun textoDe(request: Request): String? {
        val cuerpo = request.body ?: return request.url.toString()
        val largo = cuerpo.contentLength()
        if (cuerpo.isOneShot() || cuerpo.isDuplex() || largo !in 0..LIMITE) return null
        return request.url.toString() + "\n" + Buffer().also(cuerpo::writeTo).readUtf8()
    }

    private companion object {
        const val TAG = "📸 PuertaDeLaFoto"
        val RONDA = Regex(".*/orders/[^/]+/items/?")
        val EXCEPCIONES = listOf("/sync/intents", "/sync/recovery", "/print-jobs/sync")
        const val LIMITE = 1L * 1024 * 1024
    }
}
