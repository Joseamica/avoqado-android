package com.avoqado.pos.payment.domain

/**
 * Cobros que el POS registra A MANO porque el dinero NO pasó por Avoqado.
 *
 * Existe por un caso real de piso: el mesero cobra con una terminal que no es
 * nuestra, o con la nuestra cuando su SIM 4G todavía no reporta. Su única
 * opción era marcarlo como efectivo, y eso rompía el arqueo — el corte pedía
 * dinero que nunca entró al cajón (el corte filtra por method=CASH).
 *
 * Deliberadamente NO distingue débito de crédito: el mesero no siempre lo sabe
 * y un dato inventado es peor que uno genérico. Se registra como OTHER y el
 * detalle real viaja en `externalSource`.
 *
 * 🔴 `serverMethod` se espeja por nombre EXACTO con el enum PaymentMethod del
 * server y con iOS. Un nombre que el server no conozca se rechaza.
 */
enum class ManualPaymentMethod(
    val serverMethod: String,
    val externalSource: String?,
    val label: String,
    val description: String,
) {
    CARD_EXTERNAL(
        serverMethod = "OTHER",
        externalSource = "Tarjeta (terminal externa)",
        label = "Tarjeta (otra terminal)",
        description = "Se cobró con una terminal que no es de Avoqado",
    ),
    TRANSFER(
        serverMethod = "BANK_TRANSFER",
        externalSource = "Transferencia",
        label = "Transferencia",
        description = "El cliente transfirió a la cuenta del negocio",
    ),
    OTHER(
        serverMethod = "OTHER",
        externalSource = "Otro",
        label = "Otro medio",
        description = "Vale, cortesía de un tercero, o cualquier otro acuerdo",
    ),
}
