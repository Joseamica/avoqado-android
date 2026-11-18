package com.avoqado.pos.scale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnoDeConexionTest {
    @Test fun `una conexion que termina despues de soltar la bascula ya no es la vigente`() {
        val turnos = TurnoDeConexion()
        val primera = turnos.abrir()
        turnos.cerrar()                       // la etiqueta (o la X) cerró la captura mientras se abría el puerto
        assertFalse(turnos.vigente(primera))
    }

    @Test fun `la conexion de la captura siguiente gana a la que llega tarde`() {
        val turnos = TurnoDeConexion()
        val vieja = turnos.abrir()
        turnos.cerrar()
        val nueva = turnos.abrir()
        assertTrue(turnos.vigente(nueva))
        assertFalse(turnos.vigente(vieja))
    }
}
