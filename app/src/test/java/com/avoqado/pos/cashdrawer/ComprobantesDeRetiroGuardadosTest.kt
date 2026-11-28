package com.avoqado.pos.cashdrawer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.avoqado.pos.cashdrawer.data.ComprobanteDeRetiro
import com.avoqado.pos.cashdrawer.data.ComprobantesDeRetiroPorImprimir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** El papel ya impreso se guarda para «Reimprimir» (La Galeterie, 27-nov) sin volver a salir solo, y se tira a los 45 días. */
class ComprobantesDeRetiroGuardadosTest {

    @get:Rule val temporal = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy {
        ComprobantesDeRetiroPorImprimir(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { temporal.root.resolve("retiro_comprobantes.preferences_pb") }),
        )
    }

    @After fun fin() = scope.cancel()

    private fun c(localId: String, venueId: String = VENUE_ID) = ComprobanteDeRetiro(
        venueId = venueId, localId = localId, venueName = "La Tienda", nombreDelAparato = "CAJA", fechaTexto = "27/11/2026",
        horaTexto = "13:54", concepto = "Bajada", amountCents = 300_000, hizo = "Luis", rolDeQuienHizo = "CASHIER",
        autorizadoPor = "Vero",
    )

    private val dia = 24L * 60 * 60 * 1000

    @Test fun `impreso deja de estar pendiente y se encuentra por su id local o el del servidor`() = runBlocking {
        store.anotar(c("loc-1"))
        store.ligarAlServidor(VENUE_ID, "loc-1", "srv-1") // se confirmó antes de que saliera el papel
        store.marcarImpreso(c("loc-1"))

        assertTrue(store.pendientes(VENUE_ID).isEmpty())
        assertEquals("Vero", store.buscar(VENUE_ID, "loc-1")?.autorizadoPor)
        assertEquals("conserva el id del servidor del pendiente", "loc-1", store.buscar(VENUE_ID, "srv-1")?.localId)
        assertNull("nunca el de otro negocio", store.buscar("otro-venue", "loc-1"))
    }

    @Test fun `ligar despues de imprimir tambien sirve, y sin papel guardado no crea nada`() = runBlocking {
        store.anotar(c("loc-2"))
        store.marcarImpreso(c("loc-2"))
        store.ligarAlServidor(VENUE_ID, "loc-2", "srv-2")
        store.ligarAlServidor(VENUE_ID, "loc-nada", "srv-nada")

        assertEquals("loc-2", store.buscar(VENUE_ID, "srv-2")?.localId)
        assertNull(store.buscar(VENUE_ID, "srv-nada"))
        assertTrue(store.pendientes(VENUE_ID).isEmpty())
    }

    @Test fun `los impresos de mas de 45 dias se tiran al imprimir otro`() = runBlocking {
        val ahora = 1_800_000_000_000L
        store.marcarImpreso(c("viejo"), ahora = ahora - 46 * dia)
        store.marcarImpreso(c("reciente"), ahora = ahora - 44 * dia)
        store.marcarImpreso(c("hoy"), ahora = ahora)

        assertNull(store.buscar(VENUE_ID, "viejo"))
        assertEquals("reciente", store.buscar(VENUE_ID, "reciente")?.localId)
        assertEquals("hoy", store.buscar(VENUE_ID, "hoy")?.localId)
    }
}
