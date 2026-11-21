package com.avoqado.pos.maquina

import com.avoqado.pos.transactions.data.mensajeDelRechazoDeReembolso
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rediseño del SERVIDOR (D5, devoluciones de una venta de la máquina): lo que esta entrada no admite se contesta `400
 * NO_ADMITIDO_EN_ESTA_ENTRADA` con `details.dondeIr`; el POS lo dice junto al texto del SERVIDOR.
 */
class ReembolsoNoAdmitidoTest {

    @Test
    fun `lo no admitido dice a donde ir`() {
        val cuerpo = """{"success":false,"message":"Este cobro sólo se devuelve por el mismo medio con que se pagó.",""" +
            """"code":"NO_ADMITIDO_EN_ESTA_ENTRADA","details":{"dondeIr":["MISMO_MEDIO","SALDO_A_FAVOR"]}}"""

        assertEquals(
            "Este cobro sólo se devuelve por el mismo medio con que se pagó. Hazlo por el mismo medio del cobro o a saldo a favor.",
            mensajeDelRechazoDeReembolso("Este cobro sólo se devuelve por el mismo medio con que se pagó.", "NO_ADMITIDO_EN_ESTA_ENTRADA", cuerpo),
        )
    }

    @Test
    fun `cualquier otro rechazo, o uno sin destinos, queda igual`() {
        assertEquals("Sin permiso", mensajeDelRechazoDeReembolso("Sin permiso", "FORBIDDEN", """{"details":{"dondeIr":["POS"]}}"""))
        assertEquals("X", mensajeDelRechazoDeReembolso("X", "NO_ADMITIDO_EN_ESTA_ENTRADA", """{"details":"raro"}"""))
        assertEquals("X", mensajeDelRechazoDeReembolso("X", "NO_ADMITIDO_EN_ESTA_ENTRADA", "no es json"))
    }
}
