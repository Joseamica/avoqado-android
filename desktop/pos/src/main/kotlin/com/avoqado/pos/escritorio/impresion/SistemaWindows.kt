package com.avoqado.pos.escritorio.impresion

import android.util.Log
import com.avoqado.pos.printing.data.model.PrinterException
import com.sun.jna.LastErrorException
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.Winspool
import com.sun.jna.platform.win32.WinspoolUtil
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/** winspool.drv: lo que jna-platform no trae para mandar un trabajo RAW. */
@Suppress("FunctionName")
private interface WinspoolCrudo : StdCallLibrary {
    @Structure.FieldOrder("pDocName", "pOutputFile", "pDatatype")
    class DOC_INFO_1 : Structure() {
        @JvmField var pDocName: WString? = null
        @JvmField var pOutputFile: WString? = null
        @JvmField var pDatatype: WString? = null
    }
    fun StartDocPrinterW(hPrinter: WinNT.HANDLE, level: Int, docInfo: DOC_INFO_1): Int
    fun StartPagePrinter(hPrinter: WinNT.HANDLE): Boolean
    fun WritePrinter(hPrinter: WinNT.HANDLE, buf: ByteArray, count: Int, written: IntByReference): Boolean
    fun EndPagePrinter(hPrinter: WinNT.HANDLE): Boolean
    fun EndDocPrinter(hPrinter: WinNT.HANDLE): Boolean
    fun AbortPrinter(hPrinter: WinNT.HANDLE): Boolean

    companion object { val INSTANCE: WinspoolCrudo by lazy { Native.load("winspool.drv", WinspoolCrudo::class.java) } }
}

/** kernel32: GetCommState/SetCommState con el DCB de 28 bytes de DcbDeWindows.kt, no con el de jna-platform. */
@Suppress("FunctionName")
private interface ComCrudo : StdCallLibrary {
    fun GetCommState(hFile: WinNT.HANDLE, lpDCB: DCB): Boolean
    fun SetCommState(hFile: WinNT.HANDLE, lpDCB: DCB): Boolean

    companion object { val INSTANCE: ComCrudo by lazy { Native.load("kernel32", ComCrudo::class.java) } }
}

/**
 * Windows real. Sólo traduce llamadas a winspool/kernel32: el orden, los textos y qué hacer cuando algo falla viven en
 * ProtocoloDeWindows.kt, probados con un Windows falso. Las dos lecturas de colas entran por parámetro para probar que
 * imprimir pregunta por ESA cola y no enumera todas.
 */
class SistemaWindows internal constructor(
    private val leerTodasLasColas: () -> List<ColaInfo>,
    private val leerUnaCola: (String) -> ColaInfo?,
) : SistemaDeImpresion {
    constructor() : this(::colasLocalesDeWindows, ::unaColaDeWindows)

    /**
     * Para «Buscar impresoras»: EnumPrinters nivel 2 de las colas LOCALES (lista fresca cada vez: la de javax.print se
     * queda vieja). Las conexiones a impresoras compartidas de OTRO equipo no: Windows puede esperar el timeout RPC de una
     * que no responde y congelar la pantalla. Una térmica de mostrador siempre es local (USB, COM o puerto TCP/IP).
     */
    override fun colas(): List<ColaInfo> = runCatching { leerTodasLasColas() }
        .onFailure { Log.w(TAG, "No se pudo leer la lista de impresoras de Windows: ${it.message}") }
        .getOrDefault(emptyList())

    /**
     * OpenPrinter + GetPrinter de ESA cola (open y write, en IO), nunca EnumPrinters de todas. null SÓLO si Windows dice
     * que el nombre no existe (ERROR_INVALID_PRINTER_NAME, 1801). Cualquier otro fallo (permisos, spooler) NO es «la
     * borraron»: decirlo así haría borrar una impresora buena. Un Error de JNA no se traga aquí: UsbPrinterManager lo
     * vuelve PrinterException con texto llano.
     */
    override fun cola(nombre: String): ColaInfo? = try {
        leerUnaCola(nombre)
    } catch (e: Exception) {
        if ((e as? LastErrorException)?.errorCode == ERROR_NOMBRE_DE_IMPRESORA_INVALIDO) {
            Log.d(TAG, "Windows no conoce la impresora «$nombre» (error 1801)")
            null
        } else {
            Log.w(TAG, "No se pudo consultar la impresora «$nombre» en Windows", e)
            throw PrinterException.ConnectionFailed(textoNoSePudoConsultar(nombre))
        }
    }

    override fun puertos(): List<PuertoInfo> = runCatching {
        Advapi32Util.registryGetValues(WinReg.HKEY_LOCAL_MACHINE, "HARDWARE\\DEVICEMAP\\SERIALCOMM")
            .mapNotNull { (dispositivo, valor) -> (valor as? String)?.let { PuertoInfo(it, dispositivo) } }
    }.getOrDefault(emptyList())   // sin puertos COM la llave no existe: no es un error

    override fun imprimirEnCola(nombre: String, documento: String, bytes: ByteArray) =
        imprimirTrabajoCrudo(ColaDeWindows, nombre, documento, bytes)

    override fun abrirPuerto(puerto: String, baudios: Int?): CanalAbierto = abrirCanalCom(PuertoDeWindows, puerto, baudios)

    private companion object { const val TAG = "🖨️Windows" }
}

/**
 * Lo que Windows dijo de una cola. Sin nombre es una consulta fallida (WinspoolUtil.getPrinterInfo2 puede devolver un
 * PRINTER_INFO_2 vacío): nunca se fabrica una cola «sana» (estado 0) con el nombre que se pidió.
 */
internal fun colaInfoDeWindows(nombre: String?, puerto: String?, driver: String?, estado: Int, atributos: Int): ColaInfo {
    if (nombre.isNullOrBlank()) throw IllegalStateException("Windows devolvió una impresora sin nombre")
    return ColaInfo(nombre, puerto.orEmpty(), driver.orEmpty(), estado, atributos)
}

private fun aColaInfo(p: Winspool.PRINTER_INFO_2) = colaInfoDeWindows(p.pPrinterName, p.pPortName, p.pDriverName, p.Status, p.Attributes)

/** PRINTER_ENUM_LOCAL: WinspoolUtil.getPrinterInfo2() (getAllPrinterInfo2 sumaría las conexiones a otros equipos). */
private fun colasLocalesDeWindows(): List<ColaInfo> =
    WinspoolUtil.getPrinterInfo2().mapNotNull { runCatching { aColaInfo(it) }.getOrNull() }

/** Lanza Win32Exception (un LastErrorException) si Windows no la abre: 1801 = la borraron o la renombraron. */
private fun unaColaDeWindows(nombre: String): ColaInfo = aColaInfo(WinspoolUtil.getPrinterInfo2(nombre))

/**
 * winspool, llamada por llamada. El código de error se lee con Native.getLastError() justo después de la llamada que
 * falló: JNA guarda el GetLastError de cada llamada por hilo (Kernel32.GetLastError por JNA devuelve ese mismo valor;
 * se usa Native.getLastError por claridad).
 */
private object ColaDeWindows : LlamadasDeCola<WinNT.HANDLE> {
    override fun abrir(nombre: String): WinNT.HANDLE? {
        val h = WinNT.HANDLEByReference()
        return if (Winspool.INSTANCE.OpenPrinter(nombre, h, null)) h.value else null
    }
    override fun iniciarDocumento(h: WinNT.HANDLE, documento: String): Boolean {
        val info = WinspoolCrudo.DOC_INFO_1().apply { pDocName = WString(documento); pDatatype = WString("RAW") }
        return WinspoolCrudo.INSTANCE.StartDocPrinterW(h, 1, info) != 0
    }
    override fun iniciarPagina(h: WinNT.HANDLE) = WinspoolCrudo.INSTANCE.StartPagePrinter(h)
    override fun escribir(h: WinNT.HANDLE, bytes: ByteArray): Int {
        val escritos = IntByReference()
        return if (WinspoolCrudo.INSTANCE.WritePrinter(h, bytes, bytes.size, escritos)) escritos.value else -1
    }
    override fun terminarPagina(h: WinNT.HANDLE) = WinspoolCrudo.INSTANCE.EndPagePrinter(h)
    override fun terminarDocumento(h: WinNT.HANDLE) = WinspoolCrudo.INSTANCE.EndDocPrinter(h)
    override fun abortar(h: WinNT.HANDLE) = WinspoolCrudo.INSTANCE.AbortPrinter(h)
    override fun cerrar(h: WinNT.HANDLE) { Winspool.INSTANCE.ClosePrinter(h) }
    override fun ultimoError(): Int = Native.getLastError()
}

/** kernel32, llamada por llamada. */
private object PuertoDeWindows : LlamadasDePuerto<WinNT.HANDLE> {
    private val k: Kernel32 get() = Kernel32.INSTANCE

    override fun abrir(puerto: String): WinNT.HANDLE? =
        k.CreateFile("\\\\.\\$puerto", WinNT.GENERIC_READ or WinNT.GENERIC_WRITE, 0, null, WinNT.OPEN_EXISTING, 0, null)
            ?.takeUnless { it == WinBase.INVALID_HANDLE_VALUE }

    /** GetCommState → prepararDcb (fBinary, sin abortar en error, velocidad sólo si la dirección la trae) → SetCommState. */
    override fun configurar(h: WinNT.HANDLE, baudios: Int?): Boolean {
        val dcb = DCB()
        if (!ComCrudo.INSTANCE.GetCommState(h, dcb)) return false
        prepararDcb(dcb, baudios)
        Log.d("🖨️Windows", "COM: ${dcb.BaudRate} baudios, ${dcb.ByteSize} bits, paridad ${dcb.Parity}, parada ${dcb.StopBits}, bits=0x${dcb.bits.toString(16)}")
        return ComCrudo.INSTANCE.SetCommState(h, dcb)
    }

    /**
     * Tope de CADA WriteFile = 5 s + 2 ms por byte de esa llamada (un trozo de 4 096 bytes: hasta ~13 s). No acota
     * CreateFile (un Bluetooth apagado puede tardar más) ni el ticket entero, que son varias llamadas.
     */
    override fun fijarTiempos(h: WinNT.HANDLE): Boolean = k.SetCommTimeouts(
        h,
        WinBase.COMMTIMEOUTS().apply {
            WriteTotalTimeoutConstant = WinDef.DWORD(5_000)
            WriteTotalTimeoutMultiplier = WinDef.DWORD(2)
        },
    )

    override fun escribir(h: WinNT.HANDLE, trozo: ByteArray): Int {
        val escritos = IntByReference()
        return if (k.WriteFile(h, trozo, trozo.size, escritos, null)) escritos.value else -1
    }

    override fun cerrar(h: WinNT.HANDLE) { k.CloseHandle(h) }
    override fun ultimoError(): Int = Native.getLastError()
}
