package com.avoqado.pos.pos.presentation.checkout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que lee la pistola o la cámara sólo entra a la venta con el cobro cerrado: con el cobro abierto, el carrito se vacía
 * al confirmarlo y lo escaneado se perdía sin cobrarse (pendiente del 19-oct). Igual que iOS (`CheckoutView`, `guard !showPaymentFlow`).
 */
class CodigoEntraALaVentaTest {
    @Test fun `con el cobro cerrado el codigo entra`() = assertTrue(codigoEntraALaVenta(cobroAbierto = false, emitiendoVale = false))

    @Test fun `P1 con el cobro abierto el codigo no entra`() = assertFalse(codigoEntraALaVenta(cobroAbierto = true, emitiendoVale = false))

    @Test fun `mientras se emite un vale el codigo no entra`() = assertFalse(codigoEntraALaVenta(cobroAbierto = false, emitiendoVale = true))
}
