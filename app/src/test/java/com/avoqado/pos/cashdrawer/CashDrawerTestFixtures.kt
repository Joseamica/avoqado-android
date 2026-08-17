package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerDao
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.CobroSinReproducir
import com.avoqado.pos.cashdrawer.data.PendingCashSales
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.SyncIntentPayload
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * Andamio compartido por las pruebas del cajón: el DAO falso con la semántica
 * hostil de SQLite, el HTTP ruteado por path, y los payloads con la forma real de
 * `formatSession`/`formatEvent` del server.
 *
 * Vive aparte porque lo usan dos suites: la de la promoción de la SESIÓN
 * ([CashDrawerSessionPromotionTest]) y la de la fusión por LLAVE de los EVENTOS
 * ([CashDrawerEventKeyMergeTest]).
 */

// MARK: - Números del caso real (2026-08-16)

internal const val VENUE_ID = "venue-1"
internal const val FONDO_CENTS = 500_000 // $5,000.00
internal const val VENTA_CENTS = 28_000 // $280.00
internal const val REEMBOLSO_CENTS = 15_000 // $150.00
internal const val PAY_IN_CENTS = 10_000 // $100.00

/**
 * 🔴 El reloj de los payloads es RELATIVO al del test, nunca una fecha fija.
 *
 * Desde que la reconciliación acota los eventos que se mudan a la ventana de la
 * caja del server (`createdAt >= openedAt`), una fecha fija en el JSON haría que el
 * resultado dependiera de la HORA a la que corre la suite: a las 09:00 la sesión
 * local nacida "ahora" caería antes de una caja fija de las 17:00 y a las 20:00
 * después. Un test que cambia de color con el reloj de pared no prueba nada.
 */
internal fun haceMinutos(minutos: Long): Long = System.currentTimeMillis() - minutos * 60_000L

internal fun isoDe(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()

// MARK: - DAO falso (semántica de Room/SQLite, a propósito hostil)

/**
 * Réplica en memoria del DAO real. Dos detalles NO son cosméticos:
 *
 * 1. `getOpenSession` devuelve la fila abierta más RECIENTE por `openedAt`, con el
 *    `id` como desempate — el mismo `ORDER BY` que ya tiene la consulta de Room.
 * 2. `insertSession/insertEvent` son `INSERT OR REPLACE`: SQLite BORRA la fila
 *    vieja y mete una nueva, o sea que reemplazar **manda la fila al final**.
 *
 * Así, un test que pase aquí no puede estar apoyándose en que Room "casualmente"
 * devuelva la fila buena.
 */
internal class FakeCashDrawerDao : CashDrawerDao {
    val sessions = LinkedHashMap<String, CashDrawerSessionEntity>()
    val events = LinkedHashMap<String, CashDrawerEventEntity>()

    override suspend fun getOpenSession(venueId: String): CashDrawerSessionEntity? =
        sessions.values
            .filter { it.venueId == venueId && it.status == "OPEN" }
            .sortedWith(compareByDescending<CashDrawerSessionEntity> { it.openedAt }.thenByDescending { it.id })
            .firstOrNull()

    override suspend fun getOpenSessions(venueId: String): List<CashDrawerSessionEntity> =
        sessions.values.filter { it.venueId == venueId && it.status == "OPEN" }

    override suspend fun insertSession(session: CashDrawerSessionEntity) {
        sessions.remove(session.id)
        sessions[session.id] = session
    }

    override suspend fun updateSession(session: CashDrawerSessionEntity) {
        if (sessions.containsKey(session.id)) sessions[session.id] = session
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    override suspend fun getClosedSessions(venueId: String): List<CashDrawerSessionEntity> =
        sessions.values.filter { it.venueId == venueId && it.status == "CLOSED" }

    override suspend fun getSessionEvents(sessionId: String): List<CashDrawerEventEntity> =
        events.values.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

    override suspend fun getEvent(eventId: String): CashDrawerEventEntity? = events[eventId]

    override suspend fun insertEvent(event: CashDrawerEventEntity) {
        events.remove(event.id)
        events[event.id] = event
    }

    override suspend fun deleteEvent(eventId: String) {
        events.remove(eventId)
    }

    override suspend fun repointEventsFrom(fromSessionId: String, toSessionId: String, sinceMillis: Long) {
        events.values
            .filter { it.sessionId == fromSessionId && it.createdAt >= sinceMillis }
            .forEach { events[it.id] = it.copy(sessionId = toSessionId) }
    }

    override suspend fun deleteUnconfirmedEvents(
        sessionId: String,
        serverOwnedTypes: List<String>,
        confirmedIds: List<String>,
        protectedIds: List<String>,
    ) {
        events.values
            .filter {
                it.sessionId == sessionId &&
                    it.type in serverOwnedTypes &&
                    it.id !in confirmedIds &&
                    it.id !in protectedIds
            }
            .map { it.id }
            .forEach { events.remove(it) }
    }

    override suspend fun sumEventsByType(sessionId: String, type: String): Int =
        events.values.filter { it.sessionId == sessionId && it.type == type }.sumOf { it.amountCents }
}

// MARK: - HTTP falso, ruteado por path

private fun respuesta(code: Int, body: String, url: String) = Response.Builder()
    .request(Request.Builder().url(url).build())
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message(if (code in 200..299) "OK" else "Error")
    .body(body.toResponseBody("application/json".toMediaType()))
    .build()

/** Lo que el POS puso EN EL CABLE: el path y el cuerpo tal cual salieron. */
internal data class LlamadaCapturada(val path: String, val body: String)

/**
 * Devuelve la respuesta cuyo path COINCIDE con el sufijo pedido. Un path sin
 * respuesta configurada revienta con IOException — o sea, "sin red" para esa
 * llamada, que es justo lo que hace falta para probar el modo isla.
 *
 * `capturadas` guarda el cuerpo de CADA request **antes** de ejecutarla, así que
 * también se ve lo que se mandó en una llamada que después se cae. Es la única
 * forma de probar que la llave de idempotencia viaja: si sólo miráramos el
 * resultado, un cuerpo sin llave se vería idéntico a uno con llave.
 */
internal fun cashDrawerClient(
    vararg rutas: Pair<String, String>,
    capturadas: MutableList<LlamadaCapturada>? = null,
): OkHttpClient {
    val porRuta = rutas.toMap()
    return mockk {
        every { newCall(any()) } answers {
            val request = firstArg<Request>()
            val path = request.url.encodedPath
            capturadas?.add(
                LlamadaCapturada(
                    path = path,
                    body = request.body?.let { cuerpo ->
                        okio.Buffer().also { cuerpo.writeTo(it) }.readUtf8()
                    } ?: "",
                ),
            )
            val cuerpo = porRuta.entries.firstOrNull { path.endsWith(it.key) }?.value
            val call = mockk<Call>()
            if (cuerpo == null) {
                every { call.execute() } throws IOException("sin red: $path")
            } else {
                every { call.execute() } returns respuesta(200, cuerpo, request.url.toString())
            }
            call
        }
    }
}

internal fun cashDrawerSecureStorage(): SecureStorage = mockk(relaxed = true) {
    every { venueId } returns VENUE_ID
    every { venueName } returns "Testarudo Cafe"
    every { userId } returns "staff-1"
    every { userFirstName } returns "Ana"
    every { userLastName } returns "Ruiz"
}

/**
 * Por defecto NO hay ningún cobro esperando en la cola — que es el estado normal
 * de un local con red. Los tests que prueban la venta encolada lo dicen explícito.
 */
internal fun sinCobrosEnCola(): PendingCashSales = mockk {
    coEvery { sinReproducir(any(), any()) } returns emptyList()
}

/** Un cobro en efectivo que sigue esperando a reproducirse, como lo ve el cajón. */
internal fun cobroDeOrden(orderId: String, totalCents: Int) =
    CobroSinReproducir(orderId = orderId, totalCents = totalCents)

/**
 * El cobro de una venta de MOSTRADOR: se cobró antes de que existiera la orden, así
 * que no tiene con qué nombrarse más que su monto. Es el caso que el pareo por
 * `orderId` dejaba fuera.
 */
internal fun cobroSinOrden(totalCents: Int) =
    CobroSinReproducir(orderId = null, totalCents = totalCents)

internal fun cobrosEnCola(vararg cobros: CobroSinReproducir): PendingCashSales = mockk {
    coEvery { sinReproducir(any(), any()) } returns cobros.toList()
}

/**
 * El `PendingCashSales` DE VERDAD, con las dos colas puestas a mano. Lo usan los
 * tests que prueban **qué cobro protege y cuál no** (efectivo vs tarjeta, vivo vs
 * cuarentena, propina): esas decisiones viven dentro del componente, así que
 * mockearlo las saltaría por completo y el test sólo se probaría a sí mismo.
 */
internal fun colaDeCobros(
    cobros: List<PendingPaymentEntity> = emptyList(),
    intents: List<SyncIntentPayload> = emptyList(),
): PendingCashSales = PendingCashSales(
    intentDao = mockk { coEvery { pendingPayloads(any(), any()) } returns intents },
    pendingPaymentDao = mockk { coEvery { forVenue(any()) } returns cobros },
)

/**
 * Una fila de `pending_payments` con la forma real que le da
 * `CashPaymentRepository.queueCashPayment`: el importe partido en base y propina, y
 * el método guardado por NOMBRE (`"CASH"`, o el del cobro declarado a mano).
 */
internal fun cobroEncolado(
    id: String,
    amountCents: Int,
    tipCents: Int = 0,
    method: String = "CASH",
    orderId: String? = null,
    syncStatus: String = PaymentSyncStatus.PENDING.name,
    createdAt: Long = haceMinutos(5),
) = PendingPaymentEntity(
    id = id,
    venueId = VENUE_ID,
    staffId = "staff-1",
    amountCents = amountCents,
    tipCents = tipCents,
    method = method,
    paymentType = if (orderId != null) "ORDER" else "FAST",
    orderId = orderId,
    syncStatus = syncStatus,
    createdAt = createdAt,
)

/**
 * Un `PAY_CASH` del outbox, con la forma que arma `PaymentFlowViewModel` y con la HORA
 * en que se encoló — que es lo que decide si cae dentro de la ventana de esta caja.
 */
internal fun intentPayCash(
    localOrderId: String,
    amountCents: Int,
    tipCents: Int = 0,
    method: String? = null,
    createdAt: Long = haceMinutos(5),
) = SyncIntentPayload(
    payloadJson = buildString {
        append("""{"localOrderId":"$localOrderId","amountCents":$amountCents,"tipCents":$tipCents""")
        if (method != null) append(""","method":"$method"""")
        append("}")
    },
    createdAt = createdAt,
)

internal fun cashDrawerRepo(
    dao: CashDrawerDao,
    client: OkHttpClient,
    pendingCashSales: PendingCashSales = sinCobrosEnCola(),
) = CashDrawerRepository(
    dao = dao,
    secureStorage = cashDrawerSecureStorage(),
    client = client,
    pendingCashSales = pendingCashSales,
)

// MARK: - Payloads del server (forma real de `formatSession`/`formatEvent`)

/**
 * Un evento tal como lo devuelve `formatEvent`.
 *
 * @param localId la LLAVE de idempotencia del POS. `null` = el server no la manda
 *   (app/servidor viejo) y el cliente tiene que comportarse exactamente como antes.
 */
internal fun eventoJson(
    id: String,
    type: String,
    amount: String,
    note: String? = null,
    localId: String? = null,
    orderId: String? = null,
    createdAt: Long = haceMinutos(30),
) = """
    {"id":"$id","type":"$type","amount":$amount,
     "note":${note?.let { "\"$it\"" } ?: "null"},
     "staffId":"staff-1","staffName":"Ana Ruiz",
     "orderId":${orderId?.let { "\"$it\"" } ?: "null"},
     "localId":${localId?.let { "\"$it\"" } ?: "null"},
     "createdAt":"${isoDe(createdAt)}"}
""".trimIndent()

/** El mismo evento pero como lo manda un server VIEJO: sin el campo `localId`. */
internal fun eventoJsonSinLlave(
    id: String,
    type: String,
    amount: String,
    note: String? = null,
    createdAt: Long = haceMinutos(30),
) = """
    {"id":"$id","type":"$type","amount":$amount,
     "note":${note?.let { "\"$it\"" } ?: "null"},
     "staffId":"staff-1","staffName":"Ana Ruiz","orderId":null,
     "createdAt":"${isoDe(createdAt)}"}
""".trimIndent()

internal fun sesionJson(
    id: String,
    vararg eventos: String,
    openedAt: Long = haceMinutos(60),
) = """
    {"success":true,"data":{
      "id":"$id","venueId":"venue-1","deviceName":"Sunmi D3","status":"OPEN",
      "openedByStaffId":"staff-1","openedByName":"Ana Ruiz",
      "openedAt":"${isoDe(openedAt)}","startingAmount":5000.00,
      "closedByStaffId":null,"closedByName":null,"closedAt":null,
      "actualAmount":null,"overShort":null,"closingNote":null,
      "events":[${eventos.joinToString(",")}]
    }}
""".trimIndent()

/**
 * La MISMA sesión, pero **sin `openedAt`** — un server viejo, un payload recortado por
 * un proxy, o un campo que se cayó.
 *
 * 🔴 Existe para fijar la degradación: un campo que falta puede quitar la cota, nunca
 * hacer desaparecer dinero. Con `parseTimestamp(null)` devolviendo `now`, esta forma
 * colapsaba la protección entera — ver `CashDrawerRepository.ventanaDeLaCaja`.
 */
internal fun sesionJsonSinApertura(
    id: String,
    vararg eventos: String,
) = """
    {"success":true,"data":{
      "id":"$id","venueId":"venue-1","deviceName":"Sunmi D3","status":"OPEN",
      "openedByStaffId":"staff-1","openedByName":"Ana Ruiz",
      "startingAmount":5000.00,
      "closedByStaffId":null,"closedByName":null,"closedAt":null,
      "actualAmount":null,"overShort":null,"closingNote":null,
      "events":[${eventos.joinToString(",")}]
    }}
""".trimIndent()

internal val aperturaDelServer: String
    get() = eventoJson("srv-ev-open", "OPEN", "5000.00", note = "Caja abierta con \$5000.00", createdAt = haceMinutos(60))

/** Una fila de evento ya existente en Room — el estado "heredado" antes del sync. */
internal fun eventoLocal(
    id: String,
    sessionId: String,
    type: String,
    amountCents: Int,
    note: String? = null,
    orderId: String? = null,
    createdAt: Long = haceMinutos(30),
) = CashDrawerEventEntity(
    id = id,
    sessionId = sessionId,
    venueId = VENUE_ID,
    type = type,
    amountCents = amountCents,
    note = note,
    staffId = "staff-1",
    staffName = "Ana Ruiz",
    orderId = orderId,
    createdAt = createdAt,
)

/** Una fila de sesión ya existente en Room. */
internal fun sesionLocal(
    id: String,
    openedAt: Long,
    startingAmountCents: Int = FONDO_CENTS,
    status: String = "OPEN",
) = CashDrawerSessionEntity(
    id = id,
    venueId = VENUE_ID,
    deviceName = "Sunmi D3",
    openedByStaffId = "staff-1",
    openedByName = "Ana Ruiz",
    openedAt = openedAt,
    startingAmountCents = startingAmountCents,
    status = status,
)
