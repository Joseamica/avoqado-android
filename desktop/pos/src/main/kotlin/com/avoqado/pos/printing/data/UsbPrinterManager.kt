package com.avoqado.pos.printing.data

import android.content.Context
import android.util.Log
import com.avoqado.pos.escritorio.impresion.CanalAbierto
import com.avoqado.pos.escritorio.impresion.DestinoDeWindows
import com.avoqado.pos.escritorio.impresion.ImpresionDeEscritorio
import com.avoqado.pos.escritorio.impresion.Momento
import com.avoqado.pos.escritorio.impresion.SOLO_EN_WINDOWS
import com.avoqado.pos.escritorio.impresion.SistemaFueraDeWindows
import com.avoqado.pos.escritorio.impresion.TEXTO_DIRECCION_DE_ANDROID
import com.avoqado.pos.escritorio.impresion.impresorasVisibles
import com.avoqado.pos.escritorio.impresion.leerDireccion
import com.avoqado.pos.escritorio.impresion.motivoFueraDeLinea
import com.avoqado.pos.escritorio.impresion.sinErroresSueltos
import com.avoqado.pos.escritorio.impresion.textoColaInexistente
import com.avoqado.pos.printing.data.model.DiscoveredPrinter
import com.avoqado.pos.printing.data.model.PrinterException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock

/**
 * Reemplazo de escritorio de la USB de Android: en Windows una impresora de tickets es una COLA de impresión (con su
 * driver) o un PUERTO COM (USB-serie, o Bluetooth emparejado). Misma firma que el original, así que PrinterService
 * (sin cambios) la usa igual: buscar, abrir, escribir, cerrar. Cada impresora (por id) tiene su canal: varias a la vez.
 *
 * 🔴 Ninguna llamada deja salir un `Error` (de JNA): PrinterService sólo atrapa `Exception`, y un Error a media venta
 * aborta el cobro entero. Todo sale como PrinterException con texto llano, y findDevice nunca lanza.
 *
 * 🔴 findDevice y close corren en el hilo de la pantalla (PrinterService los llama antes de su withContext(IO); el botón
 * «Desconectar» llama close directo): no le preguntan nada a Windows y nunca esperan. Lo que Windows sabe (borrada, en
 * pausa, fuera de línea) lo revisan open y write, que corren en IO.
 */
internal class UsbPrinterManager(@Suppress("unused") private val context: Context) {
    private sealed interface Abierta {
        data class Cola(val nombre: String) : Abierta
        class Com(val canal: CanalAbierto) : Abierta
    }

    private val abiertas = ConcurrentHashMap<String, Abierta>()

    /**
     * Un candado por impresora (por id), para abrir, escribir en COM y cerrar: dos «abrir» a la vez no hacen dos
     * CreateFile del mismo COM, dos tickets al mismo COM no se intercalan, y el cierre no corta un ticket a media escritura
     * ni deja registrado un canal que una apertura en curso abrió después.
     */
    private val candados = ConcurrentHashMap<String, ReentrantLock>()
    private fun candado(printerId: String): ReentrantLock = candados.computeIfAbsent(printerId) { ReentrantLock() }

    /**
     * Cierres que no se pudieron hacer en el acto porque la impresora estaba ocupada (escribiendo o abriéndose). Mientras
     * hay uno, la impresora ya no cuenta como abierta, ningún ticket nuevo entra, y open espera a que termine.
     */
    private class CierrePendiente { val listo = CountDownLatch(1) }
    private val cierresPendientes = ConcurrentHashMap<String, CierrePendiente>()

    /** Dónde corre un cierre pendiente: un hilo aparte que espera el candado. Las pruebas lo sustituyen para fijar el orden. */
    @Volatile internal var lanzarCierre: (Runnable) -> Unit = { Thread(it, "cerrar-impresora").apply { isDaemon = true }.start() }

    private inline fun <T> conCandado(printerId: String, bloque: () -> T): T {
        val c = candado(printerId)
        c.lock()
        try { return bloque() } finally { c.unlock() }
    }

    /** El candado de la impresora, DESPUÉS de que termine su cierre pendiente (si llega otro mientras, se espera también). */
    private inline fun <T> conCandadoTrasElCierre(printerId: String, bloque: () -> T): T {
        val c = candado(printerId)
        while (true) {
            cierresPendientes[printerId]?.listo?.await()
            c.lock()
            if (cierresPendientes[printerId] == null) {
                try { return bloque() } finally { c.unlock() }
            }
            c.unlock()
        }
    }

    private val sistema get() = ImpresionDeEscritorio.sistema

    fun discoverPrinters(): List<DiscoveredPrinter> = runCatching { impresorasVisibles(sistema.colas(), sistema.puertos()) }
        .onFailure { Log.w(TAG, "Buscar impresoras de Windows falló: ${it.message}") }
        .getOrDefault(emptyList())

    /**
     * PrinterService sólo lo compara con null (no-null = sigue conectada) y lo llama FUERA de su try y ANTES de su
     * withContext(IO), en el hilo de la pantalla: no consulta a Windows (GetPrinter puede tardar) y nunca lanza. Dice
     * sólo si la dirección es de Windows; si la impresora ya no está, lo dicen open y write.
     */
    fun findDevice(address: String): Any? = leerDireccion(address)

    /**
     * Corre en IO (PrinterService.connect → withContext(IO)). Primero espera el cierre pendiente de esta impresora: en la
     * reconexión (disconnect y luego connect) el COM viejo tiene que cerrarse ANTES de abrir el nuevo, porque Windows lo
     * abre exclusivo, y el canal viejo no se puede reusar.
     */
    suspend fun open(printerId: String, address: String) {
        sinErroresSueltos(Momento.CONECTAR) {
            // Bajo el candado de la impresora: dos «abrir» a la vez no hacen dos CreateFile del mismo COM.
            conCandadoTrasElCierre(printerId) {
                if (abiertas.containsKey(printerId)) return
                val destino = leerDireccion(address) ?: throw PrinterException.ConnectionFailed(TEXTO_DIRECCION_DE_ANDROID)
                if (sistema === SistemaFueraDeWindows) throw PrinterException.ConnectionFailed(SOLO_EN_WINDOWS)
                when (destino) {
                    is DestinoDeWindows.Cola -> {
                        val cola = sistema.cola(destino.nombre) ?: throw PrinterException.ConnectionFailed(textoColaInexistente(destino.nombre))
                        motivoFueraDeLinea(cola)?.let { throw PrinterException.ConnectionFailed(it) }
                        abiertas[printerId] = Abierta.Cola(destino.nombre)
                    }
                    is DestinoDeWindows.Com -> abiertas[printerId] = Abierta.Com(sistema.abrirPuerto(destino.puerto, destino.baudios))
                }
                Log.d(TAG, "Impresora abierta: $address")
            }
        }
    }

    fun isOpen(printerId: String): Boolean = abiertas.containsKey(printerId) && !cierresPendientes.containsKey(printerId)

    /**
     * Para la pantalla de Impresoras (PrinterService.probar, en IO): ¿la cola está lista? Le pregunta a Windows sin abrir
     * nada, con la misma regla que open (sin papel cuenta como lista: el cajón abre igual). null = no se sabe: un COM no se
     * prueba sin abrirlo (Windows lo abre exclusivo), y si Windows no contesta tampoco se inventa. Nunca lanza.
     */
    fun estaLista(address: String): Boolean? = when (val destino = leerDireccion(address)) {
        is DestinoDeWindows.Cola -> runCatching { sistema.cola(destino.nombre)?.let { motivoFueraDeLinea(it) == null } ?: false }
            .onFailure { Log.w(TAG, "No se pudo consultar la cola ${destino.nombre}: ${it.message}") }
            .getOrNull()
        else -> null
    }

    /**
     * Regresa en el acto, SIEMPRE: lo llama el hilo de la pantalla. Si la impresora está libre, cierra ya; si está ocupada
     * (un ticket a medias en el COM, una apertura), deja el cierre pendiente y lo termina otro hilo con el mismo candado,
     * cuando se desocupe: el ticket que iba no se corta.
     */
    fun close(printerId: String) {
        val c = candado(printerId)
        if (c.tryLock()) {
            try { cerrarYa(printerId) } finally { c.unlock() }
            return
        }
        val pendiente = CierrePendiente()
        if (cierresPendientes.putIfAbsent(printerId, pendiente) != null) return   // ya hay uno en camino
        val cerrar = Runnable {
            c.lock()
            try {
                cerrarYa(printerId)
            } finally {
                cierresPendientes.remove(printerId, pendiente)
                pendiente.listo.countDown()
                c.unlock()
            }
        }
        try {
            lanzarCierre(cerrar)
        } catch (t: Throwable) {
            // Sin hilo no hay cierre pendiente: que open no se quede esperando para siempre.
            Log.w(TAG, "No se pudo dejar el cierre de $printerId en espera", t)
            cierresPendientes.remove(printerId, pendiente)
            pendiente.listo.countDown()
        }
    }

    private fun cerrarYa(printerId: String) {
        (abiertas.remove(printerId) as? Abierta.Com)?.let { runCatching { it.canal.close() } }
    }

    fun write(printerId: String, data: ByteArray) {
        if (cierresPendientes.containsKey(printerId)) throw PrinterException.NotConnected()   // la están cerrando
        val abierta = abiertas[printerId] ?: throw PrinterException.NotConnected()
        sinErroresSueltos(Momento.IMPRIMIR) {
            when (abierta) {
                is Abierta.Cola -> cerrandoSiFalla(printerId) {
                    // Se vuelve a mirar: entre abrir y escribir la borraron, la desconectaron o la pausaron.
                    val cola = sistema.cola(abierta.nombre) ?: throw PrinterException.ConnectionFailed(textoColaInexistente(abierta.nombre))
                    motivoFueraDeLinea(cola)?.let { throw PrinterException.ConnectionFailed(it) }
                    sistema.imprimirEnCola(abierta.nombre, DOCUMENTO, data)
                }
                // Bajo el candado de la impresora: dos tickets al mismo COM salen uno tras otro, nunca intercalados.
                is Abierta.Com -> conCandado(printerId) {
                    if (cierresPendientes.containsKey(printerId)) throw PrinterException.NotConnected()
                    val canal = (abiertas[printerId] as? Abierta.Com)?.canal ?: throw PrinterException.NotConnected()
                    cerrandoSiFalla(printerId) { canal.escribir(data) }
                }
            }
        }
    }

    private inline fun cerrandoSiFalla(printerId: String, bloque: () -> Unit) {
        try {
            bloque()
        } catch (t: Throwable) {
            close(printerId)
            throw t
        }
    }

    private companion object {
        const val TAG = "🖨️USB"
        const val DOCUMENTO = "Avoqado POS"
    }
}
