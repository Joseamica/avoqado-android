package com.avoqado.pos.kds.data

import android.util.Log
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
data class PantallaDesconectada(val id: String = "", val name: String = "")

@Serializable
data class PantallasDesconectadasData(
    /** `false` = el negocio no tiene pantallas de cocina (casilla y plan): se pregunta más despacio. */
    val hayPantallas: Boolean = false,
    /** `false` justo después de que el servidor arrancó (Codex r5 #4): todavía no da a ninguna por desconectada. */
    val completo: Boolean = false,
    val desconectadas: List<PantallaDesconectada> = emptyList(),
    /** Ids de las estaciones con pantalla efectiva HOY según el servidor; `null` = no lo dijo (error, servidor viejo). */
    val pantallas: List<String>? = null,
)

/** `GET kds/pantallas-desconectadas` (`kitchenDisplayStations.ts` → `pantallasDesconectadas`). */
@Serializable
data class PantallasDesconectadasResponse(val success: Boolean = true, val data: PantallasDesconectadasData = PantallasDesconectadasData())

/**
 * D6 de Flautas (20-nov, opción B del founder: «como Square y Toast», simplificada tras Codex r7): qué pantallas de cocina NO
 * están conectadas a internet. Lo decide SÓLO el servidor, con SU configuración (siempre al día) y la última lectura de cada
 * tablero; aquí sólo se guarda la lista de nombres que él manda, para la franja fija («La pantalla de cocina de X no está
 * conectada a internet»). No se mezcla con la config de la caja ni con los acuses por WiFi (eso es la racha, D11).
 *
 * Sin red, con error, sin permiso, pasados [TOPE_MS] o con un servidor viejo que no conoce la ruta: «no se sabe» y la lista
 * queda vacía (no se afirma nada). Sólo lee: no hay nada que guardar antes de la red.
 */
@Singleton
class EstadoDePantallasDeCocina @Inject constructor(
    private val apiService: ApiService,
) {
    private val _sinInternet = MutableStateFlow<List<String>>(emptyList())

    /** Nombres de las estaciones cuya pantalla el servidor no ve conectada; vacío si todo bien o si no se sabe. */
    val sinInternet: StateFlow<List<String>> = _sinInternet.asStateFlow()

    /** Las estaciones con pantalla de cocina HOY, según el último sondeo; `null` = no se sabe. */
    @Volatile
    private var conPantalla: Set<String>? = null

    /**
     * ¿El servidor confirmó, en el último sondeo, que [stationId] tiene pantalla de cocina? (Codex r8 #3). La copia de la
     * configuración de la caja puede estar vieja (el dueño apagó la pantalla en el panel, y las rondas con preparación usan
     * una config congelada): la caja sólo calla el «No salió» de una estación que el servidor sigue teniendo como pantalla.
     * Sin respuesta, `false`: ante la duda se avisa como siempre.
     */
    fun esPantallaDelServidor(stationId: String): Boolean = conPantalla?.contains(stationId) == true

    private var trabajo: Job? = null
    private var venueDelTrabajo: String? = null

    /** Idempotente por sucursal; con otra, reinicia y olvida lo de la anterior. Vive en [scope] (el de la app). */
    @Synchronized
    fun iniciar(scope: CoroutineScope, venueId: String) {
        if (venueDelTrabajo == venueId && trabajo?.isActive == true) return
        trabajo?.cancel()
        venueDelTrabajo = venueId
        _sinInternet.value = emptyList()
        conPantalla = null
        trabajo = scope.launch {
            while (true) {
                val revision = revisar(venueId)
                ensureActive() // una respuesta que llegó tras cambiar de sucursal o cerrar sesión no se publica
                _sinInternet.value = revision?.nombres.orEmpty()
                conPantalla = revision?.pantallas
                delay(esperaTras(revision))
            }
        }
    }

    @Synchronized
    fun detener() {
        trabajo?.cancel()
        trabajo = null
        venueDelTrabajo = null
        _sinInternet.value = emptyList()
        conPantalla = null
    }

    /**
     * Lo que dijo el servidor. [nombres] vacío si no hay ninguna desconectada o si el servidor aún no sabe (`completo`);
     * [pantallas] = las estaciones con pantalla hoy, o `null` si no lo dijo.
     */
    internal data class Revision(val hayPantallas: Boolean, val nombres: List<String>, val pantallas: Set<String>? = null)

    /** `null` = no se sabe (sin red, error, sin permiso, tope, servidor viejo). Expuesta para las pruebas. */
    internal suspend fun revisar(venueId: String): Revision? {
        val data = try {
            withTimeoutOrNull(TOPE_MS) { apiService.pantallasDesconectadas(venueId).data }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "No se supo qué pantallas de cocina están conectadas: ${e.message}")
            null
        } ?: return null
        val nombres = if (data.completo) data.desconectadas.map { it.name }.filter { it.isNotBlank() } else emptyList()
        return Revision(data.hayPantallas, nombres, data.pantallas?.toSet())
    }

    /** Cuánto esperar para volver a preguntar, según la última respuesta. */
    internal fun esperaTras(revision: Revision?): Long = when {
        revision == null -> ESPERA_SIN_RESPUESTA_MS
        revision.hayPantallas -> INTERVALO_MS
        else -> INTERVALO_SIN_PANTALLAS_MS
    }

    companion object {
        /** Con pantallas: una que se cierra sale en la franja en unos 2 min (90 s de ventana del servidor + esto). */
        const val INTERVALO_MS = 20_000L
        /** Sin pantallas de cocina no hay nada que vigilar: se vuelve a mirar por si el negocio prende una. */
        const val INTERVALO_SIN_PANTALLAS_MS = 5 * 60_000L
        /** Tras un error (sin red, sin permiso, servidor viejo): sin insistir cada 20 s. */
        const val ESPERA_SIN_RESPUESTA_MS = 60_000L
        const val TOPE_MS = 5_000L
    }
}
