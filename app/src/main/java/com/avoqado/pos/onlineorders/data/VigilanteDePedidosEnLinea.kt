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
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.domain.TarjetaPorAbrir
import com.avoqado.pos.onlineorders.presentation.conLoCobradoEnElAparato
import com.avoqado.pos.onlineorders.presentation.numeroDeSeccion
import com.avoqado.pos.onlineorders.presentation.cobradosConTarjetaEnLaLista
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.core.data.sync.SyncOutbox
import javax.inject.Provider
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * - D662 (founder, 18-nov): su compás manda la cabecera de bandeja mientras la ventana SE VE ([seVe]): con Avoqado a la vista en
 *   CUALQUIER pantalla, la tienda de «Mi equipo» sigue abierta (el SERVIDOR sólo lo cuenta con `orders:update`). En ANDROID el
 *   vigilante ya sólo corre con la app al frente; en WINDOWS, minimizada no late. El «sondear ya» nunca late (puede salir después
 *   de `onStop`). La cabecera de fondo la pone el repositorio siempre, así un 403 no abre el PIN del gerente cada 10 s.
 * - Sin red es un estado normal: [sinConexion] se enciende, la última lista se conserva (y sigue sonando) y se reintenta.
 * - En segundo plano ([onStop]) se detiene todo: no hay promesa de avisar con la app dormida.
 * - El sonido usa sólo `RingtoneManager...play()`, lo único que el adaptador de WINDOWS tiene.
 * - D661 (Task 11): también suena mientras el SERVIDOR diga que hay pedidos pagados con tarjeta en línea por abrir
 *   ([tarjetasPorAbrir], de la lectura de «Por pagar», que obedece su `pollSeconds`). Sólo una lectura EN VIVO de esta vuelta al
 *   frente hace sonar: la copia guardada, una respuesta más vieja que la copia o lo último leído antes de quedarse sin red se ven,
 *   pero no suenan (ese pedido pudo abrirse hace rato). Un pedido que otra caja está cobrando (`cobrando`) no suena.
 * - D661: la comanda de lo cobrado sale de aquí, no de la pantalla: cada lectura en vivo trae `comandasPorImprimir`; sus renglones
 *   salen de «Por entregar» o de leer ESE pedido (Ruling 14), y el reclamo del SERVIDOR decide quién la imprime.
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
    /** Tienda híbrida: lee «Por pagar»; `conLatido` avisa al servidor que este aparato cobra folios. null = sin folios. */
    private val consultarPorPagar: (suspend (conLatido: Boolean) -> Result<ListaPorPagar>)? = null,
    /** `payments:create`: sólo quien cobra late (un aparato que no cobra no cuenta como «hay quien cobre folios»). */
    private val puedeCobrar: () -> Boolean = { false },
    /** La última lista que este aparato guardó: el número del menú de un arranque en frío sin red sale de ahí. */
    private val foliosGuardados: suspend () -> ListaPorPagar? = { null },
    /** A3: los pedidos con efectivo de ESTE aparato en la cola: no cuentan como «por pagar» (igual que en la pantalla). */
    private val efectivoEnCola: suspend () -> Map<String, Long> = { emptyMap() },
    /**
     * R100: lo que ESTE aparato cobró con tarjeta en el negocio: las marcas y los positivos que siguen en la lista durable (orden ->
     * monto). Mientras la lista no lo cuente, tampoco es «por pagar».
     */
    private val foliosConTarjeta: (venueId: String?) -> List<FolioCobradoConTarjeta> = { emptyList() },
    private val positivosConTarjeta: (venueId: String?) -> Map<String, Long> = { emptyMap() },
    /** D662: ¿la ventana se ve? ANDROID siempre (el ciclo ya para en segundo plano); WINDOWS minimizada = no. */
    private val seVe: () -> Boolean = { VentanaALaVista.aLaVista.value },
    /** D661 (Ruling 14): lee UN pedido por su propio camino, para la comanda de uno que no está en la página de «Por entregar». */
    private val leerPedido: suspend (orderId: String) -> Result<OnlineOrder> = { Result.failure(IllegalStateException("sin lector")) },
    /** D661: saca la comanda (reclamo + papel + confirmación, `ImpresionDePedidoEnLinea`). */
    private val imprimirComanda: suspend (OnlineOrder) -> Unit = {},
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        repositorio: OnlineOrdersRepository,
        roleManager: RoleManager,
        planManager: PlanManager,
        secureStorage: SecureStorage,
        @ApplicationContext contexto: Context,
        cashPaymentRepository: CashPaymentRepository,
        foliosCobradosConTarjeta: FoliosCobradosConTarjeta,
        terminalPaymentService: com.avoqado.pos.payment.data.TerminalPaymentService,
        impresion: ImpresionDePedidoEnLinea,
        syncOutbox: Provider<SyncOutbox>,
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
        consultarPorPagar = { conLatido -> repositorio.porPagar(conLatido) },
        puedeCobrar = { roleManager.hasVenuePermission("payments:create", setOf("WAITER", "CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")) },
        foliosGuardados = { repositorio.porPagarGuardado() },
        efectivoEnCola = cashPaymentRepository::efectivoEnColaPorPedido,
        foliosConTarjeta = foliosCobradosConTarjeta::marcasDe,
        positivosConTarjeta = { venue ->
            terminalPaymentService.cobrosConTarjetaDelNegocio(venue).filter { it.cobrado && it.orderId != null }
                .associate { it.orderId!! to (it.amountCents ?: 0).toLong() }
        },
        leerPedido = { orderId -> repositorio.pedidoEnLinea(orderId) },
        // El deviceId del outbox, NO uno nuevo (regla de offline-first): sin él no se reclama nada.
        imprimirComanda = { pedido ->
            val deviceId = try { syncOutbox.get().deviceId } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (deviceId != null) impresion.imprimirSiToca(pedido, deviceId)
        },
    )

    private val _pendientes = MutableStateFlow<List<OnlineOrder>>(emptyList())

    /** Los pedidos por aceptar del último sondeo bueno (se conserva si falla la red). */
    val pendientes: StateFlow<List<OnlineOrder>> = _pendientes.asStateFlow()

    private val _tarjetasPorAbrir = MutableStateFlow<List<TarjetaPorAbrir>>(emptyList())

    /**
     * D661: los pagados con tarjeta en línea que esperan que una caja los abra: la última lista leída (en vivo o, en un arranque sin red,
     * la guardada). Es el número del menú; sólo suena con [tarjetasVigentes].
     */
    val tarjetasPorAbrir: StateFlow<List<TarjetaPorAbrir>> = _tarjetasPorAbrir.asStateFlow()

    /**
     * D661: [tarjetasPorAbrir] viene de una lectura EN VIVO de esta vuelta al frente. Falso al arrancar ([onStart]), con la copia guardada
     * y en cuanto una lectura falla: una lista vieja nunca suena.
     */
    private val tarjetasVigentes = MutableStateFlow(false)

    /** D661: la sucursal de la que vienen [tarjetasPorAbrir]; null = de ninguna. */
    @Volatile private var venueDeLasTarjetas: String? = null

    private fun olvidarTarjetas() {
        _tarjetasPorAbrir.value = emptyList()
        tarjetasVigentes.value = false
        venueDeLasTarjetas = null
    }

    /** D661: comandas que este aparato ya está buscando (lectura del pedido + reclamo): una lectura nueva no las repite a la vez. */
    private val comandasEnCurso: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Una lectura de «Por pagar» a la vez: la del compás y la de [sondearFoliosYa] no se pisan. */
    private val turnoDeFolios = Mutex()

    /**
     * D661: relee «Por pagar» ya (la pantalla abrió un pedido pagado en línea): el sonido para en esta lectura y no en el siguiente compás
     * (hasta 30 s). Sin ciclo vivo (app en segundo plano) no consulta nada. No mueve el compás de los folios.
     */
    fun sondearFoliosYa() {
        if (cicloFolios == null || consultarPorPagar == null) return
        scope.launch { sondearFoliosEnTurno() }
    }

    private suspend fun sondearFoliosEnTurno(): Long = turnoDeFolios.withLock { sondearFolios() }

    private val _sinConexion = MutableStateFlow(false)

    /** El último sondeo falló: texto neutro «Sin conexión: no llegan pedidos nuevos», nunca rojo. */
    val sinConexion: StateFlow<Boolean> = _sinConexion.asStateFlow()

    private val _hayPedidosAbiertos = MutableStateFlow(false)

    /** `pendientes + activos > 0` según los conteos del último sondeo bueno (el menú se ve sin plan mientras haya pedidos). */
    val hayPedidosAbiertos: StateFlow<Boolean> = _hayPedidosAbiertos.asStateFlow()

    private val _foliosPorPagar = MutableStateFlow(0)

    /**
     * Tienda híbrida: cuántos folios esperan pago en caja (el número que se suma a la insignia del menú). Nunca contradice la lista:
     * ver `numeroDeSeccion`. Se conserva si falla la red; 0 si el servidor dice que la sección no existe para esta persona.
     */
    val foliosPorPagar: StateFlow<Int> = _foliosPorPagar.asStateFlow()

    /** La última lista de folios aplicada: sin red se vuelve a contar con la cola del aparato (lo cobrado aquí ya no cuenta). */
    @Volatile private var ultimaListaDeFolios: ListaPorPagar? = null

    /** Hay folios por pagar o por entregar (conteos del último sondeo bueno): el menú se atiende aunque el plan haya vencido. */
    @Volatile private var hayFolios = false

    /** Del inbox, sin folios: se junta con [hayFolios] en [_hayPedidosAbiertos]. */
    @Volatile private var hayAbiertosEnInbox = false

    private fun actualizarAbiertos() { _hayPedidosAbiertos.value = hayAbiertosEnInbox || hayFolios }

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
    private var cicloFolios: Job? = null
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
        hayAbiertosEnInbox = false
        hayFolios = false
        huboLecturaDeFolios = false
        ultimaListaDeFolios = null
        _foliosPorPagar.value = 0
        olvidarTarjetas()
        actualizarAbiertos()
        _sinConexion.value = false
    }

    /** Falso desde cada [onStart] hasta el primer sondeo BUENO: con datos viejos del segundo plano no suena nada. */
    private val sonidoListo = MutableStateFlow(false)

    @Synchronized
    override fun onStart(owner: LifecycleOwner) {
        ciclo?.cancel()
        cicloFolios?.cancel()
        sonido?.cancel()
        sonidoListo.value = false
        // D661: lo leído antes de irse a segundo plano no suena: espera a una lectura en vivo de esta vuelta.
        tarjetasVigentes.value = false
        sonido = scope.launch {
            combine(_pendientes, silenciados, sonidoListo, _tarjetasPorAbrir, tarjetasVigentes) { lista, callados, listo, tarjetas, vigentes ->
                val porAceptar = if (listo) lista.map { it.orderId }.filter { it !in callados } else emptyList()
                // D661: «Silenciar» es del banner de «Mi equipo»; un pagado en línea suena hasta que una caja lo abre.
                val porAbrir = if (vigentes) tarjetas.filter { !it.cobrando }.map { "$PREFIJO_TARJETA${it.orderId}" } else emptyList()
                (porAceptar + porAbrir).toSet()
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
                esperarAlSiguiente()
            }
        }
        // Los folios van a su propio compás (`pollSeconds` del servidor: 30 s con la tienda híbrida encendida, 10 min si no).
        cicloFolios = scope.launch {
            if (consultarPorPagar == null) return@launch
            while (isActive) delay(sondearFoliosEnTurno())
        }
    }

    /** Un sondeo ya (la bandeja aceptó o rechazó): el sonido para en esta petición y no en el siguiente compás. */
    fun sondearYa() {
        if (ciclo == null) return // sin ciclo vivo (app en segundo plano) no se consulta nada
        // D662: este sondeo rápido nunca late: puede salir después de `onStop`. La Bandeja visible late por su cuenta.
        // D662 (Codex p1-r2 #4): a lo más UNO en fila. Varios «aceptar» seguidos no forman fila delante del latido del compás; el que
        // se pide mientras otro corre sí sale después (su resultado no se pierde).
        if (!sondeoYaEnFila.compareAndSet(false, true)) return
        scope.launch {
            var tomado = false
            try {
                turnoDeSondeo.withLock {
                    tomado = true
                    sondeoYaEnFila.set(false)
                    sondeo(conLatido = false)
                }
            } finally {
                if (!tomado) sondeoYaEnFila.set(false)
            }
        }
    }

    private val sondeoYaEnFila = AtomicBoolean(false)

    /** Detiene la consulta y el sonido. La última lista queda tal cual para la insignia al volver. */
    @Synchronized
    override fun onStop(owner: LifecycleOwner) {
        ciclo?.cancel(); ciclo = null
        cicloFolios?.cancel(); cicloFolios = null
        sonido?.cancel(); sonido = null
    }

    /** Un sondeo a la vez: el del compás y el de [sondearYa] no se pisan. */
    private val turnoDeSondeo = Mutex()

    private suspend fun sondearUnaVez(conLatido: Boolean = true) = turnoDeSondeo.withLock { sondeo(conLatido) }

    private suspend fun sondeo(conLatido: Boolean) {
        if (!puedeLeer()) {
            // Sin sesión o sin orders:read: no se consulta NADA y no queda nada colgado (ni lista, ni sonido, ni aviso).
            olvidarDatos()
            return
        }
        val venue = venueActual()
        // Cambió la sucursal desde el último sondeo bueno: lo de la anterior no se queda ni un instante (aunque este sondeo falle).
        if (venueDeLosDatos != null && venueDeLosDatos != venue) olvidarDatos()
        // D662: late sólo el compás del ciclo vivo, con la ventana a la vista y con la tienda encendida según la última lectura
        // (Codex p1 #4: con la tienda apagada el latido sólo le costaría una escritura al SERVIDOR).
        val latiria = conLatido && seVe() && ciclo != null
        val callaPorApagada = latiria && ultimosConteos?.storeOff == true
        val resultado = try {
            consultar("pending", latiria && !callaPorApagada)
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
            hayAbiertosEnInbox = inbox.pending + inbox.active > 0
            actualizarAbiertos()
            _sinConexion.value = false
            sonidoListo.value = true
        }.onFailure {
            // Se conserva la última lista y se reintenta. «Sin conexión» sólo si de verdad no hay red: un 403, un 5xx, la falta
            // de sesión o un JSON roto no son «sin conexión» (el texto mentiría).
            Log.d(TAG, "Sondeo sin respuesta: ${it.message}")
            if (it is IOException) _sinConexion.value = true
        }
        // D662 (Codex p1-r2 #1): la acaban de prender en el panel y esta consulta salió callada por la lectura vieja: la caja late YA,
        // sin esperar otra vuelta (ahora `ultimosConteos` dice encendida, así que esto pasa a lo más una vez). Sólo si la caja SIGUE
        // a la vista (Codex p1-r3 #1): una consulta extra sin latido sólo retrasaría al compás.
        if (callaPorApagada && resultado.getOrNull()?.storeOff == false && seVe() && ciclo != null) sondeo(conLatido)
    }

    /** Hubo una lectura (buena o la guardada) de folios en esta sucursal: la guardada no vuelve a pisar un «no disponible». */
    @Volatile private var huboLecturaDeFolios = false

    /** Un sondeo de «Por pagar» (con latido sólo si la persona cobra). Devuelve cuánto esperar al siguiente. Nunca suena. */
    private suspend fun sondearFolios(): Long {
        val consultar = consultarPorPagar ?: return INTERVALO_MS
        if (!puedeLeer()) {
            huboLecturaDeFolios = false
            ultimaListaDeFolios = null
            _foliosPorPagar.value = 0
            hayFolios = false
            olvidarTarjetas()
            actualizarAbiertos()
            return INTERVALO_MS
        }
        val venue = venueActual()
        // D661: lo de otra sucursal no se queda ni un instante (ni en el menú ni sonando), aunque esta lectura falle.
        if (venueDeLasTarjetas != null && venueDeLasTarjetas != venue) {
            olvidarTarjetas()
            actualizarAbiertos()
        }
        val resultado = try {
            consultar(puedeCobrar())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (venueActual() != venue) {
            huboLecturaDeFolios = false
            // D661: lo leído es de la sucursal de antes: ni se queda ni suena.
            olvidarTarjetas()
            actualizarAbiertos()
            return INTERVALO_MS // el olvido de lo demás de la sucursal anterior lo hace el sondeo principal
        }
        val lista = resultado.getOrNull()
        if (lista != null) {
            // R102: una respuesta más vieja que la copia guardada (la bandeja ya guardó una más nueva) no mueve el número.
            if (!lista.masViejaQueLaCopia) {
                huboLecturaDeFolios = true
                aplicarFolios(lista, venue)
                // D661: sólo una lectura EN VIVO hace sonar y saca comandas.
                tarjetasVigentes.value = true
                imprimirComandasPendientes(lista, venue)
            }
            return (lista.pollSeconds.coerceIn(10, 600) * 1000L)
        }
        // D661: sin respuesta, lo último leído se sigue viendo pero ya no suena (pudo abrirse en otra caja mientras tanto).
        tarjetasVigentes.value = false
        Log.d(TAG, "Folios sin respuesta: ${resultado.exceptionOrNull()?.message}")
        if (!huboLecturaDeFolios) {
            // Arranque en frío sin red: el número del menú sale de lo que este aparato guardó (igual que la pantalla).
            val guardada = try { foliosGuardados() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (guardada != null && venueActual() == venue) {
                huboLecturaDeFolios = true
                aplicarFolios(guardada, venue)
            }
        } else {
            // Sin red, la última lista se vuelve a contar: lo cobrado aquí desde entonces ya está en la cola y no se cobra otra vez.
            ultimaListaDeFolios?.let { aplicarFolios(it, venue) }
        }
        return INTERVALO_MS
    }

    /**
     * D661 (r4 #3, r5 #2): la comanda de lo cobrado sale de una lectura FRESCA de `comandasPorImprimir`, aquí en el vigilante global y no en
     * la pantalla: aunque la respuesta de abrir se haya perdido, la cajera haya salido de la pantalla, la app se haya reiniciado, la lista
     * esté paginada o el día ya se haya cerrado. Cada una corre aparte (la lectura del pedido y el papel no atrasan el compás); el reclamo
     * del SERVIDOR decide quién la imprime y su confirmación la saca de la lista.
     */
    private fun imprimirComandasPendientes(lista: ListaPorPagar, venue: String?) {
        if (!lista.disponible) return
        for (comanda in lista.comandasPorImprimir) {
            if (!comandasEnCurso.add(comanda.orderId)) continue
            scope.launch {
                try {
                    // Sus renglones: la fila de «Por entregar» de esta MISMA lectura o, si no está ahí, ESE pedido por su camino (Ruling 14).
                    val pedido = lista.porEntregar.firstOrNull { it.orderId == comanda.orderId }
                        ?: leerPedido(comanda.orderId).onFailure {
                            Log.d(TAG, "Comanda ${comanda.orderNumber}: no se pudo leer el pedido (${it.message}); va en la siguiente lectura")
                        }.getOrNull()
                    // Ya no la pide (la imprimió otra caja o se está devolviendo) o cambió la sucursal: nada.
                    if (pedido == null || !pedido.needsPrint || venueActual() != venue) return@launch
                    imprimirComanda(pedido)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo intentar la comanda de ${comanda.orderNumber}: ${e.message}")
                } finally {
                    comandasEnCurso.remove(comanda.orderId)
                }
            }
        }
    }

    private suspend fun aplicarFolios(lista: ListaPorPagar, venue: String?) {
        ultimaListaDeFolios = lista
        // D661: la lista de pagados por abrir va con su sucursal; un «no disponible» la vacía.
        _tarjetasPorAbrir.value = if (lista.disponible) lista.tarjetasPorAbrir else emptyList()
        venueDeLasTarjetas = venue
        if (!lista.disponible) {
            _foliosPorPagar.value = 0
            hayFolios = false
        } else {
            // A3 (m2): el mismo número que la pantalla: lo cobrado en efectivo en este aparato que aún no sube ya no está «por pagar».
            val enCola = try { efectivoEnCola().keys } catch (e: CancellationException) { throw e } catch (e: Exception) { emptySet() }
            // R100: lo cobrado aquí con tarjeta que esta lista todavía no cuenta, igual que en la pantalla.
            val venue = venueActual()
            val conTarjeta = try {
                cobradosConTarjetaEnLaLista(foliosConTarjeta(venue), positivosConTarjeta(venue), lista).keys
            } catch (e: Exception) {
                emptySet()
            }
            val vista = conLoCobradoEnElAparato(lista, enCola + conTarjeta)
            _foliosPorPagar.value = numeroDeSeccion(vista.counts.toPay, vista.porPagar.size, vista.nextCursors.toPay != null)
            hayFolios = lista.counts.toPay + lista.counts.toDeliver > 0 || lista.porPagar.isNotEmpty() || lista.porEntregar.isNotEmpty() ||
                // D661: un pagado en línea por abrir también abre el menú, aunque el plan haya vencido.
                lista.tarjetasPorAbrir.isNotEmpty()
        }
        actualizarAbiertos()
    }

    /**
     * D662 (Codex p1 #3): la espera larga (sin plan, o con la tienda apagada) se corta si cambia la sucursal o vuelve el plan; se revisa
     * en el aparato cada [INTERVALO_MS], sin tocar la red. Así una caja a la vista no deja «fuera de línea» la tienda de la sucursal a
     * la que acaba de cambiar.
     */
    private suspend fun esperarAlSiguiente() {
        // Codex p1-r2 #3: sucursal y plan se anotan ANTES de calcular la espera; un cambio entre las dos lecturas corta la espera.
        val venue = venueActual()
        val conPlan = tienePlan()
        val espera = proximoIntervalo()
        if (espera <= INTERVALO_MS) return delay(espera)
        var resto = espera
        while (resto > 0) {
            val paso = minOf(resto, INTERVALO_MS)
            delay(paso)
            resto -= paso
            if (venueActual() != venue || tienePlan() != conPlan) return
        }
    }

    private fun proximoIntervalo(): Long {
        val ultimo = ultimosConteos
        if (!puedeLeer()) return INTERVALO_MS
        // D662: los 5 min sin plan sólo con la tienda APAGADA leída. Encendida, su latido no puede esperar 5 min: si el plan vuelve desde
        // otro equipo, la tienda no se queda «fuera de línea» con la caja a la vista (un primer sondeo fallido tampoco la activa).
        if (!tienePlan() && !_hayPedidosAbiertos.value && ultimo?.storeOff == true) return INTERVALO_SIN_PLAN_MS
        if (ultimo != null && ultimo.storeOff && ultimo.pending == 0) return INTERVALO_TIENDA_APAGADA_MS
        return INTERVALO_MS
    }

    companion object {
        const val INTERVALO_MS = 10_000L
        const val INTERVALO_TIENDA_APAGADA_MS = 60_000L
        const val INTERVALO_SIN_PLAN_MS = 5 * 60_000L
        const val REPETIR_SONIDO_MS = 4_000L
        /** D661: el id de un pagado en línea en el conjunto que suena (nunca choca con el de un pedido por aceptar). */
        private const val PREFIJO_TARJETA = "tarjeta:"

        private fun sonarAviso(contexto: Context) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(contexto, uri)?.play()
        }
    }
}
