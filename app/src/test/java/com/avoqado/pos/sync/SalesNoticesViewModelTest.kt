package com.avoqado.pos.sync

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.sync.data.*
import com.avoqado.pos.sync.presentation.SalesNoticesViewModel
import com.avoqado.pos.tables.data.TableSession
import io.mockk.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SalesNoticesViewModelTest {
    @get:Rule val main=MainDispatcherRule()
    private val store=mockk<SalesNoticeStore>(relaxed=true)
    private val storage=mockk<SecureStorage>(relaxed=true)
    private val session=mockk<TableSession>(relaxed=true)
    private var venue:String?="venue-A"; private var actor:String?="manager-A"; private var role="MANAGER"; private var epoch=1L
    private val payments=mockk<com.avoqado.pos.payment.data.PaymentSyncService>()
    private fun offlineNetwork():com.avoqado.pos.core.util.ConnectivityMonitor {
        val network=mockk<com.avoqado.pos.core.util.ConnectivityMonitor>()
        every { network.isConnected } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        every { network.isServerReachable } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        every { network.isFullyConnected } returns false
        return network
    }
    private fun vm():SalesNoticesViewModel {
        every { storage.venueId } answers { venue };every { storage.userId } answers { actor }
        every { storage.userRole } answers { role };every { session.contextoActual() } answers { epoch }
        coEvery { store.summary(any()) } returns SalesNoticeSummary(2,2,mapOf("FAILED" to 2),emptyMap())
        return SalesNoticesViewModel(store,storage,RoleManager(storage),session,payments,offlineNetwork())
    }
    @Test fun denied_actor_never_reads_or_acknowledges_store()=runTest {
        role="WAITER";val vm=vm();vm.refresh();vm.acknowledgeSelected();advanceUntilIdle()
        coVerify(exactly=0) { store.page(any(),any(),any()) };coVerify(exactly=0) { store.summary(any()) }
        coVerify(exactly=0) { store.acknowledge(any(),any(),any()) };assertTrue(vm.rows.value.isEmpty())
    }
    @Test fun authorized_actor_reads_exact_total_and_late_venue_result_never_overwrites_new_session()=runTest {
        val held=CompletableDeferred<SalesNoticePage>()
        coEvery { store.page("venue-A",any(),any()) } coAnswers { held.await() }
        coEvery { store.page("venue-B",any(),any()) } returns SalesNoticePage(emptyList(),7,null)
        val vm=vm();vm.refresh();runCurrent();venue="venue-B";actor="manager-B";epoch++
        vm.refresh();runCurrent();assertEquals(7,vm.total.value)
        held.complete(SalesNoticePage(emptyList(),99,null));advanceUntilIdle();assertEquals(7,vm.total.value)
    }
    @Test fun failed_local_ack_keeps_notice_and_double_tap_is_coalesced()=runTest {
        SalesNoticeSqlTest.Fixture().use { f ->
            f.payment("cash");val row=f.dao.page("venue-A","",null,emptySet()).rows.single()
            coEvery { store.page(any(),any(),any()) } returns SalesNoticePage(listOf(row),2,null)
            val held=CompletableDeferred<Boolean>();coEvery { store.acknowledge(any(),any(),any()) } coAnswers { held.await() }
            val vm=vm();vm.refresh();advanceUntilIdle();vm.select(vm.rows.value.single())
            vm.acknowledgeSelected();vm.acknowledgeSelected();runCurrent()
            coVerify(exactly=1) { store.acknowledge(row,"manager-A",any()) }
            held.completeExceptionally(IllegalStateException("disk full"));advanceUntilIdle()
            assertEquals(2,vm.summary.value?.unseenCount);assertEquals(2,vm.total.value)
            assertNotNull(vm.message.value);assertFalse(vm.acknowledging.value)
        }
    }
    @Test fun revoked_permission_between_selection_and_action_never_writes()=runTest {
        SalesNoticeSqlTest.Fixture().use { f ->
            f.payment("cash");val row=f.dao.page("venue-A","",null,emptySet()).rows.single()
            coEvery { store.page(any(),any(),any()) } returns SalesNoticePage(listOf(row),1,null)
            val vm=vm();vm.refresh();advanceUntilIdle();vm.select(row);role="WAITER"
            vm.acknowledgeSelected();advanceUntilIdle();coVerify(exactly=0) { store.acknowledge(any(),any(),any()) }
        }
    }
    @Test fun failed_page_or_summary_after_understanding_falls_back_to_raw_and_keeps_loaded_data()=runTest {
        val failures=mutableListOf<Throwable>()
        for(phase in listOf("page","summary")) {
            SalesNoticeSqlTest.Fixture().use { f ->
                f.payment("one");f.payment("two")
                val original=f.dao.page("venue-A","",null,emptySet()).rows
                for(row in original) f.write { f.dao.acknowledge(row,"manager-A",1,emptySet()) }
                var fail=false
                val subject=vm()
                coEvery { store.page("venue-A",any(),any()) } coAnswers {
                    if(fail && phase=="page") throw IllegalStateException("read failed")
                    f.dao.page("venue-A",secondArg(),thirdArg(),emptySet())
                }
                coEvery { store.summary("venue-A") } coAnswers {
                    if(fail && phase=="summary") throw IllegalStateException("read failed")
                    f.dao.summary("venue-A",emptySet())
                }
                subject.refresh();advanceUntilIdle()
                val acknowledged=requireNotNull(subject.summary.value)
                val raw=acknowledged.alertContributions.values.sum()
                assertEquals(2,raw);assertEquals(2,acknowledged.pendingCount)
                assertEquals(0,acknowledged.unseenCount);assertEquals(2,acknowledged.acknowledgedContributions.values.sum())
                assertEquals(0,com.avoqado.pos.sync.presentation.salesNoticeBannerCount(acknowledged,0,raw))
                assertEquals(2,subject.globalPendingCount.value)
                subject.search("one");advanceUntilIdle()
                assertEquals(1,subject.total.value);assertEquals(2,subject.globalPendingCount.value)
                val rows=subject.rows.value
                f.sql("UPDATE pending_payments SET lastError='new cause' WHERE id='one'")
                val economic=f.economic();fail=true
                subject.refresh();advanceUntilIdle()
                // Collect failures so both read boundaries execute even while this test is RED.
                fun verify(block:()->Unit) { try { block() } catch(e:AssertionError) { failures.add(e) } }
                verify { assertNull("$phase must invalidate the stale summary",subject.summary.value) }
                verify { assertEquals("$phase must retain the raw notice",2,com.avoqado.pos.sync.presentation.salesNoticeBannerCount(subject.summary.value,0,raw)) }
                verify { assertEquals(rows,subject.rows.value) }
                verify { assertEquals(1,subject.total.value) }
                verify { assertEquals(2,subject.globalPendingCount.value) }
                verify { assertEquals("No se pudieron leer los pendientes. Sus datos siguen guardados.",subject.message.value) }
                verify { assertEquals(economic,f.economic()) }
            }
        }
        org.junit.runners.model.MultipleFailureException.assertEmpty(failures)
    }

    @Test fun cashier_notice_keeps_legacy_destination_and_label_without_granting_reader_access()=runTest {
        role="CASHIER";val subject=vm()
        assertTrue(RoleManager(storage).canAccessTransactions);assertFalse(subject.canRead)
        assertFalse(subject.opensPendingSales);assertEquals("Revisar",subject.bannerActionLabel)
        subject.refresh();advanceUntilIdle()
        coVerify(exactly=0) { store.page(any(),any(),any()) }
        coVerify(exactly=0) { store.summary(any()) }
    }
    @Test fun tablet_read_error_without_selection_is_visible_and_never_claims_an_empty_successful_read()=runTest {
        val failures=mutableListOf<Throwable>()
        for(preloaded in listOf(false,true)) {
            SalesNoticeSqlTest.Fixture().use { f ->
                f.payment("one");f.payment("two");val subject=vm();var fail=false
                coEvery { store.page(any(),any(),any()) } coAnswers {
                    if(fail) throw IllegalStateException("read failed")
                    f.dao.page("venue-A","",null,emptySet())
                }
                coEvery { store.summary(any()) } coAnswers { f.dao.summary("venue-A",emptySet()) }
                if(preloaded) {subject.refresh();advanceUntilIdle()}
                val rows=subject.rows.value;val total=subject.total.value
                fail=true;subject.refresh();advanceUntilIdle();assertNull(subject.selected.value)
                val tablet=subject.presentation(isTablet=true)
                fun verify(block:()->Unit) {try{block()}catch(e:AssertionError){failures.add(e)}}
                verify {assertEquals("No se pudieron leer los pendientes. Sus datos siguen guardados.",tablet.message)}
                verify {assertFalse(tablet.countLabel.contains("0 operación"))}
                verify {assertNull(tablet.emptyLabel)}
                verify {assertFalse(tablet.pendingLabel.contains("· 0"))}
                verify {assertNotNull(subject.presentation(isTablet=false).message)}
                verify {assertEquals(rows,subject.rows.value);assertEquals(total,subject.total.value)}
            }
        }
        org.junit.runners.model.MultipleFailureException.assertEmpty(failures)
    }
    @Test fun observer_failure_is_contained_and_explicit_refresh_resubscribes_for_same_count_new_cause()=runTest {
        SalesNoticeSqlTest.Fixture().use { f ->
            f.payment("one");f.payment("two")
            for(row in f.dao.page("venue-A","",null,emptySet()).rows) f.write {f.dao.acknowledge(row,"manager-A",1,emptySet())}
            val subject=vm();var reads=0
            coEvery { store.page(any(),any(),any()) } coAnswers {reads++;f.dao.page(requireNotNull(venue),"",null,emptySet())}
            coEvery { store.summary(any()) } coAnswers {f.dao.summary(requireNotNull(venue),emptySet())}
            val channels=mutableListOf<kotlinx.coroutines.channels.Channel<Int>>()
            every {store.changes()} answers {kotlinx.coroutines.flow.flow {
                val channel=kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                channels.add(channel)
                for(revision in channel) emit(revision)
            }}
            var escaped:Throwable?=null
            val observing=backgroundScope.launch {
                try {subject.observeChanges()}
                catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(e:Exception){escaped=e}
            }
            try {
                subject.refresh();runCurrent();assertEquals(0,subject.summary.value?.unseenCount);assertEquals(1,channels.size)
                channels.first().close(IllegalStateException("observer read failed"));runCurrent()
                val failures=mutableListOf<Throwable>()
                fun verify(block:()->Unit){try{block()}catch(e:AssertionError){failures.add(e)}}
                var reservationCount:Int?=2
                val reservationError=runCatching {
                    com.avoqado.pos.sync.presentation.collectSalesNoticeReservations(kotlinx.coroutines.flow.flow<Int> { throw IllegalStateException("reservation read failed") }) {reservationCount=it}
                }.exceptionOrNull()
                verify {assertNull("reservation Flow errors must be contained",reservationError)}
                verify {assertNull("failed reservation count must become unknown",reservationCount)}
                val allUnderstood=SalesNoticeSummary(2,0,mapOf("FAILED" to 2),mapOf("FAILED" to 2))
                verify {assertEquals(2,com.avoqado.pos.sync.presentation.salesNoticeBannerCount(allUnderstood,null,2))}
                com.avoqado.pos.sync.presentation.collectSalesNoticeReservations(kotlinx.coroutines.flow.flowOf(3)) {reservationCount=it}
                verify {assertEquals("explicitly reattached reservation Flow recovers",3,reservationCount)}
                verify {assertNull("observer errors must not escape to the composition",escaped)}
                verify {assertNull(subject.summary.value);assertNotNull(subject.message.value)}
                subject.refresh();runCurrent()
                verify {assertEquals("explicit refresh must start a new observation",2,channels.size)}
                verify {assertNull("a successful read clears the read error",subject.message.value)}
                f.sql("UPDATE pending_payments SET lastError='new cause' WHERE id='one'")
                channels.last().trySend(1);runCurrent()
                verify {assertEquals(2,subject.globalPendingCount.value);assertEquals(1,subject.summary.value?.unseenCount)}
                val before=reads;role="WAITER";actor="waiter";epoch++;subject.refresh();runCurrent()
                channels.last().trySend(2);runCurrent()
                verify {assertEquals(before,reads);assertEquals(0,subject.globalPendingCount.value);assertTrue(subject.rows.value.isEmpty())}
                org.junit.runners.model.MultipleFailureException.assertEmpty(failures)
            } finally {observing.cancel();subject.viewModelScope.cancel()}
        }
    }

    @Test fun understood_device_notices_survive_cashier_switch_and_reopening_without_granting_row_access()=runTest {
        SalesNoticeSqlTest.Fixture().use { f ->
            f.payment("one");f.payment("two");val economic=f.economic()
            val subject=vm()
            coEvery {store.page(any(),any(),any())} coAnswers {f.dao.page(requireNotNull(venue),"",null,emptySet())}
            coEvery {store.summary(any())} coAnswers {f.dao.summary(requireNotNull(venue),emptySet())}
            coEvery {store.acknowledge(any(),any(),any())} coAnswers {
                f.write {f.dao.acknowledge(firstArg(),secondArg(),thirdArg(),emptySet())}
            }
            subject.refresh();advanceUntilIdle();val managerRows=subject.rows.value
            for(row in managerRows) {subject.select(row);subject.acknowledgeSelected();advanceUntilIdle()}
            assertEquals(0,subject.summary.value?.unseenCount);assertEquals(economic,f.economic())
            clearMocks(store,answers=false);role="CASHIER";actor="cashier";epoch++
            subject.refreshAlertSummary();advanceUntilIdle()
            val failures=mutableListOf<Throwable>()
            fun verify(block:()->Unit){try{block()}catch(e:AssertionError){failures.add(e)}}
            verify {assertEquals(0,subject.summary.value?.unseenCount)}
            verify {assertEquals(0,com.avoqado.pos.sync.presentation.salesNoticeBannerCount(subject.summary.value,0,2))}
            verify {assertFalse(subject.canRead);assertTrue(subject.rows.value.isEmpty())}
            subject.select(managerRows.first());subject.acknowledgeSelected();advanceUntilIdle()
            coVerify(exactly=0) {store.page(any(),any(),any())};coVerify(exactly=0) {store.acknowledge(any(),any(),any())}
            val reopened=vm()
            coEvery {store.summary(any())} coAnswers {f.dao.summary(requireNotNull(venue),emptySet())}
            reopened.refreshAlertSummary();advanceUntilIdle()
            verify {assertEquals(0,reopened.summary.value?.unseenCount);assertTrue(reopened.rows.value.isEmpty());assertFalse(reopened.canRead)}
            f.sql("UPDATE pending_payments SET lastError='new cause' WHERE id='one'")
            val changed=f.economic();reopened.refreshAlertSummary();advanceUntilIdle()
            verify {assertEquals(1,reopened.summary.value?.unseenCount)}
            verify {assertEquals(1,com.avoqado.pos.sync.presentation.salesNoticeBannerCount(reopened.summary.value,0,2))}
            verify {assertEquals(changed,f.economic())}
            coVerify(exactly=0) {store.page(any(),any(),any())};coVerify(exactly=0) {store.acknowledge(any(),any(),any())}
            org.junit.runners.model.MultipleFailureException.assertEmpty(failures)
        }
    }

    @Test fun late_real_session_epoch_then_real_ack_invalidation_updates_global_notice_without_resume()=runTest {
        SalesNoticeSqlTest.Fixture().use { f ->
            f.payment("one");f.payment("two");val economic=f.economic()
            every {storage.venueId} answers {venue};every {storage.userId} answers {actor};every {storage.userRole} answers {role}
            val realSession=TableSession()
            val revisions=kotlinx.coroutines.flow.MutableStateFlow(0)
            var holdNext=false
            val heldStarted=CompletableDeferred<Unit>();val held=CompletableDeferred<Unit>()
            val liveDao=object:SalesNoticeDao() {
                override suspend fun read(query:androidx.room.RoomRawQuery)=f.dao.read(query)
                override suspend fun count(query:androidx.room.RoomRawQuery)=f.dao.count(query)
                override suspend fun aggregate(query:androidx.room.RoomRawQuery):SalesNoticeAggregate {
                    if(holdNext) {holdNext=false;heldStarted.complete(Unit);held.await()}
                    return f.dao.aggregate(query)
                }
                override suspend fun save(row:SalesNoticeAcknowledgementEntity) {f.dao.save(row);revisions.value++}
                override fun changes():kotlinx.coroutines.flow.Flow<Int> = revisions
            }
            val cash=mockk<com.avoqado.pos.payment.data.CashPaymentRepository>(relaxed=true)
            every {cash.salesNoticeReservedIds()} returns emptySet()
            val liveStore=SalesNoticeStore(liveDao,cash)
            val global=SalesNoticesViewModel(liveStore,storage,RoleManager(storage),realSession,payments,offlineNetwork())
            var reader:SalesNoticesViewModel?=null
            val contextJob=backgroundScope.launch {global.observeContextChanges()}
            val failures=mutableListOf<Throwable>()
            fun verify(block:()->Unit){try{block()}catch(e:AssertionError){failures.add(e)}}
            try {
                global.observeAlertChanges();global.refreshAlertSummary();runCurrent()
                assertEquals(0L,realSession.contextoActual());assertEquals(2,global.summary.value?.unseenCount)
                // Same production method invoked by TableSyncCoordinator.start after its startup await.
                realSession.sincronizarContexto(actor,venue);runCurrent()
                assertEquals(1L,realSession.contextoActual())
                verify {assertEquals("Ver ventas",global.bannerActionLabel);assertTrue(global.opensPendingSales)}
                val localReader=SalesNoticesViewModel(liveStore,storage,RoleManager(storage),realSession,payments,offlineNetwork());reader=localReader
                localReader.refresh();runCurrent();val selected=localReader.rows.value.first {it.id=="one"}
                localReader.select(selected);localReader.acknowledgeSelected();runCurrent()
                assertEquals(1,revisions.value);assertEquals(1,f.dao.summary("venue-A",emptySet()).unseenCount)
                verify {assertEquals(1,global.summary.value?.unseenCount)}
                verify {assertEquals(1,com.avoqado.pos.sync.presentation.salesNoticeBannerCount(global.summary.value,0,2))}
                verify {assertEquals(2,global.globalPendingCount.value);assertEquals("Ver ventas",global.bannerActionLabel)}
                assertEquals(economic,f.economic())
                // Hold a real A query, then change identity/venue and let its old result return last.
                holdNext=true;global.refreshAlertSummary();runCurrent();assertTrue(heldStarted.isCompleted)
                venue="venue-B";actor="cashier-B";role="CASHIER"
                realSession.sincronizarContexto(actor,venue);runCurrent()
                held.complete(Unit);runCurrent()
                verify {assertEquals(0,global.summary.value?.pendingCount)}
                verify {assertFalse(global.canRead);assertFalse(global.opensPendingSales);assertEquals("Revisar",global.bannerActionLabel)}
                verify {assertTrue(global.rows.value.isEmpty());assertEquals(0,global.globalPendingCount.value)}
                org.junit.runners.model.MultipleFailureException.assertEmpty(failures)
            } finally {
                held.complete(Unit);contextJob.cancel();reader?.viewModelScope?.cancel();global.viewModelScope.cancel();runCurrent()
            }
        }
    }

}
