package com.avoqado.pos.onlineorders.data

import android.util.Log
import com.avoqado.pos.BuildConfig
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.onlineorders.domain.AccionResultado
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderItem
import com.avoqado.pos.onlineorders.domain.OnlineOrderModifier
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "🛎 PedidosEnLinea-Repo"

/** Un «no» del servidor ya leído: [status] HTTP, [codigo] del cuerpo y, si es un 409 de estado, el estado en que quedó el pedido. */
private class Rechazo(val status: Int, val codigo: String?, val mensaje: String, val estadoActual: OnlineOrderStatus?)

/**
 * Pedidos en línea (la tienda web del negocio) contra `/mobile/venues/:v/online-orders`. Mismo molde que `KDSRepository`.
 * `:orderId` de las rutas es `Order.id` (el `orderId` de la bandeja), nunca `onlineOrderId`.
 * Aceptar/rechazar/listo/entregado/cancelar/pausar son sólo en línea: sin red salen como [AccionResultado.SinConexion] y NO se encolan.
 */
@Singleton
class OnlineOrdersRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {

    /** Los tests de JVM apuntan a MockWebServer con `avoqado.test.baseUrl`. 🔴 Sólo en DEBUG: estas peticiones llevan el `Bearer`. */
    private fun ruta(venueId: String, resto: String): String {
        val base = (if (BuildConfig.DEBUG) System.getProperty("avoqado.test.baseUrl") else null) ?: ApiConstants.BASE_URL
        return "$base/mobile/venues/$venueId/$resto"
    }

    private fun sesion(): Pair<String, String>? {
        val venueId = secureStorage.venueId ?: return null
        val token = secureStorage.accessToken ?: return null
        return venueId to token
    }

    /**
     * El saldo se lee al tocar «Cobrar», con el cliente esperando: en la ruta del dinero se rinde rápido (5 s conectar, 8 s en
     * total; regla offline-first 2.5) y sin red se cobra el último saldo de la bandeja. Con los 30 s del cliente compartido el
     * cajero esperaba medio minuto con el WiFi «conectado sin datos». Comparte el pool de conexiones (`newBuilder`).
     */
    private val clienteDelCobro: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun sinSesion(): Result<Nothing> = Result.failure(IllegalStateException("Sin sesión o sin sucursal"))

    /** El cuerpo si fue 2xx; si no, [Rechazo] como excepción interna; sin red, la `IOException` tal cual. */
    private class RechazoException(val rechazo: Rechazo) : Exception(rechazo.mensaje)

    private suspend fun ejecutar(request: Request, cliente: OkHttpClient = client): Result<String> = try {
        val (code, body) = withContext(Dispatchers.IO) {
            cliente.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        }
        if (code in 200..299) Result.success(body) else Result.failure(RechazoException(leerRechazo(code, body)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun leerRechazo(code: Int, body: String): Rechazo {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val codigo = json?.textoONulo("code")
        val mensaje = json?.textoONulo("error") ?: json?.textoONulo("message") ?: "Error del servidor ($code)"
        val estado = if (code == 409 && codigo == "ORDER_STATE_CHANGED") OnlineOrderStatus.from(json.textoONulo("status")) else null
        return Rechazo(code, codigo, mensaje, estado)
    }

    private fun JSONObject.textoONulo(k: String): String? =
        if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

    private fun get(url: String, token: String, fondo: Boolean = false, bandeja: Boolean = false, rapida: Boolean = false): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $token").apply {
            if (fondo) header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
            if (bandeja) header(TRAY_HEADER, "1")
            // La ruta del dinero no espera un PIN dentro de su plazo corto: el 403 vuelve como respuesta, no como `IOException`.
            if (rapida) header(ForbiddenInterceptor.FAIL_FAST_HEADER, "1")
        }.get().build()

    private fun post(url: String, token: String, cuerpo: JSONObject? = null, deviceId: String? = null, errorLocal: Boolean = true): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $token").apply {
            // La pantalla explica su propio error; sólo «pausar» deja pasar el 403 `overridable` para pedir el PIN del gerente.
            if (errorLocal) header(ForbiddenInterceptor.LOCAL_ERROR_HEADER, "1")
            if (deviceId != null) header(DEVICE_HEADER, deviceId)
        }.post((cuerpo ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())).build()

    // MARK: - Bandeja

    /**
     * [status]: `pending` | `active` | `done` | `all`. [comoBandeja] = sólo la pantalla de bandeja visible (manda la cabecera que
     * mantiene abierta la tienda, R13); el vigilante pasa `false`. La cabecera de fondo va SIEMPRE: un 403 no abre el PIN cada 10 s.
     * `pending` y `active` salen de los conteos exactos que el servidor manda en toda respuesta.
     */
    suspend fun inbox(status: String, comoBandeja: Boolean, cursor: String? = null): Result<Inbox> {
        val (venueId, token) = sesion() ?: return sinSesion()
        val consulta = listOfNotNull(
            "status=" + URLEncoder.encode(status, "UTF-8"),
            cursor?.let { "cursor=" + URLEncoder.encode(it, "UTF-8") },
        ).joinToString("&")
        val request = get(ruta(venueId, "online-orders/inbox?$consulta"), token, fondo = true, bandeja = comoBandeja)
        return ejecutar(request).mapCatching { parseInbox(JSONObject(it).getJSONObject("data")) }
            .onFailure { Log.d(TAG, "Bandeja no leída: ${it.message}") }
    }

    private fun parseInbox(data: JSONObject): Inbox {
        val counts = data.optJSONObject("counts")
        val lista = data.optJSONArray("orders")
        return Inbox(
            serverTime = Instant.parse(data.getString("serverTime")),
            pending = counts?.optInt("pending") ?: 0,
            active = counts?.optInt("active") ?: 0,
            done = counts?.optInt("done") ?: 0,
            // Un renglón dañado se salta y se registra: no tumba la bandeja (el vigilante dejaría de sonar por un pedido real).
            orders = (0 until (lista?.length() ?: 0)).mapNotNull { i ->
                runCatching { parseOrder(lista!!.getJSONObject(i)) }
                    .onFailure { Log.w(TAG, "Pedido #$i de la bandeja ignorado por malformado: ${it.message}") }
                    .getOrNull()
            },
            // «Tienda apagada» = nunca encendida (`DISABLED`). Pausada, fuera de horario o sin aparato siguen siendo tienda viva.
            storeOff = data.optJSONObject("store")?.textoONulo("status") == "DISABLED",
            truncated = data.optBoolean("truncated", false),
            nextCursor = data.textoONulo("nextCursor"),
        )
    }

    private fun parseOrder(o: JSONObject): OnlineOrder {
        val items = o.optJSONArray("items")
        return OnlineOrder(
            orderId = o.getString("orderId"),
            onlineOrderId = o.getString("onlineOrderId"),
            orderNumber = o.optString("orderNumber"),
            status = OnlineOrderStatus.from(o.textoONulo("status")),
            paymentMode = o.optString("paymentMode"),
            paymentStatus = o.optString("paymentStatus"),
            customerName = o.textoONulo("customerName"),
            customerPhone = o.textoONulo("customerPhone"),
            note = o.textoONulo("note"),
            createdAt = Instant.parse(o.getString("createdAt")),
            acceptDeadlineAt = o.textoONulo("acceptDeadlineAt")?.let(Instant::parse),
            estimatedReadyAt = o.textoONulo("estimatedReadyAt")?.let(Instant::parse),
            totalCents = pesosACentavos(o, "total"),
            remainingCents = pesosACentavos(o, "remaining"),
            needsPrint = o.optBoolean("needsPrint", false),
            items = (0 until (items?.length() ?: 0)).map { parseItem(items!!.getJSONObject(it)) },
        )
    }

    private fun parseItem(i: JSONObject): OnlineOrderItem {
        val mods = i.optJSONArray("modifiers")
        return OnlineOrderItem(
            productId = i.textoONulo("productId"),
            categoryId = i.textoONulo("categoryId"),
            name = i.optString("name"),
            quantity = i.optInt("quantity", 1),
            note = i.textoONulo("note"),
            modifiers = (0 until (mods?.length() ?: 0)).map {
                val m = mods!!.getJSONObject(it)
                OnlineOrderModifier(m.optString("name"), m.optInt("quantity", 1))
            },
        )
    }

    /** Pesos del servidor (número JSON de 2 decimales) a centavos enteros, con decimal exacto: nunca `Double * 100`. */
    private fun pesosACentavos(o: JSONObject, campo: String): Long {
        if (o.isNull(campo) || !o.has(campo)) return 0L
        // `opt` devuelve Int/Long/Double/BigDecimal según el JSON; su `toString()` es el literal decimal más corto que lo representa.
        return BigDecimal(o.get(campo).toString()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    }

    // MARK: - Acciones (sólo en línea; no se encolan)

    suspend fun accept(orderId: String, minutes: Int): AccionResultado =
        accion("$orderId/accept", OnlineOrderStatus.ACCEPTED, JSONObject().put("estimatedMinutes", minutes))

    suspend fun reject(orderId: String, reason: String, note: String?): AccionResultado {
        val cuerpo = JSONObject().put("reason", reason)
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { cuerpo.put("note", it) }
        return accion("$orderId/reject", OnlineOrderStatus.REJECTED, cuerpo)
    }

    suspend fun ready(orderId: String): AccionResultado = accion("$orderId/ready", OnlineOrderStatus.READY)

    suspend fun delivered(orderId: String): AccionResultado = accion("$orderId/delivered", OnlineOrderStatus.COMPLETED)

    suspend fun cancel(orderId: String): AccionResultado = accion("$orderId/cancel", OnlineOrderStatus.CANCELLED_BY_STAFF)

    /** [minutes] 15/30/60, o `null` para reanudar. Sin permiso `online-orders:pause` el 403 `overridable` pide el PIN del gerente. */
    suspend fun pause(minutes: Int?): AccionResultado {
        val (venueId, token) = sesion() ?: return AccionResultado.Error("Sin sesión o sin sucursal")
        val cuerpo = JSONObject().put("minutes", minutes ?: JSONObject.NULL)
        return resultado(ejecutar(post(ruta(venueId, "online-orders/pause"), token, cuerpo, errorLocal = false)), quiso = null)
    }

    /** [quiso]: el estado al que llevaba la acción. Un 409 `ORDER_STATE_CHANGED` que ya está ahí cuenta como hecho (OP-D6). */
    private suspend fun accion(resto: String, quiso: OnlineOrderStatus, cuerpo: JSONObject? = null): AccionResultado {
        val (venueId, token) = sesion() ?: return AccionResultado.Error("Sin sesión o sin sucursal")
        return resultado(ejecutar(post(ruta(venueId, "online-orders/$resto"), token, cuerpo)), quiso)
    }

    private fun resultado(r: Result<String>, quiso: OnlineOrderStatus?): AccionResultado {
        r.onSuccess { return AccionResultado.Hecho }
        val e = r.exceptionOrNull()
        if (e is RechazoException) {
            val estado = e.rechazo.estadoActual
            return when {
                estado == null -> AccionResultado.Error(e.rechazo.mensaje)
                estado == quiso -> AccionResultado.Hecho
                else -> AccionResultado.YaNoDisponible(estado)
            }
        }
        // Sin red (IOException) o cualquier fallo de transporte: texto neutro en la pantalla, nunca error rojo.
        return if (e is IOException) AccionResultado.SinConexion else AccionResultado.Error(e?.message ?: "Error")
    }

    // MARK: - Impresión de la comanda (reclamo + sello; la llave es Order.id)

    /** `true` = este aparato ganó el reclamo y debe imprimir. Cualquier fallo es `false`: nunca se imprime sin reclamo. */
    suspend fun claimPrint(orderId: String, deviceId: String): Boolean = impresion("$orderId/print/claim", deviceId, "claimed")

    /** `true` = quedó sellada. `false` = ya estaba sellada o este aparato perdió el reclamo (NO significa «reimprime»). */
    suspend fun confirmPrint(orderId: String, deviceId: String): Boolean = impresion("$orderId/print/confirm", deviceId, "ok")

    private suspend fun impresion(resto: String, deviceId: String, campo: String): Boolean {
        val (venueId, token) = sesion() ?: return false
        return ejecutar(post(ruta(venueId, "online-orders/$resto"), token, deviceId = deviceId))
            .map { JSONObject(it).optJSONObject("data")?.optBoolean(campo, false) ?: false }
            .getOrDefault(false)
    }

    // MARK: - Saldo

    /** El saldo a cobrar lo dice el servidor (`preBillBalance.remainingBalanceCents`); la app nunca lo recalcula desde renglones. */
    suspend fun saldoCents(orderId: String): Result<Long> {
        val (venueId, token) = sesion() ?: return sinSesion()
        return ejecutar(get(ruta(venueId, "orders/$orderId"), token, rapida = true), clienteDelCobro).mapCatching {
            JSONObject(it).getJSONObject("order").getJSONObject("preBillBalance").getLong("remainingBalanceCents")
        }
    }

    private companion object {
        const val TRAY_HEADER = "x-online-orders-tray"
        const val DEVICE_HEADER = "x-device-id"
    }
}
