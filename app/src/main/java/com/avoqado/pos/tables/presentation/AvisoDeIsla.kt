package com.avoqado.pos.tables.presentation

import com.avoqado.pos.core.data.lan.CajasRecordadas
import com.avoqado.pos.core.data.lan.LanPeer
import com.avoqado.pos.core.data.local.PayloadCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.serialization.json.Json

/**
 * D6 (hz:e5ee98bc:D6) — la línea del plano «Este aparato no ve a las otras cajas por el WiFi». En el ensayo del 11-nov la D3 perdió a
 * la N86 por el WiFi y entró a una mesa con el mesero adentro sin ningún aviso: el restaurante creía que el Hub lo protegía.
 *
 * Es un dato DERIVADO: no se guarda, no se encola y ningún botón lo lee (el Hub degrada, nunca bloquea: regla §3.1 del hub). Sólo LEE
 * lo del D12 (`core/data/lan/`, de otra conversación): los peers del transporte ([com.avoqado.pos.core.data.lan.LanDiscoveryPort.peers])
 * y el disco de [CajasRecordadas]. `hubAvailable` del coordinador no sirve: incluye a este mismo aparato, así que vale «sí» estando solo.
 * Detalle y mapa de entradas: `docs/audit/2026-11-12-aviso-isla-en-el-plano/promesa.md` (workspace).
 */
internal object AvisoDeIsla {
    const val TEXTO = "Este aparato no ve a las otras cajas por el WiFi: cada uno trabaja por separado"

    /** Al abrir la app o el plano, NSD y el `quien` por TCP tardan unos segundos en encontrar a las demás: sin esto, la línea parpadea. */
    const val GRACIA_MS = 10_000L

    /** Vuelve a revisar el plan y el disco (olvido de 24 h) aunque la lista de cajas no cambie. */
    const val REVISION_MS = 60_000L

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * El negocio tiene el Hub, falta una caja que este aparato recuerda y no ve a ninguna que coordine mesas. [peers] incluye a este
     * mismo aparato ([yo]); una que no contesta mesas (`hub=0`) no protege. Codex r1 #1: una recordada a la vista no FALTA (una pantalla
     * de cocina con app vieja que el D12 recuerda no es «otra caja» en un negocio de una sola caja).
     */
    fun mostrar(hubDelNegocio: Boolean, yo: String, peers: List<LanPeer>, recordadas: Set<String>): Boolean =
        hubDelNegocio &&
            peers.none { it.deviceId != yo && it.sirveLeases } &&
            recordadas.any { did -> did != yo && peers.none { it.deviceId == did } }

    /**
     * Las cajas de [venueId] que el D12 recuerda (menos de [CajasRecordadas.OLVIDAR_MS]), sin este aparato. Si el disco no se puede
     * leer: ninguna, o sea sin línea (es un aviso, nunca un freno).
     */
    suspend fun recordadas(cache: PayloadCache, venueId: String, yo: String, ahora: Long): Set<String> {
        val guardado = try {
            cache.loadDurable(PayloadCache.TYPE_CAJAS_RECORDADAS, venueId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptySet()
        } ?: return emptySet()
        val cajas = runCatching { json.decodeFromString(CajasRecordadas.Disco.serializer(), guardado.json).cajas }.getOrNull() ?: return emptySet()
        return cajas.filter { (did, c) -> did != yo && c.venue == venueId && ahora - c.vistoEn < CajasRecordadas.OLVIDAR_MS }.keys
    }

    /**
     * Se quita al instante; sale sólo si la condición dura [GRACIA_MS] seguidos. Se recalcula al cambiar [peers] y cada [REVISION_MS].
     * Codex r1 #4: se compara la CONDICIÓN antes de esperar; la revisión del minuto o una lista distinta con la misma condición no
     * reinician la espera de una ausencia que ya corría.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun flujo(
        peers: Flow<List<LanPeer>>,
        yo: () -> String,
        hubDelNegocio: () -> Boolean,
        recordadas: suspend () -> Set<String>,
    ): Flow<Boolean> = combine(peers, tic()) { p, _ -> p }
        .map { p -> hubDelNegocio() && mostrar(true, yo(), p, recordadas()) }
        .distinctUntilChanged()
        .transformLatest { aislado ->
            if (aislado) delay(GRACIA_MS)
            emit(aislado)
        }
        .distinctUntilChanged()

    private fun tic(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(REVISION_MS)
        }
    }
}
