package com.avoqado.pos.escritorio.teclado

import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.scene.ComposeScene
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Une la ventana con el teclado en pantalla: lee el modo escritura del lienzo, el último puntero y las teclas físicas.
 * Todo en el hilo de Swing. NUNCA registra lo tecleado (puede ser una contraseña): sólo mostrar/ocultar y su motivo.
 */
object TecladoDeLaVentana {
    private var logica = TecladoHibrido()
    private val _visible = mutableStateOf(false)
    private val _generacion = mutableIntStateOf(0)

    /** Lo lee AppEscritorio. */
    val visible: State<Boolean> get() = _visible

    /** Sube con cada sesión de escritura nueva que deja el teclado visible: ConTecladoEnPantalla recrea su Popup. */
    val generacion: State<Int> get() = _generacion
    val estado = EstadoDeTeclas()

    private var lienzo: Component? = null
    private var ventana: Window? = null
    private var escritor: EscritorDeTeclas? = null
    private var ultimo: Puntero? = null
    private var sesionPrevia: Any? = null        // identidad de lienzo.inputMethodRequests: Compose crea uno nuevo en cada startInput
    private var capaPrevia: Any? = null          // capa enfocada de la escena (un diálogo es otra capa)
    private var capaDeLaSesion: Any? = null   // la capa donde vive el campo de la sesión actual
    private var ticket = 0                       // generación de interacción: invalida la reapertura pendiente
    private var anotadas = 0
    private var temporizador: Timer? = null
    private var escuchaTeclas: AWTEventListener? = null
    private var escuchaVentana: WindowFocusListener? = null

    /** Costuras de prueba. */
    internal var programar: (Int, () -> Unit) -> Unit = { ms, f -> Timer(ms) { f() }.apply { isRepeats = false }.start() }
    internal var anotar: (String) -> Unit = { Log.i("Teclado", it) }
    internal var capaEnfocada: () -> Any? = { null }
    internal var ventanaActiva: () -> Boolean = { ventana?.isFocused ?: true }

    @OptIn(InternalComposeUiApi::class)
    fun instalar(escena: com.avoqado.pos.escritorio.tactil.EscenaDeVentana, ventana: Window) {
        instalar(escena.lienzo, ventana, escena.escena)
        capaEnfocada = { escena.capaEnfocada() }
        InsetsDelTeclado.instalar()
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    fun instalar(lienzo: Component, ventana: Window, escena: ComposeScene) {
        desinstalar()
        configurar(lienzo, ventana) { escena.sendKeyEvent(teclaDeCompose(it)) }
        temporizador = Timer(100) { revisar() }.also { it.start() }
        escuchaTeclas = AWTEventListener { (it as? KeyEvent)?.let(::alEventoDeTecla) }
        Toolkit.getDefaultToolkit().addAWTEventListener(escuchaTeclas, AWTEvent.KEY_EVENT_MASK)
        escuchaVentana = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent) {}
            override fun windowLostFocus(e: WindowEvent) = alPerderFoco()
        }
        ventana.addWindowFocusListener(escuchaVentana)
    }

    internal fun configurar(lienzo: Component, ventana: Window?, enviar: (KeyEvent) -> Unit) {
        this.lienzo = lienzo
        this.ventana = ventana
        escritor = EscritorDeTeclas(lienzo, enviar)
    }

    /** Idempotente: para el temporizador, quita los escuchas, cancela lo pendiente y suelta las referencias. */
    fun desinstalar() {
        temporizador?.stop(); temporizador = null
        escuchaTeclas?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }; escuchaTeclas = null
        escuchaVentana?.let { ventana?.removeWindowFocusListener(it) }; escuchaVentana = null
        invalidar()
        lienzo = null; ventana = null; escritor = null; capaEnfocada = { null }
        sesionPrevia = null; capaPrevia = null; capaDeLaSesion = null
        logica.alOcultarAMano(); _visible.value = false
        InsetsDelTeclado.alto.floatValue = 0f
        InsetsDelTeclado.desinstalar()
    }

    /** El teclado vuelve a empezar de cero (pruebas). */
    internal fun reiniciar() {
        desinstalar()
        estado.mayus = false; estado.acentoPendiente = false; estado.pagina = 0
        ultimo = null; sesionPrevia = null; capaPrevia = null; anotadas = 0
        logica = TecladoHibrido(); _visible.value = false
        programar = { ms, f -> Timer(ms) { f() }.apply { isRepeats = false }.start() }
        anotar = { Log.i("Teclado", it) }
        ventanaActiva = { ventana?.isFocused ?: true }
    }

    private fun invalidar() { ticket++ }

    /** Cada 100 ms: ¿cambió la sesión de escritura (otro campo, o ninguno) o la capa enfocada (se abrió un diálogo)? */
    internal fun revisar() {
        val sesion = lienzo?.inputMethodRequests
        val capa = capaEnfocada()
        if (sesion !== sesionPrevia) {
            sesionPrevia = sesion; capaPrevia = capa; capaDeLaSesion = capa
            invalidar()
            logica.alCambiarEntrada(sesion != null, if (ventanaActiva()) ultimo else null)
            if (logica.visible) _generacion.intValue++
            sincronizar(if (sesion != null) "campo en escritura, último puntero $ultimo" else "el campo salió de escritura")
        } else if (sesion != null && capa !== capaPrevia) {
            capaPrevia = capa
            invalidar()
            logica.alOcultarAMano()
            sincronizar("cambió la capa enfocada (el campo quedó detrás)")
        } else {
            capaPrevia = capa
        }
    }

    /** Hay un campo escribiendo Y está en la capa de arriba (un diálogo encima lo deja detrás: no se reabre por él). */
    private fun entradaActiva() = lienzo?.inputMethodRequests != null && capaEnfocada() === capaDeLaSesion

    /**
     * El puente (DEDO) o el escucha del mouse (MOUSE). [soltando] = el puntero se levantó; [fueToque] = el dedo no se movió
     * más que la tolerancia (un deslizamiento no reabre el teclado). Avisar ANTES de entregar el evento a Compose: así la
     * tecla «ocultar», que corre al entregar el soltar, cancela la reapertura de su propio dedo.
     */
    fun alPuntero(p: Puntero, soltando: Boolean = false, fueToque: Boolean = true) {
        if (!soltando) {
            ultimo = p
            if (p == Puntero.MOUSE) {
                invalidar()
                logica.alPresionarConMouse()
                sincronizar("clic con el mouse")
            }
        } else if (p == Puntero.DEDO && fueToque) {
            val mio = ++ticket
            programar(150) {
                if (mio != ticket) return@programar
                revisar()                                   // un diálogo recién abierto invalida esta reapertura
                if (mio != ticket || !ventanaActiva()) return@programar
                logica.trasSoltarDedo(entradaActiva())
                sincronizar("dedo soltado sobre un campo")
            }
        }
    }

    /** Lo llama TecladoEnPantalla. */
    fun alPresionar(t: Tecla) {
        if (t == Tecla.Ocultar) {
            invalidar()
            logica.alOcultarAMano()
            sincronizar("tecla ocultar")
            return
        }
        estado.presionar(t)?.let { escritor?.escribir(it) }   // jamás se registra lo que se escribe
    }

    /** Escucha global de teclas (AWT): las del panel van directo a la escena, así que lo que llega aquí es físico o un lector de códigos. */
    internal fun alEventoDeTecla(e: KeyEvent) {
        if (e.id != KeyEvent.KEY_PRESSED) return
        val v = ventana
        if (v != null && SwingUtilities.getWindowAncestor(e.component) !== v && e.component !== v) return
        ultimo = null                                       // un Tab a otro campo ya no cuenta como «tocado con el dedo»
        invalidar()
        logica.alTeclaFisica()
        sincronizar("tecla física")
    }

    internal fun alPerderFoco() {
        invalidar()
        logica.alOcultarAMano()
        sincronizar("la ventana perdió el foco")
    }

    private fun sincronizar(motivo: String) {
        if (_visible.value == logica.visible) return
        _visible.value = logica.visible
        if (anotadas++ < 20) anotar("${if (logica.visible) "mostrado" else "oculto"}: $motivo")
    }
}
