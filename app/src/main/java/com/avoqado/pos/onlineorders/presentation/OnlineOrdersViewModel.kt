package com.avoqado.pos.onlineorders.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.onlineorders.data.ImpresionDePedidoEnLinea
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.data.ResultadoImpresion
import com.avoqado.pos.onlineorders.data.VentanaALaVista
import com.avoqado.pos.onlineorders.data.VigilanteDePedidosEnLinea
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.EfectivoDelPedido
import com.avoqado.pos.onlineorders.domain.AccionResultado
import com.avoqado.pos.onlineorders.domain.ListaPorCobrar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderItem
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.domain.PedidoPorCobrar
import com.avoqado.pos.onlineorders.domain.PrecioDeRenglon
import com.avoqado.pos.payment.data.model.PaymentItem
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Provider
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
fun entradaDePedidosEnLinea(
    puedeLeer: Boolean,
    tienePlan: Boolean,
    hayPedidosAbiertos: Boolean,
    // Rappi 2b (founder «D2-a»): la entrada también se abre con un canal de reparto o con pedidos de reparto por cobrar.
    tieneReparto: Boolean = false,
): EntradaPedidosEnLinea = when {
    !puedeLeer -> EntradaPedidosEnLinea.OCULTA
    tienePlan || hayPedidosAbiertos || tieneReparto -> EntradaPedidosEnLinea.ABIERTA
    else -> EntradaPedidosEnLinea.TEASER
}

/** «Ver» del banner: la solicitud abre la pantalla salvo que la persona no pueda ni leer pedidos. PURA. */
fun debeAbrirBandeja(solicitada: Boolean, entrada: EntradaPedidosEnLinea): Boolean =
    solicitada && entrada != EntradaPedidosEnLinea.OCULTA

/** Dinero para pintar: `$185.50`, siempre con el formato del negocio (no el del idioma del aparato). */
fun formatoPesos(centavos: Long): String = formatMoney(BigDecimal.valueOf(centavos, 2).toDouble())

private val FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm")

/** La hora en la zona del NEGOCIO, no la del reloj del aparato. */
fun horaDelPedido(pedido: OnlineOrder): String = horaDelNegocio(pedido.createdAt)

fun horaDelNegocio(instante: java.time.Instant): String = VenueTimeZone.at(instante).format(FORMATO_HORA)

/** Rappi 2b: «Venta $175 · Propina $20 · Rappi $10»; la propina y la parte de la plataforma salen sólo si no son 0. PURA. */
fun desgloseDe(pedido: PedidoPorCobrar): String = TextosPedidosEnLinea.desgloseDeReparto(
    venta = formatoPesos(pedido.saleCents),
    propina = pedido.tipCents.takeIf { it > 0 }?.let(::formatoPesos),
    plataforma = pedido.thirdPartyLabel,
    terceros = pedido.thirdPartyCents.takeIf { it > 0 }?.let(::formatoPesos),
)

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
    private val sondearVigilante: () -> Unit = {},
    private val imprimirSiToca: suspend (OnlineOrder) -> ResultadoImpresion = { ResultadoImpresion.OtroAparato },
    private val reimprimirPedido: suspend (OnlineOrder) -> ResultadoImpresion = { ResultadoImpresion.NoSalio },
    private val efectivoDelPedido: suspend (orderId: String) -> EfectivoDelPedido = { EfectivoDelPedido.NINGUNO },
    private val efectivoYaSubido: suspend (orderId: String) -> Boolean = { false },
    private val permisoCobrar: () -> Boolean = { true },
    // Rappi 2b: «Rappi · por cobrar». Por omisión, sin reparto (las pruebas de la tienda web no cambian).
    private val leerPorCobrar: suspend (cursor: String?) -> Result<ListaPorCobrar> = { Result.success(LISTA_NO_DISPONIBLE) },
    private val porCobrarGuardado: suspend () -> ListaPorCobrar? = { null },
    private val tieneCanalDeReparto: () -> Boolean = { false },
) : ViewModel() {

    @Inject
    constructor(
        repositorio: OnlineOrdersRepository,
        roleManager: RoleManager,
        planManager: PlanManager,
        vigilante: VigilanteDePedidosEnLinea,
        impresion: ImpresionDePedidoEnLinea,
        syncOutbox: Provider<SyncOutbox>,
        cashPaymentRepository: CashPaymentRepository,
    ) : this(
        repositorio = repositorio,
        permisoActualizar = { roleManager.canOperateOnlineOrders },
        permisoCobrar = { roleManager.hasVenuePermission("payments:create", setOf("WAITER", "CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")) },
        permisoCancelar = { roleManager.canCancelOnlineOrders },
        tienePlan = { planManager.hasFeature("ONLINE_ORDERING") },
        hayPedidosAbiertos = { vigilante.hayPedidosAbiertos.value },
        sondearVigilante = vigilante::sondearYa,
        // El deviceId del outbox, NO uno nuevo (regla de offline-first): sin él no se reclama nada.
        imprimirSiToca = { pedido ->
            val deviceId = try { syncOutbox.get().deviceId } catch (e: Exception) { null }
            if (deviceId == null) ResultadoImpresion.NoSalio else impresion.imprimirSiToca(pedido, deviceId)
        },
        reimprimirPedido = { pedido -> impresion.reimprimir(pedido) },
        efectivoDelPedido = cashPaymentRepository::efectivoDelPedido,
        efectivoYaSubido = cashPaymentRepository::huboEfectivoSubido,
        leerPorCobrar = { cursor -> repositorio.porCobrar(cursor) },
        porCobrarGuardado = { repositorio.porCobrarGuardado() },
        tieneCanalDeReparto = { planManager.hasFeature("DELIVERY_CHANNELS") },
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

    private val _leyoAlgunaVez = MutableStateFlow(false)

    /** Hubo al menos una lectura buena: sólo entonces «No hay pedidos por ahora» dice la verdad. */
    val leyoAlgunaVez: StateFlow<Boolean> = _leyoAlgunaVez.asStateFlow()

    private val _noSePudoLeer = MutableStateFlow(false)

    /** La última lectura falló por algo que NO es falta de red (403, 5xx, JSON roto): aviso neutro. */
    val noSePudoLeer: StateFlow<Boolean> = _noSePudoLeer.asStateFlow()

    val puedeActualizar: Boolean get() = permisoActualizar()
    val puedeCobrar: Boolean get() = permisoCobrar()
    val puedeCancelar: Boolean get() = permisoCancelar()

    private val _porCobrar = MutableStateFlow<ListaPorCobrar?>(null)

    /** Rappi 2b: «Rappi · por cobrar». null = todavía no se sabe (ni del aparato ni del servidor). */
    val porCobrar: StateFlow<ListaPorCobrar?> = _porCobrar.asStateFlow()

    /** Tienda web (plan o pedidos web abiertos): las cuatro secciones y la pausa. */
    val muestraTienda: Boolean get() = tienePlan() || hayPedidosAbiertos()

    /** Canal de reparto (plan) o pedidos de reparto por cobrar ya vistos: la sección «Rappi · por cobrar» (R14). */
    val muestraReparto: Boolean get() = tieneCanalDeReparto() || _porCobrar.value?.pedidos?.isNotEmpty() == true

    /** Sin tienda y sin reparto la pantalla es sólo el teaser. Cambia sola si el descubrimiento encuentra pedidos por cobrar. */
    val esTeaser: Boolean get() = !muestraTienda && !muestraReparto

    /** Páginas extra de «Rappi · por cobrar» cargadas con «Cargar más»; cada lectura las vuelve a recorrer. */
    private var paginasExtraPorCobrar = 0

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
            // Sólo este ciclo (pantalla a la vista) manda la cabecera de latido, y sólo si la ventana se ve (R13).
            refrescar(comoBandeja = ventanaALaVista.value)
            delay(INTERVALO_MS)
        }
    }

    /**
     * Al abrir la pantalla, aunque sea teaser: la lista del aparato primero (sin red sale de ahí) y una lectura con red. Así un
     * negocio con el plan vencido y sin nada guardado en ESTE aparato descubre sus pedidos de reparto por cobrar (Codex plan r1 #4).
     */
    suspend fun descubrirPorCobrar() {
        if (_porCobrar.value == null) {
            val guardada = try {
                porCobrarGuardado()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (_porCobrar.value == null) _porCobrar.value = guardada
        }
        mutex.withLock { refrescarPorCobrar(forzar = true) }
    }

    /** Una lectura de la bandeja. Una a la vez: la del latido y la de después de una acción no se pisan. */
    suspend fun refrescar(comoBandeja: Boolean = false, imprimirPendientes: Boolean = true) = mutex.withLock {
        refrescarPorCobrar()
        // Sólo reparto (sin tienda web): no se lee la bandeja web (un 403 de plan pintaría «No se pudo leer la bandeja»).
        if (!muestraTienda) {
            _cargando.value = false
            return@withLock
        }
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
            _leyoAlgunaVez.value = true
            _noSePudoLeer.value = false
            // Mientras la pantalla se ve, cada lectura saca la comanda de lo aceptado que aún nadie imprimió.
            if (imprimirPendientes) ordenes.filter { it.needsPrint && it.status in IMPRIMIBLES }.forEach { imprimir(it) }
        }.onFailure {
            // Se conserva la última lista. «Sin conexión» sólo si de verdad no hay red: un 403 o un JSON roto no lo son.
            Log.d(TAG, "Bandeja sin respuesta: ${it.message}")
            if (it is IOException) {
                _sinConexion.value = true
                _noSePudoLeer.value = false
            } else {
                _noSePudoLeer.value = true
            }
        }
        _cargando.value = false
    }

    /** La lista «Rappi · por cobrar» con sus páginas extra. La falla de una lectura nunca borra lo último que se vio. */
    private suspend fun refrescarPorCobrar(forzar: Boolean = false) {
        if (!forzar && !muestraReparto) return
        val primera = try {
            leerPorCobrar(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        primera.onSuccess { lista ->
            var actual = lista
            var pagina = 0
            while (pagina < paginasExtraPorCobrar && actual.nextCursor != null) {
                val siguiente = try {
                    leerPorCobrar(actual.nextCursor)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }.getOrNull() ?: break
                actual = actual.copy(
                    pedidos = (actual.pedidos + siguiente.pedidos).distinctBy { it.orderId },
                    truncated = siguiente.truncated,
                    nextCursor = siguiente.nextCursor,
                )
                pagina++
            }
            _porCobrar.value = actual
            if (!muestraTienda) _sinConexion.value = false
        }.onFailure {
            Log.d(TAG, "«Por cobrar» sin respuesta: ${it.message}")
            if (it is IOException) _sinConexion.value = true
        }
    }

    fun cargarMasPorCobrar() {
        if (_porCobrar.value?.nextCursor == null || _cargandoMas.value) return
        _cargandoMas.value = true
        viewModelScope.launch {
            try {
                paginasExtraPorCobrar++
                mutex.withLock { refrescarPorCobrar() }
            } finally {
                _cargandoMas.value = false
            }
        }
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

    fun aceptar(orderId: String, minutos: Int) {
        ejecutar(
            orderId, permisoActualizar(), sonidoCambia = true,
            // Después de releer: se imprime el pedido ACEPTADO que dijo el servidor (con su hora estimada), nunca la foto de
            // antes de aceptar. Si la relectura falló o el pedido no está, no se imprime de una foto vieja: el siguiente latido
            // lo hace por `needsPrint`.
            alHechoYReleido = { buscar(orderId)?.takeIf { it.status in IMPRIMIBLES }?.let { imprimir(it) } },
        ) { repositorio.accept(orderId, minutos) }
    }

    /** [motivo] es el código del servidor (`OUT_OF_ITEMS`, `CLOSED`, `TOO_BUSY`, `OTHER`). */
    fun rechazar(orderId: String, motivo: String) =
        ejecutar(orderId, permisoActualizar(), sonidoCambia = true) { repositorio.reject(orderId, motivo, null) }

    fun marcarListo(orderId: String) = ejecutar(orderId, permisoActualizar()) { repositorio.ready(orderId) }

    fun marcarEntregado(orderId: String) = ejecutar(orderId, permisoActualizar()) { repositorio.delivered(orderId) }

    fun cancelar(orderId: String) = ejecutar(orderId, permisoCancelar()) { repositorio.cancel(orderId) }

    /** 15/30/60 o `null` = reanudar. Sin `online-orders:pause` sí llega al servidor: su 403 `overridable` pide el PIN del gerente. */
    fun pausar(minutos: Int?) = ejecutar(CLAVE_PAUSA, permitido = true, textoExito = minutos?.let { TextosPedidosEnLinea.pausadaPor(it) }
        ?: TextosPedidosEnLinea.PAUSA_REANUDADA) { repositorio.pause(minutos) }

    /** «Reimprimir» (sin reclamar, sin confirmar): vuelve a sacar la comanda en ESTE aparato. */
    fun reimprimir(orderId: String) {
        if (!permisoActualizar()) return
        val pedido = buscar(orderId) ?: return
        if (orderId in _ocupados.value) return
        _ocupados.value = _ocupados.value + orderId
        viewModelScope.launch {
            try {
                val resultado = try {
                    reimprimirPedido(pedido)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ResultadoImpresion.NoSalio
                }
                _aviso.value = if (resultado == ResultadoImpresion.Impresa) TextosPedidosEnLinea.REIMPRESA else TextosPedidosEnLinea.REIMPRESION_FALLO
            } finally {
                _ocupados.value = _ocupados.value - orderId
            }
        }
    }

    private fun buscar(orderId: String): OnlineOrder? = with(_secciones.value) {
        (porAceptar + enPreparacion + listos + hoy).firstOrNull { it.orderId == orderId }
    }

    /**
     * Fuera de la lectura y de la acción (que no esperan al papel). El reclamo y la comanda corren en [ImpresionDePedidoEnLinea],
     * que no repite el mismo pedido a la vez y no depende de que esta pantalla siga viva.
     */
    private fun imprimir(pedido: OnlineOrder) {
        viewModelScope.launch {
            try {
                imprimirSiToca(pedido)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo intentar la comanda de ${pedido.orderNumber}: ${e.message}")
            }
        }
    }

    fun cerrarAviso() { _aviso.value = null }

    // MARK: - Cobrar (Task A5): el pedido sin mesa se cobra con el flujo de pago de siempre

    private val _cobroAbierto = MutableStateFlow<CobroDePedido?>(null)

    /** El cobro que la pantalla tiene abierto ahora (uno a la vez). null = ninguno. */
    val cobroAbierto: StateFlow<CobroDePedido?> = _cobroAbierto.asStateFlow()

    /**
     * «Cobrar»: decide si se abre el cobro y por cuánto. 🔴 DINERO.
     * 1. Un cobro a la vez en la pantalla, y un toque en vuelo de ese pedido no hace nada.
     * 2. Si ESTE aparato tiene un efectivo de ese pedido guardado sin subir (`EN_VUELO`, `PENDING` o `SYNCING`), no abre
     *    otro: sin red la bandeja conserva el saldo viejo y el segundo cobro llevaría otra llave (Codex plan r1 #2, r2 #1).
     *    El aviso dice quién lo sube: la cola sola («se sube al volver la conexión») o un gerente («Operaciones por revisar»).
     * 3. El saldo lo dice el SERVIDOR (`preBillBalance.remainingBalanceCents`): otro aparato pudo abonar o descontar. Sin red,
     *    el último `remaining` de la bandeja (contrato con Rappi 2b), salvo que este aparato ya haya subido un efectivo de ese
     *    pedido (la bandeja vieja no lo sabe): «Reintenta cuando vuelva la conexión». Nunca se recalcula desde los renglones.
     * 4. Saldo 0 ⇒ no abre: «Pagado», y se relee la bandeja.
     * Después de cobrar NO se llama `delivered`: lo decide el servidor ([cobroTerminado] sólo relee).
     */
    fun cobrar(pedido: OnlineOrder) = abrirCobro(
        orderId = pedido.orderId,
        orderNumber = pedido.orderNumber,
        saldoGuardado = pedido.remainingCents,
        plataforma = null,
        origenConSaldo = com.avoqado.pos.payment.domain.OrigenDelCobro.PEDIDO_EN_LINEA,
        origenSinSaldo = com.avoqado.pos.payment.domain.OrigenDelCobro.PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO,
        permitido = muestraTienda,
        productos = pedido.items,
    )

    /**
     * Rappi 2b: «Cobrar» de un pedido de «Rappi · por cobrar». Las MISMAS guardas que [cobrar] (cola del aparato, saldo del
     * servidor, sin red el último saldo visto, 0 ⇒ «Pagado»); cobra sólo en efectivo y sin propina (lo decide el origen).
     */
    fun cobrarDeReparto(pedido: PedidoPorCobrar) = abrirCobro(
        orderId = pedido.orderId,
        orderNumber = pedido.orderNumber,
        saldoGuardado = pedido.remainingBalanceCents,
        plataforma = pedido.thirdPartyLabel,
        origenConSaldo = com.avoqado.pos.payment.domain.OrigenDelCobro.PEDIDO_DE_PLATAFORMA_EFECTIVO,
        origenSinSaldo = com.avoqado.pos.payment.domain.OrigenDelCobro.PEDIDO_DE_PLATAFORMA_EFECTIVO_SALDO_GUARDADO,
        permitido = muestraReparto,
    )

    private fun abrirCobro(
        orderId: String,
        orderNumber: String,
        saldoGuardado: Long,
        plataforma: String?,
        origenConSaldo: com.avoqado.pos.payment.domain.OrigenDelCobro,
        origenSinSaldo: com.avoqado.pos.payment.domain.OrigenDelCobro,
        permitido: Boolean,
        productos: List<OnlineOrderItem> = emptyList(),
    ) {
        if (!permisoActualizar() || !permisoCobrar() || !permitido) return
        if (_cobroAbierto.value != null) return
        if (orderId in _ocupados.value) return
        _ocupados.value = _ocupados.value + orderId
        viewModelScope.launch {
            try {
                val guardado = try {
                    efectivoDelPedido(orderId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // No se pudo leer la cola del aparato: no se sabe si hay un cobro guardado, así que no se abre otro.
                    Log.w(TAG, "No se pudo revisar la cola de efectivo de $orderNumber: ${e.message}")
                    _aviso.value = TextosPedidosEnLinea.SALDO_NO_LEIDO
                    return@launch
                }
                when (guardado) {
                    EfectivoDelPedido.NINGUNO -> Unit
                    // Los dos bloquean; el texto dice la verdad de quién lo sube (Ruling 21 #2).
                    EfectivoDelPedido.EN_COLA -> {
                        _aviso.value = TextosPedidosEnLinea.COBRO_EN_EFECTIVO_GUARDADO
                        return@launch
                    }
                    EfectivoDelPedido.POR_REVISAR -> {
                        _aviso.value = TextosPedidosEnLinea.COBRO_EN_EFECTIVO_POR_REVISAR
                        return@launch
                    }
                }
                val leido = try {
                    repositorio.saldoDelPedido(orderId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                val saldo = leido.map { it.saldoCents }.getOrElse { error ->
                    if (error is IOException) {
                        // Sin red es un estado normal: se cobra el último saldo que dijo la bandeja. Salvo que ESTE aparato ya
                        // haya subido un efectivo de ese pedido: la bandeja vieja no sabe de él y se cobraría dos veces
                        // (Ruling 21 #3). Se espera a la red, que trae el saldo del servidor.
                        val yaSubio = try {
                            efectivoYaSubido(orderId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo revisar lo subido de $orderNumber: ${e.message}")
                            true
                        }
                        if (yaSubio) {
                            _aviso.value = TextosPedidosEnLinea.REINTENTA
                            return@launch
                        }
                        saldoGuardado
                    } else {
                        // El servidor contestó otra cosa (404, 5xx, JSON roto): no se adivina con la bandeja.
                        Log.w(TAG, "Saldo de $orderNumber no leído: ${error.message}")
                        _aviso.value = TextosPedidosEnLinea.SALDO_NO_LEIDO
                        return@launch
                    }
                }
                if (saldo <= 0L) {
                    _aviso.value = TextosPedidosEnLinea.PAGADO
                    refrescar(imprimirPendientes = false)
                    return@launch
                }
                if (saldo > Int.MAX_VALUE) {
                    _aviso.value = TextosPedidosEnLinea.SALDO_NO_LEIDO
                    return@launch
                }
                if (!permisoActualizar() || !permisoCobrar()) return@launch
                // Dos toques rápidos en pedidos distintos pasan los dos la revisión del principio: el que tarda más no
                // reemplaza a un cobro que ya está abierto (el cajero pudo haber recibido el efectivo del primero).
                if (_cobroAbierto.value != null) return@launch
                _aviso.value = null
                _cobroAbierto.value = CobroDePedido(orderId = orderId, orderNumber = orderNumber, saldoCents = saldo.toInt(),
                    origen = if (leido.isSuccess) origenConSaldo else origenSinSaldo, plataforma = plataforma,
                    renglones = leido.getOrNull()?.let { renglonesParaVer(productos, it.precios, it.saldoCents) }.orEmpty())
            } finally {
                _ocupados.value = _ocupados.value - orderId
            }
        }
    }

    /**
     * El cobro se cerró (cobrado, encolado sin red o cancelado): se suelta y se relee la bandeja. 🔴 Nunca `delivered`: pagado
     * y «Listo» lo entrega el servidor en la siguiente lectura; pagado y en preparación, a su hora o con el botón «Entregado».
     */
    fun cobroTerminado() {
        if (_cobroAbierto.value == null) return
        _cobroAbierto.value = null
        viewModelScope.launch { refrescar(imprimirPendientes = false) }
    }

    private fun ejecutar(
        clave: String,
        permitido: Boolean,
        textoExito: String? = null,
        sonidoCambia: Boolean = false,
        alHechoYReleido: () -> Unit = {},
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
                // El pedido salió de «por aceptar» (o ya no estaba): el aviso global para en ESTA petición, no en el siguiente compás.
                if (resultado is AccionResultado.Hecho || resultado is AccionResultado.YaNoDisponible) {
                    if (clave != CLAVE_PAUSA && sonidoCambia) sondearVigilante()
                }
                // Los pendientes de impresión los maneja cada acción por su cuenta (aceptar, abajo) o el siguiente latido.
                refrescar(imprimirPendientes = false)
                if (resultado is AccionResultado.Hecho) alHechoYReleido()
            } finally {
                _ocupados.value = _ocupados.value - clave
            }
        }
    }

    companion object {
        const val INTERVALO_MS = 10_000L
        val LISTA_NO_DISPONIBLE = ListaPorCobrar(emptyList(), 0, false, null, disponible = false)
        const val CLAVE_PAUSA = "__pausa__"
        private val IMPRIMIBLES = setOf(OnlineOrderStatus.ACCEPTED, OnlineOrderStatus.READY)
        private val ABIERTOS = setOf(
            OnlineOrderStatus.PENDING_ACCEPTANCE, OnlineOrderStatus.ACCEPTED, OnlineOrderStatus.READY,
        )
    }
}

/**
 * Un «Cobrar» de la bandeja ya decidido: la orden ([orderId] = `Order.id`) y el saldo que se cobra, en centavos enteros.
 * [apertura] = UNA por cada «Cobrar» (`PaymentFlowScreen`): vive aquí, en el ViewModel, porque la superposición de «Más» no
 * sobrevive a que se recree la Activity; al volver a abrirla, el cobro sigue donde iba en vez de arrancar otro.
 */
data class CobroDePedido(
    val orderId: String,
    val orderNumber: String,
    val saldoCents: Int,
    val origen: com.avoqado.pos.payment.domain.OrigenDelCobro = com.avoqado.pos.payment.domain.OrigenDelCobro.PEDIDO_EN_LINEA,
    /** Rappi 2b: «Rappi» en un pedido de plataforma (el renglón dice «Rappi #folio»); null en un pedido de la tienda web. */
    val plataforma: String? = null,
    val apertura: String = java.util.UUID.randomUUID().toString(),
    /** D4: los productos del pedido, sólo para «Ver productos»; vacío sin red, en Rappi o si no cuadran ([renglonesParaVer]). */
    val renglones: List<com.avoqado.pos.payment.data.model.PaymentItem> = emptyList(),
)

/**
 * D4 (28-oct): lo que enseña «Ver productos» al cobrar un pedido de la tienda web. Nombre, cantidad, extras y nota salen de la
 * bandeja (lo mismo que la comanda); el precio de cada producto, del detalle del servidor. Se empatan por nombre, cantidad,
 * nota y nombres de extras (los del detalle son de AHORA: si otra caja los cambió, no empatan), y sólo se enseñan si TODO cuadra:
 * mismos productos, ninguno dudoso (dos iguales con precio distinto), precio por pieza exacto y la suma igual al saldo al
 * centavo. Si no, vacío y queda «Pedido #folio»: la lista nunca dice algo distinto de lo que se cobra. PURA.
 */
fun renglonesParaVer(productos: List<OnlineOrderItem>, precios: List<PrecioDeRenglon>?, saldoCents: Long): List<PaymentItem> {
    if (precios.isNullOrEmpty() || productos.size != precios.size || precios.sumOf { it.totalCents } != saldoCents) return emptyList()
    val porProducto = precios.groupBy { listOf(it.name, it.quantity, it.note, it.modifierNames.sorted()) }
    if (porProducto.values.any { mismos -> mismos.map { it.totalCents }.distinct().size > 1 }) return emptyList()
    val llave = { p: OnlineOrderItem -> listOf(p.name, p.quantity, p.note, p.modifiers.map { it.name }.sorted()) }
    if (productos.groupingBy(llave).eachCount() != porProducto.mapValues { it.value.size }) return emptyList()
    return productos.map { p ->
        val total = porProducto.getValue(llave(p)).first().totalCents
        if (p.quantity <= 0 || total % p.quantity != 0L) return emptyList()
        PaymentItem(
            name = p.name,
            quantity = p.quantity,
            unitPrice = (total / p.quantity).toInt(),
            lineTotal = total.toInt(),
            modifiers = p.modifiers.map { if (it.quantity > 1) "${it.quantity} × ${it.name}" else it.name },
            note = p.note,
        )
    }
}

/**
 * El carrito que siembra el cobro: UN renglón de importe por el saldo, para recoger (molde: `TableOrderScreen`). El dinero
 * real vive en la orden del servidor; el renglón sólo dice cuánto. PURA: la pantalla la guarda en un `remember(cobro)` para
 * que el carrito sea estable (es llave del arranque del cobro: uno nuevo por recomposición reiniciaba el flujo).
 */
fun carritoDelCobro(cobro: CobroDePedido): CartState = CartState(
    items = listOf(
        CartItem(
            id = "pedido-en-linea-${cobro.orderId}",
            type = CartItemType.CustomAmount,
            name = cobro.plataforma?.let { TextosPedidosEnLinea.notaDeReparto(it, cobro.orderNumber) }
                ?: TextosPedidosEnLinea.pedidoNumero(cobro.orderNumber),
            unitPrice = cobro.saldoCents,
        ),
    ),
    orderType = "PICKUP",
    renglonesParaVer = cobro.renglones,
)
