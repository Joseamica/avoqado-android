package com.avoqado.pos.kds.presentation

import android.content.Context
import android.content.SharedPreferences
import android.media.RingtoneManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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
import io.mockk.Runs
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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

    /**
     * @param kdsPrefs `prefs` (el doble) salvo en las pruebas de la foto del tablero, que usan un [prefsEnDisco] REAL.
     * @param lectura lo que contesta el servidor a la lectura del tablero.
     */
    private suspend fun armar(
        guardada: String? = "st-barra",
        comandas: List<KDSOrder> = listOf(comanda("k1", 1_000), comanda("k2", 2_000)),
        kdsPrefs: KdsPrefs = prefs,
        lectura: Result<List<KDSOrder>> = Result.success(comandas),
    ): KDSViewModel {
        every { repo.venueIdActual() } returns "v1"
        every { printConfig.getCurrentConfig() } answers { config }
        every { prefs.estacion("v1") } returns guardada
        every { prefs.sonido } returns true
        every { prefs.letraGrande } returns false
        every { prefs.foto(any(), any(), any()) } returns null
        every { roleManager.role } returns "MANAGER"
        every { roleManager.canManagePrinters } returns true
        every { planManager.hasFeature("KITCHEN_DISPLAY") } returns true
        coEvery { repo.fetchOrders(any()) } returns lectura
        coEvery { repo.fetchDeliveryChannels() } returns Result.success(emptyList())
        return KDSViewModel(
            repo, mockk(relaxed = true), mockk(relaxed = true), printConfig, Provider { cola },
            kdsPrefs, roleManager, planManager, mockk(relaxed = true),
            receptor, ticketsLocales,
        ).also { it.refrescar() }
    }

    /** Codex 3.6 (#2): un `KdsPrefs` REAL sobre un disco en memoria (patrón de `KdsPrefsTest`): sobrevive al ViewModel. */
    private fun prefsEnDisco(estacion: String = "st-barra"): KdsPrefs {
        val guardado = mutableMapOf<String, Any?>()
        val editor = mockk<SharedPreferences.Editor>()
        val sp = mockk<SharedPreferences>()
        every { sp.getString(any(), any()) } answers { guardado[firstArg<String>()] as String? ?: secondArg<String?>() }
        every { sp.getBoolean(any(), any()) } answers { guardado[firstArg<String>()] as Boolean? ?: secondArg<Boolean>() }
        every { sp.edit() } returns editor
        every { editor.putString(any(), any()) } answers { guardado[firstArg<String>()] = secondArg<String?>(); editor }
        every { editor.apply() } just Runs
        val context = mockk<Context> { every { getSharedPreferences("avoqado_kds", Context.MODE_PRIVATE) } returns sp }
        return KdsPrefs(context).also { it.guardarEstacion("v1", estacion) }
    }

    private val sinInternet = Result.failure<List<KDSOrder>>(IOException("sin internet"))

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
        // M1 (revisión T8): regresa a su lugar AHORA y se dice, en vez de desaparecer callada hasta la siguiente lectura.
        assertEquals(listOf("k1", "k2"), vm.comandas.value.map { it.id })
        assertEquals(AvisoDeCocina(AccionDeCocina.LISTO.generico, esError = true), vm.aviso.value)
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
        val marcas = mutableListOf<JsonObject>()
        coEvery { cola.enqueue("v1", "KDS_TICKET_MARK", capture(marcas), any(), false) } returns "m"
        val vm = armar(comandas = listOf(comanda("k1", 1_000), comanda("u1", 2_000, sourceKey = null), comanda("k3", 3_000)))

        vm.marcarTodasListas()
        advanceUntilIdle()

        assertEquals(listOf("u1"), vm.comandas.value.map { it.id })
        // M5 (revisión T8): UNA marca por comanda con folio — dos, no una por lote.
        coVerify(exactly = 2) { cola.enqueue("v1", "KDS_TICKET_MARK", any(), any(), false) }
        assertEquals(setOf("sale:k1:st-barra", "sale:k3:st-barra"), marcas.map { it["sourceKey"]!!.jsonPrimitive.content }.toSet())
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

    /**
     * I1 (revisión T8): el otro orden de llegada — el servidor la mandó PRIMERO (nada que retirar todavía), luego llegó
     * la copia del WiFi, y se terminó antes de la siguiente lectura. Un folio que el servidor devolvía y ya no devuelve
     * está terminado: su copia pendiente también sobra.
     */
    @Test
    fun `P1 I1 el servidor primero, luego la copia del WiFi, LISTO y una lectura sin ella - no resucita`() = runTest {
        coEvery { repo.bumpOrder("k1") } returns Result.success(Unit)
        val vm = armar() // el servidor ya manda k1 y k2; no hay copias locales
        val job = launch { vm.mientrasSeVe() }
        runCurrent()
        locales.value = listOf(local("sale:k1:st-barra", 900)) // la copia del WiFi llega DESPUÉS de esa lectura
        runCurrent()
        assertEquals("por folio gana el servidor", listOf("k1", "k2"), vm.comandas.value.map { it.id })

        vm.listo("k1")
        runCurrent()
        coEvery { repo.fetchOrders(any()) } returns Result.success(listOf(comanda("k2", 2_000)))
        vm.refrescar()
        runCurrent()

        assertEquals(listOf("k2"), vm.comandas.value.map { it.id })
        job.cancelAndJoin()
    }

    // MARK: - Codex 3.6 (#2): reabrir la pantalla sin internet no la deja sin las comandas pendientes

    /**
     * La cadena del hallazgo: la lectura buena retira la copia local de lo que el servidor devuelve (la caja ya borró su
     * entrega al acusar). Se cae el internet y la tablet se reinicia: el ViewModel (y el proceso) es nuevo y su lectura
     * falla. Sin la foto del aparato, k1 y k2 no estaban en ningún lado (ni papel ni pantalla) hasta que volviera el internet.
     */
    @Test
    fun `P1 reabrir sin internet conserva las comandas que el servidor ya habia devuelto`() = runTest {
        val disco = prefsEnDisco()
        val antes = armar(kdsPrefs = disco) // lectura buena: k1 y k2
        locales.value = listOf(local("sale:k1:st-barra", 900)) // la copia del WiFi de k1
        antes.refrescar()
        assertTrue("la lectura buena retira la copia local", locales.value.isEmpty())

        val despues = armar(kdsPrefs = disco, lectura = sinInternet) // tablet reiniciada, sin internet

        assertEquals(listOf("k1", "k2"), despues.comandas.value.map { it.id })
        assertTrue(despues.sinConexion.value)
    }

    @Test
    fun `P1 la foto de otra estacion o de mas de 12 h no se usa al abrir sin internet`() = runTest {
        config = PrintConfig(stations = listOf(barra, StationInfo(id = "st-cocina", name = "Cocina", hasKitchenDisplay = true)), version = "v1")
        val disco = prefsEnDisco()
        armar(kdsPrefs = disco) // foto de Barra: k1 y k2

        disco.guardarEstacion("v1", "st-cocina")
        val cocina = armar(kdsPrefs = disco, lectura = sinInternet)
        assertEquals("la foto de Barra no se pinta en Cocina", emptyList<String>(), cocina.comandas.value.map { it.id })

        disco.guardarEstacion("v1", "st-barra")
        disco.guardarFoto("v1", "st-barra", listOf(comanda("k1", 1_000)), tomadaEnMillis = System.currentTimeMillis() - 13 * 60 * 60 * 1_000L)
        val vieja = armar(kdsPrefs = disco, lectura = sinInternet)
        assertEquals("una foto de hace 13 h ya no es la cocina de hoy", emptyList<String>(), vieja.comandas.value.map { it.id })
    }

    /** Lo terminado EN LÍNEA no puede regresar de la foto al reabrir sin internet: la cocina lo volvería a preparar. */
    @Test
    fun `P1 lo marcado LISTO en linea no regresa de la foto al reabrir sin internet`() = runTest {
        coEvery { repo.bumpOrder("k1") } returns Result.success(Unit)
        val disco = prefsEnDisco()
        val antes = armar(kdsPrefs = disco)
        antes.listo("k1")
        runCurrent()

        val despues = armar(kdsPrefs = disco, lectura = sinInternet)

        assertEquals(listOf("k2"), despues.comandas.value.map { it.id })
    }

    private class PantallaDePrueba : LifecycleOwner {
        val registro = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registro
    }

    /**
     * I3 (revisión T8): con la app en segundo plano o la pantalla apagada NADIE ve el tablero — el receptor se apaga (sin
     * acuse ⇒ la caja imprime papel) y el sondeo para. Al volver, se reactiva con línea base nueva (M3).
     */
    @Test
    fun `P1 I3 en segundo plano el receptor se apaga y el sondeo para, y al volver se reactiva`() = runTest {
        val pantalla = PantallaDePrueba()
        pantalla.registro.currentState = Lifecycle.State.RESUMED
        val vm = armar() // 1ª lectura
        val job = launch { vm.mientrasEsteALaVista(pantalla) }
        runCurrent() // 2ª lectura: abrir
        verify(atLeast = 1) { receptor.activar("v1", "st-barra") }

        pantalla.registro.currentState = Lifecycle.State.CREATED // ON_STOP: botón de inicio, otra app o pantalla apagada
        runCurrent()
        verifyOrder {
            receptor.activar("v1", "st-barra")
            receptor.desactivar()
        }
        advanceTimeBy(60_000) // un minuto en segundo plano: ni una lectura más (con el sondeo vivo serían 6)
        coVerify(exactly = 2) { repo.fetchOrders(any()) }

        pantalla.registro.currentState = Lifecycle.State.STARTED // ON_START: vuelve a verse
        runCurrent()
        verifyOrder {
            receptor.activar("v1", "st-barra")
            receptor.desactivar()
            receptor.activar("v1", "st-barra")
        }
        job.cancelAndJoin()
    }

    /**
     * M2 (revisión T8): Room contesta DESPUÉS de la primera lectura del servidor. Lo que ya estaba guardado en el aparato
     * es parte de la línea base al reabrir, no «comanda nueva». El doble de siempre emite al instante y lo escondía.
     */
    @Test
    fun `P2 M2 reabrir no suena por lo que ya estaba guardado, y lo que llega despues si`() = runTest {
        mockkStatic(RingtoneManager::class)
        every { RingtoneManager.getDefaultUri(any()) } returns null
        every { RingtoneManager.getRingtone(any(), any()) } returns null
        try {
            val guardadas = MutableSharedFlow<List<KdsTicketLocal>>()
            every { ticketsLocales.deLaEstacion(any(), any()) } returns guardadas
            val vm = armar()
            val job = launch { vm.mientrasSeVe() }
            runCurrent() // la base del servidor ya está; Room todavía no contesta

            guardadas.emit(listOf(local("round:rk:st-barra", 3_000))) // la primera lectura de Room, tarde
            runCurrent()
            assertEquals(listOf("k1", "k2", "lan:round:rk:st-barra"), vm.comandas.value.map { it.id })
            verify(exactly = 0) { RingtoneManager.getDefaultUri(any()) }

            guardadas.emit(listOf(local("round:rk:st-barra", 3_000), local("round:nueva:st-barra", 4_000))) // ésta sí es nueva
            runCurrent()
            verify(exactly = 1) { RingtoneManager.getDefaultUri(any()) }
            job.cancelAndJoin()
        } finally {
            unmockkStatic(RingtoneManager::class)
        }
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

    // MARK: - Revisión final de la rama (3.5): M1, M3 y M4

    /**
     * M1: un tablero puede mostrar una comanda del servidor de OTRA estación. Su fila sombra (el LISTO sin red) se guardaba
     * con la estación de la comanda, pero el tablero sólo lee las filas de SU estación: la comanda regresaba hasta que el
     * servidor procesara el BUMP. La sombra va con la estación del TABLERO; el BUMP sigue llevando la de la comanda.
     */
    @Test
    fun `P1 el LISTO sin red de una comanda de OTRA estacion la esconde en este tablero`() = runTest {
        // Como Room: cada tablero lee sólo las filas de SU estación, y la sombra se guarda con la estación que se le pasa.
        every { ticketsLocales.deLaEstacion(any(), any()) } answers {
            val estacion = secondArg<String>()
            locales.map { filas -> filas.filter { it.stationId == estacion } }
        }
        coEvery { ticketsLocales.marcarLista(any(), any(), any(), any()) } answers {
            val orden = firstArg<KDSOrder>()
            locales.value = locales.value +
                KdsTicketLocal(orden.sourceKey!!, secondArg(), thirdArg(), orden.orderNumber, orden.orderType, orden.items, 5_000, 5_000)
        }
        coEvery { repo.bumpOrder("k1") } returns Result.failure(IOException("sin red"))
        val marca = slot<JsonObject>()
        coEvery { cola.enqueue("v1", "KDS_TICKET_MARK", capture(marca), any(), false) } returns "m-1"
        val deCocina = comanda("k1", 1_000, sourceKey = "sale:k1:st-cocina").copy(printStationId = "st-cocina")
        val vm = armar(comandas = listOf(deCocina, comanda("k2", 2_000)))
        val job = launch { vm.mientrasSeVe() }
        runCurrent()

        vm.listo("k1")
        runCurrent()
        coEvery { repo.fetchOrders(any()) } returns Result.failure(IOException("sin red"))
        vm.refrescar() // sin red se conserva lo del servidor y se vuelve a mezclar con lo local
        runCurrent()

        assertEquals("la sombra LISTA la esconde en ESTE tablero", listOf("k2"), vm.comandas.value.map { it.id })
        assertEquals("el BUMP sigue llevando la estación de la comanda", "st-cocina", marca.captured["stationId"]!!.jsonPrimitive.content)
        job.cancelAndJoin()
    }

    /**
     * M3: LISTO sobre una comanda del WiFi la quita al instante, pero hasta que el disco avisa, su fila sigue PENDIENTE en
     * memoria: un sondeo en ese hueco la hacía reaparecer un instante. Queda protegida como un bump en vuelo.
     */
    @Test
    fun `P2 M3 LISTO sobre una comanda del WiFi no parpadea si un sondeo cae antes de que el disco avise`() = runTest {
        val puerta = CompletableDeferred<Unit>()
        coEvery { cola.enqueue(any(), any(), any(), any(), any()) } coAnswers { puerta.await(); "m-1" }
        val vm = armar()
        val job = launch { vm.mientrasSeVe() }
        runCurrent()
        locales.value = listOf(local("round:rk:st-barra", 3_000))
        runCurrent()

        vm.listo("lan:round:rk:st-barra")
        runCurrent()
        coEvery { repo.fetchOrders(any()) } returns Result.failure(IOException("sin red"))
        vm.refrescar() // un sondeo cae mientras la marca todavía se guarda
        runCurrent()

        assertEquals(listOf("k1", "k2"), vm.comandas.value.map { it.id })
        puerta.complete(Unit)
        runCurrent()
        job.cancelAndJoin()
    }

    /** M3, la otra cara: si la marca no se pudo encolar, la comanda del WiFi regresa y la protección no la vuelve a esconder. */
    @Test
    fun `P1 M3 si la marca de una comanda del WiFi no se encola regresa y un sondeo no la vuelve a esconder`() = runTest {
        coEvery { cola.enqueue(any(), any(), any(), any(), any()) } throws IllegalStateException("disco lleno")
        val vm = armar()
        val job = launch { vm.mientrasSeVe() }
        runCurrent()
        locales.value = listOf(local("round:rk:st-barra", 3_000))
        runCurrent()

        vm.listo("lan:round:rk:st-barra")
        runCurrent()
        coEvery { repo.fetchOrders(any()) } returns Result.failure(IOException("sin red"))
        vm.refrescar()
        runCurrent()

        assertEquals(listOf("k1", "k2", "lan:round:rk:st-barra"), vm.comandas.value.map { it.id })
        job.cancelAndJoin()
    }

    /**
     * M4 (paridad con iOS, m2 de la T9): si la observación de lo guardado se cae, el receptor se APAGA (sin acuse, la caja
     * saca papel) en vez de tumbar la app; la siguiente sincronización la recrea y lo vuelve a prender.
     */
    @Test
    fun `P1 M4 si la observacion local falla se apaga el receptor sin tumbar la app y la siguiente lectura lo recupera`() = runTest {
        var encendido = false
        every { receptor.activar(any(), any()) } answers { encendido = true }
        every { receptor.desactivar() } answers { encendido = false }
        var rota = true
        every { ticketsLocales.deLaEstacion(any(), any()) } answers {
            if (rota) flow<List<KdsTicketLocal>> { throw IllegalStateException("base rota") } else locales
        }
        val vm = armar()
        val job = launch { vm.mientrasSeVe() }
        runCurrent()
        assertFalse("sin observación viva no se acusa: el receptor queda apagado", encendido)

        rota = false
        locales.value = listOf(local("round:rk:st-barra", 3_000))
        vm.refrescar() // la siguiente lectura la recrea
        runCurrent()

        assertTrue("la observación volvió: el receptor también", encendido)
        assertEquals(listOf("k1", "k2", "lan:round:rk:st-barra"), vm.comandas.value.map { it.id })
        job.cancelAndJoin()
    }
}
