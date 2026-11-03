package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.presentation.cerrarDialogoDeEntrega
import com.avoqado.pos.payment.presentation.*
import com.avoqado.pos.transactions.presentation.controlDeTerminalDeDevolucion
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ControlesDeRedPorGrafoR3Test(private val control: String) {
    private fun habilitado(ruta: Boolean, servidor: Boolean, disponible: Boolean = true): Boolean = when {
        control == "devolucion-terminal" -> controlDeTerminalDeDevolucion(true, ruta, servidor)
        control.startsWith("terminal-") -> controlDeTerminalDeTarjeta(true, ruta, servidor, disponible)
        control.startsWith("recibo-") || control.startsWith("duda-") || control == "error-retry" || control == "cancelacion-consultar" ->
            controlDeReciboDeTarjeta(true, ruta, servidor, disponible)
        else -> controlDeRedDeCuenta(ruta, servidor, disponible)
    }
    @Test fun `se apaga y vuelve por cada monitor sin abandonar la pantalla`() {
        assertTrue(habilitado(true, true))
        assertFalse("$control sin ruta", habilitado(false, true))
        assertTrue(habilitado(true, true))
        assertFalse("$control sin servidor", habilitado(true, false))
        assertTrue(habilitado(true, true))
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun casos() = listOf(
            "devolucion-terminal", "cancelar-abrir", "cancelar-confirmar", "regresar-abrir", "regresar-confirmar",
            "cuenta-cobrar", "cuenta-recargar", "cuenta-apertura", "cuenta-tras-saldo", "cuenta-tras-pago-parcial",
            "cuenta-tras-resolver", "acciones-cobrar", "acciones-apertura", "acciones-tras-cerrar", "venta-guardada-get",
            "terminal-clic", "terminal-F4", "terminal-reintentar", "terminal-revisar",
            "recibo-whatsapp-abrir", "recibo-whatsapp-confirmar", "recibo-correo-abrir", "recibo-correo-confirmar",
            "recibo-imprimir-terminal", "error-retry", "duda-consultar", "duda-declarar-abrir", "duda-declarar-confirmar",
            "cancelacion-consultar"
        ).map { arrayOf(it) }
    }
}

class DialogosDeRedPorGrafoR3Test {
    @Test fun `rechazo de cancelar mantiene dialogo y motivo hasta exito`() = runTest {
        var motivo = "El cliente cambió de opinión"
        var abierta = true
        var canceladas = 0
        val recibidos = mutableListOf<String>()
        abierta = cancelarCuentaDesdeDialogo(motivo, { recibidos += it; false }, { canceladas++ })
        assertTrue("El rechazo sin red no cierra la confirmacion", abierta)
        assertEquals("El cliente cambió de opinión", motivo)
        assertEquals(0, canceladas)
        abierta = cancelarCuentaDesdeDialogo(motivo, { recibidos += it; true }, { canceladas++ })
        assertFalse(abierta)
        assertEquals(1, canceladas)
        assertEquals(listOf(motivo, motivo), recibidos)
    }
    @Test fun `terminales disponibles no se descartan cuando se corta la red`() {
        var opciones = listOf("n86", "pax")
        for ((ruta, servidor) in listOf(false to true, true to false)) {
            if (controlDeTerminalDeDevolucion(true, ruta, servidor)) opciones = emptyList()
            assertEquals(listOf("n86", "pax"), opciones)
        }
    }
    @Test fun `destino escrito sigue abierto al caer la red y cierra solo tras exito`() {
        var dialogo: Pair<String, String>? = "sobre" to "CHANGE_DESTINATION"
        val telefono = "5512345678"
        if (cerrarDialogoDeEntrega(false)) dialogo = null
        assertEquals("sobre" to "CHANGE_DESTINATION", dialogo)
        assertEquals("5512345678", telefono)
        assertTrue(cerrarDialogoDeEntrega(true))
    }
    @Test fun `recibos y declaracion despachados cierran su dialogo`() {
        for (control in listOf("whatsapp", "correo", "impresion-terminal", "declaracion")) {
            assertTrue(control, cerrarDialogoDeRecibo(true))
            assertTrue(cerrarDialogoDeRecibo(true))
        }
    }
    @Test fun `flujos legacy y selecciones locales no adquieren barrera nueva`() {
        assertTrue(controlDeTerminalDeDevolucion(false, false, false))
        assertTrue(controlDeTerminalDeTarjeta(false, false, false, true))
        assertTrue(controlDeReciboDeTarjeta(false, false, false, true))
        assertFalse(controlDeRedDeCuenta(true, true, false))
        assertFalse(controlDeTerminalDeTarjeta(true, true, true, false))
        assertFalse(controlDeReciboDeTarjeta(true, true, true, false))
    }
}
