package com.avoqado.pos.kds.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.data.KdsHttpException
import com.avoqado.pos.kds.data.KdsPrefs
import com.avoqado.pos.kds.domain.AccionDeCocina
import com.avoqado.pos.kds.domain.AvisoDeCocina
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderItem
import com.avoqado.pos.kds.domain.KDSOrderStatus
import com.avoqado.pos.kds.domain.TextosDeCocina
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.StationInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import javax.inject.Provider

/**
 * La pantalla de cocina E (spec 2026-09-27 §4 «Tablet» y §7). El ViewModel NO sondea en `init`: la pantalla llama
 * `mientrasSeVe()`; aquí se llama `refrescar()` a mano, así que no hay bucle que colgar `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KDSViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val repo = mockk<KDSRepository>(relaxed = true)
    private val printConfig = mockk<PrintConfigRepository>(relaxed = true)
    private val prefs = mockk<KdsPrefs>(relaxed = true)
    private val roleManager = mockk<RoleManager>()
    private val planManager = mockk<PlanManager>()

    private val barra = StationInfo(id = "st-barra", name = "Barra", hasKitchenDisplay = true)
    private var config = PrintConfig(stations = listOf(barra), version = "v1")

    private fun comanda(id: String, creada: Long, needsAcceptance: Boolean = false, needsPrint: Boolean = false, sourceKey: String? = "sale:$id:st-barra") =
        KDSOrder(
            id = id, orderId = "o-$id", orderNumber = id, orderType = "En tienda",
            needsAcceptance = needsAcceptance, needsPrint = needsPrint,
            items = listOf(KDSOrderItem(id = "i-$id", productName = "Taco", quantity = 1)),
            createdAt = creada, status = KDSOrderStatus.NEW, sourceKey = sourceKey, printStationId = "st-barra",
        )

    private suspend fun armar(
        guardada: String? = "st-barra",
        comandas: List<KDSOrder> = listOf(comanda("k1", 1_000), comanda("k2", 2_000)),
    ): KDSViewModel {
        every { repo.venueIdActual() } returns "v1"
        every { printConfig.getCurrentConfig() } answers { config }
        every { prefs.estacion("v1") } returns guardada
        every { prefs.sonido } returns true
        every { prefs.letraGrande } returns false
        every { roleManager.role } returns "MANAGER"
        every { roleManager.canManagePrinters } returns true
        every { planManager.hasFeature("KITCHEN_DISPLAY") } returns true
        coEvery { repo.fetchOrders(any()) } returns Result.success(comandas)
        coEvery { repo.fetchDeliveryChannels() } returns Result.success(emptyList())
        return KDSViewModel(
            repo, mockk(relaxed = true), mockk(relaxed = true), printConfig, Provider { mockk(relaxed = true) },
            prefs, roleManager, planManager, mockk(relaxed = true),
        ).also { it.refrescar() }
    }

    @Test
    fun `P1 abrir la pantalla nunca la prende`() = runTest {
        config = PrintConfig(stations = listOf(barra.copy(hasKitchenDisplay = false)), version = "v1")
        val vm = armar()
        assertTrue(vm.vista.value is VistaDeCocina.SinPantalla)
        coVerify(exactly = 0) { repo.setKitchenDisplay(any(), any()) }
    }

    @Test
    fun `P1 la estacion guardada que ya no existe regresa al selector y lo dice`() = runTest {
        val vm = armar(guardada = "st-borrada")
        val vista = vm.vista.value as VistaDeCocina.ElegirEstacion
        assertTrue(vista.laGuardadaYaNoExiste)
        assertEquals(listOf("st-barra"), vista.estaciones.map { it.id })
        coVerify(exactly = 0) { repo.fetchOrders(any()) }
    }

    @Test
    fun `elegir una estacion la guarda en el aparato y trae SUS comandas`() = runTest {
        val vm = armar(guardada = null)
        vm.elegirEstacion("st-barra")
        verify { prefs.guardarEstacion("v1", "st-barra") }
        coVerify { repo.fetchOrders("st-barra") }
        assertEquals(listOf("k1", "k2"), vm.comandas.value.map { it.id })
    }

    @Test
    fun `P1 LISTO sin red regresa la comanda a su lugar y lo dice sin rojo`() = runTest {
        val puerta = CompletableDeferred<Unit>()
        coEvery { repo.bumpOrder("k1") } coAnswers { puerta.await(); Result.failure(IOException("sin red")) }
        val vm = armar()

        vm.listo("k1")
        assertEquals("optimista: se quita al instante", listOf("k2"), vm.comandas.value.map { it.id })
        puerta.complete(Unit)
        advanceUntilIdle()

        assertEquals("el servidor no se enteró: regresa a su lugar", listOf("k1", "k2"), vm.comandas.value.map { it.id })
        assertEquals(AvisoDeCocina(AccionDeCocina.LISTO.sinRed, esError = false), vm.aviso.value)
        assertTrue(vm.sinConexion.value)
    }

    @Test
    fun `LISTO con red quita la comanda y no avisa nada`() = runTest {
        coEvery { repo.bumpOrder("k1") } returns Result.success(Unit)
        val vm = armar()
        vm.listo("k1")
        assertEquals(listOf("k2"), vm.comandas.value.map { it.id })
        assertNull(vm.aviso.value)
    }

    @Test
    fun `P1 marcar todas deja fuera el delivery que nadie ha aceptado`() = runTest {
        coEvery { repo.bumpBatch(any()) } returns Result.success(1)
        val vm = armar(comandas = listOf(comanda("k1", 1_000), comanda("k2", 2_000, needsAcceptance = true)))
        vm.marcarTodasListas()
        coVerify { repo.bumpBatch(listOf("k1")) }
    }

    @Test
    fun `sin red el tablero conserva lo que ya tenia y lo dice, y al volver se quita solo`() = runTest {
        val vm = armar()
        coEvery { repo.fetchOrders(any()) } returns Result.failure(IOException("sin red"))
        vm.refrescar()
        assertEquals(listOf("k1", "k2"), vm.comandas.value.map { it.id })
        assertTrue(vm.sinConexion.value)

        coEvery { repo.fetchOrders(any()) } returns Result.success(emptyList())
        vm.refrescar()
        assertFalse(vm.sinConexion.value)
    }

    @Test
    fun `P1 sin red y sin config guardada lo dice en vez de decir que no hay estaciones`() = runTest {
        config = PrintConfig() // version "" = este aparato nunca vio la config
        val vm = armar(guardada = "st-barra")
        val vista = vm.vista.value as VistaDeCocina.ElegirEstacion
        assertTrue(vm.sinConexion.value)
        assertFalse("sin config no se afirma que la estación desapareció", vista.laGuardadaYaNoExiste)
    }

    @Test
    fun `P2 prender con doble toque manda UNA sola peticion`() = runTest {
        config = PrintConfig(stations = listOf(barra.copy(hasKitchenDisplay = false)), version = "v1")
        val puerta = CompletableDeferred<Unit>()
        coEvery { repo.setKitchenDisplay("st-barra", true) } coAnswers { puerta.await(); Result.success(Unit) }
        val vm = armar()

        vm.cambiarPantalla(prender = true)
        vm.cambiarPantalla(prender = true)
        puerta.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.setKitchenDisplay(any(), any()) }
        assertEquals(TextosDeCocina.prendida("Barra"), vm.exito.value)
        assertFalse(vm.cambiandoPantalla.value)
    }

    @Test
    fun `P1 el rechazo de la puerta de lanzamiento se explica, no se pinta como error`() = runTest {
        config = PrintConfig(stations = listOf(barra.copy(hasKitchenDisplay = false)), version = "v1")
        coEvery { repo.setKitchenDisplay(any(), any()) } returns Result.failure(KdsHttpException(403, "KITCHEN_DISPLAY_NOT_RELEASED", "x"))
        val vm = armar()
        vm.cambiarPantalla(prender = true)
        assertEquals(AvisoDeCocina(TextosDeCocina.NO_LANZADA, esError = false), vm.aviso.value)
        assertNull(vm.exito.value)
    }

    @Test
    fun `deshacer regresa la comanda a la cocina y la saca de Recientes`() = runTest {
        coEvery { repo.fetchRecientes("st-barra") } returns Result.success(listOf(comanda("k9", 500)))
        coEvery { repo.recall("k9") } returns Result.success(Unit)
        val vm = armar()

        vm.abrirRecientes()
        assertEquals(listOf("k9"), vm.recientes.value.map { it.id })
        vm.deshacer("k9")

        assertTrue(vm.recientes.value.isEmpty())
        coVerify(exactly = 2) { repo.fetchOrders("st-barra") } // la carga + la de después de deshacer
    }

    /**
     * La «Entrega» del POS se resolvió de RAÍZ en el servidor (`needsPrint` sólo para reparto de proveedor). Aquí se fija
     * que la tablet NO pone una guarda propia por folio: el día que Uber traiga folio, esa guarda dejaría de imprimirlo
     * en silencio.
     */
    @Test
    fun `P1 la cocina confia en needsPrint del servidor - reclama con o sin folio`() = runTest {
        armar(
            comandas = listOf(
                comanda("k1", 1_000, needsPrint = true, sourceKey = "sale:ext-1:st-barra"),
                comanda("k2", 2_000, needsPrint = true, sourceKey = null),
            ),
        )
        coVerify(exactly = 1) { repo.reclamarImpresion("k1", any()) }
        coVerify(exactly = 1) { repo.reclamarImpresion("k2", any()) }
    }

    @Test
    fun `sonido y letra grande se guardan en el aparato`() = runTest {
        val vm = armar()
        vm.toggleSound()
        vm.toggleLargeFont()
        verify { prefs.sonido = false }
        verify { prefs.letraGrande = true }
        assertEquals(KDSSettings(soundEnabled = false, largeFontEnabled = true), vm.settings.value)
    }
}
