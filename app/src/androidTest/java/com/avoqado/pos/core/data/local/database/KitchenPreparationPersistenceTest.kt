package com.avoqado.pos.core.data.local.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KitchenPreparationPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "fulltest-preparation-${java.util.UUID.randomUUID()}.db"
    private var database: AvoqadoDatabase? = null
    private fun open(): AvoqadoDatabase = Room.databaseBuilder(context, AvoqadoDatabase::class.java, name).build().also { database = it }
    private fun action() = SyncIntentEntity(id = "action", venueId = "v1", staffId = "waiter-a", seq = 0,
        type = "KDS_ITEM_PROGRESS", payloadJson = "{}")
    private fun photo() = CachedPayloadEntity("preparation:v1:line", "v1", "{\"PENDING\":1}", 100)
    @After fun clean() { database?.close(); context.deleteDatabase(name) }


    @Test fun urgentPeerRequiresConfirmedCapabilityAndSurvivesRestartWithoutFinancialIntent() = runBlocking {
        val json = com.avoqado.pos.kds.domain.PreparationPeerProtocol.json
        val row = com.avoqado.pos.kds.domain.PreparationLine(id = "local:x", externalId = "sync:r:0", sourceKey = "round:r:s", stationId = "s",
            productName = "Café", quantity = 3, serviceCourse = com.avoqado.pos.pos.data.model.ServiceCourseSnapshot("dessert", "Postre", "STANDARD", preparationVersion = 1),
            preparation = com.avoqado.pos.kds.domain.PreparationCounts(HELD = 3))
        val command = com.avoqado.pos.kds.domain.PreparationPeerProgress(version = 2, venueId = "v1", deviceId = "d1", staffId = "original-waiter",
            deliveryId = "urgent:0", intentId = "urgent", action = com.avoqado.pos.kds.domain.PreparationAction.URGENT, quantity = 1,
            items = listOf(com.avoqado.pos.kds.domain.PreparationPeerItem(row.sourceKey!!, row.externalId!!, row.stationId, 3, 0, row.preparation)))
        var db = open()
        db.cachedPayloadDao().upsert(CachedPayloadEntity("preparation_capabilities:v1", "v1", """{"version":1,"enabled":true}""", 100))
        val key = "preparation:v1:${row.stableKey}"
        db.cachedPayloadDao().upsert(CachedPayloadEntity(key, "v1", json.encodeToString(com.avoqado.pos.kds.domain.PreparationLocalLine.serializer(),
            com.avoqado.pos.kds.domain.PreparationLocalLine(row)), 100))
        var denied = false
        try { com.avoqado.pos.kds.data.PreparationPeerPersistence(db).receive(command) } catch (_: IllegalStateException) { denied = true }
        assertTrue(denied)
        assertNull(db.cachedPayloadDao().get("preparation:v1:receipt:urgent:0"))
        db.cachedPayloadDao().upsert(CachedPayloadEntity("preparation_capabilities:v1", "v1", """{"version":1,"enabled":true,"urgencyVersion":1}""", 100))
        assertTrue(com.avoqado.pos.kds.data.PreparationPeerPersistence(db).receive(command))
        db.close(); db = open()
        assertTrue(com.avoqado.pos.kds.data.PreparationPeerPersistence(db).receive(command))
        val saved = json.decodeFromString<com.avoqado.pos.kds.domain.PreparationLocalLine>(db.cachedPayloadDao().get(key)!!.json).projected()
        assertEquals(3, saved.preparation.PENDING); assertEquals(0, saved.preparation.HELD)
        assertEquals("urgent", saved.preparation.urgency?.requestId)
        assertTrue(db.syncIntentDao().pendingFifo("v1").isEmpty())
    }
    @Test fun venueCachePruningKeepsDurablePreparationAndOriginalAction() = runBlocking {
        val db = open()
        val cache = db.cachedPayloadDao()
        db.syncIntentDao().insertPreparationWithNextSeq(action(), listOf(photo()))
        val job = photo().copy(cacheKey = "preparation:v1:delivery:action")
        val receipt = photo().copy(cacheKey = "preparation:v1:receipt:action")
        val cap = photo().copy(cacheKey = "preparation_capabilities:v1")
        cache.upsertAll(listOf(job, receipt, cap, photo().copy(cacheKey = "catalog:v1")))
        cache.deleteOtherVenues("v2")
        assertEquals(photo(), cache.get(photo().cacheKey))
        assertEquals(job, cache.get(job.cacheKey)); assertEquals(receipt, cache.get(receipt.cacheKey))
        assertEquals(cap, cache.get(cap.cacheKey))
        assertNull(cache.get("catalog:v1"))
        assertEquals("waiter-a", db.syncIntentDao().pendingFifo("v1").single().staffId)
    }

    @Test fun diskFailureRollsBackIntentAndPhoto() = runBlocking {
        val db = open()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_preparation_photo BEFORE INSERT ON cached_payloads BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
        var failure: Exception? = null
        try { db.syncIntentDao().insertPreparationWithNextSeq(action(), listOf(photo())) }
        catch (error: Exception) { failure = error }
        assertTrue("Debe fallar la segunda escritura, no una validación anterior", failure?.message?.contains("disk failure") == true)
        assertTrue(db.syncIntentDao().pendingFifo("v1").isEmpty())
        assertNull(db.cachedPayloadDao().get(photo().cacheKey))
    }

    @Test fun processRestartPreservesOriginalActorAndProjection() = runBlocking {
        var db = open()
        assertEquals(1L, db.syncIntentDao().insertPreparationWithNextSeq(action(), listOf(photo())))
        db.close(); db = open()
        val saved = db.syncIntentDao().pendingFifo("v1").single()
        assertEquals("waiter-a", saved.staffId)
        assertEquals(photo(), db.cachedPayloadDao().get(photo().cacheKey))
        var duplicateRejected = false
        try { db.syncIntentDao().insertPreparationWithNextSeq(action(), listOf(photo())) }
        catch (_: IllegalStateException) { duplicateRejected = true }
        assertTrue(duplicateRejected)
        assertEquals(1, db.syncIntentDao().pendingFifo("v1").size)
    }

    @Test fun missingActorCannotPersistPreparation() = runBlocking {
        val db = open()
        var rejected = false
        try { db.syncIntentDao().insertPreparationWithNextSeq(action().copy(staffId = null), listOf(photo())) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertTrue(db.syncIntentDao().pendingFifo("v1").isEmpty())
        assertNull(db.cachedPayloadDao().get(photo().cacheKey))
    }

    @Test fun snapshotOfAnotherVenueCannotPersistPreparation() = runBlocking {
        val db = open()
        val foreign = photo().copy(cacheKey = "preparation:v2:line", venueId = "v2")
        var rejected = false
        try { db.syncIntentDao().insertPreparationWithNextSeq(action(), listOf(foreign)) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertTrue(db.syncIntentDao().pendingFifo("v1").isEmpty())
        assertNull(db.cachedPayloadDao().get(foreign.cacheKey))
    }

    @Test fun unrelatedCachePayloadCannotPersistPreparation() = runBlocking {
        val db = open()
        val unrelated = photo().copy(cacheKey = "catalog:v1")
        var rejected = false
        try { db.syncIntentDao().insertPreparationWithNextSeq(action(), listOf(unrelated)) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertTrue(db.syncIntentDao().pendingFifo("v1").isEmpty())
        assertNull(db.cachedPayloadDao().get(unrelated.cacheKey))
    }

    @Test fun laneQueriesPreserveGlobalSequenceVenueAndBoundedPagesAfterRestart() = runBlocking {
        var db = open()
        val dao = db.syncIntentDao()
        dao.insertWithNextSeq(action().copy(id = "foreign", venueId = "v2"))
        for (index in 1..60) {
            dao.insertWithNextSeq(action().copy(id = "financial-$index", type = "ADD_ITEMS",
                status = if (index == 1) SyncIntentEntity.STATUS_HELD else SyncIntentEntity.STATUS_PENDING))
            dao.insertWithNextSeq(action().copy(id = "preparation-$index"))
        }
        db.close(); db = open()
        val kitchen = db.syncIntentDao().pendingFifoLane("v1", true)
        val financial = db.syncIntentDao().pendingFifoLane("v1", false)
        assertEquals((1..50).map { "preparation-$it" }, kitchen.map { it.id })
        assertEquals((1..50).map { "financial-$it" }, financial.map { it.id })
        assertEquals(SyncIntentEntity.STATUS_HELD, financial.first().status)
        assertEquals(121L, db.syncIntentDao().maxSeq())
        assertTrue(kitchen.zipWithNext().all { (a, b) -> a.seq < b.seq })
        assertTrue(kitchen.none { row -> financial.any { it.seq == row.seq } })
        assertEquals(listOf("foreign"), db.syncIntentDao().pendingFifoLane("v2", true).map { it.id })
        db.syncIntentDao().resolve(kitchen.first().id, SyncIntentEntity.STATUS_ACKED, null, null, null)
        assertEquals((2..51).map { "preparation-$it" }, db.syncIntentDao().pendingFifoLane("v1", true).map { it.id })
    }
    @Test fun preparationDeliveryCannotReplaceOriginalActorOrIntent() = runBlocking {
        val db = open()
        for ((staff, intent) in listOf("other" to "action", "waiter-a" to "another")) {
            val job = com.avoqado.pos.kds.domain.PreparationDeliveryJob(intentId = intent, venueId = "v1", staffId = staff, deviceId = "d1", commands = emptyList())
            val delivery = CachedPayloadEntity("preparation:v1:delivery:action", "v1",
                kotlinx.serialization.json.Json.encodeToString(com.avoqado.pos.kds.domain.PreparationDeliveryJob.serializer(), job), 100)
            assertNotNull(runCatching { db.syncIntentDao().insertPreparationWithNextSeq(action(), listOf(photo(), delivery)) }.exceptionOrNull())
            assertTrue(db.syncIntentDao().pendingFifo("v1").isEmpty())
            assertNull(db.cachedPayloadDao().get(delivery.cacheKey))
        }
    }
    @Test fun hundredRowsAndDeliveryAreAtomicAndSurviveRestart() = runBlocking {
        var db = open()
        val job = com.avoqado.pos.kds.domain.PreparationDeliveryJob(intentId = "action", venueId = "v1", staffId = "waiter-a", deviceId = "d1", commands = emptyList())
        val delivery = CachedPayloadEntity("preparation:v1:delivery:action", "v1",
            kotlinx.serialization.json.Json.encodeToString(com.avoqado.pos.kds.domain.PreparationDeliveryJob.serializer(), job), 100)
        val photos = (0..<100).map { photo().copy(cacheKey = "preparation:v1:line:$it") }
        assertEquals(1L, db.syncIntentDao().insertPreparationWithNextSeq(action(), photos + delivery))
        db.close(); db = open()
        assertEquals("waiter-a", db.syncIntentDao().pendingFifo("v1").single().staffId)
        assertEquals(delivery, db.cachedPayloadDao().get(delivery.cacheKey))
        assertEquals(100, db.cachedPayloadDao().preparationSnapshots("v1", photos.map { it.cacheKey }).size)
    }

    @Test fun peerReceiptAndPartialProjectionSurviveRestartWithoutFinancialIntent() = runBlocking {
        var db = open()
        val row = peerPhoto()
        val command = peerCommand(row)
        seedPeer(db, row)
        val receiver = com.avoqado.pos.kds.data.PreparationPeerPersistence(db)
        assertTrue(receiver.receive(command))
        db.close(); db = open()
        assertTrue(com.avoqado.pos.kds.data.PreparationPeerPersistence(db).receive(command))
        val saved = kotlinx.serialization.json.Json.decodeFromString(com.avoqado.pos.kds.domain.PreparationLocalLine.serializer(),
            db.cachedPayloadDao().get("preparation:v1:${row.stableKey}")!!.json)
        assertEquals(1, saved.effects.size); assertEquals(2, saved.projected().preparation.HELD)
        assertEquals(1, saved.projected().preparation.PENDING)
        assertTrue(db.syncIntentDao().pendingFifo("v1").isEmpty())
        assertTrue(db.cachedPayloadDao().get("preparation:v1:receipt:a:0")!!.json.contains("original-waiter"))
    }
    @Test fun missingSecondPeerProductRollsBackFirstAndReceipt() = runBlocking {
        val db = open(); val row = peerPhoto(); seedPeer(db, row)
        val command = peerCommand(row)
        val absent = command.items.single().copy(externalId = "absent")
        assertNotNull(runCatching { com.avoqado.pos.kds.data.PreparationPeerPersistence(db).receive(command.copy(items = command.items + absent)) }.exceptionOrNull())
        val saved = kotlinx.serialization.json.Json.decodeFromString(com.avoqado.pos.kds.domain.PreparationLocalLine.serializer(),
            db.cachedPayloadDao().get("preparation:v1:${row.stableKey}")!!.json)
        assertTrue(saved.effects.isEmpty())
        assertNull(db.cachedPayloadDao().get("preparation:v1:receipt:a:0"))
    }
    @Test fun routerAcknowledgesOnlyAfterDiskCommitAndTerminalRejectionCannotResurrect() = runBlocking {
        var db = open(); val row = peerPhoto(); seedPeer(db, row)
        val command = peerCommand(row)
        val wire = com.avoqado.pos.kds.domain.PreparationPeerProtocol.json.encodeToString(
            com.avoqado.pos.kds.domain.PreparationPeerProgress.serializer(), command)
        val receiver = com.avoqado.pos.kds.data.PreparationPeerPersistence(db)
        val ack = com.avoqado.pos.core.data.lan.EnrutadorLan.responder(wire, "v1", setOf("s"), null, { true },
            { receiver.receive(it) }, { setOf("s") })
        assertTrue(com.avoqado.pos.kds.domain.PreparationPeerProtocol.displayed(ack, command))
        assertNotNull(db.cachedPayloadDao().get("preparation:v1:receipt:a:0"))
        assertTrue(db.syncIntentDao().pendingFifo("v1").isEmpty())
        db.close(); db = open()
        val reopened = com.avoqado.pos.kds.data.PreparationPeerPersistence(db)
        val rejected = command.copy(phase = "REJECTED")
        assertTrue(reopened.receive(rejected))
        assertTrue(reopened.receive(rejected))
        assertNotNull(runCatching { reopened.receive(command) }.exceptionOrNull())
        val saved = kotlinx.serialization.json.Json.decodeFromString(com.avoqado.pos.kds.domain.PreparationLocalLine.serializer(),
            db.cachedPayloadDao().get("preparation:v1:${row.stableKey}")!!.json)
        assertTrue(saved.effects.isEmpty()); assertEquals(row.preparation, saved.projected().preparation)
        val receipt = kotlinx.serialization.json.Json.decodeFromString(com.avoqado.pos.kds.domain.PreparationPeerProgress.serializer(),
            db.cachedPayloadDao().get("preparation:v1:receipt:a:0")!!.json)
        assertEquals("REJECTED", receipt.phase); assertEquals("original-waiter", receipt.staffId)
    }
    @Test fun realSystemSqlitePagesEveryPaperIssueWithoutOptionalJsonFunctions() = runBlocking {
        val db = open(); val cache = db.cachedPayloadDao()
        val codec = com.avoqado.pos.kds.domain.PreparationPeerProtocol.json
        for (index in 0..<47) {
            val id = "issue-" + index.toString().padStart(3, '0')
            val job = com.avoqado.pos.kds.domain.PreparationDeliveryJob(intentId = id, venueId = "v1", staffId = "waiter", deviceId = "d1",
                commands = emptyList(), paperState = "FAILED", message = "Texto: \"done\":true, \"paperState\":\"QUEUED\"")
            cache.upsert(CachedPayloadEntity("preparation:v1:delivery:$id", "v1", codec.encodeToString(com.avoqado.pos.kds.domain.PreparationDeliveryJob.serializer(), job), 100))
        }
        assertEquals(47, cache.preparationPaperCount("v1", "preparation:v1:delivery:"))
        val seen = mutableListOf<String>(); var after = ""
        do {
            val page = cache.preparationPaperPage("v1", "preparation:v1:delivery:", after)
            assertTrue(page.size <= 21)
            val visible = page.take(20); seen.addAll(visible.map { it.cacheKey }); after = visible.lastOrNull()?.cacheKey.orEmpty()
        } while (page.size > 20)
        assertEquals(47, seen.size); assertEquals(47, seen.distinct().size)
        assertEquals(seen, cache.preparationDeliveries("v1", "preparation:v1:delivery:").map { it.cacheKey })
        assertEquals(0, cache.preparationPaperCount("v2", "preparation:v2:delivery:"))
    }
    private fun peerPhoto() = com.avoqado.pos.kds.domain.PreparationLine(id = "local:x", externalId = "sync:r:0", sourceKey = "round:r:s",
        stationId = "s", productName = "Café", quantity = 3,
        serviceCourse = com.avoqado.pos.pos.data.model.ServiceCourseSnapshot("dessert", "Con el postre", "STANDARD", preparationVersion = 1),
        preparation = com.avoqado.pos.kds.domain.PreparationCounts(HELD = 3))
    private fun peerCommand(row: com.avoqado.pos.kds.domain.PreparationLine) = com.avoqado.pos.kds.domain.PreparationPeerProgress(
        venueId = "v1", deviceId = "d1", staffId = "original-waiter", deliveryId = "a:0", intentId = "a",
        action = com.avoqado.pos.kds.domain.PreparationAction.RELEASE, quantity = 1,
        items = listOf(com.avoqado.pos.kds.domain.PreparationPeerItem(row.sourceKey!!, row.externalId!!, row.stationId, row.quantity, 0, row.preparation)))
    private suspend fun seedPeer(db: AvoqadoDatabase, row: com.avoqado.pos.kds.domain.PreparationLine) {
        db.cachedPayloadDao().upsert(CachedPayloadEntity("preparation_capabilities:v1", "v1", """{"version":1,"enabled":true}""", 100))
        db.cachedPayloadDao().upsert(CachedPayloadEntity("preparation:v1:${row.stableKey}", "v1",
            kotlinx.serialization.json.Json.encodeToString(com.avoqado.pos.kds.domain.PreparationLocalLine.serializer(),
                com.avoqado.pos.kds.domain.PreparationLocalLine(row)), 100))
    }

}
