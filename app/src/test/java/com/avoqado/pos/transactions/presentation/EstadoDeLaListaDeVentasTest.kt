package com.avoqado.pos.transactions.presentation

import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.designsystem.components.TextosDeLecturaFallida
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D18 (3-nov): sin red, Ventas decía «Sin transacciones · Las transacciones aparecerán aquí». La lista vivía sólo en
 * memoria: tras abrir la app sin red estaba vacía y la pantalla concluía «no hay ventas». Y (Codex r1 #1 y #3) una lista
 * de otra sucursal, de otra persona o de otra búsqueda se enseñaba como si fuera de la consulta vigente.
 */
class EstadoDeLaListaDeVentasTest {

    @Test
    fun `sin ventas en la mano y sin red dice Sin conexion, no Sin transacciones`() {
        assertEquals(
            EstadoDeLaListaDeVentas.NoSeCargo(FalloDeLectura.SIN_RED),
            estadoDeLaListaDeVentas(deLaConsulta = true, cargadas = 0, visibles = 0, cargando = false, fallo = FalloDeLectura.SIN_RED),
        )
    }

    @Test
    fun `sin ventas en la mano y el servidor contesto mal lo dice distinto`() {
        assertEquals(
            EstadoDeLaListaDeVentas.NoSeCargo(FalloDeLectura.SERVIDOR),
            estadoDeLaListaDeVentas(deLaConsulta = true, cargadas = 0, visibles = 0, cargando = false, fallo = FalloDeLectura.SERVIDOR),
        )
    }

    @Test
    fun `con ventas de la consulta en la mano un fallo no cambia nada - la lista se queda`() {
        for (fallo in listOf(FalloDeLectura.SIN_RED, FalloDeLectura.SERVIDOR, null)) {
            assertEquals(EstadoDeLaListaDeVentas.Lista, estadoDeLaListaDeVentas(true, 3, 3, cargando = false, fallo = fallo))
            assertEquals(EstadoDeLaListaDeVentas.Lista, estadoDeLaListaDeVentas(true, 3, 3, cargando = true, fallo = fallo))
            // Los filtros esconden las que SÍ hay: es la lista vacía de los filtros, no un fallo.
            assertEquals(EstadoDeLaListaDeVentas.Vacia, estadoDeLaListaDeVentas(true, 3, 0, cargando = false, fallo = fallo))
        }
    }

    @Test
    fun `la lista de otra consulta no se ve - sin red dice Sin conexion`() {
        // Otra sucursal, otra persona (también por PIN) u otra búsqueda: Codex r1 #1 y #3.
        assertEquals(
            EstadoDeLaListaDeVentas.NoSeCargo(FalloDeLectura.SIN_RED),
            estadoDeLaListaDeVentas(deLaConsulta = false, cargadas = 5, visibles = 5, cargando = false, fallo = FalloDeLectura.SIN_RED),
        )
        assertEquals(
            EstadoDeLaListaDeVentas.NoSeCargo(FalloDeLectura.SERVIDOR),
            estadoDeLaListaDeVentas(deLaConsulta = false, cargadas = 5, visibles = 5, cargando = false, fallo = FalloDeLectura.SERVIDOR),
        )
    }

    @Test
    fun `la lista de otra consulta no se ve - mientras va la vigente es el esqueleto`() {
        assertEquals(EstadoDeLaListaDeVentas.Cargando, estadoDeLaListaDeVentas(false, 5, 5, cargando = true, fallo = null))
        assertEquals(EstadoDeLaListaDeVentas.Cargando, estadoDeLaListaDeVentas(false, 5, 5, cargando = false, fallo = null))
    }

    @Test
    fun `cargando sin ventas en la mano es el esqueleto, aunque la vez pasada no hubiera red`() {
        for (fallo in listOf(FalloDeLectura.SIN_RED, FalloDeLectura.SERVIDOR, null)) {
            assertEquals(EstadoDeLaListaDeVentas.Cargando, estadoDeLaListaDeVentas(true, 0, 0, cargando = true, fallo = fallo))
        }
    }

    @Test
    fun `el servidor contesto y no hay ventas es la lista vacia de siempre`() {
        assertEquals(EstadoDeLaListaDeVentas.Vacia, estadoDeLaListaDeVentas(true, 0, 0, cargando = false, fallo = null))
    }

    @Test
    fun `los textos son los del encargo`() {
        assertEquals(
            TextosDeLecturaFallida(
                "Sin conexión",
                "Las ventas se consultan en el servidor. Las que cobres sin red se guardan en este aparato y aparecen " +
                    "aquí al volver la conexión.",
            ),
            textosDeVentasSinCargar(FalloDeLectura.SIN_RED),
        )
        assertEquals(
            TextosDeLecturaFallida("No se pudieron cargar las ventas", "Vuelve a intentarlo en un momento."),
            textosDeVentasSinCargar(FalloDeLectura.SERVIDOR),
        )
    }
}
