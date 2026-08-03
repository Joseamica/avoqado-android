package com.avoqado.pos.core.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El caso real: un mesero intentó agendar una cita sin permiso y la app le
 * enseñó "Permission 'reservations:create' required", en inglés y con comillas.
 */
class ServerErrorTextTest {

    @Test
    fun `el error de permiso del server se vuelve legible y conserva el codigo`() {
        val out = ServerErrorText.humanize("Permission 'reservations:create' required")
        assertTrue("debe hablarle a la persona", out.startsWith("No tienes permiso"))
        assertTrue("el admin necesita el código para activarlo", out.contains("reservations:create"))
        assertTrue("sin jerga en inglés", !out.contains("Permission"))
    }

    @Test
    fun `Forbidden pelado tambien se traduce`() {
        assertEquals("No tienes permiso para hacer esto.", ServerErrorText.humanize("Forbidden"))
    }

    @Test
    fun `un mensaje ya en espanol se respeta tal cual`() {
        val msg = "La mesa ya tiene una cuenta abierta"
        assertEquals(msg, ServerErrorText.humanize(msg))
    }

    @Test
    fun `sin mensaje usa el respaldo que le dieron`() {
        assertEquals("No se pudo guardar", ServerErrorText.humanize(null as String?, "No se pudo guardar"))
        assertEquals("No se pudo guardar", ServerErrorText.humanize("   ", "No se pudo guardar"))
    }

    // MARK: - Fallos de red
    //
    // El otro idioma ajeno que veía el mesero: el del SISTEMA. Un
    // UnknownHostException llega como "Unable to resolve host ...", en inglés y
    // hablando de DNS, así que quedarse sin WiFi se leía como una app rota.
    // iOS ya lo cubría con URLError; Android no, y eso es una asimetría entre
    // plataformas que sólo se nota en el piso.

    @Test
    fun `quedarse sin red dice sin conexion, no habla de hosts`() {
        val msg = ServerErrorText.humanize(java.net.UnknownHostException("Unable to resolve host \"api.avoqado.io\""))
        assertTrue("no puede filtrar el texto del sistema: $msg", !msg.contains("host"))
        assertTrue("debe decir que falta conexión: $msg", msg.contains("Sin conexión"))
    }

    @Test
    fun `cada pantalla puede decir que significa quedarse sin red ahi`() {
        // El reloj checador EXIGE internet: si alguien lee un "inténtalo de nuevo"
        // genérico puede irse creyendo que marcó su entrada.
        val msg = ServerErrorText.humanize(
            java.net.ConnectException("failed to connect"),
            offlineMessage = "Sin conexión: el reloj necesita internet para registrar tu entrada.",
        )
        assertEquals("Sin conexión: el reloj necesita internet para registrar tu entrada.", msg)
    }

    @Test
    fun `un servidor lento se distingue de no tener red`() {
        // Son cosas distintas para quien está atendiendo: una se resuelve
        // esperando, la otra revisando el WiFi.
        val msg = ServerErrorText.humanize(java.net.SocketTimeoutException("timeout"))
        assertTrue("debe hablar del servidor: $msg", msg.contains("servidor"))
        assertTrue("no puede decir que falta red: $msg", !msg.contains("Sin conexión"))
    }

    @Test
    fun `un rechazo del server NO se disfraza de problema de red`() {
        // Confundirlos es el bug clásico: o tragas un error real, o le echas la
        // culpa al WiFi de algo que el server rechazó a propósito.
        val msg = ServerErrorText.humanize(RuntimeException("Permission 'customers:create' required"))
        assertTrue("debe seguir siendo el de permiso: $msg", msg.contains("No tienes permiso"))
        assertTrue("y conservar el código: $msg", msg.contains("customers:create"))
    }

    @Test
    fun `isOffline distingue la falta de red de un rechazo`() {
        assertTrue(ServerErrorText.isOffline(java.net.UnknownHostException("x")))
        assertTrue(!ServerErrorText.isOffline(RuntimeException("Forbidden")))
        assertTrue(!ServerErrorText.isOffline(null))
    }
}
