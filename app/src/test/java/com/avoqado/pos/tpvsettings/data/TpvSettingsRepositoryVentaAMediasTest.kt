package com.avoqado.pos.tpvsettings.data

import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customerdisplay.CanonicalDeviceIdProvider
import com.avoqado.pos.customerdisplay.DisplayModeAuthorityGate
import com.avoqado.pos.customerdisplay.DisplayModeJournalState
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.customerdisplay.DisplayModeRequestJournal
import com.avoqado.pos.customerdisplay.JournalEntry
import com.avoqado.pos.settings.domain.PosMode
import com.avoqado.pos.navigation.mainContentKey
import com.avoqado.pos.pos.data.ActiveCartState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encargo ec5b28 (hz:248b195b:D18, founder «A», 21-nov): un cambio del papel del aparato o de su terminal que llega del
 * SERVIDOR mientras el aparato tiene una venta a medias NO cambia la llave de la navegación (rehacerla borra el carrito y
 * cierra el cobro). Se conserva lo de antes, se aplica lo demás y la huella queda SIN aplicar: el sondeo reintenta y el
 * cambio entra solo en cuanto el carrito queda vacío. Sin venta a medias, igual que siempre.
 */
class TpvSettingsRepositoryVentaAMediasTest {

    private val carrito = ActiveCartState()
    private val llamadas = AtomicInteger(0)
    private var respuestas: List<Pair<Int, String>> = emptyList()

    // ── cambio del papel de vales ─────────────────────────────────────────────────────────────

    @Test
    fun `con venta a medias el cambio de papel no mueve la llave de navegacion y la huella queda sin aplicar`() = runTest {
        val repo = repositorio(cobrador(huella = "h1"), emisor(huella = "h2"))
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        val antes = llave(repo)

        carrito.update(itemCount = 2, totalDisplay = "$80.00")
        repo.refreshIfFingerprintChanged(VENUE, "h2")

        assertEquals("la venta a medias se rehízo", antes, llave(repo))
        assertEquals(false, repo.terminalNavigation.value.canIssueAreaTickets)
        assertEquals("lo demás sí se aplica", false, repo.settings.value.showTipScreen)
        // La huella no quedó aplicada: el siguiente sondeo vuelve a pedir.
        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals(3, llamadas.get())
    }

    @Test
    fun `al vaciarse el carrito el siguiente sondeo aplica el cambio`() = runTest {
        val repo = repositorio(cobrador(huella = "h1"), emisor(huella = "h2"), emisor(huella = "h2"))
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        val antes = llave(repo)
        carrito.update(itemCount = 1, totalDisplay = "$40.00")
        repo.refreshIfFingerprintChanged(VENUE, "h2")

        carrito.clear()
        repo.refreshIfFingerprintChanged(VENUE, "h2")

        assertNotEquals(antes, llave(repo))
        assertEquals(true, repo.terminalNavigation.value.canIssueAreaTickets)
        assertEquals(TerminalNavigationSettings.AREA_OPERATIONS, repo.terminalNavigation.value.defaultWorkspace)
        // Ya aplicada: el siguiente sondeo con la misma huella no vuelve a pedir.
        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals(3, llamadas.get())
    }

    @Test
    fun `sin venta a medias el cambio entra de inmediato como hoy`() = runTest {
        val repo = repositorio(cobrador(huella = "h1"), emisor(huella = "h2"))
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        val antes = llave(repo)

        repo.refreshIfFingerprintChanged(VENUE, "h2")

        assertNotEquals(antes, llave(repo))
        assertEquals(true, repo.terminalNavigation.value.canIssueAreaTickets)
        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals("sin venta la huella queda aplicada", 2, llamadas.get())
    }

    @Test
    fun `con venta a medias y sin cambio en la llave la huella si queda aplicada`() = runTest {
        val repo = repositorio(cobrador(huella = "h1"), cobrador(huella = "h2", propina = false))
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        carrito.update(itemCount = 1, totalDisplay = "$40.00")

        repo.refreshIfFingerprintChanged(VENUE, "h2")
        repo.refreshIfFingerprintChanged(VENUE, "h2")

        assertEquals(false, repo.settings.value.showTipScreen)
        assertEquals(2, llamadas.get())
    }

    @Test
    fun `con venta a medias el emisor conserva su area y el menu del aparato si entra`() = runTest {
        val conMenu = emisor(huella = "h2").replace("\"fulfillmentAreaId\": \"area-cremeria\"", "\"fulfillmentAreaId\": \"area-cafe\", \"visibleMenuIds\": [\"menu-1\"]")
        val repo = repositorio(emisor(huella = "h1"), conMenu)
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        carrito.update(itemCount = 1, totalDisplay = "$40.00")

        repo.refreshIfFingerprintChanged(VENUE, "h2")

        assertEquals("area-cremeria", repo.terminalNavigation.value.fulfillmentAreaId)
        assertEquals(listOf("menu-1"), repo.terminalNavigation.value.visibleMenuIds)
    }

    // ── los dos caminos de fábrica ────────────────────────────────────────────────────────────

    @Test
    fun `con venta a medias desligar la terminal no regresa la navegacion a la de fabrica`() = runTest {
        val repo = repositorio(emisor(huella = "h1"), sinTerminal(huella = "h2"), sinTerminal(huella = "h2"))
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        val antes = llave(repo)
        carrito.update(itemCount = 3, totalDisplay = "$120.00")

        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals(antes, llave(repo))
        assertEquals("terminal-1", repo.terminalNavigation.value.terminalId)

        carrito.clear()
        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals(TerminalNavigationSettings.DEFAULT.terminalId, repo.terminalNavigation.value.terminalId)
        assertEquals(TerminalNavigationSettings.STANDARD_POS, repo.terminalNavigation.value.defaultWorkspace)
    }

    @Test
    fun `con venta a medias un 403 no regresa la navegacion a la de fabrica y el sondeo reintenta`() = runTest {
        respuestas = listOf(200 to emisor(huella = "h1"), 403 to """{"error":"Forbidden"}""", 403 to """{"error":"Forbidden"}""")
        val repo = repositorio()
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        val antes = llave(repo)
        carrito.update(itemCount = 1, totalDisplay = "$40.00")

        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals(antes, llave(repo))

        carrito.clear()
        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals(TerminalNavigationSettings.DEFAULT, repo.terminalNavigation.value)
    }

    @Test
    fun `sin venta a medias un 403 regresa a la de fabrica como hoy`() = runTest {
        respuestas = listOf(200 to emisor(huella = "h1"), 403 to """{"error":"Forbidden"}""")
        val repo = repositorio()
        repo.refreshIfFingerprintChanged(VENUE, "h1")

        repo.refreshIfFingerprintChanged(VENUE, "h2")

        assertEquals(TerminalNavigationSettings.DEFAULT, repo.terminalNavigation.value)
    }

    @Test
    fun `lo que se guarda en disco con la venta a medias es la llave de antes`() = runTest {
        val disco = mutableMapOf<String, String>()
        val repo = repositorio(cobrador(huella = "h1"), emisor(huella = "h2"), disco = disco)
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        carrito.update(itemCount = 1, totalDisplay = "$40.00")
        repo.refreshIfFingerprintChanged(VENUE, "h2")

        val guardada = disco.entries.first { it.key.startsWith("terminal_navigation") }.value
        assertFalse("el disco ya trae el papel nuevo", guardada.contains("\"canIssueAreaTickets\":true"))
    }

    // ── Codex r1 (docs/audit/2026-11-21-ajustes-con-venta-a-medias/codex-r1.md) ───────────────

    @Test
    fun `r1 3 al vaciarse el carrito lo que esperaba entra en ese momento sin esperar al sondeo`() = runBlocking {
        val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = repositorio(cobrador(huella = "h1"), emisor(huella = "h2"), emisor(huella = "h2"), alcance = alcance)
            repo.refreshIfFingerprintChanged(VENUE, "h1")
            carrito.update(itemCount = 1, totalDisplay = "$40.00")
            repo.refreshIfFingerprintChanged(VENUE, "h2")
            assertEquals(false, repo.terminalNavigation.value.canIssueAreaTickets)

            carrito.clear() // termina la venta; en una fila, el siguiente sondeo ya caería en la venta siguiente

            esperar("el papel nuevo no entró al vaciarse el carrito") { repo.terminalNavigation.value.canIssueAreaTickets }
            assertEquals(3, llamadas.get())
            // Ya sin nada en espera, terminar otra venta no pide ajustes de más.
            carrito.update(itemCount = 1, totalDisplay = "$40.00")
            carrito.clear()
            delay(300)
            assertEquals(3, llamadas.get())
        } finally {
            alcance.cancel()
        }
    }

    @Test
    fun `r1 4 desligado de verdad sin sondeo que pida ajustes al vaciarse entra la de fabrica`() = runBlocking {
        val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = repositorio(emisor(huella = "h1"), sinTerminal(huella = "h2"), sinTerminal(huella = "h2"), alcance = alcance)
            repo.refreshIfFingerprintChanged(VENUE, "h1")
            carrito.update(itemCount = 2, totalDisplay = "$80.00")
            // El sondeo da 404 y no pide ajustes; la lectura completa (volver al frente) cae en la venta.
            repo.refreshSettings()
            assertEquals("terminal-1", repo.terminalNavigation.value.terminalId)

            carrito.clear()

            esperar("el desligado no entró al vaciarse el carrito") { repo.terminalNavigation.value.terminalId == null }
            assertEquals(TerminalNavigationSettings.STANDARD_POS, repo.terminalNavigation.value.defaultWorkspace)
        } finally {
            alcance.cancel()
        }
    }

    @Test
    fun `r1 4 un 403 que conservo la navegacion tambien entra al vaciarse el carrito`() = runBlocking {
        val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            respuestas = listOf(200 to emisor(huella = "h1"), 403 to """{"error":"Forbidden"}""", 403 to """{"error":"Forbidden"}""")
            val repo = repositorio(alcance = alcance)
            repo.refreshIfFingerprintChanged(VENUE, "h1")
            carrito.update(itemCount = 1, totalDisplay = "$40.00")
            repo.refreshSettings()
            assertEquals("terminal-1", repo.terminalNavigation.value.terminalId)

            carrito.clear()

            esperar("el 403 no entró al vaciarse el carrito") { repo.terminalNavigation.value == TerminalNavigationSettings.DEFAULT }
        } finally {
            alcance.cancel()
        }
    }

    @Test
    fun `r1 1 con un cobro con saldo en vuelo y el carrito ya vacio el cambio de papel espera`() = runTest {
        val enVuelo = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val repo = repositorio(cobrador(huella = "h1"), emisor(huella = "h2"), emisor(huella = "h2"), enVuelo = { enVuelo.isNotEmpty() })
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        val antes = llave(repo)
        enVuelo.add("llave-del-cobro") // el cobro con saldo ya vació el carrito y se está mandando

        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals("el cobro con saldo se cerró", antes, llave(repo))

        enVuelo.clear()
        repo.refreshIfFingerprintChanged(VENUE, "h2") // el siguiente sondeo
        assertEquals(true, repo.terminalNavigation.value.canIssueAreaTickets)
    }

    @Test
    fun `r1 2 con la venta a medias y otra terminal el refresco del menu no le pone el menu de la nueva a la vieja`() = runTest {
        val repo = repositorio(cobrador(huella = "h1"), otraTerminal(huella = "h2"), otraTerminal(huella = "h2"))
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        carrito.update(itemCount = 1, totalDisplay = "$40.00")
        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals("terminal-1", repo.terminalNavigation.value.terminalId)

        repo.refrescarFiltroDelAparato(VENUE) // el refresco del catálogo de cada 5 min

        assertEquals("terminal-1", repo.terminalNavigation.value.terminalId)
        assertNull("se mezcló el menú de la otra terminal", repo.terminalNavigation.value.visibleMenuIds)
    }

    @Test
    fun `r1 5 un sondeo vencido que conservo el papel viejo no lo escribe en disco encima del nuevo`() = runBlocking {
        val disco = java.util.concurrent.ConcurrentHashMap<String, String>()
        val pausar = AtomicBoolean(false)
        val llego = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        val repo = repositorio(
            cobrador(huella = "h1"), emisor(huella = "h2"), emisor(huella = "h2"),
            disco = disco,
            pausa = { llave ->
                if (llave.startsWith("terminal_navigation") && pausar.compareAndSet(true, false)) {
                    llego.complete(Unit)
                    soltar.await()
                }
            },
        )
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        carrito.update(itemCount = 1, totalDisplay = "$40.00")
        pausar.set(true)
        // El sondeo recibe el papel nuevo con la venta a medias, conserva el viejo y se detiene antes de llegar al disco.
        val sondeo = launch(Dispatchers.Default) { repo.refreshIfFingerprintChanged(VENUE, "h2") }
        llego.await()
        // Termina la venta y otro refresco (volver al frente) guarda el nuevo y confirma su huella.
        carrito.clear()
        val alFrente = launch(Dispatchers.Default) { repo.refreshSettings() }
        withTimeoutOrNull(1_000) { while (!nuevoEnDisco(disco)) delay(10) }
        soltar.complete(Unit)
        sondeo.join()
        alFrente.join()

        assertEquals(true, repo.terminalNavigation.value.canIssueAreaTickets)
        assertTrue("el disco volvió al papel viejo", nuevoEnDisco(disco))
    }

    // ── ayudas ────────────────────────────────────────────────────────────────────────────────

    private fun llave(repo: TpvSettingsRepository) = mainContentKey(VENUE, PosMode.RETAIL, repo.terminalNavigation.value, 0)

    private fun nuevoEnDisco(disco: Map<String, String>) =
        disco.entries.firstOrNull { it.key.startsWith("terminal_navigation") }?.value?.contains("\"canIssueAreaTickets\":true") == true

    /** Tiempo real: el carrito se escucha en otro hilo. */
    private suspend fun esperar(motivo: String, condicion: () -> Boolean) {
        val listo = withTimeoutOrNull(5_000) { while (!condicion()) delay(10); true }
        assertTrue(motivo, listo == true)
    }

    private fun repositorio(
        vararg cuerpos: String,
        disco: MutableMap<String, String> = mutableMapOf(),
        alcance: CoroutineScope? = null,
        enVuelo: () -> Boolean = { false },
        pausa: (suspend (String) -> Unit)? = null,
    ): TpvSettingsRepository {
        if (cuerpos.isNotEmpty()) respuestas = cuerpos.map { 200 to it }
        return TpvSettingsRepository(
            secureStorage = sesion(),
            client = cliente(),
            preferencesDataStore = preferencias(disco, pausa),
            displayModePrefs = displayModePrefs(),
            displayModeJournal = journalVacio(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
            activeCartState = carrito,
            cobroDeTarjetaEnVuelo = enVuelo,
            alcance = alcance,
        )
    }

    private fun cliente(): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            val (codigo, cuerpo) = respuestas[llamadas.getAndIncrement().coerceAtMost(respuestas.size - 1)]
            mockk<Call> {
                every { execute() } returns Response.Builder()
                    .request(Request.Builder().url("https://example.test/settings").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(codigo)
                    .message(if (codigo in 200..299) "OK" else "Error")
                    .body(cuerpo.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
    }

    private fun cobrador(huella: String, propina: Boolean = true) = """
        { "success": true, "data": { "settings": { "showTipScreen": $propina },
          "deviceTerminal": { "id": "terminal-1", "defaultWorkspace": "STANDARD_POS",
            "canIssueAreaTickets": false, "canCheckoutAreaTickets": true, "canDeliverAreaTickets": false },
          "fingerprints": { "settings": "$huella" } } }
    """.trimIndent()

    private fun emisor(huella: String) = """
        { "success": true, "data": { "settings": { "showTipScreen": false },
          "deviceTerminal": { "id": "terminal-1", "defaultWorkspace": "AREA_OPERATIONS",
            "canIssueAreaTickets": true, "canCheckoutAreaTickets": false, "canDeliverAreaTickets": false,
            "fulfillmentAreaId": "area-cremeria" },
          "fingerprints": { "settings": "$huella" } } }
    """.trimIndent()

    private fun otraTerminal(huella: String) = """
        { "success": true, "data": { "settings": { "showTipScreen": true },
          "deviceTerminal": { "id": "terminal-2", "defaultWorkspace": "STANDARD_POS",
            "canIssueAreaTickets": false, "canCheckoutAreaTickets": true, "canDeliverAreaTickets": false,
            "visibleMenuIds": ["menu-2"] },
          "fingerprints": { "settings": "$huella" } } }
    """.trimIndent()

    private fun sinTerminal(huella: String) = """
        { "success": true, "data": { "settings": null, "fingerprints": { "settings": "$huella" } } }
    """.trimIndent()

    private fun sesion(): SecureStorage = mockk(relaxed = true) {
        every { accessToken } returns "access-token"
        every { venueId } returns VENUE
    }

    private fun preferencias(disco: MutableMap<String, String>, pausa: (suspend (String) -> Unit)?): PreferencesDataStore =
        mockk<PreferencesDataStore>(relaxed = true).also { p ->
            every { p.getString(any()) } answers { flowOf(disco[firstArg()]) }
            every { p.getBooleanOrNull(any()) } returns flowOf(null)
            coEvery { p.setString(any(), any()) } coAnswers {
                pausa?.invoke(firstArg())
                disco[firstArg()] = secondArg()
            }
            coEvery { p.removeString(any()) } answers { disco.remove(firstArg<String>()); Unit }
        }

    private fun displayModePrefs(): DisplayModePrefs = mockk(relaxed = true) {
        every { inverted } returns MutableStateFlow(false)
        every { dirty } returns MutableStateFlow(false)
        every { generation } returns MutableStateFlow(0L)
    }

    private fun journalVacio(): DisplayModeRequestJournal = object : DisplayModeRequestJournal {
        override fun readState(venueId: String, deviceId: String) = DisplayModeJournalState.Empty
        override fun save(entry: JournalEntry) = Unit
        override fun clear(venueId: String, deviceId: String, requestId: String) = Unit
    }

    private companion object {
        const val VENUE = "venue-a"
    }
}
