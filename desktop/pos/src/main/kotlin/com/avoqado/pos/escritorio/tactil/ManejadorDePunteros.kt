package com.avoqado.pos.escritorio.tactil

import java.util.concurrent.atomic.AtomicBoolean

/** Lo que el puente necesita de Windows: user32 por JNA en producción (Tarea 6); uno falso en las pruebas. */
interface Win32 {
    /** HWND del lienzo, para la bitácora. */
    val lienzo: Long

    /** GetPointerType; `null` = falló. */
    fun tipo(id: Int): Int?

    /** GetPointerInfo + ScreenToClient del lienzo; `null` = falló cualquiera de las dos. */
    fun info(id: Int): InfoDePuntero?

    /** GetWindowLongPtrW(GWLP_WNDPROC); 0 = falló. */
    fun procedimientoActual(): Long

    /** SetWindowLongPtrW(GWLP_WNDPROC): el anterior; 0 = falló. */
    fun ponerProcedimiento(proc: Long): Long

    /** GetMessageExtraInfo del mensaje que se está procesando. */
    fun extraDelMensaje(): Long

    /** CallWindowProcW. */
    fun llamar(proc: Long, mensaje: Int, wParam: Long, lParam: Long): Long

    /** DefWindowProcW. */
    fun porDefecto(mensaje: Int, wParam: Long, lParam: Long): Long
}

data class InfoDePuntero(
    val banderas: Int,
    val clienteX: Int,
    val clienteY: Int,
    val dwTime: Int,
    val pantallaX: Int,
    val pantallaY: Int,
    val destino: Long,
)

/**
 * La mitad nativa del puente: el procedimiento de ventana del lienzo. Corre en el hilo nativo de la ventana y NO toca AWT
 * ni Compose: decide, le pregunta a Windows y encola. `mensaje()` nunca lanza.
 */
class ManejadorDePunteros(
    private val w32: Win32,
    private val nuestro: Long,
    private val generacion: () -> Int,
    private val encolar: (ContactoDeWindows, Int) -> Unit,
    private val alApagarse: (String) -> Unit,
    private val anotar: (String) -> Unit,
) {
    @Volatile var modalAbierto = false
    /** Un botón del mouse apretado en la ventana (lo pone Swing): mientras tanto no se toman dedos nuevos. */
    @Volatile var mouseApretado = false

    private val encendido = AtomicBoolean(false)
    val activo: Boolean get() = encendido.get()

    @Volatile private var original = 0L
    @Volatile private var restaurarPendiente = false

    // Sólo el hilo nativo.
    private val contactos = ContactosInterceptados()
    private val reloj = RelojDePunteros()
    private var dedosAnotados = 0
    private var mouseAnotados = 0
    private var restauracionAnotada = false

    private val fallas = ContadorDeFallas { apagar("falla en Windows: ${it.javaClass.simpleName}: ${it.message}") }

    private class Lectura(val contacto: ContactoDeWindows, val completa: Boolean)

    /** El procedimiento anterior queda publicado ANTES de poner el nuestro: Windows puede llamarnos en cuanto existe. */
    fun enganchar() {
        val previo = w32.procedimientoActual()
        check(previo != 0L) { "GetWindowLongPtr no devolvió el procedimiento de la ventana" }
        check(previo != nuestro) { "el puente ya está enganchado a esta ventana" }
        original = previo
        encendido.set(true)
        val anterior = try {
            w32.ponerProcedimiento(nuestro)
        } catch (t: Throwable) {
            encendido.set(false)
            throw t
        }
        if (anterior == 0L) {
            encendido.set(false)
            error("SetWindowLongPtr falló")
        }
        original = anterior
    }

    /** Cualquier hilo, una sola vez: deja de tomar contactos nuevos; se restaura cuando no quede uno nuestro a medias. */
    fun apagar(motivo: String) {
        if (!encendido.compareAndSet(true, false)) return
        restaurarPendiente = true
        runCatching { alApagarse(motivo) }
    }

    fun mensaje(mensaje: Int, wParam: Long, lParam: Long): Long {
        runCatching { anotarMouse(mensaje, lParam) }   // diagnóstico: jamás cambia lo que pasa con el mensaje
        val consumido = try {
            decidir(mensaje, wParam)
        } catch (t: Throwable) {
            fallas.fallo(t)
            false
        }
        if (consumido) return 0L   // de una secuencia nuestra: Windows ya no lo convierte en mouse
        return try {
            pasar(mensaje, wParam, lParam)
        } catch (t: Throwable) {
            fallas.fallo(t)
            0L
        }
    }

    private fun anotarMouse(mensaje: Int, lParam: Long) {
        if (esDeMouse(mensaje) && !contactos.sinActivos && mouseAnotados < 30) {
            mouseAnotados++
            val extra = w32.extraDelMensaje()
            anotar(
                "Mouse durante un dedo: msg=0x%04X extra=0x%X %s cliente=(%d,%d)".format(
                    mensaje, extra, if (esMouseDelDedo(extra)) "GENERADO-POR-EL-DEDO" else "mouse-real",
                    (lParam and 0xFFFF).toShort().toInt(), ((lParam shr 16) and 0xFFFF).toShort().toInt(),
                ),
            )
        }
    }

    private fun decidir(mensaje: Int, wParam: Long): Boolean {
        if (mensaje == WM_NCDESTROY) {
            encendido.set(false)
            restaurar()
            return false
        }
        if (restaurarPendiente && contactos.sinActivos) restaurar()
        if (!esDeUnContacto(mensaje)) return false
        val gen = generacion()   // ANTES de leer: si Swing cancela mientras tanto, lo leído se tira
        val id = idDePuntero(wParam)
        val tomar = activo && !modalAbierto && !mouseApretado
        if (!contactos.registrar(mensaje, id, enContactoDe(wParam), esTactil = { esTactil(id) }, tomarNuevos = tomar)) return false
        // Desde aquí el mensaje YA es nuestro: pase lo que pase, se consume (una secuencia a medias rompe a Windows).
        if (!tomar) return true
        try {
            val lectura = leer(mensaje, id, enContactoDe(wParam)) ?: return true
            encolar(lectura.contacto, gen)
            if (lectura.completa) fallas.exito()
        } catch (t: Throwable) {
            runCatching {   // ya decidido: nada de aquí puede escapar y hacer que el mensaje se pase a Windows
                fallas.fallo(t)
                try {
                    encolar(ContactoDeWindows(id, 0, 0, FaseDeToque.CANCELA, reloj.tiempo(null)), gen)
                } catch (t2: Throwable) {
                    apagar("no se pudo avisar la cancelación de un dedo: ${t2.javaClass.simpleName}: ${t2.message}")
                }
            }
        }
        return true
    }

    private fun esTactil(id: Int): Boolean {
        val tipo = try {
            w32.tipo(id)
        } catch (t: Throwable) {
            fallas.fallo(t)
            return false
        }
        if (tipo == null) fallas.fallo(IllegalStateException("GetPointerType falló"))
        return tipo == PT_TOUCH
    }

    private fun leer(mensaje: Int, id: Int, enContacto: Boolean): Lectura? {
        if (mensaje == WM_POINTERENTER) return null   // no mueve a nadie
        // Estos dos no le preguntan nada a Windows: completa = false (ni éxito ni falla del contador).
        if (mensaje == WM_POINTERLEAVE) {             // sin contacto, el dedo se fue sin UP: se cancela por id
            return if (enContacto) null else Lectura(ContactoDeWindows(id, 0, 0, FaseDeToque.CANCELA, reloj.tiempo(null)), completa = false)
        }
        if (mensaje == WM_POINTERCAPTURECHANGED) {   // no trae información nueva: se cancela por id
            return Lectura(ContactoDeWindows(id, 0, 0, FaseDeToque.CANCELA, reloj.tiempo(null)), completa = false)
        }
        val info = w32.info(id)
        if (info == null) fallas.fallo(IllegalStateException("GetPointerInfo o ScreenToClient falló"))
        val fase = faseDe(mensaje, info?.banderas) ?: return null
        val tiempo = reloj.tiempo(info?.dwTime)
        if (fase == FaseDeToque.BAJA && info != null && dedosAnotados < 5) {
            dedosAnotados++
            runCatching {
                anotar(
                    "Dedo en Windows: lienzo=${w32.lienzo} destino=${info.destino} " +
                        "pantalla=(${info.pantallaX},${info.pantallaY}) cliente=(${info.clienteX},${info.clienteY})",
                )
            }
        }
        return Lectura(ContactoDeWindows(id, info?.clienteX ?: 0, info?.clienteY ?: 0, fase, tiempo), completa = info != null)
    }

    private fun restaurar() {
        val previo = original
        if (previo == 0L) {
            restaurarPendiente = false
            return
        }
        val actual = w32.procedimientoActual()
        if (actual == 0L) return noSePudoRestaurar("no se pudo consultar la ventana")
        if (actual != nuestro) {
            restaurarPendiente = false   // alguien subclasificó encima: quitarnos le cortaría la cadena; nos quedamos, pasando todo
            return
        }
        if (w32.ponerProcedimiento(previo) == 0L) return noSePudoRestaurar("SetWindowLongPtr falló")
        restaurarPendiente = false
    }

    /** Queda pendiente: se reintenta con el siguiente mensaje. Se anota una sola vez. */
    private fun noSePudoRestaurar(motivo: String) {
        restaurarPendiente = true
        if (!restauracionAnotada) {
            restauracionAnotada = true
            runCatching { anotar("No se pudo restaurar la ventana al apagar el puente táctil ($motivo); se reintenta") }
        }
    }

    private fun pasar(mensaje: Int, wParam: Long, lParam: Long): Long {
        val previo = original
        return if (previo != 0L) w32.llamar(previo, mensaje, wParam, lParam) else w32.porDefecto(mensaje, wParam, lParam)
    }
}
