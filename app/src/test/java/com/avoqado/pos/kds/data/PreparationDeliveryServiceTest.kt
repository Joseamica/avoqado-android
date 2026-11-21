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
        val setVenue: (String) -> Unit, val setAllowed: (Boolean) -> Unit, val cache: CachedPayloadDao,
        val setConnected: (Boolean) -> Unit = {}, val restart: () -> PreparationDeliveryService = { service }) {
        fun job(): PreparationDeliveryJob = PreparationPeerProtocol.json.decodeFromString(records.getValue("preparation:v1:delivery:a").json)
    }
    private fun fixture(scope: kotlinx.coroutines.CoroutineScope, paperState: String = "QUEUED", phase: String = "PENDING", urgent: Boolean = false, urgencyPeer: Boolean = false,
        /** D16: estaciones que el SERVIDOR confirma como pantalla; `null` = el servicio se arma sin el estado (como antes). */
        screensFromServer: Set<String>? = null, connected: Boolean = true, ageMs: Long = 0, manualPaper: Boolean = false,
        urgentHeld: Boolean = false, urgentMixed: Boolean = false, stations: PrintConfig = config): Fixture {
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
        val action = if (urgent || urgentHeld || urgentMixed) PreparationAction.URGENT else PreparationAction.RELEASE
        val selected = when {
            urgent -> row.copy(preparation = PreparationCounts(PENDING = row.quantity))
            urgentMixed -> row.copy(preparation = PreparationCounts(HELD = 2, PENDING = 1))
            else -> row
        }
        val job = preparationDeliveryJob("v1", "original-waiter", "d1", "a", listOf(selected), action, 1, null, null, stations).copy(paperState = paperState,
            manualPaper = manualPaper, createdAtMillis = System.currentTimeMillis() - ageMs)
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
        val screens = screensFromServer?.let { set -> mockk<EstadoDePantallasDeCocina>().also { every { it.esPantallaDelServidor(any()) } answers { firstArg<String>() in set } } }
        var online = connected
        val monitor = mockk<com.avoqado.pos.core.util.ConnectivityMonitor>().also { every { it.isFullyConnected } answers { online } }
        fun build() = PreparationDeliveryService(db, storage, transport, printer, configRepo, client, roles, screens, monitor).also { it.useScopeForTest(scope) }
        return Fixture(build(), printer, client, records, origins, { venue = it }, { allowed = it }, cache, { online = it }, ::build)
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

    // D16 de Flautas (20-nov): liberar un tiempo a una cocina de SOLO pantalla y sin impresora. Con internet, la caja espera el
    // «sí» del servidor antes de decidir el papel y, con él, no avisa «Esa estación no tiene impresora». En duda, avisa.
    private fun withoutPrinter(f: Fixture) {
        coEvery { f.printer.reintentar(any(), any(), any()) } coAnswers {
            EstadoDeComanda.NoSalio(listOf("Cocina"), ReintentoDeComanda.CAUSA_SIN_IMPRESORA, "24")
                .also { it.saltados = firstArg<TrabajoPendiente>().planes }
        }
    }
    private fun acked(f: Fixture) { f.origins["a"] = f.origins.getValue("a").copy(status = SyncIntentEntity.STATUS_ACKED) }

    @Test fun `D16 con internet espera el si del servidor y no avisa que falta impresora`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s")); withoutPrinter(f)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("sin el si del servidor no se decide el papel", "QUEUED", f.job().paperState)
            assertNull(f.job().message); assertNull(f.service.notice.value)
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
            acked(f); f.service.pass("v1")
            assertEquals("DONE", f.job().paperState); assertNull(f.job().message); assertNull(f.service.notice.value)
            assertTrue(f.job().done)
            assertTrue("no entra a los papeles por revisar", f.service.paperPage("v1").items.isEmpty())
            coVerify(exactly = 1) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `D16 la espera pide otra pasada sola y decide al llegar el si`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s")); withoutPrinter(f)
        try {
            // Sólo el reloj de 30 s y la pasada que pide la espera: el «sí» llega después de la primera pasada.
            f.service.start("v1"); runCurrent()
            assertEquals("QUEUED", f.job().paperState)
            acked(f)
            advanceTimeBy(PreparationDeliveryService.PASO_DE_LA_ESPERA_MS + 500); runCurrent()
            assertEquals("DONE", f.job().paperState); assertNull(f.service.notice.value)
        } finally { f.service.stop() }
    }
    @Test fun `D16 sin internet avisa en el acto como hoy`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s"), connected = false); withoutPrinter(f)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("FAILED", f.job().paperState)
            assertEquals(ReintentoDeComanda.CAUSA_SIN_IMPRESORA, f.service.notice.value)
        } finally { f.service.stop() }
    }
    @Test fun `D16 si el envio falla a media espera decide en la siguiente pasada`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s")); withoutPrinter(f)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("QUEUED", f.job().paperState)
            f.setConnected(false); f.service.pass("v1")
            assertEquals("FAILED", f.job().paperState)
            assertEquals(ReintentoDeComanda.CAUSA_SIN_IMPRESORA, f.service.notice.value)
        } finally { f.service.stop() }
    }
    @Test fun `D16 pasado el tope avisa aunque el servidor no haya contestado`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s"), ageMs = PreparationDeliveryService.TOPE_DE_LA_ESPERA_MS + 1_000)
        withoutPrinter(f)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("FAILED", f.job().paperState)
            assertEquals(ReintentoDeComanda.CAUSA_SIN_IMPRESORA, f.service.notice.value)
        } finally { f.service.stop() }
    }
    @Test fun `D16 un rechazo a media espera sale como rechazo y no imprime`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s")); withoutPrinter(f)
        try {
            f.service.start("v1"); f.service.pass("v1")
            f.origins["a"] = f.origins.getValue("a").copy(status = SyncIntentEntity.STATUS_REJECTED, message = "Otro aparato cambió el producto")
            f.service.pass("v1")
            assertEquals("CANCELLED", f.job().paperState)
            assertTrue(f.service.notice.value!!.contains("no fue aceptada"))
            coVerify(exactly = 0) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `D16 una impresora que truena tras el si sigue avisando`() = runTest {
        val f = fixture(backgroundScope, phase = "ACKED", screensFromServer = setOf("s"))
        coEvery { f.printer.reintentar(any(), any(), any()) } coAnswers {
            EstadoDeComanda.NoSalio(listOf("Cocina"), "Sin papel", "24", firstArg())
        }
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("FAILED", f.job().paperState); assertEquals("Sin papel", f.service.notice.value)
        } finally { f.service.stop() }
    }
    @Test fun `D16 una estacion con impresora no espera el si`() = runTest {
        val withPrinter = PrintConfig(stations = listOf(StationInfo("s", "Cocina", printerId = "p1", hasKitchenDisplay = true)))
        val f = fixture(backgroundScope, screensFromServer = setOf("s"), stations = withPrinter); withoutPrinter(f)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("FAILED", f.job().paperState)
            coVerify(exactly = 1) { f.printer.reintentar(any(), any(), any()) }
        } finally { f.service.stop() }
    }
    @Test fun `D16 sin confirmacion del servidor avisa en el acto`() = runTest {
        val f = fixture(backgroundScope, phase = "ACKED", screensFromServer = emptySet()); withoutPrinter(f)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("FAILED", f.job().paperState)
            assertEquals(ReintentoDeComanda.CAUSA_SIN_IMPRESORA, f.service.notice.value)
        } finally { f.service.stop() }
    }
    @Test fun `D16 reimprimir no espera ni calla`() = runTest {
        val f = fixture(backgroundScope, phase = "ACKED", screensFromServer = setOf("s"), manualPaper = true); withoutPrinter(f)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("FAILED", f.job().paperState)
            assertEquals(ReintentoDeComanda.CAUSA_SIN_IMPRESORA, f.service.notice.value)
        } finally { f.service.stop() }
    }
    @Test fun `D16 la app murio a media espera y al volver decide`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s")); withoutPrinter(f)
        f.service.start("v1"); f.service.pass("v1"); f.service.stop()
        assertEquals("QUEUED", f.job().paperState)
        acked(f)
        val again = f.restart()
        try {
            again.start("v1"); again.pass("v1")
            assertEquals("con el si, calla", "DONE", f.job().paperState); assertNull(again.notice.value)
        } finally { again.stop() }
    }
    @Test fun `D16 la app murio a media espera y al volver pasado el tope avisa`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s"), ageMs = PreparationDeliveryService.TOPE_DE_LA_ESPERA_MS + 1_000)
        withoutPrinter(f)
        val again = f.restart()
        try {
            again.start("v1"); again.pass("v1")
            assertEquals("nunca se queda callada para siempre", "FAILED", f.job().paperState)
            assertEquals(ReintentoDeComanda.CAUSA_SIN_IMPRESORA, again.notice.value)
        } finally { again.stop() }
    }
    @Test fun `D16 tras el si la copia local refrescada del servidor no manda la liberacion a revisar`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s")); withoutPrinter(f)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("QUEUED", f.job().paperState)
            // Lo que deja `KitchenPreparationRepository.merge` al refrescar del servidor tras el ACK: su fila (revisión +1, la
            // cuenta ya movida) como base y el efecto local descartado.
            val key = "preparation:v1:${row.stableKey}"
            val fromServer = row.copy(preparation = row.preparation.move(PreparationAction.RELEASE, 1, null, null, "a"), preparationRevision = 1)
            f.records[key] = CachedPayloadEntity(key, "v1", json.encodeToString(PreparationLocalLine.serializer(), PreparationLocalLine(fromServer)), 200)
            acked(f); f.service.pass("v1")
            assertEquals("DONE", f.job().paperState); assertNull(f.service.notice.value)
        } finally { f.service.stop() }
    }
    @Test fun `D16 urgente de lo retenido espera el si igual que liberar`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s"), urgentHeld = true); withoutPrinter(f)
        try {
            assertNotNull(f.job().paper); assertEquals(false, f.job().urgencyNeedsScreen)
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("QUEUED", f.job().paperState)
            acked(f); f.service.pass("v1")
            assertEquals("DONE", f.job().paperState); assertNull(f.service.notice.value)
        } finally { f.service.stop() }
    }

    @Test fun `D16 una liberacion que espera no se queda atras aunque el cursor avance`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s")); withoutPrinter(f)
        val original = f.job()
        for (index in 1..60) {
            val id = "b-" + index.toString().padStart(3, '0')
            val key = "preparation:v1:delivery:$id"
            f.records[key] = CachedPayloadEntity(key, "v1", json.encodeToString(PreparationDeliveryJob.serializer(),
                original.copy(intentId = id, paperState = "FAILED")), 100)
        }
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("QUEUED", f.job().paperState)
            acked(f); f.service.pass("v1")
            assertEquals("la siguiente pasada la revisa primero", "DONE", f.job().paperState)
        } finally { f.service.stop() }
    }
    @Test fun `D16 dos liberaciones seguidas del mismo producto no mandan la primera a revisar`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s")); withoutPrinter(f)
        val key = "preparation:v1:${row.stableKey}"
        // La segunda liberación (otra unidad del mismo producto) se guardó mientras la primera esperaba el «sí».
        f.records[key] = CachedPayloadEntity(key, "v1", json.encodeToString(PreparationLocalLine.serializer(), PreparationLocalLine(row,
            listOf(PreparationEffect("a", 0, PreparationAction.RELEASE, 1), PreparationEffect("b", 1, PreparationAction.RELEASE, 1)))), 100)
        try {
            f.service.start("v1"); f.service.pass("v1")
            assertEquals("QUEUED", f.job().paperState)
            acked(f); f.service.pass("v1")
            assertEquals("DONE", f.job().paperState); assertNull(f.service.notice.value)
        } finally { f.service.stop() }
    }
    @Test fun `D16 la urgencia con productos activos no espera el si`() = runTest {
        val f = fixture(backgroundScope, screensFromServer = setOf("s"), urgentMixed = true); withoutPrinter(f)
        try {
            assertNotNull(f.job().paper); assertEquals(true, f.job().urgencyNeedsScreen)
            f.service.start("v1"); f.service.pass("v1")
            coVerify(exactly = 1) { f.printer.reintentar(any(), any(), any()) }
            assertEquals("FAILED", f.job().paperState)
        } finally { f.service.stop() }
    }
}
