package com.avoqado.pos.tpvsettings.data

import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customerdisplay.CanonicalDeviceIdProvider
import com.avoqado.pos.customerdisplay.DisplayModeAuthorityGate
import com.avoqado.pos.customerdisplay.DisplayModeJournalState
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.customerdisplay.DisplayModeRequestJournal
import com.avoqado.pos.customerdisplay.JournalEntry
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * «Pantallas del cobro» en Más > Configuración: el negocio apaga desde la propia tablet la pantalla
 * de calificación (o la de propina) sin tener que entrar al dashboard.
 *
 * Por qué se construyó (founder, 2026-09-18): el ajuste ya existía y esta app ya lo obedecía, pero
 * la pestaña de Configuración del dashboard sólo se muestra para terminales de COBRO
 * (`TPV_ANDROID`), así que para una tablet `POS_ANDROID` no había NINGUNA forma de apagarlo.
 *
 * 🔴 El valor vive en el SERVIDOR, en la ficha de este aparato — no en el aparato (decisión del
 * founder, opción A). Así sobrevive a reinstalar la app y el dashboard ve lo mismo. Consecuencia
 * directa que estas pruebas fijan: **sin red no se puede cambiar, y la app lo DICE** en vez de
 * fingir que guardó (`.claude/rules/todo-funciona-sin-red.md`: online-only a propósito).
 */
class TpvSettingsRepositoryPantallasDelCobroTest {

    @Test
    fun `el servidor dice qué ajustes admite este aparato y la app los recuerda`() = runTest {
        val repo = repositorio(clienteQueResponde(respuestaDeSettings()))
        repo.refreshSettingsForVenue(VENUE_ID)

        assertEquals(
            listOf("showReviewScreen", "showTipScreen"),
            repo.terminalNavigation.value.configurableSettings,
        )
    }

    @Test
    fun `un aparato al que el servidor no le deja configurar nada no recibe lista`() = runTest {
        val repo = repositorio(clienteQueResponde(respuestaDeSettings(configurables = "[]")))
        repo.refreshSettingsForVenue(VENUE_ID)

        assertTrue(repo.terminalNavigation.value.configurableSettings.isEmpty())
    }

    @Test
    fun `apagar la calificación la manda a la ficha de ESTE aparato y la app la refleja`() = runTest {
        val enviadas = mutableListOf<Request>()
        val repo = repositorio(clienteQueResponde(respuestaDeSettings(), enviadas = enviadas))
        repo.refreshSettingsForVenue(VENUE_ID)
        assertEquals(true, repo.settings.value.showReviewScreen)

        val resultado = repo.guardarPantallasDelCobro(showReviewScreen = false)

        assertEquals(AjusteGuardado.Ok, resultado)
        assertEquals(false, repo.settings.value.showReviewScreen)
        val patch = enviadas.last()
        assertEquals("PATCH", patch.method)
        assertTrue(
            "la ruta debe apuntar a la terminal de este aparato: ${patch.url}",
            patch.url.toString().endsWith("/mobile/venues/$VENUE_ID/terminals/terminal-tablet/settings"),
        )
    }

    @Test
    fun `P1 sin red NO se guarda y el ajuste no cambia en pantalla`() = runTest {
        val repo = repositorio(clienteQueResponde(respuestaDeSettings()))
        repo.refreshSettingsForVenue(VENUE_ID)

        val offline = repositorioReusando(repo, clienteQueRevienta(IOException("offline")))
        val resultado = offline.guardarPantallasDelCobro(showReviewScreen = false)

        assertEquals(AjusteGuardado.SinConexion, resultado)
        assertEquals(
            "un interruptor que se mueve sin haber guardado es una mentira en pantalla",
            true,
            offline.settings.value.showReviewScreen,
        )
    }

    @Test
    fun `un 403 se distingue de un problema de red para poder explicarlo`() = runTest {
        val repo = repositorio(clienteQueResponde(respuestaDeSettings()))
        repo.refreshSettingsForVenue(VENUE_ID)

        // El GET sigue funcionando (el aparato está sincronizado); es el PATCH el que rebota.
        val sinPermiso = repositorioReusando(
            repo,
            clientePorMetodo(get = respuestaDeSettings() to 200, patch = """{"message":"No autorizado"}""" to 403),
        )
        val resultado = sinPermiso.guardarPantallasDelCobro(showReviewScreen = false)

        assertEquals(AjusteGuardado.SinPermiso, resultado)
        assertEquals(true, sinPermiso.settings.value.showReviewScreen)
    }

    @Test
    fun `sin haber sincronizado nunca no hay ficha que escribir y no se manda nada`() = runTest {
        val enviadas = mutableListOf<Request>()
        val repo = repositorio(clienteQueResponde(respuestaDeSettings(), enviadas = enviadas))
        // A propósito SIN refreshSettingsForVenue: la app acaba de instalarse.

        val resultado = repo.guardarPantallasDelCobro(showReviewScreen = false)

        assertEquals(AjusteGuardado.SinFicha, resultado)
        assertTrue("no se puede escribir a ciegas una terminal que no conocemos", enviadas.isEmpty())
    }

    // ── andamiaje ──────────────────────────────────────────────────────────────────────────────

    private val preferencias = mutableMapOf<String, String>()

    private fun repositorio(client: OkHttpClient) = TpvSettingsRepository(
        secureStorage = mockk<SecureStorage>(relaxed = true) {
            every { accessToken } returns "access-token"
            every { venueId } returns VENUE_ID
        },
        client = client,
        preferencesDataStore = preferencesBackedBy(preferencias),
        displayModePrefs = mockk<DisplayModePrefs>(relaxed = true) {
            every { inverted } returns MutableStateFlow(false)
            every { dirty } returns MutableStateFlow(false)
            every { generation } returns MutableStateFlow(0L)
        },
        displayModeJournal = object : DisplayModeRequestJournal {
            override fun readState(venueId: String, deviceId: String) = DisplayModeJournalState.Empty
            override fun save(entry: JournalEntry) = Unit
            override fun clear(venueId: String, deviceId: String, requestId: String) = Unit
        },
        deviceIdProvider = CanonicalDeviceIdProvider { "device-tablet" },
        displayModeAuthorityGate = DisplayModeAuthorityGate(),
    )

    /** Otro repositorio sobre las MISMAS preferencias: el arranque en frío que ya tiene cache. */
    private suspend fun repositorioReusando(previo: TpvSettingsRepository, client: OkHttpClient): TpvSettingsRepository {
        val repo = repositorio(client)
        repo.refreshSettingsForVenue(VENUE_ID)
        return repo
    }

    private fun clienteQueResponde(
        body: String,
        statusCode: Int = 200,
        enviadas: MutableList<Request>? = null,
    ): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            val request = firstArg<Request>()
            enviadas?.add(request)
            mockk<Call> {
                every { execute() } returns Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode in 200..299) "OK" else "Error")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
    }

    /** Respuestas distintas por verbo: lo que hace falta para probar un PATCH que rebota con el GET sano. */
    private fun clientePorMetodo(
        get: Pair<String, Int>,
        patch: Pair<String, Int>,
    ): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            val request = firstArg<Request>()
            val (body, code) = if (request.method == "PATCH") patch else get
            mockk<Call> {
                every { execute() } returns Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "Error")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
    }

    private fun clienteQueRevienta(error: IOException): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            mockk<Call> { every { execute() } throws error }
        }
    }

    private fun respuestaDeSettings(configurables: String = """["showReviewScreen", "showTipScreen"]""") =
        """
        {
          "success": true,
          "data": {
            "settings": { "showReviewScreen": true, "showTipScreen": true },
            "deviceTerminal": {
              "id": "terminal-tablet",
              "defaultWorkspace": "STANDARD_POS",
              "configurableSettings": $configurables
            }
          }
        }
        """.trimIndent()

    /** Mismo doble que `TpvSettingsRepositoryOfflineCacheTest`: un mapa que sobrevive entre repos. */
    private fun preferencesBackedBy(stored: MutableMap<String, String>): PreferencesDataStore =
        mockk<PreferencesDataStore>(relaxed = true).also { preferences ->
            every { preferences.getString(any()) } answers { flowOf(stored[firstArg()]) }
            every { preferences.getBooleanOrNull(any()) } returns flowOf(null)
            coEvery { preferences.setString(any(), any()) } answers { stored[firstArg()] = secondArg() }
            coEvery { preferences.removeString(any()) } answers { stored.remove(firstArg()); Unit }
            coEvery { preferences.updateStrings(any(), any()) } coAnswers {
                val keys: List<String> = firstArg()
                val transform: (Map<String, String?>) -> Map<String, String?> = secondArg()
                transform(keys.associateWith { stored[it] }).forEach { (key, value) ->
                    if (value == null) stored.remove(key) else stored[key] = value
                }
            }
        }

    private companion object {
        const val VENUE_ID = "venue-1"
    }
}
