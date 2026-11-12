package com.avoqado.pos.tables.presentation

import com.avoqado.pos.core.data.sync.FilaDeLaOrden
import com.avoqado.pos.core.data.sync.FotoDeMesa
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.tables.data.AppliedOrderDiscount
import com.avoqado.pos.tables.data.AppliedServiceCharge
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.OrderDetailItem
import com.avoqado.pos.tables.data.OrderDetailPayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D5 · B2b: la fórmula pura del saldo sin red de una mesa del SERVIDOR (foto + rondas posteriores − efectivo posterior) y sus grises. */
class SaldoDeMesaDelServidorTest {
    private fun item(cents: Int) = CartItem(type = CartItemType.CustomAmount, name = "Artículo", unitPrice = cents)
    private fun fila(id: String, tipo: String, seq: Long, estado: String = "PENDING", importe: Int? = null) = FilaDeLaOrden(id, tipo, estado, importe, seq)
    /** Foto de $100 pedida con la cola en la secuencia 10; la cuenta trae la ronda vieja «r0» (`sync:r0:0`). */
    private val foto = FotoDeMesa("v1", "order-F", emptyList(), "table-F", OrderDetail("order-F", total = 100.0,
        items = listOf(OrderDetailItem("i0", total = 100.0, externalId = "sync:r0:0"))), seq = 10, llavesPrevias = listOf("r0"), tomadaEn = 1)

    private fun saldo(rondas: Map<String, List<CartItem>> = emptyMap(), filas: List<FilaDeLaOrden> = emptyList(), foto: FotoDeMesa? = this.foto,
        recuperando: Boolean = false, tomadaAqui: Boolean = true, ultima: OrderDetail? = foto?.detalle, fuera: Boolean = false) =
        saldoDeMesaDelServidor(foto, rondas, filas, recuperando, tomadaAqui, ultima, fuera)

    @Test fun `27b foto mas ronda sin red menos el efectivo`() {
        val r = mapOf("r1" to listOf(item(2000)))
        assertEquals(SaldoSinRed.Conocido(12000, 0, hayCobro = false), saldo(r, listOf(fila("r1", "ADD_ITEMS", 11))))
        val cobrada = saldo(r, listOf(fila("r1", "ADD_ITEMS", 11), fila("c1", "PAY_CASH", 12, importe = 12000)))
        assertEquals(SaldoSinRed.Conocido(12000, 12000, hayCobro = true), cobrada)
        assertEquals(0, (cobrada as SaldoSinRed.Conocido).porPagarCents)
    }

    @Test fun `ronda posterior ya confirmada suma y el efectivo resta en cualquier estado`() {
        val r = mapOf("r1" to listOf(item(2000)), "r2" to listOf(item(500)))
        assertEquals(SaldoSinRed.Conocido(12500, 7000, hayCobro = true), saldo(r, listOf(fila("r1", "ADD_ITEMS", 11, "ACKED"),
            fila("r2", "ADD_ITEMS", 12, "HELD"), fila("c1", "PAY_CASH", 13, "ACKED", 3000), fila("c2", "PAY_CASH", 14, "PENDING", 4000))))
    }

    @Test fun `una ronda posterior que no se puede valorar con certeza deja la mesa en gris`() {
        // Sin borrador.
        assertEquals(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO), saldo(emptyMap(), listOf(fila("r1", "ADD_ITEMS", 11))))
        // Vieja re-encolada (A3): su llave ya existía al tomar la foto.
        assertEquals(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO), saldo(mapOf("r0" to listOf(item(100))), listOf(fila("r0", "ADD_ITEMS", 11))))
        // Con un renglón ya presente en la foto (la lectura alcanzó a verla).
        val vista = foto.copy(detalle = foto.detalle.copy(items = foto.detalle.items + OrderDetailItem("i1", externalId = "sync:r1:0")))
        assertEquals(SaldoSinRed.EnGris(SALDO_NO_RECUPERADO), saldo(mapOf("r1" to listOf(item(2000))), listOf(fila("r1", "ADD_ITEMS", 11)), vista))
    }

    @Test fun `r4 #3 borrador sin fila o recuperacion en curso queda en gris, la ronda vieja de la foto no`() {
        assertEquals(SaldoSinRed.EnGris(CUENTA_SE_ACTUALIZA), saldo(mapOf("r1" to listOf(item(2000)))))
        assertEquals(SaldoSinRed.EnGris(CUENTA_SE_ACTUALIZA), saldo(mapOf("r1" to listOf(item(2000))), listOf(fila("r1", "ADD_ITEMS", 11)), recuperando = true))
        // El borrador de una ronda que la foto ya contiene (sus renglones están en la cuenta) no es «sin fila».
        assertEquals(SaldoSinRed.Conocido(10000, 0, hayCobro = false), saldo(mapOf("r0" to listOf(item(10000)))))
    }

    @Test fun `rechazo, otro cambio sin subir, cobro en otra cola o algo previo sin subir dejan la mesa en gris`() {
        val r = mapOf("r1" to listOf(item(2000)))
        assertEquals(SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR), saldo(r, listOf(fila("r1", "ADD_ITEMS", 11, "REJECTED"))))
        assertEquals(SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR), saldo(r, listOf(fila("r1", "ADD_ITEMS", 11), fila("d1", "APPLY_DISCOUNT", 12))))
        assertEquals(SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR), saldo(r, listOf(fila("r1", "ADD_ITEMS", 11)), fuera = true))
        assertEquals(SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR), saldo(r, listOf(fila("r1", "ADD_ITEMS", 11), fila("c0", "PAY_CASH", 9, importe = 1))))
        assertEquals(SaldoSinRed.EnGris(TOMADA_EN_OTRA_TABLET), saldo(r, listOf(fila("r1", "ADD_ITEMS", 11)), tomadaAqui = false))
    }

    @Test fun `sin foto dice la razon concreta`() {
        assertEquals(SaldoSinRed.EnGris(COBRO_AQUI_SIN_SUBIR), saldo(foto = null, filas = listOf(fila("c1", "PAY_CASH", 12, importe = 100))))
        assertEquals(SaldoSinRed.EnGris(TOMADA_EN_OTRA_TABLET), saldo(foto = null, tomadaAqui = false))
        val conDescuento = OrderDetail("order-F", total = 90.0, discounts = listOf(AppliedOrderDiscount("d", "10 %", 10.0)), discountAmount = 10.0)
        assertEquals(SaldoSinRed.EnGris(motivoCuentaNoSimple(conDescuento)!!), saldo(foto = null, ultima = conDescuento))
        assertEquals(SaldoSinRed.EnGris(SIN_CUENTA_COMPLETA), saldo(foto = null, ultima = OrderDetail("order-F", total = 100.0)))
    }

    @Test fun `decision A la foto solo vale si es la ultima cuenta leida (huella), y una relectura identica no la deja gris`() {
        val r = mapOf("r1" to listOf(item(2000)))
        val filas = listOf(fila("r1", "ADD_ITEMS", 11))
        val conVersion = foto.copy(detalle = foto.detalle.copy(version = 3))
        // Relectura idéntica (otro objeto, misma versión): vale.
        assertEquals(SaldoSinRed.Conocido(12000, 0, hayCobro = false), saldo(r, filas, conVersion, ultima = conVersion.detalle.copy(customerName = null)))
        // Sin versión (servidor viejo): mismos renglones en otro orden, misma huella.
        val dos = foto.detalle.copy(items = foto.detalle.items + OrderDetailItem("i9", total = 0.0))
        assertEquals(huellaDeCuenta(dos), huellaDeCuenta(dos.copy(items = dos.items.reversed())))
        // Codex b2b-r2 #1: después leyó la cuenta con un descuento del panel (versión 4) y no pudo borrar la foto ⇒ gris con su razón.
        val conDescuento = conVersion.detalle.copy(version = 4, total = 80.0, discounts = listOf(AppliedOrderDiscount("d")), discountAmount = 20.0)
        assertEquals(SaldoSinRed.EnGris(motivoCuentaNoSimple(conDescuento)!!), saldo(r, filas, conVersion, ultima = conDescuento))
        // Otra versión aunque siga simple (otra tablet le cambió algo y la lectura no pudo volver a fotografiar) ⇒ gris.
        assertEquals(SaldoSinRed.EnGris(SIN_CUENTA_COMPLETA), saldo(r, filas, conVersion, ultima = conVersion.detalle.copy(version = 5, total = 130.0)))
        // Sin la última cuenta guardada, tampoco.
        assertEquals(SaldoSinRed.EnGris(SIN_CUENTA_COMPLETA), saldo(r, filas, conVersion, ultima = null))
    }

    @Test fun `la cuenta simple es la que no tiene abonos, descuento de cuenta, cargo ni IVA aparte`() {
        assertNull(motivoCuentaNoSimple(OrderDetail("o", total = 100.0, items = listOf(OrderDetailItem("i", total = 100.0)))))
        for (cuenta in listOf(
            OrderDetail("o", total = 100.0, payments = listOf(OrderDetailPayment(id = "p", amount = 10.0, status = "COMPLETED"))),
            OrderDetail("o", total = 90.0, discounts = listOf(AppliedOrderDiscount("d"))),
            OrderDetail("o", total = 90.0, discountAmount = 10.0),
            OrderDetail("o", total = 110.0, serviceChargeAmount = 10.0, serviceCharges = listOf(AppliedServiceCharge("s"))),
            OrderDetail("o", total = 116.0, addedTaxAmount = 16.0),
            OrderDetail("o", total = 100.0, status = "CANCELLED"),
            OrderDetail("o", total = 100.0, paymentStatus = "PAID"),
        )) assertEquals("$cuenta", true, motivoCuentaNoSimple(cuenta) != null)
    }
}
