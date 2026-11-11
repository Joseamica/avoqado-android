package com.avoqado.pos.payment

import com.avoqado.pos.core.data.lan.LeaseOutcome
import com.avoqado.pos.core.data.lan.TableLease
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.orders.data.CuentaGuardada
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.payment.domain.DatosDelCobroSinRed
import com.avoqado.pos.payment.domain.DecisionSinRed
import com.avoqado.pos.payment.domain.DecisionSinRed.Cobrar
import com.avoqado.pos.payment.domain.DecisionSinRed.Gris
import com.avoqado.pos.payment.domain.TextosDeCuentaSinRed
import com.avoqado.pos.payment.domain.decidirCobroSinRed
import com.avoqado.pos.payment.domain.llaveDeCuenta
import org.junit.Test
import org.junit.Assert.assertEquals

class CobroSinRedTest {
    private fun fila(
        saldo: Int? = 6000, pagos: Int? = 0, estado: String = "PENDING", pago: String = "PENDING", regalo: Int? = 0,
    ) = CuentaGuardada(
        OrderSummary(
            id = "o1", esDeMesa = false, source = "AVOQADO_ANDROID", status = estado, paymentStatus = pago,
            saldoPendienteCents = saldo, pagosCompletados = pagos, cobrosConSaldo = regalo,
        ),
        vistaEn = 1L,
    )

    private fun datos(
        cuenta: CuentaGuardada? = fila(), puedeCobrar: Boolean = true, abiertaAqui: Boolean = true,
        cobroLocalPendiente: Boolean = false, huboEfectivoSubido: Boolean = false, premiumAcreditado: Boolean = true,
        hayOtraCajaDelNegocio: Boolean = true, hubEncendido: Boolean = false,
    ) = DatosDelCobroSinRed(
        cuenta, puedeCobrar, abiertaAqui, cobroLocalPendiente, huboEfectivoSubido, premiumAcreditado,
        hayOtraCajaDelNegocio, hubEncendido,
    )

    private fun decide(d: DatosDelCobroSinRed, c: LeaseOutcome? = null): DecisionSinRed = decidirCobroSinRed(d, c)

    @Test fun `la abrio aqui se cobra con el saldo de la copia en cualquier plan`() {
        assertEquals(Cobrar(6000, pedirCandado = false), decide(datos(premiumAcreditado = false, hayOtraCajaDelNegocio = false)))
    }

    @Test fun `la abrio aqui y otra caja tiene el candado avisa sin bloquear`() {
        assertEquals(
            Cobrar(6000, true, "Ana la está cobrando. ¿Cobrar aquí de todos modos?"),
            decide(datos(hubEncendido = true), LeaseOutcome.Taken("Ana")),
        )
    }

    @Test fun `la abrio aqui y el hub no contesta se cobra igual`() {
        assertEquals(Cobrar(6000, true), decide(datos(hubEncendido = true), LeaseOutcome.NoHub))
    }

    @Test fun `cada razon de la fila da su gris aunque la abrio aqui`() {
        assertEquals(Gris("Sin conexión: para cobrar esta cuenta necesitas internet"), decide(datos(fila(saldo = null))))
        assertEquals(Gris("Sin conexión: las tarjetas de regalo necesitan internet"), decide(datos(fila(regalo = 1))))
        assertEquals(
            Gris("Esta cuenta ya tiene un abono: cóbrala con internet."),
            decide(datos(fila(pagos = 1, pago = "PARTIAL"))),
        )
        assertEquals(Gris(TextosDeCuentaSinRed.SIN_SALDO), decide(datos(fila(saldo = 0))))
    }

    @Test fun `sin cuenta o cuenta cerrada es gris de internet`() {
        val g = Gris(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED)
        assertEquals(g, decide(datos(cuenta = null)))
        assertEquals(g, decide(datos(cuenta = fila(estado = "CANCELLED"))))
    }

    @Test fun `un cobro local pendiente gana`() {
        assertEquals(Gris(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR), decide(datos(cobroLocalPendiente = true)))
    }

    @Test fun `si este aparato ya subio un cobro de la cuenta queda en gris`() {
        assertEquals(Gris(TextosDeCuentaSinRed.YA_SUBIO_UN_COBRO), decide(datos(huboEfectivoSubido = true)))
    }

    @Test fun `sin permiso`() {
        assertEquals(Gris(TextosDeCuentaSinRed.SIN_PERMISO), decide(datos(puedeCobrar = false)))
    }

    @Test fun `otra caja sin PREMIUM acreditado queda en gris`() {
        assertEquals(
            Gris(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA),
            decide(datos(abiertaAqui = false, premiumAcreditado = false, hayOtraCajaDelNegocio = true)),
        )
    }

    @Test fun `otra caja PREMIUM sin otra caja del negocio queda en gris aunque el hub diga Granted`() {
        val granted = LeaseOutcome.Granted(TableLease("cuenta:o1", "d1", "s1", "Yo", 1L, 99L))
        assertEquals(
            Gris(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA),
            decide(datos(abiertaAqui = false, hayOtraCajaDelNegocio = false), granted),
        )
    }

    @Test fun `otra caja PREMIUM antes del toque ofrece cobrar y pide candado`() {
        val d = datos(abiertaAqui = false)
        assertEquals(Cobrar(6000, true), decide(d, null))
        assertEquals(Cobrar(6000, true), decide(d, LeaseOutcome.Granted(TableLease("cuenta:o1", "d1", "s1", "Yo", 1L, 99L))))
        assertEquals(Gris("La está cobrando Ana"), decide(d, LeaseOutcome.Taken("Ana")))
        assertEquals(Gris(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA), decide(d, LeaseOutcome.NoHub))
    }

    @Test fun `la copia que no es vigente llega como cuenta nula y queda en gris aunque la abrio aqui`() {
        assertEquals(Gris(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED), decide(datos(cuenta = null, abiertaAqui = true)))
    }

    @Test fun `llave del candado`() {
        assertEquals("cuenta:o1", llaveDeCuenta("o1"))
    }
}
