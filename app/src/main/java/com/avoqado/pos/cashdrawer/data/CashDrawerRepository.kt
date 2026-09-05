package com.avoqado.pos.cashdrawer.data

import android.os.Build
import android.util.Log
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerStatus
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.core.data.network.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
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

/** Additive `/cash-drawer/sync` wire contract shared by the producer and its focused test. */
@Serializable
internal data class CashDrawerSyncEventDto(
    val type: String,
    val amount: Double,
    val staffId: String,
    val staffName: String,
    val orderId: String? = null,
    val createdAt: String,
    /** The exact local drawer identity; never replace it with the drawer open at replay time. */
    val sessionId: String,
    /** Stable Room id used by the server's `[venueId, localId]` idempotency key. */
    val localId: String,
)

@Serializable
internal data class CashDrawerSyncEventsRequest(val events: List<CashDrawerSyncEventDto>)

internal fun cashDrawerSyncEventJson(event: CashDrawerEventEntity): String = Json { encodeDefaults = false }.encodeToString(
    CashDrawerSyncEventsRequest.serializer(),
    CashDrawerSyncEventsRequest(
        events = listOf(
            CashDrawerSyncEventDto(
                type = event.type,
                amount = event.amountCents / 100.0,
                staffId = event.staffId,
                staffName = event.staffName,
                orderId = event.orderId,
                createdAt = java.time.Instant.ofEpochMilli(event.createdAt).toString(),
                sessionId = event.sessionId,
                localId = event.id,
            ),
        ),
    ),
)

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

/**
 * Qué hacer con un INGRESO, un RETIRO o un CIERRE de la cola, según lo que contestó el servidor.
 *
 * 🔴 Una APERTURA no pasa por aquí: su contrato es distinto y vive en [clasificarApertura].
 * Mezclarlas fue el defecto C2 — el 409 de una apertura significa «ya hay un turno abierto», que
 * NO es un rechazo, mientras que en un retiro sí lo es.
 */
internal enum class DestinoDeLaOperacion { CONFIRMADA, REINTENTAR, RECHAZADA }

/** Ya hay un turno de caja abierto en el negocio. Sobre una APERTURA significa «adopta el mío». */
internal const val CODIGO_CAJA_YA_ABIERTA = "CASH_SHIFT_ALREADY_OPEN"

/** El servidor está cerrando el turno anterior. TRANSITORIO: milisegundos, se reintenta. */
internal const val CODIGO_CIERRE_EN_PROCESO = "SHIFT_CLOSE_IN_PROGRESS"

/**
 * El `code` de negocio que el servidor pone en el cuerpo del error (`{message, code}`), o `null`
 * si no lo trae. PURA y tolerante: un cuerpo vacío, HTML de un proxy o JSON roto devuelven `null`
 * y la clasificación cae al comportamiento por código HTTP de siempre.
 *
 * 🔴 Se lee la LLAVE `code`, nunca se busca el texto dentro del mensaje. Un motivo que mencionara
 * «ya hay un turno abierto» en prosa no puede cambiar el destino de una operación de dinero —
 * es la misma trampa del `texto.contains("400")` que ya costó un retiro borrado.
 */
internal fun codigoDeNegocio(cuerpo: String): String? = try {
    Json { ignoreUnknownKeys = true }.parseToJsonElement(cuerpo)
        .jsonObject["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

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

/**
 * @param codigoDelServidor el `code` del cuerpo (ver [codigoDeNegocio]). 🔴 **Un 409 significa
 * cosas OPUESTAS según ese código**, y tratarlos igual cuesta dinero en las dos direcciones:
 * `SHIFT_CLOSE_IN_PROGRESS` dura milisegundos y descartarlo pierde la intención de abrir la
 * caja para siempre; `CASH_SHIFT_ALREADY_OPEN` sobre una apertura quiere decir que el turno YA
 * está abierto —normalmente porque mi propio POST sí llegó y la respuesta se perdió— y marcarlo
 * como rechazo dejaría al cajero con un aviso rojo por una caja que está perfectamente abierta.
 */
internal fun clasificarRespuestaDelServer(
    kind: String,
    code: Int,
    codigoDelServidor: String? = null,
): DestinoDeLaOperacion = when {
    code in 200..299 -> DestinoDeLaOperacion.CONFIRMADA
    // El código de negocio se lee ANTES del 409 genérico, que si no se lo tragaría.
    code == 409 && codigoDelServidor == CODIGO_CIERRE_EN_PROCESO -> DestinoDeLaOperacion.REINTENTAR
    // El 404 dice lo contrario según la operación: para un CIERRE es "ya estaba cerrada"
    // (nada que reintentar); para un movimiento es "aún no conozco esa caja", y descartarlo
    // borraría un retiro real.
    code == 404 -> if (kind == "CLOSE") DestinoDeLaOperacion.CONFIRMADA else DestinoDeLaOperacion.REINTENTAR
    code in RECHAZOS_DEFINITIVOS -> DestinoDeLaOperacion.RECHAZADA
    // Todo lo demás se reintenta: sin red (0), 5xx, 408 y 429 ("vas muy rápido" / "se agotó el
    // tiempo"), 401 (token vencido) y cualquier 4xx que no esté en la lista de arriba.
    else -> DestinoDeLaOperacion.REINTENTAR
}

/**
 * 🔴 EL CONTRATO REAL DE `POST /cash-drawer/open`, MEDIDO EN LAS DOS RAMAS DEL SERVIDOR (4-sep).
 *
 * La versión anterior de esta tarea se construyó sobre dos premisas FALSAS y por eso el camino
 * que de verdad corre no tenía ni una prueba:
 *
 * | Servidor | Ya hay una caja abierta | Respuesta |
 * |---|---|---|
 * | `develop` (el que se despliega con estas apps) | no | **201** con `cajaCreada: true` |
 * | `develop` | **sí** | **201 con la caja EXISTENTE** y `cajaCreada: false` — el servidor LIGA, no rebota |
 * | `develop` | carrera de dos aperturas | 409 `CASH_SHIFT_ALREADY_OPEN` |
 * | `develop` | cierre de turno en curso | 409 `SHIFT_CLOSE_IN_PROGRESS` (transitorio) |
 * | `main` (producción HOY) | sí | **409 SIN `code`** |
 * | `main` | no | 201 **sin** `cajaCreada` (campo ausente) |
 *
 * De ahí los cinco desenlaces:
 *  - [CREADA] 201 con `cajaCreada: true` **o el campo ausente**: mi apertura creó la caja.
 *  - [LIGADA] 201 con `cajaCreada: false`: el servidor me ligó a una caja que YA estaba. No es
 *    «abriste»: es adoptar la de alguien más, y eso se le DICE al cajero (su fondo no se registró).
 *  - [PREGUNTAR_POR_LA_SUYA] cualquier 409 que no sea el transitorio — **con código o sin él**,
 *    porque producción no manda ninguno: se consulta `GET /current` y se adopta lo que conteste.
 *  - [REINTENTAR] sin red, 5xx, 401, 404, 408, 429 y el 409 `SHIFT_CLOSE_IN_PROGRESS`.
 *  - [RECHAZADA] 400 / 403 / 422: el servidor nunca la va a aceptar. **La apertura NO se borra.**
 */
internal enum class DesenlaceDeLaApertura { CREADA, LIGADA, PREGUNTAR_POR_LA_SUYA, REINTENTAR, RECHAZADA }

/** Los ÚNICOS códigos con los que una APERTURA no va a funcionar nunca. El 409 NO está: liga o pregunta. */
private val RECHAZOS_DE_APERTURA = setOf(400, 403, 422)

/**
 * 🔴 Función PURA: es la única forma de probar fila por fila la tabla de arriba sin un servidor
 * que la produzca.
 *
 * @param cajaCreada lo que dijo el cuerpo (ver [leerCajaCreada]). `null` = el campo no venía, que
 * es lo que contesta producción hoy y significa CREADA: ese servidor rebota con 409 cuando ya hay
 * una caja, así que un 201 suyo sólo puede ser una caja nueva.
 */
internal fun clasificarApertura(
    code: Int,
    codigoDelServidor: String? = null,
    cajaCreada: Boolean? = null,
): DesenlaceDeLaApertura = when {
    code in 200..299 -> if (cajaCreada == false) DesenlaceDeLaApertura.LIGADA else DesenlaceDeLaApertura.CREADA
    // Transitorio: dura milisegundos. Descartarlo perdería la intención de abrir para siempre.
    code == 409 && codigoDelServidor == CODIGO_CIERRE_EN_PROCESO -> DesenlaceDeLaApertura.REINTENTAR
    // 🔴 CUALQUIER otro 409, con código o SIN él. Producción (`main`) contesta 409 pelón y la
    // versión anterior lo leía como RECHAZO: bloqueaba la caja de por vida por una respuesta
    // que sólo quiere decir «ya hay un turno abierto, adopta el mío».
    code == 409 -> DesenlaceDeLaApertura.PREGUNTAR_POR_LA_SUYA
    code in RECHAZOS_DE_APERTURA -> DesenlaceDeLaApertura.RECHAZADA
    // 404 incluido: el servidor todavía no conoce nada de esta caja.
    else -> DesenlaceDeLaApertura.REINTENTAR
}

/**
 * `data.cajaCreada` del cuerpo de `POST /open`, o `null` si no viene / no se puede leer.
 *
 * 🔴 Se lee la LLAVE, nunca el texto. Y `null` NO es `false`: un servidor viejo que no manda el
 * campo abrió una caja nueva (rebota con 409 si ya había una), así que confundirlos le sacaría al
 * cajero un aviso de «adopté la caja de otro» en cada apertura normal contra producción.
 */
internal fun leerCajaCreada(cuerpo: String): Boolean? = try {
    val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(cuerpo).jsonObject
    val obj = (root["data"] as? JsonObject) ?: root
    obj["cajaCreada"]?.jsonPrimitive?.booleanOrNull
} catch (_: Exception) {
    null
}

/** Pesos con dos decimales para un texto que lee una persona. Espejo de `formatCurrency`. */
internal fun pesosDeCentavos(cents: Int): String = "$" + String.format(java.util.Locale.US, "%,.2f", cents / 100.0)

/**
 * 🔴 ADOPTAR LA CAJA DE OTRO NUNCA ES SILENCIOSO (hallazgo I1).
 *
 * El servidor liga en vez de rebotar, y **el fondo de lo que ya estaba NUNCA se pisa**: el cajero
 * que contó $2,000 se queda operando sobre una caja de $500 y sólo se entera al cerrar, como un
 * sobrante de $1,500 sin explicación. La regla del proyecto es explícita: lo tardío no se descarta
 * en silencio.
 *
 * Función PURA para que el texto sea IDÉNTICO en Android y en iOS y se pueda probar sin pantalla.
 */
internal fun textoDeAdopcion(quien: String, hora: String, fondoServidorCents: Int, fondoLocalCents: Int): String =
    if (fondoServidorCents == fondoLocalCents) {
        "Se adoptó la caja abierta por $quien."
    } else {
        "Ya había una caja abierta por $quien desde las $hora con fondo ${pesosDeCentavos(fondoServidorCents)}. " +
            "Tu apertura de ${pesosDeCentavos(fondoLocalCents)} no se registró como una caja nueva."
    }

/**
 * Una operación del cajón que este aparato YA hizo en local y que el server aún no confirmó.
 * `kind` = OPEN | CLOSE | PAY_IN | PAY_OUT. `localId` es la llave idempotente del evento (PAY_*)
 * y, en `OPEN`, el id del evento de apertura local (el server no lee llave al abrir).
 *
 * 🔴 `OPEN` entró después (Task 8b, 4-sep). Una cola guardada en un aparato de la calle sólo trae
 * CLOSE/PAY_IN/PAY_OUT y se lee EXACTAMENTE igual: no se agregó ningún campo, sólo un valor más
 * de `kind`. Hay prueba de eso.
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
    /**
     * 🔴 Cuándo se intentó mandar esto por primera vez **CON RED** sin conseguirlo (P2-4, y su
     * corrección en la ronda 2).
     *
     * Sólo lo escribe la APERTURA, y sirve para una cosa: medir cuánto llevan los cobros
     * esperándola. Vive en disco —no en memoria— porque la espera tiene que sobrevivir a que el
     * cajero mate la app, que es justo lo que pasa en un mostrador.
     *
     * 🔴 «CON RED» es literal y es lo que arregló la ronda 2: un fallo de red devuelve el mismo
     * `Reintentar` que un 5xx, así que abrir la caja con el WiFi apagado fechaba esto milisegundos
     * después y el presupuesto de media hora se consumía **offline** — justo en el local con mala
     * red que el tope decía proteger. Ahora sólo lo fecha un intento hecho con red y servidor
     * alcanzables (`ConnectivityMonitor.isFullyConnected`).
     *
     * Nulo = todavía no se ha intentado CON RED, o ya se confirmó. Campo con default: una cola
     * guardada por una versión anterior se lee igual (hay prueba).
     */
    val primerReintentoEn: Long? = null,
    /**
     * 🔴 El ÚLTIMO intento fallido **CON RED** de esta apertura (N-P3-4, ronda 2).
     *
     * Sirve para el backoff, no para el tope: desde que el outbox de mesas también dispara el
     * replay del cajón, cada intent encolado producía otro `POST /open` mientras la apertura
     * estuviera atorada — un POST por artículo agregado a una mesa. Con esto, la apertura no se
     * vuelve a mandar hasta que pasen [BACKOFF_DE_LA_APERTURA_MS].
     *
     * 🔴 También se fecha SÓLO con red, y no es un detalle: un fallo sin red nunca tocó al
     * servidor, así que no hay nada de lo que hacer backoff — y aplicarlo igual retrasaría hasta
     * medio minuto la apertura en el momento que más importa, el instante en que vuelve el WiFi.
     * Esa es la única diferencia frente a la letra del fallo, y está aquí escrita a propósito.
     */
    val ultimoReintentoEn: Long? = null,
)

/**
 * En qué estado está la APERTURA de la caja que se está viendo, frente al servidor.
 *
 * 🔴 Existe porque una caja que sólo vive en el aparato se veía IDÉNTICA a una sana (P2 #4 de la
 * auditoría de apps). La regla del workspace es explícita: sin red es un estado NORMAL y se DICE,
 * nunca en rojo. Aquí no hay red que consultar — lo contesta la COLA, que es la verdad de qué
 * llegó y qué no.
 */
enum class EstadoDeLaApertura {
    /** El servidor ya conoce esta caja (o no hay caja que mirar). */
    CONFIRMADA,

    /** Su `OPEN` sigue en la cola: se sincroniza sola al volver la red. Banda ámbar. */
    PENDIENTE,

    /** El servidor la rechazó de plano. De eso ya avisa el aviso ROJO, que además ofrece Reintentar. */
    RECHAZADA,
}

/** Función PURA: el estado visible de la apertura de [sessionId] según la cola. */
internal fun estadoDeAperturaVisible(cola: List<PendingDrawerOp>, sessionId: String?): EstadoDeLaApertura {
    if (sessionId == null) return EstadoDeLaApertura.CONFIRMADA
    val apertura = cola.firstOrNull { it.kind == "OPEN" && it.sessionId == sessionId }
        ?: return EstadoDeLaApertura.CONFIRMADA
    return if (apertura.rechazadaEn != null) EstadoDeLaApertura.RECHAZADA else EstadoDeLaApertura.PENDIENTE
}

/**
 * 🔴 EL CAJÓN ES BARRERA DE LOS COBROS — y el orden NO es cosmético (F1, medido el 5-sep-2026).
 *
 * Medido en una Samsung SM-X133: al volver el WiFi, `PaymentSyncService` reintentaba sus cobros y
 * la cola del cajón NO se reproducía sola. Los cobros en efectivo llegaban al servidor ANTES que el
 * `OPEN`, así que nacían con `shiftId = null` y sin `CASH_SALE` en la caja — y el barrido del
 * servidor no los recupera después, porque el `openedAt` que acaba registrando es la hora del
 * replay y no la real. Un cobro que llega sin caja abierta queda huérfano **para siempre**.
 *
 * Por eso: mientras una APERTURA VIVA siga esperando al servidor, los cobros de ese ciclo esperan
 * al siguiente. Es un ciclo de retraso contra dinero que nadie puede reatribuir.
 *
 * 🔴 Una apertura RECHAZADA (`rechazadaEn != null`) NO es barrera, a propósito: el servidor nunca
 * la va a aceptar por su cuenta, así que bloquear con ella congelaría la cola de cobros **para
 * siempre** — y esa apertura ya grita en rojo en la pantalla de Caja, con su propio «Reintentar».
 * Un cobro mal atribuido se puede investigar; un cobro que nunca se manda no existe en ningún
 * reporte. La barrera vale exactamente lo que dura el defecto que la motivó, ni un caso más.
 *
 * 🔴 Y POR ESO MISMO LA BARRERA TIENE TOPE: ver [TOPE_DE_LA_BARRERA_MS] y [estadoDeLosCobros].
 */
internal fun losCobrosPuedenSalir(cola: List<PendingDrawerOp>, ahora: Long): Boolean =
    estadoDeLosCobros(cola, ahora) != EstadoDeLosCobros.ESPERANDO_LA_APERTURA

/**
 * 🔴 CUÁNTO PUEDEN ESPERAR LOS COBROS A QUE LA APERTURA LLEGUE — media hora, y ni un minuto más.
 *
 * Se mide desde el primer intento fallido **CON RED** de la apertura
 * ([PendingDrawerOp.primerReintentoEn]), no desde que se encoló: sin red no hay nada que
 * reprochar —el servidor ni se enteró— y contar esa espera gastaría el presupuesto entero durante
 * un apagón de WiFi, que es exactamente el defecto N-P2-1 de la re-revisión del 5-sep-2026.
 *
 * ⚠️ RESIDUO DECLARADO, porque el comentario no puede prometer más que el código: lo que se guarda
 * es un INSTANTE de arranque, no un cronómetro de tiempo conectado. Una vez que el reloj arrancó
 * con red, sigue corriendo aunque después se caiga el WiFi. Es la letra del fallo del controlador
 * y acota el daño al caso que importaba (abrir la caja sin red y quedarse sin red horas); medir
 * sólo los minutos conectados exigiría acumular tiempo en cada intento, y no se hizo.
 *
 * El porqué del tope (P2-4, revisión independiente del 5-sep-2026): hay clases de error que el
 * diseño trata como transitorias PARA SIEMPRE —un 409 cuyo `/current` viene vacío (decisión I3),
 * un 401, un 429, un timeout—. En ese estado la red está bien, el banner de «sin conexión» NO
 * sale, y el cajero, que está en Cobrar y no en Caja, sólo ve subir un contador. Sin tope, sus
 * ventas del día se quedan en el aparato indefinidamente.
 *
 * La comparación que decide el número: **una venta que NUNCA llega al servidor es peor que una
 * venta sin turno.** La segunda se ve en el dashboard como «fuera de turno» y se reatribuye; la
 * primera no existe en ningún reporte. Media hora es más de lo que dura cualquier bache real de
 * WiFi y menos que un turno.
 *
 * La apertura NO se descarta al levantar la barrera: sigue en la cola y se reintenta igual.
 */
internal const val TOPE_DE_LA_BARRERA_MS = 30L * 60 * 1000

/**
 * 🔴 CADA CUÁNTO SE PUEDE REINTENTAR UNA APERTURA ATORADA — medio minuto (N-P3-4).
 *
 * Desde que el outbox de mesas reproduce el cajón antes de drenar (P2-3), CADA intent encolado
 * dispara un replay: con una apertura atorada, agregar diez artículos a una mesa mandaba diez
 * `POST /open` (más sus `GET /current`) contra un servidor que ya está contestando mal. El
 * backoff no cambia ninguna barrera ni ninguna decisión de dinero — sólo deja de repetir en vano.
 *
 * Se mide desde el último intento **con red** ([PendingDrawerOp.ultimoReintentoEn]): un fallo sin
 * red no tocó al servidor, así que al volver el WiFi la apertura sale de inmediato. Sin esa
 * condición, el caso que esta tarea existe para cerrar —abrir sin red y que la apertura vuele en
 * cuanto vuelva— se retrasaría hasta 30 s.
 */
internal const val BACKOFF_DE_LA_APERTURA_MS = 30L * 1000

/**
 * Función PURA: ¿esta apertura acaba de fallar CON RED, y por tanto toca esperar antes de volver
 * a mandarla? El reloj entra por parámetro.
 */
internal fun aperturaEnBackoff(op: PendingDrawerOp, ahora: Long): Boolean {
    val ultimo = op.ultimoReintentoEn ?: return false
    return ahora - ultimo < BACKOFF_DE_LA_APERTURA_MS
}

/**
 * Qué está pasando con los cobros que dependen de una apertura que aún no llega al servidor.
 *
 * 🔴 Existe para que la app pueda DECIRLO. La barrera es correcta y es invisible: el cajero ve
 * su contador de pendientes subir y lo lee como «no hay internet», cuando la red está bien y lo
 * que falta es su caja. Un estado que sólo aparece en un `Log.w` no es un estado que el negocio
 * pueda resolver. Ver [textoDeCobrosRetenidos].
 */
enum class EstadoDeLosCobros {
    /** Nadie está esperando: no hay una apertura viva en la cola. */
    LIBRES,

    /** Hay una apertura viva y aún dentro del tope: los cobros esperan al siguiente ciclo. */
    ESPERANDO_LA_APERTURA,

    /** La apertura pasó el tope: los cobros ya salen SIN caja, y eso también se dice. */
    ENVIADOS_SIN_CAJA,
}

/** Función PURA: en qué estado está la espera de los cobros. El reloj entra por parámetro. */
internal fun estadoDeLosCobros(cola: List<PendingDrawerOp>, ahora: Long): EstadoDeLosCobros {
    val aperturas = cola.filter { it.kind == "OPEN" && it.rechazadaEn == null }
    if (aperturas.isEmpty()) return EstadoDeLosCobros.LIBRES
    // 🔴 TODAS, no la primera (N-P3-5). Con `firstOrNull`, una apertura vieja y vencida mandaba
    // sobre la cola entera: el cajero abría una caja NUEVA y sus cobros salían igual sin caja,
    // porque el reloj que decidía era el de la anterior. Basta UNA apertura dentro del plazo para
    // que los cobros esperen — el tope es un permiso de último recurso, no un interruptor que se
    // queda encendido.
    val vencida = { op: PendingDrawerOp ->
        // Nunca se ha intentado CON RED: la espera ni siquiera ha empezado.
        op.primerReintentoEn?.let { ahora - it >= TOPE_DE_LA_BARRERA_MS } ?: false
    }
    return if (aperturas.all(vencida)) {
        EstadoDeLosCobros.ENVIADOS_SIN_CAJA
    } else {
        EstadoDeLosCobros.ESPERANDO_LA_APERTURA
    }
}

/**
 * Lo que la banda de arriba dice, o `null` si no hay nada que decir.
 *
 * 🔴 Ámbar, nunca rojo: esto no es una falla, es el estado normal de un mostrador con WiFi malo
 * — la misma regla que el banner de «sin conexión». Y las dos frases dicen QUÉ pasó con el dinero,
 * que es lo único que el cajero necesita saber para decidir si llama a alguien.
 */
internal fun textoDeCobrosRetenidos(estado: EstadoDeLosCobros): String? = when (estado) {
    EstadoDeLosCobros.LIBRES -> null
    EstadoDeLosCobros.ESPERANDO_LA_APERTURA ->
        "La apertura de caja aún no llega al servidor: los cobros se enviarán en cuanto llegue"
    EstadoDeLosCobros.ENVIADOS_SIN_CAJA ->
        "Cobros enviados sin caja: la apertura sigue pendiente"
}

/**
 * 🔴 ¿La caja que el servidor devolvió al LIGAR es la mía, que llegó por otro camino? (F2)
 *
 * Medido el 5-sep-2026: dos replays simultáneos mandaron dos `POST /open`; el segundo recibió
 * `cajaCreada:false` sobre la caja que el primero acababa de crear, y la pantalla dijo «Esta caja
 * ya estaba abierta · Se adoptó la caja abierta por Main Owner» sobre la caja del PROPIO cajero.
 * Con el candado de un solo vuelo ese caso desaparece, pero el mismo cuadro se repite cuando el
 * `OPEN` sí aterrizó y su respuesta se perdió: el reintento recibe `cajaCreada:false` sobre mi
 * propia caja.
 *
 * Tres condiciones, y la del FONDO es la que la vuelve razonable: dos tablets idénticas del mismo
 * local comparten `deviceName` («samsung SM-X133» es el MODELO, no una identidad de aparato) y
 * pueden compartir la sesión del dueño, así que mirar sólo aparato y persona callaría adopciones
 * reales a diario. Con el fondo dentro, la coincidencia exige además que las dos hayan abierto con
 * el MISMO monto.
 *
 * 🔴 RESIDUO DECLARADO, y se escribe tal cual porque prometer más sería mentir: **dos aparatos del
 * mismo modelo, con la misma cuenta y el mismo fondo comparten caja SIN aviso.** Con fondos
 * redondos ($500 es el fondo estándar) eso no es exótico. No se puede distinguir desde el cliente:
 * haría falta que el `POST /open` llevara una llave idempotente y que el servidor guardara el
 * aparato que la mandó — es el pendiente **N1**, que el brief dejó fuera a propósito porque toca
 * el servidor. Cuando N1 exista, esta regla se cambia por «¿es MI apertura?» y el residuo
 * desaparece; mientras tanto Android ya tiene un `deviceId` estable (`SyncOutbox.deviceId`,
 * el del header `X-Device-Id`) listo para ocupar el lugar de `deviceName`.
 *
 * Lo que SÍ está garantizado hoy: si el fondo difiere —que es justo cuando el dinero importa— el
 * aviso sale siempre.
 */
internal fun esMiPropiaCaja(
    server: CashDrawerSessionEntity,
    op: PendingDrawerOp,
    miAparato: String,
    miStaffId: String,
): Boolean =
    server.deviceName == miAparato &&
        server.openedByStaffId == miStaffId &&
        server.startingAmountCents == op.amountCents

/** Los movimientos que el servidor deduplica por `localId`. Un CLOSE no lleva llave. */
private val TIPOS_CON_LLAVE = setOf("PAY_IN", "PAY_OUT")

/** ¿Es una entrada de una versión anterior a la idempotencia, sin llave? */
internal fun necesitaLlaveLegada(op: PendingDrawerOp): Boolean =
    op.kind in TIPOS_CON_LLAVE && op.localId.isNullOrBlank()

/**
 * 🔴 UN MOVIMIENTO SIN `localId` NUNCA SE DESCARTA EN SILENCIO NI SE MANDA VACÍO (P2 #6).
 *
 * Las colas escritas por versiones anteriores a la idempotencia traen ingresos y retiros sin
 * llave. Android los descartaba con un `return CONFIRMADA` —dinero que se evaporaba sin que nadie
 * se enterara— y iOS los mandaba con `localId: ""`, que el servidor rechaza con 400. Dos formas
 * distintas de perder el mismo dinero.
 *
 * La llave se DERIVA de lo que la entrada ya tiene, así que es la misma en cada reintento y el
 * servidor puede deduplicar: `legacy-` + sha256(caja|tipo|hora|monto). Determinista a propósito —
 * un UUID nuevo por intento convertiría un reintento en un segundo retiro.
 */
internal fun localIdLegado(op: PendingDrawerOp): String =
    "legacy-" + sha256Hex("${op.sessionId}|${op.kind}|${op.at}|${op.amountCents}").take(32)

/** La cola con las llaves legadas ya puestas. Las entradas que ya tienen llave NO se tocan. */
internal fun conLlavesLegadas(cola: List<PendingDrawerOp>): List<PendingDrawerOp> =
    cola.map { if (necesitaLlaveLegada(it)) it.copy(localId = localIdLegado(it)) else it }

private fun sha256Hex(texto: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(texto.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * El aviso durable de que una apertura terminó ADOPTANDO la caja de alguien más (I1).
 *
 * Vive en disco, no en memoria: el cajero tiene que enterarse aunque la app se reinicie entre la
 * adopción y el momento en que mira la pantalla.
 */
@Serializable
internal data class AvisoDeAdopcion(
    /** El id de la caja del SERVIDOR que se adoptó. Es la identidad del aviso. */
    val sessionId: String,
    val openedByName: String,
    val openedAtMillis: Long,
    val fondoServidorCents: Int,
    val fondoLocalCents: Int,
)

@Singleton
class CashDrawerRepository @Inject constructor(
    private val dao: CashDrawerDao,
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val pendingCashSales: PendingCashSales,
    /**
     * 🔴 La conectividad ENTRA, no se adivina (ronda 2, N-P2-1). El reloj del tope de la barrera
     * y el backoff de la apertura sólo cuentan intentos hechos con red y servidor alcanzables:
     * `isFullyConnected` es exactamente esa pregunta, y es la MISMA fuente que usan el banner de
     * «sin conexión» y el outbox, así que la app no puede contarse dos historias distintas.
     */
    private val conectividad: ConnectivityMonitor,
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
        pedirCajaAbiertaAlServidor()
    }

    /**
     * Lo que contestó `GET /cash-drawer/current`.
     *
     * 🔴 Son TRES respuestas, no dos, y colapsarlas cuesta dinero: quien reproduce una apertura
     * tiene que distinguir «el servidor DICE que no hay caja abierta» (contradicción con el 409
     * que acaba de dar: hay que avisarle a alguien) de «no se pudo preguntar» (sin red, 5xx,
     * cuerpo ilegible → reintentar). Con un solo `null` las dos se veían iguales, y ésa es
     * exactamente la diferencia entre un aviso rojo falso y perder la apertura para siempre.
     */
    private sealed interface CajaDelServidor {
        /** El servidor tiene una caja abierta y ya quedó adoptada en Room. */
        data class Adoptada(val session: CashDrawerSessionEntity) : CajaDelServidor

        /** 2xx legible que dice explícitamente que NO hay caja abierta. */
        data object NoHayNinguna : CajaDelServidor

        /** No se pudo saber: 4xx/5xx, cuerpo vacío o ilegible. */
        data object NoSeSupo : CajaDelServidor
    }

    /**
     * `GET /cash-drawer/current`, adoptando lo que conteste.
     *
     * Propaga la excepción de red a propósito: `syncFromApi` ya la atrapa y el replay la traduce.
     */
    private suspend fun pedirCajaAbiertaAlServidor(sesionLocal: String? = null): CajaDelServidor {
        val request = Request.Builder()
            .url("$baseUrl/current")
            .get()
            .build()

        val (code, body) = withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.code to (response.body?.string() ?: "")
        }

        if (code !in 200..299 || body.isEmpty()) return CajaDelServidor.NoSeSupo
        return try {
            val root = json.decodeFromString<JsonObject>(body)
            val sessionObj = parseSessionEnvelope(root)
            if (sessionObj == null) {
                CajaDelServidor.NoHayNinguna
            } else {
                // Events live inside the session payload (fallback: top-level).
                val session = adoptServerSession(sessionObj, root["events"]?.jsonArray, sesionLocal)
                Log.d(TAG, "✅ Caja del server sincronizada: ${session.id}")
                CajaDelServidor.Adoptada(session)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Parse current session error: ${e.message}")
            CajaDelServidor.NoSeSupo
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
        /**
         * La caja LOCAL que se está adoptando, si la hay. 🔴 Va aquí porque la mudanza de la cola
         * y de la fila local tiene que ocurrir **también cuando esa caja ya se cerró en local**
         * (hallazgo I2): antes sólo se promovían las abiertas, así que abrir sin red por la mañana
         * y cerrar sin red por la noche dejaba entrar la caja del servidor como una SEGUNDA caja
         * abierta y vacía —la caja fantasma— con la local duplicada en el historial.
         */
        sesionLocal: String? = null,
    ): CashDrawerSessionEntity {
        val parsed = parseSessionFromApi(sessionObj)
        // 🔴 La cola se muda ANTES de mirar nada: así el cierre encolado de la caja local queda
        // nombrando ya a la caja del servidor y la guarda de abajo lo encuentra. Sin esto, un
        // cierre encolado con el id LOCAL no frenaba la adopción y el conteo del cajero se perdía.
        if (sesionLocal != null) reapuntarPendientes(sesionLocal, parsed.id)
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

        // Las cajas locales que tienen que MUDARSE a la del servidor: las abiertas de siempre, más
        // —hallazgo I2— la que se está adoptando aunque ya esté CERRADA en local.
        val aPromover = if (venueId.isEmpty()) emptyList() else buildList {
            if (server.status == CashDrawerStatus.OPEN.name) {
                addAll(dao.getOpenSessions(venueId).filter { it.id != server.id })
            }
            sesionLocal?.takeIf { it != server.id && none { s -> s.id == it } }
                ?.let { id -> dao.getSession(id)?.let { add(it) } }
        }
        if (aPromover.isNotEmpty()) {
            aPromover
                .forEach { provisional ->
                    Log.d(TAG, "⬆️ Promoviendo caja provisional ${provisional.id} → ${server.id}")
                    dao.repointEventsFrom(provisional.id, server.id, server.openedAt)
                    // La cola durable nombra a la caja igual que Room, o sus movimientos viajarían
                    // con un id que el servidor no conoce. Ver [reapuntarPendientes].
                    reapuntarPendientes(provisional.id, server.id)
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
        // Lo que quedó pendiente de la caja ANTERIOR se manda primero y EN ORDEN (su apertura, sus
        // movimientos, su cierre). Antes esto vivía dentro de `fireApiOpen` y sólo buscaba cerrar la
        // caja previa en el server; ahora también tiene que aterrizar su APERTURA, o el cierre
        // encolado no tendría contra qué caja mandarse. Se hace ANTES de crear la sesión local para
        // que el replay no se cruce con la caja que estamos abriendo.
        reproducirPendientes()

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

        // 🔴 LA INTENCIÓN DE ABRIR SE PERSISTE **ANTES** DE TOCAR LA RED (Task 8b, 4-sep).
        //
        // Antes se disparaba UN POST y nada más: si el proceso moría entre el toque y la
        // respuesta —o simplemente no había red— el aparato se quedaba con su caja local y el
        // servidor sin ninguna, así que los cobros del día nacían con `shiftId = null` y quedaban
        // FUERA de todo turno. Encolar en el `catch` habría sido tarde: el `catch` no corre si el
        // proceso ya murió. Ahora la apertura entra a la MISMA cola durable que los ingresos, los
        // retiros y el cierre, y se reproduce en cada sync y al entrar a Caja.
        val op = PendingDrawerOp("OPEN", session.id, startingAmountCents, null, event.id, System.currentTimeMillis())
        val quedoGuardada = encolar(op)

        // 🔴 YA NO SE DISPARA UN POST DIRECTO (hallazgo C1). El disparo inmediato se saltaba la
        // cola, así que la apertura de la caja NUEVA salía antes que el cierre no aterrizado de la
        // ANTERIOR: el servidor ligaba las dos y el arqueo de la vieja quedaba firmado con el
        // dinero de la nueva. Se reproduce la cola, que es FIFO y se DETIENE en lo que no confirme.
        // Esta apertura es la última por `at`, así que sale al final — o no sale, que es lo correcto.
        //
        // 🔴 Y `ademas` va SÓLO si el disco falló (P3-4). Si la apertura quedó guardada, la cola ya
        // la tiene y volver a inyectarla podía mandarla DOS veces: el replay del sincronizador pudo
        // ganar el candado mientras tanto, confirmarla y quitarla de la cola, y entonces `ademas`
        // la resucitaba. Un POST duplicado es literalmente F2 — el defecto que esta tarea existe
        // para cerrar—, así que ningún camino puede seguir pudiendo mandarlo.
        val adoptada = reproducirPendientes(ademas = if (quedoGuardada) null else op).adoptadas[session.id]
        return adoptada
            ?: dao.getSession(session.id)
            // 🔴 El replay del sincronizador pudo confirmar ESTA MISMA apertura mientras tanto y
            // mudar la caja al id del servidor: la fila local ya no existe con su id viejo. La caja
            // abierta del venue ES ésa. Sin este eslabón se devolvería la caja provisional, con un
            // id y una hora que el servidor ya no usa — el mismo síntoma de F2, sin el POST de más.
            ?: getOpenSession()
            ?: session
    }

    /** Lo que pasó con UNA apertura. `ligada = true` ⇒ el servidor NO creó mi caja: adopté la suya. */
    internal sealed interface ResultadoDeLaApertura {
        data class Adoptada(val session: CashDrawerSessionEntity, val ligada: Boolean) : ResultadoDeLaApertura
        data object Reintentar : ResultadoDeLaApertura
        data object Rechazada : ResultadoDeLaApertura
    }

    /**
     * Manda UNA apertura al servidor y traduce su respuesta con el contrato real
     * ([clasificarApertura]). Es la MISMA función para `openSession` y para el replay.
     */
    private suspend fun enviarApertura(op: PendingDrawerOp): ResultadoDeLaApertura {
        val (code, body) = try {
            val requestBody = json.encodeToString(
                OpenDrawerRequest.serializer(),
                OpenDrawerRequest(startingAmount = op.amountCents / 100.0, deviceName = deviceName),
            ).toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$baseUrl/open").post(requestBody).build()
            withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API open session error: ${e.message}")
            return ResultadoDeLaApertura.Reintentar
        }

        return when (clasificarApertura(code, codigoDeNegocio(body), leerCajaCreada(body))) {
            DesenlaceDeLaApertura.CREADA -> {
                Log.d(TAG, "✅ El servidor creó la caja de esta apertura")
                adoptarDelCuerpo(op, body, ligada = false) ?: preguntarPorLaCajaDelServidor(op)
            }
            // 🔴 El servidor LIGÓ: la caja que devuelve NO es la que se pidió abrir. Se adopta —el
            // dinero está donde debe— pero con aviso, porque el fondo tecleado no quedó registrado.
            DesenlaceDeLaApertura.LIGADA -> {
                Log.w(TAG, "🔗 El servidor ligó esta apertura a una caja que ya estaba abierta")
                adoptarDelCuerpo(op, body, ligada = true) ?: preguntarPorLaCajaDelServidor(op)
            }
            DesenlaceDeLaApertura.PREGUNTAR_POR_LA_SUYA -> {
                Log.w(TAG, "🔁 El servidor ya tiene un turno de caja abierto ($code): se adopta el suyo")
                preguntarPorLaCajaDelServidor(op)
            }
            DesenlaceDeLaApertura.REINTENTAR -> {
                Log.w(TAG, "🔁 Apertura sin confirmar ($code); sigue en cola — $body")
                ResultadoDeLaApertura.Reintentar
            }
            // 🔴 Se MARCA, no se borra (hallazgo I4): la caja existe en el aparato y su cierre no
            // puede mandarse. El aviso trae «Reintentar», nunca «Ya lo vi».
            DesenlaceDeLaApertura.RECHAZADA -> {
                Log.e(TAG, "🛑 Apertura RECHAZADA por el server ($code) — $body")
                marcarRechazada(op, mensajeDeRechazo(code, body))
                ResultadoDeLaApertura.Rechazada
            }
        }
    }

    /** Adopta la caja que vino en el cuerpo del `POST /open`. `null` = cuerpo ilegible. */
    private suspend fun adoptarDelCuerpo(op: PendingDrawerOp, body: String, ligada: Boolean): ResultadoDeLaApertura? {
        val session = try {
            parseSessionEnvelope(json.decodeFromString<JsonObject>(body))
                ?.let { adoptServerSession(it, sesionLocal = op.sessionId) }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Parse open session error: ${e.message}")
            null
        } ?: return null
        if (ligada) anotarAdopcion(op, session)
        return ResultadoDeLaApertura.Adoptada(session, ligada)
    }

    /**
     * `GET /cash-drawer/current` y adopción de lo que conteste. Es la salida del 409
     * `CASH_SHIFT_ALREADY_OPEN`.
     *
     * 🔴 Un 2xx que dice que NO hay caja abierta es la única forma de RECHAZO aquí, y es una
     * contradicción real (el servidor acaba de decir que ya hay un turno abierto y ahora dice que
     * no): se marca para que alguien lo vea, nunca se borra en silencio. Todo lo demás —sin red,
     * 5xx, un cuerpo ilegible— es REINTENTAR: no sabemos nada y perder la apertura sería peor.
     */
    private suspend fun preguntarPorLaCajaDelServidor(op: PendingDrawerOp): ResultadoDeLaApertura {
        val respuesta = try {
            pedirCajaAbiertaAlServidor(sesionLocal = op.sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "❌ No se pudo consultar la caja del server: ${e.message}")
            return ResultadoDeLaApertura.Reintentar
        }
        return when (respuesta) {
            is CajaDelServidor.Adoptada -> {
                anotarAdopcion(op, respuesta.session)
                ResultadoDeLaApertura.Adoptada(respuesta.session, ligada = true)
            }
            // 🔴 REINTENTAR, no rechazo (hallazgo I3). «El servidor dijo que ya había un turno y su
            // /current no trae ninguno» es casi siempre una carrera benigna —otro aparato cerró
            // entre mi 409 y mi GET— y marcarla congelaba la caja para siempre, con el dinero ya
            // dentro del cajón. Quedarse atorado es ruidoso y se arregla; perder la apertura no.
            CajaDelServidor.NoHayNinguna -> {
                Log.w(TAG, "🔁 El server dijo que el turno ya estaba abierto y su /current no trae ninguno: se reintenta")
                ResultadoDeLaApertura.Reintentar
            }
            CajaDelServidor.NoSeSupo -> ResultadoDeLaApertura.Reintentar
        }
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

        // Compatibility push: the server deliberately drops client CASH_SALE because the
        // authoritative payment path already creates it. The batch shape still mirrors the
        // exact local drawer id so this producer can never erase identity from the contract.
        try {
            val body = cashDrawerSyncEventJson(event)
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

    /**
     * Guarda la operación y contesta si de verdad QUEDÓ guardada.
     *
     * 🔴 El booleano existe para el hallazgo P3-4: `openSession` pasaba su apertura como `ademas`
     * SIEMPRE, y `reproducirPendientesYaConElCandado` la re-añade cuando ya no está en la cola
     * guardada — que es exactamente lo que pasa si el replay del sincronizador ganó el candado y
     * acaba de confirmarla. Resultado: un SEGUNDO `POST /open`, que es el defecto F2 otra vez, por
     * la puerta de al lado. Ahora el respaldo `ademas` se usa SÓLO cuando el disco falló de
     * verdad, que es el único caso para el que se escribió.
     */
    private fun encolar(op: PendingDrawerOp): Boolean = synchronized(candadoDeLaCola) {
        val lista = pendientes().filter { !mismaOperacion(it, op) } + op
        guardarPendientes(lista)
        pendientes().any { mismaOperacion(it, op) }
    }

    private fun quitar(op: PendingDrawerOp) = synchronized(candadoDeLaCola) {
        guardarPendientes(pendientes().filter { !mismaOperacion(it, op) })
    }



    /**
     * 🔴 LA COLA TAMBIÉN SE MUDA CUANDO LA CAJA ADOPTA EL ID DEL SERVIDOR.
     *
     * `adoptServerSession` ya mudaba los eventos de Room, pero la cola durable seguía nombrando a
     * la caja provisional. Un retiro encolado contra ese id viajaba con `sessionId=<uuid local>`,
     * el servidor contestaba 404 «esa caja no existe en este negocio» — que es REINTENTAR, no un
     * descarte — y el movimiento se quedaba dando vueltas para siempre, bloqueando además el
     * cierre. La cola tiene que hablar de la MISMA caja que Room.
     */
    private fun reapuntarPendientes(deSesion: String, aSesion: String) = synchronized(candadoDeLaCola) {
        if (deSesion == aSesion) return@synchronized
        val lista = pendientes()
        if (lista.none { it.sessionId == deSesion }) return@synchronized
        guardarPendientes(lista.map { if (it.sessionId == deSesion) it.copy(sessionId = aSesion) else it })
        Log.d(TAG, "🔀 Cola del cajón reapuntada: $deSesion → $aSesion")
    }

    /**
     * 🔴 La identidad de una operación es su `localId`, NO su caja (hallazgo M1).
     *
     * Dos retiros de $50 de la misma caja son operaciones distintas: sin el localId, quitar uno
     * quitaba los dos (Codex, 4ª auditoría). Y adoptar la caja del servidor le CAMBIA el
     * `sessionId` a la fila guardada, así que comparar por `(kind, sessionId)` dejaba de casar en
     * cuanto el renombre no se aplicaba a las dos copias: entradas ya confirmadas que se quedaban
     * en la cola y rechazos que nunca se registraban.
     *
     * Un CLOSE no lleva localId, y ahí la caja basta — sólo puede haber un cierre por caja.
     */
    private fun mismaOperacion(a: PendingDrawerOp, b: PendingDrawerOp) = when {
        a.kind != b.kind -> false
        a.localId != null || b.localId != null -> a.localId == b.localId
        else -> a.sessionId == b.sessionId
    }

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

    /**
     * El cajero ya lo vio y decidió qué hacer con ese dinero: se saca de la cola.
     *
     * 🔴 NUNCA borra una APERTURA (hallazgo I4). Sobre un movimiento, «Ya lo vi» cierra el asunto:
     * el dinero se movió en el cajón y alguien lo anotará. Sobre una apertura no cierra nada — deja
     * la caja en un limbo del que no hay salida: sus movimientos darían 404 para siempre, su cierre
     * queda bloqueado, y al desaparecer la fila desaparece también la barrera de entrada. Para una
     * apertura el único camino es [reintentarApertura].
     */
    fun descartarRechazada(localKey: String) = synchronized(candadoDeLaCola) {
        guardarPendientes(pendientes().filter { it.kind == "OPEN" || it.rechazadaEn == null || llaveDe(it) != localKey })
    }

    /**
     * Vuelve a poner en juego una APERTURA rechazada: se le quita la marca y el siguiente replay la
     * intenta otra vez. Es lo único que el cajero puede hacer con ella, y por eso el aviso de una
     * apertura dice «Reintentar» y no «Ya lo vi» (hallazgo I4).
     */
    fun reintentarApertura(localKey: String) = synchronized(candadoDeLaCola) {
        guardarPendientes(
            pendientes().map {
                if (it.kind == "OPEN" && llaveDe(it) == localKey) it.copy(rechazadaEn = null, motivoDelRechazo = null) else it
            },
        )
    }

    // MARK: - Avisos de adopción (hallazgo I1)

    /** Una caja que se adoptó del servidor en vez de crear la del cajero. Ver [textoDeAdopcion]. */
    data class CajaAdoptada(
        val sessionId: String,
        val openedByName: String,
        val openedAtMillis: Long,
        val fondoServidorCents: Int,
        val fondoLocalCents: Int,
    )

    private fun avisosDeAdopcion(): List<AvisoDeAdopcion> = try {
        secureStorage.drawerAdoptionNoticesJson(venueId)
            ?.let { json.decodeFromString(ListSerializer(AvisoDeAdopcion.serializer()), it) } ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "❌ Avisos de adopción ilegibles: ${e.message}")
        emptyList()
    }

    private fun guardarAvisos(lista: List<AvisoDeAdopcion>) = secureStorage.setDrawerAdoptionNoticesJson(
        venueId,
        if (lista.isEmpty()) null else json.encodeToString(ListSerializer(AvisoDeAdopcion.serializer()), lista),
    )

    /**
     * 🔴 Deja constancia de que ESTA apertura no creó una caja: adoptó la que ya estaba. El fondo
     * que el cajero contó NO quedó registrado en ninguna parte del servidor, y él es el único que
     * puede resolverlo (con un ingreso, o avisándole al dueño). Ver [textoDeAdopcion].
     *
     * 🔴 **No se crea ningún movimiento de dinero automáticamente**: meter el fondo local como un
     * PAY_IN sería inventar un ingreso que nadie autorizó.
     */
    private fun anotarAdopcion(op: PendingDrawerOp, server: CashDrawerSessionEntity) = synchronized(candadoDeLaCola) {
        // 🔴 LA CAJA PROPIA NO SE «ADOPTA» (F2). Si el servidor devuelve MI caja —mismo aparato,
        // misma persona y mismo fondo— es que mi apertura sí aterrizó y sólo se perdió su respuesta.
        // Decirle al cajero «se adoptó la caja abierta por …» sobre su propia caja es una mentira
        // que además ENTRENA a ignorar el aviso, y el día que adopte la caja de otro de verdad no
        // lo va a leer.
        //
        // ⚠️ Y NO afirma «sólo se calla cuando no había nada que avisar»: con dos tablets del mismo
        // modelo, la misma cuenta y el mismo fondo, esto calla una adopción REAL. El residuo está
        // declarado en [esMiPropiaCaja] y sólo se cierra con la llave idempotente del servidor (N1).
        if (esMiPropiaCaja(server, op, deviceName, staffId)) {
            Log.d(TAG, "🔁 El servidor devolvió MI propia caja (${server.id}): no es una adopción, no se avisa")
            return@synchronized
        }
        val aviso = AvisoDeAdopcion(
            sessionId = server.id,
            openedByName = server.openedByName,
            openedAtMillis = server.openedAt,
            fondoServidorCents = server.startingAmountCents,
            fondoLocalCents = op.amountCents,
        )
        guardarAvisos(avisosDeAdopcion().filter { it.sessionId != aviso.sessionId } + aviso)
        Log.w(TAG, "🔗 Caja adoptada: ${server.id} (fondo del server ${server.startingAmountCents}, local ${op.amountCents})")
    }

    fun cajasAdoptadas(): List<CajaAdoptada> = avisosDeAdopcion().map {
        CajaAdoptada(it.sessionId, it.openedByName, it.openedAtMillis, it.fondoServidorCents, it.fondoLocalCents)
    }

    /** El cajero cerró el aviso. Sólo desaparece cuando él lo cierra: nunca solo. */
    fun descartarAvisoDeAdopcion(sessionId: String) = synchronized(candadoDeLaCola) {
        guardarAvisos(avisosDeAdopcion().filter { it.sessionId != sessionId })
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

    /**
     * 🔴 «Esta caja todavía no llega al servidor» — lo que la pantalla necesita para DECIRLO.
     * Ver [estadoDeAperturaVisible].
     */
    fun estadoDeApertura(sessionId: String?): EstadoDeLaApertura =
        estadoDeAperturaVisible(pendientes(), sessionId)

    /**
     * 🔴 Le pone llave determinista a los movimientos legados ANTES de reproducir (P2 #6).
     *
     * Se escribe en la cola, no sólo en la copia que va al cable: así la identidad de la entrada
     * (`mismaOperacion`, que compara por `localId`) deja de ser ambigua y confirmarla o marcarla
     * no puede llevarse por delante al retiro de al lado. Es idempotente: la llave se deriva de la
     * propia entrada, así que correrlo dos veces da lo mismo.
     */
    private fun completarLlavesLegadas() = synchronized(candadoDeLaCola) {
        val lista = pendientes()
        if (lista.none { necesitaLlaveLegada(it) }) return@synchronized
        guardarPendientes(conLlavesLegadas(lista))
        Log.w(TAG, "🔑 Movimiento(s) de una versión anterior sin llave: se les puso una determinista")
    }

    /** Visible para test: ¿hay un cierre de ESTA caja esperando al server? */
    fun tieneCierrePendiente(sessionId: String): Boolean = pendientes().any { it.kind == "CLOSE" && it.sessionId == sessionId }

    /** Lo que una corrida del replay adoptó, indexado por el id LOCAL con el que estaba encolada. */
    data class ResultadoDelReplay(val adoptadas: Map<String, CashDrawerSessionEntity> = emptyMap())

    /**
     * 🔴 SE REPRODUCE EN ORDEN CRONOLÓGICO ESTRICTO — FIFO por `at`, sin importar el tipo.
     *
     * Antes se ordenaba por TIPO y globalmente (`OPEN → PAY_* → CLOSE` sobre TODA la cola), y eso
     * era el hallazgo C1: con la caja A cerrada sin red y la caja B abierta después, el `OPEN(B)`
     * salía SIEMPRE antes que el `CLOSE(A)`. El servidor —que sólo admite un turno abierto por
     * negocio— ligaba B a la caja A que seguía abierta, el cliente lo leía como «mi apertura se
     * confirmó», borraba `OPEN(B)` de la cola y mandaba los movimientos de B contra A: el arqueo de
     * A quedaba firmado con dinero ajeno y la caja B no existía ni iba a existir. Dentro de UNA
     * caja el orden no cambia: su apertura se encola al crearla, así que ya es la primera por `at`.
     *
     * 🔴 Y LA BARRERA ES LA OTRA MITAD: la corrida se DETIENE en la primera entrada que quede en
     * REINTENTAR —sea apertura, movimiento o cierre—. Nada posterior en el tiempo se manda, así que
     * el `OPEN(B)` no puede adelantarse a un `CLOSE(A)` que no aterrizó. Ordenar sin detenerse
     * dejaría el mismo defecto para la segunda pasada.
     *
     * Corre al entrar a Caja, en cada sync, al cerrar y al abrir otra caja.
     */
    internal suspend fun reproducirPendientes(ademas: PendingDrawerOp? = null): ResultadoDelReplay =
        candadoDelReplay.withLock { reproducirPendientesYaConElCandado(ademas) }

    /**
     * 🔴 UN SOLO VUELO — F2, medido el 5-sep-2026 en una Samsung SM-X133.
     *
     * Al entrar a Caja salieron DOS `POST /open` con 48 ms de diferencia (11:31:27.131 y .179),
     * los dos 201: el primero creó la caja y el segundo recibió `cajaCreada:false` sobre esa misma
     * caja recién creada. La pantalla lo leyó como adoptar la caja de OTRO y la hora saltó de la
     * real (10:22) a la del servidor (10:31). Con un aparato sólo se pierde el fondo de la vista;
     * con dos, un aviso falso TAPA una adopción de verdad.
     *
     * Causa: `syncAndLoad()` y `loadCurrentSession()` disparan el replay casi a la vez, y cada uno
     * leía la MISMA cola antes de que el otro quitara nada. El candado hace que la segunda llamada
     * espere y encuentre la cola ya vacía — que es exactamente lo que debe pasar.
     *
     * Es un `Mutex` de corrutinas (no `synchronized`) porque el cuerpo SUSPENDE en cada POST, y
     * bloquear un hilo mientras se espera la red sería peor que el defecto. No es reentrante y no
     * hace falta que lo sea: ningún camino de dentro (`enviarApertura`, `fireApiClose`,
     * `reproducirMovimiento`, `adoptServerSession`) vuelve a llamar aquí — verificado.
     */
    private val candadoDelReplay = Mutex()

    /**
     * 🔴 EL CAJÓN PRIMERO, LOS COBROS DESPUÉS — el punto de entrada del sincronizador de pagos.
     *
     * Devuelve `true` si los cobros encolados pueden salir detrás. Ver [losCobrosPuedenSalir] para
     * el porqué (F1: un cobro que llega antes que su apertura nace huérfano para siempre).
     *
     * Nunca lanza: si el replay truena, se registra y se sigue. Y la barrera es **de datos, no de
     * errores** — si ni siquiera se pudo leer la cola, los cobros salen. Un fallo interno que se
     * repitiera congelaría la cola de cobros sin que nadie pudiera verlo; la barrera vale sólo
     * mientras haya una apertura viva esperando, que es lo que de verdad protege el dinero.
     */
    suspend fun sincronizarCajonPrimero(): Boolean {
        runCatching { reproducirPendientes() }
            .onFailure { Log.e(TAG, "❌ El replay del cajón falló: ${it.message}") }
        val estado = runCatching { estadoDeLosCobros(pendientes(), System.currentTimeMillis()) }
            .getOrDefault(EstadoDeLosCobros.LIBRES)
        publicarEstadoDeLosCobros(estado)
        if (estado == EstadoDeLosCobros.ENVIADOS_SIN_CAJA) {
            Log.w(TAG, "⏱️ La apertura lleva más de 30 min sin llegar: los cobros salen SIN caja (quedarán fuera de turno)")
        }
        return estado != EstadoDeLosCobros.ESPERANDO_LA_APERTURA
    }

    /**
     * 🔴 LA VOZ DE LA BARRERA (P2-4). Lo que la banda de arriba tiene que decir mientras los cobros
     * esperan a que la caja llegue al servidor — o mientras salen sin ella.
     *
     * Se publica desde [sincronizarCajonPrimero] y desde el replay, que son los dos momentos en que
     * el hecho puede cambiar. No hay temporizador propio: el sincronizador de cobros ya corre al
     * reconectar y cada 15 min, y la banda cambia EXACTAMENTE cuando cambia el comportamiento —
     * nunca antes, que sería mentir, ni mucho después.
     */
    private val _estadoDeLosCobros = MutableStateFlow(EstadoDeLosCobros.LIBRES)
    val estadoDeLosCobros: StateFlow<EstadoDeLosCobros> = _estadoDeLosCobros.asStateFlow()

    private fun publicarEstadoDeLosCobros(nuevo: EstadoDeLosCobros) {
        _estadoDeLosCobros.value = nuevo
    }

    /**
     * 🔴 Marca un intento fallido de esta apertura hecho **CON RED** (P2-4, corregido en la ronda 2).
     *
     * Escribe dos cosas distintas con propósitos distintos:
     *  - `primerReintentoEn`, **sólo la primera vez**: es el arranque del tope. Si se reescribiera
     *    en cada intento, el tope nunca se cumpliría y los cobros esperarían para siempre — justo
     *    el defecto que el tope viene a cerrar.
     *  - `ultimoReintentoEn`, **siempre**: es el backoff, que por definición mira el último.
     *
     * 🔴 Y NO SE LLAMA SIN RED. Un fallo de red devuelve el mismo `Reintentar` que un 5xx, así que
     * antes abrir la caja con el WiFi apagado arrancaba el reloj de la media hora en el acto
     * (N-P2-1). Sin red el servidor ni se enteró: no hay espera que reprochar ni POST del que
     * hacer backoff.
     *
     * Se escribe en disco porque la espera tiene que sobrevivir a que la app se reinicie.
     */
    private fun marcarReintentoConRed(op: PendingDrawerOp, ahora: Long) = synchronized(candadoDeLaCola) {
        val lista = pendientes()
        if (lista.none { mismaOperacion(it, op) }) return@synchronized
        guardarPendientes(
            lista.map {
                if (mismaOperacion(it, op)) {
                    it.copy(primerReintentoEn = it.primerReintentoEn ?: ahora, ultimoReintentoEn = ahora)
                } else {
                    it
                }
            },
        )
    }

    /** ¿Hay red Y el servidor contesta? Es lo que decide si un intento fallido cuenta. */
    private fun hayRedYServidor(): Boolean = conectividad.isFullyConnected

    private suspend fun reproducirPendientesYaConElCandado(ademas: PendingDrawerOp?): ResultadoDelReplay {
        // 🔴 Las entradas legadas (sin `localId`) reciben su llave determinista ANTES de nada:
        // así se mandan como cualquier otro movimiento en vez de descartarse en silencio (P2 #6).
        completarLlavesLegadas()
        // 🔴 `ademas` es la operación que se ACABA de encolar. Se mezcla por si el almacén no la
        // devolvió (cola ilegible, disco lleno): la intención del cajero no puede depender de que
        // una lectura de disco funcione. Entra por `at`, así que sigue siendo la última y las
        // barreras la frenan igual — no es un atajo alrededor del orden, es un respaldo del disco.
        val guardadas = pendientes()
        val todas = if (ademas != null && guardadas.none { mismaOperacion(it, ademas) }) guardadas + ademas else guardadas
        // Las ya rechazadas NO se reintentan: se quedan guardadas sólo para poder avisar.
        val lista = todas.filter { it.rechazadaEn == null }.sortedBy { it.at }
        if (lista.isEmpty()) return ResultadoDelReplay()
        var confirmados = 0
        // 🔴 Un movimiento RECHAZADO en una corrida ANTERIOR bloquea el cierre de SU caja: el
        // servidor nunca lo va a tener, así que cerrar encima firmaría un faltante falso para
        // siempre. Se siembra con `todas` —no con `lista`— porque las rechazadas se filtraron para
        // no reintentarlas, y filtrarlas las sacaba también del bucle: en la SEGUNDA pasada el
        // cierre ya no encontraba quién lo bloqueara y se mandaba sin nadie mirando (Codex, 4ª).
        val cajasBloqueadas = todas.filter { it.rechazadaEn != null && it.kind != "CLOSE" }
            .map { it.sessionId }.toMutableSet()
        // 🔴 UNA APERTURA SIN CONFIRMAR ES BARRERA DE ENTRADA: mientras el servidor no conozca la
        // caja, NADA de esa caja se manda — ni un retiro (contestaría 404 y volvería a la cola) ni
        // mucho menos el cierre, que firmaría el arqueo de una caja que allá no existe. Incluye las
        // rechazadas de corridas anteriores, por el mismo motivo de arriba.
        val aperturasSinConfirmar = todas.filter { it.kind == "OPEN" }.map { it.sessionId }.toMutableSet()
        // Adoptar la caja del servidor le cambia el id a lo que sigue en la cola, y esta lista se
        // tomó ANTES. El mapa traduce el resto de la corrida; la cola guardada ya la reapuntó
        // `adoptServerSession`.
        val renombres = mutableMapOf<String, String>()
        val adoptadas = mutableMapOf<String, CashDrawerSessionEntity>()
        for (original in lista) {
            // La caja pudo cambiar de id a media corrida (la apertura acaba de adoptar la del
            // servidor). La BARRERA se evalúa con el id ORIGINAL, que es como está la lista.
            val op = renombres[original.sessionId]?.let { original.copy(sessionId = it) } ?: original

            if (original.kind == "OPEN") {
                // 🔴 BACKOFF (N-P3-4): si esta apertura acaba de fallar CON RED, no se repite el
                // POST. Se DETIENE la corrida igual que un `Reintentar`, porque el hecho es el
                // mismo —la caja sigue sin existir en el servidor— y dejar pasar lo posterior
                // rompería la barrera de orden (C1). La única diferencia es que no se molesta al
                // servidor por enésima vez en el mismo medio minuto.
                if (aperturaEnBackoff(op, System.currentTimeMillis())) {
                    Log.w(TAG, "⏳ La apertura de ${original.sessionId} falló hace menos de 30 s: se espera antes de reintentar")
                    break
                }
                when (val resultado = enviarApertura(op)) {
                    is ResultadoDeLaApertura.Adoptada -> {
                        aperturasSinConfirmar -= original.sessionId
                        val adoptada = resultado.session
                        if (adoptada.id != original.sessionId) renombres[original.sessionId] = adoptada.id
                        adoptadas[original.sessionId] = adoptada
                        // La entrada se localiza por su `localId`, que el renombre no toca (M1).
                        quitar(op)
                        confirmados++
                    }
                    // 🔴 Una apertura RECHAZADA detiene lo posterior EN EL TIEMPO, no sólo lo
                    // suyo: el negocio se quedó sin la caja que el cajero abrió y mandar lo que
                    // viene después sería colgarlo de una caja equivocada.
                    //
                    // ⚠️ Y ESO SÓLO PASA EN LA PASADA EN QUE SE RECHAZA (hallazgo N6). En las
                    // siguientes la entrada ya está marcada, se filtró de `lista` para no
                    // reintentarla, y por tanto no vuelve a romper el bucle: lo que sigue
                    // protegiendo el dinero es `aperturasSinConfirmar`, que se siembra con las
                    // rechazadas incluidas y bloquea TODO lo de SU caja para siempre. Entradas
                    // posteriores de OTRAS cajas sí se mandan, y eso es correcto: cada una tiene
                    // su propia apertura confirmada o su propia barrera.
                    ResultadoDeLaApertura.Rechazada -> {
                        Log.w(TAG, "🛑 Apertura rechazada: la corrida se detiene aquí")
                        break
                    }
                    ResultadoDeLaApertura.Reintentar -> {
                        // 🔴 Aquí arranca el reloj del tope de la barrera (P2-4) — pero SÓLO si el
                        // intento se hizo con red y con el servidor contestando (N-P2-1). Sin red
                        // no hay nada que reprochar: el servidor ni se enteró, y contar esa espera
                        // gastaba la media hora entera durante un apagón de WiFi, que es cuando la
                        // barrera más protege. El mismo intento con red fecha además el backoff.
                        if (hayRedYServidor()) {
                            marcarReintentoConRed(original, System.currentTimeMillis())
                        } else {
                            Log.d(TAG, "📡 La apertura falló sin red: no cuenta para el tope de la barrera")
                        }
                        Log.w(TAG, "⏸️ La apertura de ${original.sessionId} no llegó: nada posterior se manda")
                        break
                    }
                }
                continue
            }

            if (original.sessionId in aperturasSinConfirmar) {
                Log.w(TAG, "⏸️ ${original.kind} de ${original.sessionId} en espera: su apertura aún no llega al server")
                continue
            }
            if (op.kind == "CLOSE" && original.sessionId in cajasBloqueadas) { Log.w(TAG, "⏸️ Cierre de ${op.sessionId} en espera: hay movimientos sin confirmar"); continue }
            val destino = when (op.kind) {
                "CLOSE" -> fireApiClose(op.sessionId, op.amountCents, op.note).also { if (it == DestinoDeLaOperacion.RECHAZADA) marcarRechazada(op, "El servidor no aceptó el cierre de esta caja.") }
                else -> reproducirMovimiento(op)
            }
            when (destino) {
                DestinoDeLaOperacion.CONFIRMADA -> { quitar(op); confirmados++ }
                // 🔴 REINTENTAR detiene la corrida: lo que viene después es POSTERIOR en el tiempo
                // y mandarlo adelantaría, por ejemplo, la apertura de la caja siguiente al cierre
                // de ésta. Es exactamente C1.
                DestinoDeLaOperacion.REINTENTAR -> {
                    if (op.kind != "CLOSE") cajasBloqueadas += original.sessionId
                    Log.w(TAG, "⏸️ ${op.kind} de ${op.sessionId} sin confirmar: la corrida se detiene aquí")
                    break
                }
                // Un rechazo de un movimiento se MARCA y se salta: es visible, bloquea el cierre de
                // SU caja, y no tiene por qué congelar las cajas siguientes.
                DestinoDeLaOperacion.RECHAZADA -> if (op.kind != "CLOSE") cajasBloqueadas += original.sessionId
            }
        }
        if (confirmados > 0) Log.d(TAG, "✅ $confirmados movimiento(s) del cajón confirmados por el server")
        // La banda se entera en el MISMO momento en que cambia el hecho, no en el siguiente ciclo.
        publicarEstadoDeLosCobros(estadoDeLosCobros(pendientes(), System.currentTimeMillis()))
        return ResultadoDelReplay(adoptadas)
    }

    /** Alias histórico (sync/VM). */
    suspend fun reproducirCierresPendientes(): ResultadoDelReplay = reproducirPendientes(null)

    /** Qué pasó con el movimiento según el servidor. Ver [clasificarRespuestaDelServer]. */
    private suspend fun reproducirMovimiento(op: PendingDrawerOp): DestinoDeLaOperacion {
        // 🔴 UN `kind` DESCONOCIDO NUNCA SE MANDA COMO RETIRO (hallazgo M3). El `else` de abajo
        // caía en `pay-out`: una versión futura que introdujera un tipo nuevo y luego se degradara
        // publicaría esa operación —el fondo de una apertura, por ejemplo— como dinero SALIENDO
        // del cajón. Se salta, se registra y se MUESTRA: bloquea el cierre en vez de mentir.
        if (op.kind != "PAY_IN" && op.kind != "PAY_OUT") {
            Log.e(TAG, "🛑 Movimiento de un tipo que esta versión no conoce: «${op.kind}». No se manda.")
            marcarRechazada(op, "Esta versión de la app no reconoce el movimiento «${op.kind}». Actualízala.")
            return DestinoDeLaOperacion.RECHAZADA
        }
        // 🔴 SIN LLAVE NO SE DESCARTA: se le pone una DETERMINISTA y se manda (P2 #6). Antes esta
        // línea decía `?: return CONFIRMADA` — un retiro de una cola vieja desaparecía sin dejar
        // rastro, y el arqueo salía con un faltante que nadie podía explicar. La cola ya viene
        // migrada por `completarLlavesLegadas`; esto cubre la entrada que llega por `ademas`.
        val localId = op.localId?.takeIf { it.isNotBlank() } ?: localIdLegado(op)
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
            // El `code` del cuerpo también aquí: un 409 `SHIFT_CLOSE_IN_PROGRESS` dura
            // milisegundos y marcarlo como rechazo definitivo perdería el movimiento.
            when (clasificarRespuestaDelServer(op.kind, code, codigoDeNegocio(resp))) {
                DestinoDeLaOperacion.CONFIRMADA -> { promoteEvent(localId, parseEventId(resp)); Log.d(TAG, "✅ ${op.kind} reproducido ($localId)"); DestinoDeLaOperacion.CONFIRMADA }
                DestinoDeLaOperacion.RECHAZADA -> { Log.e(TAG, "🛑 ${op.kind} RECHAZADO por el server ($code): se marca para avisarle al cajero — $resp"); marcarRechazada(op, mensajeDeRechazo(code, resp)); DestinoDeLaOperacion.RECHAZADA }
                // Cualquier otra cosa se reintenta, que es el lado conservador — quedarse
                // atorado es ruidoso, perderlo no.
                else -> { Log.w(TAG, "🔁 ${op.kind} de la caja ${op.sessionId} sin confirmar ($code); sigue en cola — $resp"); DestinoDeLaOperacion.REINTENTAR }
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

            clasificarRespuestaDelServer("CLOSE", code, codigoDeNegocio(body)).also {
                when (it) {
                    DestinoDeLaOperacion.CONFIRMADA -> Log.d(TAG, "✅ Cierre aceptado por el server ($sessionId, $code)")
                    DestinoDeLaOperacion.RECHAZADA -> Log.e(TAG, "🛑 Cierre RECHAZADO por el server ($code) — $body")
                    else -> Log.w(TAG, "🔁 Cierre sin confirmar ($code); sigue en cola — $body")
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
