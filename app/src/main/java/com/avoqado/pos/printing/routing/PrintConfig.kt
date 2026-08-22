package com.avoqado.pos.printing.routing

import kotlinx.serialization.Serializable

/**
 * PRINT_STATIONS — client-side print configuration (mirror of the server's
 * PrintConfigPayload from GET /mobile/venues/:venueId/print-config).
 *
 * A venue with NO stations returns `stations: []`, so an unconfigured venue makes
 * the POS behave exactly as today. `version` is a content hash the client uses to
 * detect config changes (anti-staleness) without diffing the whole payload.
 */
@Serializable
data class PrintConfig(
    val gateway: GatewayInfo? = null,
    val printers: List<PrinterInfo> = emptyList(),
    val stations: List<StationInfo> = emptyList(),
    val defaultStationId: String? = null,
    /**
     * La estación donde se EMPACA, o `null` si el negocio no marcó ninguna.
     *
     * Recibe un ticket con el pedido COMPLETO —no una comanda más— para quien arma la bolsa
     * de reparto. `null` significa que no sale ticket extra: el default es no cambiarle nada
     * a quien no lo pidió.
     */
    val packingStationId: String? = null,
    /** Categories that have an explicit station set. */
    val categoryRouting: List<CategoryRoute> = emptyList(),
    /** Products that have an explicit override set. */
    val productOverrides: List<ProductOverride> = emptyList(),
    val version: String = "",
)

@Serializable
data class GatewayInfo(
    val terminalId: String,
    val address: String? = null,
    val active: Boolean = true,
)

@Serializable
data class PrinterInfo(
    val id: String,
    val name: String,
    val connectionType: String,
    val address: String? = null,
    val stableKey: String? = null,
    val paperWidthMm: Int = 80,
    /**
     * Corrimiento a la derecha en columnas (`GS L`). Opcional con default para
     * que un server viejo —o un config cacheado de antes— siga deserializando.
     */
    val leftMarginChars: Int = 0,
    val charset: String = "CP858",
    val active: Boolean = true,
    val lastStatus: String? = null,
    val lastSeenAt: String? = null,
)

@Serializable
data class StationInfo(
    val id: String,
    val name: String,
    val printerId: String? = null,
    val copies: Int = 1,
    val isDefault: Boolean = false,
    val active: Boolean = true,
    val displayOrder: Int = 0,
)

@Serializable
data class CategoryRoute(val categoryId: String, val printStationId: String)

@Serializable
data class ProductOverride(val productId: String, val printStationId: String)

/** Retrofit envelope for GET /mobile/venues/:venueId/print-config → `{ success, data }`. */
@Serializable
data class PrintConfigResponse(val success: Boolean = true, val data: PrintConfig = PrintConfig())
