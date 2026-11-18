package com.avoqado.pos.payment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 Codex android r1 #10 (F1, tarea 6): toda tarjeta en duda del POS se ve IGUAL — el importe y sólo [Seguir]; la consulta sigue sola
 * en segundo plano. La cancelación pendiente y el Cambio de producto con la diferencia en duda usaban otras pantallas con «Volver a
 * consultar», «Confirmar estado» y «Salir (queda pendiente)». Se lee el cableado (como `CableadoDelCobroAbiertoTest`): la pantalla misma
 * la prueba `TarjetaPorConfirmarEnLaEscenaTest` en WINDOWS.
 */
class PantallaDeDudaCableadoTest {
    private fun fuente(ruta: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, ruta).takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("No encontré $ruta")
    }

    private fun rama(codigo: String, desde: String, hasta: String): String {
        val a = codigo.indexOf(desde)
        assertTrue("no encontré «$desde»", a >= 0)
        val b = codigo.indexOf(hasta, a + desde.length)
        return codigo.substring(a, if (b > a) b else codigo.length)
    }

    @Test fun `la cancelacion pendiente es la misma pantalla de duda, sin Volver a consultar`() {
        val flujo = fuente("app/src/main/java/com/avoqado/pos/payment/presentation/PaymentFlowScreen.kt")
        val cancelacion = rama(flujo, "is PaymentFlowState.CancelacionPendiente ->", "is PaymentFlowState.Success ->")
        // `DudaDeTarjeta` es la misma `TarjetaPorConfirmarView` del resultado, con el importe del intento.
        assertTrue(cancelacion, cancelacion.contains("DudaDeTarjeta("))
        assertTrue(flujo.substringAfter("private fun DudaDeTarjeta(").contains("TarjetaPorConfirmarView("))
        assertFalse(cancelacion, cancelacion.contains("volverAConsultarCancelacion"))
    }

    @Test fun `el Cambio con la diferencia en duda es la misma pantalla de duda, sin Confirmar estado ni Salir`() {
        val cambio = fuente("app/src/main/java/com/avoqado/pos/transactions/presentation/exchange/ExchangeScreen.kt")
        val duda = rama(cambio, "ExchangeSendPhase.UNCERTAIN && send.result != null ->", "else ->")
        assertTrue(duda, duda.contains("TarjetaPorConfirmarView("))
        assertFalse(duda, duda.contains("CONFIRMAR_ESTADO"))
        assertFalse(duda, duda.contains("PaymentUndeterminedView("))
    }
}
