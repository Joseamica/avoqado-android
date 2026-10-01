package com.avoqado.pos.escritorio.tactil

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

const val WM_POINTERUPDATE = 0x0245
/** GetMessageExtraInfo de un mensaje de mouse que Windows generó desde un dedo o una pluma (MI_WP_SIGNATURE). */
const val MI_WP_SIGNATURE = 0xFF515700L

fun esMouseDelDedo(extra: Long): Boolean = (extra and 0xFFFFFF00L) == MI_WP_SIGNATURE

/** Los mensajes de mouse que interesan al diagnóstico: WM_MOUSEMOVE … WM_MOUSEHWHEEL. */
fun esDeMouse(mensaje: Int): Boolean = mensaje in 0x0200..0x020E

const val WM_POINTERDOWN = 0x0246
const val WM_POINTERUP = 0x0247
const val WM_POINTERENTER = 0x0249
const val WM_POINTERLEAVE = 0x024A
const val WM_POINTERACTIVATE = 0x024B
const val WM_POINTERCAPTURECHANGED = 0x024C
const val WM_NCDESTROY = 0x0082
const val PT_TOUCH = 2
const val POINTER_FLAG_INCONTACT = 0x4
const val POINTER_FLAG_CANCELED = 0x8000

/** Banderas del MENSAJE (palabra alta del wParam de WM_POINTER*): IS_POINTER_INCONTACT_WPARAM. */
const val POINTER_MESSAGE_FLAG_INCONTACT = 0x4

/** `null` = se instala. Si no, el motivo (va al diagnóstico). */
fun motivoParaNoInstalar(so: String, propiedad: String?): String? = when {
    !so.startsWith("Windows") -> "no es Windows"
    propiedad == "mouse" -> "apagado con -Davoqado.toque=mouse"
    else -> null
}

/** GET_POINTERID_WPARAM: la palabra baja. */
fun idDePuntero(wParam: Long): Int = (wParam and 0xFFFFL).toInt()

/** El dedo sigue apoyado, según el propio mensaje (sin preguntarle a Windows). */
fun enContactoDe(wParam: Long): Boolean = ((wParam shr 16) and POINTER_MESSAGE_FLAG_INCONTACT.toLong()) != 0L

/** Los mensajes de UN contacto. WM_POINTERACTIVATE no: ése activa la ventana y siempre pasa intacto. */
private val DE_UN_CONTACTO =
    setOf(WM_POINTERENTER, WM_POINTERDOWN, WM_POINTERUPDATE, WM_POINTERUP, WM_POINTERLEAVE, WM_POINTERCAPTURECHANGED)

fun esDeUnContacto(mensaje: Int): Boolean = mensaje in DE_UN_CONTACTO

/** `banderas = null`: Windows no dio la información del puntero. Sin información confiable, el gesto se cancela. */
fun faseDe(mensaje: Int, banderas: Int?): FaseDeToque? {
    if (banderas == null) {
        return if (mensaje == WM_POINTERDOWN || mensaje == WM_POINTERUPDATE || mensaje == WM_POINTERUP ||
            mensaje == WM_POINTERCAPTURECHANGED
        ) FaseDeToque.CANCELA else null
    }
    val cancelado = banderas and POINTER_FLAG_CANCELED != 0
    return when (mensaje) {
        WM_POINTERDOWN -> FaseDeToque.BAJA
        WM_POINTERUPDATE -> when {
            cancelado -> FaseDeToque.CANCELA
            banderas and POINTER_FLAG_INCONTACT != 0 -> FaseDeToque.MUEVE
            else -> null
        }
        WM_POINTERUP -> if (cancelado) FaseDeToque.CANCELA else FaseDeToque.SUBE
        WM_POINTERCAPTURECHANGED -> FaseDeToque.CANCELA
        else -> null
    }
}

/**
 * Qué secuencias de puntero son del puente. Cada una se decide UNA vez (tomada o rechazada) y la decisión dura hasta que
 * la secuencia termina: consumir sólo una parte deja a Windows con una secuencia a medias. Sólo la usa el hilo nativo.
 */
class ContactosInterceptados(private val capacidad: Int = 64) {
    private enum class Estado(val tomado: Boolean, val vigente: Boolean) {
        TOMADO(true, true), TOMADO_FIN(true, false), RECHAZADO(false, true), RECHAZADO_FIN(false, false)
    }

    private val estados = LinkedHashMap<Int, Estado>()

    /** Ninguna secuencia nuestra a medias: ya se puede restaurar el procedimiento de la ventana. */
    val sinActivos: Boolean get() = estados.values.none { it == Estado.TOMADO }

    /** `true` = el puente consume este mensaje. `esTactil` sólo se consulta para una secuencia que empieza. */
    fun registrar(mensaje: Int, id: Int, enContacto: Boolean, esTactil: () -> Boolean, tomarNuevos: Boolean): Boolean {
        if (!esDeUnContacto(mensaje)) return false
        val actual = estados[id]
        if (actual == null || !actual.vigente) {
            if (mensaje == WM_POINTERENTER || mensaje == WM_POINTERDOWN) return decidir(id, esTactil, tomarNuevos)
            if (actual == null) return false   // empezó sin el puente: termina sin él
            if (mensaje == WM_POINTERLEAVE && !enContacto) estados.remove(id)   // el LEAVE tardío de lo ya terminado
            return actual.tomado
        }
        if (mensaje == WM_POINTERUP || mensaje == WM_POINTERCAPTURECHANGED) {
            estados[id] = if (actual.tomado) Estado.TOMADO_FIN else Estado.RECHAZADO_FIN
        } else if (mensaje == WM_POINTERLEAVE && !enContacto) {
            estados.remove(id)   // sin contacto, la secuencia acabó (p. ej. una pluma que sólo flotaba)
        }
        return actual.tomado
    }

    private fun decidir(id: Int, esTactil: () -> Boolean, tomarNuevos: Boolean): Boolean {
        estados.remove(id)
        // ponytail: sólo se tiran secuencias terminadas; las vigentes las termina Windows (UP o CAPTURECHANGED), así que
        // no se acumulan. Si algún día se acumularan, esto crece en vez de partir una secuencia.
        while (estados.size >= capacidad) {
            val terminada = estados.entries.firstOrNull { !it.value.vigente }?.key ?: break
            estados.remove(terminada)
        }
        val tomado = tomarNuevos && runCatching { esTactil() }.getOrDefault(false)
        estados[id] = if (tomado) Estado.TOMADO else Estado.RECHAZADO
        return tomado
    }
}

/** 3 fallas SEGUIDAS avisan una sola vez. Seguro entre hilos. */
class ContadorDeFallas(private val limite: Int = 3, private val alLlegar: (Throwable) -> Unit) {
    private val seguidas = AtomicInteger(0)
    private val avisado = AtomicBoolean(false)

    fun exito() = seguidas.set(0)

    fun fallo(t: Throwable) {
        if (seguidas.incrementAndGet() >= limite && avisado.compareAndSet(false, true)) alLlegar(t)
    }
}

/**
 * El tiempo de cada contacto, sacado de `POINTER_INFO.dwTime` (ms de 32 bits) y vuelto una cuenta creciente: la inercia
 * se calcula con él, así que no puede ser la hora en que el hilo de Swing alcanzó a procesarlo. Sólo el hilo nativo.
 */
class RelojDePunteros {
    private var ultimo: Long? = null
    private var ms = 0L

    fun tiempo(dwTime: Int?): Long {
        val dw = (dwTime ?: return ms).toLong() and 0xFFFF_FFFFL
        val previo = ultimo
        if (previo == null) {
            ms = dw
            ultimo = dw
        } else {
            val delta = (dw - previo) and 0xFFFF_FFFFL
            when {
                delta < 0x8000_0000L -> {   // hacia adelante (incluida la vuelta de los 32 bits)
                    ms += delta
                    ultimo = dw
                }
                0x1_0000_0000L - delta > 60_000L -> {   // no es un desorden de milisegundos: una pausa de más de ~24.8 días
                    ms += 1                             // se re-ancla sin retroceder (la duración de la pausa no importa)
                    ultimo = dw
                }
                // si no: un mensaje fuera de orden por unos ms; no cuenta
            }
        }
        return ms
    }
}
