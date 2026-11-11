package com.avoqado.pos.cashdrawer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccesoAlRetiroTest {
    @Test fun `quien puede retirar va directo, con o sin PIN y con o sin red`() {
        for (pin in listOf(true, false)) for (red in listOf(true, false))
            assertEquals(AccesoAlRetiro.DIRECTO, accesoAlRetiro(true, pin, red))
    }

    @Test fun `sin permiso y con PIN de gerente activo, con red pide PIN y sin red avisa`() {
        assertEquals(AccesoAlRetiro.CON_PIN, accesoAlRetiro(false, true, true))
        assertEquals(AccesoAlRetiro.SIN_RED, accesoAlRetiro(false, true, false))
    }

    @Test fun `sin permiso y sin PIN de gerente activo es solo para gerente`() {
        assertEquals(AccesoAlRetiro.SOLO_GERENTE, accesoAlRetiro(false, false, true))
        assertEquals(AccesoAlRetiro.SOLO_GERENTE, accesoAlRetiro(false, false, false))
    }

    @Test fun `con lista vigente manda la lista, tambien vacia`() {
        assertFalse(puedeRetirar(true, false, "MANAGER"))
        assertTrue(puedeRetirar(true, true, "CASHIER"))
    }

    @Test fun `con lista de origen desconocido vale la lista o el rol`() {
        assertTrue(puedeRetirar(false, false, "OWNER"))
        assertFalse(puedeRetirar(false, false, "CASHIER"))
        assertFalse(puedeRetirar(false, false, null))
        assertTrue(puedeRetirar(false, true, "CASHIER"))
    }
}
