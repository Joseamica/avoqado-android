package com.avoqado.pos.escritorio.base

import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.di.DatabaseModule
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.sync.data.SalesNoticeStore
import com.avoqado.pos.sync.presentation.openSalesNoticeBanner
import com.avoqado.pos.sync.presentation.salesNoticeBannerCount
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** WINDOWS uses the same production count/router as both Android layouts, with its actual Room aggregate. */
class PagarDespuesAvisoRoomTest {
    @Test fun valid_empty_room_summary_does_not_hide_one_external_pay_later_review() = runBlocking {
        val folder = Files.createTempDirectory("pay-later-notice-")
        val context = ContextoDeEscritorio(folder)
        val db = DatabaseModule.provideDatabase(context)
        try {
            val storage = SecureStorage(context).apply { venueId = id(1); userId = id(2) }
            val notices = SalesNoticeStore(db.salesNoticeDao(), CashPaymentRepository(storage, db.pendingPaymentDao()))
            val summary = notices.summary(id(1))
            assertEquals(0, summary.pendingCount)
            assertEquals(1, salesNoticeBannerCount(summary, 0, fallback = 1, payLaterCount = 1))
        } finally { db.close(); folder.toFile().deleteRecursively() }
    }

    @Test fun unknown_summary_or_reservations_never_double_counts_the_external_review() {
        assertEquals(1, salesNoticeBannerCount(null, 0, fallback = 1, payLaterCount = 1))
        assertEquals(1, salesNoticeBannerCount(null, null, fallback = 1, payLaterCount = 1))
    }

    @Test fun manager_with_pay_later_reaches_quarantine_instead_of_the_cash_only_sales_list() {
        val folder = Files.createTempDirectory("pay-later-route-")
        try {
            val storage = SecureStorage(ContextoDeEscritorio(folder)).apply { userRole = "MANAGER" }
            val roles = RoleManager(storage)
            assertTrue(roles.canResolveQuarantine)
            var sales = 0; var quarantine = 0
            openSalesNoticeBanner(roles.canAccessTransactions && roles.canResolveQuarantine, true, payLaterCount = 1,
                onPendingSales = { sales++ }, onQuarantine = { quarantine++ })
            assertEquals(1, quarantine)
            assertEquals(0, sales)
        } finally { folder.toFile().deleteRecursively() }
    }

    @Test fun no_pay_later_keeps_the_previous_sales_route() {
        var sales = 0; var quarantine = 0
        openSalesNoticeBanner(true, true, payLaterCount = 0,
            onPendingSales = { sales++ }, onQuarantine = { quarantine++ })
        assertEquals(1, sales)
        assertEquals(0, quarantine)
    }

    private fun id(n: Int) = "c" + n.toString().padStart(24, '0')
}
