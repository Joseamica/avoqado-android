package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El que hace que la comanda salga sola **cuando prendes la impresora**.
 *
 * 🔴 Es lo que pidió el founder, y es la diferencia con lo que había: el reintento automático se
 * rendía tras ~1 minuto (6 intentos). Si el cocinero prendía la impresora a los diez minutos, la
 * comanda seguía ahí pero **alguien tenía que tocar el botón**. Un POS de Windows no tenía este
 * problema porque el *spooler* del sistema insiste solo; nosotros le hablamos directo a la
 * impresora por la red, así que la fila tiene que ser nuestra.
 *
 * ## Por qué es UN solo ejecutor, y no un temporizador suelto
 *
 * 🔴 El reloj, el botón «Volver a imprimir» y cualquier disparo futuro pasan TODOS por
 * [intentarAhora], protegido por un [Mutex]. Sin eso, el reloj y el cajero pueden mandar la misma
 * comanda a la vez y la cocina recibe dos — que es exactamente el defecto que toda esta ronda
 * existe para evitar. Un candado por impresora no bastaría: serializaría los envíos, pero
 * seguirían siendo DOS.
 *
 * ## Lo que NO hace, a propósito
 *
 * - **No comprueba que haya internet.** La impresora vive en la LAN del local: exigir servidor
 *   —como hace `SyncOutbox` con `isServerReachable`— impediría imprimir con la red del negocio
 *   perfectamente sana y el servidor caído.
 * - **No revive nada por su cuenta pasada la vigencia.** Si el aviso caducó (8 h), el almacén ya
 *   devolvió `null` y aquí no hay nada que reintentar: una comanda de ayer no se imprime sola.
 * - **No corre con la app cerrada.** Eso pediría trabajo en segundo plano del sistema, y el
 *   mínimo de Android para tareas periódicas es de 15 minutos — no sirve para esto. Mientras la
 *   app esté abierta (que es como vive un POS de mostrador) el reintento es cada minuto.
 */
@Singleton
class ReplayDeComandasPendientes @Inject constructor(
    private val store: ComandasPendientesStore,
    private val comandaDispatcher: ComandaDispatcher,
) {
    private val candado = Mutex()
    private var job: Job? = null

    /** Arranca el reloj. Idempotente: llamarlo dos veces no crea dos relojes. */
    fun iniciar(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(INTERVALO_MS)
                runCatching { intentarAhora() }
                    .onFailure { Log.w(TAG, "El reintento periódico tropezó: ${it.message}") }
            }
        }
    }

    fun detener() {
        job?.cancel()
        job = null
    }

    /**
     * Intenta UNA vez la comanda pendiente, si la hay.
     *
     * @return el estado del intento, o `null` si no había nada que mandar (o si otro intento
     *   estaba en curso — el candado no espera: si el reloj ya está adentro, el toque del cajero
     *   no encola un segundo envío).
     */
    suspend fun intentarAhora(): EstadoDeComanda? {
        if (!candado.tryLock()) {
            Log.d(TAG, "Ya hay un intento en curso — este se omite en vez de duplicar")
            return null
        }
        try {
            val trabajo = store.pendiente.value?.trabajo ?: return null
            Log.d(TAG, "Reintentando la comanda del pedido ${trabajo.orderNumber}")
            val estado = comandaDispatcher.reintentar(trabajo)
            if (estado is EstadoDeComanda.Salio) {
                // 🔴 Se limpia SÓLO cuando de verdad salió. Un `NoSalio` deja el pendiente donde
                // estaba para que el siguiente tic lo vuelva a intentar.
                store.limpiar()
                Log.d(TAG, "✅ La comanda del pedido ${trabajo.orderNumber} por fin salió")
            }
            return estado
        } finally {
            candado.unlock()
        }
    }

    private companion object {
        const val TAG = "ReplayComandas"

        /**
         * Un minuto. Es un intento de conexión TCP contra una impresora de la LAN: barato.
         * Más seguido no hace que la impresora conteste antes; más espaciado hace que el cajero
         * note la espera después de prenderla.
         */
        const val INTERVALO_MS = 60_000L
    }
}
