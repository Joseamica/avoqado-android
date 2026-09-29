package com.avoqado.pos.kds.presentation

import android.media.RingtoneManager
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.data.KdsHttpException
import com.avoqado.pos.kds.data.KdsPrefs
import com.avoqado.pos.kds.data.KdsTicketsLocalesStore
import com.avoqado.pos.kds.data.ReceptorDeComandas
import com.avoqado.pos.kds.domain.AccionDeCocina
import com.avoqado.pos.kds.domain.AvisoDeCocina
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderItem
import com.avoqado.pos.kds.domain.KDSOrderStatus
import com.avoqado.pos.kds.domain.KdsTicketLocal
import com.avoqado.pos.kds.domain.TextosDeCocina
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.StationInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val receptorActivo = MutableStateFlow(false)
    private val receptor = mockk<ReceptorDeComandas>(relaxed = true) { every { activo } returns receptorActivo }
    private val locales = MutableStateFlow<List<KdsTicketLocal>>(emptyList())
    private val ticketsLocales = mockk<KdsTicketsLocalesStore>(relaxed = true) {
        every { deLaEstacion(any(), any()) } returns locales
        // Como Room: lo que se escribe vuelve a emitir el flujo de la estación.
        coEvery { retirarPendientes(any()) } answers {
            val folios = firstArg<Collection<String>>()
            locales.value = locales.value.filterNot { it.listaEnMillis == null && it.sourceKey in folios }
        }
        coEvery { quitarLista(any()) } answers {
            val folio = firstArg<String>()
            locales.value = locales.value.map { if (it.sourceKey == folio) it.copy(listaEnMillis = null) else it }
        }
    }
    private val cola = mockk<SyncOutbox>(relaxed = true)

    private fun local(folio: String, recibida: Long, lista: Long? = null) = KdsTicketLocal(
        sourceKey = folio, venueId = "v1", stationId = "st-barra", orderNumber = "77", orderType = "Mesa 8",
        items = listOf(KDSOrderItem("l-1", "Café", 2)), recibidaEnMillis = recibida, listaEnMillis = lista,
    )

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
            repo, mockk(relaxed = true), mockk(relaxed = true), printConfig, Provider { cola },
            prefs, roleManager, planManager, mockk(relaxed = true),
            receptor, ticketsLocales,
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
    fun `P1 LISTO sin red SIN folio (Uber) regresa la comanda a su lugar y lo dice sin rojo`() = runTest {
        val puerta = CompletableDeferred<Unit>()
        coEvery { repo.bumpOrder("k1") } coAnswers { puerta.await(); Result.failure(IOException("sin red")) }
        val vm = armar(comandas = listOf(comanda("k1", 1_000, sourceKey = null), comanda("k2", 2_000)))

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
    fun `P1 recientes sin red no dice que no hubo nada`() = runTest {
        coEvery { repo.fetchRecientes("st-barra") } returns Result.failure(IOException("sin red"))
        val vm = armar()

        vm.abrirRecientes()

        assertTrue("no se toca lo que ya había, pero tampoco se afirma vacío", vm.recientes.value.isEmpty())
        assertEquals(AvisoDeCocina(AccionDeCocina.RECIENTES.sinRed, esError = false), vm.recientesNoLeidas.value)
    }

    @Test
    fun `recientes leer con exito limpia el aviso de fallo anterior`() = runTest {
        coEvery { repo.fetchRecientes("st-barra") } returns Result.failure(IOException("sin red"))
        val vm = armar()
        vm.abrirRecientes()
        assertTrue(vm.recientesNoLeidas.value != null)

        coEvery { repo.fetchRecientes("st-barra") } returns Result.success(listOf(comanda("k9", 500)))
        vm.abrirRecientes()

        assertNull(vm.recientesNoLeidas.value)
        assertEquals(listOf("k9"), vm.recientes.value.map { it.id })
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
    fun `P1 M3 reabrir la pantalla no suena por lo que llego mientras estuvo cerrada`() = runTest {
        mockkStatic(RingtoneManager::class)
        every { RingtoneManager.getDefaultUri(any()) } returns null
        every { RingtoneManager.getRingtone(any(), any()) } returns null
        try {
            // Primera apertura (armar() ya deja hasLoadedFromAPI=true y previousOrderIds={k1,k2}). El VM SIGUE VIVO
            // con «Más» cuando se cierra la pantalla — es la misma instancia la que se vuelve a abrir.
            val vm = armar()

            // Mientras la pantalla estuvo cerrada llegó k3 — sin el fix, hasLoadedFromAPI sigue en true y k3
            // sonaría como comanda nueva en cuanto se reabre.
            coEvery { repo.fetchOrders(any()) } returns Result.success(
                listOf(comanda("k1", 1_000), comanda("k2", 2_000), comanda("k3", 3_000)),
            )

            val job = launch { vm.mientrasSeVe() } // reabrir
            runCurrent()
            job.cancelAndJoin()

            assertEquals(listOf("k1", "k2", "k3"), vm.comandas.value.map { it.id })
            verify(exactly = 0) { RingtoneManager.getDefaultUri(any()) }
        } finally {
            unmockkStatic(RingtoneManager::class)
        }
    }

    @Test
    fun `P1 M4 un sondeo viejo no resucita una comanda que se acaba de marcar LISTO`() = runTest {
        coEvery { repo.bumpOrder("k1") } returns Result.success(Unit)
        val vm = armar() // k1, k2

        vm.listo("k1")
        assertEquals(listOf("k2"), vm.comandas.value.map { it.id })

        // Un sondeo que arrancó ANTES del bump regresa con k1 todavía en la lista (dato viejo).
        coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k1", 1_000), comanda("k2", 2_000)))
        vm.refrescar()

        assertEquals("el sondeo viejo no resucita lo recién marcado", listOf("k2"), vm.comandas.value.map { it.id })

        // Como ya no está en el tablero, un segundo toque es un no-op — no manda otro bump ni pinta error rojo.
        vm.listo("k1")
        coVerify(exactly = 1) { repo.bumpOrder("k1") }
        assertNull(vm.aviso.value)
    }

    @Test
    fun `M4 cuando el servidor deja de mandarla ya no hace falta esperar la ventana`() = runTest {
        coEvery { repo.bumpOrder("k1") } returns Result.success(Unit)
        val vm = armar() // k1, k2
        vm.listo("k1")

        // El siguiente sondeo ya no trae k1: el servidor la confirmó.
        coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k2", 2_000)))
        vm.refrescar()
        assertEquals(listOf("k2"), vm.comandas.value.map { it.id })

        // Si "k1" reapareciera después (server-side genuinamente distinto), ya no está protegida por la ventana.
        coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k1", 1_000), comanda("k2", 2_000)))
        vm.refrescar()
        assertEquals(listOf("k1", "k2"), vm.comandas.value.map { it.id })
    }

    @Test
    fun `P1 Ronda 3 marcar todas protege el lote de un sondeo viejo`() = runTest {
        coEvery { repo.bumpBatch(any()) } returns Result.success(2)
        val vm = armar() // k1, k2

        vm.marcarTodasListas()
        assertEquals(emptyList<String>(), vm.comandas.value.map { it.id })

        // Un sondeo con dato viejo (arrancó antes del lote) sigue trayendo k1 y k2.
        coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k1", 1_000), comanda("k2", 2_000)))
        vm.refrescar()

        assertEquals("el sondeo viejo no resucita el lote recién marcado", emptyList<String>(), vm.comandas.value.map { it.id })
    }

    @Test
    fun `Ronda 3 marcar todas si el lote falla las suelta del blindaje al regresar`() = runTest {
        coEvery { repo.bumpBatch(any()) } returns Result.failure(IOException("sin red"))
        // Sin folio: prueba el blindaje M4, no D10 (con folio, sin red, se marcan BUMP y NO regresan).
        val vm = armar(comandas = listOf(comanda("k1", 1_000, sourceKey = null), comanda("k2", 2_000, sourceKey = null)))

        vm.marcarTodasListas()
        assertEquals(listOf("k1", "k2"), vm.comandas.value.map { it.id }) // regresan de inmediato (optimismo deshecho)

        // El servidor confirma que siguen pendientes de verdad — sin soltarlas del blindaje, este sondeo REAL
        // las escondería otra vez por hasta 15 s.
        coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k1", 1_000), comanda("k2", 2_000)))
        vm.refrescar()

        assertEquals("el fallo las soltó del blindaje: un sondeo real SÍ las repinta", listOf("k1", "k2"), vm.comandas.value.map { it.id })
    }

    @Test
    fun `P1 Ronda 4 LISTO seguido de deshacer inmediato no queda tapado por el blindaje`() = runTest {
        coEvery { repo.bumpOrder("k1") } returns Result.success(Unit)
        coEvery { repo.recall("k1") } returns Result.success(Unit)
        // fetchOrders sigue mockeado (default de armar()) para regresar siempre [k1, k2].
        val vm = armar()

        vm.listo("k1")
        assertEquals(listOf("k2"), vm.comandas.value.map { it.id })

        vm.deshacer("k1")

        // deshacer() relee el tablero de inmediato: el servidor YA volvió a mandar k1 (recall exitoso), y sin
        // soltar el blindaje de M4 el filtro seguiría escondiéndola hasta 15 s.
        assertEquals("deshacer suelta el blindaje: la comanda se ve de inmediato", listOf("k1", "k2"), vm.comandas.value.map { it.id })
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

    // MARK: - Etapa 3 del KDS (3.5): el WiFi del local

    @Test
    fun `P1 el receptor se activa con la estacion del tablero y se apaga al cerrar la pantalla`() = runTest {
        val vm = armar()
        val job = launch { vm.mientrasSeVe() }
        runCurrent()
        verify(atLeast = 1) { receptor.activar("v1", "st-barra") }
        job.cancelAndJoin()
        // En ORDEN: `armar()` ya lo apagó una vez (sin pantalla a la vista); lo que se prueba es que se apague DESPUÉS
        // de haberse activado, o sea al cerrar la pantalla.
        verifyOrder {
            receptor.activar("v1", "st-barra")
            receptor.desactivar()
        }
    }

    @Test
    fun `P1 LISTO sin red con folio encola BUMP y persiste la marca, sin regresar la comanda ni pintar error`() = runTest {
        coEvery { repo.bumpOrder("k1") } returns Result.failure(IOException("sin red"))
        val marca = slot<JsonObject>()
        coEvery { cola.enqueue("v1", "KDS_TICKET_MARK", capture(marca), any(), false) } returns "m-1"
        val vm = armar()

        vm.listo("k1")
        advanceUntilIdle()

        assertEquals(listOf("k2"), vm.comandas.value.map { it.id })
        assertNull(vm.aviso.value)
        assertTrue(vm.sinConexion.value)
        coVerify(exactly = 1) { ticketsLocales.marcarLista(match { it.id == "k1" }, "v1", "st-barra", any()) }
        assertEquals("sale:k1:st-barra", marca.captured["sourceKey"]!!.jsonPrimitive.content)
        assertEquals("BUMP", marca.captured["action"]!!.jsonPrimitive.content)
        assertEquals("k1", marca.captured["label"]!!.jsonPrimitive.content)
        // Ronda 1: la marca durable PRIMERO; esconderla después. Al revés, un proceso muerto entre las dos la escondía
        // para siempre sin que el servidor se enterara.
        coVerifyOrder {
            cola.enqueue("v1", "KDS_TICKET_MARK", any(), any(), false)
            ticketsLocales.marcarLista(any(), "v1", "st-barra", any())
        }
    }

    @Test
    fun `P1 si la marca BUMP no se pudo encolar la comanda NO se esconde para siempre`() = runTest {
        coEvery { repo.bumpOrder("k1") } returns Result.failure(IOException("sin red"))
        coEvery { cola.enqueue(any(), any(), any(), any(), any()) } throws IllegalStateException("disco lleno")
        val vm = armar()

        vm.listo("k1")
        advanceUntilIdle()

        coVerify(exactly = 0) { ticketsLocales.marcarLista(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 una comanda que llego por WiFi se ve con id lan y su LISTO va por la cola aunque haya red`() = runTest {
        val vm = armar()
        val job = launch { vm.mientrasSeVe() }
        runCurrent()
        locales.value = listOf(local("round:rk:st-barra", 3_000))
        runCurrent()

        assertEquals(listOf("k1", "k2", "lan:round:rk:st-barra"), vm.comandas.value.map { it.id })
        vm.listo("lan:round:rk:st-barra")
        // NUNCA `advanceUntilIdle()` con `mientrasSeVe()` vivo: su `while (true) { delay(10_000) }` gira sin fin en el
        // reloj virtual hasta el OOM (memoria `runtest-con-timer-infinito-gira-hasta-oom`). `listo` de una `lan:` corre en
        // `viewModelScope` (Main = Unconfined): con `runCurrent()` ya terminó.
        runCurrent()

        coVerify(exactly = 0) { repo.bumpOrder(any()) }
        coVerify(exactly = 1) { cola.enqueue("v1", "KDS_TICKET_MARK", any(), any(), false) }
        coVerify(exactly = 1) { ticketsLocales.marcarLista(match { it.sourceKey == "round:rk:st-barra" }, "v1", "st-barra", any()) }
        job.cancelAndJoin()
    }

    @Test
    fun `P1 el servidor gana por folio y lo marcado sin red no resucita aunque el sondeo lo traiga`() = runTest {
        val vm = armar()
        val job = launch { vm.mientrasSeVe() }
        runCurrent()
        locales.value = listOf(local("sale:k1:st-barra", 900), local("sale:k2:st-barra", 1_900, lista = 1_950))
        runCurrent()
        assertEquals("k1 gana sobre su copia local; k2 está LISTO sin red", listOf("k1"), vm.comandas.value.map { it.id })

        coEvery { repo.fetchOrders(any()) } returns Result.failure(IOException("sin red"))
        vm.refrescar()
        assertEquals("sin red se conserva y se sigue mezclando", listOf("k1"), vm.comandas.value.map { it.id })
        job.cancelAndJoin()
    }

    @Test
    fun `marcar todas sin red encola una marca por comanda con folio y regresa las que no tienen`() = runTest {
        coEvery { repo.bumpBatch(any()) } returns Result.failure(IOException("sin red"))
        val vm = armar(comandas = listOf(comanda("k1", 1_000), comanda("u1", 2_000, sourceKey = null)))

        vm.marcarTodasListas()
        advanceUntilIdle()

        assertEquals(listOf("u1"), vm.comandas.value.map { it.id })
        coVerify(exactly = 1) { cola.enqueue("v1", "KDS_TICKET_MARK", any(), any(), false) }
        assertEquals(AvisoDeCocina(AccionDeCocina.MARCAR_TODAS.sinRed, esError = false), vm.aviso.value)
    }

    @Test
    fun `recibiendo por WiFi refleja el receptor`() = runTest {
        val vm = armar()
        assertFalse(vm.recibiendoPorWifi.value)
        receptorActivo.value = true
        assertTrue(vm.recibiendoPorWifi.value)
    }

    // MARK: - Ronda 1: una comanda que llegó por WiFi no resucita, y «Deshacer» la regresa al instante

    /** Llega por el WiFi ANTES que la del servidor, y luego el servidor también la tiene (la operación normal en línea). */
    private suspend fun kotlinx.coroutines.test.TestScope.conCopiaDelWifiQueElServidorYaTiene(): KDSViewModel {
        val vm = armar(comandas = listOf(comanda("k2", 2_000)))
        backgroundScope.launch { vm.mientrasSeVe() }
        runCurrent()
        locales.value = listOf(local("sale:k1:st-barra", 900))
        runCurrent()
        assertEquals(listOf("lan:sale:k1:st-barra", "k2"), vm.comandas.value.map { it.id })
        coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k1", 1_000), comanda("k2", 2_000)))
        vm.refrescar()
        runCurrent()
        assertEquals("por folio gana el servidor", listOf("k1", "k2"), vm.comandas.value.map { it.id })
        return vm
    }

    @Test
    fun `P1 una comanda del WiFi marcada LISTO en linea no resucita como local`() = runTest {
        coEvery { repo.bumpOrder("k1") } returns Result.success(Unit)
        val vm = conCopiaDelWifiQueElServidorYaTiene()

        vm.listo("k1")
        runCurrent()
        coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k2", 2_000))) // el servidor la terminó
        vm.refrescar()
        runCurrent()

        assertEquals(listOf("k2"), vm.comandas.value.map { it.id })
    }

    @Test
    fun `P1 una comanda del WiFi en marcar todas en linea no resucita como local`() = runTest {
        coEvery { repo.bumpBatch(any()) } returns Result.success(2)
        val vm = conCopiaDelWifiQueElServidorYaTiene()
        coEvery { repo.fetchOrders(any()) } returns Result.success(emptyList()) // la lectura de después del lote

        vm.marcarTodasListas()
        runCurrent()

        coVerify { repo.bumpBatch(listOf("k1", "k2")) }
        assertEquals(emptyList<String>(), vm.comandas.value.map { it.id })
    }

    @Test
    fun `P1 una comanda del WiFi que otra pantalla de la estacion marco LISTO no resucita aqui`() = runTest {
        val vm = conCopiaDelWifiQueElServidorYaTiene()

        coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k2", 2_000))) // otra pantalla la terminó
        vm.refrescar()
        runCurrent()

        assertEquals(listOf("k2"), vm.comandas.value.map { it.id })
    }

    @Test
    fun `P1 deshacer en linea quita la marca LISTO local y la comanda se ve al instante`() = runTest {
        coEvery { repo.fetchRecientes("st-barra") } returns Result.success(listOf(comanda("k9", 500)))
        coEvery { repo.recall("k9") } returns Result.success(Unit)
        val vm = armar()
        val job = launch { vm.mientrasSeVe() }
        runCurrent()
        locales.value = listOf(local("sale:k9:st-barra", 500, lista = 600)) // se marcó LISTO sin red
        runCurrent()
        vm.abrirRecientes()
        runCurrent()

        coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k1", 1_000), comanda("k2", 2_000), comanda("k9", 500)))
        vm.deshacer("k9")
        runCurrent()

        coVerify(exactly = 1) { ticketsLocales.quitarLista("sale:k9:st-barra") }
        assertEquals(listOf("k9", "k1", "k2"), vm.comandas.value.map { it.id })
        job.cancelAndJoin()
    }

    @Test
    fun `P2 la copia del servidor que reemplaza a la del WiFi no vuelve a sonar`() = runTest {
        mockkStatic(RingtoneManager::class)
        every { RingtoneManager.getDefaultUri(any()) } returns null
        every { RingtoneManager.getRingtone(any(), any()) } returns null
        try {
            val vm = armar()
            val job = launch { vm.mientrasSeVe() }
            runCurrent()
            locales.value = listOf(local("sale:k3:st-barra", 3_000)) // llega por el WiFi: suena
            runCurrent()
            assertEquals(listOf("k1", "k2", "lan:sale:k3:st-barra"), vm.comandas.value.map { it.id })

            // El servidor ya la tiene: cambia de id (`lan:` → k3) pero es la MISMA comanda — no suena otra vez.
            coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k1", 1_000), comanda("k2", 2_000), comanda("k3", 3_000)))
            vm.refrescar()

            assertEquals(listOf("k1", "k2", "k3"), vm.comandas.value.map { it.id })
            verify(exactly = 1) { RingtoneManager.getDefaultUri(any()) }
            job.cancelAndJoin()
        } finally {
            unmockkStatic(RingtoneManager::class)
        }
    }

    @Test
    fun `P2 abierta sin internet, lo que llega por el WiFi suena`() = runTest {
        mockkStatic(RingtoneManager::class)
        every { RingtoneManager.getDefaultUri(any()) } returns null
        every { RingtoneManager.getRingtone(any(), any()) } returns null
        try {
            val vm = armar()
            coEvery { repo.fetchOrders(any()) } returns Result.failure(IOException("sin red"))
            val job = launch { vm.mientrasSeVe() } // se abre SIN internet: la base es lo que ya se veía
            runCurrent()
            verify(exactly = 0) { RingtoneManager.getDefaultUri(any()) }

            locales.value = listOf(local("round:rk:st-barra", 3_000))
            runCurrent()

            assertEquals(listOf("k1", "k2", "lan:round:rk:st-barra"), vm.comandas.value.map { it.id })
            verify(exactly = 1) { RingtoneManager.getDefaultUri(any()) }
            job.cancelAndJoin()
        } finally {
            unmockkStatic(RingtoneManager::class)
        }
    }
}
