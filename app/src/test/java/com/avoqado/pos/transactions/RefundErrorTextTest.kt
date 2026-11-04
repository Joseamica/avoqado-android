package com.avoqado.pos.transactions

import com.avoqado.pos.core.data.network.SesionSinRenovar
import com.avoqado.pos.transactions.data.RefundApiException
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import com.avoqado.pos.transactions.presentation.formatRefundError
import com.avoqado.pos.transactions.presentation.textoDeFalloAlAbrirEnTerminal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * D7: un reembolso común sin red enseñaba el texto crudo de la excepción («Failed to connect to localhost/127.0.0.1:3041»).
 * Ningún fallo de red prueba que el reembolso no se aplicó: OkHttp reintenta, así que el primer POST pudo aplicarse y lo último
 * que falla es la reconexión (Codex r3). Decirle «no se hizo, inténtalo de nuevo» sería invitarla a devolver dos veces.
 */
class RefundErrorTextTest {

    @Test
    fun `sin conexion no enseña el texto tecnico ni invita a repetirlo a ciegas`() {
        listOf(
            UnknownHostException("Unable to resolve host \"api.avoqado.io\": No address associated with hostname"),
            ConnectException("Failed to connect to localhost/127.0.0.1:3041"),
            NoRouteToHostException("No route to host"),
            SesionSinRenovar("red:UnknownHostException"),
        ).forEach { assertEquals(it.toString(), TextosCambio.REEMBOLSO_SIN_RED, formatRefundError(it, null)) }
    }

    @Test
    fun `si se fue y no hubo respuesta no invita a repetirlo a ciegas`() {
        listOf(
            SocketTimeoutException("timeout"),
            InterruptedIOException("timeout"),
            IOException("unexpected end of stream on http://localhost:3041/..."),
            SSLException("Connection reset"),
        ).forEach { assertEquals(it.toString(), TextosCambio.REEMBOLSO_SIN_RESPUESTA, formatRefundError(it, null)) }
    }

    @Test
    fun `si el servidor no renovo la sesion no se habla de falta de red ni de repetir`() {
        assertEquals(TextosCambio.REEMBOLSO_SIN_RESPUESTA, formatRefundError(SesionSinRenovar("servidor:503"), null))
    }

    @Test
    fun `ningun texto de red afirma que no se hizo ni dice intentalo de nuevo`() {
        listOf(TextosCambio.REEMBOLSO_SIN_RED, TextosCambio.REEMBOLSO_SIN_RESPUESTA).forEach {
            org.junit.Assert.assertFalse(it, it.contains("no se hizo") || it.contains("inténtalo de nuevo") || it.contains("Intenta de nuevo"))
            org.junit.Assert.assertTrue(it, it.contains("revisa en Ventas"))
        }
    }

    @Test
    fun `abrir la devolucion en la terminal sin red no enseña el texto tecnico`() {
        listOf(
            ConnectException("Failed to connect to localhost/127.0.0.1:3041"),
            UnknownHostException("Unable to resolve host"),
            SocketTimeoutException("timeout"),
            SesionSinRenovar("red:UnknownHostException"),
        ).forEach { assertEquals(it.toString(), TextosCambio.DEVOLUCION_EN_TERMINAL_SIN_RESPUESTA, textoDeFalloAlAbrirEnTerminal(it)) }
    }

    @Test
    fun `abrir la devolucion en la terminal enseña tal cual lo que rechaza el servidor`() {
        assertEquals("La terminal está ocupada", textoDeFalloAlAbrirEnTerminal(Exception("La terminal está ocupada")))
        assertEquals("No se pudo abrir la devolución en la terminal.", textoDeFalloAlAbrirEnTerminal(Exception()))
    }

    @Test
    fun `los rechazos del servidor siguen igual`() {
        assertEquals("Ya se devolvió", formatRefundError(RefundApiException(400, "Ya se devolvió"), null))
        assertEquals("Revisa los datos del reembolso", formatRefundError(RefundApiException(422, ""), null))
        assertEquals("No se pudo emitir el reembolso. Intenta de nuevo.", formatRefundError(RefundApiException(502, "Bad Gateway"), null))
        assertEquals("Conflicto", formatRefundError(RefundApiException(409, "Conflicto"), null))
        assertEquals("No venue ID", formatRefundError(Exception("No venue ID"), null))
        assertEquals("Error al emitir reembolso", formatRefundError(Exception(), null))
    }
}
