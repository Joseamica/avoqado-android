package com.avoqado.pos.scale

import com.avoqado.pos.pos.data.model.DEFAULT_SCALE_READING_MAX_AGE_MILLIS
import com.avoqado.pos.pos.data.model.NormalizedScaleReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * D15: la báscula calla con el cable puesto (apagada) y la ventana «Peso» seguía en «Lectura estable» con el último peso;
 * al abrir otro producto, «Agregar» cobraba el peso del anterior. Una lectura sólo cuenta si es de hace ≤ 2 s y llegó
 * después de abrir el producto; si no, se pinta «Lista» (no hay peso que ofrecer).
 */
class LecturaVigenteTest {
    private val abierto = 100_000L

    private fun lectura(observedAt: Long, kg: String = "0.435") = NormalizedScaleReading(
        deviceId = "rhino",
        netKg = kg,
        stable = true,
        observedAtEpochMillis = observedAt,
    )

    private fun estable(observedAt: Long) = ScaleConnectionState.Stable("Rhino", lectura(observedAt))

    @Test
    fun `un peso estable de hace un instante y despues de abrir se ofrece igual`() {
        val estado = estable(abierto + 500)
        assertSame(estado, estado.vigente(desdeEpochMillis = abierto, ahoraEpochMillis = abierto + 600))
    }

    @Test
    fun `la bascula calla - a los 2 s el peso estable ya no se ofrece`() {
        val estado = estable(abierto + 500)
        val limite = abierto + 500 + DEFAULT_SCALE_READING_MAX_AGE_MILLIS
        assertSame("justo a los 2 s todavía cuenta", estado, estado.vigente(abierto, limite))
        assertEquals(ScaleConnectionState.Ready("Rhino"), estado.vigente(abierto, limite + 1))
    }

    @Test
    fun `el peso del producto anterior no se ofrece al abrir otro aunque sea de hace un instante`() {
        val delAnterior = estable(abierto - 1)
        assertEquals(ScaleConnectionState.Ready("Rhino"), delAnterior.vigente(abierto, abierto + 10))
    }

    @Test
    fun `una lectura inestable vieja tampoco se queda en pantalla`() {
        val estado = ScaleConnectionState.Unstable("Rhino", lectura(abierto).copy(stable = false))
        assertEquals(ScaleConnectionState.Ready("Rhino"), estado.vigente(abierto, abierto + DEFAULT_SCALE_READING_MAX_AGE_MILLIS + 1))
        assertSame(estado, estado.vigente(abierto, abierto + 1))
    }

    @Test
    fun `una lectura del futuro (reloj movido) no cuenta`() {
        assertEquals(ScaleConnectionState.Ready("Rhino"), estable(abierto + 5_000).vigente(abierto, abierto + 1_000))
    }

    @Test
    fun `los estados sin lectura no cambian`() {
        listOf(
            ScaleConnectionState.NotConfigured,
            ScaleConnectionState.Connecting("Rhino"),
            ScaleConnectionState.Ready("Rhino"),
            ScaleConnectionState.Problem("Rhino", "sin cable"),
        ).forEach { assertSame(it, it.vigente(abierto, abierto + 60_000)) }
    }
}
