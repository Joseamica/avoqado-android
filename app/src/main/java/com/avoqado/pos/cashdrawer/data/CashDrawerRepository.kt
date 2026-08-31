package com.avoqado.pos.cashdrawer.data

import android.os.Build
import android.util.Log
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerStatus
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.contentOrNull

private const val TAG = "💰 CashDrawerRepo"

// MARK: - API Request/Response Models

@Serializable
private data class OpenDrawerRequest(val startingAmount: Double, val deviceName: String? = null)

/**
 * 🔴 `localId` es la LLAVE DE IDEMPOTENCIA del movimiento, y es el mismo id con el
 * que la fila vive en Room. Sin ella, la fila que crea el server nace anónima y
 * **ninguna** versión futura del cliente puede reconocerla como suya: el eco del sync
 * entra como fila nueva y el mismo ingreso/retiro se cuenta dos veces, para siempre.
 * Ése era el agujero — la fusión por llave estaba escrita en el cliente pero la
 * tubería llegaba seca, porque `payIn`/`payOut` del server creaban el evento sin
 * `localId` (nadie se lo mandaba).
 *
 * El valor NUNCA se regenera: si cambiara entre dos reintentos del mismo movimiento
 * dejaría de ser una llave y el server insertaría dos filas en vez de deduplicar
 * contra `@@unique([venueId, localId])`. Es el mismo contrato que ya usa el push de
 * ventas (`SyncEventDto.localId`) y el outbox de intents.
 *
 * Aditivo a propósito: un server viejo que no conozca el campo simplemente lo ignora
 * y todo se comporta como hoy. Mandar la llave nunca puede impedir registrar dinero.
 */
@Serializable
private data class PayInRequest(val amount: Double, val note: String? = null, val localId: String, val sessionId: String? = null)

@Serializable
private data class PayOutRequest(val amount: Double, val note: String? = null, val localId: String, val sessionId: String? = null)

@Serializable
private data class CloseDrawerRequest(val actualAmount: Double, val note: String? = null, val sessionId: String? = null)

/** Qué hacer con una operación de la cola, según lo que contestó el servidor. */
internal enum class DestinoDeLaOperacion { CONFIRMADA, REINTENTAR, RECHAZADA }

/**
 * 🔴 Función PURA a propósito: es la única forma de probar un 429 o un 503 sin un servidor
 * que los produzca. Antes esta decisión vivía dentro de la llamada de red y nadie la ejercitaba.
 *
 * Tres desenlaces, no dos. Un booleano "¿se quita de la cola?" mezclaría el caso en que el
 * servidor YA TIENE la operación con el caso en que la rechazó — y el segundo es dinero que
 * hay que enseñarle al cajero, no basura que se tira.
 *
 * `code = 0` significa que la llamada ni siquiera salió (sin red).
 */
/**
 * 🔴 Los ÚNICOS 4xx que significan "esto nunca va a funcionar".
 *
 * La lista es EXPLÍCITA, no un rango. Un rango `400..499` arrastra códigos que son transitorios
 * —el **401** de un token vencido, sobre todo: tras reautenticarse el movimiento sí habría
 * entrado— y descartarlos borra dinero para siempre (Codex, 4ª auditoría). Ante un 4xx que no
 * conocemos se REINTENTA: quedarse atorado es ruidoso y se puede arreglar; perder un retiro es
 * silencioso y no.
 */
private val RECHAZOS_DEFINITIVOS = setOf(400, 403, 409, 422)

internal fun clasificarRespuestaDelServer(kind: String, code: Int): DestinoDeLaOperacion = when {
    code in 200..299 -> DestinoDeLaOperacion.CONFIRMADA
    // El 404 dice lo contrario según la operación: para un CIERRE es "ya estaba cerrada"
    // (nada que reintentar); para un movimiento es "aún no conozco esa caja" — su apertura
    // no ha llegado —, y descartarlo borraría un retiro real.
    code == 404 -> if (kind == "CLOSE") DestinoDeLaOperacion.CONFIRMADA else DestinoDeLaOperacion.REINTENTAR
    code in RECHAZOS_DEFINITIVOS -> DestinoDeLaOperacion.RECHAZADA
    // Todo lo demás se reintenta: sin red (0), 5xx, 408 y 429 ("vas muy rápido" / "se agotó el
    // tiempo"), 401 (token vencido) y cualquier 4xx que no esté en la lista de arriba.
    else -> DestinoDeLaOperacion.REINTENTAR
}

/**
 * Un movimiento del cajón que este aparato YA hizo en local y que el server aún no confirmó.
 * `kind` = CLOSE | PAY_IN | PAY_OUT. `localId` es la llave idempotente del evento (PAY_*).
 */
@Serializable
internal data class PendingDrawerOp(
    val kind: String,
    val sessionId: String,
    val amountCents: Int,
    val note: String? = null,
    val localId: String? = null,
    val at: Long,
    /**
     * 🔴 Un rechazo definitivo NO borra la operación: la marca. Sigue siendo dinero que el
     * servidor no tiene, y el cajero tiene que enterarse antes de cerrar su caja. Nulo = viva.
     */
    val rechazadaEn: Long? = null,
    val motivoDelRechazo: String? = null,
)

@Singleton
class CashDrawerRepository @Inject constructor(
    private val dao: CashDrawerDao,
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val pendingCashSales: PendingCashSales,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val venueId: String
        get() = secureStorage.venueId ?: ""

    /** Nombre del local para el encabezado del corte impreso. */
    val venueName: String
        get() = secureStorage.venueName?.takeIf { it.isNotBlank() } ?: "Avoqado"

    private val staffId: String
        get() = secureStorage.userId ?: ""

    private val staffName: String
        get() {
            val first = secureStorage.userFirstName ?: ""
            val last = secureStorage.userLastName ?: ""
            return "$first $last".trim().ifEmpty { secureStorage.userEmail ?: "Staff" }
        }

    private val deviceName: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    private val baseUrl: String
        get() = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/cash-drawer"

    // MARK: - Session Queries

    suspend fun getOpenSession(): CashDrawerSessionEntity? {
        return dao.getOpenSession(venueId)
    }

    suspend fun getHistory(): List<CashDrawerSessionEntity> {
        return dao.getClosedSessions(venueId)
    }

    // MARK: - Tender breakdown (corte de caja — all payment methods, not just cash)

    /** One tender row for the corte's "Desglose por método de pago". */
    /**
     * @param totalCents lo cobrado por ese método, propina INCLUIDA — es lo que
     *   entró y, en efectivo, lo que está físicamente en el cajón.
     * @param tipsCents cuánto de ese total fue propina. Viaja aparte porque la
     *   propina NO es dinero del negocio: se le entrega al mesero. Un corte que la
     *   suma sin distinguirla hace que el cajón "cuadre" con dinero que se va a
     *   repartir.
     */
    data class TenderRow(val method: String, val totalCents: Int, val tipsCents: Int = 0)

    /**
     * Payments grouped by method for the session window [fromMillis, toMillis].
     * The drawer only tracks CASH physically, so card/other totals come from the
     * server's payment records. Returns empty on any failure (corte still renders).
     */
    /**
     * Desglose por método de pago de la ventana del corte.
     *
     * 🔴 `null` = NO SE PUDO consultar (sin red, 4xx/5xx, cuerpo ilegible). Una lista VACÍA es un dato
     * legítimo y distinto: el server contestó 200 y **no hubo cobros** en esa ventana. Antes las tres
     * cosas devolvían `emptyList()` y la pantalla las leía todas como "sin conexión" — el founder vio
     * "Sin conexión" y un botón de Reintentar teniendo internet, en un corte que simplemente no tuvo
     * ventas (28-ago). La UI no puede mentir sobre por qué falta un dato.
     */
    suspend fun getTenderBreakdown(fromMillis: Long, toMillis: Long): List<TenderRow>? {
        if (venueId.isEmpty()) return null
        return try {
            val from = java.time.Instant.ofEpochMilli(fromMillis).toString()
            val to = java.time.Instant.ofEpochMilli(toMillis).toString()
            val url = "$baseUrl/tender-breakdown?from=${java.net.URLEncoder.encode(from, "UTF-8")}&to=${java.net.URLEncoder.encode(to, "UTF-8")}"
            val request = Request.Builder().url(url).get().build()
            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (code !in 200..299 || body.isEmpty()) {
                Log.e(TAG, "❌ tender-breakdown failed: $code")
                return null
            }
            val root = json.decodeFromString<JsonObject>(body)
            val arr = root["data"]?.jsonObject?.get("tenderBreakdown")?.jsonArray ?: return null
// 🔴 Un renglón que no se entiende invalida el DESGLOSE ENTERO, no se salta.
            //
            // Saltárselo (`mapNotNull`) tenía dos formas de mentir, las dos silenciosas: si fallan
            // TODOS, la lista sale vacía y la pantalla la lee como "no hubo cobros" —quitando
            // incluso el botón de reintentar—; y si falla sólo alguno, el corte subestima las
            // ventas sin decirlo. Un corte de caja incompleto que se ve completo es peor que uno
            // que admite no haber podido consultarse (Codex, 4ª auditoría).
            val filas = arr.map { el ->
                val obj = el.jsonObject
                val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return@map null
                val dollars = obj["total"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val tips = obj["tips"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                TenderRow(
                    method = method,
                    totalCents = (dollars * 100).toInt(),
                    tipsCents = (tips * 100).toInt(),
                )
            }
            if (filas.any { it == null }) {
                Log.e(TAG, "❌ tender-breakdown con ${filas.count { it == null }} renglón(es) ilegibles: se reporta como NO consultado")
                return null
            }
            filas.filterNotNull()
        } catch (e: Exception) {
            Log.e(TAG, "❌ tender-breakdown error: ${e.message}")
            null
        }
    }

    // MARK: - End of day ("Cierre del día")

    /**
     * Fetches the end-of-day summary: the day's sales by tender + the blockers
     * (open checks, open drawers, clocked-in staff). Read-only; null on failure.
     */
    suspend fun getEndOfDay(): EndOfDaySummary? {
        if (venueId.isEmpty()) return null
        return try {
            val url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/end-of-day"
            val request = Request.Builder().url(url).get().build()
            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (code !in 200..299 || body.isEmpty()) {
                Log.e(TAG, "❌ end-of-day failed: $code")
                return null
            }
            json.decodeFromString(EndOfDayResponse.serializer(), body).data
        } catch (e: Exception) {
            Log.e(TAG, "❌ end-of-day error: ${e.message}")
            null
        }
    }

    // MARK: - Sync from API

    /**
     * Fetch open session + events from API and update Room.
     * Called on launch / pull-to-refresh.
     */
    suspend fun syncFromApi() {
        if (venueId.isEmpty()) return
        try {
            reproducirCierresPendientes()
            syncCurrentSession()
            syncHistory()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Sync from API error: ${e.message}")
        }
    }

    private suspend fun syncCurrentSession() {
        val request = Request.Builder()
            .url("$baseUrl/current")
            .get()
            .build()

        val (code, body) = withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.code to (response.body?.string() ?: "")
        }

        if (code in 200..299 && body.isNotEmpty()) {
            try {
                val root = json.decodeFromString<JsonObject>(body)
                val sessionObj = parseSessionEnvelope(root)
                if (sessionObj != null) {
                    // Events live inside the session payload (fallback: top-level).
                    val session = adoptServerSession(sessionObj, root["events"]?.jsonArray)
                    Log.d(TAG, "✅ Caja del server sincronizada: ${session.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Parse current session error: ${e.message}")
            }
        }
    }

    /**
     * Server envelope is {success, data: session} with the events ARRAY EMBEDDED in
     * the session; accept legacy top-level keys too. `null` = no hay caja abierta.
     */
    private fun parseSessionEnvelope(root: JsonObject): JsonObject? =
        (root["data"] as? JsonObject)
            ?: root["data"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonObject }
            ?: root["session"]?.jsonObject

    /**
     * 🔴 LA CAJA ADOPTA EL ID DEL SERVER — el corazón de la reconciliación.
     *
     * Sin red, la sesión NACE LOCAL a propósito: el POS no puede pedirle un id a
     * nadie antes de dejar abrir la caja. Pero cuando el server confirma, la fila
     * local tiene que **promoverse** a su id en vez de quedarse como una segunda
     * caja abierta. Es el mismo patrón que el outbox ya usa con las órdenes
     * (`TableSession.promoteProvisional`, `localOrderId → orderId`), sólo que aquí
     * la promoción vive en Room.
     *
     * Lo que costaba no hacerlo, medido con sqlite3 (2026-08-16): Room terminaba con
     * DOS sesiones OPEN del mismo local y `getOpenSession` devolvía siempre la
     * provisional, así que los movimientos que manda el server —el PAY_OUT del
     * reembolso, entre ellos— caían en la sesión que la pantalla no lee. El server ya
     * restaba bien y en el corte del cajero seguían sobrando los $150.
     *
     * Tres pasos, en este orden:
     *  1. Entra la fila del server. Va primero para que ningún evento quede
     *     apuntando a una sesión que no existe, ni siquiera por un instante.
     *  2. Las provisionales adoptan su id: los eventos se MUDAN con ellas
     *     (`repointEventsFrom`) y la fila vieja desaparece. Un retiro registrado sin
     *     red sigue contando; si no se mudara, el cajón "aparecería" con ese dinero
     *     de más. 🔴 La mudanza está ACOTADA a la ventana de la caja del server (ver
     *     abajo), y sólo se borra la fila provisional si ya no le queda nada.
     *  3. Entran los eventos confirmados. Antes de insertar cada uno se intenta la
     *     FUSIÓN POR LLAVE ([promoteEvent] con el `localId` que ahora manda el
     *     server); después se borran las copias locales de los tipos que el server
     *     escribe por su cuenta (ver [SERVER_OWNED_EVENT_TYPES]).
     *
     * 🔴 **Por qué la mudanza se acota por tiempo.** Antes se llevaba TODOS los
     * eventos de cualquier otra sesión abierta — incluida la caja de un turno
     * anterior que este aparato nunca vio cerrar. El `CASH_SALE` de ayer sí lo
     * borraba la limpieza por tipo, pero el retiro a mano de ayer se colaba al turno
     * de hoy: medido, $5,050.00 donde debía decir $5,130.00, un sobrante inventado
     * del tamaño exacto del retiro de ayer.
     *
     * La cota es `server.openedAt`, y no es un número arbitrario: es la MISMA ventana
     * con la que el server calcula su esperado (`calculateExpectedAmount` suma los
     * eventos colgados de la sesión, y un evento anterior a su apertura no puede
     * estar colgado de ella). Cliente y server quedan atados por construcción.
     *
     * Lo que queda fuera de la ventana NO se borra ni se toca: es dinero que salió de
     * verdad, sigue colgado de su propia caja. No contaminar nunca puede significar
     * destruir.
     */
    private suspend fun adoptServerSession(
        sessionObj: JsonObject,
        fallbackEvents: kotlinx.serialization.json.JsonArray? = null,
    ): CashDrawerSessionEntity {
        val parsed = parseSessionFromApi(sessionObj)
        // 🔴 Si este aparato ya cerró ESA caja sin red, el server todavía la ve OPEN. Adoptarla
        // como abierta borraría el conteo del cajero (pasó en la Samsung, 27-ago). Se conserva
        // CERRADA con el conteo local; el cierre pendiente se reproduce en el siguiente sync.
        val cierrePendiente = pendientes().firstOrNull { it.kind == "CLOSE" && it.sessionId == parsed.id }
        val server = if (parsed.status == CashDrawerStatus.OPEN.name && cierrePendiente != null) {
            Log.w(TAG, "⏸️ El server aún ve OPEN la caja ${parsed.id}, pero aquí ya se cerró: se conserva el cierre local")
            parsed.copy(
                status = CashDrawerStatus.CLOSED.name,
                closedAt = cierrePendiente.at,
                actualAmountCents = cierrePendiente.amountCents,
                closingNote = cierrePendiente.note,
                closedByStaffId = staffId,
                closedByName = staffName,
            )
        } else parsed
        dao.insertSession(server)

        if (server.status == CashDrawerStatus.OPEN.name && venueId.isNotEmpty()) {
            dao.getOpenSessions(venueId)
                .filter { it.id != server.id }
                .forEach { provisional ->
                    Log.d(TAG, "⬆️ Promoviendo caja provisional ${provisional.id} → ${server.id}")
                    dao.repointEventsFrom(provisional.id, server.id, server.openedAt)
                    val sobrantes = dao.getSessionEvents(provisional.id)
                    // 🔴 Caja FANTASMA (vista dos veces en la Samsung, 27-ago): el OPEN local nace unos ms
                    // ANTES del openedAt del server, queda fuera de la ventana y la provisional se
                    // "conservaba" OPEN para siempre — tras cerrar la caja real, getOpenSession la
                    // devolvía y la pantalla enseñaba una caja abierta con $0 de movimientos. Si sólo
                    // le queda su apertura, se va entera; si le queda dinero real de antes, se conserva
                    // pero CERRADA: una provisional promovida nunca es una caja abierta.
                    if (sobrantes.all { it.type == CashDrawerEventType.OPEN.name }) {
                        sobrantes.forEach { dao.deleteEvent(it.id) }
                        dao.deleteSession(provisional.id)
                    } else {
                        dao.updateSession(
                            provisional.copy(
                                status = CashDrawerStatus.CLOSED.name,
                                closedAt = server.openedAt,
                                closingNote = "Fusionada con la caja del server ${server.id}",
                            ),
                        )
                        Log.d(TAG, "🗄️ Caja ${provisional.id} conservada CERRADA: tiene movimientos anteriores a esta caja")
                    }
                }
        }

        val eventsArray = sessionObj["events"]?.jsonArray ?: fallbackEvents
        // Las ventas que el server trae POR PRIMERA VEZ: ni una fila mía renombrada, ni
        // una que un sync anterior ya copió. Ver [PendingCashSales.ventasProtegidas].
        val ventasNuevasDelServer = mutableListOf<VentaConfirmadaPorPrimeraVez>()
        var servidorConfirmaVentas = false
        val confirmedIds = eventsArray.orEmpty().map { eventJson ->
            val obj = eventJson.jsonObject
            val event = parseEventFromApi(obj, server.id)
            // 🔴 Se pregunta ANTES de tocar Room: después de insertar, TODA fila
            // "ya estaba". Es justo la diferencia entre la venta que el server acaba de
            // confirmar y la que trae en cada payload desde hace tres syncs.
            val yaEstaba = dao.getEvent(event.id) != null
            // 🔑 Si el server dice que este evento es el MÍO, mi fila adopta su id
            // ANTES de insertarlo: así queda UNA sola fila y gana el contenido del
            // server. La llave es un hecho, no una inferencia — por eso vence a la
            // ventana de arriba: un movimiento mal archivado en la caja vieja que el
            // server cuenta en la de hoy tiene que venirse, o lo perderíamos.
            val adoptada = promoteEvent(obj["localId"]?.jsonPrimitive?.contentOrNull, event.id) != null
            if (event.type == CashDrawerEventType.CASH_SALE.name) {
                servidorConfirmaVentas = true
                if (!yaEstaba && !adoptada) {
                    ventasNuevasDelServer += VentaConfirmadaPorPrimeraVez(
                        orderId = event.orderId,
                        totalCents = event.amountCents,
                    )
                }
            }
            dao.insertEvent(event)
            event.id
        }

        // Un payload SIN eventos no autoriza a borrar nada: sería el mismo error que
        // pisar la config de impresoras con un refresh fallido. Sólo se limpia cuando
        // el server efectivamente dijo qué eventos tiene.
        //
        // 🔴 Y aun entonces, una venta cuyo cobro SIGUE EN LA COLA no puede venir
        // confirmada: el server todavía no la conoce. Borrarla ahí le desaparecía al
        // cajero dinero que sí está en el cajón, justo al abrir esta pantalla para
        // cerrar su turno. Quién se salva lo decide [PendingCashSales.ventasProtegidas]
        // —lo que el server ya cubre deja de ser candidato; del resto, por orden si la
        // hay y si no por monto— consumiendo cada cobro pendiente UNA sola vez, para
        // que proteger no se convierta en duplicar.
        if (confirmedIds.isNotEmpty()) {
            // Las copias locales de venta que el server NO confirmó: las candidatas a
            // borrarse, y por tanto las únicas que un cobro encolado puede proteger.
            // Se leen DESPUÉS de insertar lo confirmado, así que una fila adoptada por
            // llave ya lleva el id del server y queda fuera por sí sola.
            val confirmados = confirmedIds.toSet()
            val ventasLocales = dao.getSessionEvents(server.id).filter {
                it.type == CashDrawerEventType.CASH_SALE.name && it.id !in confirmados
            }
            dao.deleteUnconfirmedEvents(
                server.id,
                tiposABorrar(servidorConfirmaVentas),
                confirmedIds,
                PendingCashSales.ventasProtegidas(
                    ventasLocales = ventasLocales,
                    // 🔴 La ventana de la caja: un cobro atorado del turno anterior no
                    // puede protegerle una venta a la caja de hoy (ver `sinReproducir`).
                    cobrosSinReproducir = pendingCashSales.sinReproducir(venueId, ventanaDeLaCaja(sessionObj)),
                    ventasConfirmadasPorPrimeraVez = ventasNuevasDelServer,
                ).toList(),
            )
        }
        return server
    }

    /**
     * ⬆️ Un movimiento que el cliente escribió y el server confirmó adopta el id
     * real, igual que la sesión.
     *
     * Sin esto, el eco del sync entra como una fila NUEVA (el server le pone su
     * propio cuid) y el mismo retiro de $50 se resta dos veces. La copia local es
     * necesaria —el cajero tiene que ver el movimiento al instante, con o sin red—
     * así que la salida no es dejar de escribirla, es que comparta identidad.
     *
     * Se llama desde DOS lados, y el segundo es el que cierra el agujero:
     *  - **al ESCRIBIR** el movimiento, con el id que devuelve el POST. Sólo alcanza
     *    a lo que esta app escribe estando corriendo.
     *  - **al SINCRONIZAR**, con el `localId` que el server ahora devuelve en cada
     *    evento. Es lo único que alcanza a una fila que ya estaba en Room desde antes
     *    de actualizar la app: la limpieza por tipo excluye `PAY_IN`/`PAY_OUT` a
     *    propósito y la promoción al escribir ya no puede correr para ellas, así que
     *    sin la llave el movimiento heredado se contaba dos veces PARA SIEMPRE.
     *
     * 🔴 Degradación: `localId` nulo o en blanco (server viejo, o fila sin llave) →
     * no hace nada y el comportamiento es idéntico al de antes. La llave mejora la
     * fusión; no es requisito para funcionar.
     */
    private suspend fun promoteEvent(localId: String?, serverId: String?): CashDrawerEventEntity? {
        if (localId.isNullOrBlank() || serverId.isNullOrBlank() || serverId == localId) return null
        val local = dao.getEvent(localId) ?: return null
        val promoted = local.copy(id = serverId)
        dao.insertEvent(promoted)
        dao.deleteEvent(localId)
        Log.d(TAG, "⬆️ Movimiento confirmado $localId → $serverId")
        return promoted
    }

    /** El id que el server le puso al evento que acabamos de registrar, si lo mandó. */
    private fun parseEventId(body: String): String? = try {
        val root = json.decodeFromString<JsonObject>(body)
        (root["data"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            ?: root["event"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }

    private suspend fun syncHistory() {
        val request = Request.Builder()
            .url("$baseUrl/history")
            .get()
            .build()

        val (code, body) = withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.code to (response.body?.string() ?: "")
        }

        if (code in 200..299 && body.isNotEmpty()) {
            try {
                val root = json.decodeFromString<JsonObject>(body)
                val sessionsArray = root["sessions"]?.jsonArray
                sessionsArray?.forEach { sessionJson ->
                    val session = parseSessionFromApi(sessionJson.jsonObject)
                    dao.insertSession(session)
                }
                Log.d(TAG, "✅ Synced ${sessionsArray?.size ?: 0} history sessions from API")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Parse history error: ${e.message}")
            }
        }
    }

    // MARK: - Open Session

    suspend fun openSession(startingAmountCents: Int): CashDrawerSessionEntity {
        val session = CashDrawerSessionEntity(
            id = UUID.randomUUID().toString(),
            venueId = venueId,
            deviceName = deviceName,
            openedByStaffId = staffId,
            openedByName = staffName,
            openedAt = System.currentTimeMillis(),
            startingAmountCents = startingAmountCents,
            status = CashDrawerStatus.OPEN.name,
        )
        dao.insertSession(session)

        // Record OPEN event
        val event = CashDrawerEventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            venueId = venueId,
            type = CashDrawerEventType.OPEN.name,
            amountCents = startingAmountCents,
            note = null,
            staffId = staffId,
            staffName = staffName,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertEvent(event)

        Log.d(TAG, "✅ Session opened locally: ${session.id}, starting: $startingAmountCents")

        // Si el server contesta, la caja adopta SU id aquí mismo. Si no contesta
        // —sin red, o 409 porque otra tablet ya la abrió— se queda provisional y la
        // adopta el primer sync que lo logre. Abrir la caja nunca depende de eso.
        return fireApiOpen(startingAmountCents) ?: session
    }

    private suspend fun fireApiOpen(startingAmountCents: Int): CashDrawerSessionEntity? {
        // Un cierre sin confirmar deja la caja OPEN en el server: sin esto, abrir la siguiente daría 409.
        reproducirCierresPendientes()
        try {
            val dollars = startingAmountCents / 100.0
            val requestBody = json.encodeToString(
                OpenDrawerRequest.serializer(),
                OpenDrawerRequest(startingAmount = dollars, deviceName = deviceName),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/open")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d(TAG, "✅ API open session success")
                val sessionObj = parseSessionEnvelope(json.decodeFromString<JsonObject>(body))
                return sessionObj?.let { adoptServerSession(it) }
            } else {
                Log.e(TAG, "❌ API open session failed: $code - $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API open session error: ${e.message}")
        }
        return null
    }

    // MARK: - Events

    suspend fun addPayIn(amountCents: Int, note: String?): CashDrawerEventEntity? {
        val session = getOpenSession() ?: return null
        val event = CashDrawerEventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            venueId = venueId,
            type = CashDrawerEventType.PAY_IN.name,
            amountCents = amountCents,
            note = note,
            staffId = staffId,
            staffName = staffName,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertEvent(event)
        Log.d(TAG, "✅ Pay-in recorded locally: $amountCents")

        // La fila local nace provisional y adopta el id del server si éste confirma.
        val op = PendingDrawerOp("PAY_IN", session.id, amountCents, note, event.id, System.currentTimeMillis())
        encolar(op)
        val confirmado = fireApiPayIn(amountCents, note, event.id, session.id)
        if (confirmado != null) quitar(op)
        return confirmado ?: event
    }

    private suspend fun fireApiPayIn(amountCents: Int, note: String?, localEventId: String, sessionId: String): CashDrawerEventEntity? {
        try {
            val dollars = amountCents / 100.0
            val requestBody = json.encodeToString(
                PayInRequest.serializer(),
                // La llave es el id con el que la fila YA quedó en Room, no uno nuevo.
                PayInRequest(amount = dollars, note = note, localId = localEventId, sessionId = sessionId),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/pay-in")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d(TAG, "✅ API pay-in success")
                return promoteEvent(localEventId, parseEventId(body))
            } else {
                Log.e(TAG, "❌ API pay-in failed: $code - $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API pay-in error: ${e.message}")
        }
        return null
    }

    suspend fun addPayOut(amountCents: Int, note: String?): CashDrawerEventEntity? {
        val session = getOpenSession() ?: return null
        val event = CashDrawerEventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            venueId = venueId,
            type = CashDrawerEventType.PAY_OUT.name,
            amountCents = amountCents,
            note = note,
            staffId = staffId,
            staffName = staffName,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertEvent(event)
        Log.d(TAG, "✅ Pay-out recorded locally: $amountCents")

        // La fila local nace provisional y adopta el id del server si éste confirma.
        val op = PendingDrawerOp("PAY_OUT", session.id, amountCents, note, event.id, System.currentTimeMillis())
        encolar(op)
        val confirmado = fireApiPayOut(amountCents, note, event.id, session.id)
        if (confirmado != null) quitar(op)
        return confirmado ?: event
    }

    private suspend fun fireApiPayOut(amountCents: Int, note: String?, localEventId: String, sessionId: String): CashDrawerEventEntity? {
        try {
            val dollars = amountCents / 100.0
            val requestBody = json.encodeToString(
                PayOutRequest.serializer(),
                // La llave es el id con el que la fila YA quedó en Room, no uno nuevo.
                PayOutRequest(amount = dollars, note = note, localId = localEventId, sessionId = sessionId),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/pay-out")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d(TAG, "✅ API pay-out success")
                return promoteEvent(localEventId, parseEventId(body))
            } else {
                Log.e(TAG, "❌ API pay-out failed: $code - $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API pay-out error: ${e.message}")
        }
        return null
    }

    /**
     * La venta en efectivo que el cajero acaba de cobrar, PROVISIONAL en Room.
     *
     * 🔴 El dueño de este movimiento es el SERVER: lo crea al cobrar
     * (`shared/cashDrawerPosting.postCashSaleToDrawer`) y el endpoint
     * `/cash-drawer/sync` descarta a propósito el que empuja el cliente. Esta fila
     * existe sólo para que la pantalla no se quede muda entre el cobro y el
     * siguiente sync —con o sin red— y desaparece en cuanto llega la confirmada, vía
     * [SERVER_OWNED_EVENT_TYPES]. Si no desapareciera, la MISMA venta sumaría dos
     * veces y el cajón inventaría un sobrante.
     */
    suspend fun addCashSale(amountCents: Int, orderId: String?): CashDrawerEventEntity? {
        val session = getOpenSession() ?: return null
        val event = CashDrawerEventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            venueId = venueId,
            type = CashDrawerEventType.CASH_SALE.name,
            amountCents = amountCents,
            note = null,
            staffId = staffId,
            staffName = staffName,
            orderId = orderId,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertEvent(event)
        Log.d(TAG, "✅ Cash sale recorded: $amountCents, order: $orderId")

        // Push to the server so the backend session's expectedAmount tracks
        // real cash sales (was Room-only → server drawer drifted). Uses the
        // batch /sync endpoint; fire-and-forget like the other event POSTs.
        try {
            val payload = SyncEventsRequest(
                events = listOf(
                    SyncEventDto(
                        type = CashDrawerEventType.CASH_SALE.name,
                        amount = amountCents / 100.0,
                        staffId = staffId,
                        staffName = staffName,
                        orderId = orderId,
                        createdAt = java.time.Instant.ofEpochMilli(event.createdAt).toString(),
                        localId = event.id,
                    ),
                ),
            )
            val body = json.encodeToString(SyncEventsRequest.serializer(), payload)
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/sync")
                .post(body)
                .build()
            val code = withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.code } }
            if (code !in 200..299) Log.e(TAG, "❌ API cash-sale sync failed: $code")
        } catch (e: Exception) {
            Log.e(TAG, "❌ API cash-sale sync error: ${e.message}")
        }
        return event
    }

    @kotlinx.serialization.Serializable
    private data class SyncEventDto(
        val type: String,
        val amount: Double,
        val staffId: String,
        val staffName: String,
        val orderId: String? = null,
        val createdAt: String,
        /**
         * Llave de idempotencia: el MISMO id con el que el evento vive en Room.
         *
         * Este push es fire-and-forget y sin cola de reintento: si la respuesta se pierde y
         * el lote se reenvía, sin esta llave el server insertaba las filas otra vez y el
         * cajón quedaba con efectivo que nunca existió. Con ella el server deduplica contra
         * `@@unique([venueId, localId])`. NUNCA generar un UUID nuevo aquí — tiene que ser
         * el de Room, o la llave deja de ser estable entre reintentos y no sirve de nada.
         */
        val localId: String,
    )

    @kotlinx.serialization.Serializable
    private data class SyncEventsRequest(val events: List<SyncEventDto>)

    // MARK: - Close Session

    suspend fun closeSession(actualAmountCents: Int, note: String?): CashDrawerSessionEntity? {
        val session = getOpenSession() ?: return null
        val expected = computeExpectedAmount(session.id, session.startingAmountCents)
        val overShort = actualAmountCents - expected

        val updated = session.copy(
            closedByStaffId = staffId,
            closedByName = staffName,
            closedAt = System.currentTimeMillis(),
            actualAmountCents = actualAmountCents,
            overShortCents = overShort,
            closingNote = note,
            status = CashDrawerStatus.CLOSED.name,
        )
        dao.updateSession(updated)

        // Record CLOSE event
        val event = CashDrawerEventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            venueId = venueId,
            type = CashDrawerEventType.CLOSE.name,
            amountCents = actualAmountCents,
            note = note,
            staffId = staffId,
            staffName = staffName,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertEvent(event)

        Log.d(TAG, "✅ Session closed locally: ${session.id}, actual: $actualAmountCents, over/short: $overShort")

        // 🔴 P1 (Codex + full-testing 27-ago): el cierre NO se pierde si no hay red. Si el POST
        // falla, queda encolado y se reproduce en cada sync y antes de abrir otra caja. El server
        // conserva la caja OPEN mientras tanto — y por eso el sync NO la vuelve a adoptar (ver
        // adoptServerSession): el conteo del cajero manda hasta que el server lo acepte.
        // Se encola ANTES de tocar la red (Codex, 2ª auditoría): si el proceso muere a medio POST, el
        // cierre sigue en la cola. Al confirmar, se quita.
        val op = PendingDrawerOp("CLOSE", session.id, actualAmountCents, note, null, System.currentTimeMillis())
        encolar(op)
        // Pasa por la cola: primero los ingresos/retiros pendientes de esta caja, y sólo entonces el cierre.
        reproducirPendientes()

        return updated
    }

    // MARK: - Cola durable del cajón (cierres, ingresos y retiros sin confirmar)

    /**
     * 🔴 La cola se lee entera, se modifica y se reescribe entera. Sin exclusión mutua, dos de
     * esas secuencias entrelazadas se pisan: el replay confirma A y guarda `[]` con la foto vieja,
     * justo después de que el cajero encoló un retiro B — y B desaparece de la cola aunque siga
     * en Room, así que NUNCA llega al servidor (Codex, 4ª auditoría). Es dinero que se evapora
     * entre dos escrituras.
     *
     * `synchronized` y no un `Mutex` de corrutinas porque `encolar`/`quitar` se llaman también
     * desde código NO suspendido (la venta en efectivo, el egreso), y un candado que sólo cubre
     * la mitad de los escritores no es un candado.
     */
    private val candadoDeLaCola = Any()

    private fun pendientes(): MutableList<PendingDrawerOp> = try {
        secureStorage.pendingDrawerOpsJson(venueId)
            ?.let { json.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), it) }
            ?.toMutableList() ?: mutableListOf()
    } catch (e: Exception) {
        Log.e(TAG, "❌ Cola del cajón ilegible: ${e.message}")
        mutableListOf()
    }

    private fun guardarPendientes(lista: List<PendingDrawerOp>) {
        secureStorage.setPendingDrawerOpsJson(venueId, if (lista.isEmpty()) null else json.encodeToString(ListSerializer(PendingDrawerOp.serializer()), lista))
    }

    private fun encolar(op: PendingDrawerOp) = synchronized(candadoDeLaCola) {
        val lista = pendientes().filter { !mismaOperacion(it, op) } + op
        guardarPendientes(lista)
    }

    private fun quitar(op: PendingDrawerOp) = synchronized(candadoDeLaCola) {
        guardarPendientes(pendientes().filter { !mismaOperacion(it, op) })
    }

    /**
     * 🔴 La identidad de una operación incluye su `localId`. Dos retiros de $50 de la MISMA caja
     * son operaciones distintas: sin el localId, quitar uno quitaba los dos (Codex, 4ª auditoría).
     * Un CLOSE no lleva localId, y ahí la caja basta — sólo puede haber un cierre por caja.
     */
    private fun mismaOperacion(a: PendingDrawerOp, b: PendingDrawerOp) =
        a.kind == b.kind && a.sessionId == b.sessionId && a.localId == b.localId

    /**
     * 🔴 Lo que el cajero TIENE que ver antes de cerrar: movimientos que el servidor rechazó
     * de plano. Existe dinero en el cajón físico que el servidor nunca va a conocer, y sin
     * este aviso el arqueo saldría con un faltante que nadie sabe explicar.
     */
    data class OperacionRechazada(
        val kind: String,
        val sessionId: String,
        val amountCents: Int,
        val motivo: String,
        /** Identidad ÚNICA de la fila. Dos retiros de $50 de la misma caja no son el mismo aviso. */
        val localKey: String,
    )

    fun operacionesRechazadas(sessionId: String? = null): List<OperacionRechazada> =
        pendientes().filter { it.rechazadaEn != null && (sessionId == null || it.sessionId == sessionId) }
            .map { OperacionRechazada(it.kind, it.sessionId, it.amountCents, it.motivoDelRechazo ?: "El servidor no la aceptó.", llaveDe(it)) }

    private fun llaveDe(op: PendingDrawerOp) = "${op.kind}|${op.sessionId}|${op.localId ?: ""}"

    /** El cajero ya lo vio y decidió qué hacer: se saca de la cola. */
    fun descartarRechazada(localKey: String) = synchronized(candadoDeLaCola) {
        guardarPendientes(pendientes().filter { it.rechazadaEn == null || llaveDe(it) != localKey })
    }

    private fun marcarRechazada(op: PendingDrawerOp, motivo: String) = synchronized(candadoDeLaCola) {
        val lista = pendientes().map {
            if (mismaOperacion(it, op)) it.copy(rechazadaEn = System.currentTimeMillis(), motivoDelRechazo = motivo) else it
        }
        guardarPendientes(lista)
    }

    /** El mensaje del servidor si lo trae; si no, uno que el cajero pueda leer. */
    private fun mensajeDeRechazo(code: Int, cuerpo: String): String = try {
        json.parseToJsonElement(cuerpo).jsonObject["message"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "null" } ?: "El servidor lo rechazó (error $code)."
    } catch (_: Exception) { "El servidor lo rechazó (error $code)." }

    /** Visible para test: ¿hay un cierre de ESTA caja esperando al server? */
    fun tieneCierrePendiente(sessionId: String): Boolean = pendientes().any { it.kind == "CLOSE" && it.sessionId == sessionId }

    /**
     * Reproduce contra el server, EN ORDEN, lo que se quedó sin confirmar: primero ingresos y retiros
     * (con su llave idempotente) y al final el cierre — si el cierre fuera antes, el server firmaría
     * un faltante falso por el retiro que nunca recibió (Codex, 2ª auditoría). Corre al entrar a
     * Caja, en cada sync y antes de abrir otra caja.
     */
    suspend fun reproducirPendientes() {
        val todas = pendientes()
        // Las ya rechazadas NO se reintentan: se quedan guardadas sólo para poder avisar.
        val lista = todas.filter { it.rechazadaEn == null }
            .sortedWith(compareBy({ if (it.kind == "CLOSE") 1 else 0 }, { it.at }))
        if (lista.isEmpty()) return
        var confirmados = 0
        // 🔴 CLOSE es una BARRERA (Codex 3ª auditoría): si un ingreso/retiro de ESA caja no se confirmó (red,
        // 5xx), el cierre NO se manda — el server firmaría un faltante falso por el movimiento que no recibió.
        //
        // 🔴 Un movimiento RECHAZADO también bloquea: el servidor nunca lo va a tener, así que cerrar
        // encima firmaría ese mismo faltante falso, sólo que para siempre. El cierre espera a que
        // alguien resuelva el rechazo.
        //
        // 🔴 La barrera se SIEMBRA con los rechazos de corridas ANTERIORES, no sólo con los de
        // ésta. Filtrarlos de `lista` (para no reintentarlos) los sacaba también del bucle, así
        // que en la SEGUNDA pasada —tras reabrir la app, o al volver a entrar a Caja— el cierre
        // ya no encontraba quién lo bloqueara y se mandaba con el retiro faltando: el servidor
        // firmaba el faltante falso, sólo que un rato después y sin nadie mirando.
        val cajasBloqueadas = todas.filter { it.rechazadaEn != null && it.kind != "CLOSE" }
            .map { it.sessionId }.toMutableSet()
        for (op in lista) {
            if (op.kind == "CLOSE" && op.sessionId in cajasBloqueadas) { Log.w(TAG, "⏸️ Cierre de ${op.sessionId} en espera: hay movimientos sin confirmar"); continue }
            val destino = when (op.kind) {
                "CLOSE" -> fireApiClose(op.sessionId, op.amountCents, op.note).also { if (it == DestinoDeLaOperacion.RECHAZADA) marcarRechazada(op, "El servidor no aceptó el cierre de esta caja.") }
                else -> reproducirMovimiento(op)
            }
            when (destino) {
                DestinoDeLaOperacion.CONFIRMADA -> { quitar(op); confirmados++ }
                DestinoDeLaOperacion.REINTENTAR -> if (op.kind != "CLOSE") cajasBloqueadas += op.sessionId
                DestinoDeLaOperacion.RECHAZADA -> if (op.kind != "CLOSE") cajasBloqueadas += op.sessionId
            }
        }
        if (confirmados > 0) Log.d(TAG, "✅ $confirmados movimiento(s) del cajón confirmados por el server")
    }

    /** Alias histórico (sync/VM). */
    suspend fun reproducirCierresPendientes() = reproducirPendientes()

    /** Qué pasó con el movimiento según el servidor. Ver [clasificarRespuestaDelServer]. */
    private suspend fun reproducirMovimiento(op: PendingDrawerOp): DestinoDeLaOperacion {
        val localId = op.localId ?: return DestinoDeLaOperacion.CONFIRMADA
        return try {
            val dollars = op.amountCents / 100.0
            val (path, body) = if (op.kind == "PAY_IN") {
                "pay-in" to json.encodeToString(PayInRequest.serializer(), PayInRequest(amount = dollars, note = op.note, localId = localId, sessionId = op.sessionId))
            } else {
                "pay-out" to json.encodeToString(PayOutRequest.serializer(), PayOutRequest(amount = dollars, note = op.note, localId = localId, sessionId = op.sessionId))
            }
            val request = Request.Builder().url("$baseUrl/$path").post(body.toRequestBody("application/json".toMediaType())).build()
            val (code, resp) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            when (clasificarRespuestaDelServer(op.kind, code)) {
                DestinoDeLaOperacion.CONFIRMADA -> { promoteEvent(localId, parseEventId(resp)); Log.d(TAG, "✅ ${op.kind} reproducido ($localId)"); DestinoDeLaOperacion.CONFIRMADA }
                DestinoDeLaOperacion.REINTENTAR -> { Log.w(TAG, "🔁 ${op.kind} de la caja ${op.sessionId} sin confirmar ($code); sigue en cola — $resp"); DestinoDeLaOperacion.REINTENTAR }
                DestinoDeLaOperacion.RECHAZADA -> { Log.e(TAG, "🛑 ${op.kind} RECHAZADO por el server ($code): se marca para avisarle al cajero — $resp"); marcarRechazada(op, mensajeDeRechazo(code, resp)); DestinoDeLaOperacion.RECHAZADA }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ ${op.kind} sin red: ${e.message}")
            DestinoDeLaOperacion.REINTENTAR
        }
    }

    /**
     * `true` = el server tiene la caja cerrada (2xx, o 404 "no hay caja abierta": ya estaba
     * cerrada, no hay nada que reintentar). `false` = no se pudo confirmar: sin red, 5xx, o 409
     * porque otra terminal la está cerrando en este instante.
     */
    private suspend fun fireApiClose(sessionId: String, actualAmountCents: Int, note: String?): DestinoDeLaOperacion {
        return try {
            val dollars = actualAmountCents / 100.0
            val requestBody = json.encodeToString(
                CloseDrawerRequest.serializer(),
                CloseDrawerRequest(actualAmount = dollars, note = note, sessionId = sessionId),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/close")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            clasificarRespuestaDelServer("CLOSE", code).also {
                when (it) {
                    DestinoDeLaOperacion.CONFIRMADA -> Log.d(TAG, "✅ Cierre aceptado por el server ($sessionId, $code)")
                    DestinoDeLaOperacion.REINTENTAR -> Log.w(TAG, "🔁 Cierre sin confirmar ($code); sigue en cola — $body")
                    DestinoDeLaOperacion.RECHAZADA -> Log.e(TAG, "🛑 Cierre RECHAZADO por el server ($code) — $body")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API close session error: ${e.message}")
            DestinoDeLaOperacion.REINTENTAR
        }
    }

    // MARK: - Events & Computation

    suspend fun getEvents(sessionId: String): List<CashDrawerEventEntity> {
        return dao.getSessionEvents(sessionId)
    }

    suspend fun computeExpectedAmount(sessionId: String, startingAmountCents: Int): Int {
        val cashSales = dao.sumEventsByType(sessionId, CashDrawerEventType.CASH_SALE.name)
        val payIns = dao.sumEventsByType(sessionId, CashDrawerEventType.PAY_IN.name)
        val payOuts = dao.sumEventsByType(sessionId, CashDrawerEventType.PAY_OUT.name)
        return startingAmountCents + cashSales + payIns - payOuts
    }

    // MARK: - API Response Parsing Helpers

    /**
     * Visible para test: el server manda `null` explícito en los campos opcionales
     * y `jsonPrimitive.content` los convierte en la CADENA "null" — que luego se
     * pinta tal cual en pantalla. Pasó con la nota de cierre en el historial.
     */
    internal fun parseSessionFromApi(obj: JsonObject): CashDrawerSessionEntity {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        // 🔴 `doubleOrNull`, NUNCA `double`.
        //
        // `jsonPrimitive.double` sobre un JSON null intenta convertir el TEXTO
        // "null" y revienta con NumberFormatException. En una sesión ABIERTA,
        // `actualAmount` y `overShort` son nulos por definición —el dinero aún no
        // se ha contado— así que la sesión en curso del server NUNCA se parseaba:
        // "Parse current session error: For input string: \"null\"" en el log, y
        // la caja abierta en otro dispositivo era invisible para este. El
        // historial sí entraba, porque sus sesiones están cerradas y traen cifra.
        val startingDollars = obj["startingAmount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val actualDollars = obj["actualAmount"]?.jsonPrimitive?.doubleOrNull
        val overShortDollars = (obj["overShort"] ?: obj["overShortAmount"])?.jsonPrimitive?.doubleOrNull
        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: CashDrawerStatus.OPEN.name

        return CashDrawerSessionEntity(
            id = id,
            venueId = venueId,
            deviceName = obj["deviceName"]?.jsonPrimitive?.contentOrNull,
            openedByStaffId = obj["openedByStaffId"]?.jsonPrimitive?.contentOrNull ?: "",
            openedByName = obj["openedByName"]?.jsonPrimitive?.contentOrNull ?: "",
            openedAt = parseTimestamp(obj["openedAt"]?.jsonPrimitive?.contentOrNull),
            startingAmountCents = (startingDollars * 100).roundToInt(),
            closedByStaffId = obj["closedByStaffId"]?.jsonPrimitive?.contentOrNull,
            closedByName = obj["closedByName"]?.jsonPrimitive?.contentOrNull,
            closedAt = obj["closedAt"]?.jsonPrimitive?.contentOrNull?.let { parseTimestamp(it) },
            actualAmountCents = actualDollars?.let { (it * 100).roundToInt() },
            overShortCents = overShortDollars?.let { (it * 100).roundToInt() },
            closingNote = obj["closingNote"]?.jsonPrimitive?.contentOrNull,
            status = status,
        )
    }

    private fun parseEventFromApi(obj: JsonObject, sessionId: String): CashDrawerEventEntity {
        val amountDollars = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0

        return CashDrawerEventEntity(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            venueId = venueId,
            type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "",
            amountCents = (amountDollars * 100).roundToInt(),
            note = obj["note"]?.jsonPrimitive?.contentOrNull,
            staffId = obj["staffId"]?.jsonPrimitive?.contentOrNull ?: "",
            staffName = obj["staffName"]?.jsonPrimitive?.contentOrNull ?: "",
            orderId = obj["orderId"]?.jsonPrimitive?.contentOrNull,
            createdAt = parseTimestamp(obj["createdAt"]?.jsonPrimitive?.contentOrNull),
        )
    }

    companion object {
        /**
         * 🔴 Tipos que el SERVER escribe POR SU CUENTA, y de los que esta app sólo
         * guarda una copia provisional para pintar la pantalla al instante.
         *
         * - `OPEN`: lo crea `cash-drawer.mobile.service.openSession` junto con la caja.
         * - `CASH_SALE`: lo crea `shared/cashDrawerPosting.postCashSaleToDrawer` al
         *   cobrar, y el endpoint `/cash-drawer/sync` **descarta** el que manda el
         *   cliente. O sea que el del server existe siempre y el local es, por
         *   definición, la misma venta con otro id.
         *
         * Cuando el sync trae la lista confirmada, las copias locales de estos tipos
         * se borran: si no, la MISMA venta suma dos veces y el cajón inventa un
         * sobrante — el mismo defecto del reembolso duplicado (commit `3acc7bb`),
         * pero al revés.
         *
         * `PAY_IN`/`PAY_OUT` NO van aquí: los escribe el cliente y uno registrado sin
         * red todavía no existe en el server. Borrarlo le inventaría al cajero un
         * faltante. Ésos se reconcilian por identidad, en [promoteEvent].
         */
        private val SERVER_OWNED_EVENT_TYPES = listOf(
            CashDrawerEventType.OPEN.name,
            CashDrawerEventType.CASH_SALE.name,
        )

        /**
         * 🔴 **UN PAYLOAD SIN VENTAS NO AUTORIZA A SOLTAR NINGUNA VENTA.** Espejo del
         * guard de iOS (`CashDrawerServerMerge.ventasLocalesQueElServidorYaCubre`, que
         * abre con `guard servidorConfirmaVentas else { return [] }`).
         *
         * Que el server no reporte ni una venta NO es prueba de que la mía no exista —
         * es el estado normal de una tienda que lleva rato sin red, donde lo único que
         * confirma es su propia apertura. Barrer ahí le desaparecía al cajero dinero
         * que sí está en el cajón: el MISMO cajón daba 500000 en la tablet y 530000 en
         * el iPad. En este dominio el fail-safe no puede ser desaparecer dinero (mismo
         * criterio que la config de impresoras, que no se pisa con un refresh fallido).
         *
         * El `OPEN` sí se sigue barriendo siempre: su duplicado no mueve un centavo
         * (`computeExpectedAmount` no lo suma), sólo pinta la apertura dos veces en el
         * detalle del corte, y dejar de limpiarlo convertiría este guard en una excusa
         * para no reconciliar nada.
         */
        private fun tiposABorrar(servidorConfirmaVentas: Boolean): List<String> =
            if (servidorConfirmaVentas) {
                SERVER_OWNED_EVENT_TYPES
            } else {
                listOf(CashDrawerEventType.OPEN.name)
            }
    }

    /**
     * 🔴 **DESDE CUÁNDO CUENTA ESTA CAJA, LEÍDO DEL PAYLOAD Y NO DE LA SESIÓN YA
     * PARSEADA. `0` = el server no lo dijo, o sea SIN COTA.**
     *
     * Espejo de iOS (`CashDrawerRepository.ventanaDeLaCaja(delPayload:)`, commit
     * `ca4aa65`), que lo resolvió bien desde el principio y aquí seguía abierto.
     *
     * [parseSessionFromApi] rellena `openedAt` con `now` cuando el campo falta, para que
     * la pantalla tenga algo que pintar y para que `getOpenSession` pueda ordenar. Ese
     * `now` sirve para PINTAR; como cota de la protección del cajón sería catastrófico:
     * una ventana "de ahora en adelante" deja fuera a TODOS los cobros pendientes —todos
     * se encolaron antes de "ahora"—, la protección se colapsa entera y cada venta
     * cobrada sin red desaparece del arqueo de golpe.
     *
     * Entre las dos degradaciones se elige la de siempre: **primero no desaparecer
     * dinero de la pantalla del cajero.** Sin cota se vuelve al comportamiento anterior
     * a la ventana, que como mucho deja vivo un cobro atorado de ayer; con la cota
     * inventada se pierde TODO lo cobrado sin red. Es el mismo criterio con el que
     * `PrintConfigRepository` conserva una config vieja antes que quedarse sin imprimir:
     * un campo que falta puede quitar una cota, nunca hacer desaparecer dinero.
     *
     * Hoy es latente —el server siempre manda `openedAt`
     * (`cash-drawer.mobile.service.ts`)— pero la degradación iba en la dirección
     * prohibida, así que se cierra igual.
     *
     * 🔴 Por eso el arreglo NO es tocar [parseTimestamp]: sus otros tres llamadores
     * necesitan el `now`. Un `openedAt` en 0 pondría la sesión en 1970 y rompería el
     * orden de `getOpenSession` y la ventana del corte; un evento en 0 se caería de
     * toda ventana. La decisión se resuelve AQUÍ, en el sitio de la ventana, que es el
     * único que quiere "sin cota".
     */
    internal fun ventanaDeLaCaja(sessionObj: JsonObject): Long =
        parseTimestampOrNull((sessionObj["openedAt"] ?: sessionObj["createdAt"])?.jsonPrimitive?.contentOrNull) ?: 0L

    /**
     * `null` = no se pudo leer (campo ausente, `null` explícito, o texto que no es una
     * fecha). Con qué rellenar NO es la misma respuesta en todos lados, así que la
     * decide quien llama: [parseTimestamp] pone `now` para pintar, [ventanaDeLaCaja]
     * pone `0` para no acotar.
     */
    private fun parseTimestampOrNull(value: String?): Long? {
        if (value == null) return null
        // Server sends full ISO-8601 with millis + Z ("2026-07-17T19:50:18.274Z").
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            } catch (_: Exception) {
                value.toLongOrNull()
            }
        }
    }

    /**
     * La fecha del server para PINTAR: si no se pudo leer, `now`. Lo usan la sesión y el
     * evento, donde un 0 sería una fecha de 1970 en pantalla y un orden roto.
     */
    private fun parseTimestamp(value: String?): Long =
        parseTimestampOrNull(value) ?: System.currentTimeMillis()
}
