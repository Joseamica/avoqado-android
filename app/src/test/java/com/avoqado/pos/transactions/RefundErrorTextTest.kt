package com.avoqado.pos.transactions

import com.avoqado.pos.core.data.network.SesionSinRenovar
import com.avoqado.pos.transactions.data.RefundApiException
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import com.avoqado.pos.transactions.presentation.formatRefundError
import com.avoqado.pos.transactions.presentation.textoDeFalloAlAbrirEnTerminal
import com.avoqado.pos.transactions.presentation.textoDeFalloDeListaDeTerminales
import com.avoqado.pos.transactions.presentation.esAvisoSinRed
import com.avoqado.pos.transactions.presentation.AperturasEnTerminal
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

    // D5 (full-testing en el Alien, 4-nov): sin red de verdad la falla llega ANTES de mandar nada, al pedir la lista de
    // terminales, y la pantalla enseñaba «Error de conexión» en rojo; con la puerta de conexión cerrada decía el texto de
    // tarjetas de regalo. Nada salió, así que aquí sí se puede decir «sin conexión» e invitar a repetirlo con red.
    @Test
    fun `P2 sin red la lista de terminales dice sin conexion con el texto del reembolso`() {
        val sinRed = com.avoqado.pos.payment.data.TerminalListResult.Error("Error de conexión", sinRed = true)
        assertEquals(TextosCambio.DEVOLUCION_EN_TERMINAL_SIN_RED, textoDeFalloDeListaDeTerminales(sinRed))
        assertEquals(TextosCambio.DEVOLUCION_EN_TERMINAL_SIN_RED, textoDeFalloDeListaDeTerminales(null))
    }

    @Test
    fun `P2 un rechazo del servidor al pedir las terminales se enseña tal cual`() {
        val rechazo = com.avoqado.pos.payment.data.TerminalListResult.Error("No tienes permiso", sinRed = false)
        assertEquals("No tienes permiso", textoDeFalloDeListaDeTerminales(rechazo))
    }

    @Test
    fun `P1 si un envio anterior pudo llegar a la terminal la duda gana al sin conexion`() {
        val sinRed = com.avoqado.pos.payment.data.TerminalListResult.Error("Error de conexión", sinRed = true)
        assertEquals(TextosCambio.DEVOLUCION_EN_TERMINAL_SIN_RESPUESTA, textoDeFalloDeListaDeTerminales(sinRed, pudoLlegarAntes = true))
        assertEquals(TextosCambio.DEVOLUCION_EN_TERMINAL_SIN_RESPUESTA, textoDeFalloDeListaDeTerminales(null, pudoLlegarAntes = true))
    }

    @Test
    fun `P1 la apertura en la terminal se recuerda por pago y abierta no se pierde con un fallo despues`() {
        val pago = "pago-prueba-" + System.nanoTime()
        assertEquals(null, AperturasEnTerminal.de(pago))
        AperturasEnTerminal.anotar(pago, AperturasEnTerminal.Estado.PUDO_LLEGAR)
        assertEquals(AperturasEnTerminal.Estado.PUDO_LLEGAR, AperturasEnTerminal.de(pago))
        AperturasEnTerminal.anotar(pago, AperturasEnTerminal.Estado.ABIERTA)
        AperturasEnTerminal.anotar(pago, AperturasEnTerminal.Estado.PUDO_LLEGAR)
        assertEquals(AperturasEnTerminal.Estado.ABIERTA, AperturasEnTerminal.de(pago))
        assertEquals(null, AperturasEnTerminal.de(pago + "-otro"))
        AperturasEnTerminal.olvidar(pago)
        assertEquals("al cerrar la hoja se puede volver a abrir", null, AperturasEnTerminal.de(pago))
        val viejo = pago + "-viejo"
        AperturasEnTerminal.anotar(viejo, AperturasEnTerminal.Estado.ABIERTA, ahora = 1_000L)
        assertEquals(AperturasEnTerminal.Estado.ABIERTA, AperturasEnTerminal.de(viejo, ahora = 1_000L + AperturasEnTerminal.VIGENCIA_MS))
        assertEquals("caduca si la hoja desapareció sin cerrarse", null, AperturasEnTerminal.de(viejo, ahora = 1_001L + AperturasEnTerminal.VIGENCIA_MS))
    }

    @Test
    fun `P2 el aviso sin red no es de tarjetas de regalo ni promete que no se mando`() {
        val texto = TextosCambio.DEVOLUCION_EN_TERMINAL_SIN_RED
        assert(texto.startsWith("Sin conexión")) { texto }
        assert(!texto.contains("tarjetas de regalo")) { texto }
        assert(esAvisoSinRed(texto) && !esAvisoSinRed(TextosCambio.DEVOLUCION_EN_TERMINAL_SIN_RESPUESTA))
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
