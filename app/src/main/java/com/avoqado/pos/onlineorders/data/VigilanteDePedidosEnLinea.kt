package com.avoqado.pos.onlineorders.data

import android.content.Context
import android.media.RingtoneManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import java.io.IOException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "🛎 PedidosEnLinea-Vigilante"

/**
 * Vigilante GLOBAL de «Pedidos en línea»: mientras la app esté en primer plano consulta los pedidos por aceptar, mantiene la
 * insignia y el banner, y hace sonar el aviso cada [REPETIR_SONIDO_MS] hasta que alguien acepte o rechace (o el pedido venza).
 * Se registra en el ciclo de vida del PROCESO (`AvoqadoApp.onCreate`) y, en Windows, en `Arranque.iniciar` (espejo).
 *
 * - Corre con sesión + `orders:read`, **con o sin plan** (R14): el servidor deja atender los pedidos que ya existen aunque el plan
 *   venza. Sin permiso no hay ni una consulta (sólo vuelve a mirar el permiso cada [INTERVALO_MS]).
 * - Cadencia: [INTERVALO_MS] normal; [INTERVALO_TIENDA_APAGADA_MS] si la tienda nunca se encendió y no hay pendientes; y
 *   [INTERVALO_SIN_PLAN_MS] sin plan y sin pedidos abiertos (pendientes + activos, de los conteos del último sondeo bueno).
 * - Nunca manda la cabecera de bandeja (sólo la pantalla visible mantiene abierta la tienda, R13); la de fondo la pone el repositorio
 *   siempre, así un 403 no abre el PIN del gerente cada 10 s.
 * - Sin red es un estado normal: [sinConexion] se enciende, la última lista se conserva (y sigue sonando) y se reintenta.
 * - En segundo plano ([onStop]) se detiene todo: no hay promesa de avisar con la app dormida.
 * - El sonido usa sólo `RingtoneManager...play()`, lo único que el adaptador de WINDOWS tiene.
 */
@Singleton
class VigilanteDePedidosEnLinea internal constructor(
    private val consultar: suspend (status: String, comoBandeja: Boolean) -> Result<Inbox>,
    private val puedeLeer: () -> Boolean,
    private val tienePlan: () -> Boolean,
    private val sonar: () -> Unit,
    private val scope: CoroutineScope,
    /** La sucursal de ahora: un sondeo que vuelve tras un cambio de sucursal se descarta. */
    private val venueActual: () -> String? = { null },
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        repositorio: OnlineOrdersRepository,
        roleManager: RoleManager,
        planManager: PlanManager,
        secureStorage: SecureStorage,
        @ApplicationContext contexto: Context,
    ) : this(
        consultar = { status, comoBandeja -> repositorio.inbox(status, comoBandeja) },
        puedeLeer = {
            secureStorage.venueId != null && secureStorage.accessToken != null &&
                roleManager.canReadOnlineOrders
        },
        tienePlan = { planManager.hasFeature("ONLINE_ORDERING") },
        sonar = { sonarAviso(contexto) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        venueActual = { secureStorage.venueId },
    )

    private val _pendientes = MutableStateFlow<List<OnlineOrder>>(emptyList())

    /** Los pedidos por aceptar del último sondeo bueno (se conserva si falla la red). */
    val pendientes: StateFlow<List<OnlineOrder>> = _pendientes.asStateFlow()

    private val _sinConexion = MutableStateFlow(false)

    /** El último sondeo falló: texto neutro «Sin conexión: no llegan pedidos nuevos», nunca rojo. */
    val sinConexion: StateFlow<Boolean> = _sinConexion.asStateFlow()

    private val _hayPedidosAbiertos = MutableStateFlow(false)

    /** `pendientes + activos > 0` según los conteos del último sondeo bueno (el menú se ve sin plan mientras haya pedidos). */
    val hayPedidosAbiertos: StateFlow<Boolean> = _hayPedidosAbiertos.asStateFlow()

    private val _bandejaSolicitada = MutableStateFlow(false)

    /**
     * Gancho de navegación para el botón «Ver» del banner: el banner llama [pedirAbrirBandeja] y quien tenga la pantalla (MoreMenu /
     * Task A3) observa esto, la abre y llama [bandejaAtendida]. Es un StateFlow y no un evento para que no se pierda si la pantalla
     * todavía no está compuesta cuando se toca «Ver».
     */
    val bandejaSolicitada: StateFlow<Boolean> = _bandejaSolicitada.asStateFlow()

    fun pedirAbrirBandeja() { _bandejaSolicitada.value = true }
    fun bandejaAtendida() { _bandejaSolicitada.value = false }

    /** Pedidos ya silenciados a mano: el sonido sólo vuelve con un pedido que no esté aquí. */
    private val silenciados = MutableStateFlow<Set<String>>(emptySet())

    /** Calla el aviso de los pedidos de ahora; un pedido NUEVO lo vuelve a hacer sonar. */
    fun silenciarHastaNuevo() { silenciados.value = _pendientes.value.map { it.orderId }.toSet() }

    private var ciclo: Job? = null
    private var sonido: Job? = null
    @Volatile private var ultimosConteos: Inbox? = null

    /** La sucursal de la que vienen [pendientes] y los conteos de ahora; null = no hay datos de ninguna. */
    @Volatile private var venueDeLosDatos: String? = null

    /** Suelta lo de una sucursal que ya no es la activa: ni su banner, ni su insignia, ni su sonido. */
    private fun olvidarDatos() {
        ultimosConteos = null
        venueDeLosDatos = null
        _pendientes.value = emptyList()
        silenciados.value = emptySet()
        _hayPedidosAbiertos.value = false
        _sinConexion.value = false
    }

    /** Falso desde cada [onStart] hasta el primer sondeo BUENO: con datos viejos del segundo plano no suena nada. */
    private val sonidoListo = MutableStateFlow(false)

    @Synchronized
    override fun onStart(owner: LifecycleOwner) {
        ciclo?.cancel()
        sonido?.cancel()
        sonidoListo.value = false
        sonido = scope.launch {
            combine(_pendientes, silenciados, sonidoListo) { lista, callados, listo ->
                if (listo) lista.map { it.orderId }.filter { it !in callados }.toSet() else emptySet()
            }
                // Sólo el CONJUNTO de pedidos que suenan reinicia el ciclo: un campo que cambia no da un timbre extra.
                .distinctUntilChanged()
                .collectLatest { ids ->
                    // Se cancela solo en cuanto la lista se vacía (o se silencia): el sonido para en ese instante.
                    while (ids.isNotEmpty() && isActive) {
                        try { sonar() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            Log.w(TAG, "No se pudo reproducir el aviso: ${e.message}")
                        }
                        delay(REPETIR_SONIDO_MS)
                    }
                }
        }
        ciclo = scope.launch {
            while (isActive) {
                sondearUnaVez()
                delay(proximoIntervalo())
            }
        }
    }

    /** Detiene la consulta y el sonido. La última lista queda tal cual para la insignia al volver. */
    @Synchronized
    override fun onStop(owner: LifecycleOwner) {
        ciclo?.cancel(); ciclo = null
        sonido?.cancel(); sonido = null
    }

    private suspend fun sondearUnaVez() {
        if (!puedeLeer()) {
            // Sin sesión o sin orders:read: no se consulta NADA y no queda nada colgado (ni lista, ni sonido, ni aviso).
            olvidarDatos()
            return
        }
        val venue = venueActual()
        // Cambió la sucursal desde el último sondeo bueno: lo de la anterior no se queda ni un instante (aunque este sondeo falle).
        if (venueDeLosDatos != null && venueDeLosDatos != venue) olvidarDatos()
        val resultado = try {
            consultar("pending", false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (venueActual() != venue) {
            Log.d(TAG, "Sondeo de otra sucursal descartado")
            // El banner y la insignia de la sucursal de la que venimos no se pueden quedar colgados (A2, ruling 16).
            olvidarDatos()
            return
        }
        resultado.onSuccess { inbox ->
            ultimosConteos = inbox
            venueDeLosDatos = venue
            val lista = inbox.orders.filter { it.status == OnlineOrderStatus.PENDING_ACCEPTANCE }
            _pendientes.value = lista
            silenciados.value = silenciados.value intersect lista.map { it.orderId }.toSet()
            _hayPedidosAbiertos.value = inbox.pending + inbox.active > 0
            _sinConexion.value = false
            sonidoListo.value = true
        }.onFailure {
            // Se conserva la última lista y se reintenta. «Sin conexión» sólo si de verdad no hay red: un 403, un 5xx, la falta
            // de sesión o un JSON roto no son «sin conexión» (el texto mentiría).
            Log.d(TAG, "Sondeo sin respuesta: ${it.message}")
            if (it is IOException) _sinConexion.value = true
        }
    }

    private fun proximoIntervalo(): Long {
        val ultimo = ultimosConteos
        if (!puedeLeer()) return INTERVALO_MS
        if (!tienePlan() && !_hayPedidosAbiertos.value) return INTERVALO_SIN_PLAN_MS
        if (ultimo != null && ultimo.storeOff && ultimo.pending == 0) return INTERVALO_TIENDA_APAGADA_MS
        return INTERVALO_MS
    }

    companion object {
        const val INTERVALO_MS = 10_000L
        const val INTERVALO_TIENDA_APAGADA_MS = 60_000L
        const val INTERVALO_SIN_PLAN_MS = 5 * 60_000L
        const val REPETIR_SONIDO_MS = 4_000L

        private fun sonarAviso(contexto: Context) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(contexto, uri)?.play()
        }
    }
}
