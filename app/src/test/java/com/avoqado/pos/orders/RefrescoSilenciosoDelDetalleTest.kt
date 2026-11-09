package com.avoqado.pos.orders

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.data.model.OrderDetail
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RefrescoSilenciosoDelDetalleTest {
    private val visto = OrderDetail("A")
    private fun repo() = spyk(OrdersRepository(mockk<SecureStorage>(relaxed = true), OkHttpClient()))

    @Test fun `actualiza el pedido sin poner el circulo de carga`() = runTest {
        val r = repo()
        coEvery { r.fetchOrderDetail("A") } returns visto
        r.loadOrderDetail("A")
        val nuevo = OrderDetail("A", status = "COMPLETED")
        val cargandoAlPedir = mutableListOf<Boolean>()
        coEvery { r.fetchOrderDetail("A") } coAnswers { cargandoAlPedir += r.isLoadingDetail.value; nuevo }
        r.loadOrderDetail("A", silencioso = true)
        assertEquals(listOf(false), cargandoAlPedir)
        assertEquals(nuevo, r.selectedOrder.value)
    }

    @Test fun `si la actualizacion falla se queda lo que se veia y sin error`() = runTest {
        val r = repo()
        coEvery { r.fetchOrderDetail("A") } returns visto
        r.loadOrderDetail("A")
        coEvery { r.fetchOrderDetail("A") } returns null
        r.loadOrderDetail("A", silencioso = true)
        coEvery { r.fetchOrderDetail("A") } throws IllegalStateException("se cayó")
        r.loadOrderDetail("A", silencioso = true)
        assertEquals(visto, r.selectedOrder.value)
        assertNull(r.errorMessage.value)
        assertFalse(r.isLoadingDetail.value)
    }

    @Test fun `seleccionar sigue poniendo el error si no hay pedido`() = runTest {
        val r = repo()
        coEvery { r.fetchOrderDetail("A") } returns null
        r.loadOrderDetail("A")
        assertEquals("No se encontró la orden", r.errorMessage.value)
    }
}
