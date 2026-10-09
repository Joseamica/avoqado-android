package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.kds.domain.PreparationAction
import com.avoqado.pos.kds.domain.PreparationCounts
import com.avoqado.pos.kds.domain.PreparationLine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KitchenPreparationRepositoryTest {
    @Test fun `first offline order page restores confirmed lines saved by the global kitchen page`() = runTest {
        val repo = repository()
        val local = com.avoqado.pos.kds.domain.PreparationLocalLine(line)
        val saved = CachedPayloadEntity("preparation:v1:line", "v1", com.avoqado.pos.kds.domain.PreparationPeerProtocol.json.encodeToString(
            com.avoqado.pos.kds.domain.PreparationLocalLine.serializer(), local), 100)
        coEvery { cache.preparationSnapshots("v1", listOf(saved.cacheKey)) } returns listOf(saved)
        coEvery { cache.preparationLineCount("v1", "preparation:v1:", "\"orderId\":\"order\"") } returns 1
        coEvery { cache.preparationLinePage("v1", "preparation:v1:", "\"orderId\":\"order\"", "") } returns listOf(saved)
        assertEquals(listOf("line"), repo.savedPage("v1", "order")?.items?.map { it.id })
    }
    @Test fun `saved preparation page validates its venue order version and counters before projecting`() = runTest {
        val repo = repository()
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            com.avoqado.pos.kds.domain.PreparationPageResponse.serializer(),
            com.avoqado.pos.kds.domain.PreparationPageResponse(com.avoqado.pos.kds.domain.PreparationPage(1, listOf(line), 1, false)))
        coEvery { cache.get("preparation_page:v1:order:") } returns CachedPayloadEntity("preparation_page:v1:order:", "v1", encoded, 100)
        assertEquals(line, repo.savedPage("v1", "order")!!.items.single())
        coEvery { cache.get("preparation_page:v1:order:") } returns CachedPayloadEntity("preparation_page:v1:order:", "v2", encoded, 100)
        assertNotNull(runCatching { repo.savedPage("v1", "order") }.exceptionOrNull())
        coEvery { cache.get("preparation_page:v1:order:") } returns CachedPayloadEntity("preparation_page:v1:order:", "v1", encoded.replace("\"PENDING\":1", "\"PENDING\":2"), 100)
        assertNotNull(runCatching { repo.savedPage("v1", "order") }.exceptionOrNull())
        coEvery { cache.get("preparation_page:v1:order:") } returns CachedPayloadEntity("preparation_page:v1:order:", "v1", encoded.replace("\"orderId\":\"order\"", "\"orderId\":\"other\""), 100)
        assertNotNull(runCatching { repo.savedPage("v1", "order") }.exceptionOrNull())
    }
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val cache = mockk<CachedPayloadDao>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val roles = mockk<RoleManager>(relaxed = true)
    private val call = mockk<Call>()
    private var venue: String? = "v1"
    private var actor: String? = "staff-a"
    private var permitted = true
    private val line = PreparationLine(id = "line", orderId = "order", productName = "Café", quantity = 1,
        preparation = PreparationCounts(PENDING = 1))

    private suspend fun repository(drafts: com.avoqado.pos.tables.data.TableRoundDraftStore? = null, capability: String = """{"version":1,"enabled":true}""", onRead: () -> Unit = {}): KitchenPreparationRepository {
        every { storage.venueId } answers { venue }
        every { storage.userId } answers { actor }
        every { storage.accessToken } returns "fulltest-token"
        every { roles.hasVenuePermission(any()) } answers { permitted }
        coEvery { cache.get(any()) } returns null
        coEvery { cache.get("preparation_capabilities:v1") } returns CachedPayloadEntity(
            "preparation_capabilities:v1", "v1", capability, 100)
        coEvery { cache.get("preparation:v1:line") } coAnswers { onRead(); null }
        every { call.execute() } throws IOException("offline")
        val client = mockk<OkHttpClient>(); every { client.newCall(any()) } returns call
        return KitchenPreparationRepository(storage, client, cache, intents, outbox, roles, drafts)
            .also { it.refreshCapabilities("v1") }
    }
    private val intents = mockk<SyncIntentDao>(relaxed = true)

    @Test fun `urgency requires enabled Pro and the negotiated version before allowing offline work`() = runTest {
        for (capability in listOf(
            """{"version":1,"enabled":true}""",
            """{"version":1,"enabled":false,"urgencyVersion":1}""",
            """{"version":2,"enabled":true,"urgencyVersion":1}"""
        )) {
            val repo = repository(capability = capability)
            for (action in listOf(PreparationAction.URGENT, PreparationAction.ACK_URGENT, PreparationAction.CLEAR_URGENT)) {
                org.junit.Assert.assertFalse(repo.can(action))
                assertNotNull(runCatching { repo.act("v1", listOf(line), action, 1) }.exceptionOrNull())
            }
        }
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any()) }
        val repo = repository(capability = """{"version":1,"enabled":true,"urgencyVersion":1}""")
        assertTrue(repo.can(PreparationAction.URGENT))
        every { roles.hasVenuePermission("orders:create") } returns false
        org.junit.Assert.assertFalse(repo.can(PreparationAction.URGENT))
        org.junit.Assert.assertFalse(repo.can(PreparationAction.CLEAR_URGENT))
        assertTrue(repo.can(PreparationAction.ACK_URGENT))
    }

    @Test fun `new offline rounds are paginated locally without losing products or touching HTTP`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("preparation-round-pages").toFile()
        try {
            val drafts = com.avoqado.pos.tables.data.TableRoundDraftStore(root)
            val course = com.avoqado.pos.pos.data.model.ServiceCourseSnapshot("dessert", "Con el postre", "STANDARD", preparationVersion = 1)
            val rounds = (0..2).associate { round -> "r$round" to (0..43).map { index ->
                com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine(
                    com.avoqado.pos.pos.data.model.CartItem(id = "$round-$index", type = com.avoqado.pos.pos.data.model.CartItemType.ProductItem("coffee"),
                        name = "Café", unitPrice = 4950, serviceCourse = course), null)
            } }
            val route = com.avoqado.pos.printing.routing.PrintConfig()
            drafts.save("v1", "order", emptyList(), rounds, preparationRouting = rounds.keys.associateWith { route })
            coEvery { intents.preparationStatuses("v1", any()) } answers {
                secondArg<List<String>>().map { key -> com.avoqado.pos.core.data.local.database.SyncIntentEntity(key, "v1", "staff-a", 1, "ADD_ITEMS", """{"orderId":"order"}""") }
            }
            val repo = repository(drafts)
            val rows = mutableListOf<PreparationLine>()
            var cursor: String? = null
            do {
                val page = repo.localPage("v1", "order", cursor)!!
                assertEquals(132, page.total)
                assertTrue(page.items.size <= 50)
                assertTrue(page.items.all { it.preparation.HELD == 1 && it.unavailableReason == null && it.draftRoundKey != null })
                rows += page.items; cursor = page.nextCursor
            } while (cursor != null)
            assertEquals(132, rows.size); assertEquals(132, rows.map { it.stableKey }.distinct().size)
            assertTrue(repo.localPage("v1", "different")!!.items.isEmpty())
            io.mockk.verify(exactly = 1) { call.execute() } // Only the helper's capability request, never a page HTTP.

        } finally { root.deleteRecursively() }
    }

    @Test fun `a rejected staged round cannot enqueue preparation from an older visible photo`() = runTest {
        coEvery { intents.preparationStatuses("v1", listOf("round")) } returns listOf(
            com.avoqado.pos.core.data.local.database.SyncIntentEntity("round", "v1", "staff-a", 1, "ADD_ITEMS", """{"orderId":"order"}""",
                status = "REJECTED", message = "La cuenta ya se cobró"))
        val failure = runCatching { repository().act("v1", listOf(line.copy(draftRoundKey = "round")), PreparationAction.START, 1) }.exceptionOrNull()
        assertNotNull("A new preparation action must recheck its financial round", failure)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `provisional preparation carries local order reference for reducer resolution`() = runTest {
        val selected = line.copy(id = "local:round:line:", sourceKey = "round:round:none", externalId = "sync:round:0", localOrderId = "local-order")
        repository().act("v1", listOf(selected), PreparationAction.START, 1)
        coVerify { outbox.enqueue("v1", "KDS_ITEM_PROGRESS", match { it["localOrderId"]?.toString() == "\"local-order\"" }, any(), false, null, any(), "staff-a") }
    }

    @Test fun `promoted local round preserves reducer reference while uncommitted or foreign proof stays blocked`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("preparation-round-proof").toFile()
        try {
            val drafts = com.avoqado.pos.tables.data.TableRoundDraftStore(root)
            val item = com.avoqado.pos.pos.data.model.CartItem(id = "product", type = com.avoqado.pos.pos.data.model.CartItemType.ProductItem("coffee"), name = "Café", unitPrice = 8000,
                serviceCourse = com.avoqado.pos.pos.data.model.ServiceCourseSnapshot("dessert", "Postre", "STANDARD", preparationVersion = 1))
            drafts.save("v1", "local-order", emptyList(), mapOf("round" to listOf(com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine(item, null))),
                preparationRouting = mapOf("round" to com.avoqado.pos.printing.routing.PrintConfig()))
            drafts.promote("v1", "local-order", "order")
            val repo = repository(drafts)
            val uncommitted = repo.localPage("v1", "order")!!.items.single()
            assertNotNull(uncommitted.unavailableReason)
            coEvery { intents.preparationStatuses("v1", listOf("round")) } returns listOf(
                com.avoqado.pos.core.data.local.database.SyncIntentEntity("round", "v1", "staff-a", 1, "ADD_ITEMS", """{"localOrderId":"local-order"}"""))
            val promoted = repo.localPage("v1", "order")!!.items.single()
            assertEquals("order", promoted.orderId); assertEquals("local-order", promoted.localOrderId)
            assertEquals(null, promoted.unavailableReason)
            coEvery { intents.preparationStatuses("v1", listOf("round")) } returns listOf(
                com.avoqado.pos.core.data.local.database.SyncIntentEntity("round", "v1", "staff-a", 1, "ADD_ITEMS", """{"orderId":"different"}"""))
            assertNotNull(repo.localPage("v1", "order")!!.items.single().unavailableReason)
        } finally { root.deleteRecursively() }
    }

    private suspend fun rejected(repo: KitchenPreparationRepository) {
        val failure = runCatching { repo.act("v1", listOf(line), PreparationAction.START, 1) }.exceptionOrNull()
        assertNotNull("La sesión original debe seguir vigente antes de guardar preparación", failure)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `missing actor never persists a preparation action`() = runTest {
        actor = null
        rejected(repository())
    }
    @Test fun `actor changed during snapshot read cannot inherit the prior action`() = runTest {
        rejected(repository { actor = "staff-b" })
    }
    @Test fun `venue changed during snapshot read cannot persist the previous venue action`() = runTest {
        rejected(repository { venue = "v2" })
    }
    @Test fun `permission removed during snapshot read is rechecked before enqueue`() = runTest {
        rejected(repository { permitted = false })
    }

    @Test fun `valid preparation preserves its original actor`() = runTest {
        val result = repository().act("v1", listOf(line), PreparationAction.START, 1)
        assertEquals(1, result.single().preparation.PREPARING)
        coVerify(exactly = 1) {
            outbox.enqueue("v1", "KDS_ITEM_PROGRESS", any(), any(), false, null, any(), "staff-a")
        }
    }

    @Test fun `expired session retains confirmed capability without calling it offline`() = runTest {
        val repo = repository()
        every { call.execute() } answers {
            Response.Builder().request(Request.Builder().url("http://localhost/fulltest").build())
                .protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").body("{}".toResponseBody()).build()
        }
        repo.refreshCapabilities("v1")
        assertTrue(repo.negotiated("v1"))
        assertEquals("Tu sesión necesita validarse para sincronizar. Lo guardado se conserva.", repo.notice.value)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `slow capability request does not block a locally authorized action`() = runTest {
        val repo = repository()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        every { call.execute() } answers {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            throw IOException("offline")
        }
        val refresh = launch { repo.refreshCapabilities("v1") }
        try {
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(10000) { entered.await() } }
            val action = async { repo.act("v1", listOf(line), PreparationAction.START, 1) }
            runCurrent()
            assertTrue("Una consulta HTTP no debe retener la escritura local", action.isCompleted)
            assertEquals(1, action.await().single().preparation.PREPARING)
        } finally {
            release.countDown()
            refresh.join()
        }
    }
}
