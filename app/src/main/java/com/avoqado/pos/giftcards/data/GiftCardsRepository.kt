package com.avoqado.pos.giftcards.data

import com.avoqado.pos.BuildConfig
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class GiftCardsRepository @Inject constructor(private val client: OkHttpClient, private val secureStorage: SecureStorage) {
    private val tipoJson = "application/json; charset=utf-8".toMediaType()
    private val candadoDeAjustes = Any()
    private val turnosDeAjustes = mutableMapOf<String, Long>()
    private var venueObservado = secureStorage.venueId
    private fun cache(venue: String?): EstadoDeAjustes = venue?.let { secureStorage.leerAjustesDeTarjetas(it) }?.let {
        if (it == "null") EstadoDeAjustes.NoDisponible
        else runCatching { EstadoDeAjustes.Disponible(GiftCardsApi.parsearAjustes(it)) }.getOrNull()
    } ?: EstadoDeAjustes.Desconocido
    private val ajustes = MutableStateFlow(cache(venueObservado))
    val estadoDeAjustes = ajustes.asStateFlow()

    private fun baseSinLogs() = client.newBuilder().apply {
        interceptors().removeAll { it is HttpLoggingInterceptor }
        networkInterceptors().removeAll { it is HttpLoggingInterceptor }
    }
    private val clienteDeCaja by lazy {
        baseSinLogs().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }
    internal val clienteDeGerente by lazy {
        baseSinLogs().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }
    suspend fun refrescarAjustes(venueId: String): EstadoDeAjustes {
        val turno = synchronized(candadoDeAjustes) {
            if (venueObservado != venueId) { venueObservado = venueId; ajustes.value = cache(venueId) }
            ((turnosDeAjustes[venueId] ?: 0L) + 1L).also { turnosDeAjustes[venueId] = it }
        }
        val result = pedir(venueId, null, listOf("gift-cards", "settings"), parsear = GiftCardsApi::parsearAjustes)
        return synchronized(candadoDeAjustes) {
            // B no invalida la caché de A; sólo un refresco más nuevo de A puede hacerlo.
            if (turnosDeAjustes[venueId] != turno) return@synchronized ajustes.value
            val nuevo = when (result) {
                is Respuesta.Ok -> {
                    secureStorage.guardarAjustesDeTarjetas(venueId, GiftCardsApi.json.encodeToString(AjustesDeTarjetas.serializer(), result.valor))
                    EstadoDeAjustes.Disponible(result.valor)
                }
                is Respuesta.Rechazo -> when {
                    result.fallo.http == 404 && result.fallo.code == null -> {
                        secureStorage.guardarAjustesDeTarjetas(venueId, null); EstadoDeAjustes.NoDisponible
                    }
                    result.fallo.http == 403 && result.fallo.code == null -> EstadoDeAjustes.SinPermiso
                    else -> null
                }
                Respuesta.SinRespuesta -> null
            }
            if (venueObservado == venueId && nuevo != null) ajustes.value = nuevo
            ajustes.value
        }
    }
    suspend fun vender(venueId: String, cuerpo: String): Respuesta<VentaDeTarjetas> = pedir(venueId, cuerpo, listOf("gift-cards", "orders"), parsear = GiftCardsApi::parsearVenta)
    suspend fun consultar(venueId: String, prueba: PruebaDeTarjeta): Respuesta<ConsultaDeSaldo> = pedir(venueId, GiftCardsApi.cuerpoDeConsulta(prueba), listOf("gift-cards", "lookup"), parsear = GiftCardsApi::parsearConsulta)
    suspend fun cobrar(venueId: String, orderId: String, cuerpo: String): Respuesta<CobroConSaldo> = pedir(venueId, cuerpo, listOf("orders", orderId, "pay-gift-card"), parsear = GiftCardsApi::parsearCobro)
    suspend fun cobrarNuevaCuenta(venueId: String, cuerpo: String): Respuesta<CobroConSaldo> = pedir(venueId, cuerpo, listOf("gift-cards", "pay-new-order"), parsear = GiftCardsApi::parsearCobro)
    suspend fun recuperarCobro(venueId: String, llave: String): Respuesta<CobroConSaldo?> = pedir(venueId, null, listOf("gift-cards", "payments", llave), parsear = GiftCardsApi::parsearRecuperacion)
    suspend fun regresarSaldoYCancelar(venueId: String, orderId: String, operationKey: String): Respuesta<Unit> = pedir(venueId, operacion(operationKey), listOf("orders", orderId, "gift-balance-return-and-cancel")) { confirmar(it, "cancelled") }
    suspend fun cuenta(venueId: String, orderId: String): Respuesta<CuentaDeTarjetas> = pedir(venueId, null, listOf("gift-cards", "orders", orderId), parsear = GiftCardsApi::parsearCuenta)
    suspend fun reenviar(venueId: String, giftCardId: String, operationKey: String): Respuesta<ResultadoDeReenvio> = pedir(venueId, operacion(operationKey), listOf("gift-cards", giftCardId, "resend"), parsear = GiftCardsApi::parsearReenvio)
    suspend fun cambiarDestino(venueId: String, giftCardId: String, operationKey: String, canal: String, telefonoE164: String?, correo: String?): Respuesta<ResultadoDeReenvio> = pedir(venueId, buildJsonObject {
        put("operationKey", operationKey); put("deliveryChannel", canal)
        telefonoE164?.let { put("recipientPhone", it) }; correo?.let { put("recipientEmail", it) }
    }.toString(), listOf("gift-cards", giftCardId, "change-destination"), gerente = true, parsear = GiftCardsApi::parsearReenvio)
    suspend fun revelar(venueId: String, giftCardId: String, operationKey: String, motivo: String, deGerente: Boolean): Respuesta<CodigoRevelado> = pedir(venueId, buildJsonObject {
        put("operationKey", operationKey); put("motivo", motivo)
    }.toString(), listOf("gift-cards", giftCardId, if (deGerente) "reveal-manager" else "reveal"), gerente = deGerente, parsear = GiftCardsApi::parsearRevelado)
    suspend fun revelarCuenta(venueId: String, orderId: String, operationKey: String): Respuesta<List<CodigoRevelado>> = pedir(venueId, operacion(operationKey), listOf("gift-cards", "orders", orderId, "reveal")) {
        GiftCardsApi.json.parseToJsonElement(it).jsonObject.getValue("tarjetas").jsonArray.map { card -> GiftCardsApi.parsearRevelado(card.toString()) }
    }
    suspend fun pedirCodigo(venueId: String, telefonoE164: String): Respuesta<Unit> = pedir(venueId, buildJsonObject { put("phone", telefonoE164) }.toString(), listOf("gift-cards", "phone", "request")) { confirmar(it, "enviado") }
    suspend fun verificarCodigo(venueId: String, telefonoE164: String, codigo: String): Respuesta<VerificacionPorTelefono> = pedir(venueId, buildJsonObject {
        put("phone", telefonoE164); put("code", codigo)
    }.toString(), listOf("gift-cards", "phone", "verify"), parsear = GiftCardsApi::parsearTelefono)
    suspend fun cancelarVenta(venueId: String, orderId: String, operationKey: String): Respuesta<List<String>> = pedir(venueId, operacion(operationKey), listOf("gift-cards", "orders", orderId, "cancel-sale"), gerente = true) {
        GiftCardsApi.json.parseToJsonElement(it).jsonObject.getValue("giftCardIds").jsonArray.map { id -> id.jsonPrimitive.content }
    }
    suspend fun liberarCancelacion(venueId: String, orderId: String, operationKey: String): Respuesta<Unit> = pedir(venueId, buildJsonObject {
        put("operationKey", operationKey); put("confirmacionNoDevuelto", true)
    }.toString(), listOf("gift-cards", "orders", orderId, "release-cancel"), gerente = true) { confirmar(it, "released") }

    private fun operacion(llave: String) = buildJsonObject { put("operationKey", llave) }.toString()
    private fun confirmar(body: String, campo: String) { check(GiftCardsApi.json.parseToJsonElement(body).jsonObject.getValue(campo).jsonPrimitive.boolean) }
    private fun url(venueId: String, ruta: List<String>): HttpUrl {
        val base = if (BuildConfig.DEBUG) System.getProperty("avoqado.test.baseUrl") ?: ApiConstants.BASE_URL else ApiConstants.BASE_URL
        return base.trimEnd('/').toHttpUrl().newBuilder().addPathSegment("mobile").addPathSegment("venues").addPathSegment(venueId).apply { ruta.forEach { segmento -> addPathSegment(segmento) } }.build()
    }
    private suspend fun <T> pedir(venueId: String, cuerpo: String?, ruta: List<String>, gerente: Boolean = false, parsear: (String) -> T): Respuesta<T> {
        val request = Request.Builder().url(url(venueId, ruta)).apply {
            if (!gerente) header(ForbiddenInterceptor.LOCAL_ERROR_HEADER, "1")
            if (cuerpo != null) post(cuerpo.toRequestBody(tipoJson))
        }.build()
        val result = try { (if (gerente) clienteDeGerente else clienteDeCaja).newCall(request).respuesta() }
        catch (_: IOException) { return Respuesta.SinRespuesta }
        return when (result) {
            is Respuesta.Ok -> runCatching<Respuesta<T>> { Respuesta.Ok(parsear(result.valor.toString())) }.getOrDefault(Respuesta.SinRespuesta)
            is Respuesta.Rechazo -> result
            Respuesta.SinRespuesta -> Respuesta.SinRespuesta
        }
    }
    private suspend fun Call.respuesta(): Respuesta<JsonObject> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { GiftCardsApi.clasificar(it.code, it.header("Content-Type"), it.header("ngrok-error-code"), it.body?.string().orEmpty()) }
                    cont.resume(result)
                } catch (e: IOException) { cont.resumeWithException(e) }
            }
        })
    }
}
