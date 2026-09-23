package com.avoqado.pos.inventory.waste.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Las sucursales donde el SERVIDOR dijo que la merma no está en el plan (un 403 con
 * `featureCode`, spec §5).
 *
 * 🔴 Es INFORMATIVO: cambia lo que se muestra (el subtítulo en «Más», el aviso en la pantalla,
 * el mensaje al guardar), nunca impide mandar. Si apagara el botón, trabaría justo la petición
 * que podría levantarlo — por eso el plan lo decide el servidor en cada envío.
 *
 * Va POR SUCURSAL y en disco: un bloqueo pegado de otra sucursal escondería una función que sí
 * está pagada aquí, y uno que se pierde al reiniciar dejaría de avisar.
 *
 * Sólo lo levanta [revalidar] (preguntarle al servidor). Un 201 del drenado NO: el servidor
 * recupera un folio ya aplicado ANTES del candado de plan, así que ese 201 no prueba que el
 * plan esté activo.
 */
@Singleton
class BloqueoDeMermaPorPlan @Inject constructor(
    private val secureStorage: SecureStorage,
    private val red: ConnectivityMonitor,
    private val catalogo: CatalogoDeMerma,
) {
    private val _venues = MutableStateFlow(secureStorage.venuesConMermaBloqueada)
    val venues: StateFlow<Set<String>> = _venues.asStateFlow()

    fun estaBloqueado(venueId: String?): Boolean = venueId != null && venueId in _venues.value

    fun bloquear(venueId: String) = cambiar { it + venueId }

    fun levantar(venueId: String) = cambiar { it - venueId }

    /**
     * Le pregunta al servidor, y sólo con red. Sí → se levanta; no → queda bloqueado; sin
     * respuesta clara (sin red, 5xx, un 403 que no es de plan) → se conserva: nunca se levanta
     * a ciegas.
     */
    suspend fun revalidar(venueId: String) {
        if (!red.isConnected.value || !red.isServerReachable.value) return
        when (catalogo.consultarPlan(venueId)) {
            true -> levantar(venueId)
            false -> bloquear(venueId)
            null -> Unit
        }
    }

    @Synchronized
    private fun cambiar(cambio: (Set<String>) -> Set<String>) {
        val nuevo = cambio(_venues.value)
        if (nuevo == _venues.value) return
        secureStorage.venuesConMermaBloqueada = nuevo
        _venues.value = nuevo
    }
}
