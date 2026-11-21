package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.payment.data.avisoDeSaldoCambiado
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F1-textos (catálogo D5, filas 14, 15 y 16). El aviso ámbar de 6 s del carrito decía «Venta de vales» en una venta de Mostrador.
 * El título se escoge leyendo el texto del aviso, que ya está a la mano; los demás títulos no cambian.
 */
class TituloDelAvisoF1Test {

    @Test
    fun `fila 14 el 412 de Mostrador se titula Cobro no enviado y dice que no se cobro y cuanto falta`() {
        val aviso = avisoDeSaldoCambiado(7_950)
        assertEquals("No se cobró la tarjeta: a esta cuenta le faltan \$79.50.", aviso)
        assertEquals("Cobro no enviado", tituloDelAviso(aviso))
    }

    @Test
    fun `fila 16 la cuenta con la tarjeta por confirmar dice por que y se titula Tarjeta por confirmar`() {
        assertEquals("Esta cuenta tiene una tarjeta por confirmar. Guárdala para atender a otro cliente.", CUENTA_LIGADA_GUARDALA)
        assertEquals("Tarjeta por confirmar", tituloDelAviso(CUENTA_LIGADA_GUARDALA))
    }

    @Test
    fun `los demas avisos conservan su titulo`() {
        assertEquals("Pagar después", tituloDelAviso(PAGAR_DESPUES_EN_CURSO))
        assertEquals("Sin conexión", tituloDelAviso(DIVIDIR_SIN_CONEXION))
        assertEquals("Venta a medio cobrar", tituloDelAviso(VENTA_A_MEDIAS_CAMBIADA))
        assertEquals("Venta de vales", tituloDelAviso("Cualquier otro aviso de vales."))
    }
}
