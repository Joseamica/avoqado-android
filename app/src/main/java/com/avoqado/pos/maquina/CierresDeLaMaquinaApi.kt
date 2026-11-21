package com.avoqado.pos.maquina

import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Lo que contestó el SERVIDOR, tal cual: quien llama decide qué significa. */
data class RespuestaDelServidor(val codigo: Int, val cuerpo: String)

/**
 * Las rutas de la máquina que usa la subida (C4, contrato FINAL del SERVIDOR en `mobile.routes.ts`). Con el cliente de la
 * app: la sesión y el `X-Device-ID` los ponen sus interceptores; aquí sólo la credencial de ESTA PC
 * (`X-Cash-Machine-Credential`, R5#3). Una falla de red llega como `IOException`. Ninguna abre el teclado de PIN de gerente:
 * un 403 lo explica la subida.
 */
@Singleton
class CierresDeLaMaquinaApi @Inject constructor(private val client: OkHttpClient) {

    /** Seam de pruebas: apuntar a un MockWebServer. */
    internal var baseUrl: String = ApiConstants.BASE_URL

    /** `POST …/cash-machine/sales` (R4-1): 200 evidencia, 202 incidencia, 409/422 con incidencia, 403 sin emparejar. */
    suspend fun subirCierre(venueId: String, credencial: String, cuerpo: JSONObject) =
        llamar(Request.Builder().post(cuerpo.toString().toRequestBody(JSON)), "/mobile/venues/$venueId/cash-machine/sales", credencial)

    /** `POST …/cash-machine/doubts` (R5#6): `{ status: 'ABIERTO' }` o la evidencia si el folio ya se resolvió. */
    suspend fun subirDuda(venueId: String, credencial: String, cuerpo: JSONObject) =
        llamar(Request.Builder().post(cuerpo.toString().toRequestBody(JSON)), "/mobile/venues/$venueId/cash-machine/doubts", credencial)

    /** `GET …/cash-machine/doubts/:referenceCC`: lo que la PC pregunta cada 60 s. */
    suspend fun consultarDuda(venueId: String, credencial: String, referenceCC: String) =
        llamar(Request.Builder().get(), "/mobile/venues/$venueId/cash-machine/doubts/$referenceCC", credencial)

    /** `GET …/shifts/business-current` (R3#8): `{ shiftId, startTime }` o `null`. */
    suspend fun turnoDelNegocio(venueId: String) =
        llamar(Request.Builder().get(), "/mobile/venues/$venueId/shifts/business-current", credencial = null, cliente = rapido)

    /**
     * `POST …/cash-machine/quote` (R4-2): el total del carrito ANTES del `paycash`, sin escribir nada. Se rinde rápido: sin
     * respuesta, el cobro sigue con el total local (sin red, R4-2), así que esperar 30 s sólo congelaría al cliente.
     */
    suspend fun cotizar(venueId: String, pedido: JSONObject) =
        llamar(Request.Builder().post(pedido.toString().toRequestBody(JSON)), "/mobile/venues/$venueId/cash-machine/quote", null, rapido)

    /**
     * `GET …/cash-machine/doubts?status=ABIERTO&kind=ESPERANDO` (R4-4, Codex código r2 H6): las dudas del NEGOCIO para la bandeja del
     * gerente, con SU sesión (`cash-machine:resolve`), por páginas: `{ data, total, nextCursor }`. [folio]: sólo ése (la evidencia al día
     * tras un 409 de versión, H7).
     */
    suspend fun listarDudas(venueId: String, cursor: String? = null, folio: String? = null): RespuestaDelServidor {
        val url = (baseUrl + "/mobile/venues/$venueId/cash-machine/doubts").toHttpUrl().newBuilder()
            .addQueryParameter("status", "ABIERTO").addQueryParameter("kind", "ESPERANDO").addQueryParameter("limit", DUDAS_POR_PAGINA.toString())
            .apply { cursor?.let { addQueryParameter("cursor", it) }; folio?.let { addQueryParameter("folio", it) } }
            .build()
        return llamar(Request.Builder().get(), url.toString().removePrefix(baseUrl), null)
    }

    /** `POST …/cash-machine/doubts/:referenceCC/resolve` (R4-4): el gerente, con SU sesión (`cash-machine:resolve`). */
    suspend fun resolverDuda(venueId: String, referenceCC: String, cuerpo: JSONObject) =
        llamar(Request.Builder().post(cuerpo.toString().toRequestBody(JSON)), "/mobile/venues/$venueId/cash-machine/doubts/$referenceCC/resolve", null)

    /** «Rendirse rápido» de la ruta del dinero (`offline-first-y-hub-lan.md` §2.5): 5 s para conectar, 10 s en total. */
    private val rapido by lazy { client.newBuilder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build() }

    private suspend fun llamar(
        pedido: Request.Builder,
        ruta: String,
        credencial: String?,
        cliente: OkHttpClient = client,
    ): RespuestaDelServidor =
        withContext(Dispatchers.IO) {
            pedido.url(baseUrl + ruta).header(ForbiddenInterceptor.LOCAL_ERROR_HEADER, "1")
            credencial?.let { pedido.header(CREDENCIAL, it) }
            cliente.newCall(pedido.build()).execute().use { RespuestaDelServidor(it.code, it.body?.string().orEmpty()) }
        }

    companion object {
        const val CREDENCIAL = "X-Cash-Machine-Credential"
        const val DUDAS_POR_PAGINA = 50
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
