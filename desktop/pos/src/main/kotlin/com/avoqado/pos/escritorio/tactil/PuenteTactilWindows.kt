package com.avoqado.pos.escritorio.tactil

import android.util.Log
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.avoqado.pos.escritorio.teclado.DetectorDeToque
import com.avoqado.pos.escritorio.teclado.Puntero
import com.avoqado.pos.escritorio.teclado.TecladoDeLaVentana
import java.awt.AWTEvent
import java.awt.Canvas
import java.awt.Container
import java.awt.Dialog
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

private const val GWLP_WNDPROC = -4

/** POINTER_INFO de Win32 (winuser.h), en el orden exacto. */
@Structure.FieldOrder(
    "pointerType", "pointerId", "frameId", "pointerFlags", "sourceDevice", "hwndTarget", "ptPixelLocation",
    "ptHimetricLocation", "ptPixelLocationRaw", "ptHimetricLocationRaw", "dwTime", "historyCount", "InputData",
    "dwKeyStates", "PerformanceCount", "ButtonChangeType",
)
class PointerInfo : Structure() {
    @JvmField var pointerType = 0
    @JvmField var pointerId = 0
    @JvmField var frameId = 0
    @JvmField var pointerFlags = 0
    @JvmField var sourceDevice: Pointer? = null
    @JvmField var hwndTarget: Pointer? = null
    @JvmField var ptPixelLocation = POINT()
    @JvmField var ptHimetricLocation = POINT()
    @JvmField var ptPixelLocationRaw = POINT()
    @JvmField var ptHimetricLocationRaw = POINT()
    @JvmField var dwTime = 0
    @JvmField var historyCount = 0
    @JvmField var InputData = 0
    @JvmField var dwKeyStates = 0
    @JvmField var PerformanceCount = 0L
    @JvmField var ButtonChangeType = 0
}

/** Las funciones de user32 que usa el puente, con su nombre Win32 exacto (64 bits). */
@Suppress("FunctionName")
private interface User32Tactil : StdCallLibrary {
    fun GetPointerType(pointerId: Int, pointerType: IntByReference): Boolean
    fun GetPointerInfo(pointerId: Int, pointerInfo: PointerInfo): Boolean
    fun ScreenToClient(hWnd: HWND, lpPoint: POINT): Boolean
    fun GetWindowLongPtrW(hWnd: HWND, nIndex: Int): Pointer?
    fun SetWindowLongPtrW(hWnd: HWND, nIndex: Int, dwNewLong: Pointer?): Pointer?
    fun GetMessageExtraInfo(): LPARAM
    fun CallWindowProcW(lpPrevWndFunc: Pointer, hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    fun DefWindowProcW(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT

    companion object {
        val INSTANCE: User32Tactil by lazy { Native.load("user32", User32Tactil::class.java) }
    }
}

private class Win32Jna(private val hwnd: HWND) : Win32 {
    private val u = User32Tactil.INSTANCE
    override val lienzo: Long = Pointer.nativeValue(hwnd.pointer)

    override fun tipo(id: Int): Int? = IntByReference().let { if (u.GetPointerType(id, it)) it.value else null }

    override fun info(id: Int): InfoDePuntero? {
        val i = PointerInfo()
        if (!u.GetPointerInfo(id, i)) return null
        val p = POINT(i.ptPixelLocation.x, i.ptPixelLocation.y)
        if (!u.ScreenToClient(hwnd, p)) return null
        return InfoDePuntero(i.pointerFlags, p.x, p.y, i.dwTime, i.ptPixelLocation.x, i.ptPixelLocation.y, Pointer.nativeValue(i.hwndTarget))
    }

    override fun procedimientoActual(): Long = Pointer.nativeValue(u.GetWindowLongPtrW(hwnd, GWLP_WNDPROC))

    override fun ponerProcedimiento(proc: Long): Long = Pointer.nativeValue(u.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, Pointer(proc)))

    override fun extraDelMensaje(): Long = u.GetMessageExtraInfo().toLong()

    override fun llamar(proc: Long, mensaje: Int, wParam: Long, lParam: Long): Long =
        u.CallWindowProcW(Pointer(proc), hwnd, mensaje, WPARAM(wParam), LPARAM(lParam)).toLong()

    override fun porDefecto(mensaje: Int, wParam: Long, lParam: Long): Long =
        u.DefWindowProcW(hwnd, mensaje, WPARAM(wParam), LPARAM(lParam)).toLong()
}

object PuenteTactilWindows {
    /** Referencias fuertes para siempre: si la JVM recolectara el callback, Windows llamaría a memoria liberada. */
    private val vivos = mutableListOf<Any>()

    fun instalar(ventana: ComposeWindow, alApagarse: (String) -> Unit = {}): String {
        motivoParaNoInstalar(System.getProperty("os.name").orEmpty(), System.getProperty("avoqado.toque"))
            ?.let { return "omitido: $it" }
        val escena = EscenaDeVentana.de(ventana).getOrElse { return "omitido: ${it.message}" }
        val lienzo = primerLienzo(escena.lienzo) ?: return "omitido: sin lienzo nativo (¿dibujo por software?)"
        return runCatching {
            val puente = PuenteDeVentana(ventana, escena, lienzo, alApagarse)
            vivos += puente   // ANTES de engancharse
            puente.encender()
            "activo"
        }.getOrElse { "omitido: ${it.javaClass.simpleName}: ${it.message}" }
    }

    private fun primerLienzo(c: Container): Canvas? =
        c.components.firstNotNullOfOrNull { hijo -> hijo as? Canvas ?: (hijo as? Container)?.let(::primerLienzo) }
}

/** Une las tres piezas para UNA ventana. El callback sólo convierte tipos; la lógica vive en el manejador y la entrega. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)   // ComposeScene es API interna; exceptionHandler, experimental
private class PuenteDeVentana(
    private val ventana: ComposeWindow,
    private val escena: EscenaDeVentana,
    private val lienzo: Canvas,
    private val alApagarseExterno: (String) -> Unit,
) : WinUser.WindowProc {
    private val w32 = Win32Jna(HWND(Native.getComponentPointer(lienzo)))
    private lateinit var manejador: ManejadorDePunteros
    private var entregasAnotadas = 0
    private var mouseCercaAnotados = 0              // hilo de Swing
    private var ultimoToque = 0L                    // System.nanoTime() de la última entrega a Compose; hilo de Swing

    private val entrega = EntregaDeToques(
        traductor = TraductorDeToques { x, y ->
            aEscena(x, y, escala(), origenLienzo(), escena.escena.density.density, escena.esquina)
        },
        enviar = { e ->
            ultimoToque = System.nanoTime()
            if ((e.tipo == TipoDeEvento.PRESIONA || e.tipo == TipoDeEvento.SUELTA) && entregasAnotadas < 30) {
                entregasAnotadas++
                Log.i("Toque", "A Compose: ${e.tipo} escala=${escala()} escena=${e.punteros.map { it.id to it.posicion }}")
            }
            avisarAlTeclado(e)   // ANTES de entregar: la tecla «ocultar» corre al entregar el soltar y debe cancelar su reapertura
            escena.escena.enviarToque(e)
        },
        reiniciar = { dedos -> escena.escena.reiniciarGestos(dedos) },
        puedeEntregar = { manejador.activo && !manejador.mouseApretado && !modalAhora() },
        alBajarUnDedo = {
            if (!ventana.isFocused) {
                ventana.toFront()
                ventana.requestFocus()
            }
        },
        programar = { retrasoMs, accion ->
            if (retrasoMs <= 0) SwingUtilities.invokeLater(accion)
            else Timer(retrasoMs) { accion() }.apply { isRepeats = false }.start()
        },
        fallas = ContadorDeFallas { manejador.apagar("falla al entregar a Compose: ${it.javaClass.simpleName}: ${it.message}") },
        alFallarLimpieza = { manejador.apagar("no se pudo reiniciar el gesto: ${it.javaClass.simpleName}: ${it.message}") },
        // Lo mismo que hace ComposeSceneMediator con el mouse; sin manejador, sale del drenado como saldría con el mouse.
        anotar = { Log.i("Toque", it) },
        alErrorDeLaApp = { t -> ventana.exceptionHandler?.onException(t) ?: throw t },
    )

    private var detector = DetectorDeToque(8f)

    /** Teclado híbrido: sólo avisos, sin tocar la entrega. La tolerancia de toque son 8 dp, como el umbral de arrastre. */
    private fun avisarAlTeclado(e: EventoDeToque) {
        val p = e.punteros.firstOrNull() ?: return
        when (e.tipo) {
            TipoDeEvento.PRESIONA -> {
                detector = DetectorDeToque(8f * escena.escena.density.density)
                detector.alBajar(p.posicion.x, p.posicion.y, e.punteros.size)
                TecladoDeLaVentana.alPuntero(Puntero.DEDO)
            }
            TipoDeEvento.MUEVE -> detector.alMover(p.posicion.x, p.posicion.y, e.punteros.size)
            TipoDeEvento.SUELTA -> {
                detector.alMover(p.posicion.x, p.posicion.y, e.punteros.size)
                TecladoDeLaVentana.alPuntero(Puntero.DEDO, soltando = true, fueToque = detector.fueToque())
            }
        }
    }

    /** Hilo de Swing. */
    fun encender() {
        manejador = ManejadorDePunteros(
            w32 = w32,
            nuestro = Pointer.nativeValue(CallbackReference.getFunctionPointer(this)),
            generacion = entrega::generacionActual,
            encolar = entrega::encolar,
            alApagarse = { motivo ->
                Log.w("Toque", "Puente táctil apagado ($motivo): el dedo vuelve a funcionar como mouse desde el siguiente toque")
                SwingUtilities.invokeLater {
                    entrega.cancelar("puente apagado: $motivo")
                    runCatching { alApagarseExterno(motivo) }
                }
            },
            anotar = { Log.i("Toque", it) },
        )
        manejador.modalAbierto = hayModalAbierto()
        Toolkit.getDefaultToolkit().addAWTEventListener({ actualizarModal() }, AWTEvent.WINDOW_EVENT_MASK)
        Toolkit.getDefaultToolkit().addAWTEventListener(
            { (it as? MouseEvent)?.let(::alMouse) },
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK,
        )
        ventana.addWindowListener(object : WindowAdapter() {
            override fun windowDeactivated(e: WindowEvent) = entrega.cancelar("la ventana perdió el foco")
        })
        manejador.enganchar()
    }

    /** Hilo nativo. Nunca lanza hacia JNA. */
    override fun callback(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT =
        try {
            LRESULT(manejador.mensaje(uMsg, wParam.toLong(), lParam.toLong()))
        } catch (t: Throwable) {
            LRESULT(0)
        }

    private fun actualizarModal() {
        val abierto = hayModalAbierto()
        manejador.modalAbierto = abierto
        if (abierto) entrega.cancelar("se abrió un diálogo modal")
    }

    /** Hilo de Swing: el modal se mira DIRECTO (un JOptionPane corre lo pendiente antes de avisar que se abrió). */
    private fun modalAhora(): Boolean = hayModalAbierto().also { if (it) manejador.modalAbierto = true }

    /** Hilo de Swing, ANTES de que Compose vea el evento del mouse. ENTERED/EXITED no cuentan (no son del mouse moviéndose). */
    private fun alMouse(e: MouseEvent) {
        if (mouseCercaAnotados < 60 && (entrega.hayDedos || entrega.retenidos > 0 || System.nanoTime() - ultimoToque < 3_000_000_000L)) {
            mouseCercaAnotados++   // diagnóstico: qué mouse ve AWT alrededor de un toque (ENTERED/EXITED incluidos)
            runCatching { Log.i("Toque", "Mouse en AWT cerca de un dedo: ${e.paramString()} dedos=${entrega.hayDedos} en cola=${entrega.retenidos}") }
        }
        if (e.id == MouseEvent.MOUSE_ENTERED || e.id == MouseEvent.MOUSE_EXITED) return
        if (e.id == MouseEvent.MOUSE_PRESSED) TecladoDeLaVentana.alPuntero(Puntero.MOUSE)
        if (SwingUtilities.getWindowAncestor(e.component) !== ventana && e.component !== ventana) return
        val botones = MouseEvent.BUTTON1_DOWN_MASK or MouseEvent.BUTTON2_DOWN_MASK or MouseEvent.BUTTON3_DOWN_MASK
        manejador.mouseApretado = e.modifiersEx and botones != 0
        // Todo mouse invalida lo que el hilo nativo esté leyendo; si además hay un dedo en curso o en cola, se cancela ANTES de que Compose lo vea (peor caso: un deslizamiento cortado, nunca un clic)
        entrega.invalidarPendientes()
        if (entrega.hayDedos || entrega.retenidos > 0) entrega.cancelar("evento de mouse: ${e.paramString()}")
    }

    private fun hayModalAbierto(): Boolean = Window.getWindows().any { it is Dialog && it.isModal && it.isVisible }

    private fun escala(): Double = lienzo.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

    private fun origenLienzo(): Offset =
        SwingUtilities.convertPoint(lienzo, Point(0, 0), escena.contenedor).let { Offset(it.x.toFloat(), it.y.toFloat()) }
}
