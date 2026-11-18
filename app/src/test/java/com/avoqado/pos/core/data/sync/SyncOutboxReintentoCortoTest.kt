package com.avoqado.pos.core.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.util.ConnectivityMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * na31f0d7 (tarjeta de la nube, 17-nov; ensayo B1 del 16-nov): la pantalla de cocina marca LISTO sin internet. Al volver la red,
 * la marca (`KDS_ITEM_PROGRESS`) sale, pero el SERVIDOR todavía no tiene la comanda que mandó la OTRA caja y contesta «reintenta»
 * (`PREPARATION_DEPENDENCY_PENDING`). La cola deja la marca pendiente y espera «el próximo disparador»: ya no hay reconexión ni
 * nada nuevo que encolar, así que el siguiente es el reloj de seguridad de 5 min. En el ensayo: LISTO en el servidor ~4 min tarde.
 *
 * Un «reintenta» del servidor es transitorio por definición: la cola vuelve a intentar sola en segundos, no en minutos.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncOutboxReintentoCortoTest {
    private val dao = mockk<SyncIntentDao>(relaxed = true)
    private val api = mockk<ApiService>(relaxed = true)
    init { vincularCallFixtureQ16(api) }
    private var marca = SyncIntentEntity("listo-1", "v1", "staff", 1, SyncIntentTypes.KDS_ITEM_PROGRESS, """{"externalId":"sync:r:0"}""", createdAt = 1)

    private fun subject(): SyncOutbox {
        val connectivity = mockk<ConnectivityMonitor>()
        every { connectivity.isConnected } returns MutableStateFlow(true)
        every { connectivity.isServerReachable } returns MutableStateFlow(true)
        val drawer = mockk<CashDrawerRepository>()
        coEvery { drawer.sincronizarCajonPrimero() } returns true
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "d1"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "v1"
        val cached = mockk<CachedPayloadDao>()
        coEvery { cached.get("preparation_capabilities:v1") } returns
            CachedPayloadEntity("preparation_capabilities:v1", "v1", """{"version":1,"enabled":true,"replayLaneVersion":1}""", 1)
        coEvery { dao.pendingFifoLane("v1", false, any()) } returns emptyList()
        coEvery { dao.pendingFifoLane("v1", true, any()) } answers { if (marca.status == "PENDING") listOf(marca) else emptyList() }
        coEvery { dao.resolve(any(), any(), any(), any(), any()) } answers { marca = marca.copy(status = arg(1)); Unit }
        return SyncOutbox(dao, api, connectivity, storage, drawer, context, cached).also {
            SyncOutbox::class.java.getDeclaredField("activeVenueId").apply { isAccessible = true }.set(it, "v1")
        }
    }

    @Test fun `P1 un reintenta del servidor sobre la marca LISTO se vuelve a intentar en menos de un minuto`() = runTest {
        val cola = subject()
        cola.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler))
        coEvery { api.syncIntents("v1", any(), any()) } returnsMany listOf(
            SyncIntentsResponse(data = listOf(SyncAck("listo-1", "RETRY", "PREPARATION_DEPENDENCY_PENDING", "Falta la comanda"))),
            SyncIntentsResponse(data = listOf(SyncAck("listo-1", "ACKED"))),
        )
        try {
            cola.replayNow("v1") // vuelve la red: sale la marca y el servidor dice «reintenta»
            coVerify(exactly = 1) { api.syncIntents("v1", any(), any()) }
            assertEquals("PENDING", marca.status)

            advanceTimeBy(1_000); runCurrent()
            coVerify(exactly = 1) { api.syncIntents("v1", any(), any()) } // sin bucle caliente

            advanceTimeBy(60_000); runCurrent()
            coVerify(exactly = 2) { api.syncIntents("v1", any(), any()) }
            assertEquals("ACKED", marca.status)
        } finally { cola.stop() }
    }
}
