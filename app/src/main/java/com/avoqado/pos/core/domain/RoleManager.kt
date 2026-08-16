package com.avoqado.pos.core.domain

import com.avoqado.pos.core.data.local.SecureStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoleManager @Inject constructor(
    private val secureStorage: SecureStorage,
) {
    val role: String
        get() = secureStorage.userRole?.uppercase() ?: "VIEWER"

    // MARK: - Feature Access

    /** POS checkout: WAITER, CASHIER, MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canAccessPOS: Boolean
        get() = role in setOf("WAITER", "CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /** Inventory: MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canAccessInventory: Boolean
        get() = role in setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /** Transactions: CASHIER, MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canAccessTransactions: Boolean
        get() = role in setOf("CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /** Create products: MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canCreateProducts: Boolean
        get() = role in setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /** Manage customers (create/edit): MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canManageCustomers: Boolean
        get() = role in setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /** View customers: WAITER, CASHIER, MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canViewCustomers: Boolean
        get() = role in setOf("WAITER", "CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /** Access reports: MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canAccessReports: Boolean
        get() = role in setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /** Issue refund (payments:refund): MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canIssueRefund: Boolean
        get() = role in setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /** Cash-drawer ops (open/close/pay-in-out): CASHIER, MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canManageCashDrawer: Boolean
        get() = role in setOf("CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /** Kitchen display: WAITER, CASHIER, MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canAccessKDS: Boolean
        get() = role in setOf("WAITER", "CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    // MARK: - Effective venue permissions

    /**
     * Permission checks for optional UI affordances must use the effective list
     * returned by the server for this venue, not a local role guess. That keeps
     * custom role permissions and Permission Sets authoritative.
     */
    fun hasVenuePermission(requiredPermission: String): Boolean {
        val (requiredResource, requiredAction) = requiredPermission.split(':', limit = 2)
            .let { parts -> parts.firstOrNull().orEmpty() to parts.getOrNull(1).orEmpty() }

        return secureStorage.venuePermissions.any { granted ->
            granted == "*:*" ||
                granted == requiredPermission ||
                granted == "$requiredResource:*" ||
                granted == "*:$requiredAction"
        }
    }

    /** Membership tiles are optional and must not trigger a forbidden request. */
    val canReadCreditPacks: Boolean
        get() = hasVenuePermission("creditPacks:read")

    // MARK: - Traslados entre sucursales (CEDIS)
    // Espejo de DEFAULT_PERMISSIONS del server (avoqado-server/src/lib/permissions.ts):
    // MANAGER tiene los 5 permisos inventory-transfers:* explícitos; ADMIN y OWNER
    // llevan el wildcard inventory-transfers:*. El server es la autoridad — esto
    // sólo decide qué UI pintar (Android no lee el array de permisos; deuda documentada).

    /** Ver traslados (inventory-transfers:read): MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canViewInventoryTransfers: Boolean
        get() = role in setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /** Decidir traslados — aprobar/rechazar/despachar/recibir/cancelar
     *  (inventory-transfers:approve|dispatch|receive): MANAGER, ADMIN, OWNER, SUPERADMIN */
    val canDecideInventoryTransfers: Boolean
        get() = role in setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    // MARK: - PIN de autorización de gerente

    /**
     * Cómo pintar una acción según si el rol la tiene y si el local activó el
     * PIN de autorización.
     *
     * 🔴 NO es lógica nueva de permisos: el juez sigue siendo el server. Esto
     * sólo decide si el control se ve. Con candado se toca igual, sale la
     * llamada, el server responde 403 `overridable` y el teclado aparece solo.
     */
    fun visibilityOf(allowed: Boolean, overrideEnabled: Boolean): ActionVisibility = when {
        allowed -> ActionVisibility.ALLOWED
        overrideEnabled -> ActionVisibility.LOCKED
        else -> ActionVisibility.HIDDEN
    }
}

/**
 * Cómo se pinta una acción que el rol no tiene.
 *
 * 🔴 Espejo EXACTO de `ActionVisibility` en avoqado-ios/Services/RoleManager.swift.
 *
 * Esconder un botón parece limpio, pero deja al piso sin salida: sin botón no
 * hay 403, y sin 403 no hay a quién pedirle autorización. Con el PIN de gerente
 * encendido, la acción se VE con un candado y el "no" llega con una puerta.
 */
enum class ActionVisibility { ALLOWED, LOCKED, HIDDEN }
