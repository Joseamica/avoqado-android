package com.avoqado.pos.printing.data

import android.content.Context
import android.util.Log
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.avoqado.pos.printing.data.model.PrinterException
import com.sunmi.peripheral.printer.SunmiPrinterService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Impresora TÉRMICA INTEGRADA de los POS Sunmi (D3, T3, V2…).
 *
 * 🔴 Por qué existe este archivo: la impresora del equipo NO es un periférico.
 * No enumera por USB (no hay interfaz de clase 07), no está en Bluetooth ni en
 * la red — por eso "buscar impresoras" jamás la encontraba y parecía descompuesta.
 * Solo se alcanza por el servicio AIDL `woyou.aidlservice.jiuiv5`.
 *
 * Manda ESC/POS CRUDO (`sendRAWData`), o sea reutiliza tal cual los mismos bytes
 * que ya generamos para las impresoras de red/Bluetooth: un solo formato de
 * ticket para todos los transportes, sin una segunda ruta de impresión que se
 * desincronice.
 *
 * Falla suave: en un equipo que no es Sunmi el bind no ocurre, `isAvailable`
 * queda en false y la app se comporta exactamente como antes.
 */
@Singleton
class SunmiInnerPrinter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tag = "🖨️SunmiInner"

    @Volatile
    private var service: SunmiPrinterService? = null

    /** true solo cuando el servicio respondió: es lo que decide si la ofrecemos. */
    val isAvailable: Boolean get() = service != null

    /**
     * Ancho real del cabezal, PREGUNTADO al hardware (1 = 58 mm, 2 = 80 mm).
     * Adivinarlo parte el ticket: 80 mm de ESC/POS en un cabezal de 58 mm sale
     * con las líneas cortadas. Ante la duda, 58: sobra papel en vez de perderse
     * texto.
     */
    val paperWidthMm: Int
        get() = runCatching { if (service?.printerPaper == 2) 80 else 58 }.getOrDefault(58)

    private val callback = object : InnerPrinterCallback() {
        override fun onConnected(printerService: SunmiPrinterService?) {
            service = printerService
            Log.i(tag, "Impresora integrada conectada")
        }

        override fun onDisconnected() {
            service = null
            Log.i(tag, "Impresora integrada desconectada")
        }
    }

    /** Llamar al arranque. Sin equipo Sunmi lanza excepción y se ignora. */
    fun bind() {
        if (service != null) return
        runCatching { InnerPrinterManager.getInstance().bindService(context, callback) }
            // Se registra el RESULTADO, no solo la excepción: bindService puede
            // devolver false sin lanzar nada (p.ej. si falta el <queries> del
            // manifiesto) y ese silencio ya costó una ronda de diagnóstico.
            .onSuccess { ok -> Log.i(tag, "bindService → $ok") }
            .onFailure { Log.i(tag, "Sin impresora integrada en este equipo: ${it.message}") }
    }

    fun unbind() {
        runCatching { InnerPrinterManager.getInstance().unBindService(context, callback) }
        service = null
    }

    /**
     * Manda ESC/POS crudo y ESPERA el resultado del hardware. Suspende hasta que
     * el servicio confirma: si devolviéramos antes, un ticket sin papel o con la
     * tapa abierta se reportaría como impreso.
     */
    suspend fun printRaw(data: ByteArray): Unit = suspendCancellableCoroutine { cont ->
        val svc = service
        if (svc == null) {
            cont.resumeWithException(PrinterException.ConnectionFailed("La impresora integrada no está disponible"))
            return@suspendCancellableCoroutine
        }
        runCatching {
            // 🔴 printerInit() ANTES de cada trabajo: tras el arranque —o tras un
            // trabajo de otra app— el cabezal puede quedar en un estado donde el
            // ESC/POS crudo se traga sin imprimir nada (sale papel en blanco).
            // Los ejemplos oficiales de Sunmi lo llaman siempre; nosotros no, y
            // ese fue exactamente el síntoma.
            svc.printerInit(null)
            Log.i(tag, "estado impresora = ${runCatching { svc.updatePrinterState() }.getOrNull()}")
            svc.sendRAWData(
                data,
                object : InnerResultCallback() {
                    override fun onRunResult(isSuccess: Boolean) {
                        if (!cont.isActive) return
                        if (isSuccess) {
                            cont.resume(Unit)
                        } else {
                            cont.resumeWithException(
                                PrinterException.ConnectionFailed("La impresora integrada rechazó el ticket"),
                            )
                        }
                    }

                    override fun onReturnString(result: String?) = Unit

                    override fun onRaiseException(code: Int, msg: String?) {
                        if (!cont.isActive) return
                        cont.resumeWithException(
                            PrinterException.ConnectionFailed(msg ?: "Error de la impresora integrada ($code)"),
                        )
                    }

                    override fun onPrintResult(code: Int, msg: String?) = Unit
                },
            )
        }.onFailure { if (cont.isActive) cont.resumeWithException(it) }
    }
}
