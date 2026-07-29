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
        assertEquals("No se pudo guardar", ServerErrorText.humanize(null, "No se pudo guardar"))
        assertEquals("No se pudo guardar", ServerErrorText.humanize("   ", "No se pudo guardar"))
    }
}
