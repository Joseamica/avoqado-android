package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.lan.KdsComanda
import com.avoqado.pos.core.data.lan.TransporteLan
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El receptor vive fuera del ViewModel (D8): se engancha al transporte con la estación del Tablero y guarda ANTES de acusar. */
class ReceptorDeComandasTest {

    private val transporte = mockk<TransporteLan>(relaxed = true) { every { receptorActivo } returns MutableStateFlow(false) }
    private val store = mockk<KdsTicketsLocalesStore>(relaxed = true)
    private val receptor = ReceptorDeComandas(transporte, store)

    @Test
    fun `activar anuncia la estacion y el manejador guarda y solo entonces devuelve true`() = runTest {
        val manejador = slot<suspend (KdsComanda) -> Boolean>()
        every { transporte.activarReceptor(setOf("st-barra"), capture(manejador)) } returns Unit
        coEvery { store.unir(any(), any()) } returns true

        receptor.activar("v1", "st-barra")

        val comanda = KdsComanda(venueId = "v1", deviceId = "d", sourceKey = "sale:x:st-barra", stationId = "st-barra", orderNumber = "1", orderType = "En tienda", createdAtMillis = 1, items = emptyList())
        assertTrue(manejador.captured(comanda))
        coEvery { store.unir(any(), any()) } returns false
        assertFalse(manejador.captured(comanda))
        verify { transporte.activarReceptor(setOf("st-barra"), any()) }
    }

    @Test
    fun `desactivar apaga el anuncio`() {
        receptor.desactivar()
        verify { transporte.desactivarReceptor() }
    }
}
