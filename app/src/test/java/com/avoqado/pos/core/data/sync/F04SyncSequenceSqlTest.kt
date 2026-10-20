package com.avoqado.pos.core.data.sync

import com.avoqado.pos.core.data.local.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/** SQL/algoritmo reales del DAO; NO acredita el wrapper @Transaction generado por Room. */
class F04SyncSequenceSqlTest {
    private class Fixture(path: String = ":memory:", seed: Boolean = true) : AutoCloseable {
        val db: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
        val dao = SqlDao(db)

        init {
            db.createStatement().use {
                it.execute("CREATE TABLE IF NOT EXISTS pos_sync_intents (id TEXT NOT NULL PRIMARY KEY, venue_id TEXT NOT NULL, staff_id TEXT, seq INTEGER NOT NULL, type TEXT NOT NULL, payload_json TEXT NOT NULL, status TEXT NOT NULL, error_code TEXT, message TEXT, result_json TEXT, created_at INTEGER NOT NULL)")
                it.execute("CREATE TABLE IF NOT EXISTS cached_payloads (cache_key TEXT NOT NULL PRIMARY KEY, venue_id TEXT NOT NULL, json TEXT NOT NULL, updated_at INTEGER NOT NULL)")
                val hasQ15 = it.executeQuery("PRAGMA table_info(pos_sync_intents)").use { rows ->
                    var found = false
                    while (rows.next()) if (rows.getString("name") == "q15Reason") found = true
                    found
                }
                if (!hasQ15) Q15_MIGRATION_SQL.filter { sql -> sql.startsWith("ALTER TABLE pos_sync_intents ") }.forEach { sql -> it.execute(sql) }
                if (seed) { it.execute(CREATE_SYNC_SEQUENCE_SQL); it.execute(SEED_SYNC_SEQUENCE_SQL) }
            }
        }

        suspend fun <T> write(block: suspend () -> T): T {
            db.autoCommit = false
            try { val value = block(); db.commit(); return value }
            catch (error: Throwable) { db.rollback(); throw error }
            finally { db.autoCommit = true }
        }

        fun sql(sql: String) { db.createStatement().use { it.execute(sql) } }
        override fun close() { db.close() }
    }

    /** El cuerpo de asignación/resolución es el default de SyncIntentDao, sin copiarlo. */
    private class SqlDao(private val db: Connection) : SyncIntentDao {
        override suspend fun seedSequenceState() { db.createStatement().use { it.execute(SEED_SYNC_SEQUENCE_SQL) } }
        override suspend fun sequenceState(): Long? = scalar(READ_SYNC_SEQUENCE_SQL)
        override suspend fun advanceSequenceState(floor: Long): Int = db.prepareStatement(ADVANCE_SYNC_SEQUENCE_SQL.replace(":floor", "?")).use {
            it.setLong(1, floor); it.executeUpdate()
        }
        override suspend fun maxSeq(): Long = requireNotNull(scalar(MAX_SYNC_INTENT_SQL))
        private fun scalar(sql: String): Long? = db.createStatement().use { statement ->
            statement.executeQuery(sql).use { if (it.next()) it.getLong(1) else null }
        }
        override suspend fun findById(id: String): SyncIntentEntity? = db.prepareStatement(FIND_SYNC_INTENT_SQL.replace(":id", "?")).use {
            it.setString(1, id)
            it.executeQuery().use { row ->
                if (!row.next()) null else SyncIntentEntity(
                    id = row.getString("id"), venueId = row.getString("venue_id"), staffId = row.getString("staff_id"),
                    seq = row.getLong("seq"), type = row.getString("type"), payloadJson = row.getString("payload_json"),
                    status = row.getString("status"), errorCode = row.getString("error_code"), message = row.getString("message"),
                    resultJson = row.getString("result_json"), createdAt = row.getLong("created_at"),
                )
            }
        }
        override suspend fun insertSqlQ16(entity: SyncIntentEntity): Long = db.prepareStatement("INSERT OR IGNORE INTO pos_sync_intents (id,venue_id,staff_id,seq,type,payload_json,status,error_code,message,result_json,created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use {
            it.setString(1, entity.id); it.setString(2, entity.venueId); it.setString(3, entity.staffId)
            it.setLong(4, entity.seq); it.setString(5, entity.type); it.setString(6, entity.payloadJson)
            it.setString(7, entity.status); it.setString(8, entity.errorCode); it.setString(9, entity.message)
            it.setString(10, entity.resultJson); it.setLong(11, entity.createdAt)
            if (it.executeUpdate() == 0) -1L else requireNotNull(scalar("SELECT last_insert_rowid()"))
        }
        override suspend fun resolve(id: String, status: String, errorCode: String?, message: String?, resultJson: String?) {
            val sql = RESOLVE_SYNC_INTENT_SQL.replace(":status", "?").replace(":errorCode", "?").replace(":message", "?").replace(":resultJson", "?").replace(":id", "?")
            db.prepareStatement(sql).use {
                it.setString(1, status); it.setString(2, errorCode); it.setString(3, message); it.setString(4, resultJson); it.setString(5, id)
                it.executeUpdate()
            }
        }
        override suspend fun savePreparationSnapshots(snapshots: List<CachedPayloadEntity>) {
            for (row in snapshots) db.prepareStatement("INSERT OR REPLACE INTO cached_payloads VALUES (?, ?, ?, ?)").use {
                it.setString(1, row.cacheKey); it.setString(2, row.venueId); it.setString(3, row.json); it.setLong(4, row.updatedAt)
                it.executeUpdate()
            }
        }
        override suspend fun registrarResultadoQ15(venueId: String, id: String, motivo: String?, resultadoJson: String?, ahora: Long): Int {
            // resolveStaleSequence now persists Q15 metadata for PAY_CASH. Execute its actual SQL.
            val values = mapOf<String, Any?>("venueId" to venueId, "id" to id, "motivo" to motivo, "resultadoJson" to resultadoJson, "ahora" to ahora)
            val names = mutableListOf<String>()
            val sql = Regex(":([A-Za-z][A-Za-z0-9_]*)").replace(Q15_INTENT_RESULT_SQL) { match -> names.add(match.groupValues[1]); "?" }
            return db.prepareStatement(sql).use { statement ->
                names.forEachIndexed { index, name -> statement.setObject(index + 1, values.getValue(name)) }
                statement.executeUpdate()
            }
        }
        override suspend fun referenciasSinResolverQ16(venueId: String, refs: List<String>): List<String> = error("Q16 price reads are outside the F04 sequence fixture")
        override suspend fun hayCapturaSinResolverQ16(venueId: String): Boolean = error("Q16 price reads are outside the F04 sequence fixture")
        override fun observarRevisionesQ15(venueId: String, revisadas: Boolean, busqueda: String): kotlinx.coroutines.flow.Flow<Int> = error("Q15 history observations are outside the F04 sequence fixture")
        override suspend fun retenerIntentQ15(venueId: String, id: String, motivo: String, ahora: Long): Int = error("Q15 holds are outside the F04 sequence fixture")
        override suspend fun soltarRetenidoSqlQ16(venueId: String, id: String): Int = error("Q16 releases are outside the F04 sequence fixture")
        override suspend fun preparationBefore(venueId: String, keys: List<String>): List<CachedPayloadEntity> = error("No delivery predecessor in this fixture")
        override suspend fun preparationStatuses(venueId: String, ids: List<String>): List<SyncIntentEntity> = error("Not used")
        override suspend fun pendingFifoLane(venueId: String, preparationLane: Boolean, limit: Int): List<SyncIntentEntity> = error("Not used")
        // No son parte del algoritmo probado por este fixture. Room se prueba en androidTest.
        override suspend fun sinResolver(venueId: String): List<IntentSinResolver> = error("Not used")
        override suspend fun pendingFifo(venueId: String, limit: Int): List<SyncIntentEntity> = error("Not used")
        override suspend fun soltar(id: String): Int = error("Not used")
        override suspend fun descartar(id: String): Int = error("Not used")
        override suspend fun soltarRetenidos(): Int = error("Not used")
        override suspend fun pendingCount(venueId: String): Int = error("Not used")
        override suspend fun heldCount(venueId: String): Int = error("Not used")
        override suspend fun pendingPayloads(venueId: String, type: String): List<SyncIntentPayload> = error("Not used")
        override suspend fun rejectedCount(venueId: String): Int = error("Not used")
        override suspend fun rejectedIntents(venueId: String): List<SyncIntentEntity> = error("Not used")
        override suspend fun dismiss(id: String) = error("Not used")
        override fun observarEstado(id: String): Flow<String?> = error("Not used")
        override suspend fun deleteOldAcked(olderThan: Long) = error("Not used")
    }

    private fun intent(id: String, venue: String = "v1", status: String = "PENDING", seq: Long = 0) = SyncIntentEntity(
        id = id, venueId = venue, staffId = "original-actor", seq = seq, type = "PAY_CASH", payloadJson = "{\"amountCents\":500}",
        status = status, createdAt = 1,
    )

    @Test fun cleanupAndReopenNeverRecycleTheHighestSequence() = runBlocking {
        val file = Files.createTempFile("f04-sequence-", ".sqlite").toFile()
        try {
            Fixture(file.path).use { f ->
                repeat(42) { n -> f.write { f.dao.insertWithNextSeq(intent("i$n", status = "ACKED")) } }
                f.sql("DELETE FROM pos_sync_intents WHERE status='ACKED' AND created_at < 2")
            }
            Fixture(file.path).use { f -> assertEquals(43L, f.write { f.dao.insertWithNextSeq(intent("next")) }) }
        } finally { file.delete() }
    }

    @Test fun deletingHighestHeldPendingAndRejectedKeepsTheWatermark() = runBlocking {
        Fixture().use { f ->
            for ((n, status) in listOf("HELD", "PENDING", "REJECTED").withIndex()) {
                assertEquals((n + 1).toLong(), f.write { f.dao.insertWithNextSeq(intent("i$n", status = status)) })
                f.sql("DELETE FROM pos_sync_intents")
            }
            assertEquals(4L, f.write { f.dao.insertWithNextSeq(intent("next")) })
        }
    }

    @Test fun migrationSeedsGlobalMaximumAndPreservesAllLegacyData() = runBlocking {
        Fixture(seed = false).use { f ->
            f.dao.insert(intent("low", seq = 7, status = "ACKED"))
            f.dao.insert(intent("high", venue = "v2", seq = 42, status = "REJECTED"))
            f.sql(CREATE_SYNC_SEQUENCE_SQL); f.sql(SEED_SYNC_SEQUENCE_SQL)
            assertEquals(42L, f.dao.sequenceState())
            assertEquals(intent("high", venue = "v2", seq = 42, status = "REJECTED"), f.dao.findById("high"))
            assertEquals(43L, f.write { f.dao.insertWithNextSeq(intent("next")) })
        }
    }

    @Test fun staleFloorAndRejectionCommitTogetherWithoutChangingTheOperation() = runBlocking {
        Fixture().use { f ->
            f.write { f.dao.insertWithNextSeq(intent("cash")) }
            val before = requireNotNull(f.dao.findById("cash"))
            f.write { f.dao.resolveStaleSequence("cash", "v1", before.seq, 99, "STALE_DEVICE_SEQUENCE", "Revisar", null) }
            val after = requireNotNull(f.dao.findById("cash"))
            assertEquals(before.copy(status = "REJECTED", errorCode = "STALE_DEVICE_SEQUENCE", message = "Revisar"), after)
            assertEquals(100L, f.write { f.dao.insertWithNextSeq(intent("next")) })
            f.sql("DELETE FROM pos_sync_intents WHERE id='cash'")
            assertEquals(101L, f.write { f.dao.insertWithNextSeq(intent("another", venue = "v2")) })
        }
    }

    @Test fun invalidOrMissingHintRejectsWithoutChangingTheFloor() = runBlocking {
        Fixture().use { f ->
            f.write { f.dao.insertWithNextSeq(intent("cash")) }
            f.write { f.dao.resolveStaleSequence("cash", "v1", 1, null, "STALE_DEVICE_SEQUENCE", "Revisar", null) }
            assertEquals("REJECTED", f.dao.findById("cash")?.status)
            assertEquals(2L, f.write { f.dao.insertWithNextSeq(intent("next")) })
        }
    }

    @Test fun aLowerServerHintCannotLowerTheExistingWatermark() = runBlocking {
        Fixture().use { f ->
            f.write { f.dao.insertWithNextSeq(intent("cash")) }
            f.dao.advanceSequenceState(200)
            f.write { f.dao.resolveStaleSequence("cash", "v1", 1, 99, "STALE_DEVICE_SEQUENCE", null, null) }
            assertEquals(201L, f.write { f.dao.insertWithNextSeq(intent("next")) })
        }
    }

    @Test fun wrongVenueOrSequenceCannotResolveAnotherOperation() = runBlocking {
        Fixture().use { f ->
            f.write { f.dao.insertWithNextSeq(intent("cash")) }
            for ((venue, seq) in listOf("v2" to 1L, "v1" to 2L)) {
                assertTrue(f.write { runCatching { f.dao.resolveStaleSequence("cash", venue, seq, 99, "STALE_DEVICE_SEQUENCE", null, null) } }.isFailure)
            }
            assertEquals("PENDING", f.dao.findById("cash")?.status)
        }
    }

    @Test fun failureUpdatingCounterRollsBackInsertAndStaleResolution() = runBlocking {
        Fixture().use { f ->
            f.write { f.dao.insertWithNextSeq(intent("cash")) }
            f.sql("CREATE TRIGGER reject_floor BEFORE UPDATE ON pos_sync_sequence_state BEGIN SELECT RAISE(ABORT, 'floor-write-failure'); END")
            assertTrue(runCatching { f.write { f.dao.insertWithNextSeq(intent("next")) } }.isFailure)
            assertNull(f.dao.findById("next"))
            assertTrue(runCatching { f.write { f.dao.resolveStaleSequence("cash", "v1", 1, 99, "STALE_DEVICE_SEQUENCE", null, null) } }.isFailure)
            assertEquals("PENDING", f.dao.findById("cash")?.status)
            assertEquals(1L, f.dao.sequenceState())
        }
    }

    @Test fun duplicateUuidKeepsItsDataAndDoesNotConsumeAnotherSequence() = runBlocking {
        Fixture().use { f ->
            assertEquals(1L, f.write { f.dao.insertWithNextSeq(intent("same")) })
            assertEquals(1L, f.write { f.dao.insertWithNextSeq(intent("same").copy(payloadJson = "changed")) })
            assertEquals(intent("same", seq = 1), f.dao.findById("same"))
            assertEquals(2L, f.write { f.dao.insertWithNextSeq(intent("next")) })
        }
    }

    @Test fun exhaustedOrCorruptCounterNeverWrapsOrInsertsInEitherAllocator() = runBlocking {
        for (invalid in listOf(-1L, MAX_SYNC_SEQUENCE, MAX_SYNC_SEQUENCE + 1)) Fixture().use { f ->
            f.sql("UPDATE pos_sync_sequence_state SET last_seq = $invalid WHERE id = 1")
            assertTrue(runCatching { f.write { f.dao.insertWithNextSeq(intent("overflow")) } }.isFailure)
            assertNull(f.dao.findById("overflow"))
            assertTrue(runCatching { f.write { f.dao.insertPreparationWithNextSeq(intent("prep-overflow").copy(type = "KDS_ITEM_PROGRESS"), listOf(snapshot())) } }.isFailure)
            assertNull(f.dao.findById("prep-overflow"))
        }
    }
    private fun snapshot() = CachedPayloadEntity("preparation:v1:line", "v1",
        com.avoqado.pos.kds.domain.PreparationPeerProtocol.json.encodeToString(
            com.avoqado.pos.kds.domain.PreparationLocalLine.serializer(),
            com.avoqado.pos.kds.domain.PreparationLocalLine(com.avoqado.pos.kds.domain.PreparationLine(
                id = "line", productName = "Café", quantity = 1,
                preparation = com.avoqado.pos.kds.domain.PreparationCounts(PENDING = 1)))), 1)

    @Test fun preparationAndSalesShareTheSameDurableFloorAfterDeletionAndServerHint() = runBlocking {
        Fixture().use { f ->
            val prep = intent("prep").copy(type = "KDS_ITEM_PROGRESS")
            assertEquals(1L, f.write { f.dao.insertPreparationWithNextSeq(prep, listOf(snapshot())) })
            f.sql("DELETE FROM pos_sync_intents WHERE id='prep'")
            assertEquals(2L, f.write { f.dao.insertWithNextSeq(intent("cash")) })
            f.write { f.dao.resolveStaleSequence("cash", "v1", 2, 99, "STALE_DEVICE_SEQUENCE", null, null) }
            assertEquals(100L, f.write { f.dao.insertPreparationWithNextSeq(prep.copy(id = "after"), listOf(snapshot())) })
            assertEquals(101L, f.write { f.dao.insertWithNextSeq(intent("next")) })
        }
    }

    @Test fun preparationSnapshotOrCounterFailureRollsBackIntentSnapshotsAndNumberTogether() = runBlocking {
        Fixture().use { f ->
            val prep = intent("prep").copy(type = "KDS_ITEM_PROGRESS")
            f.sql("CREATE TRIGGER reject_snapshot BEFORE INSERT ON cached_payloads BEGIN SELECT RAISE(ABORT, 'snapshot-failure'); END")
            assertTrue(runCatching { f.write { f.dao.insertPreparationWithNextSeq(prep, listOf(snapshot())) } }.isFailure)
            assertNull(f.dao.findById("prep"))
            assertEquals(0L, f.dao.sequenceState())
            f.sql("DROP TRIGGER reject_snapshot")
            f.sql("CREATE TRIGGER reject_floor BEFORE UPDATE ON pos_sync_sequence_state BEGIN SELECT RAISE(ABORT, 'floor-write-failure'); END")
            assertTrue(runCatching { f.write { f.dao.insertPreparationWithNextSeq(prep, listOf(snapshot())) } }.isFailure)
            assertNull(f.dao.findById("prep"))
            f.db.createStatement().use { stmt -> stmt.executeQuery("SELECT COUNT(*) FROM cached_payloads").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) } }
            assertEquals(0L, f.dao.sequenceState())
        }
    }

}
