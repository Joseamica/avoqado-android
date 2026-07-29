package com.avoqado.pos.core.domain.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La tabla de verdad de §5.6, completa y explícita. Son 3 modos × 2 momentos = 6 casos, y los 6
 * están escritos: una tabla a medias es cómo se cuela un área que nunca imprime.
 */
class AreaComandaPolicyTest {

    @Test
    fun `IMMEDIATE imprime al emitir el vale y no al regresar pagado`() {
        assertTrue(AreaComandaPolicy.shouldPrint(FulfillmentMode.IMMEDIATE, ComandaMoment.AREA_TICKET_ISSUED))
        assertFalse(AreaComandaPolicy.shouldPrint(FulfillmentMode.IMMEDIATE, ComandaMoment.AREA_TICKET_PAID))
    }

    @Test
    fun `HOLD_UNTIL_PAID imprime al emitir el vale y no al regresar pagado`() {
        assertTrue(AreaComandaPolicy.shouldPrint(FulfillmentMode.HOLD_UNTIL_PAID, ComandaMoment.AREA_TICKET_ISSUED))
        assertFalse(AreaComandaPolicy.shouldPrint(FulfillmentMode.HOLD_UNTIL_PAID, ComandaMoment.AREA_TICKET_PAID))
    }

    @Test
    fun `PREPARE_ON_PAID no imprime al emitir el vale y si al regresar pagado`() {
        assertFalse(AreaComandaPolicy.shouldPrint(FulfillmentMode.PREPARE_ON_PAID, ComandaMoment.AREA_TICKET_ISSUED))
        assertTrue(AreaComandaPolicy.shouldPrint(FulfillmentMode.PREPARE_ON_PAID, ComandaMoment.AREA_TICKET_PAID))
    }

    /** Ningún modo se queda sin momento: si alguno no imprimiera nunca, el área jamás se enteraría
     *  de un pedido y nadie lo notaría hasta el reclamo. */
    @Test
    fun `todos los modos imprimen en exactamente un momento`() {
        for (mode in FulfillmentMode.entries) {
            val momentos = ComandaMoment.entries.filter { AreaComandaPolicy.shouldPrint(mode, it) }
            assertEquals("$mode debería imprimir en exactamente un momento", 1, momentos.size)
        }
    }

    // MARK: - Espejo del enum del server

    @Test
    fun `fromServer mapea los tres modos por nombre exacto`() {
        assertEquals(FulfillmentMode.IMMEDIATE, FulfillmentMode.fromServer("IMMEDIATE"))
        assertEquals(FulfillmentMode.HOLD_UNTIL_PAID, FulfillmentMode.fromServer("HOLD_UNTIL_PAID"))
        assertEquals(FulfillmentMode.PREPARE_ON_PAID, FulfillmentMode.fromServer("PREPARE_ON_PAID"))
        assertEquals(FulfillmentMode.HOLD_UNTIL_PAID, FulfillmentMode.fromServer(" hold_until_paid "))
    }

    /** Un server más nuevo, un campo vacío o un dato sucio caen a IMMEDIATE: se imprime. Lo
     *  contrario ("no sé, no imprimo") deja al área sin comanda por culpa de un string. */
    @Test
    fun `fromServer cae a IMMEDIATE ante lo desconocido`() {
        assertEquals(FulfillmentMode.IMMEDIATE, FulfillmentMode.fromServer(null))
        assertEquals(FulfillmentMode.IMMEDIATE, FulfillmentMode.fromServer(""))
        assertEquals(FulfillmentMode.IMMEDIATE, FulfillmentMode.fromServer("MODO_QUE_NO_EXISTE"))
    }
}
