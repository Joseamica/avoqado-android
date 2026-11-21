package com.avoqado.pos.tables.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/** D16 (2.ª pasada con aparatos, B9): «No salió la comanda de: …» pide avisar a cocina; en 2.2 s no da tiempo de leerlo. */
class AvisoDeComandaEnLaMesaTest {
    @Test fun `el aviso de la comanda que no salio se queda 60 s y los demas como siempre`() {
        assertEquals(60_000L, TableOrderViewModel.duracionDelAvisoDeError(TableOrderViewModel.PISTA_COMANDA_SIN_IMPRESORA))
        assertEquals(60_000L, TableOrderViewModel.duracionDelAvisoDeError(TableOrderViewModel.PISTA_COMANDA_REINTENTO))
        assertEquals(2_200L, TableOrderViewModel.duracionDelAvisoDeError(null))
        assertEquals(2_200L, TableOrderViewModel.duracionDelAvisoDeError("Revisa la conexión"))
    }
}
