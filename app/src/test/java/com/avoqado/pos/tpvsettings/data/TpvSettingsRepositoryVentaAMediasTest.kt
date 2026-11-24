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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encargo ec5b28 (hz:248b195b:D18, founder «A», 21-nov): un cambio del papel del aparato o de su terminal que llega del
 * SERVIDOR mientras el aparato tiene una venta a medias NO cambia la llave de la navegación (rehacerla borra el carrito y
 * cierra el cobro). Se conserva lo de antes, se aplica lo demás y la huella queda SIN aplicar: el sondeo reintenta y el
 * cambio entra solo en cuanto el carrito queda vacío. Sin venta a medias, igual que siempre.
 */
class TpvSettingsRepositoryVentaAMediasTest {

    private val carrito = ActiveCartState()
    /** El carrito de Cobrar: la señal guarda la parte de cada carrito por su dueño (encargo f7529d). */
    private val carritoDeCobrar = Any()
    private val llamadas = AtomicInteger(0)
    private var respuestas: List<Pair<Int, String>> = emptyList()

    // ── cambio del papel de vales ─────────────────────────────────────────────────────────────

    @Test
    fun `con venta a medias el cambio de papel no mueve la llave de navegacion y la huella queda sin aplicar`() = runTest {
        val repo = repositorio(cobrador(huella = "h1"), emisor(huella = "h2"))
        repo.refreshIfFingerprintChanged(VENUE, "h1")
        val antes = llave(repo)

        carrito.update(dueno = carritoDeCobrar, itemCount = 2, totalDisplay = "$80.00")
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
        carrito.update(dueno = carritoDeCobrar, itemCount = 1, totalDisplay = "$40.00")
        repo.refreshIfFingerprintChanged(VENUE, "h2")

        carrito.update(dueno = carritoDeCobrar, itemCount = 0, totalDisplay = "$0.00")
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
        carrito.update(dueno = carritoDeCobrar, itemCount = 1, totalDisplay = "$40.00")

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
        carrito.update(dueno = carritoDeCobrar, itemCount = 1, totalDisplay = "$40.00")

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
        carrito.update(dueno = carritoDeCobrar, itemCount = 3, totalDisplay = "$120.00")

        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals(antes, llave(repo))
        assertEquals("terminal-1", repo.terminalNavigation.value.terminalId)

        carrito.update(dueno = carritoDeCobrar, itemCount = 0, totalDisplay = "$0.00")
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
        carrito.update(dueno = carritoDeCobrar, itemCount = 1, totalDisplay = "$40.00")

        repo.refreshIfFingerprintChanged(VENUE, "h2")
        assertEquals(antes, llave(repo))

        carrito.update(dueno = carritoDeCobrar, itemCount = 0, totalDisplay = "$0.00")
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
        carrito.update(dueno = carritoDeCobrar, itemCount = 1, totalDisplay = "$40.00")
        repo.refreshIfFingerprintChanged(VENUE, "h2")

        val guardada = disco.entries.first { it.key.startsWith("terminal_navigation") }.value
        assertFalse("el disco ya trae el papel nuevo", guardada.contains("\"canIssueAreaTickets\":true"))
    }

    // ── ayudas ────────────────────────────────────────────────────────────────────────────────

    private fun llave(repo: TpvSettingsRepository) = mainContentKey(VENUE, PosMode.RETAIL, repo.terminalNavigation.value, 0)

    private fun repositorio(vararg cuerpos: String, disco: MutableMap<String, String> = mutableMapOf()): TpvSettingsRepository {
        if (cuerpos.isNotEmpty()) respuestas = cuerpos.map { 200 to it }
        return TpvSettingsRepository(
            secureStorage = sesion(),
            client = cliente(),
            preferencesDataStore = preferencias(disco),
            displayModePrefs = displayModePrefs(),
            displayModeJournal = journalVacio(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
            activeCartState = carrito,
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

    private fun sinTerminal(huella: String) = """
        { "success": true, "data": { "settings": null, "fingerprints": { "settings": "$huella" } } }
    """.trimIndent()

    private fun sesion(): SecureStorage = mockk(relaxed = true) {
        every { accessToken } returns "access-token"
        every { venueId } returns VENUE
    }

    private fun preferencias(disco: MutableMap<String, String>): PreferencesDataStore =
        mockk<PreferencesDataStore>(relaxed = true).also { p ->
            every { p.getString(any()) } answers { flowOf(disco[firstArg()]) }
            every { p.getBooleanOrNull(any()) } returns flowOf(null)
            coEvery { p.setString(any(), any()) } answers { disco[firstArg()] = secondArg() }
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
