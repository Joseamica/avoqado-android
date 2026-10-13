package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.lan.LanHubService
import com.avoqado.pos.core.data.lan.LeaseOutcome
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.tables.data.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TableAperturaIdentidadTest {
    @get:Rule val main=MainDispatcherRule()
    private val repository=mockk<TableServiceRepository>(relaxed=true)
    private val hub=mockk<LanHubService>(relaxed=true)
    private val storage=mockk<SecureStorage>(relaxed=true)
    private val session=TableSession()
    private val outbox=mockk<com.avoqado.pos.core.data.sync.SyncOutbox>(relaxed=true)
    private var venue="FULLTEST-open-A"
    private val target=DiningTable("t-B","B")
    private fun active(id:String="A")=TableSession.Active("t-$id",id,null,"o-$id",id,1,8000,TableSession.Mode.PAYING)
    private fun vm():TablesViewModel {
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        coEvery { repository.refresh(any()) } returns Result.success(emptyList())
        every { storage.venueId } answers { venue }
        session.sincronizarContexto("actor",venue)
        coEvery { hub.acquire(any(),any(),any()) } returns LeaseOutcome.NoHub
        return TablesViewModel(repository=repository,planManager=mockk(relaxed=true),tableSession=session,
            printerService=mockk(relaxed=true),secureStorage=storage,syncOutbox=outbox,lanHub=hub)
    }
    @Test fun apertura_B_pendiente_no_sustituye_A_que_ya_esta_cobrando()=runTest {
        val held=CompletableDeferred<Result<OpenedOrder>>()
        coEvery { repository.openTable(any(),any(),any()) } coAnswers {held.await()}
        val subject=vm();var navigation=0
        try {
            subject.openTableAndStartOrdering(target,2){navigation++};runCurrent()
            val a=active();session.start(a,venue)
            held.complete(Result.success(OpenedOrder("o-B","B")));advanceUntilIdle()
            assertEquals(a,session.current());assertEquals(0,navigation)
            assertEquals(TableActionState.Idle,subject.actionState.value)
        } finally {subject.viewModelScope.cancel()}
    }
    @Test fun logout_y_venue_nuevo_rechazan_respuesta_de_apertura_vieja()=runTest {
        val held=CompletableDeferred<Result<OpenedOrder>>()
        coEvery { repository.openTable(any(),any(),any()) } coAnswers {held.await()}
        val subject=vm();var navigation=0
        try {
            subject.openTableAndStartOrdering(target,2){navigation++};runCurrent()
            venue="FULLTEST-open-C";session.clear();session.sincronizarContexto("otro",venue)
            val c=active("C");session.start(c,venue)
            held.complete(Result.success(OpenedOrder("o-B","B")));advanceUntilIdle()
            assertEquals(c,session.current());assertEquals(0,navigation)
        } finally {subject.viewModelScope.cancel()}
    }
    @Test fun lease_pendiente_no_abre_B_despues_de_activar_A()=runTest {
        val lease=CompletableDeferred<LeaseOutcome>()
        val subject=vm();coEvery {hub.acquire(any(),any(),any())} coAnswers {lease.await()}
        var navigation=0
        try {
            subject.openTableAndStartOrdering(target,2){navigation++};runCurrent()
            val a=active();session.start(a,venue);lease.complete(LeaseOutcome.NoHub);advanceUntilIdle()
            assertEquals(a,session.current());assertEquals(0,navigation)
            coVerify(exactly=0){repository.openTable(any(),any(),any())}
        } finally {subject.viewModelScope.cancel()}
    }
    @Test fun refresco_de_apertura_no_navega_sobre_otra_cuenta()=runTest {
        val held=CompletableDeferred<Result<List<DiningTable>>>()
        coEvery {repository.openTable(any(),any(),any())} returns Result.success(OpenedOrder("o-B","B"))
        val subject=vm();var navigation=0
        coEvery {repository.refresh(any())} coAnswers {held.await()}
        try {
            subject.openTableAndStartOrdering(target,2){navigation++};runCurrent()
            assertEquals("o-B",session.current()?.orderId)
            val a=active();session.start(a,venue);held.complete(Result.success(emptyList()));advanceUntilIdle()
            assertEquals(a,session.current());assertEquals(0,navigation)
        } finally {subject.viewModelScope.cancel()}
    }
    @Test fun apertura_vigente_conserva_sesion_y_navegacion_una_vez()=runTest {
        coEvery {repository.openTable(any(),any(),any())} returns Result.success(OpenedOrder("o-B","B"))
        val subject=vm();var navigation=0
        try {
            subject.openTableAndStartOrdering(target,2){navigation++};advanceUntilIdle()
            assertEquals("o-B",session.current()?.orderId);assertEquals(TableSession.Mode.ORDERING,session.current()?.mode)
            assertEquals(1,navigation);assertEquals(TableActionState.Idle,subject.actionState.value)
        } finally {subject.viewModelScope.cancel()}
    }
    @Test fun apertura_offline_encolada_no_sustituye_sesion_activada_durante_escritura()=runTest {
        val held=CompletableDeferred<String>()
        coEvery {repository.openTable(any(),any(),any())} returns Result.failure(java.io.IOException("red caída"))
        every {outbox.nextLocalFolio(any())} returns "L-1"
        coEvery {outbox.enqueue(any(),any(),any(),any(),any(),any(),any(),any())} coAnswers {held.await()}
        val subject=vm();var navigation=0
        try {
            subject.openTableAndStartOrdering(target,2){navigation++};runCurrent()
            val a=active();session.start(a,venue)
            held.complete("intent-open-B");advanceUntilIdle()
            assertEquals(a,session.current());assertEquals(0,navigation)
            coVerify(exactly=0){repository.markTableOccupiedLocally(any(),any(),any())}
        } finally {subject.viewModelScope.cancel()}
    }

    @Test fun cambio_antes_de_launch_no_pide_lease_del_contexto_nuevo()=runTest {
        kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val subject=vm();var navigation=0
        try {
            subject.openTableAndStartOrdering(target,2){navigation++}
            venue="FULLTEST-context-C";session.clear();session.sincronizarContexto("otro",venue)
            val c=active("C");session.start(c,venue);advanceUntilIdle()
            coVerify(exactly=0){hub.acquire(any(),any(),any())}
            coVerify(exactly=0){repository.openTable(any(),any(),any())}
            coVerify(exactly=0){outbox.enqueue(any(),any(),any(),any(),any(),any(),any(),any())}
            assertEquals(c,session.current());assertEquals(0,navigation)
        } finally {subject.viewModelScope.cancel()}
    }
    @Test fun fallo_real_de_saveDurable_del_piso_muestra_error_y_conserva_intent_y_Active_propia()=runTest {
        vm().viewModelScope.cancel() // configure only this fixture's storage and context
        val api=mockk<com.avoqado.pos.core.data.network.ApiService>(relaxed=true)
        val dao=mockk<com.avoqado.pos.core.data.local.database.CachedPayloadDao>(relaxed=true)
        coEvery {dao.get(any())} returns null
        coEvery {api.getTables(venue)} returns TablesResponse(data=listOf(target))
        coEvery {api.openTable(venue,target.id,any())} throws java.io.IOException("sin red")
        val real=TableServiceRepository(api,com.avoqado.pos.core.data.local.PayloadCache(dao),outbox,session,mockk(relaxed=true),mockk(relaxed=true))
        assertTrue(real.refresh(venue).isSuccess)
        coEvery {dao.upsert(any())} throws java.io.IOException("disco lleno")
        every {outbox.nextLocalFolio(venue)} returns "L-disk"
        coEvery {outbox.enqueue(any(),any(),any(),any(),any(),any(),any(),any())} returns "intent-preservado"
        val subject=TablesViewModel(repository=real,planManager=mockk(relaxed=true),tableSession=session,
            printerService=mockk(relaxed=true),secureStorage=storage,syncOutbox=outbox,lanHub=hub)
        var navigation=0
        try {
            subject.openTableAndStartOrdering(target,2){navigation++};advanceUntilIdle()
            assertEquals("t-B",session.current()?.tableId);assertTrue(session.current()?.isProvisional == true)
            assertEquals(0,navigation)
            assertEquals(TableActionState.Error("No se pudo guardar la mesa en este aparato. Libera espacio e inténtalo de nuevo."),subject.actionState.value)
            coVerify(exactly=1){outbox.enqueue(venue,com.avoqado.pos.core.data.sync.SyncIntentTypes.OPEN_TABLE,any(),any(),any(),any(),any(),any())}
            coVerify(exactly=0){outbox.descartar(any(),any())}
        } finally {subject.viewModelScope.cancel()}
    }

}
