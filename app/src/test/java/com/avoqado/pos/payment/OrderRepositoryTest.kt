package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.OrderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderRepositoryTest {

    // MARK: - isQueueableError tests

    @Test
    fun `UnknownHostException is queueable`() {
        assertTrue(OrderRepository.isQueueableError(java.net.UnknownHostException()))
    }

    @Test
    fun `ConnectException is queueable`() {
        assertTrue(OrderRepository.isQueueableError(java.net.ConnectException()))
    }

    @Test
    fun `SocketTimeoutException is queueable`() {
        assertTrue(OrderRepository.isQueueableError(java.net.SocketTimeoutException()))
    }

    @Test
    fun `IOException is queueable`() {
        assertTrue(OrderRepository.isQueueableError(java.io.IOException()))
    }

    @Test
    fun `IllegalArgumentException is not queueable`() {
        assertFalse(OrderRepository.isQueueableError(IllegalArgumentException()))
    }

    @Test
    fun `RuntimeException is not queueable`() {
        assertFalse(OrderRepository.isQueueableError(RuntimeException()))
    }

    // MARK: - isQueueableHttpCode tests

    @Test
    fun `500 is queueable`() {
        assertTrue(OrderRepository.isQueueableHttpCode(500))
    }

    @Test
    fun `503 is queueable`() {
        assertTrue(OrderRepository.isQueueableHttpCode(503))
    }

    @Test
    fun `400 is not queueable`() {
        assertFalse(OrderRepository.isQueueableHttpCode(400))
    }

    @Test
    fun `404 is not queueable`() {
        assertFalse(OrderRepository.isQueueableHttpCode(404))
    }

    @Test
    fun `200 is not queueable`() {
        assertFalse(OrderRepository.isQueueableHttpCode(200))
    }

    // MARK: - ServerException

    @Test
    fun `ServerException carries status code`() {
        val ex = OrderRepository.ServerException(503, "Service Unavailable")
        assertEquals(503, ex.code)
        assertEquals("Service Unavailable", ex.message)
    }
}
