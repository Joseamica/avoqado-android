package com.avoqado.pos.payment.data

import com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El transporte real de la cancelación durable: reusa el servicio de terminal y el repositorio de
 * órdenes que ya existen, sin estrenar cliente HTTP ni contratos.
 */
@Singleton
class CancelacionDeCobroHttp @Inject constructor(
    private val terminalPaymentService: TerminalPaymentService,
    private val orderRepository: OrderRepository,
) : CancelacionDeCobroTransport {

    override suspend fun pedirCancelacion(venueId: String, terminalId: String, requestId: String): RespuestaDeCancelacion =
        terminalPaymentService.pedirCancelacion(
            requestId = requestId,
            terminalId = terminalId,
            venueId = venueId,
            reason = MOTIVO,
        )

    override suspend fun consultarEstado(venueId: String, requestId: String): ConsultaDeEstado =
        terminalPaymentService.consultarEstado(requestId, venueId, enSegundoPlano = true)

    override suspend fun cancelarOrden(venueId: String, orderId: String): ResultadoDeCancelarOrden =
        orderRepository.cancelOrder(orderId, venueId = venueId, reason = MOTIVO)

    /**
     * 🔴 La llave durable se toca en el hilo PRINCIPAL, que es donde la escriben el cobro y el
     * ViewModel. Es lo único que hace atómico el «léela y decide»: sin eso, la cancelación —que
     * corre en IO— podía pisar la llave de un cobro que acababa de empezar en la pantalla.
     */
    override suspend fun soltarLlaveSiEs(requestId: String) = withContext(Dispatchers.Main.immediate) {
        terminalPaymentService.soltarLlaveSiEs(requestId)
    }

    override suspend fun armarLlaveSiLibre(requestId: String): Boolean = withContext(Dispatchers.Main.immediate) {
        terminalPaymentService.armarLlaveSiLibre(requestId)
    }

    private companion object {
        /** Queda en la bitácora del servidor: por qué se canceló la cuenta. */
        const val MOTIVO = "Cobro con tarjeta cancelado en el POS"
    }
}
