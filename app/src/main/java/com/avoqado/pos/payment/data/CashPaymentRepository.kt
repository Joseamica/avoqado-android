package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CashPaymentRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val pendingPaymentDao: PendingPaymentDao,
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
    ): String {
        val entity = construirFila(
            orderRequest, staffId, cashTenderedCents, changeCents, rating, orderId, orderExternalId,
            customerId, idempotencyKey, manualMethod, tenderType, PaymentSyncStatus.PENDING,
        )
        // REPLACE: si este cobro se había reservado (`reservarCobro`, misma llave) la reserva se
        // vuelve la fila de la cola, con los datos finales del intento.
        pendingPaymentDao.insert(entity)
        Log.d("💵", "💾 Cash payment queued offline: ${entity.id} (${entity.paymentType})")
        return entity.id
    }

    // MARK: - Cobro en vuelo (se guarda ANTES de tocar la red)

    /**
     * Los cobros que ESTE proceso reservó y todavía no suelta. Una fila `EN_VUELO` que no está
     * aquí es de un proceso que murió a media petición: ésa es la «sin confirmar».
     */
    private val reservasDeEsteProceso: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 🔴 Guarda el cobro en efectivo ANTES del POST (regla «se persiste antes de tocar la red»).
     *
     * Antes la fila nacía en el `onFailure`, hasta 15 s después del toque: si la app moría en
     * medio, el cobro no quedaba en ningún lado, y si el POST sí había llegado, el cajero lo
     * rehacía con otra llave (dos ventas por un billete). La reserva lleva la MISMA llave que el
     * intento en línea y que la cola; se suelta al terminar el intento (`soltarReserva`).
     *
     * Queda `EN_VUELO`: la cola no la reproduce sola y el cajón no la cuenta, porque si el proceso
     * muere no se sabe si la venta se completó. Eso lo decide una persona al reabrir.
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
    ) {
        val entity = construirFila(
            orderRequest, staffId, cashTenderedCents, changeCents, rating, orderId, orderExternalId,
            customerId, idempotencyKey, manualMethod, tenderType, PaymentSyncStatus.EN_VUELO,
        )
        // Primero la marca en memoria y DESPUÉS la fila: quien observe la tabla nunca ve este
        // cobro como «sin confirmar» mientras va en vuelo.
        reservasDeEsteProceso += entity.id
        pendingPaymentDao.insert(entity)
    }

    /** El intento terminó (bien, encolado, rechazado o cancelado): la reserva sobra. */
    suspend fun soltarReserva(id: String) {
        pendingPaymentDao.borrarSiEnVuelo(id)
        reservasDeEsteProceso -= id
    }

    /**
     * La pantalla que llevaba este cobro se cerró con el POST en vuelo: ya nadie va a soltar la
     * reserva y no se sabe si el servidor la recibió. Deja de esconderse: aparece «sin confirmar».
     */
    fun olvidarReservaEnMemoria(id: String) {
        reservasDeEsteProceso -= id
    }

    /** Cobros de un proceso que murió a media petición, del local actual. */
    suspend fun cobrosSinConfirmar(): List<PendingPaymentEntity> {
        val venueId = secureStorage.venueId ?: return emptyList()
        return pendingPaymentDao.enVueloDelVenue(venueId).filter { it.id !in reservasDeEsteProceso }
    }

    /** Lo mismo, en vivo, para el aviso y la cuarentena. */
    fun observarSinConfirmar(): Flow<List<PendingPaymentEntity>> =
        pendingPaymentDao.observarEnVuelo().map { filas ->
            val venueId = secureStorage.venueId
            filas.filter { it.venueId == venueId && it.id !in reservasDeEsteProceso }
        }

    /** «Sí se cobró»: a la cola con la MISMA llave. Si el POST sí había llegado, el servidor deduplica. */
    suspend fun confirmarQueSeCobro(id: String) {
        pendingPaymentDao.enVueloAPendiente(id)
        Log.w("💵", "Cobro sin confirmar $id: el cajero dice que SÍ se cobró — se manda a la cola")
    }

    /** «No se cobró»: se borra, y sólo si seguía sin confirmar. */
    suspend fun descartarPorqueNoSeCobro(id: String) {
        pendingPaymentDao.borrarSiEnVuelo(id)
        Log.w("💵", "Cobro sin confirmar $id: el cajero dice que NO se cobró — se descarta")
    }

    /** El estado en vivo de un cobro encolado (null = ya no está: la cola lo sincronizó y lo limpió). */
    fun observarEstado(id: String): Flow<String?> = pendingPaymentDao.observarEstado(id)

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
    ): PendingPaymentEntity {
        val localId = idempotencyKey?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val hasOrderItems = OrderRepository.hasProductItems(orderRequest)
        val paymentType = if (orderId != null || hasOrderItems) "ORDER" else "FAST"
        return PendingPaymentEntity(
            id = localId,
            venueId = secureStorage.venueId ?: "",
            staffId = staffId,
            amountCents = orderRequest.total - orderRequest.tip,
            tipCents = orderRequest.tip,
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
                    externalId = orderExternalId ?: "offline-order:$localId",
                )
            } else {
                null
            },
            syncStatus = estado.name,
            retryCount = 0,
            createdAt = System.currentTimeMillis(),
        )
    }
}

sealed class CashPaymentResult {
    data class Success(val changeCents: Int) : CashPaymentResult()
    data class InsufficientFunds(val shortfall: Int) : CashPaymentResult()
}
