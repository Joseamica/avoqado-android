package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.printing.data.model.PrinterException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reemplazo de escritorio: una PC no trae la impresora interna de Sunmi. Se comporta como un Android SIN impresora
 * integrada (el servicio nunca se liga), así PrinterService toma su camino de siempre: «La impresora integrada no está
 * disponible» / «Este equipo no tiene impresora integrada». Nunca finge que imprimió.
 */
@Singleton
class SunmiInnerPrinter @Inject constructor() {
    val isAvailable: Boolean get() = false

    val hasPhysicalPrinter: Boolean get() = false

    val paperWidthMm: Int get() = 58

    fun bind() {
        Log.w("Escritorio", "No disponible en Windows todavía: impresora integrada de Sunmi")
    }

    suspend fun ensureBound(): Boolean = false

    /** El mismo error que el original cuando no hay servicio ligado. */
    suspend fun printRaw(data: ByteArray) {
        throw PrinterException.ConnectionFailed("La impresora integrada no está disponible")
    }
}
