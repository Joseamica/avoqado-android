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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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

/** «Menú del aparato» D2: el refresco SUAVE cambia sólo `visibleCategoryIds` / `visibleMenuIds` y nunca borra nada. */
class TpvSettingsRepositoryFiltroTest {

    private val stored = mutableMapOf<String, String>()

    @Volatile private var sesion = VENUE_ID

    /** Respuestas en orden de SALIDA a la red; `retenida` deja la petición en vuelo hasta que la prueba la suelte. */
    private class Escalonado(private val respuestas: List<Pair<() -> Response, CountDownLatch?>>) {
        val enVuelo = Array(respuestas.size) { CountDownLatch(1) }
        val peticiones = AtomicInteger(0)
        val client: OkHttpClient = mockk {
            every { newCall(any()) } answers {
                val i = peticiones.getAndIncrement()
                mockk<Call> {
                    every { execute() } answers {
                        enVuelo[i].countDown()
                        respuestas[i].second?.await(10, TimeUnit.SECONDS)
                        respuestas[i].first()
                    }
                }
            }
        }
    }

    private fun ok(body: String, code: Int = 200): () -> Response = {
        Response.Builder()
            .request(Request.Builder().url("https://example.test/settings").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private fun falla(): () -> Response = { throw IOException("offline") }

    private fun escalonado(vararg r: Pair<() -> Response, CountDownLatch?>) = Escalonado(r.toList())

    private fun repo(client: OkHttpClient) = TpvSettingsRepository(
        secureStorage = mockk<SecureStorage>(relaxed = true) {
            every { accessToken } returns "access-token"
            every { venueId } answers { sesion }
        },
        client = client,
        preferencesDataStore = preferences(),
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
        deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
        displayModeAuthorityGate = DisplayModeAuthorityGate(),
    )

    private fun preferences(): PreferencesDataStore = mockk(relaxed = true) {
        every { getString(any()) } answers { flowOf(stored[firstArg()]) }
        every { getBooleanOrNull(any()) } returns flowOf(null)
        coEvery { setString(any(), any()) } answers { stored[firstArg()] = secondArg() }
        coEvery { removeString(any()) } answers { stored.remove(firstArg()) }
    }

    private fun completa(cats: String, menus: String, canIssue: Boolean = true) =
        """{ "success": true, "data": { "settings": { "showReviewScreen": false },
          "deviceTerminal": { "id": "t-1", "defaultWorkspace": "AREA_OPERATIONS", "canIssueAreaTickets": $canIssue,
          "fulfillmentAreaId": "area-1", "visibleCategoryIds": $cats, "visibleMenuIds": $menus } } }"""

    private fun suave(cats: String, menus: String) =
        """{ "success": true, "data": { "deviceTerminal": { "id": "t-1", "visibleCategoryIds": $cats, "visibleMenuIds": $menus } } }"""

    @Test
    fun `refresco suave solo cambia los dos campos y no cambia mainContentKey`() = runBlocking {
        val http = escalonado(ok(completa("[\"a\"]", "[\"m1\"]")) to null, ok(suave("[\"b\",\"c\"]", "null")) to null)
        val r = repo(http.client)
        r.refreshSettingsForVenue(VENUE_ID)
        val antes = r.terminalNavigation.value

        r.refrescarFiltroDelAparato(VENUE_ID)

        assertEquals(antes.copy(visibleCategoryIds = listOf("b", "c"), visibleMenuIds = null), r.terminalNavigation.value)
        // lo guardado también lleva el filtro nuevo (arranque sin red)
        val fria = repo(escalonado(falla() to null).client)
        fria.refreshSettingsForVenue(VENUE_ID)
        assertEquals(listOf("b", "c"), fria.terminalNavigation.value.visibleCategoryIds)
    }

    @Test
    fun `un null mas null legitimo del servidor limpia el filtro`() = runBlocking {
        val r = repo(escalonado(ok(completa("[\"a\"]", "[\"m1\"]")) to null, ok(suave("null", "null")) to null).client)
        r.refreshSettingsForVenue(VENUE_ID)
        r.refrescarFiltroDelAparato(VENUE_ID)
        assertNull(r.terminalNavigation.value.visibleCategoryIds)
        assertNull(r.terminalNavigation.value.visibleMenuIds)
    }

    @Test
    fun `refresco suave ante un 403 no publica DEFAULT ni borra lo guardado`() = runBlocking {
        val r = repo(escalonado(ok(completa("[\"a\"]", "[\"m1\"]")) to null, ok("{\"error\":\"x\"}", 403) to null).client)
        r.refreshSettingsForVenue(VENUE_ID)
        val antes = r.terminalNavigation.value

        r.refrescarFiltroDelAparato(VENUE_ID)

        assertEquals(antes, r.terminalNavigation.value)
        val fria = repo(escalonado(falla() to null).client)
        fria.refreshSettingsForVenue(VENUE_ID)
        assertEquals(listOf("a"), fria.terminalNavigation.value.visibleCategoryIds)
    }

    @Test
    fun `refresco suave ante un 401 sin red o 500 o cuerpo roto no toca nada`() = runBlocking {
        val malas = listOf(
            ok("{}", 401), falla(), ok("{}", 500), ok("no es json"),
            ok("{\"success\":false,\"data\":null}"), ok("{\"success\":true,\"data\":{\"settings\":{}}}"),
        )
        for (mala in malas) {
            val r = repo(escalonado(ok(completa("[\"a\"]", "[\"m1\"]")) to null, mala to null).client)
            r.refreshSettingsForVenue(VENUE_ID)
            val antes = r.terminalNavigation.value
            r.refrescarFiltroDelAparato(VENUE_ID)
            assertEquals(antes, r.terminalNavigation.value)
        }
    }

    @Test
    fun `refresco suave mientras corre otro no pide dos veces`() = runBlocking {
        val soltar = CountDownLatch(1)
        val http = escalonado(ok(completa("[\"a\"]", "null")) to null, ok(suave("[\"b\"]", "null")) to soltar)
        val r = repo(http.client)
        r.refreshSettingsForVenue(VENUE_ID)

        val uno = async(Dispatchers.Default) { r.refrescarFiltroDelAparato(VENUE_ID) }
        assertTrue(http.enVuelo[1].await(10, TimeUnit.SECONDS))
        r.refrescarFiltroDelAparato(VENUE_ID) // el segundo se descarta
        soltar.countDown()
        uno.await()

        assertEquals(2, http.peticiones.get())
        assertEquals(listOf("b"), r.terminalNavigation.value.visibleCategoryIds)
    }

    @Test
    fun `suave se omite si hay un completo en curso`() = runBlocking {
        val soltar = CountDownLatch(1)
        val http = escalonado(ok(completa("[\"a\"]", "null")) to soltar)
        val r = repo(http.client)

        val completo = async(Dispatchers.Default) { r.refreshSettingsForVenue(VENUE_ID) }
        assertTrue(http.enVuelo[0].await(10, TimeUnit.SECONDS))
        r.refrescarFiltroDelAparato(VENUE_ID)
        soltar.countDown()
        completo.await()

        assertEquals("el suave no salió a la red", 1, http.peticiones.get())
        assertEquals(listOf("a"), r.terminalNavigation.value.visibleCategoryIds)
    }

    @Test
    fun `completo que empieza despues del suave gana y aplica todo`() = runBlocking {
        val soltarSuave = CountDownLatch(1)
        val soltarCompleto = CountDownLatch(1)
        val http = escalonado(
            ok(completa("[\"a\"]", "null", canIssue = false)) to null, // carga inicial
            ok(suave("[\"viejo\"]", "null")) to soltarSuave,
            ok(completa("[\"nuevo\"]", "null", canIssue = true)) to soltarCompleto,
        )
        val r = repo(http.client)
        r.refreshSettingsForVenue(VENUE_ID)

        val s = async(Dispatchers.Default) { r.refrescarFiltroDelAparato(VENUE_ID) }
        assertTrue(http.enVuelo[1].await(10, TimeUnit.SECONDS))
        val c = async(Dispatchers.Default) { r.refreshSettingsForVenue(VENUE_ID) }
        assertTrue(http.enVuelo[2].await(10, TimeUnit.SECONDS))
        soltarSuave.countDown()
        s.await() // llega con el completo en curso: se descarta
        soltarCompleto.countDown()
        c.await()

        assertEquals(listOf("nuevo"), r.terminalNavigation.value.visibleCategoryIds)
        assertTrue(r.terminalNavigation.value.canIssueAreaTickets)
    }

    @Test
    fun `suave que llega despues de un completo se descarta`() = runBlocking {
        val soltarSuave = CountDownLatch(1)
        val http = escalonado(
            ok(completa("[\"a\"]", "null")) to null,
            ok(suave("[\"viejo\"]", "null")) to soltarSuave,
            ok(completa("[\"nuevo\"]", "null")) to null,
        )
        val r = repo(http.client)
        r.refreshSettingsForVenue(VENUE_ID)

        val s = async(Dispatchers.Default) { r.refrescarFiltroDelAparato(VENUE_ID) }
        assertTrue(http.enVuelo[1].await(10, TimeUnit.SECONDS))
        r.refreshSettingsForVenue(VENUE_ID) // el completo empieza y termina mientras el suave vuela
        soltarSuave.countDown()
        s.await()

        assertEquals(listOf("nuevo"), r.terminalNavigation.value.visibleCategoryIds)
    }

    @Test
    fun `suave que llega tras un cambio de negocio se descarta`() = runBlocking {
        val soltar = CountDownLatch(1)
        val http = escalonado(ok(completa("[\"a\"]", "null")) to null, ok(suave("[\"b\"]", "null")) to soltar)
        val r = repo(http.client)
        r.refreshSettingsForVenue(VENUE_ID)

        val s = async(Dispatchers.Default) { r.refrescarFiltroDelAparato(VENUE_ID) }
        assertTrue(http.enVuelo[1].await(10, TimeUnit.SECONDS))
        sesion = "venue-b"
        soltar.countDown()
        s.await()

        assertEquals(listOf("a"), r.terminalNavigation.value.visibleCategoryIds)
    }

    private companion object {
        const val VENUE_ID = "venue-a"
    }
}
