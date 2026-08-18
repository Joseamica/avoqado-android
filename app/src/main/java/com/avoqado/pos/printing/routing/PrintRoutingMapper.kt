package com.avoqado.pos.printing.routing

/**
 * PRINT_STATIONS — glue between the POS cart and the pure routing engine.
 *
 * Resolves each cart line's effective station from the venue's [PrintConfig]
 * (product override ?? category default ?? venue default) and produces the
 * per-station comanda plans. Pure + deterministic → unit-tested; no printing I/O.
 */
object PrintRoutingMapper {

    /** Distil the routing inputs the engine needs from the loaded config. */
    fun toRoutingConfig(config: PrintConfig): RoutingConfig = RoutingConfig(
        defaultStationId = config.defaultStationId,
        activeStationIds = config.stations.filter { it.active }.map { it.id }.toSet(),
    )

    /**
     * Split the given cart lines into one comanda plan per effective station.
     * Unrouted items (no station, no default) collapse into a single plan with
     * stationId == null → the caller prints ONE marked "SIN ESTACIÓN" ticket.
     */
    fun buildComandas(items: List<RoutableItem>, config: PrintConfig): List<TicketPlan> {
        val categoryMap = config.categoryRouting.associate { it.categoryId to it.printStationId }
        val productMap = config.productOverrides.associate { it.productId to it.printStationId }

        val routingItems = items.map { item ->
            RoutingItemInput(
                orderItemId = item.orderItemId,
                productId = item.productId,
                productStationId = item.productId?.let { productMap[it] },
                categoryStationId = item.categoryId?.let { categoryMap[it] },
                productName = item.productName,
                quantity = item.quantity,
                modifiers = item.modifiers,
                notes = item.notes,
            )
        }
        return PrintRoutingEngine.buildTicketPlans(routingItems, toRoutingConfig(config))
    }

    /** Resolve the station name for a plan (null for the unrouted "SIN ESTACIÓN" bucket). */
    fun stationName(stationId: String?, config: PrintConfig): String? =
        stationId?.let { id -> config.stations.firstOrNull { it.id == id }?.name }
}

/**
 * The minimal cart-line shape the router needs. The ViewModel maps its real cart
 * items to this, keeping the mapper decoupled from the cart model.
 */
data class RoutableItem(
    val orderItemId: String,
    val productId: String?,
    val categoryId: String?,
    val productName: String,
    val quantity: Int,
    val modifiers: List<String> = emptyList(),
    val notes: String? = null,
    /**
     * COMBOS — nombre del combo al que pertenece la línea, o null si va suelta.
     * NO entra al motor de ruteo (que es espejo byte a byte del server y no sabe
     * de promociones): viaja aparte hasta [com.avoqado.pos.printing.data.ComandaPrinter],
     * que encabeza con él los productos de CADA estación. Un combo cuyos productos
     * se reparten entre cocina y barra sale encabezado en las dos comandas.
     */
    val comboName: String? = null,
)
