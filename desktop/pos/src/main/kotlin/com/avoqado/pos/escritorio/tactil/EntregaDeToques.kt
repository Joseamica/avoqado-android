package com.avoqado.pos.escritorio.tactil

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * La mitad de Swing del puente. Entrega EN ORDEN lo que dejó el hilo nativo, en tandas (el dibujo y el teclado no
 * esperan) y sin reentrada. Para todo lo raro hay una sola regla, `cancelar()`: nueva generación, cola vacía, dedos
 * olvidados y gestos de Compose reiniciados (reintentando mientras Compose esté ocupado; si reiniciar truena, se apaga).
 * Todo corre en el hilo de Swing salvo `generacionActual` y `encolar`.
 */
class EntregaDeToques(
    private val traductor: TraductorDeToques,
    private val enviar: (EventoDeToque) -> Unit,
    private val reiniciar: (dedos: Set<Long>) -> Boolean,
    private val puedeEntregar: () -> Boolean,
    private val alBajarUnDedo: () -> Unit,
    private val programar: (retrasoMs: Int, accion: () -> Unit) -> Unit,
    private val fallas: ContadorDeFallas,
    private val alFallarLimpieza: (Throwable) -> Unit,
    private val alErrorDeLaApp: (Throwable) -> Unit,
    private val tope: Int = 256,
    private val lote: Int = 64,
    private val anotar: (String) -> Unit = {},
) {
    private class Entrada(val generacion: Int, val contacto: ContactoDeWindows)

    private val cola = ArrayBlockingQueue<Entrada>(tope)
    private val generacion = AtomicInteger(0)
    private val desbordada = AtomicBoolean(false)
    private val programado = AtomicBoolean(false)

    // Sólo el hilo de Swing.
    private var entregando = false
    private var cancelacionesAnotadas = 0
    private var reinicioPendiente = false
    private var reintentoProgramado = false          // UNA sola cadena de reintentos, aunque se cancele muchas veces
    private val enCompose = LinkedHashSet<Long>()     // dedos que Compose tiene presionados, según lo que le mandamos
    private val porReiniciar = LinkedHashSet<Long>()

    /** Lo retenido ahora (pruebas y bitácora). */
    val retenidos: Int get() = cola.size

    /** Hilo de Swing: un dedo en curso (en el traductor o presionado en Compose). */
    val hayDedos: Boolean get() = traductor.hayDedos || enCompose.isNotEmpty()

    /** Cualquier hilo. El hilo nativo la toma ANTES de leer un mensaje: si entretanto se cancela, lo que traiga se tira. */
    fun generacionActual(): Int = generacion.get()

    /** Cualquier hilo: lo que el hilo nativo esté leyendo ahora mismo ya no se entrega (p. ej. llegó el mouse). */
    fun invalidarPendientes() {
        generacion.incrementAndGet()
    }

    /** Cualquier hilo. La generación se revisa al ENTREGAR (una sola revisión, la que alcanza también a la carrera). */
    fun encolar(c: ContactoDeWindows, deGeneracion: Int) {
        if (c.fase == FaseDeToque.MUEVE && cola.remainingCapacity() <= tope / 4) return   // el siguiente trae la posición
        if (!cola.offer(Entrada(deGeneracion, c))) {   // una transición no cupo: lo retenido queda viejo YA y se cancela todo
            generacion.incrementAndGet()
            desbordada.set(true)
        }
        reprogramar(0)
    }

    fun drenar() {
        programado.set(false)
        if (entregando) {   // un manejador abrió un bucle anidado: la vuelta de afuera sigue después
            reprogramar(16)
            return
        }
        entregando = true
        try {
            var n = 0
            while (n < lote) {
                if (desbordada.getAndSet(false)) cancelar("la cola se desbordó")   // antes de CADA contacto: también si desborda a media entrega
                val e = cola.poll() ?: break
                if (e.generacion == generacion.get()) entregarUno(e.contacto)
                n++
            }
        } finally {
            entregando = false
        }
        if (cola.isNotEmpty()) reprogramar(0)
    }

    /** Pérdida de foco, modal, apagado, desborde, cancelación o falla: nada de lo que iba ni de lo que esperaba sigue. */
    fun cancelar(motivo: String) {
        if (cancelacionesAnotadas < 30) {
            cancelacionesAnotadas++
            runCatching { anotar("Gesto cancelado: $motivo · dedos en Compose=$enCompose · en cola=${cola.size}") }
        }
        generacion.incrementAndGet()
        cola.clear()
        traductor.soltarTodos(0)?.punteros?.forEach { porReiniciar += it.id }
        porReiniciar += enCompose
        enCompose.clear()
        reinicioPendiente = true
        intentarReinicio()
    }

    private fun entregarUno(c: ContactoDeWindows) {
        val e = try {
            if (!puedeEntregar()) {
                if (hayDedos) cancelar("no se puede entregar (puente apagado, diálogo modal o botón del mouse apretado)")   // algo en curso: se cancela una vez
                return
            }
            if (reinicioPendiente) return   // Compose sigue ocupado: nada entra hasta reiniciar
            if (c.fase == FaseDeToque.BAJA) alBajarUnDedo()
            val traducido = traductor.traducir(c) ?: return
            if (traducido.cancela) {
                traducido.punteros.forEach { porReiniciar += it.id }
                cancelar("Windows canceló el dedo (CANCELED, captura perdida, LEAVE sin UP o sin información)")
                return
            }
            traducido
        } catch (t: Throwable) {   // falla del puente: nada sale de drenar, el lote sigue y el drenado se reprograma
            fallas.fallo(t)
            runCatching { cancelar("falla del puente: ${t.javaClass.simpleName}: ${t.message}") }
            return
        }
        try {
            enviar(e)
        } catch (t: Throwable) {   // lo lanzó la app (un onClick) o Compose: se reinicia el gesto y se reporta como con el mouse
            runCatching { cancelar("la app tronó al recibir el toque: ${t.javaClass.simpleName}") }
            alErrorDeLaApp(t)
            return
        }
        e.punteros.forEach { if (it.presionado) enCompose += it.id else enCompose -= it.id }
        fallas.exito()
    }

    private fun intentarReinicio() {
        if (!reinicioPendiente) return
        val listo = try {
            reiniciar(porReiniciar.toSet())
        } catch (t: Throwable) {
            reinicioPendiente = false
            porReiniciar.clear()
            alFallarLimpieza(t)
            return
        }
        if (listo) {
            reinicioPendiente = false
            porReiniciar.clear()
        } else if (!reintentoProgramado) {
            reintentoProgramado = true
            programar(16) {
                reintentoProgramado = false
                intentarReinicio()
            }
        }
    }

    private fun reprogramar(retrasoMs: Int) {
        if (programado.compareAndSet(false, true)) programar(retrasoMs, ::drenar)
    }
}
