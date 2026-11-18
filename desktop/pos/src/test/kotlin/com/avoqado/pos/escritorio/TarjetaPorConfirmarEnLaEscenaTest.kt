package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.Escritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.designsystem.components.AtajosDeTeclado
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.presentation.CancelacionPendienteView
import com.avoqado.pos.payment.presentation.TarjetaPorConfirmarView
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 🔴 F1 mínimo, tarea 6 (founder, 17 y 18-nov: «el sistema solo»; «que nunca trabe una terminal»). La tarjeta en duda en el POS de
 * ANDROID y de WINDOWS: el cajero ve el importe del intento y [Seguir]; ninguna decisión. Dos toques, Esc o Atrás salen UNA vez y nunca
 * cancelan, declaran ni reenvían: la solicitud sigue guardada y la revisa el gerente con su PIN en «Operaciones por revisar».
 */
@OptIn(ExperimentalTestApi::class)
class TarjetaPorConfirmarEnLaEscenaTest {
    private val folder = Files.createTempDirectory("tarjeta-por-confirmar")
    private val context = ContextoDeEscritorio(folder)
    @AfterTest fun cleanup() { folder.toFile().deleteRecursively() }

    @BeforeTest fun contextoDeEscritorio() {
        if (runCatching { Escritorio.contexto }.isSuccess) return
        val carpeta = Files.createTempDirectory("Avoqado POS tarjeta por confirmar")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        Escritorio.instalar(actividad, Inyector.crear(actividad))
    }

    private fun ComposeUiTest.escena(contenido: @Composable () -> Unit) = setContent {
        CompositionLocalProvider(
            LocalContext provides context, LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f),
            LocalView provides View(context), LocalEsEscritorio provides true,
        ) { AvoqadoTheme(darkTheme = false) { contenido() } }
    }

    /** Lo que el piso ya no puede tocar (F1): ninguna decisión sobre el dinero. */
    private val decisionesDePiso = listOf(
        CancelacionDeCobro.BOTON_DECLARAR_NO_COBRADO, CancelacionDeCobro.NO_SE_COBRO_COBRAR_OTRA_VEZ,
        CancelacionDeCobro.BOTON_CANCELAR_VENTA, CancelacionDeCobro.DECLARACION_CONFIRMAR, "Cobrar de nuevo",
    )

    private fun ComposeUiTest.sinDecisionesDePiso() = decisionesDePiso.forEach {
        assertEquals(0, onAllNodesWithText(it).fetchSemanticsNodes().size, "el piso no decide: «$it» no debe verse")
    }

    @Test fun `el cajero ve el importe del intento y solo Seguir, aunque se este consultando`() = runComposeUiTest {
        var seguir = 0
        escena { TarjetaPorConfirmarView(montoDelCobro = "\$350.00", confirmando = true, sinRed = false, onSeguir = { seguir++ }) }

        onNodeWithText("Tarjeta \$350.00 por confirmar. No la repitas. Avisa al gerente.").assertExists()
        onNodeWithText(CancelacionDeCobro.CONFIRMANDO_CON_EL_BANCO).assertExists()
        sinDecisionesDePiso()
        assertEquals(1, onAllNodes(hasClickAction()).fetchSemanticsNodes().size, "una sola acción: Seguir")

        // Dos toques en el mismo cuadro (un dedo rápido): sale UNA vez.
        val tocar = onNodeWithText(CancelacionDeCobro.BOTON_SEGUIR).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnUiThread { tocar(); tocar() }
        waitForIdle()
        assertEquals(1, seguir)
    }

    @Test fun `sin importe guardado dice Importe por confirmar y nunca otro numero`() = runComposeUiTest {
        escena { TarjetaPorConfirmarView(montoDelCobro = null, confirmando = false, sinRed = false, onSeguir = {}) }

        onNodeWithText(CancelacionDeCobro.IMPORTE_POR_CONFIRMAR).assertExists()
        onNodeWithText("Tarjeta por confirmar. No la repitas. Avisa al gerente.").assertExists()
        assertEquals(0, onAllNodesWithText("\$", substring = true).fetchSemanticsNodes().size, "sin importe no se inventa uno")
    }

    @Test fun `con teclado en Windows Esc es Seguir, una vez, y Enter no decide nada`() = runComposeUiTest {
        var seguir = 0
        escena { TarjetaPorConfirmarView(montoDelCobro = "\$350.00", confirmando = false, sinRed = true, onSeguir = { seguir++ }) }

        runOnIdle {
            AtajosDeTeclado.atender(Key.Enter)
            AtajosDeTeclado.atender(Key.Escape)
            AtajosDeTeclado.atender(Key.Escape)
        }
        waitForIdle()
        assertEquals(1, seguir, "Esc sale una sola vez y Enter no hace nada")
        sinDecisionesDePiso()
    }

    @Test fun `cancelacion pendiente - el cajero no declara, Seguir sale aunque se este consultando`() = runComposeUiTest {
        var seguir = 0
        var consultas = 0
        escena {
            CancelacionPendienteView(
                sinRed = false, isChecking = true, terminalLibre = true,
                onVolverAConsultar = { consultas++ }, onSalir = { seguir++ },
            )
        }

        onNodeWithText(CancelacionDeCobro.CUERPO_TERMINAL_LIBRE_PISO).assertExists()
        sinDecisionesDePiso()
        val tocar = onNodeWithText(CancelacionDeCobro.BOTON_SEGUIR).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnUiThread { tocar(); tocar() }
        runOnIdle { AtajosDeTeclado.atender(Key.Escape) }
        waitForIdle()
        assertEquals(1, seguir)
        assertEquals(0, consultas)
    }

    /** F1 (un paso, Codex código r1 #10): el teclado del gerente ya dice qué firma, su botón es [Firmar] y un 403 se dice ahí mismo. */
    @Test fun `el teclado del gerente dice que firma, Firmar manda una vez y un PIN sin permiso se dice en el mismo teclado`() = runComposeUiTest {
        val enviados = mutableListOf<String>()
        val respuesta = kotlinx.coroutines.CompletableDeferred<com.avoqado.pos.core.data.network.OverrideResult>()
        val cobro = com.avoqado.pos.payment.presentation.CobroPorConfirmar(
            requestId = "req-abcdef123", venueId = "venue-1", montoCents = 35_000, desdeMillis = 1_763_490_600_000,
        )
        escena {
            com.avoqado.pos.sync.presentation.RevisionDelGerente(
                paso = com.avoqado.pos.payment.presentation.PasoDelGerente.Firmar(cobro, id = 1),
                onFirmar = { pin -> enviados += pin; respuesta.await() },
                onCerrar = {},
            )
        }

        onNodeWithText("Gerente: escribe tu PIN para marcar \$350.00 de las", substring = true).assertExists()
        onNodeWithText("como no cobrado", substring = true).assertExists()
        assertEquals(0, onAllNodesWithText("Autorizar").fetchSemanticsNodes().size, "el botón es Firmar")
        listOf("4", "3", "2", "1").forEach { onNodeWithText(it).performClick() }
        val firmar = onNodeWithText(CancelacionDeCobro.BOTON_FIRMAR).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnUiThread { firmar(); firmar() }
        waitForIdle()
        assertEquals(listOf("4321"), enviados, "dos toques, una sola firma")

        respuesta.complete(com.avoqado.pos.core.data.network.OverrideResult.Failed(CancelacionDeCobro.PIN_NO_ES_DE_GERENTE))
        waitForIdle()
        onNodeWithText(CancelacionDeCobro.PIN_NO_ES_DE_GERENTE).assertExists()
    }
}
