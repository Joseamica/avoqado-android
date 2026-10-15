package com.avoqado.pos.core.data.local.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Pendiente de ejecutar: verifica el DAO generado y @Transaction, no sólo JDBC. */
class F04RoomSequenceTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: SyncIntentDao
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java).build()
        dao = db.syncIntentDao()
    }
    @After fun close() { db.close() }
    private fun intent(id: String, status: String = "PENDING") = SyncIntentEntity(id, "v1", "staff", 0, "PAY_CASH", "{}", status, createdAt = 1)

    @Test fun allRealDeletionPathsKeepTheCounter() = runBlocking {
        assertEquals(1L, dao.insertWithNextSeq(intent("acked", "ACKED").copy(type = "ADD_ITEMS")))
        dao.deleteOldAcked(2)
        assertNull(dao.findById("acked"))
        assertEquals(2L, dao.insertWithNextSeq(intent("held", "HELD").copy(type = "ADD_ITEMS")))
        dao.descartar("held")
        assertNull(dao.findById("held"))
        assertEquals(3L, dao.insertWithNextSeq(intent("pending").copy(type = "ADD_ITEMS")))
        dao.descartar("pending")
        assertNull(dao.findById("pending"))
        assertEquals(4L, dao.insertWithNextSeq(intent("rejected", "REJECTED").copy(type = "ADD_ITEMS")))
        dao.dismiss("rejected")
        assertNull(dao.findById("rejected"))
        assertEquals(5L, dao.insertWithNextSeq(intent("next").copy(type = "ADD_ITEMS")))
    }

    @Test fun concurrentEnqueueUsesDifferentNumbersAndACommittedHintSetsTheNextFloor() = runBlocking {
        val numbers = (1..20).map { n -> async { dao.insertWithNextSeq(intent("i$n")) } }.awaitAll()
        assertEquals((1L..20L).toSet(), numbers.toSet())
        val row = requireNotNull(dao.findById("i1"))
        dao.resolveStaleSequence(row.id, "v1", row.seq, 99, "STALE_DEVICE_SEQUENCE", null, null)
        assertEquals(100L, dao.insertWithNextSeq(intent("after")))
        assertEquals("REJECTED", dao.findById(row.id)?.status)
    }

    @Test fun generatedTransactionRollsBackBothInsertAndStaleResolutionWhenCounterWriteFails() = runBlocking {
        dao.insertWithNextSeq(intent("original"))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_floor BEFORE UPDATE ON pos_sync_sequence_state BEGIN SELECT RAISE(ABORT, 'floor-write-failure'); END")
        assertTrue(runCatching { dao.insertWithNextSeq(intent("next")) }.isFailure)
        assertNull(dao.findById("next"))
        assertTrue(runCatching { dao.resolveStaleSequence("original", "v1", 1, 99, "STALE_DEVICE_SEQUENCE", null, null) }.isFailure)
        assertEquals("PENDING", dao.findById("original")?.status)
        assertEquals(1L, dao.sequenceState())
    }
    private fun snapshot() = CachedPayloadEntity("preparation:v1:line", "v1",
        com.avoqado.pos.kds.domain.PreparationPeerProtocol.json.encodeToString(
            com.avoqado.pos.kds.domain.PreparationLocalLine.serializer(),
            com.avoqado.pos.kds.domain.PreparationLocalLine(com.avoqado.pos.kds.domain.PreparationLine(
                id = "line", productName = "Café", quantity = 1,
                preparation = com.avoqado.pos.kds.domain.PreparationCounts(PENDING = 1)))), 1)

    @Test fun bothGeneratedAllocatorsShareTheDurableCounterAndTheServerFloor() = runBlocking {
        val prep = intent("prep").copy(type = "KDS_ITEM_PROGRESS")
        assertEquals(1L, dao.insertPreparationWithNextSeq(prep, listOf(snapshot())))
        dao.descartar("prep")
        assertEquals(2L, dao.insertWithNextSeq(intent("cash")))
        dao.resolveStaleSequence("cash", "v1", 2, 99, "STALE_DEVICE_SEQUENCE", null, null)
        assertEquals(100L, dao.insertPreparationWithNextSeq(prep.copy(id = "after"), listOf(snapshot())))
        assertEquals(101L, dao.insertWithNextSeq(intent("next")))
        assertEquals(snapshot(), db.cachedPayloadDao().get(snapshot().cacheKey))
    }

    @Test fun preparationSnapshotFailureLeavesNoIntentAndConsumesNoSequence() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_snapshot BEFORE INSERT ON cached_payloads BEGIN SELECT RAISE(ABORT, 'snapshot-write-failure'); END")
        assertTrue(runCatching { dao.insertPreparationWithNextSeq(intent("prep").copy(type = "KDS_ITEM_PROGRESS"), listOf(snapshot())) }.isFailure)
        assertNull(dao.findById("prep"))
        assertNull(db.cachedPayloadDao().get(snapshot().cacheKey))
        // seed performed in the aborted @Transaction also rolls back; 0 is the effective initial floor.
        assertEquals(0L, dao.sequenceState() ?: 0L)
        assertEquals(1L, dao.insertWithNextSeq(intent("next")))
    }

}
