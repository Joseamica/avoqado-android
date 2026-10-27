package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.CortesiaPersistida
import org.junit.Assert.*
import org.junit.Test

class CortesiaPersistidaGuardTest {
    private fun pedido(reason: String = "\"Invitación\"") = """{"total":11000,"items":[{"externalId":"linea-comp","isCortesia":true,"cortesiaReason":$reason}]}"""
    private fun respuesta(total: String = "110", reason: String = "\"Invitación\"") = """{"data":{"id":"orden-A","total":$total,"items":[{"externalId":"linea-comp","isCortesia":true,"cortesiaReason":$reason}]}}"""

    @Test fun `flags exactos no autorizan abono si total canonico supera lo previsto`() {
        assertFalse(CortesiaPersistida.confirmada(pedido(),respuesta("220")))
    }
    @Test fun `cuerpo durable sin razon positiva no prueba la cortesia original`() {
        for(reason in listOf("null","\"\"","\"   \"")) assertFalse(reason,CortesiaPersistida.confirmada(pedido(reason),respuesta(reason=reason)))
    }
    @Test fun `precio canonico menor conserva contrato y razon recortada`() {
        assertTrue(CortesiaPersistida.confirmada(pedido("\"  Invitación  \""),respuesta("100")))
    }
}
