package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.core.data.lan.ClienteDeComandas
import com.avoqado.pos.core.data.lan.KdsComanda
import com.avoqado.pos.core.data.lan.KdsLanProtocol
import com.avoqado.pos.core.data.lan.TransporteLan
import com.avoqado.pos.kds.data.local.EntregaKdsPendienteEntity
import com.avoqado.pos.kds.data.local.EntregasKdsPendientesDao
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EntregaPorWifi"

/** Una entrega por WiFi: el mensaje y, si la estación es «sólo pantalla», el trabajo de papel que sale si nadie acusa. */
data class EntregaKds(val mensaje: KdsComanda, val trabajoDeRespaldo: TrabajoPendiente?)

/**
 * Etapa 3 del KDS (3.5, D5/D7) — la caja le empuja las comandas a las pantallas del local: GUARDA primero (las que
 * tienen trabajo de respaldo), empuja todas en paralelo con el presupuesto del cliente (≤ 1.5 s en total) y devuelve
 * qué estaciones acusaron. Basta UN acuse por estación. Sin pantallas descubiertas para una estación no se espera nada.
 *
 * Espejo de avoqado-ios: Printing/Services/EntregaPorWifi.swift.
 */
@Singleton
class EntregaPorWifi @Inject constructor(
    private val transporte: TransporteLan,
    private val dao: EntregasKdsPendientesDao,
    private val cliente: ClienteDeComandas,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val deviceId: String get() = transporte.deviceId

    suspend fun entregar(entregas: List<EntregaKds>, ahora: Long = System.currentTimeMillis()): Set<String> {
        // 🔴 ANTES de tocar la red (`todo-funciona-sin-red.md`, pregunta 2): si el proceso muere aquí, al abrir se reintenta o sale en papel.
        for (e in entregas) {
            val trabajo = e.trabajoDeRespaldo ?: continue
            runCatching {
                dao.guardar(
                    EntregaKdsPendienteEntity(
                        sourceKey = e.mensaje.sourceKey, venueId = e.mensaje.venueId, stationId = e.mensaje.stationId,
                        mensajeJson = KdsLanProtocol.encode(e.mensaje),
                        trabajoJson = json.encodeToString(TrabajoPendiente.serializer(), trabajo),
                        creadaEnMillis = ahora,
                    ),
                )
            }.onFailure { Log.w(TAG, "No se pudo guardar la entrega ${e.mensaje.sourceKey}: ${it.message}") }
        }
        val acusadas = coroutineScope {
            entregas.map { e -> async { if (empujar(e.mensaje)) e.mensaje.stationId else null } }.awaitAll()
        }.filterNotNull().toSet()
        for (e in entregas) if (e.mensaje.stationId in acusadas) runCatching { dao.borrar(e.mensaje.sourceKey) }
        Log.d(TAG, "📡 Empujadas ${entregas.size} · acusaron $acusadas")
        return acusadas
    }

    /**
     * Todas las pantallas de la estación en paralelo; basta UN acuse. [esperarPantallasMs] > 0 (replay al abrir la app:
     * el descubrimiento tarda unos segundos) espera a que aparezca alguna antes de rendirse.
     */
    suspend fun empujar(mensaje: KdsComanda, esperarPantallasMs: Long = 0L): Boolean {
        var pantallas = transporte.pantallasDe(mensaje.stationId)
        if (pantallas.isEmpty() && esperarPantallasMs > 0) {
            withTimeoutOrNull(esperarPantallasMs) { transporte.peers.first { peers -> peers.any { mensaje.stationId in it.kdsStations } } }
            pantallas = transporte.pantallasDe(mensaje.stationId)
        }
        if (pantallas.isEmpty()) return false
        return coroutineScope { pantallas.map { p -> async { cliente.entregar(p, mensaje) } }.awaitAll() }.any { it }
    }

    /** Las filas de ESTE despacho ya se decidieron (el papel salió, o se decidió que no hacía falta): se borran. */
    suspend fun cerrar(entregas: List<EntregaKds>) {
        for (e in entregas) runCatching { dao.borrar(e.mensaje.sourceKey) }
    }
}
