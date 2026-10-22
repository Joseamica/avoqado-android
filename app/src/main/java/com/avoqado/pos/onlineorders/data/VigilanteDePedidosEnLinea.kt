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
    private var ultimosConteos: Inbox? = null

    @Synchronized
    override fun onStart(owner: LifecycleOwner) {
        ciclo?.cancel()
        sonido?.cancel()
        sonido = scope.launch {
            combine(_pendientes, silenciados) { lista, callados -> lista.any { it.orderId !in callados } }
                .collectLatest { debeSonar ->
                    // Se cancela solo en cuanto la lista se vacía (o se silencia): el sonido para en ese instante.
                    while (debeSonar && isActive) {
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
            ultimosConteos = null
            _pendientes.value = emptyList()
            _sinConexion.value = false
            _hayPedidosAbiertos.value = false
            return
        }
        val resultado = try {
            consultar("pending", false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        resultado.onSuccess { inbox ->
            ultimosConteos = inbox
            val lista = inbox.orders.filter { it.status == OnlineOrderStatus.PENDING_ACCEPTANCE }
            _pendientes.value = lista
            silenciados.value = silenciados.value intersect lista.map { it.orderId }.toSet()
            _hayPedidosAbiertos.value = inbox.pending + inbox.active > 0
            _sinConexion.value = false
        }.onFailure {
            // Sin red (o cualquier fallo): estado normal. Se conserva la última lista y se reintenta en el siguiente ciclo.
            Log.d(TAG, "Sondeo sin respuesta: ${it.message}")
            _sinConexion.value = true
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
