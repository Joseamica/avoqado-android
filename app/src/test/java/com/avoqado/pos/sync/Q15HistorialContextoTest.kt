package com.avoqado.pos.sync

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.RevisionDelCobroRecord
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.sync.presentation.HistorialDelCobroViewModel
import com.avoqado.pos.tables.data.TableSession
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class Q15HistorialContextoTest {
    @get:Rule val main=MainDispatcherRule()
    private val dao=mockk<PendingPaymentDao>(relaxed=true)
    private val storage=mockk<SecureStorage>(relaxed=true)
    private val session=mockk<TableSession>(relaxed=true)
    private var venue:String?="FULLTEST-A"
    private var staff:String?="gerente-A"
    private var role="MANAGER"
    private var epoch=1L
    private fun row(id:String,tenant:String="FULLTEST-A")=RevisionDelCobroRecord(
        "PAYMENT",id,tenant,"SYNCED","capture",null,null,"DIFERENCIA_DE_COBRO",10,null,null,null,"o1",
    )
    private fun vm():HistorialDelCobroViewModel {
        every { storage.venueId } answers { venue }
        every { storage.userId } answers { staff }
        every { storage.userRole } answers { role }
        every { session.contextoActual() } answers { epoch }
        return HistorialDelCobroViewModel(dao,storage,RoleManager(storage),session,mockk<com.avoqado.pos.pos.data.StaffRepository>(relaxed=true).also { coEvery { it.getActiveStaff(any()) } returns Result.success(emptyList()) })
    }
    private fun abrirB() { venue="FULLTEST-B";staff="gerente-B";epoch=2 }
    private fun responder(tenant:String,id:String) {
        coEvery { dao.contarRevisionesQ15(tenant,false,"") } returns 1
        coEvery { dao.paginaRevisionesQ15(tenant,false,"",null,null,null) } returns listOf(row(id,tenant))
    }

    @Test fun misma_instancia_reabre_B_y_revisa_solo_como_gerente_B()=runTest {
        responder("FULLTEST-A","p-A");responder("FULLTEST-B","p-B")
        coEvery { dao.revisarPagoQ15("FULLTEST-B","p-B","gerente-B","MANAGER",any()) } returns 1
        val subject=vm();subject.cargar();advanceUntilIdle()
        assertEquals(listOf("p-A"),subject.filas.value.map {it.id})
        abrirB();subject.cargar();advanceUntilIdle()
        assertTrue(subject.canRead);assertEquals(1,subject.total.value)
        assertEquals(listOf("p-B"),subject.filas.value.map {it.id})
        subject.marcarRevisado(row("p-A"));subject.marcarRevisado(row("p-B","FULLTEST-B"));advanceUntilIdle()
        coVerify(exactly=1) { dao.revisarPagoQ15("FULLTEST-B","p-B","gerente-B","MANAGER",any()) }
        coVerify(exactly=0) { dao.revisarPagoQ15("FULLTEST-A",any(),any(),any(),any()) }
    }

    @Test fun pagina_de_A_retenida_no_borra_ni_publica_sobre_B()=runTest {
        val held=CompletableDeferred<List<RevisionDelCobroRecord>>()
        coEvery { dao.contarRevisionesQ15("FULLTEST-A",false,"") } returns 1
        coEvery { dao.paginaRevisionesQ15("FULLTEST-A",false,"",null,null,null) } coAnswers { held.await() }
        responder("FULLTEST-B","p-B")
        val subject=vm();subject.cargar();runCurrent();assertTrue(subject.cargando.value)
        abrirB();subject.cargar();runCurrent()
        assertEquals(listOf("p-B"),subject.filas.value.map {it.id})
        held.complete(listOf(row("p-A")));advanceUntilIdle()
        assertEquals(listOf("p-B"),subject.filas.value.map {it.id});assertEquals(1,subject.total.value)
        assertFalse(subject.cargando.value);assertNull(subject.mensaje.value)
    }

    @Test fun logout_y_relogin_misma_identidad_descartan_count_antiguo_antes_de_leer_pagina()=runTest {
        val held=CompletableDeferred<Int>()
        coEvery { dao.contarRevisionesQ15("FULLTEST-A",false,"") } coAnswers { held.await() }
        val subject=vm();subject.cargar();runCurrent()
        venue=null;staff=null;epoch=2;subject.cargar()
        assertFalse(subject.canRead);assertTrue(subject.filas.value.isEmpty())
        venue="FULLTEST-A";staff="gerente-A";epoch=3
        responder("FULLTEST-A","p-nueva");subject.cargar();runCurrent()
        held.complete(99);advanceUntilIdle()
        assertTrue(subject.canRead);assertEquals(listOf("p-nueva"),subject.filas.value.map {it.id})
        assertEquals(1,subject.total.value)
        coVerify(exactly=1) { dao.paginaRevisionesQ15("FULLTEST-A",false,"",null,null,null) }
    }

    @Test fun waiter_revocado_al_reabrir_no_lee_y_no_conserva_filas_de_gerente()=runTest {
        responder("FULLTEST-A","p-A");val subject=vm();subject.cargar();advanceUntilIdle()
        clearMocks(dao,answers=false);role="WAITER";staff="mesero";epoch=2
        subject.cargar();subject.marcarRevisado(row("p-A"));advanceUntilIdle()
        assertFalse(subject.canRead);assertTrue(subject.filas.value.isEmpty());assertEquals(0,subject.total.value)
        coVerify(exactly=0) { dao.contarRevisionesQ15(any(),any(),any()) }
        coVerify(exactly=0) { dao.paginaRevisionesQ15(any(),any(),any(),any(),any(),any()) }
        coVerify(exactly=0) { dao.revisarPagoQ15(any(),any(),any(),any(),any()) }
    }

    @Test fun permiso_revocado_durante_count_impide_la_lectura_siguiente()=runTest {
        val held=CompletableDeferred<Int>()
        coEvery { dao.contarRevisionesQ15(any(),any(),any()) } coAnswers { held.await() }
        val subject=vm();subject.cargar();runCurrent();role="WAITER"
        held.complete(1);advanceUntilIdle()
        assertTrue(subject.filas.value.isEmpty());assertEquals(0,subject.total.value);assertFalse(subject.cargando.value)
        coVerify(exactly=0) { dao.paginaRevisionesQ15(any(),any(),any(),any(),any(),any()) }
    }

    @Test fun respuesta_de_revision_A_no_recarga_ni_limpia_B()=runTest {
        responder("FULLTEST-A","p-A");responder("FULLTEST-B","p-B")
        val held=CompletableDeferred<Int>()
        coEvery { dao.revisarPagoQ15("FULLTEST-A","p-A","gerente-A","MANAGER",any()) } coAnswers { held.await() }
        val subject=vm();subject.cargar();advanceUntilIdle();subject.marcarRevisado(row("p-A"));runCurrent()
        abrirB();subject.cargar();runCurrent()
        assertEquals(listOf("p-B"),subject.filas.value.map {it.id})
        held.complete(1);advanceUntilIdle()
        assertEquals(listOf("p-B"),subject.filas.value.map {it.id});assertNull(subject.mensaje.value)
        coVerify(exactly=1) { dao.contarRevisionesQ15("FULLTEST-B",false,"") }
        coVerify(exactly=1) { dao.revisarPagoQ15("FULLTEST-A","p-A","gerente-A","MANAGER",any()) }
        coVerify(exactly=0) { dao.revisarPagoQ15("FULLTEST-B",any(),any(),any(),any()) }
    }
}
