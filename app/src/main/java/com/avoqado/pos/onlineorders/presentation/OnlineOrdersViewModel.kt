package com.avoqado.pos.onlineorders.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.data.VentanaALaVista
import com.avoqado.pos.onlineorders.data.VigilanteDePedidosEnLinea
import com.avoqado.pos.onlineorders.domain.AccionResultado
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "🛎 PedidosEnLinea-Bandeja"

/** Las cuatro secciones de la bandeja. «Hoy» es todo lo que ya no está abierto (cerrado, rechazado, vencido, cancelado). */
data class SeccionesBandeja(
    val porAceptar: List<OnlineOrder> = emptyList(),
    val enPreparacion: List<OnlineOrder> = emptyList(),
    val listos: List<OnlineOrder> = emptyList(),
    val hoy: List<OnlineOrder> = emptyList(),
) {
    val vacia: Boolean get() = porAceptar.isEmpty() && enPreparacion.isEmpty() && listos.isEmpty() && hoy.isEmpty()
}

/** Cómo se ve la entrada «Pedidos en línea» del menú «Más». */
enum class EntradaPedidosEnLinea { OCULTA, TEASER, ABIERTA }

/**
 * R14: la entrada depende de `orders:read`, NO del plan: el servidor deja atender los pedidos que ya existen aunque el plan venza.
 * Sin plan **y** sin pedidos abiertos se queda a la vista con su candado (teaser, como Reservaciones). PURA.
 */
fun entradaDePedidosEnLinea(puedeLeer: Boolean, tienePlan: Boolean, hayPedidosAbiertos: Boolean): EntradaPedidosEnLinea = when {
    !puedeLeer -> EntradaPedidosEnLinea.OCULTA
    tienePlan || hayPedidosAbiertos -> EntradaPedidosEnLinea.ABIERTA
    else -> EntradaPedidosEnLinea.TEASER
}

/** «Ver» del banner: la solicitud abre la pantalla salvo que la persona no pueda ni leer pedidos. PURA. */
fun debeAbrirBandeja(solicitada: Boolean, entrada: EntradaPedidosEnLinea): Boolean =
    solicitada && entrada != EntradaPedidosEnLinea.OCULTA

/** Dinero para pintar: `$185.50`, siempre con el formato del negocio (no el del idioma del aparato). */
fun formatoPesos(centavos: Long): String = formatMoney(BigDecimal.valueOf(centavos, 2).toDouble())

private val FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm")

/** La hora en la zona del NEGOCIO, no la del reloj del aparato. */
fun horaDelPedido(pedido: OnlineOrder): String = VenueTimeZone.at(pedido.createdAt).format(FORMATO_HORA)

/**
 * Task A3: la pantalla «Pedidos en línea».
 *
 * - Mientras está a la vista ([mientrasSeVe]) lee `status=all` cada 10 s **con** la cabecera de latido (R13) si la ventana se ve
 *   ([VentanaALaVista]); minimizada en WINDOWS ⇒ sin cabecera. Cerrada la pantalla, no consulta nada.
 * - Aceptar, rechazar, «Listo», «Entregado», cancelar y pausar son sólo en línea y NO se encolan. Tras CADA acción relee la
 *   bandeja: nunca actualiza a ciegas. El 409 con el estado que se quería ya llega como `Hecho` (A1): sin error.
 * - Sin red es un estado normal: texto neutro, la última lista se conserva.
 * - Los permisos (nombres EXACTOS del servidor) esconden los botones; la acción además se niega aquí por si algo la llama. La
 *   pausa NO se niega: sin `online-orders:pause` el servidor responde 403 `overridable` y el interceptor pide el PIN del gerente.
 */
@HiltViewModel
class OnlineOrdersViewModel internal constructor(
    private val repositorio: OnlineOrdersRepository,
    private val permisoActualizar: () -> Boolean,
    private val permisoCancelar: () -> Boolean,
    private val tienePlan: () -> Boolean,
    private val hayPedidosAbiertos: () -> Boolean,
    private val ventanaALaVista: StateFlow<Boolean> = VentanaALaVista.aLaVista,
) : ViewModel() {

    @Inject
    constructor(
        repositorio: OnlineOrdersRepository,
        roleManager: RoleManager,
        planManager: PlanManager,
        vigilante: VigilanteDePedidosEnLinea,
    ) : this(
        repositorio = repositorio,
        permisoActualizar = { roleManager.canOperateOnlineOrders },
        permisoCancelar = { roleManager.canCancelOnlineOrders },
        tienePlan = { planManager.hasFeature("ONLINE_ORDERING") },
        hayPedidosAbiertos = { vigilante.hayPedidosAbiertos.value },
    )

    private val _secciones = MutableStateFlow(SeccionesBandeja())
    val secciones: StateFlow<SeccionesBandeja> = _secciones.asStateFlow()

    private val _sinConexion = MutableStateFlow(false)

    /** La última lectura falló por falta de red: banner neutro «Sin conexión: no llegan pedidos nuevos». */
    val sinConexion: StateFlow<Boolean> = _sinConexion.asStateFlow()

    private val _aviso = MutableStateFlow<String?>(null)

    /** Texto neutro de lo último que pasó con una acción (ya no disponible, reintenta, error del servidor). */
    val aviso: StateFlow<String?> = _aviso.asStateFlow()

    private val _ocupados = MutableStateFlow<Set<String>>(emptySet())

    /** Pedidos con una acción en vuelo: sus botones se apagan y un segundo toque no hace nada. */
    val ocupados: StateFlow<Set<String>> = _ocupados.asStateFlow()

    private val _hayMas = MutableStateFlow(false)

    /** Quedan activos (en preparación / listos) sin traer: «Cargar más». */
    val hayMas: StateFlow<Boolean> = _hayMas.asStateFlow()

    private val _cargandoMas = MutableStateFlow(false)
    val cargandoMas: StateFlow<Boolean> = _cargandoMas.asStateFlow()

    private val _hoyRecortado = MutableStateFlow(false)

    /** El servidor recortó la lista: en «Hoy» va la línea «Se muestran los últimos 50». */
    val hoyRecortado: StateFlow<Boolean> = _hoyRecortado.asStateFlow()

    private val _cargando = MutableStateFlow(true)

    /** Todavía no llegó la primera lectura de esta apertura. */
    val cargando: StateFlow<Boolean> = _cargando.asStateFlow()

    val puedeActualizar: Boolean get() = permisoActualizar()
    val puedeCancelar: Boolean get() = permisoCancelar()

    /** Sin plan y sin pedidos abiertos la pantalla es sólo el teaser: no consulta nada. */
    val esTeaser: Boolean get() = !tienePlan() && !hayPedidosAbiertos()

    private val mutex = Mutex()

    /** Cuántas páginas extra de activos se cargaron con «Cargar más»; cada lectura las vuelve a recorrer para no tirarlas. */
    private var paginasExtra = 0

    /**
     * Lo llama la pantalla mientras está a la vista (`repeatOnLifecycle(STARTED)`): lee la bandeja y repite cada
     * [INTERVALO_MS]. Se cancela al cerrarla o con la app en segundo plano.
     */
    suspend fun mientrasSeVe() {
        paginasExtra = 0
        _aviso.value = null
        while (true) {
            refrescar()
            delay(INTERVALO_MS)
        }
    }

    /** Una lectura de la bandeja. Una a la vez: la del latido y la de después de una acción no se pisan. */
    suspend fun refrescar() = mutex.withLock {
        val comoBandeja = ventanaALaVista.value
        val primera = try {
            repositorio.inbox("all", comoBandeja, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        primera.onSuccess { inbox ->
            var ordenes = inbox.orders
            var cursor = inbox.nextCursor
            var pagina = 0
            while (pagina < paginasExtra && cursor != null) {
                val siguiente = try {
                    repositorio.inbox("active", comoBandeja, cursor)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }.getOrNull() ?: break
                ordenes = (ordenes + siguiente.orders).distinctBy { it.orderId }
                cursor = siguiente.nextCursor
                pagina++
            }
            _secciones.value = SeccionesBandeja(
                porAceptar = ordenes.filter { it.status == OnlineOrderStatus.PENDING_ACCEPTANCE },
                enPreparacion = ordenes.filter { it.status == OnlineOrderStatus.ACCEPTED },
                listos = ordenes.filter { it.status == OnlineOrderStatus.READY },
                hoy = ordenes.filter { it.status !in ABIERTOS },
            )
            _hayMas.value = cursor != null
            _hoyRecortado.value = inbox.truncated
            _sinConexion.value = false
        }.onFailure {
            // Se conserva la última lista. «Sin conexión» sólo si de verdad no hay red: un 403 o un JSON roto no lo son.
            Log.d(TAG, "Bandeja sin respuesta: ${it.message}")
            if (it is IOException) _sinConexion.value = true
        }
        _cargando.value = false
    }

    fun cargarMas() {
        if (!_hayMas.value || _cargandoMas.value) return
        _cargandoMas.value = true
        viewModelScope.launch {
            try {
                paginasExtra++
                refrescar()
            } finally {
                _cargandoMas.value = false
            }
        }
    }

    // MARK: - Acciones (sólo en línea, nunca encoladas)

    fun aceptar(orderId: String, minutos: Int) =
        ejecutar(orderId, permisoActualizar()) { repositorio.accept(orderId, minutos) }

    /** [motivo] es el código del servidor (`OUT_OF_ITEMS`, `CLOSED`, `TOO_BUSY`, `OTHER`). */
    fun rechazar(orderId: String, motivo: String) =
        ejecutar(orderId, permisoActualizar()) { repositorio.reject(orderId, motivo, null) }

    fun marcarListo(orderId: String) = ejecutar(orderId, permisoActualizar()) { repositorio.ready(orderId) }

    fun marcarEntregado(orderId: String) = ejecutar(orderId, permisoActualizar()) { repositorio.delivered(orderId) }

    fun cancelar(orderId: String) = ejecutar(orderId, permisoCancelar()) { repositorio.cancel(orderId) }

    /** 15/30/60 o `null` = reanudar. Sin `online-orders:pause` sí llega al servidor: su 403 `overridable` pide el PIN del gerente. */
    fun pausar(minutos: Int?) = ejecutar(CLAVE_PAUSA, permitido = true, textoExito = minutos?.let { TextosPedidosEnLinea.pausadaPor(it) }
        ?: TextosPedidosEnLinea.PAUSA_REANUDADA) { repositorio.pause(minutos) }

    fun cerrarAviso() { _aviso.value = null }

    private fun ejecutar(
        clave: String,
        permitido: Boolean,
        textoExito: String? = null,
        llamada: suspend () -> AccionResultado,
    ) {
        if (!permitido) return
        if (clave in _ocupados.value) return
        _ocupados.value = _ocupados.value + clave
        viewModelScope.launch {
            try {
                val resultado = try {
                    llamada()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AccionResultado.Error(e.message ?: "No se pudo completar la acción")
                }
                _aviso.value = when (resultado) {
                    AccionResultado.Hecho -> textoExito
                    is AccionResultado.YaNoDisponible -> TextosPedidosEnLinea.YA_NO_DISPONIBLE
                    AccionResultado.SinConexion -> TextosPedidosEnLinea.REINTENTA
                    is AccionResultado.Error -> resultado.mensaje
                }
                // Siempre se relee: el servidor es el que dice dónde quedó el pedido.
                refrescar()
            } finally {
                _ocupados.value = _ocupados.value - clave
            }
        }
    }

    companion object {
        const val INTERVALO_MS = 10_000L
        const val CLAVE_PAUSA = "__pausa__"
        private val ABIERTOS = setOf(
            OnlineOrderStatus.PENDING_ACCEPTANCE, OnlineOrderStatus.ACCEPTED, OnlineOrderStatus.READY,
        )
    }
}
