package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.DialogoDePedido
import com.avoqado.pos.onlineorders.presentation.DialogosDeLaBandeja
import com.avoqado.pos.onlineorders.presentation.ListaDeBandeja
import com.avoqado.pos.onlineorders.presentation.SeccionesBandeja
import com.avoqado.pos.onlineorders.presentation.TipoDeDialogo
import com.avoqado.pos.onlineorders.presentation.rememberDialogoDePedido
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W77Q9: la bandeja abierta en «En preparación (4)» y llega un pendiente. Sin esto el pendiente quedaba ARRIBA de la ventana (el
 * anclaje por clave de LazyColumn dejaba como primera fila a `h-En preparación`). Se prueba la LISTA de verdad (`ListaDeBandeja`) y
 * los diálogos hoisted (`DialogosDeLaBandeja`), con el cableado de la pantalla: un estado de lista, un estado de diálogo.
 */
@OptIn(ExperimentalTestApi::class)
class BandejaDeUnPedidoNuevoEnLaEscenaTest {
    private val folder = Files.createTempDirectory("bandeja-pedido-nuevo")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    private fun pedido(id: String, estado: OnlineOrderStatus) = OnlineOrder(
        orderId = id, onlineOrderId = "o-$id", orderNumber = id, status = estado,
        paymentMode = "PAY_AT_PICKUP", paymentStatus = "PENDING", customerName = null, customerPhone = null, note = null,
        createdAt = Instant.parse("2026-10-22T10:00:00Z"), acceptDeadlineAt = null, estimatedReadyAt = null,
        totalCents = 20000, remainingCents = 20000, needsPrint = false, items = emptyList(),
    )

    private fun pendientes(vararg ids: String) = ids.map { pedido(it, OnlineOrderStatus.PENDING_ACCEPTANCE) }
    private fun enPreparacion(vararg ids: String) = ids.map { pedido(it, OnlineOrderStatus.ACCEPTED) }

    /** Lo que el cableado de `OnlineOrdersScreen` le pasa a la lista y a los diálogos, con las acciones anotadas. */
    private class Pantalla {
        var secciones by mutableStateOf(SeccionesBandeja())
        lateinit var estado: LazyListState
        lateinit var dialogo: DialogoDePedido
        lateinit var alcance: CoroutineScope
        val aceptados = mutableListOf<Pair<String, Int>>()
        val rechazados = mutableListOf<Pair<String, String>>()
        val cancelados = mutableListOf<String>()

        /** La clave de la primera fila VISIBLE (la que Compose cuenta como tal; el relleno de arriba no suma). */
        fun primeraClave(): Any? = estado.layoutInfo.visibleItemsInfo.first { it.index == estado.firstVisibleItemIndex }.key
    }

    @Composable private fun escena(contenido: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalContext provides context, LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f),
            LocalView provides View(context), LocalEsEscritorio provides true,
        ) { AvoqadoTheme(darkTheme = false, content = contenido) }
    }

    private fun ComposeUiTest.montar(inicial: SeccionesBandeja): Pantalla {
        val p = Pantalla().apply { secciones = inicial }
        setContent {
            escena {
                p.estado = rememberLazyListState()
                p.dialogo = rememberDialogoDePedido()
                p.alcance = rememberCoroutineScope()
                Box(Modifier.width(720.dp).height(700.dp)) {
                    ListaDeBandeja(
                        secciones = p.secciones, sinConexion = false, ocupados = emptySet(), hayMas = false, cargandoMas = false,
                        hoyRecortado = false, puedeActualizar = true, puedeCancelar = true, puedeCobrar = false,
                        onPedirDialogo = p.dialogo::abrir, onListo = {}, onEntregado = {}, onCobrar = {}, onReimprimir = {},
                        onCargarMas = {}, estadoLista = p.estado,
                    )
                    DialogosDeLaBandeja(
                        dialogo = p.dialogo, secciones = p.secciones,
                        onAceptar = { id, m -> p.aceptados += id to m },
                        onRechazar = { id, motivo -> p.rechazados += id to motivo },
                        onCancelar = { p.cancelados += it },
                    )
                }
            }
        }
        waitForIdle()
        return p
    }

    private fun ComposeUiTest.irA(p: Pantalla, indice: Int) {
        p.alcance.launch { p.estado.scrollToItem(indice) }
        waitForIdle()
    }

    @Test fun `pending arriving while at top keeps the list at top`() = runComposeUiTest {
        val p = montar(SeccionesBandeja(enPreparacion = enPreparacion("e1", "e2", "e3", "e4")))
        assertEquals("h-En preparación", p.primeraClave())
        p.secciones = SeccionesBandeja(porAceptar = pendientes("p1"), enPreparacion = enPreparacion("e1", "e2", "e3", "e4"))
        waitForIdle()
        assertEquals("h-Por aceptar", p.primeraClave())
        assertEquals(0, p.estado.firstVisibleItemIndex)
        assertEquals(0, p.estado.firstVisibleItemScrollOffset)
    }

    @Test fun `reader mid-list is not moved when a pending arrives`() = runComposeUiTest {
        val ids = (1..12).map { "e$it" }.toTypedArray()
        val p = montar(SeccionesBandeja(enPreparacion = enPreparacion(*ids)))
        irA(p, 5)
        val antes = p.primeraClave()
        val desplazamiento = p.estado.firstVisibleItemScrollOffset
        p.secciones = SeccionesBandeja(porAceptar = pendientes("p1"), enPreparacion = enPreparacion(*ids))
        waitForIdle()
        assertEquals(antes, p.primeraClave())
        assertEquals(desplazamiento, p.estado.firstVisibleItemScrollOffset)
    }

    @Test fun `reader below keeps reading when the pending disappears`() = runComposeUiTest {
        val ids = (1..8).map { "e$it" }.toTypedArray()
        val p = montar(SeccionesBandeja(porAceptar = pendientes("p1"), enPreparacion = enPreparacion(*ids)))
        irA(p, 2)   // 0 = h-Por aceptar · 1 = la tarjeta · 2 = h-En preparación
        assertEquals("h-En preparación", p.primeraClave())
        p.secciones = SeccionesBandeja(enPreparacion = enPreparacion(*ids))   // otro aparato atendió el pendiente
        waitForIdle()
        assertEquals("h-En preparación", p.primeraClave())
    }

    @Test fun `open dialog survives its card leaving composition`() = runComposeUiTest {
        val ids = (1..30).map { "p$it" }.toTypedArray()
        val p = montar(SeccionesBandeja(porAceptar = pendientes(*ids)))
        onNodeWithTag("aceptar-p2").performClick()
        waitForIdle()
        assertEquals("p2", p.dialogo.orderId)
        assertEquals(TipoDeDialogo.ACEPTAR, p.dialogo.tipo)
        onNodeWithTag("minutos-15").assertExists()
        // Llegan 99 en preparación y la lectura se va lejos: la tarjeta p2 sale de la composición.
        p.secciones = SeccionesBandeja(porAceptar = pendientes(*ids), enPreparacion = enPreparacion(*(1..99).map { "e$it" }.toTypedArray()))
        waitForIdle()
        irA(p, 100)
        // La fila ya no está en la ventana de la lista (Compose puede conservar su nodo desactivado en la reserva: eso ya no la compone).
        assertTrue(p.estado.layoutInfo.visibleItemsInfo.none { it.key == "Por aceptar-p2" }, "la tarjeta p2 sigue a la vista")
        onNodeWithTag("minutos-15").assertExists()
        onNodeWithTag("minutos-15").performClick()
        waitForIdle()
        assertEquals(listOf("p2" to 15), p.aceptados)
        assertNull(p.dialogo.orderId)
        onNodeWithTag("minutos-15").assertDoesNotExist()
    }

    @Test fun `the dialog closes when its order is gone`() = runComposeUiTest {
        val p = montar(SeccionesBandeja(porAceptar = pendientes("p1", "p2")))
        onNodeWithTag("aceptar-p1").performClick()
        waitForIdle()
        assertEquals("p1", p.dialogo.orderId)
        onNodeWithTag("minutos-10").assertExists()
        // Otro aparato lo aceptó: ya no está en «Por aceptar».
        p.secciones = SeccionesBandeja(porAceptar = pendientes("p2"), enPreparacion = enPreparacion("p1"))
        waitForIdle()
        onNodeWithTag("minutos-10").assertDoesNotExist()
        assertNull(p.dialogo.orderId)
        assertEquals(emptyList(), p.aceptados)
    }

    @Test fun `rechazar y cancelar usan los mismos testTags y las mismas acciones`() = runComposeUiTest {
        val p = montar(SeccionesBandeja(porAceptar = pendientes("p1"), enPreparacion = enPreparacion("e1")))
        onNodeWithTag("rechazar-p1").performClick()
        waitForIdle()
        assertEquals(TipoDeDialogo.RECHAZAR, p.dialogo.tipo)
        onNodeWithTag("motivo-TOO_BUSY").performClick()
        waitForIdle()
        assertEquals(listOf("p1" to "TOO_BUSY"), p.rechazados)
        assertNull(p.dialogo.orderId)

        onNodeWithTag("cancelar-e1").performClick()
        waitForIdle()
        assertEquals(TipoDeDialogo.CANCELAR, p.dialogo.tipo)
        onNodeWithTag("cancelar-confirma").performClick()
        waitForIdle()
        assertEquals(listOf("e1"), p.cancelados)
        assertNull(p.dialogo.orderId)
    }
}
