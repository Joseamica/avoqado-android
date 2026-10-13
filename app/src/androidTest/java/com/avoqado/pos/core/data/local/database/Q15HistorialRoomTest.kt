package com.avoqado.pos.core.data.local.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Q15HistorialRoomTest {
    private lateinit var db: AvoqadoDatabase
    private val payment get() = db.pendingPaymentDao()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    private fun original() = PendingPaymentEntity("p1","FULLTEST-A","staff",10005,500,"CASH","ORDER",orderId="o1",q15CaptureJson="original",capturedOrderRef="o1")
    @Test fun reserva_repetida_conserva_captura_resultado_y_pago_aplicado() = runBlocking {
        payment.insert(original())
        payment.registrarResultadoQ15("FULLTEST-A","p1",true,"DIFERENCIA_DE_COBRO","first",10)
        payment.insert(original().copy(q15CaptureJson=null, syncStatus="EN_VUELO"))
        val row = requireNotNull(payment.findForVenue("FULLTEST-A","p1"))
        assertEquals("SYNCED",row.syncStatus); assertEquals("original",row.q15CaptureJson)
        assertEquals("first",row.q15InitialResultJson)
    }
    @Test fun review_rol_tenant_e_historial_real_room() = runBlocking {
        payment.insert(original()); payment.registrarResultadoQ15("FULLTEST-A","p1",true,"DIFERENCIA_DE_COBRO","first",10)
        assertEquals(0,payment.revisarPagoQ15("FULLTEST-A","p1","cashier","CASHIER",20))
        assertEquals(0,payment.revisarPagoQ15("FULLTEST-B","p1","manager","MANAGER",20))
        assertEquals(1,payment.revisarPagoQ15("FULLTEST-A","p1","manager","MANAGER",20))
        assertEquals(1,payment.contarRevisionesQ15("FULLTEST-A",true,""))
        assertEquals("original",payment.paginaRevisionesQ15("FULLTEST-A",true,"",null,null,null).single().q15CaptureJson)
    }
    @Test fun hold_y_ttl_no_descartan_ni_reintentan_cobro_desconocido() = runBlocking {
        payment.insert(original().copy(q15CaptureJson=null, syncStatus="FAILED"))
        payment.retenerPagoQ15("FULLTEST-A","p1","CAPTURA_SIN_EVIDENCIA",10)
        payment.retryFailed("p1"); assertEquals("FAILED",payment.syncStatusOf("p1"))
        assertEquals(0,payment.deleteFailed("p1")); payment.deleteSynced(Long.MAX_VALUE)
        assertNotNull(payment.findForVenue("FULLTEST-A","p1"))
        assertEquals(0,payment.revisarPagoQ15("FULLTEST-A","p1","manager","MANAGER",20))
    }
    @Test fun referencia_original_no_cruza_venue() = runBlocking {
        payment.insert(original())
        assertTrue(payment.hayCapturaDeOrdenQ15("FULLTEST-A","o1"))
        assertFalse(payment.hayCapturaDeOrdenQ15("FULLTEST-B","o1"))
    }
}
