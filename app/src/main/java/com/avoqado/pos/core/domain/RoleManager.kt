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
}
