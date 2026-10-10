package com.avoqado.pos.orders

import com.avoqado.pos.orders.data.model.OrderDetail
import com.avoqado.pos.orders.presentation.ivaAgregadoDeLaOrden
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** IVA por producto, plan 7 (r2 R2-13): el detalle de orden sólo dice «IVA agregado» si la orden de verdad lo sumó aparte. */
class IvaAgregadoDeLaOrdenTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun orden(extra: String) = json.decodeFromString<OrderDetail>("""{"id":"o1","orderNumber":"42","taxAmount":16.0$extra}""")

    @Test
    fun `P1 contrato IVA_INCLUIDO con taxAmount escrito - sin fila de IVA agregado`() {
        assertNull(ivaAgregadoDeLaOrden(orden(",\"addedTaxAmount\":0.0")))
    }

    @Test
    fun `IVA aparte - la fila con su importe`() {
        assertEquals(16.0, ivaAgregadoDeLaOrden(orden(",\"addedTaxAmount\":16.0")))
    }

    @Test
    fun `un servidor anterior (sin el campo) nunca afirma un IVA agregado`() {
        assertNull(ivaAgregadoDeLaOrden(orden("")))
    }
}
