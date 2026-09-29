package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.core.data.lan.ClienteDeComandas
import com.avoqado.pos.core.data.lan.KdsComanda
import com.avoqado.pos.core.data.lan.KdsLanProtocol
import com.avoqado.pos.core.data.lan.TransporteLan
import com.avoqado.pos.kds.data.local.EntregaKdsPendienteEntity
import com.avoqado.pos.kds.data.local.EntregasKdsPendientesDao
import com.avoqado.pos.printing.routing.TicketPlan
import kotlinx.coroutines.CancellationException
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
data class EntregaKds(val mensaje: KdsComanda, val trabajoDeRespaldo: TrabajoPendiente?) {
    /** La llave de su fila (ronda 2, N1), o `null` si no se guarda (impresora + pantalla: sin trabajo de respaldo). */
    val entregaId: String? get() = trabajoDeRespaldo?.let { entregaIdDe(mensaje.sourceKey, it.planes) }
}

/**
 * Ronda 2 (N1): UNA fila por PLAN — `<folio>|<orderItemIds del plan, ordenados, separados por coma>`. Los cursos de una
 * ronda comparten folio (`round:<llave>:<estación>`); con el folio de llave, el `REPLACE` del curso 2 pisaba la fila
 * aún sin decidir del curso 1, y si los dos papeles fallaban el curso 1 no quedaba en ningún lado.
 */
internal fun entregaIdDe(sourceKey: String, planes: List<TicketPlan>): String =
    sourceKey + "|" + planes.flatMap { p -> p.lines.flatMap { it.orderItemIds } }.sorted().joinToString(",")

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
            sinTumbar("No se pudo guardar la entrega ${e.mensaje.sourceKey}") {
                dao.guardar(
                    EntregaKdsPendienteEntity(
                        entregaId = entregaIdDe(e.mensaje.sourceKey, trabajo.planes),
                        sourceKey = e.mensaje.sourceKey, venueId = e.mensaje.venueId, stationId = e.mensaje.stationId,
                        mensajeJson = KdsLanProtocol.encode(e.mensaje),
                        trabajoJson = json.encodeToString(TrabajoPendiente.serializer(), trabajo),
                        creadaEnMillis = ahora,
                    ),
                )
            }
        }
        val acusadas = coroutineScope {
            entregas.map { e -> async { if (empujar(e.mensaje)) e.mensaje.stationId else null } }.awaitAll()
        }.filterNotNull().toSet()
        for (e in entregas) if (e.mensaje.stationId in acusadas) borrar(e)
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
        for (e in entregas) borrar(e)
    }

    /**
     * Ronda 2 (N2): el despacho vivo terminó y el papel de estas NO salió — se quedan, SOLTADAS con su hora. El reloj de
     * [ReplayDeEntregasKds] las toma en su siguiente tic, sin esperar a que se reabra la app.
     */
    suspend fun soltar(entregas: List<EntregaKds>, ahora: Long = System.currentTimeMillis()) {
        for (e in entregas) {
            val id = e.entregaId ?: continue
            sinTumbar("No se pudo soltar la entrega $id") { dao.soltar(id, ahora) }
        }
    }

    private suspend fun borrar(e: EntregaKds) {
        val id = e.entregaId ?: return // sin trabajo de respaldo nunca se guardó
        sinTumbar("No se pudo borrar la entrega $id") { dao.borrar(id) }
    }

    /**
     * Ronda 1 (I2): el papel de respaldo que sale DESPUÉS del despacho («Volver a imprimir», el reloj de la libreta, el
     * replay) cierra SU fila. El trabajo no lleva el folio, así que se reconoce por venue + orden + plan EXACTO: dos rondas
     * de la misma mesa comparten orden y estación pero no renglones, y no se confunden.
     */
    suspend fun cerrarPorPapel(venueId: String, orderNumber: String, planes: List<TicketPlan>) {
        if (planes.isEmpty()) return
        sinTumbar("No se pudieron cerrar las entregas del papel de $orderNumber") {
            for (fila in dao.delVenue(venueId)) {
                val trabajo = runCatching { json.decodeFromString(TrabajoPendiente.serializer(), fila.trabajoJson) }.getOrNull() ?: continue
                if (trabajo.orderNumber == orderNumber && trabajo.planes.isNotEmpty() && trabajo.planes.all { it in planes }) {
                    dao.borrar(fila.entregaId)
                }
            }
        }
    }

    /** M6 de la revisión: `runCatching` se tragaba la cancelación de las llamadas suspendidas a la base. */
    private inline fun sinTumbar(que: String, bloque: () -> Unit) {
        try {
            bloque()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$que: ${e.message}")
        }
    }
}
