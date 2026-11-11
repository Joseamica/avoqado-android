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
            mutex.withLock {
                // Si lo guardado no se puede leer, no se escribe encima: borraría las demás marcas.
                val marcas = leerDelDisco(venueId)
                val enLaCopia = copia.cuentas(venueId).mapTo(HashSet()) { it.cuenta.id }
                val desde = ahora() - PODA_MS
                val quedan = marcas.filter { (id, marcadaEn) -> marcadaEn >= desde || id in enLaCopia } + (orderId to ahora())
                cache.saveDurable(PayloadCache.TYPE_CUENTAS_ABIERTAS_AQUI, venueId, json.encodeToString(quedan))
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ no se pudo marcar $orderId en $venueId; queda en gris: ${e.message}")
        }
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

    /** LANZA si el disco falla; un JSON ilegible cuenta como «sin marcas» (no hay nada que conservar). */
    private suspend fun leerDelDisco(venueId: String): Map<String, Long> {
        val guardado = cache.loadDurable(PayloadCache.TYPE_CUENTAS_ABIERTAS_AQUI, venueId) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Long>>(guardado.json) }
            .getOrElse { Log.w(TAG, "⚠️ marcas ilegibles de $venueId: ${it.message}"); emptyMap() }
    }

    private companion object {
        const val TAG = "CuentasAbiertasAqui"
        const val PODA_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
