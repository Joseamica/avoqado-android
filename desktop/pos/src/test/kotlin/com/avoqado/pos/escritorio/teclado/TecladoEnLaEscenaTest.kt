package com.avoqado.pos.escritorio.teclado

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.avoqado.pos.escritorio.tactil.ContactoDeWindows
import com.avoqado.pos.escritorio.tactil.FaseDeToque
import com.avoqado.pos.escritorio.tactil.TraductorDeToques
import com.avoqado.pos.escritorio.tactil.enviarToque
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.fillMaxSize
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** El campo REAL de Compose recibiendo las teclas del teclado en pantalla, y el panel tocado por el puente táctil. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class TecladoEnLaEscenaTest {
    private var reloj = 1_000L
    private fun ImageComposeScene.interna(): ComposeScene =
        ImageComposeScene::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(this) as ComposeScene

    private fun ImageComposeScene.cuadros(n: Int = 1) = repeat(n) { reloj += 16; render(reloj * 1_000_000) }

    private fun ImageComposeScene.tocar(x: Int, y: Int) {
        val tr = TraductorDeToques { px, py -> Offset(px.toFloat(), py.toFloat()) }
        tr.traducir(ContactoDeWindows(7, x, y, FaseDeToque.BAJA, reloj))?.let { interna().enviarToque(it) }
        reloj += 60; render(reloj * 1_000_000)
        tr.traducir(ContactoDeWindows(7, x, y, FaseDeToque.SUBE, reloj))?.let { interna().enviarToque(it) }
        cuadros(5)
    }

    /**
     * Cada tecla espera a que el campo se VUELVA A COMPONER con el texto nuevo antes de la siguiente, como pasa con un
     * cajero real (entre dos toques hay decenas de cuadros). Sin esto, en el runner lento de GitHub la tecla siguiente se
     * aplicaba sobre el texto viejo y se «perdían» letras: «Hol andú» en vez de «Hola ñandú» (run 37054232000).
     */
    private fun ImageComposeScene.entregarTecla(tecla: java.awt.event.KeyEvent, caja: Caja) {
        // Publica el cambio antes del cuadro, sin competir con las notificaciones globales de otras escenas.
        Snapshot.withMutableSnapshot { sendKeyEvent(teclaDeCompose(tecla)) }
        cuadros()
        var vueltas = 0
        while (caja.compuesto != caja.texto && vueltas++ < 200) { Thread.sleep(2); cuadros() }
        check(caja.compuesto == caja.texto) { "el campo no se recompuso: compuesto=«${caja.compuesto}» texto=«${caja.texto}»" }
    }

    private fun ImageComposeScene.teclas(caja: Caja) =
        EscritorDeTeclas(object : java.awt.Component() {}) { entregarTecla(it, caja) }

    private class Caja { @Volatile var texto = ""; @Volatile var compuesto = ""; var hecho = 0 }

    private fun conCampo(caja: Caja, panel: Boolean, estado: EstadoDeTeclas = EstadoDeTeclas(), prueba: (ImageComposeScene, EscritorDeTeclas?) -> Unit) {
        lateinit var escena: ImageComposeScene
        var escritor: EscritorDeTeclas? = null
        escena = ImageComposeScene(600, 600, Density(1f)) {
            MaterialTheme {
                Column {
                    // Panel arriba (filas de 52 dp desde y=0); el campo abajo, en y=400.
                    if (panel) TecladoEnPantalla({ t -> estado.presionar(t)?.let { escritor!!.escribir(it) } }, estado, Modifier.fillMaxWidth())
                    else androidx.compose.foundation.layout.Spacer(Modifier.height(260.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.height(140.dp))
                    var v by remember { mutableStateOf("") }
                    BasicTextField(
                        value = v, onValueChange = { v = it; caja.texto = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { caja.hecho++ }),
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                    )
                    SideEffect { caja.compuesto = v }
                }
            }
        }
        try {
            escritor = escena.teclas(caja)
            escena.cuadros(2)
            prueba(escena, escritor)
        } finally { escena.close() }
    }

    private fun EscritorDeTeclas.tecleo(e: EstadoDeTeclas, vararg ts: Tecla) = ts.forEach { e.presionar(it)?.let(::escribir) }

    @Test fun `escribir Hola nandu, borrar y enter en el campo real`() {
        val caja = Caja(); val e = EstadoDeTeclas()
        conCampo(caja, panel = false) { s, w ->
            s.tocar(100, 420); s.cuadros(3)   // el campo toma el foco
            w!!.tecleo(e, Tecla.Mayus, Tecla.Letra('h'), Tecla.Letra('o'), Tecla.Letra('l'), Tecla.Letra('a'), Tecla.Espacio,
                Tecla.Letra('ñ'), Tecla.Letra('a'), Tecla.Letra('n'), Tecla.Letra('d'), Tecla.Acento, Tecla.Letra('u'))
            assertEquals("Hola ñandú", caja.texto)
            w.tecleo(e, Tecla.Borrar)
            assertEquals("Hola ñand", caja.texto)
            w.tecleo(e, Tecla.Enter)
            assertEquals(1, caja.hecho)
        }
    }

    @Test fun `el dedo sobre una tecla escribe sin quitarle el foco al campo`() {
        val caja = Caja(); val e = EstadoDeTeclas()
        conCampo(caja, panel = true, estado = e) { s, _ ->
            s.tocar(100, 420); s.cuadros(3)
            // «a»: 3.ª fila (índice 2) del panel, primera tecla de 10: x = 600/20, y = 2*52 + 26.
            s.tocar(30, 130)
            s.tocar(30, 130)
            assertEquals("aa", caja.texto)   // el foco sobrevivió al primer toque; si no, el segundo no escribiría
        }
    }

    @Test fun `el dedo escribe en un campo de un dialogo con el teclado encima de el`() {
        val caja = Caja(); val e = EstadoDeTeclas()
        var abierto = true
        lateinit var escena: ImageComposeScene
        var escritor: EscritorDeTeclas? = null
        escena = ImageComposeScene(600, 600, Density(1f)) {
            MaterialTheme {
                ConTecladoEnPantalla(
                    visible = true,
                    teclado = { TecladoEnPantalla({ t -> e.presionar(t)?.let { escritor!!.escribir(it) } }, e, Modifier.fillMaxWidth()) },
                ) {
                    if (abierto) androidx.compose.ui.window.Dialog(onDismissRequest = { abierto = false }) {
                        var v by remember { mutableStateOf("") }
                        BasicTextField(
                            value = v, onValueChange = { v = it; caja.texto = it },
                            modifier = Modifier.width(300.dp).height(60.dp),
                        )
                        SideEffect { caja.compuesto = v }
                    }
                }
            }
        }
        try {
            escritor = escena.teclas(caja)
            escena.cuadros(3)
            escena.tocar(300, 280)   // el campo del diálogo (centrado)
            escena.cuadros(3)
            // «a» del popup: el panel mide 5 × 52 dp y se pega abajo (y de 340 a 600): fila 2 ⇒ y = 340 + 130.
            escena.tocar(30, 470)
            escena.tocar(30, 470)
            assertEquals("aa", caja.texto)
            assertTrue(abierto, "tocar el teclado no debe contar como «toque afuera» del diálogo")
        } finally { escena.close() }
    }

    @Test fun `la tecla responde en toda su caja de 52 dp, tambien en el margen`() {
        val caja = Caja(); val e = EstadoDeTeclas()
        conCampo(caja, panel = true, estado = e) { s, _ ->
            s.tocar(100, 420); s.cuadros(3)
            s.tocar(1, 130)    // el borde izquierdo de «a»: dentro de los 2 dp de margen que sólo dibujan
            s.tocar(30, 105)   // y el borde alto de la fila (y=104 es el límite con la fila de arriba)
            assertEquals("aa", caja.texto)
        }
    }

    // --- diálogos, capas y ajuste de teclado ---

    private fun solicitudNueva(): java.awt.im.InputMethodRequests = java.lang.reflect.Proxy.newProxyInstance(
        java.awt.im.InputMethodRequests::class.java.classLoader, arrayOf(java.awt.im.InputMethodRequests::class.java),
    ) { _, _, _ -> null } as java.awt.im.InputMethodRequests

    private fun ComposeScene.capaEnfocadaReal(): Any? =
        generateSequence<Class<*>>(javaClass) { it.superclass }.firstNotNullOf { c -> c.declaredFields.firstOrNull { it.name == "focusedLayer" } }
            .apply { isAccessible = true }.get(this)

    /** Campo A en la pantalla principal; al activar [abrir] se abre un Dialog con el campo B. La sesión de escritura se simula. */
    private fun conDialogoYTeclado(cajaB: Caja, prueba: (ImageComposeScene, (Boolean) -> Unit, () -> Unit) -> Unit) {
        var abierto by mutableStateOf(false)
        var solicitud: java.awt.im.InputMethodRequests? = null
        val lienzo = object : java.awt.Component() { override fun getInputMethodRequests() = solicitud }
        TecladoDeLaVentana.reiniciar()
        lateinit var escena: ImageComposeScene
        escena = ImageComposeScene(600, 600, Density(1f)) {
            MaterialTheme {
                ConTecladoEnPantalla(
                    visible = TecladoDeLaVentana.visible.value,
                    generacion = TecladoDeLaVentana.generacion.value,
                    teclado = { TecladoEnPantalla(TecladoDeLaVentana::alPresionar, TecladoDeLaVentana.estado, Modifier.fillMaxWidth()) },
                ) {
                    BasicTextField(value = "", onValueChange = {}, modifier = Modifier.fillMaxWidth().height(40.dp))
                    if (abierto) androidx.compose.ui.window.Dialog(onDismissRequest = { abierto = false }) {
                        var v by remember { mutableStateOf("") }
                        BasicTextField(value = v, onValueChange = { v = it; cajaB.texto = it }, modifier = Modifier.width(300.dp).height(60.dp))
                        SideEffect { cajaB.compuesto = v }
                    }
                }
            }
        }
        try {
            TecladoDeLaVentana.configurar(lienzo, null) { escena.entregarTecla(it, cajaB) }
            TecladoDeLaVentana.capaEnfocada = { escena.interna().capaEnfocadaReal() }
            escena.cuadros(3)
            val dedo = { TecladoDeLaVentana.alPuntero(Puntero.DEDO) }
            prueba(escena, { a -> abierto = a; escena.cuadros(3) }) { solicitud = solicitudNueva(); dedo(); TecladoDeLaVentana.revisar(); escena.cuadros(3) }
            assertTrue(abierto, "tocar el teclado no debe contar como «toque afuera» del diálogo")
        } finally { TecladoDeLaVentana.reiniciar(); escena.close() }
    }

    @Test fun `con el teclado visible, abrir un dialogo lo oculta y tocar su campo lo trae encima`() {
        val b = Caja()
        conDialogoYTeclado(b) { s, abrir, nuevaSesion ->
            s.tocar(100, 20); nuevaSesion()                 // campo A (principal): el teclado se muestra
            assertTrue(TecladoDeLaVentana.visible.value)
            abrir(true); TecladoDeLaVentana.revisar(); s.cuadros(3)
            assertFalse(TecladoDeLaVentana.visible.value, "el campo A quedó detrás del diálogo")
            s.tocar(300, 300); nuevaSesion()                // campo B del diálogo
            assertTrue(TecladoDeLaVentana.visible.value)
            s.tocar(30, 470); s.tocar(30, 470)
            assertEquals("aa", b.texto)
        }
    }

    @Test fun `abrir el dialogo y pasar directo a su campo recrea el popup arriba del dialogo`() {
        val b = Caja()
        conDialogoYTeclado(b) { s, abrir, nuevaSesion ->
            s.tocar(100, 20); nuevaSesion()
            assertTrue(TecladoDeLaVentana.visible.value)
            abrir(true)                                      // sin sondeo entre medias: el teclado sigue «visible»
            s.tocar(300, 300); nuevaSesion()                 // sesión nueva con el teclado ya visible ⇒ otro popup
            assertTrue(TecladoDeLaVentana.visible.value)
            s.tocar(30, 470); s.tocar(30, 470)
            assertEquals("aa", b.texto)
        }
    }

    @Test fun `con el teclado visible el borde de un campo de dialogo de pantalla completa queda arriba de las teclas`() {
        var fondo = Float.MAX_VALUE
        InsetsDelTeclado.instalar()
        val escena = ImageComposeScene(600, 600, Density(1f)) {
            MaterialTheme {
                ConTecladoEnPantalla(visible = true, teclado = { TecladoEnPantalla({}, EstadoDeTeclas(), Modifier.fillMaxWidth()) }) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = {},
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                    ) {
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.fillMaxWidth().height(40.dp).onGloballyPositioned { fondo = it.boundsInRoot().bottom },
                            )
                        }
                    }
                }
            }
        }
        try {
            escena.cuadros(6)
            assertTrue(fondo <= 340f, "el campo debe quedar arriba del teclado (y=340); su borde inferior está en $fondo")
        } finally { InsetsDelTeclado.desinstalar(); escena.close() }
    }
}
