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
    /** Respaldo: el servidor de hoy NO lo manda (manda `meta`); se conserva por si algún camino viejo lo usa. */
    val pagination: Pagination? = null,
    /**
     * Lo que el servidor manda de verdad: `{ data, meta: { totalCount, …, totalMatches?, searchCapped? } }` (auditoría
     * A #10 y B3). Opcional: un servidor viejo o un campo nuevo no rompen la lectura (`ignoreUnknownKeys`).
     */
    val meta: Meta? = null,
) {
    @Serializable
    data class Pagination(
        val total: Int = 0,
        val page: Int = 1,
        val limit: Int = 50,
        val totalPages: Int = 1,
    )

    @Serializable
    data class Meta(
        /**
         * Cuántos clientes cumplen la búsqueda y se pueden ALCANZAR paginando. Con más de 5000 coincidencias el servidor
         * topa y aquí manda 5000 (auditoría B3: así el dashboard no ofrece páginas vacías); el exacto va en [totalMatches].
         */
        val totalCount: Int? = null,
        /** Conteo EXACTO de coincidencias en TODO el negocio (B3). Campo nuevo: un servidor viejo no lo manda. */
        val totalMatches: Int? = null,
        /** true = más de 5000 coincidencias: el servidor sólo pagina las más nuevas. Ausente (servidor viejo) = false. */
        val searchCapped: Boolean? = null,
    )

    /**
     * El total que se le dice al cajero: el EXACTO (`meta.totalMatches`) si vino; si no, `meta.totalCount`, o
     * `pagination.total`, o null si no vino ninguno (no se inventa).
     */
    val totalDelServidor: Int?
        get() = meta?.totalMatches ?: meta?.totalCount ?: pagination?.total
}

/** Una respuesta de la búsqueda: lo que se pinta, cuántos cumplen en TODO el negocio y si el servidor tuvo que topar. */
data class PaginaDeClientes(
    val clientes: List<Customer>,
    val total: Int?,
    val busquedaTopada: Boolean = false,
)

@Serializable
data class CreateCustomerResponse(
    val message: String? = null,
    val customer: Customer? = null,
)

/**
 * `PUT /mobile/venues/:venueId/customers/:customerId`: sólo datos de contacto, y sólo los que cambiaron. Los `null` NO
 * viajan (el repositorio serializa con `encodeDefaults = false`): el servidor rechaza cualquier otro campo.
 */
@Serializable
data class UpdateCustomerRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val phone: String? = null,
    val email: String? = null,
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
