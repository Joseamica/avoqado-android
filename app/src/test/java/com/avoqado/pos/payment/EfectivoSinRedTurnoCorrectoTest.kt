package com.avoqado.pos.payment

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.FakeCashDrawerDao
import com.avoqado.pos.cashdrawer.conectividadDePrueba
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingCashSales
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.sesionLocal
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.local.database.SyncIntentPayload
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncAck
import com.avoqado.pos.core.data.sync.SyncIntentsRequest
import com.avoqado.pos.core.data.sync.SyncIntentsResponse
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.PaymentSyncService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A4 (efectivo sin red en el turno correcto, 21-oct-2026) — UN aparato, sin red: abre la caja A, vende, cierra A, abre B y
 * vuelve a vender. Al volver la red, UNA sola llamada a `syncNow` tiene que mandar todo EN ORDEN:
 *
 * ```
 *   apertura A → venta de A → cierre de A → apertura B → venta de B
 * ```
 *
 * Así cada venta cae en su turno y el corte de A cuadra. Antes el cierre de A salía antes que su venta (el servidor firmaba un
 * sobrante y la venta caía en B), o la barrera global de la apertura cerraba un ciclo con la del cierre.
 *
 * Cajón, servicio de cobros y outbox REALES; HTTP del cajón y de los cobros por MockWebServer; las dos colas en memoria con la
 * semántica de su SQL. Variantes: el cobro de A en el outbox (`PAY_CASH` de una mesa) y el de B en la cola de cobros (las dos
 * colas mezcladas), y una mesa abierta sin red en B (`OPEN_TABLE`, como lo escribe `TablesViewModel`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EfectivoSinRedTurnoCorrectoTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tope: org.junit.rules.Timeout = org.junit.rules.Timeout.builder()
        .withTimeout(90, java.util.concurrent.TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private companion object { const val VENUE = "FULLTEST-turno-A" }

    /** Lo que llegó al servidor, en orden, de las tres colas. */
    private val bitacora = CopyOnWriteArrayList<String>()

    /** El servidor del cajón y de los cobros. Crea `srv-A` para la apertura con llave `ev-open-A`, y así. */
    private inner class Servidor : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            val body = request.body.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
            return when {
                path.endsWith("/cash-drawer/open") -> {
                    val llave = body.getString("localId")
                    val id = "srv-" + llave.removePrefix("ev-open-")
                    bitacora += "OPEN $id"
                    ok(sesion(id, "OPEN", llave, cajaCreada = true))
                }
                path.endsWith("/cash-drawer/close") -> {
                    val id = body.getString("sessionId")
                    bitacora += "CLOSE $id"
                    ok(sesion(id, "CLOSED", null, cajaCreada = null))
                }
                path.endsWith("/fast") -> {
                    bitacora += "FAST ${body.getString("idempotencyKey")} ${body.optString("cashDrawerSessionId", "-")}"
                    fun pesos(cents: Int) = java.math.BigDecimal(cents).movePointLeft(2).toPlainString()
                    ok(JSONObject().put("success", true).put("data", JSONObject().put("id", "pago-" + body.getString("idempotencyKey"))
                        .put("orderId", "rapida-" + body.getString("idempotencyKey")).put("status", "COMPLETED")
                        .put("amount", pesos(body.getInt("amount"))).put("tipAmount", pesos(body.getInt("tip")))
                        .put("method", body.getString("method")).put("splitType", body.getString("splitType"))).toString())
                }
                path.endsWith("/cash-drawer/current") -> ok("""{"success":true,"data":null}""")
                path.endsWith("/cash-drawer/sync") -> ok("""{"success":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
        private fun sesion(id: String, status: String, llave: String?, cajaCreada: Boolean?) = JSONObject().put("success", true)
            .put("data", JSONObject().put("id", id).put("venueId", VENUE).put("deviceName", "D3").put("status", status)
                .put("openedByStaffId", "staff-1").put("openedByName", "Ana Ruiz").put("openedAt", "2026-10-21T10:00:00.000Z")
                .put("startingAmount", 1000.0).apply {
                    if (cajaCreada != null) put("cajaCreada", cajaCreada)
                    if (llave != null) put("localId", llave)
                    if (status == "CLOSED") put("closedAt", "2026-10-21T12:00:00.000Z").put("actualAmount", 1100.0)
                }.put("events", org.json.JSONArray())).toString()
    }

    /** La cola de cobros (`pending_payments`) en memoria, con la semántica de su SQL. */
    private val filas = ConcurrentHashMap<String, PendingPaymentEntity>()
    private val pagosDao = mockk<PendingPaymentDao>(relaxed = true).also { dao ->
        fun cambiar(id: String, f: (PendingPaymentEntity) -> PendingPaymentEntity) { filas.computeIfPresent(id) { _, v -> f(v) } }
        coEvery { dao.getPendingPayments(any()) } answers {
            filas.values.filter { it.syncStatus == "PENDING" }.sortedWith(compareBy({ it.createdAt }, { it.id })).take(firstArg())
        }
        coEvery { dao.forVenue(any()) } answers { filas.values.filter { it.venueId == firstArg<String>() } }
        coEvery { dao.findForVenue(any(), any()) } answers { filas[secondArg()]?.takeIf { it.venueId == firstArg<String>() } }
        coEvery { dao.updateStatus(any(), any()) } answers { cambiar(firstArg()) { it.copy(syncStatus = secondArg()) } }
        coEvery { dao.incrementRetry(any(), any()) } answers {
            cambiar(firstArg()) { it.copy(syncStatus = "PENDING", retryCount = it.retryCount + 1, lastRetryAt = secondArg()) }
        }
        coEvery { dao.updateStatusWithError(any(), any(), any()) } answers { cambiar(firstArg()) { it.copy(syncStatus = secondArg(), lastError = thirdArg()) } }
        coEvery { dao.registrarResultadoQ15(any(), any(), any(), any(), any(), any()) } answers {
            val aplicado = arg<Boolean>(2)
            cambiar(secondArg()) { it.copy(syncStatus = if (aplicado || it.syncStatus == "SYNCED") "SYNCED" else "PENDING",
                q15Reason = it.q15Reason ?: arg<String?>(3), q15ResultJson = arg<String?>(4) ?: it.q15ResultJson) }
            1
        }
        every { dao.getPendingCount() } returns flowOf(0)
        every { dao.getFailedCount() } returns flowOf(0)
    }

    /** El outbox (`pos_sync_intents`) en memoria. */
    private val intents = CopyOnWriteArrayList<SyncIntentEntity>()
    private val intentDao = mockk<SyncIntentDao>(relaxed = true).also { dao ->
        coEvery { dao.pendingFifo(any(), any()) } answers { intents.filter { it.status == "PENDING" }.sortedBy { it.seq } }
        coEvery { dao.pendingPayloads(any(), any()) } answers {
            intents.filter { it.status == "PENDING" && it.venueId == firstArg<String>() && it.type == secondArg<String>() }
                .map { SyncIntentPayload(it.payloadJson, it.createdAt) }
        }
        coEvery { dao.resolve(any(), any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            if (secondArg<String>() != "PENDING") intents.removeIf { it.id == id }
            Unit
        }
        coEvery { dao.resolverCobroQ15(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val id = firstArg<String>(); intents.removeIf { it.id == id }; Unit
        }
    }

    private val server = MockWebServer().apply { dispatcher = Servidor(); start() }
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val sent = chain.request()
        chain.proceed(sent.newBuilder().url(server.url(sent.url.encodedPath)).build())
    }.build()

    private var cola: String? = null
    private var alias: String? = null
    private val storage = mockk<SecureStorage>(relaxed = true).also { st ->
        every { st.venueId } returns VENUE
        every { st.userId } returns "staff-1"
        every { st.userFirstName } returns "Ana"
        every { st.userLastName } returns "Ruiz"
        every { st.pendingDrawerOpsJson(any()) } answers { cola }
        every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
        every { st.drawerSessionAliasesJson(any()) } answers { alias }
        every { st.setDrawerSessionAliasesJson(any(), any()) } answers { alias = secondArg() }
    }
    private val dao = FakeCashDrawerDao()
    private val cajon = CashDrawerRepository(dao, storage, client, PendingCashSales(intentDao, pagosDao), conectividadDePrueba(hayRed = true))

    /** La cola del cajón tal como la deja un aparato SIN red: abrir A, cerrar A, abrir B. */
    private fun sinRedAbrirCerrarYAbrir() {
        dao.sessions["A-local"] = sesionLocal("A-local", openedAt = 1L, startingAmountCents = 100_000, status = "CLOSED")
        dao.sessions["B-local"] = sesionLocal("B-local", openedAt = 5L, startingAmountCents = 50_000)
        cola = Json { encodeDefaults = false }.encodeToString(ListSerializer(PendingDrawerOp.serializer()), listOf(
            PendingDrawerOp("OPEN", "A-local", 100_000, localId = "ev-open-A", at = 1),
            PendingDrawerOp("CLOSE", "A-local", 110_000, at = 3),
            PendingDrawerOp("OPEN", "B-local", 50_000, localId = "ev-open-B", at = 5),
        ))
    }

    /** Un cobro en efectivo de importe libre en la cola de cobros, con la caja que estaba abierta al cobrar. */
    private fun cobroEnLaCola(id: String, caja: String, createdAt: Long) {
        filas[id] = PendingPaymentEntity(id = id, venueId = VENUE, staffId = "staff-1", amountCents = 10_000, tipCents = 0,
            method = "CASH", paymentType = "FAST", syncStatus = "PENDING", createdAt = createdAt, cashDrawerSessionId = caja,
            q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(VENUE, id, null, 10_000, 0, "FULLPAYMENT", "CASH")))
    }

    /** Un intent del outbox con la caja en el payload (`PAY_CASH` de la mesa, u `OPEN_TABLE` como lo escribe `TablesViewModel`). */
    private fun intentEnElOutbox(id: String, seq: Long, type: String, caja: String) {
        val payload = if (type == "PAY_CASH") """{"orderId":"orden-A","amountCents":10000,"tipCents":0,"cashDrawerSessionId":"$caja"}"""
            else """{"tableId":"mesa-B","covers":2,"localOrderId":"$id","cashDrawerSessionId":"$caja"}"""
        intents += SyncIntentEntity(id = id, venueId = VENUE, staffId = "staff-1", seq = seq, type = type, payloadJson = payload,
            q15CaptureJson = if (type == "PAY_CASH") ConciliacionDelCobro.capturarIntent(VENUE, id, payload) else null,
            capturedOrderRef = if (type == "PAY_CASH") "orden-A" else null, createdAt = seq)
    }

    private fun outboxReal(): SyncOutbox {
        val api = mockk<ApiService>(relaxed = true)
        com.avoqado.pos.core.data.sync.vincularCallFixtureQ16(api)
        coEvery { api.syncIntents(VENUE, any()) } answers {
            val request = secondArg<SyncIntentsRequest>()
            SyncIntentsResponse(data = request.intents.map { wire ->
                bitacora += "${wire.type} ${wire.id} ${(wire.payload["cashDrawerSessionId"] as? JsonPrimitive)?.content ?: "-"}"
                val result = if (wire.type == "PAY_CASH") JsonObject(mapOf("paymentId" to JsonPrimitive("pago-" + wire.id),
                    "orderId" to JsonPrimitive("orden-A"), "amount" to JsonPrimitive(10000), "tipAmount" to JsonPrimitive(0),
                    "method" to JsonPrimitive("CASH")))
                else JsonObject(mapOf("localOrderId" to requireNotNull(wire.payload["localOrderId"]), "orderId" to JsonPrimitive("orden-mesa-B")))
                SyncAck(id = wire.id, status = "ACKED", result = result)
            })
        }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "aparato-fijo"
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns Files.createTempDirectory("turno-correcto").toFile()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return SyncOutbox(intentDao, api, red(), storage, cajon, context, mockk(relaxed = true)).also {
            // Su replay propio (el de `start`) NO corre: este scheduler nunca avanza. Todo sale por UNA llamada a `syncNow`.
            it.usarContextoDeSincronizacion(StandardTestDispatcher(TestCoroutineScheduler()))
            it.start(VENUE)
        }
    }

    private fun red() = mockk<ConnectivityMonitor>(relaxed = true).also {
        every { it.isConnected } returns MutableStateFlow(true)
        every { it.isServerReachable } returns MutableStateFlow(true)
    }

    /** UNA llamada a `syncNow` (la del arranque) y se espera a que termine. */
    private suspend fun unaSolaSincronizacion(outbox: SyncOutbox) {
        val service = PaymentSyncService(pagosDao, storage, client, red(), cajon, outbox, io.mockk.mockk<com.avoqado.pos.payment.data.CashPaymentRepository>(relaxed = true))
        service.usarContextoDeSincronizacion(Dispatchers.Unconfined)
        try {
            service.start()
            service.esperarSincronizacionActualQ16()
        } finally { service.stop() }
    }

    @Test fun `P1 una sola sincronizacion manda apertura A, venta A, cierre A, apertura B y venta B en ese orden`() = runTest {
        try {
            sinRedAbrirCerrarYAbrir()
            cobroEnLaCola("venta-A", caja = "A-local", createdAt = 2)
            cobroEnLaCola("venta-B", caja = "B-local", createdAt = 6)
            val outbox = mockk<SyncOutbox>(relaxed = true)
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)

            unaSolaSincronizacion(outbox)

            assertEquals(listOf("OPEN srv-A", "FAST venta-A srv-A", "CLOSE srv-A", "OPEN srv-B", "FAST venta-B srv-B"), bitacora.toList())
            assertEquals(PaymentSyncStatus.SYNCED.name, filas.getValue("venta-A").syncStatus)
            assertEquals(PaymentSyncStatus.SYNCED.name, filas.getValue("venta-B").syncStatus)
        } finally { server.shutdown() }
    }

    @Test fun `P1 con las dos colas mezcladas el cobro de A en el outbox sale antes del cierre de A`() = runTest {
        try {
            sinRedAbrirCerrarYAbrir()
            intentEnElOutbox("mesa-A-cobro", seq = 1, type = "PAY_CASH", caja = "A-local")
            cobroEnLaCola("venta-B", caja = "B-local", createdAt = 6)
            val outbox = outboxReal()

            try { unaSolaSincronizacion(outbox) } finally { outbox.stop() }

            assertEquals(listOf("OPEN srv-A", "PAY_CASH mesa-A-cobro srv-A", "CLOSE srv-A", "OPEN srv-B", "FAST venta-B srv-B"), bitacora.toList())
        } finally { server.shutdown() }
    }

    @Test fun `P1 la mesa abierta sin red en B espera la apertura de B y sale con la caja de B`() = runTest {
        try {
            sinRedAbrirCerrarYAbrir()
            cobroEnLaCola("venta-A", caja = "A-local", createdAt = 2)
            intentEnElOutbox("mesa-B", seq = 1, type = "OPEN_TABLE", caja = "B-local")
            val outbox = outboxReal()

            try { unaSolaSincronizacion(outbox) } finally { outbox.stop() }

            assertEquals(listOf("OPEN srv-A", "FAST venta-A srv-A", "CLOSE srv-A", "OPEN srv-B", "OPEN_TABLE mesa-B srv-B"), bitacora.toList())
        } finally { server.shutdown() }
    }
}
