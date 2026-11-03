package com.avoqado.pos.transactions

import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.transactions.data.model.PendingCustomerAssignment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** El mismo SecureStorage real de Android sobre el disco de escritorio; sin DPAPI en esta Mac de pruebas. */
class PendingCustomerAssignmentStorageTest {
    @Test fun `pending survives process reopening and stays scoped by actor venue and payment`() {
        val folder = Files.createTempDirectory("customer-assignment-storage")
        try {
            val pending = PendingCustomerAssignment("actor-a", "venue-a", "payment-a", "customer-a", "Ana")
            val first = SecureStorage(ContextoDeEscritorio(folder))
            assertTrue(first.writePendingCustomerAssignment(pending))
            val reopened = SecureStorage(ContextoDeEscritorio(folder))
            assertEquals(pending, reopened.readPendingCustomerAssignment("actor-a", "venue-a", "payment-a"))
            assertNull(reopened.readPendingCustomerAssignment("actor-b", "venue-a", "payment-a"))
            assertNull(reopened.readPendingCustomerAssignment("actor-a", "venue-b", "payment-a"))
            assertNull(reopened.readPendingCustomerAssignment("actor-a", "venue-a", "payment-b"))
            assertTrue(reopened.clearPendingCustomerAssignment(pending))
            assertNull(SecureStorage(ContextoDeEscritorio(folder)).readPendingCustomerAssignment("actor-a", "venue-a", "payment-a"))
        } finally { folder.toFile().deleteRecursively() }
    }
}
