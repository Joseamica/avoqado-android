package com.avoqado.pos.escritorio.bascula

import com.avoqado.pos.escritorio.impresion.PuertoInfo
import com.avoqado.pos.escritorio.impresion.esBluetooth
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Cfgmgr32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.platform.win32.WinReg

/*
 * La báscula en Windows es un puerto COM (adaptador USB-serie CH340, FTDI, Prolific, Silicon Labs, o un COM de la tarjeta
 * madre). Android la busca por el VID/PID del USB; aquí se lee del registro qué COM es de qué VID/PID, y se elige con la
 * MISMA regla de Android: nunca adivinar entre varios.
 */

/**
 * Un puerto COM vivo: nombre («COM3»), su dispositivo de Windows (\Device\…) y el VID/PID del USB si lo tiene. [ambiguo]: el
 * registro guarda ese COM para DOS adaptadores distintos (uno ya desconectado conserva su PortName): no se sabe cuál es.
 */
data class PuertoSerie(
    val nombre: String,
    val dispositivo: String,
    val vendorId: Int?,
    val productId: Int?,
    val ambiguo: Boolean = false,
)

private val VID_PID = Regex("""VID_([0-9A-Fa-f]{4})[&+]PID_([0-9A-Fa-f]{4})""")

/** «VID_1A86&PID_7523», «VID_0403+PID_6001+A50285BIA» (FTDI), «VID_067B&PID_2303&MI_00» ⇒ (vid, pid). */
internal fun vidPidDe(clave: String): Pair<Int, Int>? =
    VID_PID.find(clave)?.let { it.groupValues[1].toInt(16) to it.groupValues[2].toInt(16) }

sealed interface EleccionDePuerto {
    data class Elegido(val puerto: PuertoSerie) : EleccionDePuerto
    data class Falla(val mensaje: String) : EleccionDePuerto
}

/**
 * La regla de `UsbSerialScaleManager.connect` (Android), con los puertos COM en lugar de los drivers USB. Los Bluetooth no
 * son báscula (un BT emparejado también es un COM en Windows).
 */
internal fun elegirPuerto(puertos: List<PuertoSerie>, vendorId: Int?, productId: Int?): EleccionDePuerto {
    val candidatos = puertos.filterNot { esBluetooth(PuertoInfo(it.nombre, it.dispositivo)) }
    fun coincide(p: PuertoSerie): Boolean = when {
        p.ambiguo -> vendorId == null && productId == null   // con VID/PID configurado, nunca se adivina un COM ambiguo
        vendorId != null && productId != null -> p.vendorId == vendorId && p.productId == productId
        vendorId != null -> p.vendorId == vendorId
        productId != null -> p.productId == productId
        else -> true
    }
    candidatos.singleOrNull(::coincide)?.let { return EleccionDePuerto.Elegido(it) }
    val ambiguo = candidatos.firstOrNull { it.ambiguo }
    return EleccionDePuerto.Falla(
        when {
            candidatos.isEmpty() -> "No se detectó una báscula serial (USB o COM). Revisa cable y adaptador."
            candidatos.none(::coincide) && ambiguo != null ->
                "Windows tiene ${ambiguo.nombre} registrado para dos adaptadores distintos y no se sabe si es la báscula. " +
                    "Desconecta los adaptadores que no uses y vuelve a conectar la báscula."
            candidatos.count(::coincide) > 1 -> "Hay más de una báscula compatible conectada; configura VID/PID."
            vendorId == null || productId == null -> "Hay varios puertos seriales; configura VID/PID para elegir la báscula."
            else -> "La báscula configurada no está conectada."
        },
    )
}

/** Lo que se lee del registro de Windows (HKLM); una prueba da uno falso. */
internal interface Registro {
    /** ¿Ese aparato (`USB\VID_…&PID_…\<instancia>`) está conectado AHORA? El registro guarda también los ausentes. */
    fun presente(idDeInstancia: String): Boolean
    fun subclaves(ruta: String): List<String>
    fun texto(ruta: String, nombre: String): String?
    fun valores(ruta: String): Map<String, String>
}

/**
 * Los COM vivos (HARDWARE\DEVICEMAP\SERIALCOMM) con su VID/PID: cada adaptador deja su COM en
 * `Enum\USB\VID_…&PID_…\<instancia>\Device Parameters\PortName` (FTDI: `Enum\FTDIBUS\VID_…+PID_…+serie\0000\…`).
 */
internal fun puertosSerie(registro: Registro): List<PuertoSerie> {
    // El registro también guarda adaptadores AUSENTES con su PortName (uno desconectado conserva su COM3 aunque COM3 sea ahora
    // de otro): sólo cuentan los que Windows tiene conectados AHORA. Si aun así dos distintos reclaman el mismo COM, no se pisa
    // uno con otro (eso abriría el equivocado): el COM queda ambiguo.
    // Por COM: cada APARATO (id de instancia) que lo reclama, con su VID/PID. Se cuentan aparatos, no modelos: dos adaptadores
    // conectados del mismo modelo que dicen COM3 también son ambiguos.
    val reclamosPorCom = HashMap<String, MutableMap<String, Pair<Int, Int>>>()
    for (bus in listOf("USB", "FTDIBUS")) {
        val raiz = "SYSTEM\\CurrentControlSet\\Enum\\$bus"
        for (clave in registro.subclaves(raiz)) {
            val vp = vidPidDe(clave) ?: continue
            for (instancia in registro.subclaves("$raiz\\$clave")) {
                val com = registro.texto("$raiz\\$clave\\$instancia\\Device Parameters", "PortName") ?: continue
                val id = "$bus\\$clave\\$instancia"
                if (!registro.presente(id)) continue
                reclamosPorCom.getOrPut(com.uppercase()) { LinkedHashMap() }[id] = vp
            }
        }
    }
    return registro.valores("HARDWARE\\DEVICEMAP\\SERIALCOMM")
        .map { (dispositivo, com) ->
            val reclamos = reclamosPorCom[com.uppercase()].orEmpty()
            val unico = reclamos.values.singleOrNull()
            PuertoSerie(com, dispositivo, unico?.first, unico?.second, ambiguo = reclamos.size > 1)
        }
        .sortedBy { it.nombre }
}

/** CR_NO_SUCH_DEVNODE (cfgmgr32.h): el aparato no está conectado. */
internal const val CR_NO_SUCH_DEVNODE = 0x0D

/** Sólo «no existe ese aparato» cuenta como desconectado; cualquier otro error de la API no dice nada: se supone conectado. */
internal fun conectadoSegunCodigo(codigo: Int): Boolean = codigo != CR_NO_SUCH_DEVNODE

internal object RegistroDeWindows : Registro {
    private val hklm = WinReg.HKEY_LOCAL_MACHINE
    /** CM_Locate_DevNode NORMAL sólo encuentra aparatos conectados. Si no se puede preguntar, se supone conectado (lo de antes). */
    override fun presente(idDeInstancia: String): Boolean = runCatching {
        conectadoSegunCodigo(Cfgmgr32.INSTANCE.CM_Locate_DevNode(IntByReference(), idDeInstancia, Cfgmgr32.CM_LOCATE_DEVNODE_NORMAL))
    }.getOrDefault(true)
    override fun subclaves(ruta: String): List<String> =
        runCatching { Advapi32Util.registryGetKeys(hklm, ruta).toList() }.getOrDefault(emptyList())
    override fun texto(ruta: String, nombre: String): String? = runCatching {
        if (Advapi32Util.registryValueExists(hklm, ruta, nombre)) Advapi32Util.registryGetStringValue(hklm, ruta, nombre) else null
    }.getOrNull()
    override fun valores(ruta: String): Map<String, String> = runCatching {
        Advapi32Util.registryGetValues(hklm, ruta).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())   // sin puertos COM la llave no existe: no es un error
}
