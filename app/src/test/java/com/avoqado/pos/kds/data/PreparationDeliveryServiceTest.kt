package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.lan.*
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.kds.domain.*
import com.avoqado.pos.pos.data.model.ServiceCourseSnapshot
import com.avoqado.pos.printing.data.*
import com.avoqado.pos.printing.routing.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreparationDeliveryServiceTest {
    private val json = PreparationPeerProtocol.json
    private val row = PreparationLine("local:x", orderId = "order", externalId = "sync:r:0", sourceKey = "round:r:s", stationId = "s",
        orderNumber = "24", productName = "Café", quantity = 3,
        serviceCourse = ServiceCourseSnapshot("dessert", "Con el postre", "STANDARD", preparationVersion = 1), preparation = PreparationCounts(HELD = 3))
    private val config = PrintConfig(stations = listOf(StationInfo("s", "Cocina", hasKitchenDisplay = true)))
    private class Fixture(val service: PreparationDeliveryService, val printer: ReintentoDeComanda,
        val client: ClienteDeComandas, val records: MutableMap<String, CachedPayloadEntity>, val origins: MutableMap<String, SyncIntentEntity>,
        val setVenue: (String) -> Unit, val setAllowed: (Boolean) -> Unit, val cache: CachedPayloadDao) {
        fun job(): PreparationDeliveryJob = PreparationPeerProtocol.json.decodeFromString(records.getValue("preparation:v1:delivery:a").json)
    }
    private fun fixture(scope: kotlinx.coroutines.CoroutineScope, paperState: String = "QUEUED", phase: String = "PENDING", urgent: Boolean = false, urgencyPeer: Boolean = false): Fixture {
        val db = mockk<AvoqadoDatabase>()
        val cache = mockk<CachedPayloadDao>()
        val intents = mockk<SyncIntentDao>()
        val storage = mockk<SecureStorage>()
        val transport = mockk<TransporteLan>(relaxed = true)
        val printer = mockk<ReintentoDeComanda>()
        val configRepo = mockk<PrintConfigRepository>()
        val client = mockk<ClienteDeComandas>()
        val roles = mockk<com.avoqado.pos.core.domain.RoleManager>()
        var allowed = true
        every { roles.hasVenuePermission(any()) } answers { allowed }
        var venue = "v1"
        every { storage.venueId } answers { venue }; every { storage.userId } returns "current-waiter"
        every { db.cachedPayloadDao() } returns cache; every { db.syncIntentDao() } returns intents
        every { transport.deviceId } returns "d1"
        every { transport.peers } returns MutableStateFlow(listOf(LanPeer("kds", "10.0.0.2", 9000, kdsStations = setOf("s"), preparationVersion = 1, urgencyVersion = if (urgencyPeer) 1 else 0)))
        val action = if (urgent) PreparationAction.URGENT else PreparationAction.RELEASE
        val selected = if (urgent) row.copy(preparation = PreparationCounts(PENDING = row.quantity)) else row
        val job = preparationDeliveryJob("v1", "original-waiter", "d1", "a", listOf(selected), action, 1, null, null, config).copy(paperState = paperState)
        val records = mutableMapOf(
            "preparation_capabilities:v1" to CachedPayloadEntity("preparation_capabilities:v1", "v1", """{"version":1,"enabled":true,"urgencyVersion":1}""", 100),
            "preparation:v1:delivery:a" to CachedPayloadEntity("preparation:v1:delivery:a", "v1", json.encodeToString(PreparationDeliveryJob.serializer(), job), 100),
            "preparation:v1:${row.stableKey}" to CachedPayloadEntity("preparation:v1:${row.stableKey}", "v1", json.encodeToString(PreparationLocalLine.serializer(),
                PreparationLocalLine(selected, listOf(PreparationEffect("a", 0, action, 1)))), 100))
        val origins = mutableMapOf("a" to SyncIntentEntity("a", "v1", "original-waiter", 1, "KDS_ITEM_PROGRESS", "{}", status = phase))
        coEvery { cache.get(any()) } coAnswers { records[firstArg()] }
        coEvery { cache.preparationDeliveries(any(), any(), any()) } coAnswers {
            records.values.filter { it.cacheKey.startsWith(secondArg<String>()) && it.cacheKey > thirdArg<String>() &&
                !json.decodeFromString<PreparationDeliveryJob>(it.json).done }.take(50)
        }
        coEvery { cache.preparationSnapshots(any(), any()) } coAnswers { secondArg<List<String>>().mapNotNull { records[it] } }
        coEvery { cache.upsert(any()) } coAnswers { val value = firstArg<CachedPayloadEntity>(); records[value.cacheKey] = value }
        coEvery { cache.preparationPaperCount(any(), any()) } coAnswers {
            records.values.count { it.venueId == firstArg<String>() && it.cacheKey.startsWith(secondArg<String>()) &&
                json.decodeFromString<PreparationDeliveryJob>(it.json).paperState in setOf("QUEUED", "PRINTING", "FAILED", "UNCERTAIN", "REVIEW") }
        }
        coEvery { cache.preparationPaperPage(any(), any(), any()) } coAnswers {
            records.values.filter { it.venueId == firstArg<String>() && it.cacheKey.startsWith(secondArg<String>()) &&
                it.cacheKey > thirdArg<String>() && json.decodeFromString<PreparationDeliveryJob>(it.json).paperState in
                setOf("QUEUED", "PRINTING", "FAILED", "UNCERTAIN", "REVIEW") }.sortedBy { it.cacheKey }.take(21)
        }
        coEvery { intents.preparationStatuses(any(), any()) } coAnswers { secondArg<List<String>>().mapNotNull { origins[it] } }
        coEvery { printer.reintentar(any(), any(), any()) } returns EstadoDeComanda.Salio
        coEvery { client.enviar(any(), any(), any()) } coAnswers {
            val command = json.decodeFromString<PreparationPeerProgress>(secondArg())
            json.encodeToString(PreparationPeerAck.serializer(), PreparationPeerAck(status = "ok", deliveryId = command.deliveryId, phase = command.phase))
        }
        return Fixture(PreparationDeliveryService(db, storage, transport, printer, configRepo, client, roles).also { it.useScopeForTest(scope) },
            printer, client, records, origins, { venue = it }, { allowed = it }, cache)
    }


    @Test fun `urgency retries durable receipt until the kitchen actually displays it`() = runTest {
        val f = fixture(backgroundScope, paperState = "NONE", phase = "ACKED", urgent = true, urgencyPeer = true)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("REVIEW", f.job().paperState)
            coEvery { f.client.enviar(any(), any(), any()) } coAnswers {
                val command = json.decodeFromString<PreparationPeerProgress>(secondArg())
                json.encodeToString(PreparationPeerAck.serializer(), PreparationPeerAck(version = 2, status = "ok", deliveryId = command.deliveryId,
                    phase = command.phase, displayStations = listOf("s")))
            }
            f.service.pass("v1")
            assertEquals("NONE", f.job().paperState); assertTrue(f.job().done)
            assertNull(f.service.notice.value)
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `compatible urgent display receipt confirms priority without reprinting active products`() = runTest {
        val f = fixture(backgroundScope, paperState = "NONE", phase = "ACKED", urgent = true, urgencyPeer = true)
        coEvery { f.client.enviar(any(), any(), any()) } coAnswers {
            val command = json.decodeFromString<PreparationPeerProgress>(secondArg())
            json.encodeToString(PreparationPeerAck.serializer(), PreparationPeerAck(version = 2, status = "ok", deliveryId = command.deliveryId,
                phase = command.phase, displayStations = listOf("s")))
        }
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("NONE", f.job().paperState); assertTrue(f.job().done)
            assertEquals(setOf("a:0"), f.job().screenReceipts)
            assertEquals(3, json.decodeFromString<PreparationLocalLine>(f.records.getValue("preparation:v1:${row.stableKey}").json).projected().preparation.PENDING)
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `active urgency without compatible display stays visible and never repeats cooking paper`() = runTest {
        val f = fixture(backgroundScope, paperState = "NONE", phase = "ACKED", urgent = true)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("REVIEW", f.job().paperState); assertFalse(f.job().done)
            assertTrue(f.job().message!!.contains("avisa a cocina"))
            val issue = f.service.paperPage("v1").items.single()
            assertFalse(issue.canRetry)
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
            coVerify(exactly = 0) { f.client.enviar(any(), any(), any()) }
            f.service.resolvePaper("v1", "a", false); f.service.pass("v1")
            assertEquals("CONFIRMED", f.job().paperState)
            assertEquals("current-waiter", f.job().resolvedByStaffId)
        } finally { f.service.stop() }
    }
    @Test fun `stale retry cannot reprint mixed urgent release after it becomes a screen warning`() = runTest {
        val f = fixture(backgroundScope, paperState = "REVIEW", phase = "ACKED")
        val original = f.records.getValue("preparation:v1:delivery:a")
        // A mixed urgency can retain its released-food ticket after printing, but REVIEW
        // now asks for a kitchen acknowledgement, not another copy of that ticket.
        f.records[original.cacheKey] = original.copy(json = json.encodeToString(PreparationDeliveryJob.serializer(),
            f.job().copy(urgencyNeedsScreen = true)))
        try {
            f.service.start("v1")
            assertNotNull(f.job().paper)
            assertFalse(f.service.paperPage("v1").items.single().canRetry)
            assertNotNull(runCatching { f.service.resolvePaper("v1", "a", true) }.exceptionOrNull())
            assertEquals("REVIEW", f.job().paperState); assertNull(f.job().resolvedByStaffId)
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
            f.service.resolvePaper("v1", "a", false)
            assertEquals("CONFIRMED", f.job().paperState)
        } finally { f.service.stop() }
    }
    @Test fun `generic POS ack keeps selected release paper and never prints it twice`() = runTest {
        val f = fixture(backgroundScope)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("DONE", f.job().paperState); assertFalse(f.job().done)
            coVerify(exactly = 1) { f.printer.reintentar(match { it.planes.single().lines.single().quantity == 1 && it.config.stations.single().respaldoLocal }, 1, any()) }
            f.service.pass("v1")
            coVerify(exactly = 1) { f.printer.reintentar(any(), any(), any()) }
            assertEquals("original-waiter", f.job().staffId)
            assertEquals(listOf("a"), f.origins.keys.toList())
        } finally { f.service.stop() }
    }
    @Test fun `only a durable display receipt may suppress screen-only release paper`() = runTest {
        val f = fixture(backgroundScope)
        coEvery { f.client.enviar(any(), any(), any()) } coAnswers {
            val command = json.decodeFromString<PreparationPeerProgress>(secondArg())
            json.encodeToString(PreparationPeerAck.serializer(), PreparationPeerAck(status = "ok", deliveryId = command.deliveryId, phase = command.phase, displayStations = listOf("s")))
        }
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("DONE", f.job().paperState); assertEquals(setOf("a:0"), f.job().screenReceipts)
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `process death during printing becomes visible uncertainty and does not repeat paper`() = runTest {
        val f = fixture(backgroundScope, paperState = "PRINTING")
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("UNCERTAIN", f.job().paperState); assertTrue(f.job().message!!.contains("Revisa el ticket"))
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `business rejection cancels unsent paper and propagates correction with original actor`() = runTest {
        val f = fixture(backgroundScope, phase = "REJECTED")
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("REJECTED", f.job().phase); assertEquals("CANCELLED", f.job().paperState)
            coVerify { f.client.enviar(any(), match { json.decodeFromString<PreparationPeerProgress>(it).let { c -> c.phase == "REJECTED" && c.staffId == "original-waiter" } }, any()) }
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `venue change after socket response suspends paper without dropping original job`() = runTest {
        val f = fixture(backgroundScope)
        coEvery { f.client.enviar(any(), any(), any()) } coAnswers { f.setVenue("v2"); null }
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("QUEUED", f.job().paperState); assertEquals("v1", f.job().venueId)
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `failed release paper preserves remaining copies for explicit retry`() = runTest {
        val f = fixture(backgroundScope)
        coEvery { f.printer.reintentar(any(), any(), any()) } coAnswers {
            val work = firstArg<TrabajoPendiente>().copy(copiasPendientes = mapOf("s" to 1))
            EstadoDeComanda.NoSalio(listOf("Cocina"), "Sin papel", "24", work)
        }
        try {
            f.service.start("v1"); f.service.pass("v1"); f.service.pass("v1")
            assertEquals("FAILED", f.job().paperState); assertEquals(1, f.job().paper!!.copiasPendientes["s"])
            coVerify(exactly = 1) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `known peer without receipt cannot finish terminal delivery`() = runTest {
        val f = fixture(backgroundScope, phase = "ACKED")
        coEvery { f.client.enviar(any(), any(), any()) } returns null
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertFalse(f.job().done)
        } finally { f.service.stop() }
    }
    @Test fun `role revoked cannot resolve uncertain paper or adopt original actor`() = runTest {
        val f = fixture(backgroundScope, paperState = "UNCERTAIN")
        try {
            f.service.start("v1"); f.setAllowed(false)
            assertNotNull(runCatching { f.service.resolvePaper("v1", "a", true) }.exceptionOrNull())
            assertEquals("UNCERTAIN", f.job().paperState); assertNull(f.job().resolvedByStaffId)
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `role revoked during saved job read cannot resolve or print paper`() = runTest {
        val f = fixture(backgroundScope, paperState = "UNCERTAIN")
        coEvery { f.cache.get("preparation:v1:delivery:a") } coAnswers {
            f.setAllowed(false); f.records["preparation:v1:delivery:a"]
        }
        try {
            f.service.start("v1")
            assertNotNull(runCatching { f.service.resolvePaper("v1", "a", true) }.exceptionOrNull())
            assertEquals("UNCERTAIN", f.job().paperState); assertNull(f.job().resolvedByStaffId)
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `paper review paginates every ticket with total and venue scope`() = runTest {
        val f = fixture(backgroundScope)
        val original = f.job()
        for (index in 1..46) {
            val id = "a-" + index.toString().padStart(3, '0')
            val key = "preparation:v1:delivery:$id"
            f.records[key] = CachedPayloadEntity(key, "v1", json.encodeToString(PreparationDeliveryJob.serializer(),
                original.copy(intentId = id, paperState = "FAILED")), 100)
        }
        f.records["preparation:v2:delivery:foreign"] = CachedPayloadEntity("preparation:v2:delivery:foreign", "v2",
            json.encodeToString(PreparationDeliveryJob.serializer(), original.copy(venueId = "v2")), 100)
        try {
            f.service.start("v1")
            val ids = mutableListOf<String>(); var cursor: String? = null
            do {
                val page = f.service.paperPage("v1", cursor)
                assertEquals(47, page.total); assertTrue(page.items.size <= 20)
                ids += page.items.map { it.intentId }
                if (!page.hasMore) break
                cursor = page.cursor; assertNotNull(cursor)
            } while (true)
            assertEquals(47, ids.size); assertEquals(47, ids.toSet().size); assertFalse("foreign" in ids)
        } finally { f.service.stop() }
    }
}
