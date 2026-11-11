package com.avoqado.pos.onlineorders.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.core.util.formatMoney
import com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta
import com.avoqado.pos.onlineorders.data.ImpresionDePedidoEnLinea
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.data.ResultadoImpresion
import com.avoqado.pos.onlineorders.data.VentanaALaVista
import com.avoqado.pos.onlineorders.data.VigilanteDePedidosEnLinea
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.EfectivoDelPedido
import com.avoqado.pos.onlineorders.domain.AccionResultado
import com.avoqado.pos.onlineorders.domain.HuellaDeFolio
import com.avoqado.pos.onlineorders.domain.ListaPorCobrar
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
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
    // Tienda híbrida (A2): «Por pagar» y «Por entregar». Por omisión, sin folios (las pruebas de la bandeja no cambian).
    private val leerPorPagar: suspend (conLatido: Boolean, seccion: String?, cursor: String?) -> Result<ListaPorPagar> =
        { _, _, _ -> Result.success(LISTA_FOLIOS_NO_DISPONIBLE) },
    private val foliosGuardados: suspend () -> ListaPorPagar? = { null },
    private val ahoraMs: () -> Long = System::currentTimeMillis,
    /** A3: los pedidos con efectivo de este aparato en la cola -> lo cobrado (`CashPaymentRepository.efectivoEnColaPorPedido`). */
    private val efectivoEnCola: suspend () -> Map<String, Long> = { emptyMap() },
    /** R100 (c): las marcas «aquí ya se cobró con tarjeta» de la bandeja (las escribe `TerminalPaymentService`). */
    private val foliosConTarjeta: FoliosCobradosConTarjeta = FoliosCobradosConTarjeta.enMemoria(),
    /** R100 (b) y (d): los cobros con tarjeta de este aparato que siguen en la lista durable, del negocio dado. */
    private val cobrosConTarjeta: (venueId: String?) -> List<com.avoqado.pos.payment.data.ContextoDeCobro> = { emptyList() },
    /** El negocio de ahora: una marca sólo la suelta una lectura del MISMO negocio. */
    private val venueActual: () -> String? = { null },
    /** R101: lo escondido se guarda en la MISMA copia que la lista de folios (`OnlineOrdersRepository.guardarOcultos`). */
    private val guardarOcultos: suspend (Map<String, HuellaDeFolio>) -> Unit = {},
    /** Parte 2 (A1): el último `stampsEnabled` que este aparato supo del negocio; sin saberlo, no se promete el sello. */
    private val sellosActivos: () -> Boolean = { false },
    /** A1 (e): un cobro con saldo de esta orden mandado y sin confirmar (`SolicitudesDeTarjeta.cuentaTrabada`). */
    private val saldoEnDuda: (orderId: String) -> Boolean = { false },
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
        foliosCobradosConTarjeta: FoliosCobradosConTarjeta,
        terminalPaymentService: com.avoqado.pos.payment.data.TerminalPaymentService,
        secureStorage: com.avoqado.pos.core.data.local.SecureStorage,
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
        leerPorPagar = { conLatido, seccion, cursor -> repositorio.porPagar(conLatido, seccion, cursor) },
        foliosGuardados = { repositorio.porPagarGuardado() },
        efectivoEnCola = cashPaymentRepository::efectivoEnColaPorPedido,
        foliosConTarjeta = foliosCobradosConTarjeta,
        cobrosConTarjeta = terminalPaymentService::cobrosConTarjetaDelNegocio,
        venueActual = { secureStorage.venueId },
        guardarOcultos = { repositorio.guardarOcultos(it) },
        sellosActivos = { secureStorage.venueId?.let(secureStorage::programaDeSellosActivo) == true },
        saldoEnDuda = { orderId ->
            secureStorage.venueId?.let {
                com.avoqado.pos.giftcards.domain.SolicitudesDeTarjeta.cuentaTrabada(secureStorage.giftCardRequestsJson, it, orderId)
            } == true
        },
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

    /** Parte 2: «Con cuenta: … · suma su sello al pagar» sólo si los sellos del negocio están prendidos. */
    val sumaSello: Boolean get() = sellosActivos()

    private val _porCobrar = MutableStateFlow<ListaPorCobrar?>(null)

    /** Rappi 2b: «Rappi · por cobrar». null = todavía no se sabe (ni del aparato ni del servidor). */
    val porCobrar: StateFlow<ListaPorCobrar?> = _porCobrar.asStateFlow()

    /** Tienda web (plan o pedidos web abiertos): las cuatro secciones y la pausa. */
    val muestraTienda: Boolean get() = tienePlan() || hayPedidosAbiertos()

    /** Canal de reparto (plan) o pedidos de reparto por cobrar ya vistos: la sección «Rappi · por cobrar» (R14). */
    val muestraReparto: Boolean get() = tieneCanalDeReparto() || _porCobrar.value?.pedidos?.isNotEmpty() == true

    /** Sin tienda y sin reparto la pantalla es sólo el teaser. Cambia sola si el descubrimiento encuentra pedidos por cobrar. */
    val esTeaser: Boolean get() = !muestraTienda && !muestraReparto

    // MARK: - Tienda híbrida (A2): «Por pagar» y «Por entregar»

    private val _porPagar = MutableStateFlow<ListaPorPagar?>(null)

    /**
     * Los folios de ahora. null = todavía no se sabe (ni del aparato ni del servidor). `guardadoHaceMin != null` = la lista sale del
     * aparato (sin red), con su edad. `disponible = false` = el servidor dijo que no hay sección: no se pinta aunque haya algo guardado.
     */
    val porPagar: StateFlow<ListaPorPagar?> = _porPagar.asStateFlow()

    private val _errorFolios = MutableStateFlow(false)

    /** No hay lista que enseñar y la lectura falló por algo que no es falta de red: «No se pudieron leer los folios» + «Reintentar». */
    val errorFolios: StateFlow<Boolean> = _errorFolios.asStateFlow()

    private val _leyendoFolios = MutableStateFlow(true)

    /** Primera lectura de folios todavía en curso (ni lista ni error): «Leyendo folios…». */
    val leyendoFolios: StateFlow<Boolean> = _leyendoFolios.asStateFlow()

    private val _busqueda = MutableStateFlow("")

    /** Lo escrito en «Buscar folio o nombre». */
    val busqueda: StateFlow<String> = _busqueda.asStateFlow()

    fun buscarFolio(texto: String) { _busqueda.value = texto }

    private val _cobradosEnElAparato = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * A3: los folios de «Por pagar» con un efectivo de ESTE aparato en la cola (se sube solo al volver la red) -> lo cobrado, en
     * centavos (`amountCents` de la cola). La pantalla los pinta en «Por entregar» con «Pagado en efectivo $X» y «Cobro en efectivo
     * guardado…» ([conLoCobradoEnElAparato]) sin esperar a la red: nadie los cobra dos veces. «Por revisar» no cuenta: no se sube
     * solo y su «Cobrar» ya dice que lo resuelve un gerente.
     */
    val cobradosEnElAparato: StateFlow<Map<String, Long>> = _cobradosEnElAparato.asStateFlow()

    private val _cobradosConTarjeta = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * A5 (Codex fase 3-4 r1 #1): los folios de «Por pagar» que ESTE aparato ya cobró con tarjeta y la lista todavía no cuenta (la red se
     * cayó antes de releer, o la lista es la guardada) -> lo cobrado. Igual que el efectivo en cola: la pantalla los pinta en «Por
     * entregar» («Pagado con tarjeta $X») y «Cobrar» se niega.
     */
    val cobradosConTarjeta: StateFlow<Map<String, Long>> = _cobradosConTarjeta.asStateFlow()

    /**
     * Una sola lectura de la cola del aparato (no una por folio). Corre ANTES de tocar la red (al abrir con lo guardado, al cerrar un
     * cobro y al empezar cada lectura de folios) y otra vez después: con el WiFi «conectado sin datos» la red tarda, y lo cobrado ya
     * tiene que verse en «Por entregar».
     */
    private suspend fun releerCobradosEnElAparato() {
        val lista = _porPagar.value
        val folios = lista?.porPagar.orEmpty().map { it.orderId }.toSet()
        try {
            val venue = venueActual()
            val positivos = cobrosConTarjeta(venue).filter { it.cobrado && it.orderId != null }
                .associate { it.orderId!! to (it.amountCents ?: 0).toLong() }
            _cobradosConTarjeta.value = cobradosConTarjetaEnLaLista(foliosConTarjeta.marcasDe(venue), positivos, lista)
        } catch (e: Exception) {
            // No se pudo leer la marca: lo último que se supo se queda, y «Cobrar» vuelve a revisar (y no abre otro cobro).
            Log.w(TAG, "No se pudieron leer los folios cobrados con tarjeta: ${e.message}")
        }
        val enCola = try {
            efectivoEnCola()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No se pudo leer la cola: lo último que se supo se queda, y «Cobrar» vuelve a revisar (y no abre otro cobro).
            Log.w(TAG, "No se pudo leer la cola de efectivo: ${e.message}")
            return
        }
        _cobradosEnElAparato.value = enCola.filterKeys { it in folios }
    }

    /** Páginas extra por sección (`toPay` / `toDeliver`) traídas con «Cargar más»; cada lectura las vuelve a recorrer. */
    private val paginasExtraFolios = mutableMapOf(SECCION_POR_PAGAR to 0, SECCION_POR_ENTREGAR to 0)

    /** Cuándo (reloj del aparato) salió la lista que se enseña: para decir «de hace N min» si después se cae la red. */
    private var leidoEnMs = 0L

    /**
     * Al abrir la pantalla: lo guardado primero (sin red sale de ahí, con su edad) y una lectura con red. Lo guardado nunca pisa una
     * respuesta del servidor ni vuelve después de que el servidor dijo «no disponible» (m2 de A1).
     */
    suspend fun descubrirPorPagar() {
        if (_porPagar.value == null) {
            val guardada = try {
                foliosGuardados()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (_porPagar.value == null && guardada != null) {
                _porPagar.value = guardada
                // R101: lo escondido vuelve con la copia: tras reiniciar no regresan botones de acciones ya hechas.
                _ocultos.value = guardada.ocultos + _ocultos.value
                leidoEnMs = ahoraMs() - (guardada.guardadoHaceMin ?: 0L) * 60_000
            }
        }
        soltarMarcasDeFoliosConLaCopia() // R104: al abrir, contra la copia en disco
        releerCobradosEnElAparato() // antes de la red: lo cobrado aquí sin subir ya va en «Por entregar»
        mutex.withLock { refrescarPorPagar(comoBandeja = false) }
    }

    fun reintentarFolios() {
        viewModelScope.launch { mutex.withLock { refrescarPorPagar(comoBandeja = false) } }
    }

    /**
     * La lista de folios con sus páginas extra. La falla de una lectura nunca borra lo último que se vio. El latido sólo sale si la
     * pantalla es la bandeja visible Y la persona cobra (`payments:create`): el servidor lo vuelve a comprobar.
     */
    private suspend fun refrescarPorPagar(comoBandeja: Boolean) {
        // R105 (c): una lectura nueva corta el recorrido de páginas extra de la anterior (y su resultado ya no se publica).
        val generacion = ++generacionDeFolios
        trabajoDeExtras?.cancel()
        trabajoDeExtras = null
        releerCobradosEnElAparato() // antes de la red, y otra vez al final con la lista nueva
        val primera = try {
            leerPorPagar(comoBandeja && permisoCobrar(), null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        // R102: una respuesta más vieja que la copia guardada (otra lectura pedida después ya la dejó) no mueve nada en memoria.
        if (primera.getOrNull()?.masViejaQueLaCopia == true) {
            Log.d(TAG, "«Por pagar»: respuesta más vieja que la copia guardada; se queda la lista de ahora")
            releerCobradosEnElAparato()
            _leyendoFolios.value = false
            return
        }
        primera.onSuccess { lista ->
            // R105 (c) (Codex r7 #1): la página 1 se publica YA, sin esperar a las extras (un folio nuevo por cobrar no espera a una
            // página de «Por entregar»). Lo que ya se veía de las extras se conserva mientras se releen aparte (regla A2).
            val previa = _porPagar.value
            val conExtras = lista.disponible && SECCIONES.any { (paginasExtraFolios[it] ?: 0) > 0 && cursorDe(lista, it) != null }
            var actual = lista
            if (conExtras) {
                for (seccion in SECCIONES) {
                    if ((paginasExtraFolios[seccion] ?: 0) > 0 && cursorDe(lista, seccion) != null) {
                        actual = conservarLoQueSeVeia(actual, previa, seccion)
                    }
                }
            }
            _porPagar.value = actual
            _errorFolios.value = false
            if (!conExtras) leidoEnMs = ahoraMs()
            // R104: tras guardar la copia, se mira la copia EN DISCO (no esta lista con sus páginas extra) y se sueltan las marcas de
            // folios que ella ya cuenta.
            if (lista.disponible && lista.copiaGuardada) soltarMarcasDeFoliosConLaCopia()
            // Codex r2 #3: lo escondido tras una acción se poda en cuanto el SERVIDOR lo trae distinto (o ya no lo trae).
            if (lista.disponible) _ocultos.value = ocultosQueSiguen(_ocultos.value, actual)
            // Las extras van en su propio trabajo, FUERA del candado de la pantalla: «Reintentar» nunca espera detrás de ellas.
            if (conExtras) trabajoDeExtras = viewModelScope.launch { recorrerExtras(lista, previa, generacion) }
        }.onFailure {
            Log.d(TAG, "«Por pagar» sin respuesta: ${it.message}")
            val actual = _porPagar.value
            if (it is IOException) {
                _sinConexion.value = true
                // Sin red no es un error de lectura: el aviso de sin conexión y el de «no se pudieron leer» nunca van juntos.
                _errorFolios.value = false
                // Se conserva la última lista, diciendo qué tan vieja es.
                if (actual != null && actual.disponible) {
                    _porPagar.value = actual.copy(guardadoHaceMin = ((ahoraMs() - leidoEnMs) / 60_000).coerceAtLeast(0))
                }
            } else if (actual != null) {
                _noSePudoLeer.value = true
            } else {
                _errorFolios.value = true
            }
        }
        releerCobradosEnElAparato()
        _leyendoFolios.value = false
    }

    private fun cursorDe(lista: ListaPorPagar, seccion: String) =
        if (seccion == SECCION_POR_PAGAR) lista.nextCursors.toPay else lista.nextCursors.toDeliver

    /** R105 (c): cada lectura de página 1 lleva su número; el recorrido de extras de una lectura vieja no publica nada. */
    private var generacionDeFolios = 0L

    /** El recorrido de páginas extra en curso (fuera de [mutex]); una lectura nueva lo cancela. */
    private var trabajoDeExtras: kotlinx.coroutines.Job? = null

    /**
     * R105 (c): las páginas extra de [primera], cada una con su plazo total (el del repositorio). Termina publicando la lista completa;
     * si una falla, conserva lo que ya se veía de esa sección (A5 #4) y lo dice, sin dar la lectura por buena. Nunca suelta marcas: eso
     * sólo lo hace la copia guardada (R104).
     */
    private suspend fun recorrerExtras(primera: ListaPorPagar, previa: ListaPorPagar?, generacion: Long) {
        var actual = primera
        var fallo: Throwable? = null
        for (seccion in SECCIONES) {
            var pagina = 0
            while (pagina < (paginasExtraFolios[seccion] ?: 0)) {
                val cursor = cursorDe(actual, seccion) ?: break
                val siguiente = try {
                    leerPorPagar(false, seccion, cursor)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                val leida = siguiente.getOrNull()
                if (leida == null) {
                    fallo = siguiente.exceptionOrNull()
                    actual = conservarLoQueSeVeia(actual, previa, seccion)
                    break
                }
                actual = sumarPagina(actual, leida, seccion)
                pagina++
            }
        }
        if (generacion != generacionDeFolios) return // ya publicó una lectura más nueva
        _porPagar.value = actual
        val falloDePagina = fallo
        if (falloDePagina == null) {
            leidoEnMs = ahoraMs()
        } else {
            Log.d(TAG, "«Por pagar» incompleto: ${falloDePagina.message}")
            if (falloDePagina is IOException) _sinConexion.value = true else _noSePudoLeer.value = true
        }
        _ocultos.value = ocultosQueSiguen(_ocultos.value, actual)
        releerCobradosEnElAparato()
    }

    /**
     * 🔴 R104, la ÚNICA puerta para soltar la marca de un FOLIO: la copia guardada EN DISCO del negocio de ahora (lo que «Cobrar» usa
     * sin red) ya cuenta el pago ([tarjetasConfirmadasPorLaCopia]). Corre tras guardar una copia y al abrir la pantalla. Nada en
     * memoria, ninguna página extra y ninguna Bandeja suelta la marca de un folio.
     */
    private suspend fun soltarMarcasDeFoliosConLaCopia() {
        val venue = venueActual() ?: return
        try {
            val copia = foliosGuardados() ?: return
            if (venueActual() != venue) return
            foliosConTarjeta.olvidar(tarjetasConfirmadasPorLaCopia(foliosConTarjeta.marcas(), venue, copia))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo revisar la copia guardada para soltar marcas: ${e.message}")
        }
    }

    /**
     * R101: la Bandeja es la fuente del «Cobrar» de «Mi equipo» y no tiene copia guardada: su lista en memoria (`_secciones`) cambia en
     * este mismo paso. Sólo suelta marcas de «Mi equipo» y sólo con una lectura 2xx, completa y del negocio de ahora.
     */
    private fun soltarMarcasDeMiEquipo(venueDeLaLectura: String?, vistos: List<OnlineOrder>) {
        val venue = venueDeLaLectura ?: return
        if (venueActual() != venue) return
        try {
            val deMiEquipo = foliosConTarjeta.marcas().filter { !it.esFolio }
            foliosConTarjeta.olvidar(tarjetasConfirmadas(deMiEquipo, venue, vistos, null))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron soltar los cobros con tarjeta ya confirmados: ${e.message}")
        }
    }

    /**
     * A5 (#4): una página extra de [seccion] no llegó. Lo que [previa] enseñaba de esa sección y la lectura nueva no trae (en ninguna
     * de las dos secciones) se queda, con el cursor de entonces: así «Cargar más» no vuelve a ofrecer lo que ya se ve.
     */
    private fun conservarLoQueSeVeia(nueva: ListaPorPagar, previa: ListaPorPagar?, seccion: String): ListaPorPagar {
        if (previa == null || !previa.disponible) return nueva
        val vistos = (nueva.porPagar + nueva.porEntregar).map { it.orderId }.toSet()
        return if (seccion == SECCION_POR_PAGAR) {
            nueva.copy(
                porPagar = nueva.porPagar + previa.porPagar.filter { it.orderId !in vistos },
                nextCursors = nueva.nextCursors.copy(toPay = previa.nextCursors.toPay),
            )
        } else {
            nueva.copy(
                porEntregar = nueva.porEntregar + previa.porEntregar.filter { it.orderId !in vistos },
                nextCursors = nueva.nextCursors.copy(toDeliver = previa.nextCursors.toDeliver),
            )
        }
    }

    /** La página extra de UNA sección: la otra viene vacía y no se toca. */
    private fun sumarPagina(actual: ListaPorPagar, pagina: ListaPorPagar, seccion: String) =
        if (seccion == SECCION_POR_PAGAR) {
            actual.copy(
                porPagar = (actual.porPagar + pagina.porPagar).distinctBy { it.orderId },
                nextCursors = actual.nextCursors.copy(toPay = pagina.nextCursors.toPay),
                counts = actual.counts.copy(toPay = pagina.counts.toPay),
            )
        } else {
            actual.copy(
                porEntregar = (actual.porEntregar + pagina.porEntregar).distinctBy { it.orderId },
                nextCursors = actual.nextCursors.copy(toDeliver = pagina.nextCursors.toDeliver),
                counts = actual.counts.copy(toDeliver = pagina.counts.toDeliver),
            )
        }

    /** «Cargar más» de una sección de folios ([SECCION_POR_PAGAR] o [SECCION_POR_ENTREGAR]). */
    fun cargarMasFolios(seccion: String) {
        val lista = _porPagar.value ?: return
        if (cursorDe(lista, seccion) == null || _cargandoMas.value) return
        _cargandoMas.value = true
        viewModelScope.launch {
            try {
                paginasExtraFolios[seccion] = (paginasExtraFolios[seccion] ?: 0) + 1
                mutex.withLock { refrescarPorPagar(comoBandeja = false) }
                // «Cargar más» sigue ocupado hasta que llegue la página (o la corte su plazo o una lectura nueva), sin tomar el candado.
                trabajoDeExtras?.join()
            } finally {
                _cargandoMas.value = false
            }
        }
    }

    /** «Entregado» de un folio: el servidor lo cierra y el aviso dice a quién se le entregó. */
    fun entregarFolio(pedido: OnlineOrder) = ejecutar(
        pedido.orderId, permisoActualizar(), textoExito = TextosPedidosEnLinea.folioEntregado(pedido.orderNumber, pedido.customerName),
        huella = huellaDe(_porPagar.value, pedido.orderId),
    ) { repositorio.delivered(pedido.orderId) }

    private val _ocultos = MutableStateFlow<Map<String, HuellaDeFolio>>(emptyMap())

    /**
     * R100 (Codex r2 #3): folios con una acción ya confirmada («Listo», «Entregado», «Cancelar folio») -> su huella de ANTES. La pantalla
     * los esconde mientras la lista los traiga igual ([sinLosYaAtendidos]); una lectura buena que los trae distintos los suelta.
     */
    val ocultos: StateFlow<Map<String, HuellaDeFolio>> = _ocultos.asStateFlow()

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
        val estaLectura = ++lecturasIniciadas
        val venueDeLaLectura = venueActual()
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
            var completa = true
            while (pagina < paginasExtra && cursor != null) {
                val siguiente = try {
                    repositorio.inbox("active", comoBandeja, cursor)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }.getOrNull()
                if (siguiente == null) {
                    completa = false
                    break
                }
                ordenes = (ordenes + siguiente.orders).distinctBy { it.orderId }
                cursor = siguiente.nextCursor
                pagina++
            }
            val leidos = ordenes.filter { it.status == OnlineOrderStatus.PENDING_ACCEPTANCE }
            // Una lectura EXITOSA que salió DESPUÉS del 409 y ya no lo trae confirmando (o ya no por aceptar) libera «Aceptar».
            confirmando.entries.removeAll { (id, ultimaDeAntes) ->
                estaLectura > ultimaDeAntes && leidos.none { it.orderId == id && it.cardStatus == "CONFIRMING" }
            }
            // La que ya iba en el aire trae una foto de antes del 409: no le devuelve «Aceptar» ni el texto de antes.
            val pendientes = leidos.map { if (it.orderId in confirmando) it.copy(cardStatus = "CONFIRMING") else it }
            _secciones.value = SeccionesBandeja(
                porAceptar = pendientes,
                // Un folio pagado vive en «Por entregar»: aquí sólo lo que acepta el equipo (o un servidor viejo que no lo dice).
                enPreparacion = ordenes.filter { it.status == OnlineOrderStatus.ACCEPTED && it.requiresAcceptance != false },
                // R98: un folio READY sigue en «Por entregar» (con su «Listo» y sólo «Entregado»): aquí tampoco se repite.
                listos = ordenes.filter { it.status == OnlineOrderStatus.READY && it.requiresAcceptance != false },
                hoy = ordenes.filter { it.status !in ABIERTOS },
            )
            // R101: la Bandeja es la fuente del «Cobrar» de «Mi equipo» (no tiene copia guardada: `_secciones` acaba de cambiar), así
            // que sólo suelta marcas de «Mi equipo». Nunca las de un folio, aunque el folio también salga aquí: su fuente es «Por pagar».
            if (completa) soltarMarcasDeMiEquipo(venueDeLaLectura, ordenes)
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
        // Los folios van DESPUÉS de la bandeja: si la red se cayó entre las dos lecturas, la última palabra es la suya. «Cargando»
        // cubre las dos para que «No hay pedidos por ahora» no salga mientras los folios todavía no se sabe si existen.
        refrescarPorPagar(comoBandeja)
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
        // Aquí y no sólo en el botón: ningún camino de la vista (el botón, los minutos de un diálogo ya abierto) se la salta.
        if (orderId in confirmando || buscar(orderId)?.let(::sePuedeAceptar) == false) return
        ejecutar(
            orderId, permisoActualizar(), sonidoCambia = true,
            // Después de releer: se imprime el pedido ACEPTADO que dijo el servidor (con su hora estimada), nunca la foto de
            // antes de aceptar. Si la relectura falló o el pedido no está, no se imprime de una foto vieja: el siguiente latido
            // lo hace por `needsPrint`.
            alHechoYReleido = { buscar(orderId)?.takeIf { it.status in IMPRIMIBLES }?.let { imprimir(it) } },
        ) {
            repositorio.accept(orderId, minutos)
        }
    }

    /** Cuántas lecturas de la bandeja han salido: cada una toma su número ANTES de preguntar. */
    private var lecturasIniciadas = 0L

    /**
     * Pedidos a los que el servidor dijo «confirmando el pago» (409) -> la última lectura que ya había salido entonces. Siguen sin
     * poder aceptarse aunque la relectura falle; sólo una lectura posterior a ese número los suelta.
     */
    private val confirmando = mutableMapOf<String, Long>()

    private fun marcarConfirmando(orderId: String) {
        confirmando[orderId] = lecturasIniciadas
        val s = _secciones.value
        _secciones.value = s.copy(porAceptar = s.porAceptar.map { if (it.orderId == orderId) it.copy(cardStatus = "CONFIRMING") else it })
    }

    /** [motivo] es el código del servidor (`OUT_OF_ITEMS`, `CLOSED`, `TOO_BUSY`, `OTHER`). */
    fun rechazar(orderId: String, motivo: String) =
        ejecutar(orderId, permisoActualizar(), sonidoCambia = true) { repositorio.reject(orderId, motivo, null) }

    // La huella se toma ANTES de mandar la acción (null si no es un folio: la Bandeja de siempre).
    fun marcarListo(orderId: String) =
        ejecutar(orderId, permisoActualizar(), huella = huellaDe(_porPagar.value, orderId)) { repositorio.ready(orderId) }

    fun marcarEntregado(orderId: String) =
        ejecutar(orderId, permisoActualizar(), huella = huellaDe(_porPagar.value, orderId)) { repositorio.delivered(orderId) }

    fun cancelar(orderId: String) =
        ejecutar(orderId, permisoCancelar(), huella = huellaDe(_porPagar.value, orderId)) { repositorio.cancel(orderId) }

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
     * Tienda híbrida (A3): «Cobrar» de un folio de «Por pagar». Las MISMAS guardas que [cobrar] (un efectivo de este aparato sin
     * subir bloquea; con red el saldo del servidor, con abono lo que falta y nunca el total; sin red el último `remaining` de la
     * lista, sólo efectivo), más la relectura del folio con red: si su cuenta ya se canceló (venció o lo cancelaron) no abre el
     * cobro y dice «cóbralo como venta normal»; si otra caja ya lo cobró, lo dice con la hora. Abierto, el cobro lleva el texto
     * del folio vencido para el 400 de cuenta cancelada (decisión 16: ese efectivo en vivo se rechaza y su reserva se borra).
     */
    fun cobrarFolio(pedido: OnlineOrder) = abrirCobro(
        orderId = pedido.orderId,
        orderNumber = pedido.orderNumber,
        saldoGuardado = pedido.remainingCents,
        plataforma = null,
        origenConSaldo = com.avoqado.pos.payment.domain.OrigenDelCobro.PEDIDO_EN_LINEA,
        origenSinSaldo = com.avoqado.pos.payment.domain.OrigenDelCobro.PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO,
        // R14: cobrar lo que ya existe nunca depende del plan; el folio sólo se ve si el servidor tiene la sección.
        permitido = true,
        productos = pedido.items,
        folio = pedido,
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
        /** A3: el folio de «Por pagar» que se cobra (null = pedido de «Mi equipo» o de reparto, como siempre). */
        folio: OnlineOrder? = null,
    ) {
        if (!permisoActualizar() || !permisoCobrar() || !permitido) return
        if (_cobroAbierto.value != null) return
        if (orderId in _ocupados.value) return
        _ocupados.value = _ocupados.value + orderId
        viewModelScope.launch {
            try {
                // R100: UNA sola pregunta por todo el dinero de este aparato que el SERVIDOR todavía no confirma.
                val dinero = dineroSinConfirmar(venueActual(), orderId, esFolio = folio != null)
                if (dinero.bloquea) {
                    _aviso.value = dinero.aviso
                    return@launch
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
                        if (folio == null) {
                            saldoGuardado
                        } else if (cobradoConSaldoAqui(orderId)) {
                            // A1: esta caja ya cobró con saldo este folio y la copia guardada todavía no lo cuenta: su saldo es de
                            // antes del cobro. Se espera a la red (la marca la suelta la copia que ya lo cuenta).
                            _aviso.value = TextosPedidosEnLinea.FOLIO_COBRADO_CON_SALDO
                            return@launch
                        } else {
                            // R104: el saldo de un folio sin red sale SÓLO de la copia guardada, la misma contra la que se sueltan las
                            // marcas de tarjeta. Si no está ahí (p. ej. vino de una página extra), no se inventa ni se reusa uno.
                            val enLaCopia = try {
                                foliosGuardados()?.porPagar?.firstOrNull { it.orderId == orderId }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                            if (enLaCopia == null) {
                                _aviso.value = TextosPedidosEnLinea.FOLIO_SIN_SALDO_GUARDADO
                                return@launch
                            }
                            enLaCopia.remainingCents
                        }
                    } else {
                        // El servidor contestó otra cosa (404, 5xx, JSON roto): no se adivina con la bandeja.
                        Log.w(TAG, "Saldo de $orderNumber no leído: ${error.message}")
                        _aviso.value = TextosPedidosEnLinea.SALDO_NO_LEIDO
                        return@launch
                    }
                }
                // A3: con red se relee el folio. Su cuenta cancelada (venció o lo cancelaron) no se cobra: el SERVIDOR rechazaría el
                // efectivo en vivo (regla del founder del 3-oct). Saldada, ya la cobró otra caja. Las dos releen la lista.
                val releido = leido.getOrNull()
                if (folio != null && releido != null) {
                    val cerrada = releido.estadoDeLaCuenta in CUENTAS_CERRADAS
                    // Una cancelada «no debe» aunque nadie pagó: saldada sólo si está viva o si de verdad entró un cobro.
                    val pagada = releido.saldoCents <= 0L && (!cerrada || releido.pagadoA != null)
                    val aviso = when {
                        // Si el cobro salió de ESTE aparato (su fila ya subió), no se culpa a «otra caja».
                        pagada -> TextosPedidosEnLinea.folioYaPagado(releido.pagadoA?.let(::horaDelNegocio), enEstaCaja = cobradoAqui(orderId))
                        cerrada -> textoDeFolioCerrado(folio.acceptDeadlineAt, java.time.Instant.ofEpochMilli(ahoraMs()))
                        else -> null
                    }
                    if (aviso != null) {
                        _aviso.value = aviso
                        refrescar(imprimirPendientes = false)
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
                // (d) Una tarjeta en duda no frena el efectivo (founder, 2-nov «B»): sólo se avisa.
                _aviso.value = dinero.aviso
                _cobroAbierto.value = CobroDePedido(orderId = orderId, orderNumber = orderNumber, saldoCents = saldo.toInt(),
                    origen = if (leido.isSuccess) origenConSaldo else origenSinSaldo, plataforma = plataforma,
                    renglones = leido.getOrNull()?.let { renglonesParaVer(productos, it.precios, it.saldoCents) }.orEmpty(),
                    esFolio = folio != null, venceA = folio?.acceptDeadlineAt)
            } finally {
                _ocupados.value = _ocupados.value - orderId
            }
        }
    }

    /**
     * 🔴 R100 (como R89 en la TPV): TODO el dinero de [orderId] que ESTE aparato tiene y el SERVIDOR todavía no confirma. Lo consulta
     * cada «Cobrar» de la bandeja (folio, «Mi equipo» y reparto). En orden:
     * - (a) la cola de efectivo: en cola, o fallido / por revisar sin reconocer ⇒ bloquea con su texto de siempre;
     * - (b) un positivo de tarjeta que sigue en la lista durable (respuesta directa, desenlace tardío o conciliación) ⇒ bloquea;
     * - (c) la marca «aquí ya se cobró» que dejó ese positivo al salir de la lista ⇒ bloquea;
     * - (d) una tarjeta EN DUDA de la orden ⇒ NO bloquea (founder, 2-nov «B»): se cobra y se avisa;
     * - (e) A1: un cobro con SALDO mandado y sin confirmar ⇒ bloquea («Comprobando el cobro con saldo…»). Su marca, ya confirmado,
     *   sólo frena sin red (`cobradoConSaldoAqui` en [abrirCobro]): con red manda el saldo del servidor.
     * Si algo truena, falla CERRADO.
     */
    internal suspend fun dineroSinConfirmar(venueId: String?, orderId: String, esFolio: Boolean): DineroSinConfirmar = try {
        val efectivo = efectivoDelPedido(orderId)
        val tarjetas = cobrosConTarjeta(venueId).filter { it.orderId == orderId }
        val yaConTarjeta = if (esFolio) TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA else TextosPedidosEnLinea.PEDIDO_COBRADO_CON_TARJETA
        when {
            // Los dos de efectivo bloquean; el texto dice la verdad de quién lo sube (Ruling 21 #2).
            efectivo == EfectivoDelPedido.EN_COLA ->
                DineroSinConfirmar(bloquea = true, aviso = TextosPedidosEnLinea.COBRO_EN_EFECTIVO_GUARDADO)
            efectivo == EfectivoDelPedido.POR_REVISAR ->
                DineroSinConfirmar(bloquea = true, aviso = TextosPedidosEnLinea.COBRO_EN_EFECTIVO_POR_REVISAR)
            // (e) A1, Codex r1 #1: un cobro con saldo de esta orden que el servidor todavía no confirma (respuesta perdida, app
            // reiniciada). No se abre otro cobro con un importe de antes: el resolutor lo confirma en segundos con red.
            saldoEnDuda(orderId) -> DineroSinConfirmar(bloquea = true, aviso = com.avoqado.pos.giftcards.domain.TextosDeTarjetas.COMPROBANDO_COBRO)
            tarjetas.any { it.cobrado } -> DineroSinConfirmar(bloquea = true, aviso = yaConTarjeta)
            // Una marca de saldo (A1) NO frena aquí: con red manda el saldo del servidor; sin red la revisa `abrirCobro` (Codex r1 #4).
            foliosConTarjeta.marcasDe(venueId).any { it.orderId == orderId && !it.conSaldo } -> DineroSinConfirmar(bloquea = true, aviso = yaConTarjeta)
            tarjetas.isNotEmpty() -> DineroSinConfirmar(bloquea = false, aviso = com.avoqado.pos.payment.domain.CardChargeDecision.UNDETERMINED_MESSAGE)
            else -> DineroSinConfirmar.NADA
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo revisar el dinero sin confirmar de $orderId: ${e.message}")
        DineroSinConfirmar(bloquea = true, aviso = TextosPedidosEnLinea.SALDO_NO_LEIDO)
    }

    /** A1: ¿hay una marca «aquí ya se cobró con saldo» de [orderId] que la copia guardada todavía no cuenta? En duda, sí (cerrado). */
    private fun cobradoConSaldoAqui(orderId: String): Boolean = try {
        foliosConTarjeta.marcasDe(venueActual()).any { it.orderId == orderId && it.conSaldo }
    } catch (e: Exception) {
        true
    }

    /** ¿ESTE aparato ya subió un cobro de [orderId]? En duda, no: se dice lo de siempre («otra caja»). */
    private suspend fun cobradoAqui(orderId: String): Boolean = try {
        efectivoYaSubido(orderId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * El cobro se cerró (cobrado, encolado sin red o cancelado): se suelta y se relee la bandeja. 🔴 Nunca `delivered`: pagado
     * y «Listo» lo entrega el servidor en la siguiente lectura; pagado y en preparación, a su hora o con el botón «Entregado».
     */
    fun cobroTerminado() {
        if (_cobroAbierto.value == null) return
        _cobroAbierto.value = null
        viewModelScope.launch {
            // A3: lo cobrado sin red se mueve a «Por entregar» YA, sin esperar a que la bandeja conteste (o se rinda).
            releerCobradosEnElAparato()
            refrescar(imprimirPendientes = false)
        }
    }

    private fun ejecutar(
        clave: String,
        permitido: Boolean,
        textoExito: String? = null,
        sonidoCambia: Boolean = false,
        alHechoYReleido: () -> Unit = {},
        /** R100: la huella del folio tomada ANTES de la petición; con la acción confirmada se esconde hasta que el SERVIDOR cambie. */
        huella: HuellaDeFolio? = null,
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
                // Cualquier acción puede recibirlo: rechazar también, si otro aparato ya está cobrando (Codex ANDROID r2).
                if (resultado is AccionResultado.ConfirmandoPago) marcarConfirmando(clave)
                // Hecha (o ya no aplica): la tarjeta de antes no vuelve con sus botones aunque la relectura falle.
                if (huella != null && (resultado is AccionResultado.Hecho || resultado is AccionResultado.YaNoDisponible)) {
                    _ocultos.value = _ocultos.value + (clave to huella)
                    // R101: en la MISMA copia que la lista guardada, para que un reinicio no devuelva el botón viejo.
                    try {
                        guardarOcultos(_ocultos.value)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo guardar lo escondido de ${huella.seccion}: ${e.message}")
                    }
                }
                _aviso.value = when (resultado) {
                    AccionResultado.Hecho -> textoExito
                    is AccionResultado.YaNoDisponible -> TextosPedidosEnLinea.YA_NO_DISPONIBLE
                    AccionResultado.SinConexion -> TextosPedidosEnLinea.REINTENTA
                    is AccionResultado.ConfirmandoPago -> resultado.mensaje
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
        val LISTA_FOLIOS_NO_DISPONIBLE = ListaPorPagar(
            false, false, 600, com.avoqado.pos.onlineorders.domain.ConteosPorPagar(0, 0), emptyList(), emptyList(),
            com.avoqado.pos.onlineorders.domain.CursoresPorPagar(null, null),
        )
        /** Los nombres de sección que entiende `GET …/to-pay?section=`. */
        const val SECCION_POR_PAGAR = "toPay"
        const val SECCION_POR_ENTREGAR = "toDeliver"
        private val SECCIONES = listOf(SECCION_POR_PAGAR, SECCION_POR_ENTREGAR)
        const val CLAVE_PAUSA = "__pausa__"
        /** `Order.status` de una cuenta que ya no admite un cobro nuevo en vivo (A3). */
        private val CUENTAS_CERRADAS = setOf("CANCELLED", "DELETED")
        private val IMPRIMIBLES = setOf(OnlineOrderStatus.ACCEPTED, OnlineOrderStatus.READY)
        private val ABIERTOS = setOf(
            OnlineOrderStatus.PENDING_ACCEPTANCE, OnlineOrderStatus.ACCEPTED, OnlineOrderStatus.READY,
        )
    }
}

/** R100: lo que contesta `dineroSinConfirmar`. [bloquea] = «Cobrar» no abre; si no, [aviso] (si hay) se dice y se cobra igual. */
data class DineroSinConfirmar(val bloquea: Boolean, val aviso: String?) {
    companion object {
        val NADA = DineroSinConfirmar(bloquea = false, aviso = null)
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
    /** A3: un folio de «Por pagar» (el renglón dice «Folio W7K3P» y el 400 de cuenta cancelada lleva su texto). */
    val esFolio: Boolean = false,
    /** A3: la hora en que vence el folio, para decir «venció» o «se canceló» cuando el SERVIDOR rechaza el efectivo. */
    val venceA: java.time.Instant? = null,
) {
    /**
     * Lo que dice el cobro si el SERVIDOR rechaza el efectivo en vivo porque la cuenta ya se canceló, calculado AL FALLAR ([ahora]):
     * pasada su hora, venció; antes, otra caja lo canceló. null = no es un folio (como siempre).
     */
    fun textoSiLaCuentaSeCancelo(ahora: java.time.Instant): String? = if (esFolio) textoDeFolioCerrado(venceA, ahora) else null
}

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
                ?: if (cobro.esFolio) TextosPedidosEnLinea.folio(cobro.orderNumber) else TextosPedidosEnLinea.pedidoNumero(cobro.orderNumber),
            unitPrice = cobro.saldoCents,
        ),
    ),
    orderType = "PICKUP",
    renglonesParaVer = cobro.renglones,
)
