package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.PrinterStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [shouldReconnect] — the pure decision function [PrinterService.sendData]
 * uses to decide whether the cached printer socket must be dropped and a fresh
 * connection opened, instead of being reused as-is.
 *
 * This targets the silent print-loss bug: [PrinterService] cached WiFi/Bluetooth
 * sockets keyed only by `printer.id`, with no awareness of the printer's address.
 * When an operator edited a printer's IP (or a refetched config changed it) while
 * the in-memory status still said "Connected", the stale socket pointing at the
 * OLD address was reused. The write into that dead socket did not throw, so the
 * app logged success while nothing printed.
 */
class PrinterServiceReconnectTest {

    private val endpointA = "192.168.1.50:9100"
    private val endpointB = "10.0.2.2:9100"

    @Test
    fun `regression guard - address changed while status says connected must reconnect, not reuse`() {
        // This is exactly the reproduced bug: printer.address was edited (e.g. via
        // the dashboard) from 127.0.0.1:9100 to 10.0.2.2:9100, status still says
        // Connected, and the socket is technically still open (it just points at
        // the wrong host). The legacy code reused it silently; the fix must not.
        val result = shouldReconnect(
            status = PrinterStatus.Connected,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointB,
            socketClosed = false,
        )
        assertTrue("Endpoint changed while 'connected' must force a reconnect", result)
    }

    @Test
    fun `connected plus same endpoint plus socket open means reuse - legacy behavior guard`() {
        // The additive contract: when the cache is genuinely still valid, sendData
        // must behave byte-for-byte like today - no reconnect, no extra latency.
        val result = shouldReconnect(
            status = PrinterStatus.Connected,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointA,
            socketClosed = false,
        )
        assertFalse("A valid, matching, open cached socket must be reused as-is", result)
    }

    @Test
    fun `printing plus same endpoint plus socket open also means reuse`() {
        // PrinterStatus.isConnected is true for both Connected and Printing.
        val result = shouldReconnect(
            status = PrinterStatus.Printing,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointA,
            socketClosed = false,
        )
        assertFalse(result)
    }

    @Test
    fun `connected but socket already closed must reconnect`() {
        val result = shouldReconnect(
            status = PrinterStatus.Connected,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointA,
            socketClosed = true,
        )
        assertTrue("A closed cached socket must never be reused", result)
    }

    @Test
    fun `status disconnected must reconnect - today's existing behavior`() {
        val result = shouldReconnect(
            status = PrinterStatus.Disconnected,
            cachedEndpoint = null,
            requestedEndpoint = endpointA,
            socketClosed = true,
        )
        assertTrue(result)
    }

    @Test
    fun `status connecting must reconnect`() {
        val result = shouldReconnect(
            status = PrinterStatus.Connecting,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointA,
            socketClosed = false,
        )
        assertTrue(result)
    }

    @Test
    fun `status error must reconnect`() {
        val result = shouldReconnect(
            status = PrinterStatus.Error("boom"),
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointA,
            socketClosed = false,
        )
        assertTrue(result)
    }

    @Test
    fun `connected with no cached endpoint at all must reconnect`() {
        // Defensive case: status says connected but there is no record of what
        // endpoint the socket was opened for (e.g. pre-fix cache state).
        val result = shouldReconnect(
            status = PrinterStatus.Connected,
            cachedEndpoint = null,
            requestedEndpoint = endpointA,
            socketClosed = false,
        )
        assertTrue(result)
    }

    @Test
    fun `connected with different endpoint and closed socket must reconnect`() {
        val result = shouldReconnect(
            status = PrinterStatus.Connected,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointB,
            socketClosed = true,
        )
        assertTrue(result)
    }
}
