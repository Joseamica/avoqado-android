package com.avoqado.pos.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrintRoutingMapper
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.tables.data.AddOrderItemRequest
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.OrderDetailItem
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.round

/**
 * TABLE_SERVICE (PRO) — state for the dedicated table screen (TableOrderScreen).
 *
 * Square's check model, two halves:
 * - SENT (server truth): the open order fetched via getOrderDetail — items
 *   grouped by course with their send time (createdAt == fire moment).
 * - PENDING (local only): lines picked from the grid, each tagged with the
 *   course selected at add time. They only exist on the server after "Enviar".
 *
 * The quick-sale register knows nothing about any of this — table ordering
 * lives here; paying still rides the proven PAYING seam through the register.
 */
/**
 * Qué se cobra: lo que el server ya conoce MÁS lo que salió por el outbox sin red.
 * `sessionTotalCents` es sólo el último recurso — vale 0 en las mesas provisionales,
 * y confiar en él dejaba el botón "Pagar" muerto sin internet (bug 2026-07-27).
 */
internal fun payableCents(fromCheckCents: Int, queuedCents: Int, sessionTotalCents: Int): Int =
    (fromCheckCents + queuedCents).takeIf { it > 0 } ?: sessionTotalCents

@HiltViewModel
class TableOrderViewModel @Inject constructor(
    private val repository: TableServiceRepository,
    val tableSession: TableSession,
    private val printConfigRepository: PrintConfigRepository,
    private val comandaPrinter: ComandaPrinter,
    private val printerService: PrinterService,
    private val secureStorage: SecureStorage,
    private val syncOutbox: com.avoqado.pos.core.data.sync.SyncOutbox,
    private val productsRepository: com.avoqado.pos.pos.data.ProductsRepository,
    private val connectivityMonitor: com.avoqado.pos.core.util.ConnectivityMonitor,
    /** "Marcar entrada/salida" (Acciones) reusa el reloj checador tal cual. */
    val timeEntryRepository: com.avoqado.pos.timeclock.data.TimeEntryRepository,
) : ViewModel() {

    /** Json para serializar payloads de intents (mismas opciones que la red). */
    private val wireJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** A not-yet-sent line: the cart item + the course it will fire under. */
    data class PendingLine(
        val item: CartItem,
        val course: String?,
        val seat: Int? = null,
        /**
         * Sólo en líneas enviadas SIN red: el `sync:<intentId>:<idx>` que el
         * reducer le va a poner a esta fila al aplicar el ADD_ITEMS. Es el
         * único identificador que existe antes de sincronizar, y es lo que
         * permite separar un cheque que todavía no tiene ids de server.
         */
        val externalId: String? = null,
    )

    /** Floor snapshot — the header reads covers/openedAt for the active table. */
    val floorTables: StateFlow<List<com.avoqado.pos.tables.data.DiningTable>> = repository.tables

    private val _check = MutableStateFlow<OrderDetail?>(null)
    val check: StateFlow<OrderDetail?> = _check.asStateFlow()

    /**
     * Propiedad de mesa ("Solo el propietario puede modificar sus mesas"):
     * el cheque es de OTRO mesero, el switch del venue está encendido y yo no
     * tengo 'tables:manage-all' → la pantalla se pinta read-only. El server
     * refuerza la regla de todos modos (403 TABLE_OWNED_BY_OTHER).
     */
    val readOnlyCheck: StateFlow<Boolean> =
        combine(_check, repository.ownership) { check, ownership ->
            ownership.isLockedForMe(check?.waiter?.id)
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /** Nombre del dueño para el banner "Mesa de {mesero} — solo lectura". */
    val lockOwnerName: StateFlow<String?> =
        combine(_check, repository.ownership) { check, ownership ->
            if (ownership.isLockedForMe(check?.waiter?.id)) check?.waiter?.name else null
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    private val _isLoadingCheck = MutableStateFlow(false)
    val isLoadingCheck: StateFlow<Boolean> = _isLoadingCheck.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingLine>>(emptyList())
    val pending: StateFlow<List<PendingLine>> = _pending.asStateFlow()

    /**
     * Offline-first: rondas ya "enviadas" SIN red — impresas en cocina y
     * encoladas al outbox, esperando ack del replay ("Por sincronizar").
     */
    private val _queued = MutableStateFlow<List<PendingLine>>(emptyList())
    val queued: StateFlow<List<PendingLine>> = _queued.asStateFlow()

    /** Pendientes de sincronizar del outbox (badge global de la pantalla). */
    val outboxPending: StateFlow<Int> = syncOutbox.pendingCount

    init {
        // Offline-first: cuando el replay confirma intents de ESTA sesión, el
        // server ya absorbió las rondas — vaciamos "Por sincronizar" y
        // recargamos el cheque (server reconciliation). La promoción de la
        // sesión provisional la hace TableSyncCoordinator.
        viewModelScope.launch {
            syncOutbox.acks.collect { ack ->
                if (!ack.isAcked) return@collect
                val session = tableSession.current() ?: return@collect
                val result = ack.result ?: return@collect
                val ackOrder = result["orderId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                val localRef = result["localOrderId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                if (ackOrder == session.orderId || localRef == session.orderId) {
                    _queued.value = emptyList()
                    loadCheck()
                }
            }
        }
    }

    /** Selected course slot (null = "Inmediato"). New grid picks land here. */
    private val _selectedCourse = MutableStateFlow<String?>(null)
    val selectedCourse: StateFlow<String?> = _selectedCourse.asStateFlow()

    /** "Más platos": extra slots beyond the base list ("Plato 5", "Plato 6"...). */
    private val _extraCourses = MutableStateFlow<List<String>>(emptyList())
    val extraCourses: StateFlow<List<String>> = _extraCourses.asStateFlow()

    private val _hideSent = MutableStateFlow(false)
    val hideSent: StateFlow<Boolean> = _hideSent.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val venueId: String? get() = secureStorage.venueId

    companion object {
        /** Base slots, always visible in the pending card (Square-style). */
        val BASE_COURSES: List<String?> = listOf(null, "Aperitivos", "Principales", "Postres")
    }

    // MARK: - Check (server truth)

    fun loadCheck() {
        val session = tableSession.current() ?: return
        // Sesión provisional (mesa abierta offline): la orden aún no existe en
        // el server — no hay cheque que cargar ni error que mostrar. El panel
        // vive de pending + queued hasta que el ack promueva la sesión.
        if (session.isProvisional) {
            _check.value = null
            return
        }
        val vId = venueId ?: return
        viewModelScope.launch {
            _isLoadingCheck.value = true
            repository.getOrderDetail(vId, session.orderId).fold(
                onSuccess = { detail ->
                    _check.value = detail
                    // Toda acción del panel incrementa Order.version en el server;
                    // sin refrescarla, el siguiente Enviar da 409 (auditoría).
                    detail.version?.let { tableSession.updateVersion(it) }
                },
                onFailure = { e ->
                    // 🔴 Sin red NO es un error: es el estado normal del local
                    // en un apagón. Avisarlo aquí hacía que el mesero viera
                    // "No se pudo actualizar la cuenta" CADA vez que entraba a
                    // una mesa, con la cuenta perfectamente bien. Ese ruido
                    // entrena a ignorar los avisos, y el día que salga uno de
                    // verdad también lo van a ignorar. El banner naranja de
                    // arriba ya comunica que no hay conexión.
                    if (!isNetworkError(e)) {
                        _actionMessage.value = "No se pudo actualizar la cuenta"
                    }
                },
            )
            _isLoadingCheck.value = false
            // El saldo de puntos depende del cliente adjunto, que viene en el cheque.
            loadLoyalty()
        }
    }

    fun toggleHideSent() {
        _hideSent.value = !_hideSent.value
    }

    // MARK: - Pending (local round being built)

    fun selectCourse(course: String?) {
        _selectedCourse.value = course
    }

    /** "Más platos": adds the next numbered slot and selects it. */
    fun addExtraCourse() {
        val next = "Plato ${BASE_COURSES.size + _extraCourses.value.size + 1}"
        _extraCourses.value = _extraCourses.value + next
        _selectedCourse.value = next
    }

    /** Grid tap without modifiers. Dedupe is COURSE-aware: the same product on
     *  two courses must stay two lines (they fire at different moments). */
    fun addProduct(product: Product) {
        // Guard de inventario (búsqueda/escáner llegan sin pasar por el tile).
        if (product.isOutOfStock) {
            _actionMessage.value = "\"${product.name}\" está agotado"
            return
        }
        val course = _selectedCourse.value
        val existing = _pending.value.firstOrNull { line ->
            line.course == course &&
                (line.item.type as? CartItemType.ProductItem)?.productId == product.id &&
                line.item.selectedModifiers.isEmpty()
        }
        _pending.value = if (existing != null) {
            _pending.value.map { line ->
                if (line === existing) line.copy(item = line.item.copy(quantity = line.item.quantity + 1)) else line
            }
        } else {
            _pending.value + PendingLine(
                item = CartItem(
                    type = CartItemType.ProductItem(product.id),
                    name = product.name,
                    unitPrice = product.priceInCents,
                    imageUrl = product.imageUrl,
                    colorHex = product.color,
                    categoryId = product.categoryId,
                ),
                course = course,
            )
        }
    }

    /** Detail-panel add (modifiers/notes/cortesía). Always its own line. */
    fun addProductWithModifiers(
        product: Product,
        quantity: Int,
        modifiers: List<SelectedModifier>,
        note: String?,
        isCortesia: Boolean = false,
        cortesiaReason: String? = null,
    ) {
        if (product.isOutOfStock) {
            _actionMessage.value = "\"${product.name}\" está agotado"
            return
        }
        _pending.value = _pending.value + PendingLine(
            item = CartItem(
                type = CartItemType.ProductItem(product.id),
                name = product.name,
                unitPrice = product.priceInCents,
                quantity = quantity,
                imageUrl = product.imageUrl,
                colorHex = product.color,
                categoryId = product.categoryId,
                selectedModifiers = modifiers,
                itemNote = note,
                isCortesia = isCortesia,
                cortesiaReason = cortesiaReason,
            ),
            course = _selectedCourse.value,
        )
    }

    /** Asiento de una línea pendiente: cicla — → A1 → ... → Amax → —. */
    fun cyclePendingSeat(lineItemId: String, maxSeats: Int) {
        _pending.value = _pending.value.map { line ->
            if (line.item.id != lineItemId) line
            else {
                val next = when (val cur = line.seat) {
                    null -> 1
                    else -> if (cur >= maxSeats) null else cur + 1
                }
                line.copy(seat = next)
            }
        }
    }

    fun removePending(lineItemId: String) {
        _pending.value = _pending.value.filterNot { it.item.id == lineItemId }
    }

    /** "Borrar nuevos artículos" (Acciones): drops every unsent line. */
    fun clearPending() {
        _pending.value = emptyList()
        _actionMessage.value = "Artículos sin enviar borrados"
    }

    /** Criterios de "Ordenar carrito" (Square). */
    enum class CartSort(val label: String) {
        ASIENTO("Por asiento"),
        NOMBRE("Por nombre"),
        PRECIO("Por precio"),
    }

    /** "Ordenar carrito": reordena SOLO las líneas pendientes. Lo ya enviado
     *  vive en el server ordenado por hora de envío y no se toca. */
    fun sortPending(mode: CartSort) {
        _pending.value = when (mode) {
            CartSort.ASIENTO -> _pending.value.sortedWith(
                compareBy({ it.seat ?: Int.MAX_VALUE }, { it.item.name.lowercase() }),
            )
            CartSort.NOMBRE -> _pending.value.sortedBy { it.item.name.lowercase() }
            CartSort.PRECIO -> _pending.value.sortedByDescending { it.item.unitPrice }
        }
        _actionMessage.value = "Carrito ordenado — ${mode.label.lowercase()}"
    }

    /** ¿Hay impresora de recibos? Square deshabilita "Caja abierta" sin caja. */
    val hasCashDrawer: Boolean
        get() = printerService.getDefaultPrinter(PrinterRole.RECEIPT) != null

    /**
     * Por qué NO se puede imprimir la pre-cuenta ahora, o null si sí se puede.
     *
     * El botón se habilitaba sólo por el subtotal, así que sin impresora
     * configurada se veía activo y el toque no hacía nada visible (el aviso
     * salía por Toast, que en la Sunmi queda DETRÁS de la pantalla del
     * cliente). Un botón que se ve disponible y no responde parece app rota.
     */
    val printPreBillBlockedReason: String?
        get() = when {
            printerService.getDefaultPrinter(PrinterRole.RECEIPT) == null ->
                "No hay impresora de recibos configurada. Ve a Más › Impresora para elegir una."
            _check.value == null ->
                "La cuenta todavía no se puede leer del servidor. Sin conexión no se imprime la pre-cuenta."
            else -> null
        }

    /** "Caja abierta": pulso ESC/POS al cajón por la impresora de recibos. */
    fun openCashDrawer() {
        val printer = printerService.getDefaultPrinter(PrinterRole.RECEIPT) ?: run {
            _actionMessage.value = "No hay impresora de recibos configurada"
            return
        }
        viewModelScope.launch {
            try {
                printerService.openCashDrawer(printer)
                _actionMessage.value = "Caja abierta"
            } catch (e: Exception) {
                _actionMessage.value = "No se pudo abrir la caja: ${e.message}"
            }
        }
    }

    /** "Importe personalizado" (Acciones): a free-amount line on the selected
     *  course — the server accepts it in the same round (customName +
     *  customUnitPriceCents, no productId). */
    fun addCustomAmount(name: String, amountCents: Int) {
        if (amountCents <= 0) return
        _pending.value = _pending.value + PendingLine(
            item = CartItem(
                type = CartItemType.CustomAmount,
                name = name.ifBlank { "Importe personalizado" },
                unitPrice = amountCents,
            ),
            course = _selectedCourse.value,
        )
    }

    fun updatePendingQuantity(lineItemId: String, quantity: Int) {
        if (quantity < 1) return removePending(lineItemId)
        _pending.value = _pending.value.map { line ->
            if (line.item.id == lineItemId) line.copy(item = line.item.copy(quantity = quantity)) else line
        }
    }

    val pendingCount: Int get() = _pending.value.sumOf { it.item.quantity }
    val pendingTotalCents: Int get() = _pending.value.sumOf { it.item.totalPrice }

    // MARK: - Enviar (fire the round)

    /**
     * Sends ALL pending lines in ONE atomic round (each with its own course —
     * the server accepts course per line), then prints one comanda batch per
     * course. Success → session cleared, caller returns to the floor plan
     * (Square drops the waiter back on the plano after firing).
     */
    fun sendRound(onDone: (Boolean, String) -> Unit) {
        val session = tableSession.current() ?: run { onDone(false, "No hay mesa activa"); return }
        val vId = venueId ?: return
        if (_isSending.value) return
        val lines = _pending.value
        if (lines.isEmpty()) {
            onDone(false, "Agrega productos para enviar")
            return
        }

        _isSending.value = true
        viewModelScope.launch {
            val requests = lines.map { line ->
                when (val type = line.item.type) {
                    is CartItemType.ProductItem -> AddOrderItemRequest(
                        productId = type.productId,
                        quantity = line.item.quantity,
                        notes = line.item.itemNote,
                        modifierIds = line.item.selectedModifiers.map { it.modifierId }.ifEmpty { null },
                        course = line.course,
                        isCortesia = line.item.isCortesia.takeIf { it },
                        cortesiaReason = line.item.cortesiaReason?.takeIf { line.item.isCortesia },
                        seat = line.seat,
                    )
                    // Custom-amount line: server keys on customName + cents.
                    else -> AddOrderItemRequest(
                        productId = null,
                        quantity = line.item.quantity,
                        notes = line.item.itemNote,
                        course = line.course,
                        customName = line.item.name,
                        customUnitPriceCents = line.item.effectiveUnitPrice,
                        isCortesia = line.item.isCortesia.takeIf { it },
                        cortesiaReason = line.item.cortesiaReason?.takeIf { line.item.isCortesia },
                        seat = line.seat,
                    )
                }
            }
            // Offline-first Corte B: una sesión PROVISIONAL (mesa abierta sin
            // red) nunca toca el server directo — su orden aún no existe allá.
            if (session.isProvisional) {
                enqueueRoundOffline(vId, session, lines, requests, onDone)
                return@launch
            }

            repository.addRound(vId, session.orderId, requests, session.version).fold(
                onSuccess = { updated ->
                    // Saved — hand control back IMMEDIATELY; printing and the
                    // floor refresh are slow network hops and must not block.
                    // Square: Enviar NO regresa al piso — la sesión sigue viva
                    // y el panel recarga para mostrar la ronda recién enviada.
                    tableSession.updateVersion(updated.version)
                    _pending.value = emptyList()
                    _isSending.value = false
                    onDone(true, "Ronda enviada a cocina — Mesa ${session.tableNumber}")
                    loadCheck()
                    printRoundComandas(vId, session, lines, refreshFloor = true)
                },
                onFailure = { e ->
                    if (isNetworkError(e)) {
                        // Sin red: write-ahead al outbox + impresión LAN local.
                        // La comanda sale YA; el server se entera en el replay.
                        enqueueRoundOffline(vId, session, lines, requests, onDone)
                    } else {
                        _isSending.value = false
                        repository.refresh(vId)
                        val msg = if (e.message?.contains("409") == true) {
                            "La orden cambió en otro dispositivo — vuelve a abrir la mesa"
                        } else {
                            e.message ?: "No se pudo enviar la ronda"
                        }
                        onDone(false, msg)
                    }
                },
            )
        }
    }

    /** Red caída/timeout — nunca un rechazo de negocio del server. */
    private fun isNetworkError(e: Throwable): Boolean = when (e) {
        is java.io.IOException -> true
        is retrofit2.HttpException -> e.code() in 502..504
        else -> false
    }

    /**
     * Acciones ONLINE-ONLY a propósito (quitar descuento/cargo YA aplicado,
     * cortesía de UN item ya enviado, canje de lealtad): sin red se avisa
     * amable ANTES de intentar — nada de errores crudos. Se "rehabilitan"
     * solas: el check es al tocar.
     *
     * Dividir y fusionar NO están en esta lista: sí funcionan sin red (ver el
     * comentario de [splitItems] / [mergeFrom] más abajo y los intents
     * SPLIT_ORDER / MERGE_ORDERS). No les agregues un requireOnline.
     */
    /** Conectividad, para que la UI distinga "espera" de "te falta un paso". */
    val isConnected: StateFlow<Boolean> = connectivityMonitor.isConnected

    /**
     * Un botón gris que no explica por qué está gris es el peor estado posible
     * para un mesero: toca, no pasa nada, y no sabe si está roto. Esto le da
     * la razón en su idioma.
     */
    private val _blockedNotice = MutableStateFlow<String?>(null)

    /**
     * Aviso de "por qué está gris este botón". PERSISTE hasta que el mesero lo
     * cierra o hace otra cosa — a diferencia de un Toast, que se evapora en 3
     * segundos y en plena comida nadie alcanza a leer.
     */
    val blockedNotice: StateFlow<String?> = _blockedNotice.asStateFlow()

    fun showBlockedReason(motivo: String) {
        _blockedNotice.value = motivo
    }

    fun dismissBlockedNotice() {
        _blockedNotice.value = null
    }

    private fun requireOnline(accion: String): Boolean {
        if (connectivityMonitor.isConnected.value) return true
        _actionMessage.value = "Sin conexión — $accion necesita internet; se habilita al volver la señal"
        return false
    }

    // Separar y fusionar YA no exigen que el cheque exista en el server: las
    // líneas enviadas sin red se referencian por su externalId y el cheque
    // provisional por su localOrderId, así que el reducer resuelve ambos. Por
    // eso aquí no hay guard: ver splitItems / mergeFrom.

    /**
     * Offline-first: la ronda se guarda como intent ADD_ITEMS (write-ahead),
     * se imprime la comanda por LAN al instante y las líneas quedan en
     * [queued] ("Por sincronizar") hasta que el ack del replay las confirme.
     */
    private suspend fun enqueueRoundOffline(
        vId: String,
        session: TableSession.Active,
        lines: List<PendingLine>,
        requests: List<AddOrderItemRequest>,
        onDone: (Boolean, String) -> Unit,
    ) {
        val payload = kotlinx.serialization.json.buildJsonObject {
            if (session.isProvisional) {
                put("localOrderId", kotlinx.serialization.json.JsonPrimitive(session.orderId))
            } else {
                put("orderId", kotlinx.serialization.json.JsonPrimitive(session.orderId))
            }
            put(
                "items",
                wireJson.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(AddOrderItemRequest.serializer()),
                    requests,
                ),
            )
        }
        val intentId = syncOutbox.enqueue(vId, com.avoqado.pos.core.data.sync.SyncIntentTypes.ADD_ITEMS, payload)
        // Espejo EXACTO del externalId que el reducer inyecta por índice
        // (sync.mobile.service.ts: `sync:${intent.id}:${idx}`). Guardarlo aquí
        // es lo que deja separar el cheque antes de que sincronice.
        val stamped = lines.mapIndexed { idx, line -> line.copy(externalId = "sync:$intentId:$idx") }
        _queued.value = _queued.value + stamped
        _pending.value = emptyList()
        _isSending.value = false
        onDone(true, "Sin conexión — ronda guardada e impresa; se sincronizará sola")
        printRoundComandas(vId, session, lines, refreshFloor = false)
        // Corte E: la venta offline descuenta el stock local aproximado.
        productsRepository.applyLocalSale(
            lines.mapNotNull { line ->
                (line.item.type as? CartItemType.ProductItem)?.let { it.productId to line.item.quantity }
            },
        )
    }

    /** Comandas por curso a las estaciones LAN (funciona con o sin internet —
     *  la config de ruteo usa su cache si el refresh de red falla). */
    private fun printRoundComandas(
        vId: String,
        session: TableSession.Active,
        lines: List<PendingLine>,
        refreshFloor: Boolean,
    ) {
        viewModelScope.launch {
            runCatching { printConfigRepository.refresh(vId) }
            val config = printConfigRepository.getCurrentConfig()
            // 🔴 SIN guard por estaciones. Antes esto exigía al menos una
            // estación activa, así que un local sin estaciones configuradas —o
            // un POS recién instalado que nunca pudo bajarlas— NO imprimía NADA,
            // aunque tuviera su impresora de cocina conectada y con rol. La
            // cocina no se enteraba del pedido y nadie lo notaba hasta el
            // reclamo. El motor de ruteo YA es fail-open (los items sin estación
            // forman un ticket "SIN ESTACIÓN") y printComandas cae a la
            // impresora KITCHEN local, así que dejarlo pasar es lo correcto:
            // en este dominio el fail-safe jamás puede ser no imprimir.
            run {
                // One comanda batch per course so each ticket reads
                // "Mesa 8 · Aperitivos" like the single-course flow.
                // Comandas route catalog products only — custom
                // amounts ride to the check but never to kitchen.
                val kitchenLines = lines.filter { it.item.type is CartItemType.ProductItem }
                kitchenLines.groupBy { it.course }.forEach { (course, courseLines) ->
                    val routable = courseLines.map { line ->
                        RoutableItem(
                            orderItemId = line.item.id,
                            productId = (line.item.type as? CartItemType.ProductItem)?.productId,
                            categoryId = line.item.categoryId,
                            productName = line.item.name,
                            quantity = line.item.quantity,
                            modifiers = line.item.selectedModifiers.map { it.modifierName },
                            notes = line.item.itemNote,
                        )
                    }
                    val plans = PrintRoutingMapper.buildComandas(routable, config)
                    comandaPrinter.printComandas(
                        plans = plans,
                        config = config,
                        orderNumber = session.orderNumber,
                        orderType = "Mesa ${session.tableNumber}" + (course?.let { " · $it" } ?: ""),
                    )
                }
            }
            if (refreshFloor) repository.refresh(vId)
        }
    }

    // MARK: - Sent-item actions

    /** "Dar de cortesía" on an already-sent line. */
    fun compSentItem(itemId: String, reason: String) {
        if (!requireOnline("la cortesía de un artículo enviado")) return
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.compItem(vId, session.orderId, itemId, reason).fold(
                onSuccess = {
                    _actionMessage.value = "Artículo de cortesía — $reason"
                    loadCheck()
                    repository.refresh(vId)
                },
                onFailure = { e ->
                    _actionMessage.value = e.message ?: "No se pudo dar de cortesía"
                    loadCheck()
                },
            )
        }
    }

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    /** "Mover" — moves the check to another table. The session's table context
     *  goes stale, so on success the caller exits to the floor plan. */
    fun moveTable(targetTableId: String, onDone: (Boolean, String) -> Unit) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.moveOrder(vId, session.orderId, targetTableId).fold(
                onSuccess = {
                    repository.refresh(vId)
                    tableSession.clear()
                    val target = repository.tables.value.firstOrNull { it.id == targetTableId }
                    onDone(true, "Cuenta movida a Mesa ${target?.number ?: ""}".trim())
                },
                onFailure = { e -> onDone(false, e.message ?: "No se pudo mover la cuenta") },
            )
        }
    }

    /** "Asignar" — reassigns the check to another waiter (stays on screen). */
    fun assignWaiter(staffId: String, staffName: String) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.assignOrder(vId, session.orderId, staffId).fold(
                onSuccess = {
                    repository.refresh(vId)
                    _actionMessage.value = "Cuenta asignada a $staffName"
                },
                onFailure = { e -> _actionMessage.value = e.message ?: "No se pudo asignar la cuenta" },
            )
        }
    }

    /** "Cortesía en la cuenta" — comps EVERY line with one reason. */
    fun compWholeCheck(reason: String) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.compWholeOrder(vId, session.orderId, reason).fold(
                onSuccess = {
                    _actionMessage.value = "Cuenta de cortesía — $reason"
                    loadCheck()
                    repository.refresh(vId)
                },
                onFailure = { e ->
                    _actionMessage.value = e.message ?: "No se pudo dar la cortesía"
                    loadCheck()
                },
            )
        }
    }

    /** Detalles del cheque (nombre/notas/comensales/cliente/cumplimiento). null = sin cambio. */
    fun updateDetails(name: String? = null, notes: String? = null, covers: Int? = null, customerId: String? = null, orderType: String? = null) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.updateOrderDetails(
                vId, session.orderId,
                com.avoqado.pos.tables.data.OrderDetailsRequest(name = name, notes = notes, covers = covers, customerId = customerId, orderType = orderType),
            ).fold(
                onSuccess = {
                    _actionMessage.value = "Cuenta actualizada"
                    loadCheck()
                    repository.refresh(vId)
                },
                onFailure = { e -> _actionMessage.value = e.message ?: "No se pudo actualizar la cuenta" },
            )
        }
    }

    /** Aplica un descuento de catálogo (ORDER) al cheque. */
    fun applyDiscount(discountId: String) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.applyOrderDiscount(vId, session.orderId, discountId).fold(
                onSuccess = { _actionMessage.value = "Descuento aplicado"; loadCheck(); repository.refresh(vId) },
                onFailure = { e -> _actionMessage.value = e.message ?: "No se pudo aplicar el descuento" },
            )
        }
    }

    /** Quita un descuento aplicado del cheque. */
    fun removeDiscount(orderDiscountId: String) {
        if (!requireOnline("quitar un descuento aplicado")) return
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.removeOrderDiscount(vId, session.orderId, orderDiscountId).fold(
                onSuccess = { _actionMessage.value = "Descuento quitado"; loadCheck(); repository.refresh(vId) },
                onFailure = { e -> _actionMessage.value = e.message ?: "No se pudo quitar el descuento" },
            )
        }
    }

    /** "Dividir la cuenta": same PAYING hand-off as Pagar, but the register
     *  auto-opens the split sheet on arrival. */
    fun preparePagar(openSplit: Boolean): Boolean {
        val ok = preparePagar()
        if (ok && openSplit) {
            tableSession.current()?.let { tableSession.start(it.copy(openSplitOnArrival = true)) }
        }
        return ok
    }

    // MARK: - Menús por horario

    private val _menus = MutableStateFlow<List<com.avoqado.pos.tables.data.VenueMenu>>(emptyList())
    val menus: StateFlow<List<com.avoqado.pos.tables.data.VenueMenu>> = _menus.asStateFlow()

    /** Menú elegido para la cuadrícula. Null = catálogo completo. */
    private val _selectedMenuId = MutableStateFlow<String?>(null)
    val selectedMenuId: StateFlow<String?> = _selectedMenuId.asStateFlow()

    /** Categorías del menú elegido; null = sin filtro. */
    val menuCategoryIds: StateFlow<Set<String>?> = _selectedMenuId
        .combine(_menus) { id, list ->
            // Menú sin categorías = sin filtro: un set vacío dejaría la
            // cuadrícula en blanco sin salida (auditoría).
            if (id == null) null
            else list.firstOrNull { it.id == id }?.categoryIds?.takeIf { it.isNotEmpty() }?.toSet()
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /** Carga los menús y preselecciona el que aplica AHORA (lo decide el server
     *  con la zona horaria del venue, no la del dispositivo). */
    fun loadMenus() {
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.getMenus(vId).onSuccess { payload ->
                _menus.value = payload.menus
                // Solo preselecciona si el menú activo tiene horario propio: si
                // el venue no usa menús por horario, la cuadrícula no se toca.
                if (_selectedMenuId.value == null) {
                    val activo = payload.menus.firstOrNull { it.id == payload.activeMenuId }
                    if (activo != null && activo.scheduleDisplay != null) {
                        _selectedMenuId.value = activo.id
                    }
                }
            }
        }
    }

    fun selectMenu(menuId: String?) {
        _selectedMenuId.value = menuId
        _actionMessage.value = menuId?.let { id ->
            _menus.value.firstOrNull { it.id == id }?.let { "Menú: ${it.name}" }
        } ?: "Catálogo completo"
    }

    // MARK: - Cobros por servicio

    private val _serviceCharges = MutableStateFlow<List<com.avoqado.pos.tables.data.ServiceChargeOption>>(emptyList())
    val serviceCharges: StateFlow<List<com.avoqado.pos.tables.data.ServiceChargeOption>> = _serviceCharges.asStateFlow()

    fun loadServiceCharges() {
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.getServiceCharges(vId).onSuccess { _serviceCharges.value = it }
        }
    }

    /** Aplica un cobro por servicio: SUMA al total del cheque. */
    fun applyServiceCharge(serviceChargeId: String) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.applyServiceCharge(vId, session.orderId, serviceChargeId).fold(
                onSuccess = {
                    _actionMessage.value = "Cobro por servicio aplicado"
                    loadCheck()
                    repository.refresh(vId)
                },
                onFailure = { e -> _actionMessage.value = e.message ?: "No se pudo aplicar el cobro" },
            )
        }
    }

    fun removeServiceCharge(orderServiceChargeId: String) {
        if (!requireOnline("quitar un cargo aplicado")) return
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.removeServiceCharge(vId, session.orderId, orderServiceChargeId).fold(
                onSuccess = {
                    _actionMessage.value = "Cobro por servicio quitado"
                    loadCheck()
                    repository.refresh(vId)
                },
                onFailure = { e -> _actionMessage.value = e.message ?: "No se pudo quitar el cobro" },
            )
        }
    }

    // MARK: - Recompensas (lealtad)

    private val _loyalty = MutableStateFlow<com.avoqado.pos.tables.data.CustomerLoyalty?>(null)
    val loyalty: StateFlow<com.avoqado.pos.tables.data.CustomerLoyalty?> = _loyalty.asStateFlow()

    /** Carga el saldo del cliente adjunto al cheque (null si no hay cliente). */
    fun loadLoyalty() {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        val customerId = _check.value?.customerId
        if (customerId.isNullOrBlank()) {
            _loyalty.value = null
            return
        }
        viewModelScope.launch {
            repository.getCustomerLoyalty(vId, customerId, session.orderId).fold(
                onSuccess = { _loyalty.value = it },
                onFailure = { _loyalty.value = null },
            )
        }
    }

    /**
     * Canjea puntos como descuento del cheque. El server quema los puntos y
     * aplica el descuento en UNA transacción; quitar ese descuento después
     * devuelve los puntos solo.
     */
    fun redeemPoints(points: Int) {
        if (!requireOnline("canjear puntos")) return
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        val customerId = _check.value?.customerId ?: return
        viewModelScope.launch {
            repository.redeemLoyaltyPoints(vId, session.orderId, customerId, points).fold(
                onSuccess = {
                    _actionMessage.value = "Recompensa aplicada — $points puntos"
                    loadCheck()
                    loadLoyalty()
                    repository.refresh(vId)
                },
                onFailure = { e -> _actionMessage.value = e.message ?: "No se pudo canjear los puntos" },
            )
        }
    }

    /**
     * Multi-cheque: cambia la sesión a OTRA cuenta abierta de la MISMA mesa sin
     * salir de la pantalla. Devuelve la sesión nueva para que la pantalla
     * actualice su snapshot (colectar la sesión causaba double-pop).
     */
    fun switchToCheck(check: com.avoqado.pos.tables.data.OpenCheckSummary): TableSession.Active? {
        val current = tableSession.current() ?: return null
        val next = current.copy(
            orderId = check.id,
            orderNumber = check.orderNumber,
            version = check.version,
            totalCents = round(check.total * 100).toInt(),
            // Nunca arrastrar PAYING al otro cheque: cobrar es por-cuenta.
            mode = TableSession.Mode.ORDERING,
        )
        tableSession.start(next)
        clearPending()
        _actionMessage.value = null
        loadCheck()
        return next
    }

    /**
     * "Fusionar cuentas": vuelca OTRA cuenta abierta en la actual. El server
     * rechaza si el origen trae descuentos/recompensas (su monto se calculó
     * sobre esa cuenta) — el mensaje se muestra tal cual al mesero.
     */
    fun mergeFrom(sourceOrderId: String, onDone: (Boolean, String) -> Unit) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        val offline = !connectivityMonitor.isConnected.value
        viewModelScope.launch {
            repository.mergeOrders(vId, session.orderId, sourceOrderId).fold(
                onSuccess = {
                    loadCheck()
                    repository.refresh(vId)
                    onDone(true, if (offline) "Cuentas fusionadas — se sincroniza al volver la señal" else "Cuentas fusionadas")
                },
                onFailure = { e -> onDone(false, e.message ?: "No se pudo fusionar") },
            )
        }
    }

    /** ¿Cuántos asientos distintos tienen artículos? Habilita "Dividir por puesto". */
    val seatsWithItems: Int
        get() = _check.value?.items?.mapNotNull { it.seat }?.distinct()?.size ?: 0

    /**
     * "Dividir por puesto": el server arma un cheque por asiento en UNA
     * transacción. Al terminar se sale al plano: la cuenta original cambió y
     * las nuevas viven como cheques hermanos de la misma mesa.
     */
    fun splitBySeat(onDone: (Boolean, String) -> Unit) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        // Un id local por asiento con artículos: sin red, cada cheque creado
        // necesita el suyo para que un cobro posterior aterrice en el correcto.
        val seats = (_check.value?.items ?: emptyList()).mapNotNull { it.seat }.distinct().sorted()
        val offline = !connectivityMonitor.isConnected.value
        viewModelScope.launch {
            repository.splitOrderBySeat(vId, session.orderId, seats).fold(
                onSuccess = {
                    repository.refresh(vId)
                    tableSession.clear()
                    onDone(true, if (offline) "Cuenta dividida por puesto — se sincroniza al volver la señal" else "Cuenta dividida por puesto")
                },
                onFailure = { e -> onDone(false, e.message ?: "No se pudo dividir por puesto") },
            )
        }
    }

    /** Multi-cheque: separa artículos ya enviados en una cuenta NUEVA de la
     *  misma mesa (Square's separate checks). La sesión sigue en la original. */
    /**
     * Lo que se puede separar: las líneas del cheque (ids reales del server) MÁS
     * las enviadas sin red, identificadas por su externalId. Sin esto una mesa
     * abierta offline no tenía NADA que separar — el cheque del server todavía
     * no existe.
     */
    val splittableItems: StateFlow<List<OrderDetailItem>> =
        combine(_check, _queued) { check, queued ->
            val fromServer = check?.items ?: emptyList()
            val fromOutbox = queued.mapNotNull { line ->
                val ext = line.externalId ?: return@mapNotNull null
                OrderDetailItem(
                    id = ext,
                    productId = (line.item.type as? CartItemType.ProductItem)?.productId,
                    productName = line.item.name,
                    quantity = line.item.quantity,
                    unitPrice = line.item.unitPrice / 100.0,
                    total = line.item.unitPrice / 100.0 * line.item.quantity,
                    notes = line.item.itemNote,
                    course = line.course,
                    seat = line.seat,
                )
            }
            fromServer + fromOutbox
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun splitItems(itemIds: List<String>) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        if (itemIds.isEmpty()) return
        // Una línea seleccionada es "del outbox" si su id es un externalId que
        // pusimos al encolarla; si no, es un id real del server.
        val offlineIds = _queued.value.mapNotNull { it.externalId }.toSet()
        val refs = itemIds.map { id ->
            if (id in offlineIds) {
                TableServiceRepository.SplitItemRef(serverId = null, externalId = id)
            } else {
                TableServiceRepository.SplitItemRef(serverId = id, externalId = null)
            }
        }
        val offline = !connectivityMonitor.isConnected.value
        viewModelScope.launch {
            repository.splitOrder(vId, session.orderId, refs).fold(
                onSuccess = {
                    _actionMessage.value = if (offline) {
                        "Cuenta separada — ${itemIds.size} artículo(s); se sincroniza al volver la señal"
                    } else {
                        "Cuenta separada — ${itemIds.size} artículo(s) movidos"
                    }
                    loadCheck()
                    repository.refresh(vId)
                },
                onFailure = { e -> _actionMessage.value = e.message ?: "No se pudo separar la cuenta" },
            )
        }
    }

    /** "Anular cuenta" — cancels the check; success means the table is free. */
    fun anularCuenta(reason: String, onDone: (Boolean, String) -> Unit) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.cancelOrder(vId, session.orderId, reason).fold(
                onSuccess = {
                    repository.clearTable(vId, session.tableId)
                    repository.refresh(vId)
                    tableSession.clear()
                    onDone(true, "Cuenta anulada — Mesa ${session.tableNumber} liberada")
                },
                onFailure = { e ->
                    repository.refresh(vId)
                    onDone(false, e.message ?: "No se pudo anular la cuenta")
                },
            )
        }
    }

    /** "Volver a imprimir pedido" (Acciones): re-fires the comandas for the
     *  ALREADY-SENT items, grouped by course — for when the kitchen lost a
     *  ticket. Uses the check's real lines (productId + modifiers). */
    fun reprintComandas() {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        val sentItems = _check.value?.items?.filter { it.productId != null } ?: emptyList()
        if (sentItems.isEmpty()) {
            _actionMessage.value = "No hay artículos enviados para reimprimir"
            return
        }
        viewModelScope.launch {
            printConfigRepository.refresh(vId)
            val config = printConfigRepository.getCurrentConfig()
            // Sin estaciones NO se aborta: el motor de ruteo es fail-open (los
            // items caen en un ticket "SIN ESTACIÓN") y ComandaPrinter usa la
            // impresora KITCHEN local. Abortar aquí dejaba a un local sin
            // estaciones configuradas sin poder reimprimir NADA.
            if (!config.stations.any { it.active } && printerService.getDefaultPrinter(PrinterRole.KITCHEN) == null) {
                _actionMessage.value = "No hay ninguna impresora de cocina configurada"
                return@launch
            }
            sentItems.groupBy { it.course }.forEach { (course, courseItems) ->
                val routable = courseItems.map { item ->
                    RoutableItem(
                        orderItemId = item.id,
                        productId = item.productId,
                        categoryId = null,
                        productName = item.productName ?: "Artículo",
                        quantity = item.quantity,
                        modifiers = item.modifiers.map { it.name },
                        notes = item.notes,
                    )
                }
                val plans = PrintRoutingMapper.buildComandas(routable, config)
                comandaPrinter.printComandas(
                    plans = plans,
                    config = config,
                    orderNumber = session.orderNumber,
                    orderType = "Mesa ${session.tableNumber}" + (course?.let { " · $it" } ?: "") + " · REIMPRESIÓN",
                )
            }
            _actionMessage.value = "Comandas reimpresas"
        }
    }

    /** "¡Listo!" del menú por tiempo (Square): manda a cocina la orden de
     *  MARCHAR ese curso — imprime la comanda del curso con sello ¡MARCHAR!. */
    fun marcharCourse(course: String?) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        val courseItems = _check.value?.items?.filter { it.course == course && it.productId != null } ?: emptyList()
        if (courseItems.isEmpty()) {
            _actionMessage.value = "No hay artículos en ese tiempo"
            return
        }
        viewModelScope.launch {
            printConfigRepository.refresh(vId)
            val config = printConfigRepository.getCurrentConfig()
            // Sin estaciones NO se aborta: el motor de ruteo es fail-open (los
            // items caen en un ticket "SIN ESTACIÓN") y ComandaPrinter usa la
            // impresora KITCHEN local. Abortar aquí dejaba a un local sin
            // estaciones configuradas sin poder reimprimir NADA.
            if (!config.stations.any { it.active } && printerService.getDefaultPrinter(PrinterRole.KITCHEN) == null) {
                _actionMessage.value = "No hay ninguna impresora de cocina configurada"
                return@launch
            }
            val routable = courseItems.map { item ->
                RoutableItem(
                    orderItemId = item.id,
                    productId = item.productId,
                    categoryId = null,
                    productName = item.productName ?: "Artículo",
                    quantity = item.quantity,
                    modifiers = item.modifiers.map { it.name },
                    notes = item.notes,
                )
            }
            val plans = PrintRoutingMapper.buildComandas(routable, config)
            comandaPrinter.printComandas(
                plans = plans,
                config = config,
                orderNumber = session.orderNumber,
                orderType = "Mesa ${session.tableNumber} · ¡MARCHAR ${course ?: "Inmediato"}!",
            )
            _actionMessage.value = "¡Marchando ${course ?: "Inmediato"}!"
        }
    }

    /** "Repetir" del menú por tiempo (Square): duplica los artículos ya
     *  enviados de ese curso como líneas PENDIENTES (nueva ronda). */
    fun repeatCourse(course: String?) {
        val courseItems = _check.value?.items?.filter { it.course == course && it.productId != null } ?: emptyList()
        if (courseItems.isEmpty()) {
            _actionMessage.value = "No hay artículos en ese tiempo"
            return
        }
        val copies = courseItems.map { item ->
            PendingLine(
                item = CartItem(
                    type = CartItemType.ProductItem(item.productId!!),
                    name = item.productName ?: "Artículo",
                    unitPrice = kotlin.math.round(item.unitPrice * 100).toInt(),
                    quantity = item.quantity,
                    selectedModifiers = item.modifiers.map { m ->
                        com.avoqado.pos.pos.data.model.SelectedModifier(
                            groupId = "",
                            groupName = "",
                            modifierId = m.id,
                            modifierName = m.name,
                            priceInCents = kotlin.math.round(m.price * 100).toInt(),
                        )
                    },
                    itemNote = item.notes,
                ),
                course = course,
            )
        }
        _pending.value = _pending.value + copies
        _selectedCourse.value = course
        _actionMessage.value = "${copies.sumOf { it.item.quantity }} artículo(s) repetidos en ${course ?: "Inmediato"}"
    }

    /** Pre-cuenta on the RECEIPT printer, from the freshly loaded check. */
    fun printPreBill() {
        val session = tableSession.current() ?: return
        // Nada de `?: return` mudo: si no se puede, se DICE por qué. Antes, sin
        // red el cheque venía null y el botón simplemente no hacía nada.
        printPreBillBlockedReason?.let {
            showBlockedReason(it)
            return
        }
        val order = _check.value ?: return
        val printer = printerService.getDefaultPrinter(PrinterRole.RECEIPT) ?: return
        viewModelScope.launch {
            try {
                fun cents(v: Double) = round(v * 100).toInt()
                val receipt = ReceiptData(
                    orderNumber = order.orderNumber,
                    orderType = "Mesa ${session.tableNumber} — PRE-CUENTA",
                    items = order.items.map {
                        ReceiptItem(
                            name = it.productName ?: "Artículo",
                            quantity = it.quantity,
                            unitPrice = cents(it.unitPrice),
                            totalPrice = cents(it.total),
                        )
                    },
                    subtotal = cents(order.total),
                    taxAmount = 0,
                    total = cents(order.total),
                    venueName = secureStorage.venueName ?: "Avoqado",
                    cashierName = null,
                )
                printerService.printReceipt(receipt, printer)
                _actionMessage.value = "Pre-cuenta impresa"
            } catch (e: Exception) {
                _actionMessage.value = "No se pudo imprimir: ${e.message ?: "desconocido"}"
            }
        }
    }

    // MARK: - Pagar / salir

    /**
     * Flips the session to PAYING with the FRESH check total (the floor
     * snapshot could be stale if another device added items), so the register's
     * PAYING seam seeds and charges exactly what's owed. Returns false when
     * there's nothing to charge.
     */
    /**
     * Total COBRABLE, misma fuente que muestra la UI: lo del cheque del server
     * MÁS lo enviado sin red (que el server todavía no conoce).
     *
     * 🔴 Existe porque el botón "Pagar" se habilitaba con este total pero
     * preparePagar() leía `session.totalCents`, que en una mesa PROVISIONAL
     * nace en 0 y nunca se actualiza. Resultado: el botón decía "Pagar $119.00",
     * el mesero tocaba y NO PASABA NADA — sin red no se podía cobrar una mesa.
     * Encontrado en la Sunmi T3 Pro el 2026-07-28.
     */
    val payableTotalCents: Int
        get() {
            val session = tableSession.current()
            val fromCheck = _check.value?.takeIf { it.id == session?.orderId }?.let { check ->
                val paid = check.payments.filter { it.status == "COMPLETED" }.sumOf { it.amount + it.tipAmount }
                round((check.total - paid) * 100).toInt()
            } ?: 0
            val fromQueued = _queued.value.sumOf { it.item.effectiveUnitPrice * it.item.quantity }
            return fromCheck + fromQueued
        }

    fun preparePagar(): Boolean {
        val session = tableSession.current() ?: return false
        // 🔴 MONEY: si ya hubo pagos parciales (split), Pagar debe cobrar SOLO lo
        // restante — usar el total completo re-cobraría lo ya pagado.
        // Solo confiar en el cheque si ES el de esta sesión (cambiar de cuenta
        // dispara loadCheck async — cobrar el total del cheque ANTERIOR contra
        // el orderId nuevo sería dinero equivocado; auditoría 2026-07-18).
        val totalFromCheck = _check.value?.takeIf { it.id == session.orderId }?.let { check ->
            // amount + tipAmount: el server mete las propinas de pagos previos
            // DENTRO de order.total, así que se restan completas.
            val paid = check.payments.filter { it.status == "COMPLETED" }.sumOf { it.amount + it.tipAmount }
            round((check.total - paid) * 100).toInt()
        } ?: 0
        // Sin red el cheque del server no existe: se cobra lo enviado por el
        // outbox. `session.totalCents` NO sirve aquí — nace en 0 en las mesas
        // provisionales y dejaba el cobro muerto.
        val queuedCents = _queued.value.sumOf { it.item.effectiveUnitPrice * it.item.quantity }
        val totalCents = payableCents(totalFromCheck, queuedCents, session.totalCents)
        if (totalCents <= 0) return false
        tableSession.start(session.copy(mode = TableSession.Mode.PAYING, totalCents = totalCents))
        return true
    }

    /** Leaves the screen back to the floor plan. Pending lines die with it —
     *  the caller confirms with the user first when any exist. */
    fun exitToFloor() {
        tableSession.clear()
        _pending.value = emptyList()
    }
}
