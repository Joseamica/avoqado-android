package com.avoqado.pos.escritorio.dibujo

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.ConfiguracionDeAndroid
import com.avoqado.escritorio.Escritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.escritorio.AppEscritorio
import com.avoqado.pos.escritorio.Diagnostico
import com.avoqado.pos.escritorio.Arranque
import com.avoqado.pos.escritorio.RaizDeEscritorio
import com.avoqado.pos.escritorio.diagnostico.DatosDelEquipo
import com.avoqado.pos.escritorio.diagnostico.veredicto
import com.avoqado.pos.settings.MoreMenuScreen
import com.google.inject.Injector
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/**
 * Las bandas del modo de dibujo y sus renglones de «Más» (hz:a5fa2439:D2), con el dueño real ([DibujoDeLaCaja]), una carpeta
 * temporal y un reloj falso. Una banda a la vez, que nunca toma el foco.
 */
@OptIn(ExperimentalTestApi::class)
class AvisoDeDibujoTest {
    private val carpeta = Files.createTempDirectory("aviso-de-dibujo")
    private var relojNs = 0L
    private val fijados = mutableListOf<String>()

    @BeforeTest fun armar() = DibujoDeLaCaja.reiniciarParaPruebas()

    @AfterTest fun limpiar() {
        DibujoDeLaCaja.reiniciarParaPruebas()
        carpeta.toFile().setWritable(true)
        carpeta.toFile().deleteRecursively()
    }

    private fun arrancar(archivo: Ajuste? = null, entorno: Map<String, String> = emptyMap(), propiedades: Map<String, String> = emptyMap()) {
        DibujoDeLaCaja.reiniciarParaPruebas()
        relojNs = 0
        fijados.clear()
        if (archivo != null) assertTrue(ArchivoDeDibujo.guardar(carpeta, archivo))
        DibujoDeLaCaja.alArrancar(carpeta, entorno::get, propiedades::get, fijar = { fijados += it }, ahoraNs = { relojNs })
    }

    private fun pintar(motor: String, ms: Long) {
        relojNs += ms * 1_000_000
        DibujoDeLaCaja.alPintar(motor)
    }

    private fun soloLectura(dir: Path = carpeta) {
        assumeTrue("sólo con permisos POSIX", Files.getFileStore(dir).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
        assumeFalse("corre como root: la carpeta sigue escribible", Files.isWritable(dir))
    }

    private val confirmacion = "Listo. Cierra y vuelve a abrir el POS para aplicarlo."
    private val fallo = "El modo rápido no funcionó en esta computadora. La app regresó al modo automático."
    private val noAlcanza = "Esta computadora tarda en dibujar la pantalla, aun en modo rápido. Puedes seguir trabajando, pero la app " +
        "va a responder lento."
    private val ofrecer = "Esta computadora tarda en dibujar la pantalla. El modo rápido puede ayudar."
    private val noSeGuardo = "No se pudo guardar el cambio. Inténtalo de nuevo."
    private val explicacion = "El modo rápido dibuja sin la tarjeta de video y con menos animaciones. Sirve en computadoras con " +
        "tarjeta de video antigua; en una moderna puede ir más lento. Se aplica al volver a abrir el POS."

    /** De las cuatro bandas sale sólo [texto] (null = ninguna). */
    private fun ComposeUiTest.soloSale(texto: String?) {
        for (t in listOf(confirmacion, fallo, noAlcanza, ofrecer)) {
            if (t == texto) onNodeWithText(t).assertExists() else onNodeWithText(t).assertDoesNotExist()
        }
    }

    private fun ComposeUiTest.ponerBanda() = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f)) { AvisoDeDibujo() }
        }
    }

    // MARK: - Cada banda, sólo en su caso

    @Test fun `OpenGL lento en automático ofrece el modo rápido con sus dos botones`() = runComposeUiTest {
        arrancar()
        ponerBanda()
        soloSale(null)
        runOnIdle { pintar("OPENGL", 9_000) }
        soloSale(ofrecer)
        onNodeWithText("Activar modo rápido").assertExists()
        onNodeWithText("Ahora no").assertExists()
    }

    @Test fun `Activar modo rápido deja el archivo en RAPIDO y la banda pasa a la confirmación`() = runComposeUiTest {
        arrancar()
        pintar("OPENGL", 9_000)
        ponerBanda()
        onNodeWithText("Activar modo rápido").performClick()
        assertEquals(Modo.RAPIDO, ArchivoDeDibujo.leer(carpeta).modo)
        soloSale(confirmacion)
        onNodeWithText("Entendido").performClick()
        soloSale(null)
        assertEquals(Modo.RAPIDO, DibujoDeLaCaja.estado.value.modo)
    }

    @Test fun `Ahora no la quita y no vuelve en el siguiente arranque`() = runComposeUiTest {
        arrancar()
        pintar("OPENGL", 9_000)
        ponerBanda()
        onNodeWithText("Ahora no").performClick()
        soloSale(null)
        assertTrue(ArchivoDeDibujo.leer(carpeta).sinAviso)

        runOnIdle { arrancar(); pintar("OPENGL", 9_000) }   // la siguiente apertura, igual de lenta
        assertNull(DibujoDeLaCaja.estado.value.aviso)
        soloSale(null)
    }

    @Test fun `si el modo rápido falló lo dice y Entendido lo calla`() = runComposeUiTest {
        arrancar(Ajuste(modo = Modo.RAPIDO, intentando = true))   // la apertura rápida anterior no pintó: modo seguro
        pintar("OPENGL", 600)
        ponerBanda()
        soloSale(fallo)
        onNodeWithText("Entendido").performClick()
        soloSale(null)
        assertTrue(ArchivoDeDibujo.leer(carpeta).sinAviso)
    }

    @Test fun `un archivo en rapido con fallo, editado a mano, no fija el motor ni avisa que falló`() = runComposeUiTest {
        arrancar(Ajuste(modo = Modo.RAPIDO, fallo = true))
        assertEquals(emptyList(), fijados)
        pintar("OPENGL", 600)
        ponerBanda()
        soloSale(null)
        assertEquals(Modo.AUTO, DibujoDeLaCaja.estado.value.modo)
    }

    @Test fun `en modo rápido y aun lento dice que no alcanza`() = runComposeUiTest {
        arrancar(Ajuste(modo = Modo.RAPIDO))
        pintar("SOFTWARE_FAST", 9_000)
        ponerBanda()
        soloSale(noAlcanza)
        onNodeWithText("Entendido").performClick()
        soloSale(null)
    }

    @Test fun `una sola banda a la vez, la confirmación va antes que el aviso que sigue vivo`() = runComposeUiTest {
        arrancar()
        pintar("SOFTWARE", 9_000)   // en automático, Skiko cayó solo a software y aun así tarda
        ponerBanda()
        soloSale(noAlcanza)
        runOnIdle { assertTrue(DibujoDeLaCaja.elegir(Modo.RAPIDO)) }   // desde «Más»: se aplica al reabrir
        assertEquals(Aviso.NO_ALCANZA, DibujoDeLaCaja.estado.value.aviso)
        soloSale(confirmacion)
        onNodeWithText("Entendido").performClick()
        soloSale(noAlcanza)
    }

    @Test fun `callar que no alcanza, elegir Automático en Más y dar Entendido no trae de vuelta ninguna banda`() = runComposeUiTest {
        arrancar(Ajuste(modo = Modo.RAPIDO))
        pintar("SOFTWARE_FAST", 9_000)
        ponerBanda()
        soloSale(noAlcanza)
        onNodeWithText("Entendido").performClick()
        soloSale(null)
        runOnIdle { assertTrue(DibujoDeLaCaja.elegir(Modo.AUTO)) }   // desde «Más»: se aplica al reabrir
        soloSale(confirmacion)
        onNodeWithText("Entendido").performClick()
        soloSale(null)
    }

    @Test fun `elegir Rápido y luego Automático no trae la oferta`() = runComposeUiTest {
        arrancar()
        pintar("OPENGL", 9_000)
        ponerBanda()
        onNodeWithText("Activar modo rápido").performClick()
        soloSale(confirmacion)
        runOnIdle { assertTrue(DibujoDeLaCaja.elegir(Modo.AUTO)) }   // desde «Más»: ya no hay nada que aplicar al reabrir
        soloSale(null)
        assertNull(DibujoDeLaCaja.estado.value.aviso)
    }

    @Test fun `con FLUIDA, LIGERA o sin medición no sale nada`() = runComposeUiTest {
        arrancar()
        ponerBanda()
        assertNull(DibujoDeLaCaja.estado.value.rendimiento)
        soloSale(null)

        runOnIdle { arrancar(); pintar("DIRECT3D", 600) }
        assertEquals(Rendimiento.FLUIDA, DibujoDeLaCaja.estado.value.rendimiento)
        soloSale(null)

        runOnIdle { arrancar(); pintar("OPENGL", 600) }
        assertEquals(Rendimiento.FLUIDA, DibujoDeLaCaja.estado.value.rendimiento)
        soloSale(null)

        runOnIdle { arrancar(Ajuste(modo = Modo.RAPIDO)); pintar("SOFTWARE_FAST", 1_500) }
        assertEquals(Rendimiento.LIGERA, DibujoDeLaCaja.estado.value.rendimiento)
        soloSale(null)
    }

    @Test fun `si no se puede guardar sale el error, el botón se queda y el modo no cambia`() = runComposeUiTest {
        arrancar()
        pintar("OPENGL", 9_000)
        ponerBanda()
        soloLectura()
        onNodeWithText("Activar modo rápido").performClick()
        onNodeWithText(noSeGuardo).assertExists()
        soloSale(ofrecer)
        onNodeWithText("Activar modo rápido").assertExists()
        assertEquals(Modo.AUTO, DibujoDeLaCaja.estado.value.modo)
        assertEquals(Modo.AUTO, ArchivoDeDibujo.leer(carpeta).modo)
    }

    // MARK: - La raíz real: la banda no toma el foco ni se come las teclas

    /** La app de verdad (como Main, antes de la ventana), una vez por prueba; [soltarLaApp] deja todo como estaba. */
    private var datosDeLaApp: Path? = null
    private var instaladaAntes: Pair<Context, Injector>? = null

    private fun prepararLaApp() {
        if (datosDeLaApp != null) return
        instaladaAntes = runCatching { Escritorio.contexto to Escritorio.inyector }.getOrNull()
        val datos = Files.createTempDirectory("Avoqado POS aviso de dibujo")
        datosDeLaApp = datos
        Bitacora.iniciar(datos)
        Arranque.abrir(datos)
    }

    @AfterTest fun soltarLaApp() {
        val datos = datosDeLaApp ?: return
        datosDeLaApp = null
        RaizDeEscritorio.viewModelStore.clear()
        instaladaAntes?.let { (contexto, inyector) -> Escritorio.instalar(contexto, inyector) }
        datos.toFile().deleteRecursively()
    }

    /**
     * Sustituye «F12 sigue llegando a la pantalla»: aquí no hay ventana con sus atajos, así que se mira que la tecla que manda
     * el campo enfocado sube hasta el `onKeyEvent` de la pantalla de abajo con la banda a la vista (los atajos de la ventana
     * reciben lo que nadie usó, igual que ese `onKeyEvent`).
     */
    @Test fun `la banda no toma el foco, el campo enfocado lo conserva y F12 llega a la pantalla`() = runComposeUiTest {
        arrancar()
        prepararLaApp()
        mainClock.autoAdvance = false
        val teclas = mutableListOf<Key>()
        val foco = FocusRequester()
        setContent {
            AppEscritorio { _: WindowSizeClass ->
                var texto by remember { mutableStateOf("") }
                Box(Modifier.fillMaxSize().onKeyEvent { if (it.type == KeyEventType.KeyDown) teclas += it.key; false }) {
                    BasicTextField(texto, { texto = it }, Modifier.testTag("campo").focusRequester(foco))
                }
            }
        }
        mainClock.advanceTimeBy(3_000)   // ya sin el splash, que se come los toques
        mainClock.autoAdvance = true   // con el reloj parado no se atienden los toques
        runOnIdle { foco.requestFocus() }
        onNodeWithTag("campo").assertIsFocused()

        runOnIdle { pintar("OPENGL", 9_000) }
        onNodeWithText(ofrecer).assertExists()
        onNodeWithTag("campo").assertIsFocused()
        onNodeWithTag("campo").performKeyInput { pressKey(Key.F12) }
        assertEquals(listOf(Key.F12), teclas)

        onNodeWithText("Ahora no").performClick()   // tocar la banda tampoco se lleva el foco
        onNodeWithText(ofrecer).assertDoesNotExist()
        onNodeWithTag("campo").assertIsFocused()
    }

    // MARK: - «Más»

    private val datos = DatosDelEquipo(
        windows = true, versionSO = "10.0", ramGb = 3.87, nucleos = 4, procesador = "Intel(R) Core(TM) i5-3470 CPU @ 3.20GHz",
        discoLibreGb = 40.0, anchoPx = 1366, altoPx = 768, puntosTactiles = 0,
        motorDeDibujo = "OPENGL", primerCuadroMs = 9_000, toqueConElDedo = "omitido",
    )

    private fun ComposeUiTest.masDeEscritorio(delEquipo: DatosDelEquipo? = datos) {
        prepararLaApp()
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalEsEscritorio provides true) {
                AppEscritorio(datosDelEquipo = { delEquipo }) { MoreMenuScreen(onLogout = {}) }
            }
        }
        mainClock.advanceTimeBy(3_000)   // ya sin el splash, que se come los toques
        mainClock.autoAdvance = true   // con el reloj parado no se atienden los toques
    }

    @Test fun `en Más de escritorio salen los dos renglones y elegir Modo rápido llama a elegir(RAPIDO)`() = runComposeUiTest {
        arrancar()
        pintar("OPENGL", 600)
        masDeEscritorio()
        onNodeWithText("Modo de dibujo").assert(hasText("Automático"))
        onNodeWithText("Diagnóstico de esta computadora").assertExists()

        onNodeWithText("Modo de dibujo").performClick()
        onNodeWithText(explicacion).assertExists()
        onNodeWithText("Se aplica al volver a abrir el POS.").assertDoesNotExist()
        onNodeWithText("Modo rápido").performClick()
        assertEquals(Modo.RAPIDO, DibujoDeLaCaja.estado.value.modo)
        assertEquals(Modo.RAPIDO, ArchivoDeDibujo.leer(carpeta).modo)
        onNodeWithText("Se aplica al volver a abrir el POS.").assertExists()

        onNodeWithContentDescription("Cerrar").performClick()
        onNodeWithText(explicacion).assertDoesNotExist()
        onNodeWithText("Modo de dibujo").assert(hasText("Modo rápido"))
        onNodeWithText(confirmacion).assertExists()
    }

    @Test fun `en Más el diagnóstico muestra el veredicto de esta computadora`() = runComposeUiTest {
        arrancar()
        pintar("OPENGL", 9_000)
        masDeEscritorio()
        onNodeWithText("Diagnóstico de esta computadora").performClick()
        onNodeWithText(veredicto(datos.copy(dibujo = DibujoDeLaCaja.estado.value)).comoTexto()).assertExists()
    }

    @Test fun `el diagnóstico se ve en Más aunque no se pueda escribir su archivo`() = runComposeUiTest {
        arrancar()
        pintar("OPENGL", 9_000)
        val sinEscritura = Files.createTempDirectory("diagnostico-sin-escritura")
        try {
            soloLectura(sinEscritura)
            val escritos = Diagnostico.escribir(sinEscritura, "OPENGL", 9_000, "omitido", DibujoDeLaCaja.estado.value, equipo = datos)
            assertFalse(Files.exists(sinEscritura.resolve("logs/diagnostico.txt")))
            masDeEscritorio(escritos)
            onNodeWithText("Diagnóstico de esta computadora").performClick()
            onNodeWithText(veredicto(escritos.copy(dibujo = DibujoDeLaCaja.estado.value)).comoTexto()).assertExists()
        } finally {
            sinEscritura.toFile().setWritable(true)
            sinEscritura.toFile().deleteRecursively()
        }
    }

    @Test fun `con el apagador puesto no sale Modo de dibujo ni ninguna banda, y el diagnóstico sí`() = runComposeUiTest {
        arrancar(propiedades = mapOf("avoqado.dibujo.apagado" to "true"))
        assertTrue(DibujoDeLaCaja.apagado)
        pintar("OPENGL", 9_000)
        masDeEscritorio()
        onNodeWithText("Diagnóstico de esta computadora").assertExists()
        onNodeWithText("Modo de dibujo").assertDoesNotExist()
        soloSale(null)
    }

    @Test fun `en ANDROID esos renglones no existen`() = runComposeUiTest {
        arrancar()
        pintar("OPENGL", 9_000)
        prepararLaApp()
        setContent {   // lo que pone MainActivity: ni LocalEsEscritorio ni los renglones del escritorio
            CompositionLocalProvider(LocalViewModelStoreOwner provides RaizDeEscritorio, LocalLifecycleOwner provides RaizDeEscritorio) {
                ConfiguracionDeAndroid(1280.dp, 800.dp) { AvoqadoTheme(darkTheme = false) { MoreMenuScreen(onLogout = {}) } }
            }
        }
        onNodeWithText("Ajustes").assertExists()
        onNodeWithText("Modo de dibujo").assertDoesNotExist()
        onNodeWithText("Diagnóstico de esta computadora").assertDoesNotExist()
    }

    // MARK: - La hoja de «Modo de dibujo», sola

    private fun ComposeUiTest.ponerHoja() = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalConfiguration provides configuracionDeAndroid(1280.dp, 800.dp, 1f), LocalEsEscritorio provides true) {
                HojaDeModoDeDibujo(onDismiss = {})
            }
        }
    }

    @Test fun `con el motor fijado a mano la hoja lo dice`() = runComposeUiTest {
        arrancar(entorno = mapOf("SKIKO_RENDER_API" to "OPENGL"))
        pintar("OPENGL", 600)
        ponerHoja()
        onNodeWithText("Fijado a mano en esta computadora (OPENGL).").assertExists()
        onNodeWithText("Se aplica al volver a abrir el POS.").assertDoesNotExist()
    }

    @Test fun `si la hoja no puede guardar lo dice y el modo no cambia`() = runComposeUiTest {
        arrancar()
        pintar("OPENGL", 600)
        ponerHoja()
        soloLectura()
        onNodeWithText("Modo rápido").performClick()
        onNodeWithText(noSeGuardo).assertExists()
        assertEquals(Modo.AUTO, DibujoDeLaCaja.estado.value.modo)
    }
}
