package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.lan.TransporteLan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Etapa 3 del KDS (3.5, D8) — el receptor de comandas por WiFi. Fuera del ViewModel (que vive con «Más» y muere con la
 * pantalla): se activa con `mientrasSeVe()` del Tablero y se apaga al salir; cambiar de estación re-anuncia. Una tablet
 * que eligió estación una vez y hoy está en Cobrar NO acusa: acusar una comanda que nadie ve es peor que el papel.
 */
@Singleton
class ReceptorDeComandas @Inject constructor(
    private val transporte: TransporteLan,
    private val store: KdsTicketsLocalesStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ¿Recibiendo DE VERDAD? Enganchado Y el socket del transporte sirviendo: cambia sola si la red local cae o se reabre
     * (paridad con iOS). La banda de la pantalla dice «recibiendo por el WiFi del local» sólo con esto (D11).
     */
    val activo: StateFlow<Boolean> get() = transporte.receptorActivo

    fun activar(venueId: String, stationId: String) {
        // 🔴 GUARDAR y luego acusar: el transporte sólo acusa si esto devuelve true.
        transporte.activarReceptor(setOf(stationId)) { comanda -> store.unir(comanda) }
        scope.launch { store.purgar(venueId) }
    }

    fun desactivar() {
        transporte.desactivarReceptor()
    }
}
