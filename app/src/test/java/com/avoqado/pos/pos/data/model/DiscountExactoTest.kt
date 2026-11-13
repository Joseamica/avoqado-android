package com.avoqado.pos.pos.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** El descuento de cuenta al centavo exacto: la misma tabla que el SERVIDOR (`calcularDescuentoExactoCents`) y la TPV. */
class DiscountExactoTest {
    private fun d(tipo: String, valor: Double, tope: Double? = null) =
        Discount(id = "d", name = "d", value = valor, type = tipo, maxDiscountAmount = tope)

    @Test fun `2135 centavos al 10 por ciento da 214`() = assertEquals(214, d("PERCENTAGE", 10.0).calculateDiscount(2135))
    @Test fun `375 centavos al 9_2 por ciento da 35`() = assertEquals(35, d("PERCENTAGE", 9.2).calculateDiscount(375))
    @Test fun `10000 al 10 por ciento con tope de 5 pesos da 500`() = assertEquals(500, d("PERCENTAGE", 10.0, 5.0).calculateDiscount(10000))
    @Test fun `fijo de 50 pesos sobre 3000 centavos da 3000`() = assertEquals(3000, d("FIXED", 50.0).calculateDiscount(3000))
    @Test fun `base cero da cero`() = assertEquals(0, d("PERCENTAGE", 10.0).calculateDiscount(0))
    @Test fun `9999 al 33_33 por ciento da 3333`() = assertEquals(3333, d("PERCENTAGE", 33.33).calculateDiscount(9999))
}
