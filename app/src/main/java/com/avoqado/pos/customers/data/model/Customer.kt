package com.avoqado.pos.customers.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Customer(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val createdAt: String? = null,
    val birthday: String? = null,
    val lastVisit: String? = null,
    val totalVisits: Int? = null,
    // 🔴 El server manda `averageOrderValue` y `totalSpent`; la app pedía un
    // `averageSpend` que NO existe en el contrato, así que el perfil de un cliente
    // con $331.10 gastados mostraba "Gasto promedio $0.00". Los nombres se espejan
    // EXACTO con el server: un typo aquí no falla, sólo miente en silencio.
    val averageOrderValue: Double? = null,
    val totalSpent: Double? = null,
    val loyaltyPoints: Int? = null,
    val groups: List<String>? = null,
) {
    val fullName: String
        get() {
            val first = firstName ?: ""
            val last = lastName ?: ""
            val name = "$first $last".trim()
            return name.ifEmpty { email ?: phone ?: "Cliente" }
        }

    val initials: String
        get() {
            val first = firstName?.take(1) ?: ""
            val last = lastName?.take(1) ?: ""
            val combined = "$first$last".uppercase()
            return combined.ifEmpty { "?" }
        }

    val displayContact: String?
        get() = phone ?: email
}

@Serializable
data class CustomersResponse(
    val data: List<Customer> = emptyList(),
    val pagination: Pagination? = null,
) {
    @Serializable
    data class Pagination(
        val total: Int = 0,
        val page: Int = 1,
        val limit: Int = 50,
        val totalPages: Int = 1,
    )
}

@Serializable
data class CreateCustomerResponse(
    val message: String? = null,
    val customer: Customer? = null,
)

@Serializable
data class CreateCustomerRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val birthday: String? = null,
    val groupIds: List<String>? = null,
)
