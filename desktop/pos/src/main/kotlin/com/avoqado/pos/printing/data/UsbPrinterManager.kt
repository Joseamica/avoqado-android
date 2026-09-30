package com.avoqado.pos.printing.data

import android.content.Context
import android.util.Log
import com.avoqado.pos.printing.data.model.DiscoveredPrinter
import com.avoqado.pos.printing.data.model.PrinterException

/**
 * Reemplazo de escritorio: no hay USB host de Android. Se comporta como un Android sin impresora USB conectada:
 * no descubre nada, no abre nada y `write` falla como «no conectada». PrinterService ya sabe manejar esos errores
 * (el estado de la impresora queda en Error con el motivo). Las impresoras de red (WiFi) siguen funcionando.
 */
internal class UsbPrinterManager(@Suppress("unused") private val context: Context) {
    fun discoverPrinters(): List<DiscoveredPrinter> {
        Log.w("Escritorio", "No disponible en Windows todavía: impresoras USB")
        return emptyList()
    }

    /** En Android devuelve el UsbDevice; aquí nunca hay uno (PrinterService sólo lo compara con null). */
    fun findDevice(address: String): Any? = null

    suspend fun open(printerId: String, address: String) {
        throw PrinterException.ConnectionFailed("Las impresoras USB no están disponibles en Windows todavía. Usa una impresora de red.")
    }

    fun isOpen(printerId: String): Boolean = false

    fun close(printerId: String) = Unit

    /** El mismo error que el original cuando la impresora no está abierta. */
    fun write(printerId: String, data: ByteArray) {
        throw PrinterException.NotConnected()
    }
}
