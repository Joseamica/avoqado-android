package com.avoqado.pos.onlineorders.data

import android.util.Log
import com.avoqado.pos.BuildConfig
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.onlineorders.domain.AccionResultado
import com.avoqado.pos.onlineorders.domain.BeneficioDelFolio
import com.avoqado.pos.onlineorders.domain.ClienteDelFolio
import com.avoqado.pos.onlineorders.domain.ConteosPorPagar
import com.avoqado.pos.onlineorders.domain.CursoresPorPagar
import com.avoqado.pos.onlineorders.domain.HuellaDeFolio
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.ListaPorCobrar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderItem
import com.avoqado.pos.onlineorders.domain.OnlineOrderModifier
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.domain.PedidoPorCobrar
import com.avoqado.pos.onlineorders.domain.SaldoDelPedido
import com.avoqado.pos.onlineorders.domain.PrecioDeRenglon
import com.avoqado.pos.onlineorders.presentation.ocultosQueSiguen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val cache: PayloadCache,
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

    /**
     * R103: como [ejecutar], pero la llamada se CANCELA de verdad si la corrutina se cancela (`execute()` dentro de `withContext` seguiría
     * bloqueado hasta su plazo, con el candado de la página 1 tomado). Lo que llega después de cancelar se ignora.
     */
    private suspend fun ejecutarCancelable(request: Request, cliente: OkHttpClient = client): Result<String> = try {
        val (code, body) = kotlinx.coroutines.suspendCancellableCoroutine<Pair<Int, String>> { cont ->
            val call = cliente.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val leida = runCatching { response.use { it.code to it.body?.string().orEmpty() } }
                    if (cont.isActive) cont.resumeWith(leida)
                }
            })
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

    private fun get(url: String, token: String, fondo: Boolean = false, bandeja: Boolean = false, rapida: Boolean = false, latido: Boolean = false): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $token").apply {
            if (fondo) header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
            if (bandeja) header(TRAY_HEADER, "1")
            if (latido) header(TO_PAY_HEADER, "1")
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
            cardStatus = o.textoONulo("cardStatus"),
            cardChargeStatus = o.optJSONObject("card")?.textoONulo("status"),
            rejectReason = o.textoONulo("rejectReason"),
            requiresAcceptance = if (o.isNull("requiresAcceptance")) null else o.optBoolean("requiresAcceptance"),
            channel = o.textoONulo("channel"),
            // R67, opcional: una fecha rota no tumba el folio (se queda sin hora, como con un SERVIDOR viejo).
            paidAt = o.textoONulo("paidAt")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            // Parte 2 (S14), opcionales: un dato roto se salta, nunca tumba el folio (como con un SERVIDOR viejo).
            cliente = o.optJSONObject("cliente")?.let { c ->
                val id = c.textoONulo("id")
                val nombre = c.textoONulo("nombre")
                if (id != null && nombre != null) ClienteDelFolio(id, nombre) else null
            },
            beneficios = o.optJSONArray("beneficios")?.let { lista ->
                (0 until lista.length()).mapNotNull { i ->
                    runCatching {
                        val b = lista.getJSONObject(i)
                        BeneficioDelFolio(b.textoONulo("nombre")!!, pesosACentavos(b, "monto"))
                    }.getOrNull()
                }
            }.orEmpty(),
            beneficiosTotal = o.optInt("beneficiosTotal", 0),
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
                e.rechazo.status == 409 && e.rechazo.codigo == "ONLINE_ORDER_CONFIRMING_PAYMENT" -> AccionResultado.ConfirmandoPago(e.rechazo.mensaje)
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

    // MARK: - Rappi 2b: «Rappi · por cobrar»

    /**
     * Pedidos de plataforma con efectivo por cobrar (`GET online-orders/delivery-cash`, tope y cursor del servidor). La página 1 se
     * guarda en el aparato tal cual llegó: sin red o con la app recién abierta, la sección sale de ahí ([porCobrarGuardado]). Un
     * SERVIDOR sin la ruta (404) o sin permiso (403) ⇒ `disponible = false`, sin error: la sección simplemente no sale.
     */
    suspend fun porCobrar(cursor: String? = null): Result<ListaPorCobrar> {
        val (venueId, token) = sesion() ?: return sinSesion()
        val consulta = cursor?.let { "?cursor=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val leido = ejecutar(get(ruta(venueId, "online-orders/delivery-cash$consulta"), token, fondo = true))
        val rechazo = (leido.exceptionOrNull() as? RechazoException)?.rechazo
        if (rechazo != null && rechazo.status in setOf(403, 404)) {
            return Result.success(ListaPorCobrar(emptyList(), 0, false, null, disponible = false))
        }
        return leido.mapCatching { cuerpo ->
            parsePorCobrar(cuerpo).also { if (cursor == null) cache.save(CACHE_POR_COBRAR, venueId, cuerpo) }
        }.onFailure { Log.d(TAG, "«Por cobrar» no leído: ${it.message}") }
    }

    /** La última página 1 que llegó del servidor, guardada en el aparato; `null` si nunca se leyó. */
    suspend fun porCobrarGuardado(): ListaPorCobrar? {
        val venueId = secureStorage.venueId ?: return null
        return cache.load(CACHE_POR_COBRAR, venueId)?.let { runCatching { parsePorCobrar(it.json) }.getOrNull() }
    }

    private fun parsePorCobrar(cuerpo: String): ListaPorCobrar {
        val data = JSONObject(cuerpo).getJSONObject("data")
        val lista = data.optJSONArray("orders")
        return ListaPorCobrar(
            // Un renglón dañado se salta: no tumba la lista.
            pedidos = (0 until (lista?.length() ?: 0)).mapNotNull { i ->
                runCatching {
                    val o = lista!!.getJSONObject(i)
                    PedidoPorCobrar(
                        orderId = o.getString("orderId"),
                        orderNumber = o.getString("orderNumber"),
                        provider = o.getString("provider"),
                        customerName = o.textoONulo("customerName"),
                        createdAt = Instant.parse(o.getString("createdAt")),
                        remainingBalanceCents = o.getLong("remainingBalanceCents"),
                        saleCents = o.getLong("saleCents"),
                        tipCents = o.getLong("tipCents"),
                        thirdPartyCents = o.getLong("thirdPartyCents"),
                        thirdPartyLabel = o.getString("thirdPartyLabel"),
                    )
                }.onFailure { Log.w(TAG, "Pedido por cobrar #$i ignorado por malformado: ${it.message}") }.getOrNull()
            },
            total = data.optInt("total", 0),
            truncated = data.optBoolean("truncated", false),
            nextCursor = data.textoONulo("nextCursor"),
        )
    }

    // MARK: - Tienda híbrida: «Por pagar» / «Por entregar»

    /**
     * `GET online-orders/to-pay` (+ `?section=toPay|toDeliver&cursor=…` para «Cargar más»). [conLatido] manda la cabecera que avisa
     * al servidor que ESTE aparato cobra folios (sólo pantallas que cobran; el vigilante y el panel no). La página 1 sin sección se
     * guarda en el aparato ([porPagarGuardado]). 403/404 ⇒ `disponible = false`, sin error.
     */
    suspend fun porPagar(conLatido: Boolean, seccion: String? = null, cursor: String? = null): Result<ListaPorPagar> =
        // R103: la página 1 se lee ENTERA bajo [lecturaDePagina1]; «Cargar más» (sección y cursor) no la espera.
        if (seccion != null || cursor != null) {
            leerPorPagar(conLatido, seccion, cursor)
        } else {
            lecturaDePagina1.withLock { leerPorPagar(conLatido, null, null) }
        }

    /**
     * R104/R105 (Codex r6 #2, r7 #1): el plazo TOTAL de cada lectura de «Por pagar», página 1 y extras (`callTimeout`: conectar,
     * esperar y leer TODO el cuerpo). El plazo de lectura del cliente vale por cada lectura del socket: una respuesta a gotas nunca lo
     * vencía. Al vencer, OkHttp corta la llamada y falla como sin red: la página 1 suelta [lecturaDePagina1] y nada se aplica. En
     * tiempo real (no el virtual de las corrutinas de prueba). Las pruebas lo acortan.
     */
    internal var plazoTotalDeFoliosMs: Long = 20_000L

    /**
     * 🔴 R103 (Codex r5): el número de R102 dice cuándo SALIÓ la petición, no qué tan nueva es la foto del servidor; con dos lecturas a
     * la vez el servidor puede atender la segunda primero. Así que la página 1 de «Por pagar» se lee ENTERA bajo este candado (red,
     * leer y guardar la copia): la bandeja y el vigilante, que comparten este repositorio único, nunca se enciman, y cada lectura sale
     * hacia el servidor después de que la anterior ya llegó. Orden fijo de candados: éste y luego [copiaDeFolios]; nada toma la copia y
     * luego éste. Una lectura cancelada lo suelta al instante ([ejecutarCancelable]) y nunca se aplica.
     */
    private val lecturaDePagina1 = Mutex()

    private suspend fun leerPorPagar(conLatido: Boolean, seccion: String?, cursor: String?): Result<ListaPorPagar> {
        val (venueId, token) = sesion() ?: return sinSesion()
        // R102 (segunda red): el número de la lectura se toma ANTES de la red: lo que cuenta es cuándo se PIDIÓ, no cuándo llegó.
        val numero = secuenciaDeLecturas.incrementAndGet()
        val consulta = listOfNotNull(
            seccion?.let { "section=" + URLEncoder.encode(it, "UTF-8") },
            cursor?.let { "cursor=" + URLEncoder.encode(it, "UTF-8") },
        ).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        val cliente = client.newBuilder().callTimeout(plazoTotalDeFoliosMs, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val leido = ejecutarCancelable(get(ruta(venueId, "online-orders/to-pay$consulta"), token, fondo = true, latido = conLatido), cliente)
        val rechazo = (leido.exceptionOrNull() as? RechazoException)?.rechazo
        if (rechazo != null && rechazo.status in setOf(403, 404)) {
            val vieja = seccion == null && cursor == null && copiaDeFolios.withLock { numero <= (secuenciaDeLaCopia[venueId] ?: 0L) }
            return Result.success(parsePorPagar("{\"data\":{}}").copy(disponible = false, showTile = false, masViejaQueLaCopia = vieja, numero = numero))
        }
        val lista = leido.mapCatching { parsePorPagar(it) }.onFailure { Log.d(TAG, "«Por pagar» no leído: ${it.message}") }
        if (seccion != null || cursor != null) return lista
        return lista.map {
            when (guardarCopia(venueId, leido.getOrThrow(), it, numero)) {
                Guardado.GUARDADA -> it.copy(numero = numero)
                Guardado.FALLO -> it.copy(copiaGuardada = false, numero = numero)
                Guardado.VIEJA -> it.copy(copiaGuardada = false, masViejaQueLaCopia = true, numero = numero)
            }
        }
    }

    /**
     * R102: la bandeja y el vigilante leen `to-pay` por separado y la red puede entregar al revés. Cada lectura de la página 1 lleva su
     * número ([secuenciaDeLecturas], tomado antes de la red, en este repositorio ÚNICO que comparten) y la copia recuerda el de la
     * lectura que la dejó ([secuenciaDeLaCopia]). En memoria basta: tras reiniciar, toda lectura nueva se pidió después de la copia.
     */
    private val secuenciaDeLecturas = java.util.concurrent.atomic.AtomicLong()
    private val secuenciaDeLaCopia = mutableMapOf<String, Long>()

    private enum class Guardado { GUARDADA, VIEJA, FALLO }

    /** Una escritura a la vez sobre la copia de «Por pagar»: la de cada lectura y la de lo escondido no se pisan. */
    private val copiaDeFolios = Mutex()

    /**
     * R101: la página 1 queda como la copia guardada, junto con lo escondido que todavía aplica ([ocultosQueSiguen]), en UNA sola
     * escritura. R102: sólo si se PIDIÓ después de la lectura que dejó la copia de ahora; si no, se descarta (la copia sólo avanza).
     */
    private suspend fun guardarCopia(venueId: String, cuerpo: String, lista: ListaPorPagar, numero: Long): Guardado = copiaDeFolios.withLock {
        if (numero <= (secuenciaDeLaCopia[venueId] ?: 0L)) {
            Log.d(TAG, "«Por pagar»: respuesta $numero más vieja que la copia (${secuenciaDeLaCopia[venueId]}): se descarta")
            return@withLock Guardado.VIEJA
        }
        try {
            val previos = cache.load(CACHE_POR_PAGAR, venueId)?.let { ocultosDe(JSONObject(it.json)) }.orEmpty()
            // W-1 (Codex r4): la copia y su número de lectura van juntos: una cancelación (app a segundo plano) entre los dos dejaba la
            // copia nueva con el número viejo y la pantalla ya no la reconocía como más nueva.
            withContext(kotlinx.coroutines.NonCancellable) {
                cache.saveDurable(CACHE_POR_PAGAR, venueId, conOcultos(cuerpo, ocultosQueSiguen(previos, lista), System.currentTimeMillis()))
                secuenciaDeLaCopia[venueId] = numero
            }
            Guardado.GUARDADA
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ No se pudo guardar la copia de «Por pagar»: ${e.message}")
            Guardado.FALLO
        }
    }

    /**
     * R101: lo escondido tras una acción confirmada se guarda en la MISMA copia que la lista (misma fila, una escritura), con la edad de
     * esa lista intacta. `false` si no hay copia o no se pudo escribir (queda sólo en memoria hasta la siguiente lectura buena).
     */
    suspend fun guardarOcultos(ocultos: Map<String, HuellaDeFolio>): Boolean {
        val venueId = secureStorage.venueId ?: return false
        return copiaDeFolios.withLock {
            try {
                val actual = cache.load(CACHE_POR_PAGAR, venueId) ?: return@withLock false
                val raiz = JSONObject(actual.json)
                cache.saveDurable(CACHE_POR_PAGAR, venueId, conOcultos(actual.json, ocultos, raiz.optLong(GUARDADA_EN, actual.updatedAt)))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ No se pudo guardar lo escondido de «Por pagar»: ${e.message}")
                false
            }
        }
    }

    /** La última página 1 que llegó del servidor, con su edad y lo escondido; `null` si nunca se leyó. */
    suspend fun porPagarGuardado(): ListaPorPagar? {
        val venueId = secureStorage.venueId ?: return null
        // W-1: la copia y el número de la lectura que la dejó, juntos (bajo el mismo candado con que se escriben).
        val (c, numero) = copiaDeFolios.withLock { cache.load(CACHE_POR_PAGAR, venueId) to (secuenciaDeLaCopia[venueId] ?: 0L) }
        c ?: return null
        return runCatching {
            val raiz = JSONObject(c.json)
            // La edad es la de la LISTA: guardar lo escondido no la rejuvenece.
            val edad = if (raiz.has(GUARDADA_EN)) ((System.currentTimeMillis() - raiz.getLong(GUARDADA_EN)) / 60_000).coerceAtLeast(0) else c.ageMinutes
            parsePorPagar(c.json).copy(guardadoHaceMin = edad, ocultos = ocultosDe(raiz), numero = numero)
        }.getOrNull()
    }

    private fun conOcultos(cuerpo: String, ocultos: Map<String, HuellaDeFolio>, guardadaEn: Long): String {
        val json = JSONObject()
        ocultos.forEach { (id, h) ->
            json.put(id, JSONObject().put("seccion", h.seccion).put("status", h.status.name).put("remainingCents", h.remainingCents))
        }
        return JSONObject(cuerpo).put(OCULTOS, json).put(GUARDADA_EN, guardadaEn).toString()
    }

    private fun ocultosDe(raiz: JSONObject): Map<String, HuellaDeFolio> {
        val json = raiz.optJSONObject(OCULTOS) ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { id ->
            val h = json.optJSONObject(id) ?: return@mapNotNull null
            id to HuellaDeFolio(h.optString("seccion"), OnlineOrderStatus.from(h.optString("status")), h.optLong("remainingCents"))
        }.toMap()
    }

    private fun parsePorPagar(cuerpo: String): ListaPorPagar {
        val data = JSONObject(cuerpo).getJSONObject("data")
        val counts = data.optJSONObject("counts")
        val cursores = data.optJSONObject("nextCursors")
        // Un folio dañado se salta: no tumba la lista (el aviso de un folio real no puede depender de otro roto).
        fun folios(clave: String): List<OnlineOrder> {
            val lista = data.optJSONArray(clave)
            return (0 until (lista?.length() ?: 0)).mapNotNull { i ->
                runCatching { parseOrder(lista!!.getJSONObject(i)) }
                    .onFailure { Log.w(TAG, "Folio $clave #$i ignorado por malformado: ${it.message}") }.getOrNull()
            }
        }
        return ListaPorPagar(
            disponible = true,
            showTile = data.optBoolean("showTile", false),
            pollSeconds = data.optInt("pollSeconds", 600),
            counts = ConteosPorPagar(counts?.optInt("toPay") ?: 0, counts?.optInt("toDeliver") ?: 0),
            porPagar = folios("toPay"),
            porEntregar = folios("toDeliver"),
            nextCursors = CursoresPorPagar(cursores?.textoONulo("toPay"), cursores?.textoONulo("toDeliver")),
        )
    }

    // MARK: - Saldo

    /**
     * El saldo a cobrar lo dice el servidor (`preBillBalance.remainingBalanceCents`); la app nunca lo recalcula desde renglones.
     * D4 (28-oct): de la MISMA respuesta sale el precio de cada producto, sólo para «Ver productos». Un producto incompleto
     * (sin nombre, sin `total`, por peso) o un pago previo dejan [SaldoDelPedido.precios] en null; el saldo se cobra igual.
     */
    suspend fun saldoDelPedido(orderId: String): Result<SaldoDelPedido> {
        val (venueId, token) = sesion() ?: return sinSesion()
        return ejecutar(get(ruta(venueId, "orders/$orderId"), token, rapida = true), clienteDelCobro).mapCatching {
            val orden = JSONObject(it).getJSONObject("order")
            val balance = orden.getJSONObject("preBillBalance")
            val saldo = balance.getLong("remainingBalanceCents")
            // Con cualquier pago previo la lista no se enseña: un abono y un cargo iguales cuadrarían la suma por casualidad.
            val sinPagos = balance.optLong("totalPaidCents", -1L) == 0L
            SaldoDelPedido(
                saldo, if (sinPagos) runCatching { precios(orden) }.getOrNull() else null,
                estadoDeLaCuenta = orden.textoONulo("status"),
                pagadoA = runCatching { ultimoCobro(orden) }.getOrNull(),
            )
        }
    }

    /** La hora del último cobro que entró (`COMPLETED` y positivo: un reembolso o un intento fallido no cuentan); null si no hay. */
    private fun ultimoCobro(orden: JSONObject): Instant? {
        val pagos = orden.optJSONArray("payments") ?: return null
        return (0 until pagos.length()).mapNotNull { i ->
            val p = pagos.getJSONObject(i)
            if (p.optString("status") != "COMPLETED" || p.optDouble("amount", 0.0) <= 0.0) return@mapNotNull null
            p.textoONulo("createdAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        }.maxOrNull()
    }

    private fun precios(orden: JSONObject): List<PrecioDeRenglon> {
        val items = orden.getJSONArray("items")
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            require(o.isNull("weightQuantity") && !o.isNull("total")) { "renglón sin precio o por peso" }
            PrecioDeRenglon(
                name = o.textoONulo("productName") ?: error("sin nombre"),
                quantity = o.getInt("quantity"),
                totalCents = pesosACentavos(o, "total"),
                note = o.textoONulo("notes"),
                modifierNames = o.optJSONArray("modifiers")?.let { m -> (0 until m.length()).map { m.getJSONObject(it).getString("name") } }.orEmpty(),
            )
        }
    }

    private companion object {
        const val TRAY_HEADER = "x-online-orders-tray"
        const val DEVICE_HEADER = "x-device-id"
        const val TO_PAY_HEADER = "x-online-orders-to-pay"
        const val CACHE_POR_PAGAR = "to_pay"
        /** R101: llaves que el aparato agrega a su copia de «Por pagar» (el SERVIDOR nunca las manda). */
        const val OCULTOS = "ocultosDelAparato"
        const val GUARDADA_EN = "guardadaEnMs"
        const val CACHE_POR_COBRAR = "delivery_cash"
    }
}
