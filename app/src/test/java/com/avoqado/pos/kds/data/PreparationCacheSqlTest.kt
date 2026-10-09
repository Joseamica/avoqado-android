package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.local.database.PreparationCacheSql
import com.avoqado.pos.kds.domain.PreparationDeliveryJob
import com.avoqado.pos.kds.domain.PreparationPeerProtocol
import org.junit.Assert.*
import org.junit.Test
import org.sqlite.Function
import java.sql.DriverManager
import java.sql.SQLException

/** Actual DAO SQL, including Android's older SQLite builds without optional JSON1. */
class PreparationCacheSqlTest {
    @Test fun `confirmed line cache pages every product and never matches another orders user text`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            Function.create(db, "json_extract", object : Function() { override fun xFunc() { throw SQLException("JSON1 unavailable") } })
            db.createStatement().execute("CREATE TABLE cached_payloads(cache_key TEXT PRIMARY KEY, venue_id TEXT, json TEXT, updated_at INTEGER)")
            fun add(id: String, venue: String, order: String, draft: String? = null) {
                val row = com.avoqado.pos.kds.domain.PreparationLine(id, orderId = order, productName = "Texto: \"orderId\":\"order\"", quantity = 1,
                    preparation = com.avoqado.pos.kds.domain.PreparationCounts(PENDING = 1), draftRoundKey = draft)
                db.prepareStatement("INSERT INTO cached_payloads VALUES(?,?,?,100)").use {
                    it.setString(1, "preparation:$venue:$id"); it.setString(2, venue)
                    it.setString(3, PreparationPeerProtocol.json.encodeToString(com.avoqado.pos.kds.domain.PreparationLocalLine.serializer(),
                        com.avoqado.pos.kds.domain.PreparationLocalLine(row))); it.executeUpdate()
                }
            }
            for (i in 0..<132) add("line-" + i.toString().padStart(3, '0'), "v1", "order")
            add("foreign-order", "v1", "other"); add("foreign-venue", "v2", "order")
            add("unsent-round", "v1", "order", "round")
            fun sql(template: String, after: String = "") = template.replace(":venueId", "'v1'")
                .replace(":prefix", "'preparation:v1:'").replace(":orderToken", "'\"orderId\":\"order\"'").replace(":after", "'$after'")
            assertEquals(132, db.createStatement().use { st -> st.executeQuery(sql(PreparationCacheSql.LINE_COUNT)).use { it.next(); it.getInt(1) } })
            val seen = mutableListOf<String>(); var after = ""
            do {
                val page = db.createStatement().use { st -> st.executeQuery(sql(PreparationCacheSql.LINE_PAGE, after)).use { r ->
                    buildList { while (r.next()) add(r.getString("cache_key")) }
                } }
                assertTrue(page.size <= 51)
                seen += page.take(50); after = seen.last()
            } while (page.size > 50)
            assertEquals(132, seen.size); assertEquals(132, seen.distinct().size)
            assertFalse(seen.any { "foreign" in it })
        }
    }
    @Test fun `old SQLite pages all paper issues and never interprets user text as delivery state`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            Function.create(db, "json_extract", object : Function() {
                override fun xFunc() { throw SQLException("Optional JSON1 is unavailable on this Android version") }
            })
            db.createStatement().use { it.execute("CREATE TABLE cached_payloads(cache_key TEXT PRIMARY KEY, venue_id TEXT, json TEXT, updated_at INTEGER)") }
            fun add(id: String, venue: String = "v1", state: String = "FAILED", done: Boolean = false) {
                val job = PreparationDeliveryJob(intentId = id, venueId = venue, staffId = "waiter", deviceId = "d1", commands = emptyList(),
                    paperState = state, done = done, message = "Texto: \"done\":true, \"paperState\":\"QUEUED\" · Café ☕")
                db.prepareStatement("INSERT INTO cached_payloads VALUES(?,?,?,100)").use {
                    it.setString(1, "preparation:$venue:delivery:$id"); it.setString(2, venue)
                    it.setString(3, PreparationPeerProtocol.json.encodeToString(PreparationDeliveryJob.serializer(), job)); it.executeUpdate()
                }
            }
            for (i in 0..<47) add("issue-" + i.toString().padStart(3, '0'))
            add("done", state = "DONE", done = true)
            add("foreign", venue = "v2")
            fun sql(template: String, after: String = "") = template.replace(":venueId", "'v1'")
                .replace(":prefix", "'preparation:v1:delivery:'").replace(":after", "'$after'")
            val total = db.createStatement().use { st -> st.executeQuery(sql(PreparationCacheSql.PAPER_COUNT)).use { it.next(); it.getInt(1) } }
            assertEquals(47, total)
            var after = ""; val seen = mutableListOf<String>()
            do {
                val page = db.createStatement().use { st -> st.executeQuery(sql(PreparationCacheSql.PAPER_PAGE, after)).use { r ->
                    buildList { while (r.next()) this.add(r.getString("cache_key")) }
                } }
                assertTrue(page.size <= 21)
                val visible = page.take(20); seen.addAll(visible); after = visible.lastOrNull().orEmpty()
            } while (page.size > 20)
            assertEquals(47, seen.size); assertEquals(47, seen.distinct().size)
            val deliveries = db.createStatement().use { st -> st.executeQuery(sql(PreparationCacheSql.DELIVERIES)).use { r ->
                buildList { while (r.next()) this.add(r.getString("cache_key")) }
            } }
            assertEquals(seen, deliveries)
            assertFalse(deliveries.any { it.endsWith(":done") || it.contains("v2") })
        }
    }
}
