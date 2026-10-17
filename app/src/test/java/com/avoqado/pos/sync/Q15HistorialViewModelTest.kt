package com.avoqado.pos.sync
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.sync.presentation.HistorialDelCobroViewModel
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class Q15HistorialViewModelTest {
    @get:Rule val main=MainDispatcherRule()
    private val dao=mockk<PendingPaymentDao>(relaxed=true)
    private val storage=mockk<SecureStorage>(relaxed=true)
    private val session=mockk<TableSession>(relaxed=true)
    private var venue="FULLTEST-A";private var role="MANAGER";private var epoch=1L
    private fun row(id:String,reason:String="DIFERENCIA_DE_COBRO",state:String="SYNCED")=RevisionDelCobroRecord("PAYMENT",id,"FULLTEST-A",state,"capture",null,null,reason,10,null,null,null,"o1")
    private fun vm():HistorialDelCobroViewModel {
        every { storage.venueId } answers { venue };every { storage.userId } returns "staff";every { storage.userRole } answers { role }
        every { session.contextoActual() } answers { epoch }
        return HistorialDelCobroViewModel(dao,storage,RoleManager(storage),session)
    }
    @Test fun pagina_y_cursor_conservan_total_y_no_cargan_todo()=runTest {
        coEvery { dao.contarRevisionesQ15("FULLTEST-A",false,"") } returns 121
        coEvery { dao.paginaRevisionesQ15("FULLTEST-A",false,"",null,null,null) } returns (0..49).map { row("p$it") }
        coEvery { dao.paginaRevisionesQ15("FULLTEST-A",false,"",10,"PAYMENT","p49") } returns (50..99).map { row("p$it") }
        val subject=vm();subject.cargar();advanceUntilIdle()
        assertEquals(121,subject.total.value);assertEquals(50,subject.filas.value.size)
        subject.cargarMas();advanceUntilIdle();assertEquals(100,subject.filas.value.size);assertEquals(121,subject.total.value)
        coVerify(exactly=1) { dao.paginaRevisionesQ15("FULLTEST-A",false,"",10,"PAYMENT","p49") }
    }
    @Test fun cajero_no_lee_historia_ni_marca_revisado()=runTest {
        role="CASHIER";val subject=vm();subject.cargar();subject.marcarRevisado(row("p1"));advanceUntilIdle()
        assertTrue(subject.filas.value.isEmpty())
        coVerify(exactly=0) { dao.paginaRevisionesQ15(any(),any(),any(),any(),any(),any()) }
        coVerify(exactly=0) { dao.revisarPagoQ15(any(),any(),any(),any(),any()) }
    }
    @Test fun gerente_revisa_aplicado_sin_delete_retry_ni_price_release()=runTest {
        val r=row("p1")
        coEvery { dao.contarRevisionesQ15(any(),any(),any()) } returns 1
        coEvery { dao.paginaRevisionesQ15(any(),any(),any(),any(),any(),any()) } returns listOf(r)
        coEvery { dao.revisarPagoQ15("FULLTEST-A","p1","staff","MANAGER",any()) } returns 1
        val subject=vm();subject.cargar();advanceUntilIdle();subject.marcarRevisado(r);advanceUntilIdle()
        coVerify(exactly=1) { dao.revisarPagoQ15("FULLTEST-A","p1","staff","MANAGER",any()) }
        subject.marcarRevisado(row("p2","PRICE_PROOF_PENDING","PENDING"));advanceUntilIdle()
        coVerify(exactly=0) { dao.revisarPagoQ15(any(),"p2",any(),any(),any()) }
        coVerify(exactly=0) { dao.deleteFailed(any()) };coVerify(exactly=0) { dao.retryFailed(any()) }
    }
    @Test fun respuesta_vieja_tras_cambio_sesion_no_publica_filas_del_otro_venue()=runTest {
        val deferred=CompletableDeferred<List<RevisionDelCobroRecord>>()
        coEvery { dao.contarRevisionesQ15(any(),any(),any()) } returns 1
        coEvery { dao.paginaRevisionesQ15(any(),any(),any(),any(),any(),any()) } coAnswers { deferred.await() }
        val subject=vm();subject.cargar();runCurrent();venue="FULLTEST-B";epoch=2
        deferred.complete(listOf(row("p1")));advanceUntilIdle()
        assertTrue(subject.filas.value.isEmpty());assertEquals(0,subject.total.value)
        subject.marcarRevisado(row("p1"));advanceUntilIdle()
        coVerify(exactly=0) { dao.revisarPagoQ15(any(),any(),any(),any(),any()) }
    }
    @Test fun filtro_nuevo_descarta_lectura_anterior_y_reinicia_cursor()=runTest {
        val first=CompletableDeferred<List<RevisionDelCobroRecord>>()
        coEvery { dao.contarRevisionesQ15(any(),any(),any()) } returns 1
        coEvery { dao.paginaRevisionesQ15("FULLTEST-A",false,"",null,null,null) } coAnswers { first.await() }
        coEvery { dao.paginaRevisionesQ15("FULLTEST-A",true,"folio",null,null,null) } returns listOf(row("actual"))
        val subject=vm();subject.cargar();runCurrent();subject.cambiarFiltro(true,"folio");runCurrent()
        first.complete(listOf(row("vieja")));advanceUntilIdle()
        assertEquals(listOf("actual"),subject.filas.value.map { it.id })
    }
    @Test fun detalle_usa_evidencia_original_y_resultado_no_saldo_o_change() {
        val capture=com.avoqado.pos.payment.data.ConciliacionDelCobro.guardarCaptura(com.avoqado.pos.payment.data.CapturaDelCobro("FULLTEST-A","p1","o1",10005,500,changeCents=12345))
        val r=row("p1").copy(q15CaptureJson=capture,q15InitialResultJson="""{"paymentId":"sp1","amountCents":9505,"tipCents":1000}""",q15ResultJson="""{"paymentId":"sp1","amountCents":9505,"tipCents":1000}""")
        val detail=vm().detalle(r)
        assertTrue(detail.any {it.contains("100.05") && it.contains("5.00")})
        assertTrue(detail.any {it.contains("95.05") && it.contains("10.00")})
        assertFalse(detail.any {it.contains("123.45")})
    }
    // Q16/C2: el gerente VE cuánto se cobró y cuánto dice el servidor (mismo texto en iOS).
    @Test fun detalle_muestra_total_al_cobrar_y_total_en_el_servidor_si_el_precio_cambio() {
        val capture=com.avoqado.pos.payment.data.ConciliacionDelCobro.guardarCaptura(com.avoqado.pos.payment.data.CapturaDelCobro("FULLTEST-A","p1",null,10000,500))
        val resultado="""{"paymentId":"sp1","orderId":"o1","amountCents":10000,"tipCents":500,"camposDiferentes":["totalDeLaOrden"],"camposNoComprobados":[],"totalAlCobrarCents":10000,"totalEnElServidorCents":12000}"""
        val detail=vm().detalle(row("p1").copy(q15CaptureJson=capture,q15InitialResultJson=resultado,q15ResultJson=resultado))
        assertTrue(detail.toString(),detail.contains("Total al cobrar $100.00 · total en el servidor $120.00"))
        assertTrue(detail.toString(),detail.contains("Campos distintos: total de la orden"))
        val igual=vm().detalle(row("p1").copy(q15CaptureJson=capture,q15ResultJson="""{"paymentId":"sp1","amountCents":10000,"tipCents":500}"""))
        assertFalse(igual.any { it.startsWith("Total al cobrar") })
    }
    @Test fun detalle_legacy_o_campo_ausente_nunca_muestra_cero_como_prueba() {
        val r=row("p1").copy(q15CaptureJson=null,q15ResultJson="""{"paymentId":"sp1"}""")
        val detail=vm().detalle(r)
        assertTrue(detail.any {it.contains("Captura original: sin evidencia")})
        assertTrue(detail.any {it.contains("Sin confirmar")})
        assertFalse(detail.any {it.contains("$0.00")})
    }
}
