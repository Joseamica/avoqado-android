package com.avoqado.pos.escritorio.impresion

import android.util.Log
import com.avoqado.pos.printing.data.model.PrinterException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * El ORDEN de las llamadas a Windows y lo que se le dice al cajero cuando una falla, separados de JNA para probarlos sin
 * Windows (ProtocoloDeWindowsTest). SistemaWindows sólo traduce cada llamada a winspool/kernel32; las decisiones viven aquí.
 */

private const val TAG = "🖨️Windows"

internal const val ERROR_ARCHIVO_NO_ENCONTRADO = 2           // ERROR_FILE_NOT_FOUND: el COM ya no existe
internal const val ERROR_ACCESO_DENEGADO = 5                 // ERROR_ACCESS_DENIED: otro programa tiene el COM, o permisos
internal const val ERROR_NOMBRE_DE_IMPRESORA_INVALIDO = 1801 // ERROR_INVALID_PRINTER_NAME
internal const val ERROR_TIPO_DE_DATOS_INVALIDO = 1804       // ERROR_INVALID_DATATYPE: el driver no acepta RAW
internal const val TROZO_COM = 4_096

/** Cuándo pasó la falla: decide si PrinterService la ve como de conexión o de impresión. */
internal enum class Momento { CONECTAR, IMPRIMIR }

/**
 * 🔴 Nada sale de aquí como `Error`. PrinterService (de Android, sin cambios) sólo atrapa `Exception`, y un
 * `UnsatisfiedLinkError` o `NoClassDefFoundError` de JNA a media venta abortaría el cobro entero: ni cajón ni comanda.
 * Una PrinterException pasa tal cual; cualquier otra cosa se vuelve una con texto llano (la causa, a la bitácora).
 */
internal inline fun <T> sinErroresSueltos(momento: Momento, bloque: () -> T): T =
    try {
        bloque()
    } catch (e: PrinterException) {
        throw e
    } catch (t: Throwable) {
        throw comoFallaDeImpresora(t, momento)
    }

internal fun comoFallaDeImpresora(causa: Throwable, momento: Momento): PrinterException {
    Log.e(TAG, "Falla inesperada al hablar con la impresora (${momento.name})", causa)
    return if (momento == Momento.CONECTAR) {
        PrinterException.ConnectionFailed(TEXTO_NO_SE_PUDO_HABLAR)
    } else {
        PrinterException.PrintFailed(TEXTO_NO_SE_PUDO_HABLAR)
    }
}

// ================================================================== colas (winspool.drv)

/** Las llamadas de winspool.drv de un trabajo RAW. `H` es el handle de la impresora (WinNT.HANDLE en Windows). */
internal interface LlamadasDeCola<H : Any> {
    /** OpenPrinter; null si Windows no la abrió. */
    fun abrir(nombre: String): H?
    /** StartDocPrinter con datatype RAW; false si lo rechazó. */
    fun iniciarDocumento(h: H, documento: String): Boolean
    /** StartPagePrinter. */
    fun iniciarPagina(h: H): Boolean
    /** WritePrinter: cuántos bytes aceptó (puede ser una parte), o -1 si falló. */
    fun escribir(h: H, bytes: ByteArray): Int
    /** EndPagePrinter. */
    fun terminarPagina(h: H): Boolean
    /** EndDocPrinter: el trabajo queda completo en la cola. */
    fun terminarDocumento(h: H): Boolean
    /** AbortPrinter: intenta borrar el trabajo; false si Windows no pudo. */
    fun abortar(h: H): Boolean
    /** ClosePrinter. */
    fun cerrar(h: H)
    /** El código de error de la llamada que acaba de fallar (Native.getLastError en Windows). */
    fun ultimoError(): Int
}

/**
 * Un trabajo RAW completo: OpenPrinter → StartDoc → StartPage → WritePrinter (las veces que haga falta: puede aceptar
 * sólo una parte, y se sigue desde el byte pendiente en el MISMO trabajo) → EndPage → EndDoc, y ClosePrinter siempre.
 *
 * Si algo falla después de StartDoc —también EndDoc—, se INTENTA cancelar con AbortPrinter. Eso no promete que no haya
 * salido papel: Windows puede ir imprimiendo mientras recibe, y con impresión directa AbortPrinter no hace nada. Si
 * Windows dice que no pudo cancelarlo, el cajero lo sabe: tiene que revisar la cola antes de reimprimir.
 */
internal fun <H : Any> imprimirTrabajoCrudo(w: LlamadasDeCola<H>, nombre: String, documento: String, bytes: ByteArray) {
    sinErroresSueltos(Momento.IMPRIMIR) {
        val h = w.abrir(nombre) ?: run {
            val error = w.ultimoError()
            Log.w(TAG, "OpenPrinter(«$nombre») falló: error $error")
            throw PrinterException.ConnectionFailed(
                if (error == ERROR_NOMBRE_DE_IMPRESORA_INVALIDO) textoColaInexistente(nombre) else textoNoSeAbrioLaCola(nombre),
            )
        }
        try {
            if (!w.iniciarDocumento(h, documento)) {
                val error = w.ultimoError()
                Log.w(TAG, "StartDocPrinter(«$nombre», RAW) falló: error $error")
                throw PrinterException.PrintFailed(
                    when (error) {
                        ERROR_TIPO_DE_DATOS_INVALIDO -> textoNoAceptaTicketsDirectos(nombre)
                        ERROR_ACCESO_DENEGADO -> textoSinPermisoParaImprimir(nombre)
                        else -> textoTicketIncompleto(nombre)
                    },
                )
            }
            try {
                if (!w.iniciarPagina(h)) fallaDelTrabajo(w, nombre, "StartPagePrinter")
                escribirTodo(w, h, nombre, bytes)
                if (!w.terminarPagina(h)) fallaDelTrabajo(w, nombre, "EndPagePrinter")
                if (!w.terminarDocumento(h)) fallaDelTrabajo(w, nombre, "EndDocPrinter")
            } catch (t: Throwable) {
                // El trabajo sólo queda «completo» si EndDocPrinter respondió bien: cualquier otra salida intenta cancelarlo.
                if (!cancelarTrabajo(w, h, nombre)) {
                    Log.w(TAG, "El trabajo de «$nombre» falló y Windows no lo canceló", t)
                    throw PrinterException.PrintFailed(TEXTO_NO_SE_PUDO_CANCELAR)
                }
                throw t
            }
        } finally {
            runCatching { w.cerrar(h) }.onFailure { Log.w(TAG, "ClosePrinter(«$nombre») falló: ${it.message}") }
        }
    }
}

/** WritePrinter hasta mandar todo: un resultado positivo parcial sigue desde el byte pendiente; 0 o fallo es falla. */
private fun <H : Any> escribirTodo(w: LlamadasDeCola<H>, h: H, nombre: String, bytes: ByteArray) {
    var desde = 0
    while (desde < bytes.size) {
        val pendiente = if (desde == 0) bytes else bytes.copyOfRange(desde, bytes.size)
        val escritos = w.escribir(h, pendiente)
        if (escritos <= 0) fallaDelTrabajo(w, nombre, "WritePrinter (iban $desde de ${bytes.size} bytes, devolvió $escritos)")
        desde += minOf(escritos, pendiente.size)
    }
}

/** AbortPrinter; true sólo si Windows dijo que lo canceló. Su código de error se lee en el acto. */
private fun <H : Any> cancelarTrabajo(w: LlamadasDeCola<H>, h: H, nombre: String): Boolean = try {
    val cancelado = w.abortar(h)
    if (!cancelado) Log.w(TAG, "AbortPrinter(«$nombre») no canceló el trabajo: error ${w.ultimoError()}")
    cancelado
} catch (t: Throwable) {
    Log.w(TAG, "AbortPrinter(«$nombre») falló", t)
    false
}

private fun fallaDelTrabajo(w: LlamadasDeCola<*>, nombre: String, llamada: String): Nothing {
    Log.w(TAG, "$llamada de «$nombre» falló: error ${w.ultimoError()}")
    throw PrinterException.PrintFailed(textoTicketIncompleto(nombre))
}

// ================================================================== puertos COM (kernel32)

/** Las llamadas de kernel32 de un puerto COM. `H` es el handle del puerto (WinNT.HANDLE en Windows). */
internal interface LlamadasDePuerto<H : Any> {
    /** CreateFile("\\.\COM3"); null si Windows no lo abrió. */
    fun abrir(puerto: String): H?
    /**
     * GetCommState → prepararDcb → SetCommState: se respeta lo que Windows tiene configurado en el puerto (control de
     * flujo, DTR/RTS, 8N1 o lo que sea); `baudios` null = también su velocidad.
     */
    fun configurar(h: H, baudios: Int?): Boolean
    /** SetCommTimeouts: tope de cada WriteFile = 5 s + 2 ms por byte de esa llamada (no del ticket entero). */
    fun fijarTiempos(h: H): Boolean
    /** WriteFile: cuántos bytes aceptó, o -1 si falló. */
    fun escribir(h: H, trozo: ByteArray): Int
    /** CloseHandle. */
    fun cerrar(h: H)
    /** El código de error de la llamada que acaba de fallar (Native.getLastError en Windows). */
    fun ultimoError(): Int
}

/** Abre y deja listo un puerto COM. Si algo falla DESPUÉS de CreateFile, el handle se cierra (si no, el COM queda tomado). */
internal fun <H : Any> abrirCanalCom(p: LlamadasDePuerto<H>, puerto: String, baudios: Int?): CanalAbierto =
    sinErroresSueltos(Momento.CONECTAR) {
        val h = p.abrir(puerto) ?: run {
            val error = p.ultimoError()
            Log.w(TAG, "CreateFile($puerto) falló: error $error")
            throw PrinterException.ConnectionFailed(
                when (error) {
                    ERROR_ARCHIVO_NO_ENCONTRADO -> "El puerto $puerto ya no existe. Revisa el cable y vuelve a buscar la impresora."
                    ERROR_ACCESO_DENEGADO -> "El puerto $puerto está ocupado por otro programa o Windows no dejó abrirlo."
                    else -> "No se pudo abrir el puerto $puerto. Revisa que la impresora esté encendida y conectada. " +
                        "Si es Bluetooth, elige el puerto COM SALIENTE de esa impresora (Windows › Bluetooth › Más opciones › " +
                        "Puertos COM) y confírmalo con la página de prueba."
                },
            )
        }
        try {
            if (!p.configurar(h, baudios)) fallaAlPreparar(p, puerto, "SetCommState(${baudios ?: "velocidad de Windows"})")
            if (!p.fijarTiempos(h)) fallaAlPreparar(p, puerto, "SetCommTimeouts")
        } catch (t: Throwable) {
            runCatching { p.cerrar(h) }
            throw t
        }
        CanalCom(p, h, puerto)
    }

private fun fallaAlPreparar(p: LlamadasDePuerto<*>, puerto: String, llamada: String): Nothing {
    Log.w(TAG, "$llamada falló: error ${p.ultimoError()}")
    throw PrinterException.ConnectionFailed(
        "No se pudo preparar el puerto $puerto para imprimir. Desconecta y vuelve a conectar la impresora, y vuelve a intentar.",
    )
}

private class CanalCom<H : Any>(private val p: LlamadasDePuerto<H>, private val h: H, private val puerto: String) : CanalAbierto {
    private val cerrado = AtomicBoolean(false)

    override fun escribir(bytes: ByteArray) {
        sinErroresSueltos(Momento.IMPRIMIR) {
            if (cerrado.get()) throw PrinterException.NotConnected()
            var desde = 0
            while (desde < bytes.size) {
                val trozo = bytes.copyOfRange(desde, minOf(bytes.size, desde + TROZO_COM))
                val escritos = p.escribir(h, trozo)
                if (escritos <= 0) {
                    Log.w(TAG, "WriteFile($puerto) falló tras $desde de ${bytes.size} bytes: error ${p.ultimoError()}")
                    throw PrinterException.PrintFailed("La impresora en $puerto dejó de responder. Revisa el cable o que esté encendida, y vuelve a intentar.")
                }
                desde += escritos
            }
        }
    }

    /** Una sola vez: un segundo CloseHandle sobre un handle ya reusado por Windows cerraría OTRA cosa. */
    override fun close() {
        if (cerrado.compareAndSet(false, true)) p.cerrar(h)
    }
}
