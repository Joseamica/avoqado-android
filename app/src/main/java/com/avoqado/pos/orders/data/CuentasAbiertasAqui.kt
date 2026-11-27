package com.avoqado.pos.orders.data

import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * D12 (Codex r1 #6): «la abrió aquí». Por sucursal, las cuentas que creó ESTE aparato (`orderId` → cuándo se marcó). Es la prueba
 * de que esta caja abrió la cuenta y puede cobrarla en efectivo sin red; sin marca (la abrió otra caja, una versión anterior, o
 * el disco falló) la cuenta queda en gris.
 * - Quien crea la cuenta marca con el id del servidor ANTES de borrar su evidencia local (p. ej. antes de
 *   `PayLaterQueue.cerrarAceptada`): `PayLaterQueue.mandar`/`cerrarLlave`, `OrderRepository.createOrder` y `PaymentSyncService`.
 * - No caduca mientras la cuenta siga en la copia ([CopiaDeCuentas]); al marcar se podan sólo las de más de 7 días que ya no
 *   están en ella.
 * - [marcar] nunca lanza y, una vez confirmada la cuenta por el servidor, termina aunque cancelen a quien la llamó.
 */
@Singleton
class CuentasAbiertasAqui @Inject constructor(private val cache: PayloadCache, private val copia: CopiaDeCuentas) {
    internal var ahora: () -> Long = System::currentTimeMillis
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun marcar(venueId: String, orderId: String): Unit = withContext(NonCancellable) {
        try {
            guardar(PayloadCache.TYPE_CUENTAS_ABIERTAS_AQUI, venueId, orderId)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ no se pudo marcar $orderId en $venueId; queda en gris: ${e.message}")
        }
    }

    /**
     * D39 (Codex r5 #1, decisión «sin red no se cobra una cuenta con puntos»): se marca ANTES de mandar el canje. Con la marca, la copia
     * sin red no cobra la cuenta: su saldo pudo ser el de antes del canje (el canje entró y la red se cayó antes de releer). false = no se
     * pudo guardar, y quien canjea no manda nada.
     */
    suspend fun marcarCanje(venueId: String, orderId: String): Boolean = withContext(NonCancellable) {
        try {
            guardar(PayloadCache.TYPE_CUENTAS_CON_CANJE_AQUI, venueId, orderId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ no se pudo marcar el canje de $orderId en $venueId: ${e.message}")
            false
        }
    }

    /** Si el disco falla, true: sin saber, la cuenta no se cobra sin red. */
    suspend fun tieneCanje(venueId: String, orderId: String): Boolean = try {
        orderId in leerDelDisco(venueId, PayloadCache.TYPE_CUENTAS_CON_CANJE_AQUI)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ no se pudieron leer los canjes de $venueId: ${e.message}")
        true
    }

    private suspend fun guardar(tipo: String, venueId: String, orderId: String) = mutex.withLock {
        // Si lo guardado no se puede leer, no se escribe encima: borraría las demás marcas.
        val marcas = leerDelDisco(venueId, tipo)
        val enLaCopia = copia.cuentas(venueId).mapTo(HashSet()) { it.cuenta.id }
        val desde = ahora() - PODA_MS
        val quedan = marcas.filter { (id, marcadaEn) -> marcadaEn >= desde || id in enLaCopia } + (orderId to ahora())
        cache.saveDurable(tipo, venueId, json.encodeToString(quedan))
    }

    /** Si el disco falla, false: sin prueba, la cuenta queda en gris. */
    suspend fun laAbrioAqui(venueId: String, orderId: String): Boolean = try {
        orderId in leerDelDisco(venueId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ no se pudieron leer las marcas de $venueId: ${e.message}")
        false
    }

    /** D12 · Opción D: todas las que abrió esta caja en [venueId]. LANZA si el disco falla (quien pregunta no contesta por ninguna). */
    suspend fun todas(venueId: String): Set<String> = leerDelDisco(venueId).keys

    /**
     * LANZA si el disco falla. Un JSON ilegible de «abiertas aquí» cuenta como «sin marcas» (sin prueba, la cuenta queda en gris). D39
     * (Codex r9 #2): uno ilegible de canjes NO se sabe y también LANZA: así [tieneCanje] dice que sí (sin red no se cobra) y [marcarCanje]
     * no escribe encima de lo ilegible (borraría las marcas de las demás cuentas).
     */
    private suspend fun leerDelDisco(venueId: String, tipo: String = PayloadCache.TYPE_CUENTAS_ABIERTAS_AQUI): Map<String, Long> {
        val guardado = cache.loadDurable(tipo, venueId) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Long>>(guardado.json) }.getOrElse {
            Log.w(TAG, "⚠️ marcas ilegibles de $venueId ($tipo): ${it.message}")
            if (tipo == PayloadCache.TYPE_CUENTAS_CON_CANJE_AQUI) throw IllegalStateException("marcas de canje ilegibles", it)
            emptyMap()
        }
    }

    private companion object {
        const val TAG = "CuentasAbiertasAqui"
        const val PODA_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
