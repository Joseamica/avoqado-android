package com.avoqado.pos.inventory.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Supplier(
    val id: String,
    val name: String = "",
    val contactName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val rating: Double = 0.0,
    val leadTimeDays: Int = 0,
    val active: Boolean = true,
)

@Serializable
data class SuppliersResponse(
    val success: Boolean = true,
    val data: List<Supplier> = emptyList(),
)
