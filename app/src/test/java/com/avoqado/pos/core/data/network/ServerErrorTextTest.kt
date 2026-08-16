package com.avoqado.pos.core.data.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * El caso real: un mesero intentó agendar una cita sin permiso y la app le
 * enseñó "Permission 'reservations:create' required", en inglés y con comillas.
 */
class ServerErrorTextTest {

    @Test
    fun `el error de permiso del server nombra la ACCION, no el codigo`() {
        // 🔴 2026-08-16: el founder vio "te active «tpv:read»" y su queja fue
        // exacta — "tiene tpv:read, no explica la causa real". El código no le
        // dice nada a quien está cobrando; la acción sí.
        val out = ServerErrorText.humanize("Permission 'reservations:create' required")
        assertTrue("debe hablarle a la persona: $out", out.startsWith("No tienes permiso"))
        assertTrue("debe nombrar la acción: $out", out.contains("agendar una reservación"))
        assertFalse("el código técnico no se enseña: $out", out.contains("reservations:create"))
        assertFalse("sin jerga en inglés: $out", out.contains("Permission"))
    }

    @Test
    fun `el caso real del cajero — tpv read deja de ser un codigo`() {
        // Medido en hardware: un CASHIER cobrando vio este modal en la pantalla
        // de propina porque la app consulta sola qué terminales están en línea.
        val out = ServerErrorText.humanize("Permission 'tpv:read' required")
        assertEquals(
            "No tienes permiso para hacer esto. Pídele a un administrador que te active «cobrar con terminal».",
            out,
        )
    }

    @Test
    fun `un permiso sin etiqueta sigue siendo entendible, y NUNCA muestra el codigo`() {
        // El respaldo no puede dejar la frase coja ("que te active «esta
        // acción»") ni caer otra vez en el código técnico.
        val out = ServerErrorText.humanize("Permission 'cosas:raras' required")
        assertFalse("nada de códigos: $out", out.contains("cosas:raras"))
        assertFalse("ni la frase coja del respaldo: $out", out.contains("«"))
        assertTrue("tiene que decir a quién pedírselo: $out", out.contains("administrador"))
        assertTrue("y que el problema es de permisos: $out", out.startsWith("No tienes permiso"))
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
        assertTrue("y nombrar la acción: $msg", msg.contains("dar de alta un cliente"))
    }

    @Test
    fun `isOffline distingue la falta de red de un rechazo`() {
        assertTrue(ServerErrorText.isOffline(java.net.UnknownHostException("x")))
        assertTrue(!ServerErrorText.isOffline(RuntimeException("Forbidden")))
        assertTrue(!ServerErrorText.isOffline(null))
    }

    // MARK: - Retrofit: el motivo vive en el CUERPO, no en el message

    private fun httpError(code: Int, body: String): retrofit2.HttpException =
        retrofit2.HttpException(
            retrofit2.Response.error<Any>(
                code,
                body.toResponseBody("application/json".toMediaType()),
            ),
        )

    @Test
    fun `un rechazo de negocio muestra el motivo del server, no HTTP 400`() {
        // 🔴 Visto en la T3 el 2026-08-09: el mesero tocó "Fusionar cuentas", el
        // server explicó perfectamente por qué no se podía, y en pantalla salió
        // "HTTP 400". `HttpException.message` es genérico; el motivo viaja en el
        // cuerpo y nadie lo leía. Pasaba en TODA llamada Retrofit de la app.
        val error = httpError(400, """{"message":"La cuenta origen ya tiene pagos; no se puede fusionar"}""")

        assertEquals(
            "La cuenta origen ya tiene pagos; no se puede fusionar",
            ServerErrorText.humanize(error, "No se pudo fusionar"),
        )
    }

    @Test
    fun `tambien lee las otras llaves que usa el server`() {
        assertEquals("Algo pasó", ServerErrorText.humanize(httpError(400, """{"error":"Algo pasó"}"""), "fallback"))
        assertEquals("Otra cosa", ServerErrorText.humanize(httpError(422, """{"errorMessage":"Otra cosa"}"""), "fallback"))
    }

    @Test
    fun `sin cuerpo util cae al fallback, nunca a HTTP nnn`() {
        listOf("", "no soy json", "{}", """{"message":""}""").forEach { body ->
            val texto = ServerErrorText.humanize(httpError(500, body), "No se pudo completar")
            assertEquals("No se pudo completar", texto)
            assertFalse("no debe filtrarse jerga HTTP: $texto", texto.contains("HTTP"))
        }
    }

    @Test
    fun `un permiso del server sigue traduciendose aunque venga en el cuerpo`() {
        // La traducción de permisos ya existía para el texto plano; tiene que
        // seguir aplicando cuando el mismo texto llega dentro del JSON.
        val texto = ServerErrorText.humanize(
            httpError(403, """{"message":"Permission 'tables:manage-all' required"}"""),
            "fallback",
        )
        assertTrue(texto.contains("No tienes permiso"))
        assertTrue(
            "la acción se nombra igual que cuando viene en texto plano: $texto",
            texto.contains("modificar mesas de otro mesero"),
        )
    }
}
