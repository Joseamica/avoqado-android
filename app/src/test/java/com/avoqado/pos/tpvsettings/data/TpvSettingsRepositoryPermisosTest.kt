package com.avoqado.pos.tpvsettings.data

import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customerdisplay.CanonicalDeviceIdProvider
import com.avoqado.pos.customerdisplay.DisplayModeAuthorityGate
import com.avoqado.pos.customerdisplay.DisplayModeJournalState
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.customerdisplay.DisplayModeRequestJournal
import com.avoqado.pos.customerdisplay.JournalEntry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TpvSettingsRepositoryPermisosTest {
    @Test fun `los permisos de la sesion reemplazan la lista y la marcan vigente`() = runTest {
        val fixture = Fixture()
        fixture.body = """{"data":{"permisosDeLaSesion":["cash-drawer:pay-out","orders:read"]}}"""
        fixture.repository().refreshSettingsForVenue("venue-a")
        assertEquals(listOf("cash-drawer:pay-out", "orders:read"), fixture.permisos)
        assertTrue(fixture.vigente)
    }

    @Test fun `una lista vacia tambien es vigente`() = runTest {
        val fixture = Fixture()
        fixture.body = """{"data":{"permisosDeLaSesion":[]}}"""
        fixture.repository().refreshSettingsForVenue("venue-a")
        assertEquals(emptyList<String>(), fixture.permisos)
        assertTrue(fixture.vigente)
    }

    @Test fun `si entro otra persona o cambio el venue mientras viajaba la respuesta, no se tocan`() = runTest {
        val otraPersona = Fixture()
        otraPersona.body = """{"data":{"permisosDeLaSesion":["cash-drawer:pay-out"]}}"""
        otraPersona.beforeResponse = { otraPersona.generacion++ }
        otraPersona.repository().refreshSettingsForVenue("venue-a")
        assertEquals(listOf("viejo"), otraPersona.permisos)
        assertFalse(otraPersona.vigente)

        val otroVenue = Fixture()
        otroVenue.body = """{"data":{"permisosDeLaSesion":["cash-drawer:pay-out"]}}"""
        otroVenue.beforeResponse = { otroVenue.venue = "venue-b" }
        otroVenue.repository().refreshSettingsForVenue("venue-a")
        assertEquals(listOf("viejo"), otroVenue.permisos)
        assertFalse(otroVenue.vigente)
    }

    @Test fun `sin el campo, nada cambia`() = runTest {
        val fixture = Fixture()
        fixture.body = """{"data":{}}"""
        fixture.repository().refreshSettingsForVenue("venue-a")
        assertEquals(0, fixture.escrituras)
        assertEquals(listOf("viejo"), fixture.permisos)
        assertFalse(fixture.vigente)
    }

    private class Fixture {
        var venue = "venue-a"
        var generacion = 7L
        var permisos: List<String> = listOf("viejo")
        var vigente = false
        var escrituras = 0
        var body = """{"data":{}}"""
        var failure: IOException? = null
        var beforeResponse: () -> Unit = {}
        private val storage = mockk<SecureStorage>(relaxed = true).also { s ->
            every { s.accessToken } returns "test-token"
            every { s.venueId } answers { venue }
            every { s.generacion } answers { generacion }
            every { s.guardarPermisosDeLaSesionSi(any(), any(), any()) } answers {
                // Misma regla que SecureStorage: sólo si sigue la sesión y la sucursal que mandaron la petición.
                val ok = firstArg<Long>() == generacion && secondArg<String>() == venue
                if (ok) { permisos = thirdArg(); vigente = true; escrituras++ }
                ok
            }
        }

        fun repository(): TpvSettingsRepository {
            val preferences = mockk<PreferencesDataStore>(relaxed = true) {
                every { getString(any()) } returns flowOf(null)
                every { getBooleanOrNull(any()) } returns flowOf(null)
            }
            val client = mockk<OkHttpClient> {
                every { newCall(any()) } answers {
                    val request = firstArg<Request>()
                    mockk<Call> {
                        every { execute() } answers {
                            beforeResponse()
                            failure?.let { throw it }
                            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                                .body(body.toResponseBody("application/json".toMediaType())).build()
                        }
                    }
                }
            }
            return TpvSettingsRepository(
                secureStorage = storage,
                client = client,
                preferencesDataStore = preferences,
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
        }
    }
}
