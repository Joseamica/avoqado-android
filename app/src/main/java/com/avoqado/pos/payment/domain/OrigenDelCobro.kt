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
    /** Un saldo de bandeja sirve para efectivo durable; jamás para autorizar una tarjeta. */
    PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO,

    /**
     * Rappi 2b: un pedido de plataforma que el negocio cobra en EFECTIVO con su repartidor («Rappi · por cobrar»). Sólo efectivo
     * (el SERVIDOR rechaza otro método con `RAPPI_CASH_ORDER_CASH_ONLY`), sin calificación ni propina (la propina ya viene en el
     * pedido) y el SERVIDOR reparte lo recibido (terceros → propina → venta).
     */
    PEDIDO_DE_PLATAFORMA_EFECTIVO,

    /** Igual, abierto con el saldo GUARDADO en el aparato (sin red al abrir): el efectivo ya recibido se manda como captura previa. */
    PEDIDO_DE_PLATAFORMA_EFECTIVO_SALDO_GUARDADO;

    val esPedidoEnLinea: Boolean get() = this != NORMAL
    val permiteTarjeta: Boolean get() = this == NORMAL || this == PEDIDO_EN_LINEA
    val esDePlataforma: Boolean get() = this == PEDIDO_DE_PLATAFORMA_EFECTIVO || this == PEDIDO_DE_PLATAFORMA_EFECTIVO_SALDO_GUARDADO

    /** La calificación y la propina del cliente: no en un pedido de plataforma (la propina ya viene en el pedido; sería otra). */
    val pideCalificacionYPropina: Boolean get() = !esDePlataforma

    /**
     * El efectivo del repartidor YA se recibió cuando el cajero confirma: el POST va en el momento con `isOfflineReplay` (captura
     * previa), así el SERVIDOR no lo rechaza ni lo recorta si Rappi canceló u otro aparato lo cobró mientras el cobro estaba abierto;
     * lo registra y avisa (Codex plan r1 #1, r2 #1; código de apps r1 #2: también con el saldo fresco).
     */
    val esCapturaPrevia: Boolean get() = esDePlataforma
}
