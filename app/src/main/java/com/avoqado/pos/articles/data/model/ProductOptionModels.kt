package com.avoqado.pos.articles.data.model

import kotlinx.serialization.Serializable
import java.util.Locale

// MARK: - Product Option Models

@Serializable
data class ProductOption(
    val id: String,
    val name: String = "",
    val active: Boolean? = null,
    val values: List<ProductOptionValue>? = null,
) {
    val valueCount: Int get() = values?.size ?: 0

    val valuesPreview: String
        get() = values?.take(3)?.joinToString(", ") { it.name } ?: ""
}

@Serializable
data class ProductOptionsListResponse(
    val success: Boolean = true,
    val data: List<ProductOption> = emptyList(),
)

@Serializable
data class ProductOptionValue(
    val id: String,
    val name: String = "",
    val displayOrder: Int? = null,
    val active: Boolean? = null,
)
