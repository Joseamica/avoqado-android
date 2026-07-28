package com.avoqado.pos.addons.domain

data class Addon(
    val id: String,
    val name: String,
    val description: String,
    val iconName: String,
    val category: AddonCategory,
)

enum class AddonCategory(val displayName: String) {
    RECOMMENDED("Recomendaciones para tu negocio"),
    REACH_CLIENTS("Llegar a mas clientes"),
    SELL_ONLINE("Vender en línea"),
    BOOST_SALES("Aumentar las ventas"),
}

val allAddons = listOf(
    Addon(
        id = "estimates",
        name = "Presupuestos",
        description = "Crea y envia presupuestos a tus clientes antes de facturar.",
        iconName = "Description",
        category = AddonCategory.RECOMMENDED,
    ),
    Addon(
        id = "loyalty",
        name = "Programa de lealtad",
        description = "Recompensa a tus clientes frecuentes con puntos y promociones.",
        iconName = "Loyalty",
        category = AddonCategory.RECOMMENDED,
    ),
    Addon(
        id = "gift_cards",
        name = "Tarjetas de regalo",
        description = "Vende tarjetas de regalo fisicas y digitales.",
        iconName = "CardGiftcard",
        category = AddonCategory.BOOST_SALES,
    ),
    Addon(
        id = "online_store",
        name = "Tienda en línea",
        description = "Publica tus productos en una tienda web sincronizada con tu inventario.",
        iconName = "ShoppingCart",
        category = AddonCategory.SELL_ONLINE,
    ),
    Addon(
        id = "invoicing",
        name = "Facturación",
        description = "Genera facturas electronicas CFDI desde la app.",
        iconName = "Receipt",
        category = AddonCategory.RECOMMENDED,
    ),
    Addon(
        id = "marketing",
        name = "Marketing por email",
        description = "Envia campanas de email a tus clientes directamente.",
        iconName = "Email",
        category = AddonCategory.REACH_CLIENTS,
    ),
    Addon(
        id = "delivery",
        name = "Entregas",
        description = "Gestiona pedidos de entrega a domicilio y rastrea repartidores.",
        iconName = "LocalShipping",
        category = AddonCategory.REACH_CLIENTS,
    ),
    Addon(
        id = "appointments",
        name = "Citas",
        description = "Permite a tus clientes agendar citas en línea.",
        iconName = "CalendarMonth",
        category = AddonCategory.BOOST_SALES,
    ),
)
