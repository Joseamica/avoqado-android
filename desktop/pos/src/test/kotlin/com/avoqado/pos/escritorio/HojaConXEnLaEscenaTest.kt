package com.avoqado.pos.escritorio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.material3.Button
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.avoqado.pos.areatickets.domain.PapelDelAparato
import com.avoqado.pos.areatickets.presentation.HojaDelPapel
import com.avoqado.pos.areatickets.presentation.PapelDelAparatoSheet
import com.avoqado.pos.areatickets.presentation.tituloDeLaHoja
import com.avoqado.pos.cashdrawer.presentation.PayInOutSheet
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import com.avoqado.pos.escritorio.Inyector
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * En Windows cada hoja modal trae una X que la cierra: con mouse no hay gesto de bajarla ni botón de atrás, y
 * arrastrarla hasta abajo dejaba la app bloqueada detrás (La Galeterie, 8-oct).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class HojaConXEnLaEscenaTest {
    /** La rama de Android llama `ImmersiveWindow`, que pide el contexto de escritorio, como en Main. Sin esto dependía del orden de las clases. */
    @BeforeTest fun contextoDeEscritorio() {
        if (runCatching { Escritorio.contexto }.isSuccess) return
        val carpeta = Files.createTempDirectory("Avoqado POS hoja")
        Bitacora.iniciar(carpeta)
        val actividad = ActividadDeEscritorio(carpeta)
        Escritorio.instalar(actividad, Inyector.crear(actividad))
    }

    @Test fun `P1 en escritorio la X cierra la hoja y avisa una vez`() = runComposeUiTest {
        var cierres = 0
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides true) {
                MaterialTheme {
                    var abierta by remember { mutableStateOf(true) }
                    if (abierta) {
                        AvoqadoModalBottomSheet(onDismissRequest = { cierres++; abierta = false }) { Text("Configurar impresora") }
                    }
                }
            }
        }
        onNodeWithText("Configurar impresora").assertExists()
        onNodeWithContentDescription("Cerrar").performClick()
        waitForIdle()
        assertEquals(1, cierres)
        onNodeWithText("Configurar impresora").assertDoesNotExist()
    }

    @Test fun `fuera de escritorio la hoja es la de Material sin X`() = runComposeUiTest {
        setContent { MaterialTheme { AvoqadoModalBottomSheet(onDismissRequest = {}) { Text("Configurar impresora") } } }
        onNodeWithText("Configurar impresora").assertExists()
        onNodeWithContentDescription("Cerrar").assertDoesNotExist()
    }

    // MARK: - A pantalla completa («Emitir reembolso»). Cada prueba corre en las dos ramas de la hoja: Windows y Android.

    private var cierres = 0

    /** Escena más ancha que los 640 dp de una hoja común, como la D3 o una PC. */
    private fun hoja(enWindows: Boolean, aPantallaCompleta: Boolean, contenido: @Composable ColumnScope.() -> Unit, prueba: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(1280, 800) {
            setContent {
                CompositionLocalProvider(LocalEsEscritorio provides enWindows) {
                    MaterialTheme {
                        var abierta by remember { mutableStateOf(true) }
                        if (abierta) {
                            AvoqadoModalBottomSheet(
                                onDismissRequest = { cierres++; abierta = false },
                                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                                dragHandle = null,
                                conXPropia = true,
                                aPantallaCompleta = aPantallaCompleta,
                                content = contenido,
                            )
                        }
                    }
                }
            }
            waitForIdle()
            prueba()
        }

    private fun ComposeUiTest.bordes(tag: String) = onNodeWithTag(tag).getBoundsInRoot()

    /** Con poco contenido: si la hoja no ocupara todo el alto, lo corto quedaría pegado abajo y no arriba. */
    private fun ocupaTodo(enWindows: Boolean) = hoja(enWindows, aPantallaCompleta = true, contenido = {
        Box(Modifier.fillMaxWidth().height(40.dp).testTag("corto"))
    }) {
        val corto = bordes("corto")
        assertEquals(0.dp, corto.left)
        assertEquals(1280.dp, corto.right)
        assertEquals(0.dp, corto.top)
    }
    @Test fun `a pantalla completa el contenido corto va arriba y a todo el ancho en Windows`() = ocupaTodo(enWindows = true)
    @Test fun `a pantalla completa el contenido corto va arriba y a todo el ancho en Android`() = ocupaTodo(enWindows = false)

    private fun sinArrastre(enWindows: Boolean) = hoja(enWindows, aPantallaCompleta = true, contenido = {
        Box(Modifier.fillMaxSize().testTag("cuerpo"))
    }) {
        onNodeWithTag("cuerpo").performTouchInput { swipeDown() }
        waitForIdle()
        assertEquals(0, cierres, "arrastrar hacia abajo cerró la pantalla")
        assertEquals(0.dp, bordes("cuerpo").top, "la pantalla se movió con el arrastre")
    }
    @Test fun `a pantalla completa arrastrar hacia abajo no cierra en Windows`() = sinArrastre(enWindows = true)
    @Test fun `a pantalla completa arrastrar hacia abajo no cierra en Android`() = sinArrastre(enWindows = false)

    /** Seguir bajando una lista que ya llegó arriba no debe tirar la pantalla con lo capturado. */
    private fun listaArriba(enWindows: Boolean) = hoja(enWindows, aPantallaCompleta = true, contenido = {
        Column(Modifier.fillMaxSize().testTag("lista").verticalScroll(rememberScrollState())) {
            repeat(80) { Text("Renglón $it") }
        }
    }) {
        onNodeWithTag("lista").performTouchInput { swipeDown() }
        waitForIdle()
        assertEquals(0, cierres, "seguir bajando la lista cerró la pantalla")
        assertEquals(0.dp, bordes("lista").top, "la pantalla se movió con la lista")
    }
    @Test fun `a pantalla completa una lista que ya llego arriba no tira la pantalla en Windows`() = listaArriba(enWindows = true)
    @Test fun `a pantalla completa una lista que ya llego arriba no tira la pantalla en Android`() = listaArriba(enWindows = false)

    /** Quitarle el arrastre a la hoja no debe quitárselo a lo de adentro. */
    private fun listaAvanza(enWindows: Boolean) = hoja(enWindows, aPantallaCompleta = true, contenido = {
        Column(Modifier.fillMaxSize().testTag("lista").verticalScroll(rememberScrollState())) {
            repeat(80) { Text("Renglón $it") }
        }
    }) {
        // Sin recortar: `getBoundsInRoot` lo recorta contra la lista, y fuera de la vista seguiría diciendo 0.
        assertEquals(0.dp, onNodeWithText("Renglón 0").getUnclippedBoundsInRoot().top, "montaje: la lista empieza arriba")
        onNodeWithTag("lista").performTouchInput { swipeUp() }
        waitForIdle()
        assertTrue(onNodeWithText("Renglón 0").getUnclippedBoundsInRoot().top < 0.dp, "la lista no avanzó con el dedo")
        assertEquals(0.dp, bordes("lista").top, "la pantalla se movió con la lista")
        assertEquals(0, cierres)
    }
    @Test fun `a pantalla completa una lista larga avanza con el dedo en Windows`() = listaAvanza(enWindows = true)
    @Test fun `a pantalla completa una lista larga avanza con el dedo en Android`() = listaAvanza(enWindows = false)

    private fun tocar(enWindows: Boolean) {
        var toques = 0
        hoja(enWindows, aPantallaCompleta = true, contenido = {
            Box(Modifier.fillMaxWidth().height(100.dp).testTag("boton").clickable { toques++ })
        }) {
            onNodeWithTag("boton").performTouchInput { click() }
            waitForIdle()
            assertEquals(1, toques, "el botón no recibió el toque")
            assertEquals(0, cierres)
        }
    }
    @Test fun `a pantalla completa un boton recibe el toque en Windows`() = tocar(enWindows = true)
    @Test fun `a pantalla completa un boton recibe el toque en Android`() = tocar(enWindows = false)

    @Test fun `a pantalla completa con su encabezado no sale una segunda X`() = hoja(enWindows = true, aPantallaCompleta = true, contenido = {
        AvoqadoFullscreenHeader(title = "Emitir reembolso", onNav = {})
    }) {
        onAllNodesWithContentDescription("Cerrar").assertCountEquals(1)
    }

    // Sin pedirla, la hoja sigue igual: con su tope de ancho, pegada abajo y se cierra arrastrando.
    private fun hojaComun(enWindows: Boolean) = hoja(enWindows, aPantallaCompleta = false, contenido = {
        Box(Modifier.fillMaxWidth().height(300.dp).testTag("comun"))
    }) {
        val comun = bordes("comun")
        assertEquals(640.dp, comun.right - comun.left)
        assertEquals(800.dp, comun.bottom)
        onNodeWithTag("comun").performTouchInput { swipeDown() }
        waitForIdle()
        assertEquals(1, cierres, "la hoja común dejó de cerrarse al arrastrarla")
    }
    @Test fun `sin pantalla completa la hoja sigue igual en Windows`() = hojaComun(enWindows = true)
    @Test fun `sin pantalla completa la hoja sigue igual en Android`() = hojaComun(enWindows = false)

    // MARK: - hz:a5fa2439:D5: Esc (o «atrás») llega a la ventana de la hoja desde un botón suyo con el foco, no a los atajos.

    private fun ComposeUiTest.escDesde(nodo: SemanticsNodeInteraction) {
        nodo.performSemanticsAction(SemanticsActions.RequestFocus)
        nodo.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
    }

    /** Una hoja que sí se deja cerrar (el default) se sigue cerrando con Esc y avisa una vez. */
    private fun escCierraLaHojaComun(enWindows: Boolean) = hoja(enWindows, aPantallaCompleta = false, contenido = {
        Button(onClick = {}) { Text("Guardar impresora") }
    }) {
        escDesde(onNodeWithText("Guardar impresora"))
        assertEquals(1, cierres, "Esc dejó de cerrar una hoja común")
        onNodeWithText("Guardar impresora").assertDoesNotExist()
    }
    @Test fun `D5 Esc sigue cerrando una hoja comun en Windows`() = escCierraLaHojaComun(enWindows = true)
    @Test fun `D5 atras sigue cerrando una hoja comun en Android`() = escCierraLaHojaComun(enWindows = false)

    /**
     * Las hojas con guardia («guardando») llevan el interruptor apagado FIJO: un valor que cambia con la hoja abierta puede no
     * llegarle a la librería (Codex r1 #1). Esc/atrás no las cierra nunca, guarden o no; se cierran con su X o sus botones.
     */
    private fun ComposeUiTest.hojaConGuardia(enWindows: Boolean, hoja: @Composable (cerrar: () -> Unit) -> Unit): () -> Int {
        var cierresDeLaHoja = 0
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides enWindows) {
                MaterialTheme {
                    var abierta by remember { mutableStateOf(true) }
                    if (abierta) hoja { cierresDeLaHoja++; abierta = false }
                }
            }
        }
        waitForIdle()
        return { cierresDeLaHoja }
    }

    /** Entrada/salida de efectivo (el retiro, que es el que guarda con PIN). */
    private fun escEnElRetiro(enWindows: Boolean, ocupado: Boolean) = runDesktopComposeUiTest(1280, 800) {
        val cierresDelRetiro = hojaConGuardia(enWindows) { cerrar ->
            PayInOutSheet(isPayIn = false, onConfirm = { _, _ -> }, onDismiss = cerrar, ocupado = ocupado)
        }
        // Mientras registra, los campos van en sólo lectura y pierden la acción de escribir: se busca a quién se le puede dar el foco.
        escDesde(onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))[0])
        assertEquals(0, cierresDelRetiro(), "Esc cerró el retiro (registrando: $ocupado)")
        onNodeWithText("Registrar egreso").assertIsDisplayed()
    }
    @Test fun `P1 D5 Esc no cierra el retiro mientras se registra en Windows`() = escEnElRetiro(enWindows = true, ocupado = true)
    @Test fun `P1 D5 atras no cierra el retiro mientras se registra en Android`() = escEnElRetiro(enWindows = false, ocupado = true)
    @Test fun `D5 Esc tampoco cierra el retiro sin registrar en Windows`() = escEnElRetiro(enWindows = true, ocupado = false)
    @Test fun `D5 atras tampoco cierra el retiro sin registrar en Android`() = escEnElRetiro(enWindows = false, ocupado = false)

    private fun papel(guardando: Boolean) = HojaDelPapel(
        primeraVez = false, venueId = "c123456789012345678901234", terminalId = "c223456789012345678901234",
        areas = emptyList(), areasDeMas = 0, seleccion = PapelDelAparato.Cobra, puedeConfigurar = true, ventaAMedias = false,
        guardando = guardando,
    )

    /** Papel del aparato: igual que el retiro. */
    private fun escEnElPapel(enWindows: Boolean, guardando: Boolean) = runDesktopComposeUiTest(1280, 800) {
        val cierresDelPapel = hojaConGuardia(enWindows) { cerrar ->
            PapelDelAparatoSheet(estado = papel(guardando), hayRed = true, onElegir = {}, onGuardar = {}, onAhoraNo = null,
                onCerrar = cerrar)
        }
        escDesde(onNodeWithText("Cobra (caja)"))
        assertEquals(0, cierresDelPapel(), "Esc cerró el papel del aparato (guardando: $guardando)")
        onNodeWithText(tituloDeLaHoja(enWindows)).assertIsDisplayed()
    }
    @Test fun `P1 D5 Esc no cierra el papel del aparato mientras guarda en Windows`() = escEnElPapel(enWindows = true, guardando = true)
    @Test fun `P1 D5 atras no cierra el papel del aparato mientras guarda en Android`() = escEnElPapel(enWindows = false, guardando = true)
    @Test fun `D5 Esc tampoco cierra el papel del aparato sin guardar en Windows`() = escEnElPapel(enWindows = true, guardando = false)
    @Test fun `D5 atras tampoco cierra el papel del aparato sin guardar en Android`() = escEnElPapel(enWindows = false, guardando = false)

    /**
     * Codex r1 #2: la X de Windows baja la hoja ANTES de avisar, y mientras guarda el cierre se rechaza ⇒ hoja invisible
     * encima. Mientras guarda no hay X (como el retiro); sin guardar, sí.
     */
    private fun xDelPapel(guardando: Boolean, equis: Int) = runDesktopComposeUiTest(1280, 800) {
        hojaConGuardia(enWindows = true) { cerrar ->
            PapelDelAparatoSheet(estado = papel(guardando), hayRed = true, onElegir = {}, onGuardar = {}, onAhoraNo = null,
                onCerrar = cerrar)
        }
        onAllNodesWithContentDescription("Cerrar").assertCountEquals(equis)
    }
    @Test fun `P1 D5 en Windows el papel del aparato no lleva X mientras guarda`() = xDelPapel(guardando = true, equis = 0)
    @Test fun `en Windows el papel del aparato lleva su X sin guardar`() = xDelPapel(guardando = false, equis = 1)
}
