package com.avoqado.pos.tables.data

import kotlinx.serialization.Serializable

@Serializable
data class Table(
    val id: String,
    val number: String,
    val capacity: Int? = null,
    val active: Boolean = true,
)
