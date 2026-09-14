package com.avoqado.pos.tpvsettings.data

import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.customerdisplay.DisplayModeAuthorityGate
import com.avoqado.pos.customerdisplay.CanonicalDeviceIdProvider
import com.avoqado.pos.customerdisplay.DisplayModeJournalState
import com.avoqado.pos.customerdisplay.DisplayModeRequestJournal
import com.avoqado.pos.customerdisplay.JournalEntry
import com.avoqado.pos.printing.receiptlayout.CanonicalLayout
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TpvSettingsRepositoryOfflineCacheTest {

    @Test
    fun `cold offline start restores last known terminal workspace and venue settings`() = runTest {
        val stored = mutableMapOf<String, String>()
        val preferences = preferencesBackedBy(stored)
        val storage = authenticatedStorage()

        val onlineRepository = TpvSettingsRepository(
            secureStorage = storage,
            client = clientReturning(areaOperationsResponse()),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        )
        onlineRepository.refreshSettingsForVenue(VENUE_ID)

        assertEquals(TerminalNavigationSettings.AREA_OPERATIONS, onlineRepository.terminalNavigation.value.defaultWorkspace)
        assertEquals(false, onlineRepository.settings.value.showReviewScreen)

        val coldOfflineRepository = TpvSettingsRepository(
            secureStorage = storage,
            client = clientThrowing(IOException("offline")),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        )
        coldOfflineRepository.refreshSettingsForVenue(VENUE_ID)

        assertEquals(TerminalNavigationSettings.AREA_OPERATIONS, coldOfflineRepository.terminalNavigation.value.defaultWorkspace)
        assertEquals(true, coldOfflineRepository.terminalNavigation.value.canIssueAreaTickets)
        assertEquals("area-creamery", coldOfflineRepository.terminalNavigation.value.fulfillmentAreaId)
        assertEquals(false, coldOfflineRepository.settings.value.showReviewScreen)
    }

    @Test
    fun `terminal cache is scoped by venue and never leaks into another venue`() = runTest {
        val stored = mutableMapOf<String, String>()
        val preferences = preferencesBackedBy(stored)
        val storage = authenticatedStorage()

        TpvSettingsRepository(
            secureStorage = storage,
            client = clientReturning(areaOperationsResponse()),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        ).refreshSettingsForVenue(VENUE_ID)

        val otherVenueRepository = TpvSettingsRepository(
            secureStorage = storage,
            client = clientThrowing(IOException("offline")),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        )
        otherVenueRepository.refreshSettingsForVenue("venue-b")

        assertEquals(TerminalNavigationSettings.DEFAULT, otherVenueRepository.terminalNavigation.value)
        assertEquals(TpvSettings.DEFAULT, otherVenueRepository.settings.value)
    }

    @Test
    fun `authoritative server denial does not preserve cached terminal capabilities`() = runTest {
        val stored = mutableMapOf<String, String>()
        val preferences = preferencesBackedBy(stored)
        val storage = authenticatedStorage()

        TpvSettingsRepository(
            secureStorage = storage,
            client = clientReturning(areaOperationsResponse()),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        ).refreshSettingsForVenue(VENUE_ID)

        val deniedRepository = TpvSettingsRepository(
            secureStorage = storage,
            client = clientReturning(body = "{\"error\":\"Forbidden\"}", statusCode = 403),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        )
        deniedRepository.refreshSettingsForVenue(VENUE_ID)

        assertEquals(TerminalNavigationSettings.DEFAULT, deniedRepository.terminalNavigation.value)

        val coldAfterDenial = TpvSettingsRepository(
            secureStorage = storage,
            client = clientThrowing(IOException("offline")),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        )
        coldAfterDenial.refreshSettingsForVenue(VENUE_ID)
        assertEquals(TerminalNavigationSettings.DEFAULT, coldAfterDenial.terminalNavigation.value)
    }

    @Test
    fun `P1 el ticket guardado sobrevive un arranque sin red sin refresh y no se filtra a otro venue`() = runTest {
        val preferences = preferencesBackedBy(mutableMapOf())
        repositoryWith(preferences, clientReturning(receiptLayoutResponse())).refreshSettingsForVenue(VENUE_ID)

        // Proceso nuevo, sin red y SIN haber llamado a refresh: imprimir lee el disco de la sucursal.
        val coldOffline = repositoryWith(preferences, clientThrowing(IOException("offline")))
        val ticket = coldOffline.receiptTicketFor(VENUE_ID)
        assertEquals(7, ticket.layout?.revision)
        assertEquals("TCA2501231A6", ticket.info?.fiscalEmisors?.single()?.rfc)
        assertNull(coldOffline.receiptTicketFor("venue-b").info)
        assertNull(coldOffline.receiptTicketFor("venue-b").layout)
    }

    @Test
    fun `P2 un 408 o un 429 no borran el ticket guardado y un 403 si`() = runTest {
        for (code in listOf(408, 429)) {
            val preferences = preferencesBackedBy(mutableMapOf())
            val repository = repositoryWith(preferences, clientReturning(receiptLayoutResponse()))
            repository.refreshSettingsForVenue(VENUE_ID)
            // Un límite de peticiones o un timeout son de la RED, no del permiso.
            repositoryWith(preferences, clientReturning(body = "{\"error\":\"retry\"}", statusCode = code)).refreshSettingsForVenue(VENUE_ID)
            assertEquals("tras un $code", 7, repositoryWith(preferences, clientThrowing(IOException("offline"))).receiptTicketFor(VENUE_ID).layout?.revision)
        }
        val preferences = preferencesBackedBy(mutableMapOf())
        repositoryWith(preferences, clientReturning(receiptLayoutResponse())).refreshSettingsForVenue(VENUE_ID)
        repositoryWith(preferences, clientReturning(body = "{\"error\":\"Forbidden\"}", statusCode = 403)).refreshSettingsForVenue(VENUE_ID)
        assertNull(repositoryWith(preferences, clientThrowing(IOException("offline"))).receiptTicketFor(VENUE_ID).layout)
    }

    @Test
    fun `P1 la respuesta tardia de la sucursal anterior no toca el ticket de la nueva`() = runTest {
        val preferences = preferencesBackedBy(mutableMapOf())
        val dos = DosRespuestas(
            primera = receiptLayoutResponse(revision = 7, rfc = "TCA2501231A6"),
            segunda = receiptLayoutResponse(revision = 3, rfc = "BBB010101AAA"),
        )
        val repository = repositoryWith(preferences, dos.client)

        segundaTerminaAntes(repository, dos, primeraVenue = VENUE_ID, segundaVenue = "venue-b")

        assertEquals(3, repository.receiptTicketFor("venue-b").layout?.revision)
        assertEquals("BBB010101AAA", repository.receiptTicketFor("venue-b").info?.fiscalEmisors?.single()?.rfc)
        // Lo de A queda en A, que es donde va.
        assertEquals(7, repository.receiptTicketFor(VENUE_ID).layout?.revision)
    }

    @Test
    fun `P2 dos refresh de la misma sucursal - la respuesta vieja que llega al final no restaura un ticket viejo`() = runTest {
        val preferences = preferencesBackedBy(mutableMapOf())
        val dos = DosRespuestas(
            primera = receiptLayoutResponse(revision = 7, rfc = "AAA010101OLD"),
            segunda = receiptLayoutResponse(revision = 8, rfc = "AAA010101NEW"),
        )
        segundaTerminaAntes(repositoryWith(preferences, dos.client), dos, primeraVenue = VENUE_ID, segundaVenue = VENUE_ID)

        val ticket = repositoryWith(preferences, clientThrowing(IOException("offline"))).receiptTicketFor(VENUE_ID)
        assertEquals(8, ticket.layout?.revision)
        assertEquals("AAA010101NEW", ticket.info?.fiscalEmisors?.single()?.rfc)
    }

    @Test
    fun `P2 una respuesta mas nueva que no trae el encabezado no bloquea el que trae una vieja`() = runTest {
        val preferences = preferencesBackedBy(mutableMapOf())
        val dos = DosRespuestas(
            primera = receiptLayoutResponse(revision = 7, rfc = "TCA2501231A6"),
            // El servidor omite el encabezado si no lo pudo resolver (devicePayload.service.ts).
            segunda = receiptLayoutResponse(revision = 8, withInfo = false),
        )
        segundaTerminaAntes(repositoryWith(preferences, dos.client), dos, primeraVenue = VENUE_ID, segundaVenue = VENUE_ID)

        val ticket = repositoryWith(preferences, clientThrowing(IOException("offline"))).receiptTicketFor(VENUE_ID)
        assertEquals(8, ticket.layout?.revision)
        assertEquals("TCA2501231A6", ticket.info?.fiscalEmisors?.single()?.rfc)
    }

    @Test
    fun `P2 una respuesta mas nueva sin nada del ticket no bloquea lo que trae una vieja`() = runTest {
        val preferences = preferencesBackedBy(mutableMapOf())
        val dos = DosRespuestas(
            primera = receiptLayoutResponse(revision = 7, rfc = "TCA2501231A6"),
            segunda = receiptLayoutResponse(withInfo = false, withLayout = false),
        )
        segundaTerminaAntes(repositoryWith(preferences, dos.client), dos, primeraVenue = VENUE_ID, segundaVenue = VENUE_ID)

        val ticket = repositoryWith(preferences, clientThrowing(IOException("offline"))).receiptTicketFor(VENUE_ID)
        assertEquals(7, ticket.layout?.revision)
        assertEquals("TCA2501231A6", ticket.info?.fiscalEmisors?.single()?.rfc)
    }

    @Test
    fun `P1 un 403 viejo que llega tarde no borra el ticket que una respuesta mas nueva ya guardo`() = runTest {
        val preferences = preferencesBackedBy(mutableMapOf())
        val dos = DosRespuestas(
            primera = "{\"error\":\"Forbidden\"}", codigoPrimera = 403,
            segunda = receiptLayoutResponse(revision = 8, rfc = "AAA010101NEW"),
        )
        segundaTerminaAntes(repositoryWith(preferences, dos.client), dos, primeraVenue = VENUE_ID, segundaVenue = VENUE_ID)

        val ticket = repositoryWith(preferences, clientThrowing(IOException("offline"))).receiptTicketFor(VENUE_ID)
        assertEquals(8, ticket.layout?.revision)
        assertEquals("AAA010101NEW", ticket.info?.fiscalEmisors?.single()?.rfc)
    }

    @Test
    fun `P1 un 403 viejo no se aplica cuando una respuesta mas nueva ya contesto bien aunque no trajera el encabezado`() = runTest {
        val preferences = preferencesBackedBy(
            mutableMapOf("receipt_info_$VENUE_ID" to """{"name":"Testarudo Cafe","rfc":"OLD010101OLD"}"""),
        )
        val dos = DosRespuestas(
            primera = "{\"error\":\"Forbidden\"}", codigoPrimera = 403,
            segunda = receiptLayoutResponse(revision = 8, withInfo = false),
        )
        segundaTerminaAntes(repositoryWith(preferences, dos.client), dos, primeraVenue = VENUE_ID, segundaVenue = VENUE_ID)

        // La respuesta nueva fue un 200: el aparato SÍ tiene acceso y el 403 viejo es información vencida.
        val ticket = repositoryWith(preferences, clientThrowing(IOException("offline"))).receiptTicketFor(VENUE_ID)
        assertEquals(8, ticket.layout?.revision)
        assertEquals("OLD010101OLD", ticket.info?.rfc)
    }

    @Test
    fun `P1 un 403 viejo no borra el ticket guardado aunque la respuesta mas nueva no trajera nada del ticket`() = runTest {
        val preferences = preferencesBackedBy(mutableMapOf())
        repositoryWith(preferences, clientReturning(receiptLayoutResponse(revision = 5))).refreshSettingsForVenue(VENUE_ID)
        val dos = DosRespuestas(
            primera = "{\"error\":\"Forbidden\"}", codigoPrimera = 403,
            segunda = receiptLayoutResponse(withInfo = false, withLayout = false),
        )
        segundaTerminaAntes(repositoryWith(preferences, dos.client), dos, primeraVenue = VENUE_ID, segundaVenue = VENUE_ID)

        val ticket = repositoryWith(preferences, clientThrowing(IOException("offline"))).receiptTicketFor(VENUE_ID)
        assertEquals(5, ticket.layout?.revision)
        assertEquals("TCA2501231A6", ticket.info?.fiscalEmisors?.single()?.rfc)
    }

    @Test
    fun `P1 una respuesta vieja que llega despues de un 403 mas nuevo no resucita el ticket`() = runTest {
        val preferences = preferencesBackedBy(mutableMapOf())
        val dos = DosRespuestas(
            primera = receiptLayoutResponse(revision = 7),
            segunda = "{\"error\":\"Forbidden\"}", codigoSegunda = 403,
        )
        segundaTerminaAntes(repositoryWith(preferences, dos.client), dos, primeraVenue = VENUE_ID, segundaVenue = VENUE_ID)

        val ticket = repositoryWith(preferences, clientThrowing(IOException("offline"))).receiptTicketFor(VENUE_ID)
        assertNull(ticket.layout)
        assertNull(ticket.info)
    }

    @Test
    fun `P2 un disco que falla no impide imprimir y el refresh sigue aplicando el resto de settings`() = runTest {
        val preferences = preferencesBackedBy(mutableMapOf()).also { p ->
            every { p.getString("receipt_ticket_$VENUE_ID") } returns flow { throw IOException("disco") }
            coEvery { p.updateStrings(any(), any()) } throws IOException("disco")
        }
        val repository = repositoryWith(
            preferences,
            clientReturning(receiptLayoutResponse(settings = "{ \"showReviewScreen\": false }")),
        )

        assertNull(repository.receiptTicketFor(VENUE_ID).layout) // la canónica, no una excepción
        repository.refreshSettingsForVenue(VENUE_ID)
        // El fallo del ticket no cortó el refresh: los settings que vienen DESPUÉS sí se aplicaron.
        assertEquals(false, repository.settings.value.showReviewScreen)
    }

    @Test
    fun `P1 la lectura y la escritura del ticket van bajo el MISMO candado`() = runTest {
        // 🔴 El arnés de las otras pruebas serializa en `execute()`, así que las dos corrutinas
        // nunca se encuentran DENTRO de la sección crítica: neutralizar el `Mutex` dejaba las
        // 2 332 pruebas de la app en verde (revisión de la Task 9, sabotaje S6). Ésta sí entra:
        // detiene el refresh con la escritura a medias y exige que imprimir TENGA que esperar.
        val escribiendo = CountDownLatch(1)
        val soltar = CountDownLatch(1)
        val soltadoPorLaPrueba = AtomicBoolean(false)
        val preferences = preferencesBackedBy(mutableMapOf()).also { p ->
            coEvery { p.updateStrings(any(), any()) } coAnswers {
                escribiendo.countDown()
                // Se queda DENTRO del candado hasta que la prueba lo suelte.
                soltadoPorLaPrueba.set(soltar.await(30, TimeUnit.SECONDS))
            }
        }
        val repository = repositoryWith(preferences, clientReturning(receiptLayoutResponse()))

        val refresh = async(Dispatchers.Default) { repository.refreshSettingsForVenue(VENUE_ID) }
        assertTrue(
            "el refresh nunca llego a escribir el ticket",
            withContext(Dispatchers.Default) { escribiendo.await(30, TimeUnit.SECONDS) },
        )

        // Con el candado puesto, quien imprime NO puede leer a medias: caduca.
        val leidoAMedias = withContext(Dispatchers.Default) {
            withTimeoutOrNull(500) { repository.receiptTicketFor(VENUE_ID) }
        }

        soltar.countDown()
        withContext(Dispatchers.Default) { withTimeout(30_000) { refresh.await() } }

        // Si el latch hubiera vencido solo, el orden no queda demostrado y la prueba lo dice.
        assertTrue("la escritura se solto por el plazo, no por la prueba", soltadoPorLaPrueba.get())
        assertNull(
            "receiptTicketFor entro mientras la escritura estaba a medias: no comparten candado",
            leidoAMedias,
        )
    }

    @Test
    fun `el encabezado guardado por la version anterior de la app sigue saliendo hasta la primera respuesta`() = runTest {
        val stored = mutableMapOf(
            "receipt_info_$VENUE_ID" to """{"name":"Testarudo Cafe","legalName":"TESTARUDO","rfc":"TCA2501231A6"}""",
        )
        val ticket = repositoryWith(preferencesBackedBy(stored), clientThrowing(IOException("offline"))).receiptTicketFor(VENUE_ID)
        assertEquals("TCA2501231A6", ticket.info?.rfc)
        assertNull(ticket.layout)
    }

    private fun preferencesBackedBy(stored: MutableMap<String, String>): PreferencesDataStore =
        mockk<PreferencesDataStore>(relaxed = true).also { preferences ->
            every { preferences.getString(any()) } answers { flowOf(stored[firstArg()]) }
            every { preferences.getBooleanOrNull(any()) } returns flowOf(null)
            coEvery { preferences.setString(any(), any()) } answers {
                stored[firstArg()] = secondArg()
            }
            coEvery { preferences.removeString(any()) } answers {
                stored.remove(firstArg())
            }
            coEvery { preferences.updateStrings(any(), any()) } coAnswers {
                applyUpdate(stored, firstArg(), secondArg())
            }
        }

    private fun authenticatedStorage(): SecureStorage = mockk<SecureStorage>(relaxed = true) {
        every { accessToken } returns "access-token"
    }

    /**
     * Sin cambios pendientes: estos tests cubren el cache de settings/terminal,
     * no la sincronización del modo de pantallas — que ese `dirty` esté en
     * `false` evita que la reconciliación intente empujar nada aquí.
     */
    private fun fakeDisplayModePrefs(): DisplayModePrefs = mockk<DisplayModePrefs>(relaxed = true) {
        every { inverted } returns MutableStateFlow(false)
        every { dirty } returns MutableStateFlow(false)
        every { generation } returns MutableStateFlow(0L)
    }

    private fun emptyJournal(): DisplayModeRequestJournal = object : DisplayModeRequestJournal {
        override fun readState(venueId: String, deviceId: String) = DisplayModeJournalState.Empty
        override fun save(entry: JournalEntry) = Unit
        override fun clear(venueId: String, deviceId: String, requestId: String) = Unit
    }

    private fun clientReturning(body: String, statusCode: Int = 200): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://example.test/settings").build())
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message(if (statusCode in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        return mockk {
            every { newCall(any()) } returns call
        }
    }

    private fun clientThrowing(error: IOException): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } throws error
        return mockk {
            every { newCall(any()) } returns call
        }
    }

    private fun areaOperationsResponse(): String =
        """
        {
          "success": true,
          "data": {
            "settings": { "showReviewScreen": false },
            "deviceTerminal": {
              "id": "terminal-creamery",
              "defaultWorkspace": "AREA_OPERATIONS",
              "canIssueAreaTickets": true,
              "canCheckoutAreaTickets": false,
              "canDeliverAreaTickets": true,
              "fulfillmentAreaId": "area-creamery"
            }
          }
        }
        """.trimIndent()

    /** Lo que hace `PreferencesDataStore.updateStrings`, sobre el mapa de la prueba. */
    private fun applyUpdate(
        stored: MutableMap<String, String>,
        keys: List<String>,
        transform: (Map<String, String?>) -> Map<String, String?>,
    ) {
        for ((key, value) in transform(keys.associateWith { stored[it] })) {
            if (value == null) stored.remove(key) else stored[key] = value
        }
    }

    private fun repositoryWith(preferences: PreferencesDataStore, client: OkHttpClient) = TpvSettingsRepository(
        secureStorage = authenticatedStorage(),
        client = client,
        preferencesDataStore = preferences,
        displayModePrefs = fakeDisplayModePrefs(),
        displayModeJournal = emptyJournal(),
        deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
        displayModeAuthorityGate = DisplayModeAuthorityGate(),
    )

    /**
     * Dos respuestas en orden de SALIDA a la red: la primera se queda en vuelo hasta que la prueba la
     * suelte (no por plazo: si vence, `soltadaPorLaPrueba` queda en false y la prueba lo reporta).
     */
    private inner class DosRespuestas(
        private val primera: String,
        private val segunda: String,
        private val codigoPrimera: Int = 200,
        private val codigoSegunda: Int = 200,
    ) {
        val primeraEnVuelo = CountDownLatch(1)
        val soltarPrimera = CountDownLatch(1)
        val soltadaPorLaPrueba = AtomicBoolean(false)
        private val llamadas = AtomicInteger(0)
        val client: OkHttpClient = mockk {
            every { newCall(any()) } answers {
                val numero = llamadas.incrementAndGet()
                mockk<Call> {
                    every { execute() } answers {
                        if (numero == 1) {
                            primeraEnVuelo.countDown()
                            soltadaPorLaPrueba.set(soltarPrimera.await(30, TimeUnit.SECONDS))
                            jsonResponse(primera, codigoPrimera)
                        } else {
                            jsonResponse(segunda, codigoSegunda)
                        }
                    }
                }
            }
        }
    }

    /** La primera petición sale, la segunda sale DESPUÉS y termina ANTES; luego termina la primera. */
    private suspend fun TestScope.segundaTerminaAntes(
        repository: TpvSettingsRepository,
        dos: DosRespuestas,
        primeraVenue: String,
        segundaVenue: String,
    ) {
        val primera = async(Dispatchers.Default) { repository.refreshSettingsForVenue(primeraVenue) }
        assertTrue("la primera nunca salió a la red", withContext(Dispatchers.Default) { dos.primeraEnVuelo.await(30, TimeUnit.SECONDS) })
        repository.refreshSettingsForVenue(segundaVenue)
        dos.soltarPrimera.countDown()
        withContext(Dispatchers.Default) { withTimeout(30_000) { primera.await() } }
        assertTrue("la primera se soltó por el plazo, no por la prueba", dos.soltadaPorLaPrueba.get())
    }

    /** Receta ÍNTEGRA (la canónica) con su revisión y un emisor con su RFC; cualquiera de los dos puede faltar. */
    private fun receiptLayoutResponse(
        revision: Int = 7,
        rfc: String = "TCA2501231A6",
        withInfo: Boolean = true,
        withLayout: Boolean = true,
        settings: String = "null",
    ): String {
        val info = """"receiptInfo": { "name": "Testarudo Cafe", "fiscalEmisors": [{ "id": "emA", "legalName": "TESTARUDO", "rfc": "$rfc", "lugarExpedicion": null, "merchantAccountIds": ["maA"] }], "principalEmisorId": "emA", "legacy": { "legalName": null, "rfc": null } }"""
        val layout = """"receiptLayout": { "schemaVersion": 1, "revision": $revision, "blocks": ${CanonicalLayout.JSON} }"""
        val fields = listOfNotNull("\"settings\": $settings", info.takeIf { withInfo }, layout.takeIf { withLayout }).joinToString(", ")
        return """{ "success": true, "data": { $fields } }"""
    }

    /** Una respuesta nueva por llamada: el cuerpo de OkHttp sólo se puede leer una vez. */
    private fun jsonResponse(body: String, statusCode: Int = 200): Response = Response.Builder()
        .request(Request.Builder().url("https://example.test/settings").build())
        .protocol(Protocol.HTTP_1_1)
        .code(statusCode)
        .message(if (statusCode in 200..299) "OK" else "Error")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private companion object {
        const val VENUE_ID = "venue-a"
    }
}
