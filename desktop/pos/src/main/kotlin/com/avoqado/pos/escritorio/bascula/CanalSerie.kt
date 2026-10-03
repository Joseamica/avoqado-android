package com.avoqado.pos.escritorio.bascula

import com.avoqado.pos.escritorio.impresion.ComCrudo
import com.avoqado.pos.escritorio.impresion.DCB
import com.avoqado.pos.escritorio.impresion.F_DSR_SENSITIVITY
import com.avoqado.pos.escritorio.impresion.F_DTR_CONTROL
import com.avoqado.pos.escritorio.impresion.F_INX
import com.avoqado.pos.escritorio.impresion.F_OUTX
import com.avoqado.pos.escritorio.impresion.F_OUTX_CTS_FLOW
import com.avoqado.pos.escritorio.impresion.F_OUTX_DSR_FLOW
import com.avoqado.pos.escritorio.impresion.F_PARITY
import com.avoqado.pos.escritorio.impresion.F_RTS_CONTROL
import com.avoqado.pos.escritorio.impresion.prepararDcb
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Un puerto serie abierto para LEER el peso (y mandar el comando de consulta). */
internal interface CanalSerie {
    /** Bloquea hasta ~250 ms; los bytes leídos, 0 si no llegó nada, -1 si el puerto se cayó. */
    fun leer(destino: ByteArray): Int
    fun escribir(bytes: ByteArray): Boolean
    fun cerrar()
}

/**
 * Lo de la báscula sobre lo que ya hace la impresora (prepararDcb: fBinary, sin abortar en error, velocidad): bits de datos,
 * paridad y bits de parada del perfil, como Android (`UsbSerialPort.setParameters`). Paridad: NONE/ODD/EVEN.
 * 🔴 Y SIN control de flujo, con DTR y RTS prendidos (lo de usb-serial-for-android): a diferencia de la impresora, aquí NO se
 * respeta lo que Windows tenga guardado en el puerto. Con CTS/DSR exigidos y un cable que no los trae, la consulta no sale;
 * con fDsrSensitivity y DSR bajo, Windows tira lo que manda la báscula.
 */
internal fun prepararDcbDeBascula(dcb: DCB, baudios: Int, datos: Int, paridad: String?, parada: Int?) {
    prepararDcb(dcb, baudios)
    val sinFlujo = F_OUTX_CTS_FLOW or F_OUTX_DSR_FLOW or F_DTR_CONTROL or F_DSR_SENSITIVITY or F_OUTX or F_INX or F_RTS_CONTROL
    dcb.bits = (dcb.bits and sinFlujo.inv()) or (1 shl 4) /* DTR_CONTROL_ENABLE */ or (1 shl 12) /* RTS_CONTROL_ENABLE */
    dcb.ByteSize = datos.toByte()
    val p = when (paridad?.uppercase()) { "ODD" -> 1; "EVEN" -> 2; else -> 0 }
    dcb.Parity = p.toByte()
    dcb.bits = if (p != 0) dcb.bits or F_PARITY else dcb.bits and F_PARITY.inv()
    dcb.StopBits = (if (parada == 2) 2 else 0).toByte()   // TWOSTOPBITS = 2, ONESTOPBIT = 0
}

/** Lo que ve el cajero cuando el COM no abre (el código de Windows de CreateFile). */
internal fun mensajeDeApertura(puerto: String, codigo: Int): String = when (codigo) {
    5 -> "Otro programa tiene abierto $puerto: cierra el software de la báscula u otra app que la use."
    2, 3 -> "$puerto ya no está conectado. Revisa el cable de la báscula."
    else -> "No se pudo abrir la báscula en $puerto (código $codigo de Windows)."
}

internal fun abrirCanalSerieDeWindows(puerto: String, baudios: Int, datos: Int, paridad: String?, parada: Int?): CanalSerie {
    val k = Kernel32.INSTANCE
    val h = k.CreateFile("\\\\.\\$puerto", WinNT.GENERIC_READ or WinNT.GENERIC_WRITE, 0, null, WinNT.OPEN_EXISTING, 0, null)
        ?.takeUnless { it == WinBase.INVALID_HANDLE_VALUE }
        ?: throw IOException(mensajeDeApertura(puerto, Native.getLastError()))
    try {
        val dcb = DCB()
        if (!ComCrudo.INSTANCE.GetCommState(h, dcb)) throw IOException("Windows no dejó leer la configuración de $puerto.")
        prepararDcbDeBascula(dcb, baudios, datos, paridad, parada)
        if (!ComCrudo.INSTANCE.SetCommState(h, dcb)) throw IOException("Windows no aceptó la configuración de $puerto ($baudios baudios).")
        val tiempos = WinBase.COMMTIMEOUTS().apply {
            ReadIntervalTimeout = WinDef.DWORD(50)          // fin de una ráfaga
            ReadTotalTimeoutConstant = WinDef.DWORD(250)    // un ReadFile nunca bloquea más: el lector revisa si debe parar
            WriteTotalTimeoutConstant = WinDef.DWORD(1_000)
        }
        if (!k.SetCommTimeouts(h, tiempos)) throw IOException("Windows no aceptó los tiempos de $puerto.")
    } catch (e: Throwable) {
        k.CloseHandle(h)
        throw e
    }
    // 🔴 Nunca se usa el HANDLE después de cerrarlo, ni se cierra dos veces: Windows reasigna ese número y se leería, escribiría
    // o cerraría otra cosa (una impresora). Leer y escribir lo «toman» (pueden ir a la vez); cerrar espera a que lo suelten
    // (a lo más 250 ms de una lectura o 1 s de una escritura) y lo cierra una sola vez.
    val uso = ReentrantReadWriteLock()
    var cerrado = false   // bajo `uso`
    return object : CanalSerie {
        override fun leer(destino: ByteArray): Int = uso.read {
            if (cerrado) return -1
            val leidos = IntByReference()
            if (k.ReadFile(h, destino, destino.size, leidos, null)) leidos.value else -1
        }
        override fun escribir(bytes: ByteArray): Boolean = uso.read {
            if (cerrado) return false
            val escritos = IntByReference()
            k.WriteFile(h, bytes, bytes.size, escritos, null) && escritos.value == bytes.size
        }
        override fun cerrar() = uso.write {
            if (!cerrado) { cerrado = true; k.CloseHandle(h) }
        }
    }
}
