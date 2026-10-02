package com.avoqado.pos.escritorio.impresion

import com.avoqado.pos.printing.data.model.DiscoveredPrinter
import com.avoqado.pos.printing.data.model.PrinterConnectionType

/**
 * Las impresoras «USB» del POS de Windows. La app de Android guarda una USB como `usb:VID:PID`; en Windows una
 * impresora de tickets es una COLA de impresión (con su driver) o un PUERTO COM (USB-serie o Bluetooth emparejado),
 * así que la dirección dice cuál: `usb:cola:<nombre>` o `usb:com:COM3[:baudios]`.
 */
sealed interface DestinoDeWindows {
    data class Cola(val nombre: String) : DestinoDeWindows
    /** `baudios` null = la velocidad que Windows tiene configurada en ese puerto (lo que guarda «Buscar»). */
    data class Com(val puerto: String, val baudios: Int? = null) : DestinoDeWindows
}

private const val PREFIJO_COLA = "usb:cola:"
private const val PREFIJO_COM = "usb:com:"
private val PUERTO_COM = Regex("""COM\d{1,3}""")

fun leerDireccion(address: String): DestinoDeWindows? = when {
    address.startsWith(PREFIJO_COLA) -> address.removePrefix(PREFIJO_COLA).takeIf { it.isNotBlank() }?.let { DestinoDeWindows.Cola(it) }
    address.startsWith(PREFIJO_COM) -> {
        val partes = address.removePrefix(PREFIJO_COM).split(':')
        val puerto = partes[0].takeIf { PUERTO_COM.matches(it) }
        when {
            puerto == null -> null
            partes.size == 1 -> DestinoDeWindows.Com(puerto)                                   // sin velocidad: la de Windows
            partes.size == 2 -> partes[1].toIntOrNull()?.takeIf { it > 0 }?.let { DestinoDeWindows.Com(puerto, it) }
            else -> null
        }
    }
    else -> null
}

fun DestinoDeWindows.comoDireccion(): String = when (this) {
    is DestinoDeWindows.Cola -> PREFIJO_COLA + nombre
    is DestinoDeWindows.Com -> PREFIJO_COM + puerto + (baudios?.let { ":$it" } ?: "")
}

data class ColaInfo(val nombre: String, val puerto: String, val driver: String, val estado: Int, val atributos: Int)
data class PuertoInfo(val puerto: String, val dispositivo: String)

private val PUERTOS_VIRTUALES = setOf("PORTPROMPT:", "NUL:", "SHRFAX:", "FILE:", "XPSPORT:")
private val MARCAS_VIRTUALES = listOf("ONENOTE", "PDF", "XPS", "FAX")
/** El driver de las colas que instalan las apps de la tienda («OneNote for Windows 10»): no dice ONENOTE en ningún lado. */
private const val DRIVER_DE_APPS = "MICROSOFT SOFTWARE PRINTER DRIVER"

/**
 * Las colas que no son una impresora: guardar como PDF/XPS, fax, OneNote. Lo delatan el PUERTO o el DRIVER, que pone
 * Windows (medido en Windows real: la de OneNote de la tienda va en un puerto `Microsoft.Office.OneNote_…` con el driver
 * «Microsoft Software Printer Driver»). El NOMBRE no cuenta: lo escribe el usuario, y «Caja XPS» puede ser la térmica.
 */
fun esColaVirtual(c: ColaInfo): Boolean {
    val puerto = c.puerto.uppercase()
    val driver = c.driver.trim().uppercase()
    return puerto in PUERTOS_VIRTUALES || driver == DRIVER_DE_APPS ||
        MARCAS_VIRTUALES.any { it in puerto || it in driver }
}

fun esBluetooth(p: PuertoInfo): Boolean = "BTHMODEM" in p.dispositivo.uppercase() || "BLUETOOTH" in p.dispositivo.uppercase()

/** Puerto de una impresora conectada por cable USB: `USB001`, y también los de los drivers de Epson (`TMUSB001`, `ESDPRT001`). */
private fun esPuertoUsb(puerto: String): Boolean = puerto.uppercase().let { "USB" in it || "ESDPRT" in it }

// Bits de PRINTER_INFO_2 (winspool.h).
private const val ESTADO_PAUSA = 0x1
private const val ESTADO_ERROR = 0x2
private const val ESTADO_BORRANDOSE = 0x4                  // PRINTER_STATUS_PENDING_DELETION
private const val ESTADO_ATASCO = 0x8
private const val ESTADO_PROBLEMA_DE_PAPEL = 0x40          // PRINTER_STATUS_PAPER_PROBLEM
private const val ESTADO_FUERA_DE_LINEA = 0x80
private const val ESTADO_NO_DISPONIBLE = 0x1000
private const val ESTADO_PIDE_ATENCION = 0x100000          // PRINTER_STATUS_USER_INTERVENTION
private const val ESTADO_TAPA_ABIERTA = 0x400000
private const val ATRIBUTO_TRABAJAR_SIN_CONEXION = 0x400

/**
 * null = lista para imprimir; si no, QUÉ pasa y QUÉ hacer, para el cajero. Cada estado bloquea SOLO: Windows no siempre
 * lo acompaña con PRINTER_STATUS_ERROR. Ocupada (BUSY) o imprimiendo (PRINTING) no bloquean.
 *
 * 🔴 SIN PAPEL (PRINTER_STATUS_PAPER_OUT, 0x10) NO bloquea: el pulso del cajón no usa papel y, con el cliente esperando su
 * cambio, el cajón tiene que abrir. Es lo mismo que hace la USB de Android, que no revisa el papel.
 */
fun motivoFueraDeLinea(c: ColaInfo): String? = when {
    // Primero la marca que puso una persona: el remedio es quitarla, no revisar el cable.
    c.atributos and ATRIBUTO_TRABAJAR_SIN_CONEXION != 0 ->
        "La impresora «${c.nombre}» está marcada como «Usar impresora sin conexión» en Windows. Quita esa marca en Impresoras y escáneres."
    c.estado and ESTADO_BORRANDOSE != 0 ->
        "Windows está borrando la impresora «${c.nombre}». Bórrala también de Avoqado y vuelve a buscarla."
    c.estado and (ESTADO_FUERA_DE_LINEA or ESTADO_NO_DISPONIBLE) != 0 ->
        "La impresora «${c.nombre}» está fuera de línea en Windows. Revisa que esté encendida y que el cable esté conectado."
    c.estado and ESTADO_PAUSA != 0 ->
        "La impresora «${c.nombre}» está en pausa en Windows. Abre Impresoras y escáneres, entra a la impresora y quita la pausa."
    c.estado and (ESTADO_ERROR or ESTADO_ATASCO or ESTADO_TAPA_ABIERTA) != 0 ->
        "La impresora «${c.nombre}» tiene un error en Windows (tapa abierta o papel atorado). Revísala y vuelve a intentar."
    c.estado and ESTADO_PROBLEMA_DE_PAPEL != 0 ->
        "La impresora «${c.nombre}» tiene un problema con el papel. Revisa el rollo y que no esté atorado, y vuelve a intentar."
    c.estado and ESTADO_PIDE_ATENCION != 0 ->
        "La impresora «${c.nombre}» pide atención en Windows. Revísala (papel, tapa o botones) y vuelve a intentar."
    else -> null
}

fun impresorasVisibles(colas: List<ColaInfo>, puertos: List<PuertoInfo>): List<DiscoveredPrinter> {
    val reales = colas.filterNot(::esColaVirtual).sortedWith(compareBy({ !esPuertoUsb(it.puerto) }, { it.nombre }))
    val deCola = reales.map {
        DiscoveredPrinter(
            id = "win_cola_${it.nombre}",
            name = "${it.nombre} (impresora de Windows)",
            connectionType = PrinterConnectionType.USB,
            address = DestinoDeWindows.Cola(it.nombre).comoDireccion(),
        )
    }
    // SERIALCOMM también lista puertos virtuales que no se llaman COMn (com0com «CNCA0»…): esos no se pueden abrir.
    val deCom = puertos.filter { PUERTO_COM.matches(it.puerto) }.sortedBy { it.puerto.removePrefix("COM").toInt() }.map {
        DiscoveredPrinter(
            id = "win_com_${it.puerto}",
            name = if (esBluetooth(it)) "Bluetooth (${it.puerto})" else "Puerto ${it.puerto}",
            connectionType = PrinterConnectionType.USB,
            address = DestinoDeWindows.Com(it.puerto).comoDireccion(),
        )
    }
    return deCola + deCom
}

// --- Textos para el cajero: QUÉ pasa y QUÉ hacer. El detalle técnico (códigos de Windows, bytes) va a la bitácora. ---

const val SOLO_EN_WINDOWS = "Las impresoras USB y COM sólo funcionan en Windows."
const val TEXTO_DIRECCION_DE_ANDROID = "Esta impresora se configuró en otro aparato. Bórrala y vuelve a buscarla."

fun textoColaInexistente(nombre: String) =
    "Windows ya no tiene la impresora «$nombre» (la borraron o le cambiaron el nombre). Bórrala de Avoqado y vuelve a buscarla."

fun textoNoSeAbrioLaCola(nombre: String) = "Windows no dejó abrir la impresora «$nombre». Revisa que esté encendida y vuelve a intentar."

/** StartDocPrinter con ERROR_INVALID_DATATYPE: un driver v4/IPP que no acepta RAW. */
fun textoNoAceptaTicketsDirectos(nombre: String) =
    "La impresora «$nombre» no acepta tickets directos. Usa su driver de tickets o el driver «Generic / Text Only»."

fun textoTicketIncompleto(nombre: String) = "La impresora «$nombre» no recibió el ticket completo. Revisa que esté encendida y vuelve a intentar."

/** StartDocPrinter con ERROR_ACCESS_DENIED: el usuario de Windows no tiene permiso de imprimir en esa cola. */
fun textoSinPermisoParaImprimir(nombre: String) =
    "Windows no permite imprimir en «$nombre» con este usuario. Revisa los permisos de la impresora."

/** GetPrinter falló por algo que NO es «no existe» (permisos, spooler): no es motivo para borrarla. */
fun textoNoSePudoConsultar(nombre: String) = "No se pudo consultar la impresora «$nombre» en Windows. Vuelve a intentar."

/** AbortPrinter no canceló el trabajo a medias: podría salir (o seguir en la cola) si se reimprime sin revisar. */
const val TEXTO_NO_SE_PUDO_CANCELAR =
    "No se pudo cancelar el trabajo en Windows; revisa la cola de la impresora antes de volver a imprimir."

/** Un Error de JNA (librería que no carga, clase que falta) convertido en falla de impresora. La causa va a la bitácora. */
const val TEXTO_NO_SE_PUDO_HABLAR = "No se pudo hablar con la impresora en Windows. Vuelve a intentar; si sigue, reinicia el POS."
