package com.avoqado.pos.core.util

/**
 * Traducción LOCAL del enum de roles — espejo por nombre EXACTO de
 * `DEFAULT_ROLE_DISPLAY_NAMES` del server (venueRoleConfig.dashboard.service.ts).
 *
 * Es el RESPALDO para cuando el server no manda `roleDisplayName` (server
 * viejo): "WAITER" pelón en el selector de Vendedor fue el defecto que originó
 * esto (founder, 2026-09-01). El nombre custom del venue (p.ej. VIEWER
 * renombrado a "Investor") SIEMPRE gana — esto sólo traduce el enum.
 */
object RoleDisplay {
    private val SPANISH = mapOf(
        "SUPERADMIN" to "Super Administrador",
        "OWNER" to "Propietario",
        "ADMIN" to "Administrador",
        "MANAGER" to "Gerente",
        "CASHIER" to "Cajero",
        "WAITER" to "Mesero",
        "KITCHEN" to "Cocina",
        "HOST" to "Host",
        "VIEWER" to "Observador",
    )

    /**
     * El nombre que se PINTA: el custom del server si vino, si no la traducción
     * local del enum, y sólo como último recurso el enum crudo (un rol nuevo
     * que esta versión no conoce se enseña tal cual en vez de esconderse).
     */
    fun label(roleDisplayName: String?, role: String?): String? =
        roleDisplayName?.takeIf { it.isNotBlank() }
            ?: role?.let { SPANISH[it] ?: it }
}
