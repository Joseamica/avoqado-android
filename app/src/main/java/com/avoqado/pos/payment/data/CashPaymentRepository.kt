package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.RevisionCanceladoCero
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CashPaymentRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val pendingPaymentDao: PendingPaymentDao,
    /**
     * 🔴 D2, rediseño r9: el ÚNICO escritor de la venta en el cajón de un cobro con apartado ([SalidaDelApartado]). Cada salida de
     * `EN_VUELO` de este repositorio pasa por él. null sólo en pruebas que no miran el cajón; Hilt y Guice lo inyectan siempre.
     */
    private val salida: SalidaDelApartado? = null,
) {
    fun processCashPayment(
        totalCents: Int,
        cashReceivedCents: Int,
    ): CashPaymentResult {
        val changeCents = cashReceivedCents - totalCents
        Log.d("💵", "Cash payment: total=$totalCents, received=$cashReceivedCents, change=$changeCents")

        return if (changeCents >= 0) {
            CashPaymentResult.Success(changeCents = changeCents)
        } else {
            CashPaymentResult.InsufficientFunds(shortfall = -changeCents)
        }
    }

    /**
     * Queue a cash payment for offline sync.
     * Called when the order creation API fails with a network/server error.
     *
     * 🔴 LÍMITE DECLARADO (P3-5, revisión independiente del 5-sep-2026): **el cobro en efectivo
     * hecho CON red no pasa por ninguna cola, y por tanto tampoco por la barrera del cajón.**
     * Esta función sólo se usa cuando el intento en línea falla; un cobro nuevo con la red ya de
     * vuelta y el `OPEN` todavía pendiente aterriza directo y nace igual de huérfano.
     *
     * No se arregla a propósito, y la razón es la misma regla de siempre: retener un cobro que el
     * cajero está haciendo AHORA, con red, sería impedir una venta — y el hub LAN ya dejó escrito
     * que nada de esto puede bloquear un cobro. Además, con red el `OPEN` o ya salió, o está en
     * REINTENTAR porque el SERVIDOR lo rechazó: en el segundo caso esperar no arregla nada.
     * Lo que sí queda: la banda de arriba lo DICE (ver `estadoDeLosCobros`), y el dueño ve esos
     * cobros como «fuera de turno» y puede reatribuirlos.
     */
    suspend fun queueCashPayment(
        orderRequest: CreateOrderRequest,
        staffId: String,
        cashTenderedCents: Int?,
        changeCents: Int?,
        rating: Int?,
        orderId: String? = null,
        /** Identidad estable de la creación de orden original. */
        orderExternalId: String? = null,
        /**
         * Cliente elegido en el carrito. Se PERSISTE con el pago: sin esto, un
         * cobro hecho sin red se reproducía al reconectar como venta anónima
         * aunque el cajero sí hubiera elegido al cliente — y nadie se entera,
         * porque el ticket ya salió bien.
         */
        customerId: String? = null,
        /**
         * 🔴 LA MISMA llave que usó el intento en línea, no una nueva.
         *
         * El id de esta fila ES la llave que `PaymentSyncService` manda al
         * reintentar. Inventar una aquí rompe la deduplicación del server: si
         * el cobro SÍ se aplicó pero la respuesta se perdió (servidor
         * reiniciado, WiFi caído a media respuesta), el reintento llega con una
         * llave que el server no conoce, se salta el atajo idempotente y choca
         * contra "Order is already paid" — 400 permanente. El cobro queda en
         * cuarentena para siempre aunque el dinero YA esté cobrado, y el
         * gerente no tiene forma de saber si entró.
         *
         * Medido el 2026-08-09 con el log del backend: el pago quedó guardado
         * con la llave `65fb7769…`, hubo 6 reintentos y CERO deduplicaciones.
         */
        idempotencyKey: String? = null,
        /**
         * Cobro registrado a mano (terminal ajena, transferencia). null =
         * efectivo. Se PERSISTE: sin esto, un cobro con tarjeta hecho sin red
         * se reproducía como efectivo al reconectar y el corte pedía dinero
         * que nunca entró al cajón.
         */
        manualMethod: com.avoqado.pos.payment.domain.ManualPaymentMethod? = null,
        /**
         * Tipo de pago del catálogo. EXCLUYENTE con `manualMethod`: el server rechaza
         * `method` + `tenderTypeId` juntos a propósito (ambigüedad de dinero) y resuelve
         * él la comisión/cajón/forma SAT desde su historial.
         */
        tenderType: com.avoqado.pos.payment.domain.TenderTypeOption? = null,
        /** Dinero de esta captura; orderRequest conserva la orden completa para recrearla. */
        amountCents: Int = orderRequest.total - orderRequest.tip,
        tipCents: Int = orderRequest.tip,
        splitType: String = "FULLPAYMENT",
        venueId: String? = null,
        /**
         * Cuándo se CAPTURÓ la venta (epoch ms del aparato, una sola vez). Nulo ⇒ la que trae el pedido capturado
         * ([CreateOrderRequest.createdAtLocal]), la de la reserva en disco con la misma llave o, si no hay, ahora.
         */
        capturadaEn: Long? = null,
        /**
         * 🔴 La caja abierta cuando el cajero CONFIRMÓ el cobro, leída UNA vez (`PaymentFlowViewModel`), con el id que el
         * aparato tenía entonces. Si este cobro ya tenía reserva, manda la de la reserva ([filaAlReemplazar]).
         */
        cajaDelCobro: String? = null,
    ): String {
        require(venueId == null || venueId.isNotBlank()) { "No venue" }
        val venue = venueId ?: secureStorage.venueId.orEmpty()
        val original = idempotencyKey?.takeIf { it.isNotBlank() }?.let { pendingPaymentDao.findForVenue(venue, it) }
        val hora = original?.createdAt ?: capturadaEn ?: orderRequest.createdAtLocal ?: System.currentTimeMillis()
        val entity = construirFila(
            orderRequest, staffId, cashTenderedCents, changeCents, rating, orderId ?: original?.orderId,
            orderExternalId ?: original?.externalOriginal(),
            customerId, idempotencyKey, manualMethod, tenderType, PaymentSyncStatus.PENDING, amountCents, tipCents, splitType, venue, hora,
            cajaDelCobro,
        )
        // REPLACE: si este cobro se había reservado (`reservarCobro`, misma llave) la reserva se
        // vuelve la fila de la cola, con los datos finales del intento. 🔴 Rediseño r9: si esa reserva seguía `EN_VUELO`, su
        // venta entra al cajón en la MISMA transacción, con el importe de la fila (sin 2xx: [SalidaDelApartado]).
        salir(entity.id, conDosXx = false, delFlujo = true) { pendingPaymentDao.insert(entity); true }
        Log.d("💵", "💾 Cash payment queued offline: ${entity.id} (${entity.paymentType})")
        return entity.id
    }

    // MARK: - Cobro en vuelo (se guarda ANTES de tocar la red)

    /**
     * Los cobros de un flujo de cobro VIVO de este proceso (la venta en pantalla es su dueña). Una fila `EN_VUELO` que no está
     * aquí la dejó un flujo que ya no existe (la app murió, la pantalla se cerró, empezó otra venta): nadie la resolvió, y desde
     * D2 v7 se da por COBRADA ([darPorCobradasLasAbandonadas]).
     */
    private val reservasDeEsteProceso: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Read-only presentation scope; never releases a live reservation. */
    fun salesNoticeReservedIds(): Set<String> = reservasDeEsteProceso.toSet()

    /**
     * D15, Codex r19: las llaves que este proceso ya apartó, para siempre. «Cancelar» borra la fila, pero la venta conserva su llave (para
     * que el servidor deduplique): si vuelve, ya pudo salir y su gemela ya puede estar en la caja, así que no toma otra foto (la regla
     * de antes). Las llaves de los flujos no sobreviven a la muerte del proceso. ponytail: crece una llave por cobro mientras viva el
     * proceso (decenas de KB al día); acotarlo si algún día pesa.
     */
    private val llavesYaApartadas: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 🔴 Guarda el cobro en efectivo ANTES del POST (regla «se persiste antes de tocar la red»).
     *
     * Antes la fila nacía en el `onFailure`, hasta 15 s después del toque: si la app moría en
     * medio, el cobro no quedaba en ningún lado, y si el POST sí había llegado, el cajero lo
     * rehacía con otra llave (dos ventas por un billete). La reserva lleva la MISMA llave que el
     * intento en línea y que la cola.
     *
     * Queda `EN_VUELO` y es del flujo que la guardó mientras viva: la cola no la reproduce y el cajón no la cuenta. Sólo la
     * borra una acción de la cajera sobre esa venta (D2 v7: «Cancelar», o volver a cobrarla con otra llave —[enLugarDe]—); lo
     * que nadie resolvió se da por cobrado al soltarse ([darPorCobradasLasAbandonadas]), al estilo Toast.
     */
    suspend fun reservarCobro(
        orderRequest: CreateOrderRequest,
        staffId: String,
        cashTenderedCents: Int?,
        changeCents: Int?,
        rating: Int?,
        orderId: String? = null,
        orderExternalId: String? = null,
        customerId: String? = null,
        idempotencyKey: String,
        manualMethod: com.avoqado.pos.payment.domain.ManualPaymentMethod? = null,
        tenderType: com.avoqado.pos.payment.domain.TenderTypeOption? = null,
        /** Dinero de esta captura; orderRequest conserva la orden completa para recrearla. */
        amountCents: Int = orderRequest.total - orderRequest.tip,
        tipCents: Int = orderRequest.tip,
        splitType: String = "FULLPAYMENT",
        venueId: String? = null,
        /** Cuándo se CAPTURÓ la venta. Nulo ⇒ la que trae el pedido capturado o, si no trae, ahora. */
        capturadaEn: Long? = null,
        /**
         * 🔴 La caja de la venta, ANTES de la red: la que estaba abierta al confirmar el cobro. Es la primera captura y la que
         * manda (efectivo sin red en el turno correcto, 21-oct-2026). Nula = no había caja abierta.
         */
        cajaDelCobro: String? = null,
        /**
         * 🔴 D2 v7: las llaves de los intentos ANTERIORES de esta misma venta (p. ej. la del 409 de precio). La cajera la volvió a
         * cobrar con otra llave: sus apartados se borran en la MISMA escritura que guarda éste. Vacío = primer intento o
         * reintento con la misma llave (reemplaza su propia fila y no borra nada).
         */
        enLugarDe: Collection<String> = emptyList(),
    ) {
        require(venueId == null || venueId.isNotBlank()) { "No venue" }
        val venue = venueId ?: secureStorage.venueId.orEmpty()
        val original = pendingPaymentDao.findForVenue(venue, idempotencyKey)
        val hora = capturadaEn ?: orderRequest.createdAtLocal ?: System.currentTimeMillis()
        // Codex r20: la llave se marca sólo cuando su reserva QUEDÓ guardada (abajo). Si el guardado falla no sale el POST: el
        // reintento sigue siendo la primera vez y toma su foto.
        val primeraVez = idempotencyKey !in llavesYaApartadas
        val entity = construirFila(
            orderRequest, staffId, cashTenderedCents, changeCents, rating, orderId ?: original?.orderId,
            orderExternalId ?: original?.externalOriginal(),
            customerId, idempotencyKey, manualMethod, tenderType, PaymentSyncStatus.EN_VUELO, amountCents, tipCents, splitType, venue,
            hora, cajaDelCobro,
            // D15: lo que la caja ya trae ANTES del POST no puede ser la gemela de este cobro. Un reintento conserva la foto del primero;
            // una llave que ya se apartó (Codex r19: «Cancelar» y volver a cobrar) no toma otra.
            ventasYaEnLaCaja = if (primeraVez) salida?.ventasDelServidorEnLaCaja(cajaDelCobro, venue, hora) else null,
        )
        val anteriores = enLugarDe.filter { it != entity.id }
        // Primero la marca en memoria y DESPUÉS la fila: quien observe la tabla nunca ve este
        // cobro como «sin confirmar» mientras va en vuelo.
        // 🔴 Un REINTENTO con la misma llave ya era del flujo: si su guardado falla, la fila anterior sigue siendo suya (Codex r7).
        val esNueva = reservasDeEsteProceso.add(entity.id)
        try {
            if (anteriores.isEmpty()) pendingPaymentDao.insert(entity) else pendingPaymentDao.reservarEnLugarDe(entity, anteriores)
        } catch (e: Exception) {
            if (esNueva) reservasDeEsteProceso -= entity.id
            throw e
        }
        llavesYaApartadas += idempotencyKey
        reservasDeEsteProceso -= anteriores.toSet()
    }

    /**
     * El intento terminó con el cobro registrado o encolado: la fila ya no está `EN_VUELO` (o se da por cobrada) y el flujo la
     * suelta. No borra nada: la SQL conserva todo apartado ([PendingPaymentDao.borrarSiEnVuelo]).
     */
    suspend fun soltarReserva(id: String) {
        pendingPaymentDao.borrarSiEnVuelo(id)
        reservasDeEsteProceso -= id
    }

    /**
     * El flujo que llevaba este cobro ya no existe (la pantalla se cerró, se recreó o empezó otra venta) sin que la cajera
     * decidiera: el apartado queda sin dueño y se da por cobrado en la siguiente vuelta de la cola ([darPorCobradasLasAbandonadas]).
     */
    fun olvidarReservaEnMemoria(id: String) {
        reservasDeEsteProceso -= id
    }

    /**
     * 🔴 D2 v7: la cajera tocó «Cancelar» en ESA venta, o la terminó por otro camino: su apartado se borra (sólo un `EN_VUELO`
     * con captura) y deja de ser del flujo. Si el disco falla, lanza y la fila sigue siendo del flujo.
     */
    suspend fun descartarReservaRechazada(id: String) {
        pendingPaymentDao.descartarReservaRechazada(id)
        reservasDeEsteProceso -= id
        salida?.olvidarCajero(id)
    }

    /**
     * Agregado 2 de la ronda corta: quién cobra este apartado (la sesión al confirmar el cobro). La venta que el flujo saca de
     * `EN_VUELO` (2xx, encolar, respaldo del 2xx) se anota a su nombre, como antes la anotaba `recordCashSale`.
     */
    fun recordarCajero(llave: String, staffId: String, nombre: String) {
        salida?.recordarCajero(llave, staffId, nombre)
    }

    /**
     * 🔴 D2 v7, punto 7: el servidor contestó 2xx y lo local falló después. Es COBRADO: la fila pasa a la cola con su MISMA llave
     * (el servidor deduplica). Si ni eso se puede escribir, se queda `EN_VUELO`, sin dueño, y se da por cobrada en la siguiente
     * vuelta. Nunca lanza.
     */
    suspend fun darPorCobrado(
        id: String,
        /** Codex r14 A5: lo que el servidor CONFIRMÓ ([OrderRepository.CobroRegistradoSinGuardar.importeCobradoCents]); null = el de la fila. */
        importeCents: Int? = null,
    ): Boolean = try {
        // El servidor sí lo confirmó: su venta se anota siempre, aunque la caja ya traiga la del servidor (r10 #2).
        pasarALaCola(id, conDosXx = true, delFlujo = true, importeCents)
    } finally {
        reservasDeEsteProceso -= id
    }

    /**
     * 🔴 D2 v7, punto 3 — «se da por cobrado», como Toast. Al arrancar y en cada vuelta de la cola: todo apartado `EN_VUELO` del
     * local actual que ya no es de un flujo vivo, con su captura completa, sin marca Q15 y de efectivo PURO pasa a `PENDING` con
     * su MISMA llave (lo mismo que «sí se cobró»); si el servidor ya lo tenía, deduplica. Sólo si su captura trae
     * `daPorCobradaAlAbandonar == true` (la apartó esta app): las «sin confirmar» que dejó la app de antes al soltar un rechazo, «Ya
     * pagó de otra forma» (método manual o tipo del catálogo) y las filas heredadas sin captura se quedan como hoy. Devuelve cuántas pasó.
     */
    suspend fun darPorCobradasLasAbandonadas(): Int {
        val venueId = secureStorage.venueId?.takeIf { it.isNotBlank() } ?: return 0
        return pendingPaymentDao.enVueloDelVenue(venueId).count { fila ->
            fila.id !in reservasDeEsteProceso && fila.q15Reason == null &&
                fila.method == "CASH" && fila.tenderTypeId == null &&
                // 🔴 Sólo lo que apartó la app nueva: las «sin confirmar» que dejó la de antes se quedan como hoy.
                ConciliacionDelCobro.leerCaptura(fila.q15CaptureJson, venueId, fila.id)?.daPorCobradaAlAbandonar == true &&
                // Rediseño r9: pasar a la cola y anotar su venta en el cajón son UNA escritura ([SalidaDelApartado]).
                pasarALaCola(fila.id, conDosXx = false, delFlujo = false)
        }
    }

    /**
     * Una fila que no se pudo pasar a la cola NO se calla: queda en el log y se queda `EN_VUELO` para la siguiente vuelta (también
     * si lo que falló fue anotar su venta en el cajón: es la misma transacción).
     */
    private suspend fun pasarALaCola(id: String, conDosXx: Boolean, delFlujo: Boolean, importeCents: Int? = null): Boolean = try {
        salir(id, conDosXx, delFlujo, importe = { fila -> importeCents ?: (fila.amountCents + fila.tipCents) }) { pendingPaymentDao.enVueloAPendiente(id) == 1 }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("💵", "No se pudo dar por cobrado el apartado $id; sigue EN_VUELO para la siguiente vuelta: ${e.message}")
        false
    }

    /** Cobros de un proceso que murió a media petición, del local actual. */
    suspend fun cobrosSinConfirmar(): List<PendingPaymentEntity> {
        val venueId = secureStorage.venueId ?: return emptyList()
        return pendingPaymentDao.enVueloDelVenue(venueId).filter { it.id !in reservasDeEsteProceso && RevisionCanceladoCero.mostrarFallido(it) }
    }

    /** Lo mismo, en vivo, para el aviso y la cuarentena. */
    fun observarSinConfirmar(): Flow<List<PendingPaymentEntity>> =
        pendingPaymentDao.observarEnVuelo().map { filas ->
            val venueId = secureStorage.venueId
            filas.filter { it.venueId == venueId && it.id !in reservasDeEsteProceso && RevisionCanceladoCero.mostrarFallido(it) }
        }

    /** Manager confirmation cannot reconstruct a lost amount or release a price hold. */
    suspend fun confirmarQueSeCobro(id:String):Boolean {
        val venue=secureStorage.venueId ?: return false
        val row=pendingPaymentDao.findForVenue(venue,id) ?: return false
        if(row.syncStatus!="EN_VUELO" || row.q15Reason!=null) return false
        if(ConciliacionDelCobro.leerCaptura(row.q15CaptureJson,venue,id)==null) {
            pendingPaymentDao.retenerPagoQ15(venue,id,ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA,System.currentTimeMillis());return false
        }
        // Rediseño r9 (prueba 10): «Sí se cobró» también anota su venta en el cajón, en la misma escritura (antes no la anotaba).
        return salir(id, conDosXx = false, delFlujo = false) { pendingPaymentDao.enVueloAPendiente(id) == 1 }
    }

    /**
     * 🔴 Codex r10 #1 + agregados 1 y 3 de la ronda corta: la orden de la venta ya nació (y quizá con otro total: precio corregido o
     * total adoptado). El apartado lleva el importe que de verdad se cobra Y su orden ANTES de mandar el `/pay`, en UNA escritura: si la
     * app muere o la venta se suelta, el barrido lo da por cobrado (y la cola lo sube) con ESE importe, y su venta en el cajón lleva su
     * orden (la fusión la reclama por orden y la gemela compara la orden). La captura Q15 original no se toca. Lanza si el disco falla o
     * si la fila ya no está en vuelo (0 filas): el POST no sale.
     */
    suspend fun actualizarApartado(id: String, venueId: String, amountCents: Int, orderId: String) {
        val escritas = com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(venueId) {
            pendingPaymentDao.actualizarApartadoEnVuelo(id, amountCents, orderId)
        }
        check(escritas == 1) { "El apartado $id ya no está en vuelo: no se actualizó su importe ni su orden" }
    }

    private suspend fun salir(
        id: String,
        conDosXx: Boolean,
        delFlujo: Boolean,
        importe: (PendingPaymentEntity) -> Int = { it.amountCents + it.tipCents },
        transicion: suspend () -> Boolean,
    ): Boolean = salida?.salir(id, conDosXx, delFlujo, importe, transicion) ?: transicion()

    /** Uncertain money stays stored. A human assertion alone is not proof of a non-payment. */
    suspend fun descartarPorqueNoSeCobro(id:String):Boolean {
        val venue=secureStorage.venueId ?: return false
        val row=pendingPaymentDao.findForVenue(venue,id) ?: return false
        if(row.syncStatus!="EN_VUELO") return false
        val reason=if(ConciliacionDelCobro.leerCaptura(row.q15CaptureJson,venue,id)==null)
            ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA else ConciliacionDelCobro.RESULTADO_NO_COMPROBADO
        pendingPaymentDao.retenerPagoQ15(venue,id,reason,System.currentTimeMillis())
        return false
    }

    /**
     * 🔴 DINERO. ¿Este aparato tiene un cobro de la orden [orderId] guardado que todavía puede llegar al servidor, y quién lo
     * sube? Lo pregunta la bandeja «Pedidos en línea» antes de abrir «Cobrar» (un cobro por pedido a la vez, Codex plan r1 #2
     * y r2 #1): sin red la bandeja conserva el saldo viejo, y un segundo cobro nacería con OTRA llave — el servidor aceptaría
     * los dos porque los dos son dinero que el cajero declaró recibido. Los dos estados distintos de [EfectivoDelPedido.NINGUNO]
     * bloquean; sólo cambia lo que se le dice al cajero (Ruling 21 #2: no decir «se sube solo» de algo que nada sube).
     *
     * - [EfectivoDelPedido.EN_COLA]: `PENDING` sin motivo de revisión (la cola lo sube sola al volver la red) o `SYNCING`
     *   (subiéndose ahora; si la red cae, regresa a `PENDING`).
     * - [EfectivoDelPedido.POR_REVISAR]: espera a un gerente en «Operaciones por revisar»:
     *   - `EN_VUELO`: guardado antes del POST y sin desenlace (el proceso murió a media petición, o un 4xx dejó la reserva).
     *     Pudo llegar; nada lo reproduce hasta «Sí, se cobró». Con motivo también: «no se cobró» del gerente no prueba que no
     *     se cobró (`descartarPorqueNoSeCobro`).
     *   - `PENDING` con motivo de revisión: `PaymentSyncService` lo detiene (también el retenido por precio, que para una orden
     *     que el servidor ya tiene no se suelta solo).
     *   - `FAILED`: el servidor lo rechazó O se agotaron los reintentos de red (`handleNetworkError`); el dinero se recibió y
     *     el gerente lo reintenta ahí (PR #53).
     *   Si hay de los dos tipos, gana éste: es el que necesita a una persona.
     * - [EfectivoDelPedido.NINGUNO]: sólo `SYNCED` (ya está en el saldo del servidor).
     *
     * El SQL sólo acota por local; la decisión vive aquí, en Kotlin, donde las pruebas la fijan (como `PendingCashSales`).
     */
    suspend fun efectivoDelPedido(orderId: String): EfectivoDelPedido {
        val venueId = secureStorage.venueId ?: return EfectivoDelPedido.NINGUNO
        if (orderId.isBlank()) return EfectivoDelPedido.NINGUNO
        val estados = pendingPaymentDao.forVenue(venueId).filter { it.orderId == orderId }.map { fila ->
            when (fila.syncStatus) {
                PaymentSyncStatus.SYNCING.name -> EfectivoDelPedido.EN_COLA
                PaymentSyncStatus.PENDING.name ->
                    if (fila.q15Reason == null) EfectivoDelPedido.EN_COLA else EfectivoDelPedido.POR_REVISAR
                PaymentSyncStatus.EN_VUELO.name, PaymentSyncStatus.FAILED.name -> EfectivoDelPedido.POR_REVISAR
                else -> EfectivoDelPedido.NINGUNO
            }
        }
        return when {
            EfectivoDelPedido.POR_REVISAR in estados -> EfectivoDelPedido.POR_REVISAR
            EfectivoDelPedido.EN_COLA in estados -> EfectivoDelPedido.EN_COLA
            else -> EfectivoDelPedido.NINGUNO
        }
    }

    /**
     * 🔴 DINERO. ¿Este aparato ya SUBIÓ un cobro de la orden [orderId] (`SYNCED`)? Sólo importa SIN red (Ruling 21 #3): el
     * efectivo encolado se sube en un parpadeo del WiFi y la red se cae antes de que la bandeja se relea; la bandeja vieja
     * sigue diciendo el saldo completo y no sabe de ese pago. Con red el saldo del servidor ya lo cuenta.
     *
     * La fila no guarda CUÁNDO subió (sería una columna nueva de Room), así que no se puede distinguir «subió después de la
     * última lectura buena de la bandeja» de «subió antes»: se responde por cualquier fila subida, que es lo seguro. El único
     * falso positivo es un segundo abono SIN red a un pedido que ya recibió efectivo de este aparato: espera a la red.
     */
    suspend fun huboEfectivoSubido(orderId: String): Boolean {
        val venueId = secureStorage.venueId ?: return false
        if (orderId.isBlank()) return false
        return pendingPaymentDao.forVenue(venueId).any { it.orderId == orderId && it.syncStatus == PaymentSyncStatus.SYNCED.name }
    }

    /** El estado en vivo de un cobro encolado (null = ya no está: la cola lo sincronizó y lo limpió). */
    fun observarEstado(id: String): Flow<String?> = pendingPaymentDao.observarEstado(id)

    /** D1 (26-oct): cómo va, en vivo, la parte de una venta dividida que se guardó sin red. Ver [estadoDeParte]. */
    fun observarParte(id: String): Flow<EstadoDeParte> {
        val venueId = secureStorage.venueId ?: return kotlinx.coroutines.flow.flowOf(EstadoDeParte.NoEsta)
        return pendingPaymentDao.observarFila(venueId, id).map(::estadoDeParte)
    }

    /**
     * El id del pago que el SERVIDOR le dio a un cobro encolado que ya subió (Info-7, QA en la D3, 15-oct): con él
     * «Cobro exitoso» pregunta por el sello de esa venta y cambia «El sello se suma al sincronizar» por lo que pasó.
     * La cola lo deja en `q15ResultJson` en la misma escritura que marca la fila SYNCED (`ConciliacionDelCobro.evaluar`).
     * null si la fila ya no está (la limpieza la borró), es de otro negocio, aún no sube o el servidor no dijo el id.
     */
    suspend fun pagoDelServidor(id: String): String? {
        val venueId = secureStorage.venueId ?: return null
        val fila = pendingPaymentDao.findForVenue(venueId, id) ?: return null
        if (fila.syncStatus != PaymentSyncStatus.SYNCED.name) return null
        val resultado = fila.q15ResultJson ?: return null
        return runCatching {
            (Json.parseToJsonElement(resultado).jsonObject["paymentId"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun PendingPaymentEntity.externalOriginal(): String? = orderRequestJson?.let { raw ->
        runCatching {
            (Json.parseToJsonElement(raw).jsonObject["externalId"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
        }.getOrNull()
    }

    private fun construirFila(
        orderRequest: CreateOrderRequest,
        staffId: String,
        cashTenderedCents: Int?,
        changeCents: Int?,
        rating: Int?,
        orderId: String?,
        orderExternalId: String?,
        customerId: String?,
        idempotencyKey: String?,
        manualMethod: com.avoqado.pos.payment.domain.ManualPaymentMethod?,
        tenderType: com.avoqado.pos.payment.domain.TenderTypeOption?,
        estado: PaymentSyncStatus,
        amountCents: Int,
        tipCents: Int,
        splitType: String,
        venueId: String,
        /** La hora de la venta, una sola vez: es la de la fila y la que viaja como `createdAtLocal`. */
        capturadaEn: Long,
        /** La caja abierta al confirmar el cobro (efectivo sin red en el turno correcto). Nula = no había. */
        cajaDelCobro: String?,
        /** D15: sólo la reserva la toma ([SalidaDelApartado.ventasDelServidorEnLaCaja]). */
        ventasYaEnLaCaja: List<String>? = null,
    ): PendingPaymentEntity {
        val localId = idempotencyKey?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        // La misma regla que crear la orden en línea: una venta rápida DIVIDIDA también es ORDER (D2 etapa 2), así su parte
        // guardada sin red se reproduce contra la misma orden y entra a la espera de D1-B.
        val hasOrderItems = OrderRepository.aceptaCrearOrden(orderRequest)
        val paymentType = if (orderId != null || hasOrderItems) "ORDER" else "FAST"
        val entity = PendingPaymentEntity(
            id = localId,
            venueId = venueId,
            staffId = staffId,
            amountCents = amountCents,
            tipCents = tipCents,
            splitType = splitType,
            // Se guarda el nombre del método MANUAL (o "CASH"); el sync lo
            // traduce al enum del server al reproducirlo.
            method = manualMethod?.name ?: "CASH",
            // 🔴 La cola guarda el TIPO del catálogo. Sin esto, una venta cobrada sin
            // red perdía el tipo al reproducirse y aterrizaba como EFECTIVO, callada,
            // y la idempotencia impedía repararla.
            tenderTypeId = tenderType?.id,
            tenderRevision = tenderType?.revision,
            // 🔴 EL CLIENTE, en COLUMNA propia y no sólo dentro del `orderRequestJson`:
            // ese JSON sólo existe cuando la venta tiene productos, así que el cobro
            // RÁPIDO ("Otro importe") perdía al cliente y se reproducía anónimo. Se
            // guarda en los dos tipos para que el replay no tenga que adivinar dónde
            // buscarlo.
            customerId = customerId?.takeIf { it.isNotBlank() },
            paymentType = paymentType,
            orderId = orderId,
            orderNumber = null,
            cashTenderedCents = cashTenderedCents,
            changeCents = changeCents,
            rating = rating,
            itemsJson = null,
            orderRequestJson = if (hasOrderItems) {
                OrderRepository.buildCreateOrderPayload(
                    request = orderRequest,
                    staffId = staffId,
                    customerId = customerId,
                    // D16: el tipo del carrito, igual que la orden en línea. Sin él, «En tienda» subía como TAKEOUT.
                    orderType = orderRequest.orderType ?: "TAKEOUT",
                    externalId = orderExternalId ?: "offline-order:$localId",
                    // «La venta ya ocurrió»: el servidor nunca la rechaza por precio (contrato del precio que vio el cliente).
                    isOfflineReplay = true,
                    createdAtLocal = capturadaEn,
                )
            } else {
                null
            },
            syncStatus = estado.name,
            retryCount = 0,
            createdAt = capturadaEn,
            // 🔴 La caja de la venta viaja en la fila desde la PRIMERA escritura (la reserva, antes de la red).
            cashDrawerSessionId = cajaDelCobro?.takeIf { it.isNotBlank() },
        )
        val capture = CapturaDelCobro(
            venueId = entity.venueId, key = entity.id, orderId = entity.orderId,
            amountCents = entity.amountCents, tipCents = entity.tipCents, splitType = entity.splitType,
            method = if (tenderType == null) manualMethod?.serverMethod ?: "CASH" else null,
            tenderTypeId = entity.tenderTypeId, tenderRevision = entity.tenderRevision,
            cashTenderedCents = entity.cashTenderedCents, changeCents = entity.changeCents,
            customerId = entity.customerId, orderRequestJson = entity.orderRequestJson,
            // 🔴 D2 v7: el apartado que guarda ESTA app se da por cobrado si nadie lo resuelve. Un reintento con la misma llave
            // conserva la captura de disco (y su marca): `filaAlReemplazar`.
            daPorCobradaAlAbandonar = true.takeIf { estado == PaymentSyncStatus.EN_VUELO },
            // Codex r11 #1: la hora de ESTE intento; si ya había captura en disco, `filaAlReemplazar` conserva la del primero.
            primeraCapturaEn = capturadaEn,
            ventasYaEnLaCaja = ventasYaEnLaCaja)
        return entity.copy(q15CaptureJson = ConciliacionDelCobro.guardarCaptura(capture),
            capturedOrderRef = entity.orderId ?: if (hasOrderItems) orderExternalId ?: "offline-order:$localId" else null)
    }
}

/**
 * D1 (26-oct, founder «B»): la parte de una venta de tienda dividida que se cobró en efectivo y se guardó sin red. El resto
 * de la venta espera a que ESTA parte suba y el servidor le dé su orden; entonces se cobra contra esa orden (una sola venta).
 */
sealed interface EstadoDeParte {
    /** En la cola: se sube sola cuando vuelva la conexión. */
    data object Subiendo : EstadoDeParte

    /** Retenida, sin confirmar o fallida: la resuelve un gerente en «Operaciones por revisar». Esperar no basta. */
    data object PorRevisar : EstadoDeParte

    /**
     * Subió: su orden, lo que esa orden quedó debiendo según el servidor (null si no lo dijo) y si la dio por PAGADA (el
     * servidor perdona un centavo: PAID puede venir con saldo 1).
     */
    data class Subida(val orderId: String, val saldoCents: Int?, val pagada: Boolean = false) : EstadoDeParte

    /** Subió, pero el servidor dice que la venta debe otra cosa que el carrito: nada se cobra solo, lo revisa el cajero. */
    data class SaldoDistinto(val orderId: String, val saldoCents: Int) : EstadoDeParte

    /**
     * Venta sin productos (cobro rápido de importe tecleado): el servidor la cierra con lo cobrado y no hay orden que
     * continuar. Queda fuera de D1 (no hay inventario): el resto es otro cobro rápido, como antes.
     */
    data object SinOrden : EstadoDeParte

    /** Ya no está en este aparato. */
    data object NoEsta : EstadoDeParte
}

/** La lectura pura de una fila de la cola (sin red ni base: se prueba sola). */
internal fun estadoDeParte(fila: PendingPaymentEntity?): EstadoDeParte {
    if (fila == null) return EstadoDeParte.NoEsta
    if (fila.paymentType == "FAST") return EstadoDeParte.SinOrden
    return when (fila.syncStatus) {
        PaymentSyncStatus.SYNCED.name -> {
            val resultado = runCatching { Json.parseToJsonElement(fila.q15ResultJson ?: "").jsonObject }.getOrNull()
            val orderId = (resultado?.get("orderId") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: fila.orderId?.takeIf { it.isNotBlank() }
            val saldo = (resultado?.get("saldoCents") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            val pagada = (resultado?.get("estadoDeLaOrden") as? JsonPrimitive)?.content == "PAID"
            if (orderId == null) EstadoDeParte.PorRevisar else EstadoDeParte.Subida(orderId, saldo, pagada)
        }
        PaymentSyncStatus.PENDING.name, PaymentSyncStatus.SYNCING.name ->
            if (fila.q15Reason != null) EstadoDeParte.PorRevisar else EstadoDeParte.Subiendo
        else -> EstadoDeParte.PorRevisar
    }
}

/** Lo que este aparato tiene guardado de una orden, para «Cobrar» de la bandeja. Ver `CashPaymentRepository.efectivoDelPedido`. */
enum class EfectivoDelPedido {
    /** Nada que pueda llegar al servidor: se puede cobrar. */
    NINGUNO,
    /** En la cola: se sube solo al volver la red. Bloquea. */
    EN_COLA,
    /** Lo resuelve un gerente en «Operaciones por revisar». Bloquea. */
    POR_REVISAR,
}

sealed class CashPaymentResult {
    data class Success(val changeCents: Int) : CashPaymentResult()
    data class InsufficientFunds(val shortfall: Int) : CashPaymentResult()
}
