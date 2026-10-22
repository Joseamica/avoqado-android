package com.avoqado.pos.payment.domain

/**
 * Quién abrió el cobro. Lo pasa la pantalla que lo abre (`PaymentFlowScreen` → `startPaymentFlow`); por default es
 * [NORMAL], y así cada cobro de hoy (mostrador, split de mostrador, mesas) queda exactamente igual.
 *
 * Se agregan valores aquí, nunca banderas sueltas: cada origen nuevo decide en `startPaymentFlow` qué contexto del
 * aparato puede adoptar su cobro.
 */
enum class OrigenDelCobro {
    /** El cobro de siempre. Una mesa en `PAYING` sigue mandando sobre la orden (así cobra la mesa). */
    NORMAL,

    /**
     * El cobro de un pedido de la tienda web, abierto desde la bandeja «Pedidos en línea» (pedido sin mesa).
     * 🔴 Cobra SIEMPRE la orden que le pasaron (`resumeOrderId`): una mesa que quedó en `PAYING` no lo captura
     * (Codex plan r1 #1: el dinero liquidaba otra cuenta y el pedido web seguía debiendo).
     */
    PEDIDO_EN_LINEA,
}
