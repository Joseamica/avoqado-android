package com.avoqado.pos.core.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Prueba del replay y sus emisiones. No acredita startup, timer ni una DB real. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class F04SyncReplayTest {
    private val dao = mockk<SyncIntentDao>(relaxed = true)
    private val api = mockk<ApiService>(relaxed = true)
    init { com.avoqado.pos.core.data.sync.vincularCallFixtureQ16(api) }
    private val events = mutableListOf<String>()
    private val cashPayload = """{"orderId":"order-F04","amountCents":100,"tipCents":0,"method":"CASH"}"""
    private var row = SyncIntentEntity("cash", "v1", "staff", 1, "PAY_CASH", cashPayload, createdAt = 1,
        q15CaptureJson = requireNotNull(ConciliacionDelCobro.capturarIntent("v1", "cash", cashPayload)),
        capturedOrderRef = "order-F04")

    private fun subject(response: SyncAck): SyncOutbox {
        val connectivity = mockk<ConnectivityMonitor>()
        every { connectivity.isConnected } returns MutableStateFlow(true)
        every { connectivity.isServerReachable } returns MutableStateFlow(true)
        val drawer = mockk<CashDrawerRepository>()
        coEvery { drawer.sincronizarCajonPrimero() } returns true
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "d1"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        coEvery { dao.pendingFifo("v1", any()) } answers { if (row.status == "PENDING") listOf(row) else emptyList() }
        coEvery { api.syncIntents("v1", any()) } returns SyncIntentsResponse(data = listOf(response))
        coEvery { dao.resolve(any(), any(), any(), any(), any()) } answers {
            events += "legacy"; row = row.copy(status = arg(1)); Unit
        }
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "v1"
        val cached = mockk<com.avoqado.pos.core.data.local.database.CachedPayloadDao>()
        coEvery { cached.get("preparation_capabilities:v1") } returns null
        return SyncOutbox(dao, api, connectivity, storage, drawer, context, cached).also {
            // Configura sólo el contexto del método; no ejecuta los triggers concurrentes de start().
            SyncOutbox::class.java.getDeclaredField("activeVenueId").apply { isAccessible = true }.set(it, "v1")
        }
    }

    private fun stale(details: JsonObject? = JsonObject(mapOf("latestSeq" to JsonPrimitive(99)))) =
        SyncAck("cash", "REJECTED", "STALE_DEVICE_SEQUENCE", "Revisar", details = details)

    @Test fun replayPersistsTheServerFloorBeforePublishingTheTerminalAck() = runTest {
        val subject = subject(stale())
        coEvery { dao.resolveStaleSequence("cash", "v1", 1, 99, any(), any(), any()) } answers {
            events += "commit"; row = row.copy(status = "REJECTED"); Unit
        }
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            subject.acks.collect { events += "emit" }
        }
        try {
            subject.replayNow("v1")
            assertEquals(listOf("commit", "emit"), events)
            coVerify(exactly = 1) { api.syncIntents("v1", any()) }
            coVerify(exactly = 0) { dao.resolve(any(), any(), any(), any(), any()) }
        } finally { collector.cancel(); subject.stop() }
    }

    @Test fun failedCounterWriteKeepsPendingAndDoesNotPublishOrHotLoop() = runTest {
        val subject = subject(stale())
        coEvery { dao.resolveStaleSequence(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("floor-write-failure")
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            subject.acks.collect { events += "emit" }
        }
        try {
            subject.replayNow("v1")
            assertEquals("PENDING", row.status)
            assertTrue(events.isEmpty())
            coVerify(exactly = 1) { api.syncIntents("v1", any()) }
            coVerify(exactly = 0) { dao.resolve(any(), any(), any(), any(), any()) }
        } finally { collector.cancel(); subject.stop() }
    }

    @Test fun missingHintStillPersistsTheLegacyStaleRejectionWithoutInventingAFloor() = runTest {
        val subject = subject(stale(details = null))
        coEvery { dao.resolveStaleSequence("cash", "v1", 1, null, any(), any(), any()) } answers {
            events += "commit"; row = row.copy(status = "REJECTED"); Unit
        }
        try {
            subject.replayNow("v1")
            assertEquals("REJECTED", row.status)
            assertEquals(listOf("commit"), events)
            coVerify(exactly = 0) { dao.resolve(any(), any(), any(), any(), any()) }
        } finally { subject.stop() }
    }

    @Test fun staleAckForAnUnknownUuidDoesNotAdvanceResolveOrHotLoop() = runTest {
        val response = stale().copy(id = "not-in-batch")
        val subject = subject(response)
        var attempts = 0
        coEvery { api.syncIntents("v1", any()) } answers {
            attempts += 1
            // El guard global aprobado corta antes de cualquier resolución; el límite protege el fixture.
            if (attempts > 1) throw IllegalStateException("test-stop-after-second-attempt")
            SyncIntentsResponse(data = listOf(response))
        }
        try {
            subject.replayNow("v1")
            assertEquals(1, attempts)
            assertEquals("PENDING", row.status)
            assertTrue(events.isEmpty())
            coVerify(exactly = 0) { dao.resolveStaleSequence(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { dao.resolve(any(), any(), any(), any(), any()) }
        } finally { subject.stop() }
    }
    @Test fun negotiatedPreparationLanePersistsStaleFloorWithoutCrossingHeldSalesBarrier() = runTest {
        row = row.copy(type = "KDS_ITEM_PROGRESS")
        val subject = subject(stale())
        // El fixture expone exactamente la capacidad aprobada; no cambia el modo de la cola global.
        val cachedField = SyncOutbox::class.java.getDeclaredField("cachedPayloads").apply { isAccessible = true }
        val cached = cachedField.get(subject) as com.avoqado.pos.core.data.local.database.CachedPayloadDao
        coEvery { cached.get("preparation_capabilities:v1") } returns com.avoqado.pos.core.data.local.database.CachedPayloadEntity(
            "preparation_capabilities:v1", "v1", """{"version":1,"enabled":true,"replayLaneVersion":1}""", 1)
        val heldSale = row.copy(id = "held-sale", type = "PAY_CASH", status = "HELD", seq = 0)
        coEvery { dao.pendingFifoLane("v1", false, any()) } returns listOf(heldSale)
        coEvery { dao.pendingFifoLane("v1", true, any()) } answers { if (row.status == "PENDING") listOf(row) else emptyList() }
        coEvery { dao.resolveStaleSequence("cash", "v1", 1, 99, any(), any(), any()) } answers {
            events += "commit"; row = row.copy(status = "REJECTED"); Unit
        }
        try {
            subject.replayNow("v1")
            assertEquals(listOf("commit"), events)
            assertEquals("HELD", heldSale.status)
            coVerify(exactly = 1) { api.syncIntents("v1", match { request -> request.intents.map { it.id } == listOf("cash") }) }
            coVerify(exactly = 0) { dao.pendingFifo(any(), any()) }
            coVerify(exactly = 0) { dao.resolve(any(), any(), any(), any(), any()) }
        } finally { subject.stop() }
    }

    @Test fun legacyPayWithoutCaptureStaysHeldBeforeTransportOrStaleResolution() = runTest {
        val legacy = row.copy(q15CaptureJson = null)
        row = legacy
        val subject = subject(stale())
        coEvery { dao.retenerIntentQ15("v1", "cash", ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, any()) } answers {
            row = row.copy(status = "HELD", q15Reason = ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA); 1
        }
        try {
            subject.replayNow("v1")
            assertEquals("HELD", row.status)
            assertEquals(legacy.id, row.id)
            assertEquals(legacy.payloadJson, row.payloadJson)
            assertNull(row.q15CaptureJson)
            assertTrue(events.isEmpty())
            coVerify(exactly = 1) { dao.retenerIntentQ15("v1", "cash", ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, any()) }
            coVerify(exactly = 0) { api.syncIntents(any(), any()) }
            coVerify(exactly = 0) { dao.resolveStaleSequence(any(), any(), any(), any(), any(), any(), any()) }
        } finally { subject.stop() }
    }

}
