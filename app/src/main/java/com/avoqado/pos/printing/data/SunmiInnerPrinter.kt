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
     * ¿Este equipo trae impresora FÍSICA?
     *
     * El bind AIDL NO alcanza para saberlo: Sunmi preinstala
     * `woyou.aidlservice.jiuiv5` en TODA su gama, así que en una T3 Pro —que no
     * tiene cabezal— el bind también responde `true` y la app terminaba
     * ofreciendo "Impresora integrada" en un equipo sin impresora. Configurada
     * ahí, la comanda se enruta a un destino inexistente y la cocina nunca se
     * entera: el fallo se ve al momento de servir, no al configurar.
     *
     * Medido en hardware (T3 PRO SUPER, 2026-07-28): `updatePrinterState()`
     * devuelve 505 ("no printer detected") con modal/serial VACÍOS.
     *
     * 🔴 El sesgo es DELIBERADO hacia ofrecerla: sólo se descarta con evidencia
     * POSITIVA de ausencia. Sin papel, tapa abierta, sobrecalentada o un error
     * transitorio son estados de una impresora que SÍ existe — descartarla ahí
     * dejaría al local sin comandas, que es justo el fallo que no se vale
     * cometer. Ante cualquier duda (excepción, valor desconocido): se ofrece.
     */
    val hasPhysicalPrinter: Boolean
        get() {
            val svc = service ?: return false
            val state = runCatching { svc.updatePrinterState() }.getOrNull()
            if (state == NO_PRINTER_DETECTED) return false
            // El servicio corre pero no hay cabezal detrás: sin estado legible
            // Y sin modelo, no hay nada que respalde que exista.
            val modal = runCatching { svc.printerModal }.getOrNull()
            if (state == null && modal.isNullOrBlank()) return false
            return true
        }

    /** Para el log de arranque: deja rastro de por qué se ofreció o no. */
    private fun hardwareDescription(): String {
        val svc = service ?: return "sin servicio"
        val state = runCatching { svc.updatePrinterState() }.getOrNull()
        val modal = runCatching { svc.printerModal }.getOrNull().orEmpty()
        return if (hasPhysicalPrinter) {
            "impresora física presente (state=$state modal=${modal.ifBlank { "?" }})"
        } else {
            "SIN impresora física (state=$state) — no se ofrecerá la integrada"
        }
    }

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
            Log.i(tag, "Hardware: ${hardwareDescription()}")
        }

        override fun onDisconnected() {
            service = null
            Log.i(tag, "Impresora integrada desconectada")
        }
    }

    /** Sólo para tests: inyecta el servicio sin pasar por el bind del SO. */
    @androidx.annotation.VisibleForTesting
    internal fun attachServiceForTest(svc: SunmiPrinterService?) { service = svc }

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
     * Garantiza el bind ANTES de imprimir. El bind es asíncrono y sólo se hacía
     * al BUSCAR impresoras — así que tras reiniciar la app (o si el servicio se
     * cayó) la impresora guardada salía "no disponible" e imprimir fallaba con
     * "no hay impresora de recibos configurada", aunque la búsqueda sí la veía.
     * Aquí se liga y se espera a que responda (hasta ~2 s). En equipos no-Sunmi
     * el bind nunca conecta y devuelve false sin trabar nada.
     */
    suspend fun ensureBound(): Boolean {
        if (service != null) return true
        bind()
        repeat(BIND_WAIT_TRIES) {
            if (service != null) return true
            kotlinx.coroutines.delay(BIND_WAIT_STEP_MS)
        }
        return service != null
    }

    /**
     * Manda ESC/POS crudo y ESPERA el resultado del hardware. Suspende hasta que
     * el servicio confirma: si devolviéramos antes, un ticket sin papel o con la
     * tapa abierta se reportaría como impreso.
     */
    suspend fun printRaw(data: ByteArray) {
        // Liga a demanda: si el servicio se cayó o nunca se bindeó esta sesión,
        // lo intenta ahora en vez de fallar de una. Esta era la causa raíz de
        // "no hay impresora de recibos configurada" tras reiniciar la app.
        ensureBound()
        try {
            printRawInternal(data)
        } catch (e: PrinterException) {
            // 🔴 AUTO-RECUPERACIÓN. El servicio AIDL de Sunmi se cuelga solo en
            // estado 3 y deja de imprimir en silencio; re-ligarlo lo devuelve a
            // estado 1 (medido en una D3 con impresora real). Antes de darle un
            // error al mesero —que no puede hacer nada con él a media comida—
            // la app se recupera sola y reintenta UNA vez.
            //
            // Sólo para estados recuperables: sin papel o tapa abierta necesitan
            // una mano humana y su mensaje debe llegar tal cual.
            if (!isRecoverable(e.message)) throw e
            Log.w(tag, "Impresora en mal estado (${e.message}) — re-ligo el servicio y reintento")
            unbind()
            if (!ensureBound()) throw e
            printRawInternal(data)
        }
    }

    /** Estados que se arreglan re-ligando el servicio, sin tocar el hardware. */
    private fun isRecoverable(message: String?): Boolean =
        message?.contains("no responde") == true

    /**
     * Traduce el estado del cabezal a algo que un mesero pueda ACCIONAR, o null
     * si se puede imprimir.
     *
     * Medido en una D3 con impresora real (2026-07-28): el servicio AIDL de
     * Sunmi se quedó en estado 3 ("comunicación anormal") solo, sin que nadie
     * tocara nada — y en ese estado `sendRAWData` NO imprime, NO devuelve error
     * y la app seguía diciendo "Conectada". O sea: la comanda no sale y NADIE se
     * entera hasta que el cliente reclama. Reiniciar el servicio lo devolvió a
     * estado 1 y el papel salió.
     */
    private fun stateProblem(state: Int?): String? = when (state) {
        null, 1, 2 -> null // normal / preparando / no legible → adelante
        4 -> "La impresora no tiene papel. Cambia el rollo."
        5 -> "La impresora está sobrecalentada. Espera un momento."
        6 -> "La tapa de la impresora está abierta. Ciérrala."
        7 -> "El cortador de la impresora está atascado."
        9 -> "La impresora no encuentra la marca del papel."
        NO_PRINTER_DETECTED -> "Este equipo no tiene impresora integrada."
        else -> "La impresora no responde (estado $state)."
    }

    private suspend fun printRawInternal(data: ByteArray): Unit = suspendCancellableCoroutine { cont ->
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
            val state = runCatching { svc.updatePrinterState() }.getOrNull()
            Log.i(tag, "estado impresora = $state")
            // 🔴 Sin esto el fallo es MUDO: con el servicio en estado 3 no sale
            // papel, no hay excepción y el ticket se da por impreso.
            stateProblem(state)?.let { motivo ->
                cont.resumeWithException(PrinterException.ConnectionFailed(motivo))
                return@runCatching
            }
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

    private companion object {
        // Espera al bind del servicio (asíncrono): 10 × 200 ms = 2 s.
        const val BIND_WAIT_TRIES = 10
        const val BIND_WAIT_STEP_MS = 200L

        /** Código del SDK de Sunmi: el servicio existe pero no hay cabezal. */
        const val NO_PRINTER_DETECTED = 505
    }
}
