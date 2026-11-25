package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.content.Context
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.Escritorio
import com.google.inject.Injector
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.customerdisplay.CustomerDisplayScreen
import com.avoqado.pos.customerdisplay.CustomerDisplayState
import com.avoqado.pos.designsystem.LocalModoLigero
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.escritorio.dibujo.Ajuste
import com.avoqado.pos.escritorio.dibujo.ArchivoDeDibujo
import com.avoqado.pos.escritorio.dibujo.DibujoDeLaCaja
import com.avoqado.pos.escritorio.dibujo.Modo
import com.avoqado.pos.navigation.NavHostDelPos
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Modo ligero del POS de Windows (hz:a5fa2439:D2): cuando se dibuja sin la tarjeta de video se quitan el movimiento del
 * indicador de carga, la onda del clic, las transiciones entre pantallas y el reposo animado de la pantalla del cliente. Con
 * el modo apagado (lo de Android) todo se comporta como hoy. El reloj va a mano: hay animaciones infinitas.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class ModoLigeroTest {
    private val carpeta = Files.createTempDirectory("modo-ligero")
    private val contexto = ContextoDeEscritorio(carpeta)

    @BeforeTest fun armar() = DibujoDeLaCaja.reiniciarParaPruebas()

    @AfterTest fun limpiar() {
        DibujoDeLaCaja.reiniciarParaPruebas()
        carpeta.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.pixeles(): IntArray {
        val img = onRoot().captureToImage().toAwtImage()
        return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    }

    private fun ligero(valor: Boolean, content: @Composable () -> Unit): @Composable () -> Unit =
        { CompositionLocalProvider(LocalModoLigero provides valor, content = content) }

    // MARK: - Indicador de carga

    private fun ComposeUiTest.cargaSeMueveEnUnSegundo(modoLigero: Boolean): Boolean {
        mainClock.autoAdvance = false
        // Fondo opaco: sobre transparente, los bordes suavizados se acumulan de una captura a otra (aun sin avanzar el reloj).
        setContent(ligero(modoLigero) { Box(Modifier.fillMaxSize().background(Color.White)) { AvoqadoBrandLoader() } })
        mainClock.advanceTimeBy(300)
        val antes = pixeles()
        mainClock.advanceTimeBy(1_000)
        return !antes.contentEquals(pixeles())
    }

    @Test fun `en modo ligero el indicador de carga queda quieto`() = runComposeUiTest {
        assertFalse(cargaSeMueveEnUnSegundo(modoLigero = true))
    }

    @Test fun `sin modo ligero el indicador de carga se mueve como hoy`() = runComposeUiTest {
        assertTrue(cargaSeMueveEnUnSegundo(modoLigero = false))
    }

    // MARK: - La onda del clic

    @Test fun `la onda del clic se apaga solo en modo ligero`() = runComposeUiTest {
        var deFuera: RippleConfiguration? = null
        var conLigero: RippleConfiguration? = RippleConfiguration()
        var sinLigero: RippleConfiguration? = null
        var localCon: Boolean? = null
        var localSin: Boolean? = null
        setContent {
            deFuera = LocalRippleConfiguration.current
            ConModoLigero(ligero = true) { conLigero = LocalRippleConfiguration.current; localCon = LocalModoLigero.current }
            ConModoLigero(ligero = false) { sinLigero = LocalRippleConfiguration.current; localSin = LocalModoLigero.current }
        }
        waitForIdle()
        assertNull(conLigero)
        assertSame(deFuera, sinLigero, "apagado: la misma onda de siempre, no una copia")
        assertEquals(true, localCon)
        assertEquals(false, localSin)
    }

    // MARK: - Transiciones entre pantallas

    private class Dueno : ViewModelStoreOwner, LifecycleOwner {
        override val viewModelStore = ViewModelStore()
        private val registro = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registro
    }

    @Composable private fun DosPantallas(alCrear: (NavHostController) -> Unit) {
        val nav = rememberNavController()
        alCrear(nav)
        NavHostDelPos(navController = nav, startDestination = "a", modifier = Modifier) {
            composable("a") { Text("Pantalla A") }
            composable("b") { Text("Pantalla B") }
        }
    }

    /** Navega de A a B y dice si A sigue compuesta tras un cuadro. */
    private fun ComposeUiTest.aSigueTrasUnCuadro(navegar: () -> NavHostController): Boolean {
        mainClock.advanceTimeBy(100)
        assertEquals(1, onAllNodesWithText("Pantalla A").fetchSemanticsNodes().size)
        runOnUiThread { navegar().navigate("b") }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        assertEquals(1, onAllNodesWithText("Pantalla B").fetchSemanticsNodes().size)
        return onAllNodesWithText("Pantalla A").fetchSemanticsNodes().isNotEmpty()
    }

    private fun ComposeUiTest.navegacion(modoLigero: Boolean): Boolean {
        mainClock.autoAdvance = false
        val dueno = Dueno()
        lateinit var nav: NavHostController
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides dueno, LocalLifecycleOwner provides dueno, LocalModoLigero provides modoLigero) {
                DosPantallas { nav = it }
            }
        }
        return aSigueTrasUnCuadro { nav }
    }

    @Test fun `en modo ligero cambiar de pantalla no deja la anterior compuesta`() = runComposeUiTest {
        assertFalse(navegacion(modoLigero = true))
    }

    @Test fun `sin modo ligero la pantalla anterior se desvanece como hoy`() = runComposeUiTest {
        assertTrue(navegacion(modoLigero = false))
    }

    // MARK: - Reposo de la pantalla del cliente (las dos ramas)

    @Composable private fun superficie(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides contexto, LocalConfiguration provides contexto.resources.configuration,
            LocalView provides View(contexto)) { AvoqadoTheme(darkTheme = false, content = content) }
    }

    private fun marcaDeAvoqado() = CustomerDisplayState()   // sin sesión: habla por Avoqado (marca sobre la aurora)
    private fun marcaDelNegocio() = CustomerDisplayState().apply { setVenueBranding("Un negocio", null) }

    /** Capturas del reposo a 1 s, 2 s y 31 s de montado. */
    private fun ComposeUiTest.reposo(state: CustomerDisplayState, modoLigero: Boolean): Triple<IntArray, IntArray, IntArray> {
        mainClock.autoAdvance = false
        setContent { superficie { CompositionLocalProvider(LocalModoLigero provides modoLigero) { CustomerDisplayScreen(state = state, onRating = {}, onTip = {}) } } }
        mainClock.advanceTimeBy(1_000)
        val uno = pixeles()
        mainClock.advanceTimeBy(1_000)
        val dos = pixeles()
        mainClock.advanceTimeBy(29_000)
        return Triple(uno, dos, pixeles())
    }

    @Test fun `en modo ligero el reposo de Avoqado queda quieto y su fondo avanza cada 30 s`() = runComposeUiTest {
        val (uno, dos, treintaYUno) = reposo(marcaDeAvoqado(), modoLigero = true)
        assertContentEquals(uno, dos)
        assertFalse(uno.contentEquals(treintaYUno), "el fondo debe moverse a los 30 s: protección contra imagen quemada")
    }

    @Test fun `en modo ligero el reposo del negocio queda quieto y avanza cada 30 s`() = runComposeUiTest {
        val (uno, dos, treintaYUno) = reposo(marcaDelNegocio(), modoLigero = true)
        assertContentEquals(uno, dos)
        assertFalse(uno.contentEquals(treintaYUno), "el letrero debe moverse a los 30 s: protección contra imagen quemada")
    }

    @Test fun `sin modo ligero los dos reposos se mueven como hoy`() {
        runComposeUiTest { val (uno, dos, _) = reposo(marcaDeAvoqado(), modoLigero = false); assertFalse(uno.contentEquals(dos)) }
        runComposeUiTest { val (uno, dos, _) = reposo(marcaDelNegocio(), modoLigero = false); assertFalse(uno.contentEquals(dos)) }
    }

    // MARK: - Por la raíz real (AppEscritorio + DibujoDeLaCaja)

    private val datosDelDibujo: Path = carpeta.resolve("dibujo")

    private fun arrancarDibujo(archivo: Ajuste? = null, entorno: Map<String, String> = emptyMap()) {
        DibujoDeLaCaja.reiniciarParaPruebas()
        if (archivo != null) assertTrue(ArchivoDeDibujo.guardar(datosDelDibujo, archivo))
        DibujoDeLaCaja.alArrancar(datosDelDibujo, entorno::get, emptyMap<String, String>()::get, fijar = {}, ahoraNs = { 0L })
    }

    /** La app de verdad (como Main, antes de la ventana), una vez por prueba; [soltarLaApp] deja todo como estaba. */
    private var datosDeLaApp: Path? = null
    private var instaladaAntes: Pair<Context, Injector>? = null

    private fun prepararLaApp() {
        if (datosDeLaApp != null) return
        instaladaAntes = runCatching { Escritorio.contexto to Escritorio.inyector }.getOrNull()
        val datos = Files.createTempDirectory("Avoqado POS modo ligero")
        datosDeLaApp = datos
        Bitacora.iniciar(datos)
        Arranque.abrir(datos)
    }

    @AfterTest fun soltarLaApp() {
        val datos = datosDeLaApp ?: return
        datosDeLaApp = null
        RaizDeEscritorio.viewModelStore.clear()   // los ViewModels de esta prueba no los hereda la siguiente
        instaladaAntes?.let { (contexto, inyector) -> Escritorio.instalar(contexto, inyector) }
        datos.toFile().deleteRecursively()
    }

    /** La raíz real con un contenido que anota cómo ve el modo en su PRIMER cuadro y deja navegar. */
    private fun ComposeUiTest.raizConDosPantallas(): Pair<() -> Boolean?, () -> NavHostController> {
        prepararLaApp()
        mainClock.autoAdvance = false
        var primero: Boolean? = null
        lateinit var nav: NavHostController
        setContent {
            AppEscritorio { _: WindowSizeClass ->
                if (primero == null) primero = LocalModoLigero.current
                DosPantallas { nav = it }
            }
        }
        return Pair({ primero }, { nav })
    }

    @Test fun `con el archivo en rapido el NavHost se compone ligero desde el primer cuadro`() = runComposeUiTest {
        arrancarDibujo(Ajuste(modo = Modo.RAPIDO))
        assertTrue(DibujoDeLaCaja.estado.value.modoLigero)
        val (primero, nav) = raizConDosPantallas()
        mainClock.advanceTimeByFrame()
        assertEquals(true, primero())
        assertFalse(aSigueTrasUnCuadro(nav))
    }

    @Test fun `con SKIKO_RENDER_API en software el NavHost se compone ligero desde el primer cuadro`() = runComposeUiTest {
        arrancarDibujo(entorno = mapOf("SKIKO_RENDER_API" to "DIRECT_SOFTWARE"))
        assertTrue(DibujoDeLaCaja.estado.value.modoLigero)
        val (primero, nav) = raizConDosPantallas()
        mainClock.advanceTimeByFrame()
        assertEquals(true, primero())
        assertFalse(aSigueTrasUnCuadro(nav))
    }

    @Test fun `con un archivo que solo trae la pista de caer sola a software el NavHost se compone ligero desde el primer cuadro`() = runComposeUiTest {
        arrancarDibujo(Ajuste(caeSolaASoftware = true))
        assertTrue(DibujoDeLaCaja.estado.value.modoLigero)
        val (primero, nav) = raizConDosPantallas()
        mainClock.advanceTimeByFrame()
        assertEquals(true, primero())
        assertFalse(aSigueTrasUnCuadro(nav))
    }

    /**
     * El splash: la pantalla entera a 0, 500, 1 000 y 1 300 ms, y la esquina cuadro por cuadro de 1.3 s a 2.6 s. El splash es
     * blanco y el inicio de sesión de escritorio es del color de marca oscuro: la esquina dice cuándo empieza y acaba de irse.
     */
    private data class Splash(val capturas: List<IntArray>, val esquinas: List<Int>)

    private fun splash(modo: Modo?): Splash {
        var resultado: Splash? = null
        runComposeUiTest {
            prepararLaApp()
            arrancarDibujo(modo?.let { Ajuste(modo = it) })
            assertEquals(modo == Modo.RAPIDO, DibujoDeLaCaja.estado.value.modoLigero)
            mainClock.autoAdvance = false
            setContent { AppEscritorio() }
            val capturas = mutableListOf(pixeles())
            for (paso in listOf(500L, 500L, 300L)) {
                mainClock.advanceTimeBy(paso)
                capturas += pixeles()
            }
            val esquinas = mutableListOf<Int>()
            while (mainClock.currentTime < 2_600) {
                esquinas += onRoot().captureToImage().toAwtImage().getRGB(2, 2)
                mainClock.advanceTimeByFrame()
            }
            resultado = Splash(capturas, esquinas)
        }
        return resultado!!
    }

    @Test fun `el splash se ve y dura igual con el modo ligero prendido y apagado`() {
        val apagado = splash(modo = null)
        val prendido = splash(modo = Modo.RAPIDO)
        assertFalse(apagado.capturas[0].contentEquals(apagado.capturas[1]), "el splash se mueve")
        apagado.capturas.zip(prendido.capturas).forEachIndexed { i, (a, p) -> assertContentEquals(a, p, "captura $i") }
        assertEquals(0xFFFFFFFF.toInt(), apagado.esquinas.first(), "a los 1.3 s sigue el splash")
        assertEquals(0xFF1C1C1E.toInt(), apagado.esquinas.last(), "a los 2.6 s ya se fue")
        assertEquals(apagado.esquinas, prendido.esquinas, "se va en el mismo cuadro y con la misma salida en los dos modos")
    }

    @Test fun `si Skiko cae solo a software con la app montada, lo escrito se conserva`() = runComposeUiTest {
        arrancarDibujo()
        assertFalse(DibujoDeLaCaja.estado.value.modoLigero)
        prepararLaApp()
        mainClock.autoAdvance = false
        var montajes = 0
        var ligeroDentro: Boolean? = null
        lateinit var nav: NavHostController
        setContent {
            AppEscritorio { _: WindowSizeClass ->
                nav = rememberNavController()
                NavHostDelPos(navController = nav, startDestination = "a", modifier = Modifier) {
                    composable("a") {
                        var texto by remember { mutableStateOf("") }
                        DisposableEffect(Unit) { montajes++; onDispose { } }
                        ligeroDentro = LocalModoLigero.current
                        BasicTextField(texto, { texto = it }, Modifier.testTag("campo"))
                    }
                    composable("b") { Text("Pantalla B") }
                }
            }
        }
        mainClock.advanceTimeBy(1_700)   // ya sin splash
        onNodeWithTag("campo").performTextInput("hola")
        mainClock.advanceTimeByFrame()
        assertEquals(false, ligeroDentro)

        runOnIdle { DibujoDeLaCaja.alPintar("SOFTWARE") }   // la ventana abrió con software: ahora sí es ligero
        assertTrue(DibujoDeLaCaja.estado.value.modoLigero)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        assertEquals(true, ligeroDentro)
        onNodeWithTag("campo").assertTextEquals("hola")
        assertEquals(1, montajes, "la pantalla no se vuelve a crear")
    }
}
