package com.avoqado.pos.tables.data

import kotlinx.serialization.Serializable

/**
 * Plano en el POS (spec docs/superpowers/specs/2026-10-09-plano-de-mesas-pos-design.md §3.2). Espejo de
 * avoqado-ios Tables/FloorPlanModels.swift. Todo con default: un servidor que no mande un campo no rompe el plano.
 */
@Serializable
data class TablesVersions(
    val tablesVersion: String = "",
    val floorPlanVersion: String = "",
)

/** GET /mobile/venues/{venueId}/tables/version — la pregunta barata de cada 10 s. */
@Serializable
data class TablesVersionResponse(
    val success: Boolean = true,
    val data: TablesVersions? = null,
)

@Serializable
data class FloorPlanArea(
    val id: String,
    val name: String = "",
    /** WIDE | SQUARE | TALL; null = área anterior al plano ⇒ se dibuja WIDE. */
    val floorShape: String? = null,
    val sortOrder: Int = 0,
)

/** Coordenadas 0–1 relativas a la caja de SU área (spec 2026-10-08 §4.2). */
@Serializable
data class FloorPlanElement(
    val id: String,
    val type: String = "WALL", // WALL | BAR_COUNTER | SERVICE_AREA | LABEL | DOOR
    val areaId: String? = null,
    val positionX: Double = 0.0,
    val positionY: Double = 0.0,
    val width: Double? = null,
    val height: Double? = null,
    val rotation: Int = 0,
    val endX: Double? = null,
    val endY: Double? = null,
    val label: String? = null,
    val color: String? = null,
)

@Serializable
data class FloorPlanPayload(
    val floorPlanVersion: String = "",
    val areas: List<FloorPlanArea> = emptyList(),
    val elements: List<FloorPlanElement> = emptyList(),
    /** El servidor recortó el plano (más de 30 áreas o 1500 elementos). */
    val overLimit: Boolean = false,
)

/** GET /mobile/venues/{venueId}/floor-plan */
@Serializable
data class FloorPlanResponse(
    val success: Boolean = true,
    val data: FloorPlanPayload? = null,
)
