package com.avoqado.pos.pos.data

import com.avoqado.pos.pos.data.DiscountsRepository.CouponResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 D5 (5-nov): en producción había 8 cupones activos y 0 canjes. Una causa vivía aquí: la app leía `coupon` en la raíz y el SERVIDOR lo
 * manda dentro de `data`, con el descuento anidado, así que todo cupón válido salía «Cupon no valido».
 *
 * Los cuerpos de abajo son la respuesta REAL de `POST /mobile/venues/:id/coupons/validate`, fijada del lado del SERVIDOR por
 * `tests/unit/controllers/mobile/coupon.validate.test.ts`.
 */
class CuponDelServidorTest {

    private val valido = """
        {"success":true,"data":{"valid":true,"coupon":{"id":"cup-1","code":"DESC10","discount":
        {"id":"d-1","name":"Diez","type":"PERCENTAGE","value":10,"scope":"ORDER","maxDiscountAmount":50}}}}
    """.trimIndent()

    private fun invalido(error: String, errorCode: String) = """{"success":true,"data":{"valid":false,"error":"$error","errorCode":"$errorCode"}}"""

    @Test
    fun `P1 un cupon valido se lee como valido, con su codigo y su tope`() {
        val r = leerValidacionDeCupon(200, valido) as CouponResult.Valid
        assertEquals("cup-1", r.coupon.id)
        assertEquals("DESC10", r.coupon.code)
        assertEquals("Diez", r.coupon.name)
        assertEquals("PERCENTAGE", r.coupon.type)
        assertEquals(10.0, r.coupon.value, 0.0)
        assertEquals(50.0, r.coupon.maxDiscountAmount!!, 0.0)
    }

    @Test
    fun `P1 aplicado al carrito baja el total, respeta el tope y lleva el codigo a la venta`() {
        val descuento = (leerValidacionDeCupon(200, valido) as CouponResult.Valid).coupon.toDiscount()
        assertEquals("DESC10", descuento.couponCode)
        assertEquals("Diez (DESC10)", descuento.name)
        assertEquals("ORDER", descuento.scope)
        assertEquals(1000, descuento.calculateDiscount(10000))
        // 10 % de $600 serían $60: el tope del catálogo deja $50, igual que el SERVIDOR.
        assertEquals(5000, descuento.calculateDiscount(60000))
    }

    @Test
    fun `P1 el mismo redondeo que el servidor`() {
        val descuento = (leerValidacionDeCupon(200, valido) as CouponResult.Valid).coupon.toDiscount()
        // El SERVIDOR calcula en centavos (`calculateDiscountCents`): 2135 al 10 % son 214.
        assertEquals(214, descuento.calculateDiscount(2135))
    }

    @Test
    fun `P1 cada rechazo dice su razon, no un cupon no valido generico`() {
        val casos = listOf(
            invalido("Este cliente ya usó este cupón 1 vez.", "CUSTOMER_LIMIT"),
            invalido("Este cupón ya venció.", "EXPIRED"),
            invalido("Este cupón ya alcanzó su límite de usos.", "USAGE_LIMIT"),
            invalido("Este cupón pide una compra mínima de \$150.00.", "MIN_PURCHASE"),
            invalido("No encontramos ese código de cupón.", "NOT_FOUND"),
        )
        val razones = casos.map { (leerValidacionDeCupon(200, it) as CouponResult.Invalid).reason }
        assertEquals(
            listOf(
                "Este cliente ya usó este cupón 1 vez.",
                "Este cupón ya venció.",
                "Este cupón ya alcanzó su límite de usos.",
                "Este cupón pide una compra mínima de \$150.00.",
                "No encontramos ese código de cupón.",
            ),
            razones,
        )
    }

    @Test
    fun `un monto fijo del catalogo se lee como fijo`() {
        val r = leerValidacionDeCupon(
            200,
            """{"success":true,"data":{"valid":true,"coupon":{"id":"c","code":"SAVE10","discount":
               {"id":"d","name":"Quince","type":"FIXED_AMOUNT","value":15,"scope":"ORDER","maxDiscountAmount":null}}}}""",
        ) as CouponResult.Valid
        assertNull(r.coupon.maxDiscountAmount)
        assertEquals(1500, r.coupon.toDiscount().calculateDiscount(10000))
        assertEquals(800, r.coupon.toDiscount().calculateDiscount(800))
    }

    @Test
    fun `una cortesia del catalogo regala toda la base`() {
        val r = leerValidacionDeCupon(
            200,
            """{"success":true,"data":{"valid":true,"coupon":{"id":"c","code":"GRATIS","discount":
               {"id":"d","name":"Gratis","type":"COMP","value":0,"scope":"ORDER","maxDiscountAmount":null}}}}""",
        ) as CouponResult.Valid
        assertEquals(10000, r.coupon.toDiscount().calculateDiscount(10000))
    }

    @Test
    fun `un cupon por producto se rechaza con su razon en vez de rebajar toda la cuenta`() {
        val r = leerValidacionDeCupon(
            200,
            """{"success":true,"data":{"valid":true,"coupon":{"id":"c","code":"CAFE","discount":
               {"id":"d","name":"Café","type":"PERCENTAGE","value":10,"scope":"ITEM","maxDiscountAmount":null}}}}""",
        )
        assertEquals(CouponResult.Invalid(CUPON_NO_ES_DE_CUENTA), r)
    }

    @Test
    fun `P1 un portal cautivo o un 500 no dicen que el cupon no sirve`() {
        assertEquals(CouponResult.Error(CUPON_NO_SE_PUDO_VALIDAR), leerValidacionDeCupon(200, "<html>Inicia sesión en el WiFi</html>"))
        assertEquals(CouponResult.Error(CUPON_NO_SE_PUDO_VALIDAR), leerValidacionDeCupon(200, """{"success":true}"""))
        assertEquals(CouponResult.Error(CUPON_NO_SE_PUDO_VALIDAR), leerValidacionDeCupon(500, """{"success":false,"message":"Error al validar cupon"}"""))
        assertEquals(CouponResult.Error(CUPON_NO_SE_PUDO_VALIDAR), leerValidacionDeCupon(502, "Bad Gateway"))
    }

    @Test
    fun `un 403 enseña lo que dijo el servidor`() {
        val r = leerValidacionDeCupon(403, """{"success":false,"message":"Permission 'coupons:redeem' required"}""")
        assertTrue(r is CouponResult.Error && r.message.contains("coupons:redeem"))
    }

    @Test
    fun `el texto de sin conexion es honesto, no dice que el cupon sea invalido`() {
        assertTrue(CUPON_SIN_RED_AL_VALIDAR.startsWith("Sin conexión"))
    }
}
