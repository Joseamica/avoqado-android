package com.avoqado.pos.kds.data

import android.util.Log
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.printing.routing.PrintConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PantallasDeCocina"

@Serializable
data class PantallasConectadasData(
    val conectadas: List<String> = emptyList(),
    /** `false` justo después de que el servidor arrancó (Codex r5 #4): lo que falta en [conectadas] no se sabe todavía. */
    val completo: Boolean = false,
)

/** `GET kds/pantallas-conectadas` (`kds.mobile.controller.ts` → `listarPantallasConectadas`). */
@Serializable
data class PantallasConectadasResponse(val success: Boolean = true, val data: PantallasConectadasData = PantallasConectadasData())

/**
 * D6 de Flautas (20-nov, opción B del founder: «como Square y Toast»): qué pantallas de cocina NO están conectadas. La caja
 * ya no avisa «No salió» en cada orden cuando la venta está en el servidor y la estación es sólo pantalla; en cambio, la
 * franja fija dice «La pantalla de cocina de X no está conectada» si esa pantalla no le ha pedido su tablero al servidor en
 * 90 s (el servidor lo lleva en memoria, `pantallasVivas.ts`) ni acusó su última entrega por WiFi (eso lo resta `AppState`).
 *
 * Cada [INTERVALO_MS], con sesión y sólo si el negocio tiene estaciones con pantalla. Sin red, con error, pasados [TOPE_MS]
 * o con un servidor viejo que no conoce la ruta: «no se sabe», y no se afirma nada (sin red ya habla la banda de «Sin
 * conexión»). Sólo lee: no hay nada que guardar antes de la red.
 */
@Singleton
class EstadoDePantallasDeCocina @Inject constructor(
    private val apiService: ApiService,
    private val printConfigRepository: PrintConfigRepository,
    private val transporteLan: com.avoqado.pos.core.data.lan.TransporteLan,
) {
    private val _desconectadas = MutableStateFlow<Set<String>>(emptySet())

    /** Ids de estación (activas y con pantalla) cuya pantalla el servidor no ha visto; vacío si todo bien o si no se sabe. */
    val desconectadas: StateFlow<Set<String>> = _desconectadas.asStateFlow()

    private var trabajo: Job? = null
    private var venueDelTrabajo: String? = null

    /** Idempotente por sucursal; con otra, reinicia. Vive en [scope] (el de la app). */
    @Synchronized
    fun iniciar(scope: CoroutineScope, venueId: String) {
        if (venueDelTrabajo == venueId && trabajo?.isActive == true) return
        trabajo?.cancel()
        venueDelTrabajo = venueId
        _desconectadas.value = emptySet()
        trabajo = scope.launch {
            while (true) {
                _desconectadas.value = revisar(venueId) ?: emptySet()
                delay(INTERVALO_MS)
            }
        }
    }

    @Synchronized
    fun detener() {
        trabajo?.cancel()
        trabajo = null
        venueDelTrabajo = null
        _desconectadas.value = emptySet()
    }

    /** Las estaciones con pantalla que NO están conectadas, o `null` si no se sabe. Expuesta para las pruebas. */
    internal suspend fun revisar(venueId: String, ahora: Long = System.currentTimeMillis()): Set<String>? {
        // Codex r6 #4: la config de ESTA sucursal (al cambiar de sucursal la de memoria puede ser la anterior).
        val conPantalla = printConfigRepository.savedConfig(venueId).stations.filter { it.active && it.hasKitchenDisplay }.map { it.id }
        if (conPantalla.isEmpty()) return emptySet()
        val respuesta = try {
            withTimeoutOrNull(TOPE_MS) { apiService.pantallasConectadas(venueId).data }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "No se supo qué pantallas están conectadas: ${e.message}")
            null
        } ?: return null
        // Recién arrancado el servidor no sabe aún de las que faltan: no se afirma nada (un servidor viejo nunca dice «completo»).
        if (!respuesta.completo) return null
        // Codex r5 #2 / r6 #1: la que acusó por WiFi hace poco está recibiendo aunque el servidor no la vea; un acuse viejo no cuenta.
        val conectadas = respuesta.conectadas.toSet() + transporteLan.racha.acusaronDesde(ahora - ACUSE_WIFI_VIGENTE_MS)
        return conPantalla.filterNot { it in conectadas }.toSet()
    }

    companion object {
        /** Cada cuánto se pregunta: una pantalla que se cierra sale en la franja en unos 2 min (90 s de ventana + esto). */
        const val INTERVALO_MS = 20_000L
        const val TOPE_MS = 5_000L
        /**
         * Un acuse por WiFi cuenta como «está recibiendo» por esto. Llegan sólo cuando hay comandas: con más, una pantalla
         * sin internet pero con WiFi saldría como desconectada en un rato sin pedidos; con menos, una que se apagó tras su
         * último acuse se taparía más tiempo. Acotado (Codex r6 #1: antes, para siempre).
         */
        const val ACUSE_WIFI_VIGENTE_MS = 5 * 60_000L
    }
}
