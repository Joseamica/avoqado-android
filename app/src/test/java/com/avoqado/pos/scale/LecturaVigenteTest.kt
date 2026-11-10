package com.avoqado.pos.scale

import com.avoqado.pos.pos.data.model.DEFAULT_SCALE_READING_MAX_AGE_MILLIS
import com.avoqado.pos.pos.data.model.NormalizedScaleReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * D15: la báscula calla con el cable puesto (apagada) y la ventana «Peso» seguía en «Lectura estable» con el último peso;
 * al abrir otro producto, «Agregar» cobraba el peso del anterior. Una lectura sólo cuenta si es NUEVA (distinta de la que
 * ya estaba al abrir el producto) y de hace ≤ 2 s; si no, se pinta «Lista» (no hay peso que ofrecer).
 */
class LecturaVigenteTest {
    private val t = 100_000L

    private fun lectura(observedAt: Long, kg: String = "0.435") = NormalizedScaleReading(
        deviceId = "rhino",
        netKg = kg,
        stable = true,
        observedAtEpochMillis = observedAt,
    )

    private fun estable(observedAt: Long, kg: String = "0.435") = ScaleConnectionState.Stable("Rhino", lectura(observedAt, kg))

    @Test
    fun `un peso estable nuevo de hace un instante se ofrece igual`() {
        val estado = estable(t + 500)
        assertSame(estado, estado.vigente(lecturaAlAbrir = null, ahoraEpochMillis = t + 600))
        assertSame("también si al abrir había otra lectura", estado, estado.vigente(lectura(t), t + 600))
    }

    @Test
    fun `la bascula calla - a los 2 s el peso estable ya no se ofrece`() {
        val estado = estable(t + 500)
        val limite = t + 500 + DEFAULT_SCALE_READING_MAX_AGE_MILLIS
        assertSame("justo a los 2 s todavía cuenta", estado, estado.vigente(null, limite))
        assertEquals(ScaleConnectionState.Ready("Rhino"), estado.vigente(null, limite + 1))
    }

    @Test
    fun `la lectura que ya estaba al abrir el producto no se ofrece aunque sea de hace un instante`() {
        val delAnterior = estable(t - 1)
        assertEquals(ScaleConnectionState.Ready("Rhino"), delAnterior.vigente(delAnterior.reading, t + 10))
    }

    /** Codex r1: otro producto se abre en el MISMO milisegundo en que llegó la lectura. */
    @Test
    fun `la lectura del mismo milisegundo en que se abrio el producto tampoco cuenta`() {
        val alAbrir = estable(t)
        assertEquals(ScaleConnectionState.Ready("Rhino"), alAbrir.vigente(alAbrir.reading, t))
    }

    /** Codex r1: con la hora, un reloj que se atrasa un minuto dejaba «Agregar» apagado hasta reabrir. */
    @Test
    fun `si el reloj se atrasa, una lectura nueva se ofrece igual`() {
        val alAbrir = lectura(t)
        val nueva = estable(t - 60_000, kg = "0.500")
        assertSame(nueva, nueva.vigente(alAbrir, t - 59_900))
    }

    @Test
    fun `una lectura inestable vieja tampoco se queda en pantalla`() {
        val estado = ScaleConnectionState.Unstable("Rhino", lectura(t).copy(stable = false))
        assertEquals(ScaleConnectionState.Ready("Rhino"), estado.vigente(null, t + DEFAULT_SCALE_READING_MAX_AGE_MILLIS + 1))
        assertSame(estado, estado.vigente(null, t + 1))
    }

    @Test
    fun `una lectura del futuro (reloj movido) no cuenta`() {
        assertEquals(ScaleConnectionState.Ready("Rhino"), estable(t + 5_000).vigente(null, t + 1_000))
    }

    /** Codex r1: «Agregar» y F12 vuelven a preguntar al tocarse; justo al caducar ya no hay peso. */
    @Test
    fun `el peso para agregar se pregunta en el instante del toque`() {
        val estado = estable(t)
        assertEquals("0.435", estado.pesoEstableVigente(null, t + DEFAULT_SCALE_READING_MAX_AGE_MILLIS)?.netKg)
        assertNull(estado.pesoEstableVigente(null, t + DEFAULT_SCALE_READING_MAX_AGE_MILLIS + 1))
        assertNull("inestable no se agrega", ScaleConnectionState.Unstable("Rhino", lectura(t).copy(stable = false)).pesoEstableVigente(null, t))
        assertNull("la del producto anterior no se agrega", estado.pesoEstableVigente(estado.reading, t))
    }

    @Test
    fun `los estados sin lectura no cambian`() {
        listOf(
            ScaleConnectionState.NotConfigured,
            ScaleConnectionState.Connecting("Rhino"),
            ScaleConnectionState.Ready("Rhino"),
            ScaleConnectionState.Problem("Rhino", "sin cable"),
        ).forEach { assertSame(it, it.vigente(null, t + 60_000)) }
    }
}
