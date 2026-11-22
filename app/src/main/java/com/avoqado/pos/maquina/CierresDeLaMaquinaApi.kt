package com.avoqado.pos.maquina

import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lo que contestó el SERVIDOR, tal cual: quien llama decide qué significa. Codex código POS r11 (F6): [deAvoqado] = la contestó Avoqado (trae
 * su `X-Correlation-ID`, que el SERVIDOR pone en su primer middleware a toda respuesta: `src/config/middleware.ts`, `requestLogger.ts`), y no
 * un proxy o la puerta de entrada de Render o Cloudflare. Sin saberlo, false: nada que no se sepa de Avoqado saca una fila de la cola.
 */
data class RespuestaDelServidor(val codigo: Int, val cuerpo: String, val deAvoqado: Boolean = false)

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
        llamar(Request.Builder().post(deUnSoloEnvio(cuerpo)), "/mobile/venues/$venueId/cash-machine/sales", credencial, subidas)

    /** `POST …/cash-machine/doubts` (R5#6): `{ status: 'ABIERTO' }` o la evidencia si el folio ya se resolvió. */
    suspend fun subirDuda(venueId: String, credencial: String, cuerpo: JSONObject) =
        llamar(Request.Builder().post(deUnSoloEnvio(cuerpo)), "/mobile/venues/$venueId/cash-machine/doubts", credencial, subidas)

    /** `GET …/cash-machine/doubts/:referenceCC`: lo que la PC pregunta cada 60 s hasta que el panel la resuelve (recorte R2). */
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
     * 🔴 Codex código POS r10 (F5 · transporte): ningún cuerpo de la máquina se reenvía sin releer su fila. Un cierre o una duda llevan lo que
     * contó la máquina en el instante en que se leyó su fila; si el cliente de red lo repitiera solo (el reenvío del autenticador tras un 401
     * con la sesión vencida, un 408, un 503 con `Retry-After: 0`, una conexión que se cae a medio envío), la corrección que llegó mientras
     * tanto se perdería: el SERVIDOR registraría $0 de adeudo cuando son $20. Dos piezas, verificadas en `RetryAndFollowUpInterceptor` de
     * OkHttp 4.12:
     * - el cuerpo es de UN solo envío (`isOneShot`): OkHttp no sigue un 401 (el autenticador renueva la sesión igual, pero su reenvío se
     *   descarta), un 503, un 421 ni una redirección que conserva el cuerpo, ni reintenta si la conexión se cayó con el envío empezado;
     * - y sin reintento de conexión (`retryOnConnectionFailure(false)`): tampoco repite un 408 ni prueba otra ruta con el cuerpo ya armado.
     * La respuesta regresa a la subida (401 y 408/503 = reintentar, una falla de red = sin red), que relee la fila en la pasada siguiente.
     */
    private val subidas by lazy { client.newBuilder().retryOnConnectionFailure(false).build() }

    private fun deUnSoloEnvio(cuerpo: JSONObject): RequestBody {
        val bytes = cuerpo.toString().toRequestBody(JSON)
        return object : RequestBody() {
            override fun contentType() = bytes.contentType()
            override fun contentLength() = bytes.contentLength()
            override fun writeTo(sink: okio.BufferedSink) = bytes.writeTo(sink)
            override fun isOneShot() = true
        }
    }

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
            cliente.newCall(pedido.build()).execute().use {
                RespuestaDelServidor(it.code, it.body?.string().orEmpty(), deAvoqado = it.header(CORRELACION) != null)
            }
        }

    companion object {
        const val CREDENCIAL = "X-Cash-Machine-Credential"
        /** Codex código POS r11 (F6): el encabezado con que el SERVIDOR firma TODA respuesta suya (`requestLoggerMiddleware`). */
        const val CORRELACION = "X-Correlation-ID"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
