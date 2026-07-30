package com.avoqado.pos.core.domain

import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleManagerTest {

    private val secureStorage = mockk<SecureStorage>()
    private val roleManager = RoleManager(secureStorage)

    // MARK: - Role defaults

    @Test
    fun `null role defaults to VIEWER`() {
        every { secureStorage.userRole } returns null
        assertEquals("VIEWER", roleManager.role)
    }

    @Test
    fun `role is uppercased`() {
        every { secureStorage.userRole } returns "waiter"
        assertEquals("WAITER", roleManager.role)
    }

    // MARK: - VIEWER (lowest): no access to POS, inventory, transactions

    @Test
    fun `VIEWER cannot access POS`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canAccessPOS)
    }

    @Test
    fun `VIEWER cannot access inventory`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canAccessInventory)
    }

    @Test
    fun `VIEWER cannot access transactions`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canAccessTransactions)
    }

    @Test
    fun `VIEWER cannot create products`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canCreateProducts)
    }

    @Test
    fun `VIEWER cannot manage customers`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canManageCustomers)
    }

    @Test
    fun `VIEWER cannot view customers`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canViewCustomers)
    }

    // MARK: - HOST: same as VIEWER

    @Test
    fun `HOST cannot access POS`() {
        every { secureStorage.userRole } returns "HOST"
        assertFalse(roleManager.canAccessPOS)
    }

    @Test
    fun `HOST cannot access inventory`() {
        every { secureStorage.userRole } returns "HOST"
        assertFalse(roleManager.canAccessInventory)
    }

    // MARK: - KITCHEN: same as VIEWER

    @Test
    fun `KITCHEN cannot access POS`() {
        every { secureStorage.userRole } returns "KITCHEN"
        assertFalse(roleManager.canAccessPOS)
    }

    // MARK: - WAITER: can access POS and view customers, nothing else

    @Test
    fun `WAITER can access POS`() {
        every { secureStorage.userRole } returns "WAITER"
        assertTrue(roleManager.canAccessPOS)
    }

    @Test
    fun `WAITER cannot access inventory`() {
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canAccessInventory)
    }

    @Test
    fun `WAITER cannot access transactions`() {
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canAccessTransactions)
    }

    @Test
    fun `WAITER cannot create products`() {
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canCreateProducts)
    }

    @Test
    fun `WAITER cannot manage customers`() {
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canManageCustomers)
    }

    @Test
    fun `WAITER can view customers`() {
        every { secureStorage.userRole } returns "WAITER"
        assertTrue(roleManager.canViewCustomers)
    }

    // MARK: - Effective venue permissions

    @Test
    fun `credit packs stay hidden when effective permission is absent`() {
        every { secureStorage.venuePermissions } returns listOf(
            "menu:read",
            "area-tickets:issue",
        )

        assertFalse(roleManager.canReadCreditPacks)
    }

    @Test
    fun `credit packs are available when exact effective permission is present`() {
        every { secureStorage.venuePermissions } returns listOf(
            "menu:read",
            "creditPacks:read",
        )

        assertTrue(roleManager.canReadCreditPacks)
    }

    @Test
    fun `venue permission matcher accepts resource and global wildcards`() {
        every { secureStorage.venuePermissions } returns listOf("creditPacks:*")
        assertTrue(roleManager.hasVenuePermission("creditPacks:read"))

        every { secureStorage.venuePermissions } returns listOf("*:*")
        assertTrue(roleManager.hasVenuePermission("area-tickets:deliver"))
    }

    // MARK: - CASHIER: POS + transactions + view customers

    @Test
    fun `CASHIER can access POS`() {
        every { secureStorage.userRole } returns "CASHIER"
        assertTrue(roleManager.canAccessPOS)
    }

    @Test
    fun `CASHIER cannot access inventory`() {
        every { secureStorage.userRole } returns "CASHIER"
        assertFalse(roleManager.canAccessInventory)
    }

    @Test
    fun `CASHIER can access transactions`() {
        every { secureStorage.userRole } returns "CASHIER"
        assertTrue(roleManager.canAccessTransactions)
    }

    @Test
    fun `CASHIER cannot create products`() {
        every { secureStorage.userRole } returns "CASHIER"
        assertFalse(roleManager.canCreateProducts)
    }

    @Test
    fun `CASHIER can view customers`() {
        every { secureStorage.userRole } returns "CASHIER"
        assertTrue(roleManager.canViewCustomers)
    }

    // MARK: - MANAGER: full access

    @Test
    fun `MANAGER can access POS`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canAccessPOS)
    }

    @Test
    fun `MANAGER can access inventory`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canAccessInventory)
    }

    @Test
    fun `MANAGER can access transactions`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canAccessTransactions)
    }

    @Test
    fun `MANAGER can create products`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canCreateProducts)
    }

    @Test
    fun `MANAGER can manage customers`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canManageCustomers)
    }

    @Test
    fun `MANAGER can view customers`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canViewCustomers)
    }

    // MARK: - ADMIN, OWNER, SUPERADMIN: full access (spot checks)

    @Test
    fun `ADMIN has full access`() {
        every { secureStorage.userRole } returns "ADMIN"
        assertTrue(roleManager.canAccessPOS)
        assertTrue(roleManager.canAccessInventory)
        assertTrue(roleManager.canAccessTransactions)
        assertTrue(roleManager.canCreateProducts)
        assertTrue(roleManager.canManageCustomers)
        assertTrue(roleManager.canViewCustomers)
    }

    @Test
    fun `OWNER has full access`() {
        every { secureStorage.userRole } returns "OWNER"
        assertTrue(roleManager.canAccessPOS)
        assertTrue(roleManager.canAccessInventory)
        assertTrue(roleManager.canAccessTransactions)
        assertTrue(roleManager.canCreateProducts)
        assertTrue(roleManager.canManageCustomers)
        assertTrue(roleManager.canViewCustomers)
    }

    @Test
    fun `SUPERADMIN has full access`() {
        every { secureStorage.userRole } returns "SUPERADMIN"
        assertTrue(roleManager.canAccessPOS)
        assertTrue(roleManager.canAccessInventory)
        assertTrue(roleManager.canAccessTransactions)
        assertTrue(roleManager.canCreateProducts)
        assertTrue(roleManager.canManageCustomers)
        assertTrue(roleManager.canViewCustomers)
    }

    // MARK: - Unknown role treated as no access

    @Test
    fun `unknown role has no access`() {
        every { secureStorage.userRole } returns "INTERN"
        assertFalse(roleManager.canAccessPOS)
        assertFalse(roleManager.canAccessInventory)
        assertFalse(roleManager.canAccessTransactions)
        assertFalse(roleManager.canCreateProducts)
        assertFalse(roleManager.canManageCustomers)
        assertFalse(roleManager.canViewCustomers)
    }
}
