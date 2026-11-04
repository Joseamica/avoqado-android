package com.avoqado.pos.core.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * D22: un GET que se corta DE VERDAD (`call.cancel()`) si se cancela la corrutina. Con `execute()` dentro de
 * `withContext(IO)`, cancelar la corrutina dejaba la petición viva hasta su respuesta. Los errores salen igual que con
 * `execute()` (un IOException, o lo que lance un interceptor), y el cuerpo se lee completo antes de volver.
 *
 * @return el código HTTP y el cuerpo (vacío si no hubo).
 */
suspend fun OkHttpClient.leerCancelable(request: Request): Pair<Int, String> = coroutineScope {
    val call = newCall(request)
    val respuesta = async(Dispatchers.IO) {
        call.execute().use { response -> response.code to (response.body?.string() ?: "") }
    }
    try {
        respuesta.await()
    } catch (cancelada: CancellationException) {
        call.cancel()
        throw cancelada
    }
}
