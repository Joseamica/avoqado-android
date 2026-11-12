package com.avoqado.pos.payment

import com.avoqado.pos.core.data.lan.CuentaEnElRegistro
import com.avoqado.pos.core.data.lan.CuentasEntreCajas
import com.avoqado.pos.core.data.lan.EstadoDeCuenta
import com.avoqado.pos.core.data.lan.Instante
import com.avoqado.pos.core.data.lan.Respuesta
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.orders.data.CuentaGuardada
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.payment.domain.DatosDelCobroSinRed
import com.avoqado.pos.payment.domain.DecisionSinRed
import com.avoqado.pos.payment.domain.DecisionSinRed.Cobrar
import com.avoqado.pos.payment.domain.DecisionSinRed.Gris
import com.avoqado.pos.payment.domain.DecisionSinRed.NoConfirmo
import com.avoqado.pos.payment.domain.TextosDeCuentaSinRed
import com.avoqado.pos.payment.domain.decidirCobroSinRed
import com.avoqado.pos.payment.domain.llaveDeCuenta
import org.junit.Assert.assertEquals
import org.junit.Test

/** D12 · Opción D: quién cobra sin red una cuenta abierta. El permiso lo da la caja que la ABRIÓ; los textos son los oficiales. */
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
        hayOtraCajaDelNegocio: Boolean = true, registro: CuentaEnElRegistro? = null, puedeDeclarar: Boolean = false,
        cajaQueLaAbrio: String? = null,
    ) = DatosDelCobroSinRed(
        cuenta, puedeCobrar, abiertaAqui, cobroLocalPendiente, huboEfectivoSubido, premiumAcreditado, hayOtraCajaDelNegocio,
        registro = registro, puedeDeclararNoSeCobro = puedeDeclarar, miDeviceId = "caja-1", cajaQueLaAbrio = cajaQueLaAbrio,
    )

    private fun decide(d: DatosDelCobroSinRed, p: CuentasEntreCajas.DelDueno? = null): DecisionSinRed = decidirCobroSinRed(d, p)
    private fun contesta(r: Respuesta?, caja: String? = "Caja 1") = CuentasEntreCajas.DelDueno(r, "caja-1", caja, 0L)
    private fun enRegistro(estado: EstadoDeCuenta, por: String = "caja-2", falta: Boolean = false) =
        CuentaEnElRegistro(estado, por, if (por == "caja-1") "Caja 1" else "Caja 2", "Ana", "i", Instante("b", 0), faltaSubirla = falta)

    // MARK: - Los textos oficiales (brief de la Opción D), tal cual

    @Test fun `los textos de la opción D dicen exactamente lo acordado`() {
        assertEquals("Avisándole a Caja 1…", TextosDeCuentaSinRed.avisandoA("Caja 1"))
        assertEquals("Ana la está cobrando en Caja 2. Espera a que termine.", TextosDeCuentaSinRed.laEstaCobrandoEn("Ana", "Caja 2"))
        assertEquals("Esta cuenta se abrió en Caja 1 y no la encuentro en la red. Cóbrala ahí o cuando vuelva la conexión.",
            TextosDeCuentaSinRed.noLaEncuentro("Caja 1"))
        assertEquals(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA, TextosDeCuentaSinRed.noLaEncuentro(null))
        assertEquals("Se perdió el contacto con Caja 1: esta cuenta no se cobró aquí. No recibas el efectivo. Cóbrala en Caja 1 o cuando vuelva la conexión.",
            TextosDeCuentaSinRed.sePerdioElContacto("Caja 1"))
        assertEquals("Ana empezó a cobrarla en Caja 2 y no confirmó. Pregúntale antes de cobrar.", TextosDeCuentaSinRed.noConfirmo("Ana", "Caja 2"))
        assertEquals("No se cobró: cobrar aquí", TextosDeCuentaSinRed.NO_SE_COBRO_COBRAR_AQUI)
        assertEquals("Cobrada en Caja 2", TextosDeCuentaSinRed.cobradaEn("Caja 2", faltaSubirla = false))
        assertEquals("Cobrada en Caja 2 · falta subirla", TextosDeCuentaSinRed.cobradaEn("Caja 2", faltaSubirla = true))
    }

    // MARK: - La caja que la abrió

    @Test fun `la abrio aqui se cobra con el saldo de la copia en cualquier plan`() {
        assertEquals(Cobrar(6000), decide(datos(premiumAcreditado = false, hayOtraCajaDelNegocio = false)))
    }

    @Test fun `la abrio aqui y su registro dice que otra caja la tiene, ya la cobró o no confirmó`() {
        assertEquals(Gris("Ana la está cobrando en Caja 2. Espera a que termine."), decide(datos(registro = enRegistro(EstadoDeCuenta.APARTADA))))
        assertEquals(Gris("Ana la está cobrando en Caja 2. Espera a que termine."), decide(datos(registro = enRegistro(EstadoDeCuenta.COMPROMETIDA))))
        assertEquals(NoConfirmo("Ana empezó a cobrarla en Caja 2 y no confirmó. Pregúntale antes de cobrar.", 6000),
            decide(datos(registro = enRegistro(EstadoDeCuenta.COMPROMETIDA), puedeDeclarar = true)))
        assertEquals(Gris("Cobrada en Caja 2 · falta subirla"), decide(datos(registro = enRegistro(EstadoDeCuenta.CERRADA, falta = true))))
        assertEquals(Gris("Cobrada en Caja 1"), decide(datos(registro = enRegistro(EstadoDeCuenta.CERRADA, por = "caja-1"))))
    }

    @Test fun `la abrio aqui y lo de su registro es suyo, se ofrece cobrar`() {
        assertEquals(Cobrar(6000), decide(datos(registro = enRegistro(EstadoDeCuenta.APARTADA, por = "caja-1"))))
    }

    @Test fun `ya no hay «cobrar aquí de todos modos» - la respuesta de su propio registro manda`() {
        assertEquals(Gris("Ana la está cobrando en Caja 2. Espera a que termine."),
            decide(datos(), contesta(Respuesta.Ocupada("Ana", "Caja 2"))))
        assertEquals(Cobrar(6000), decide(datos(), contesta(Respuesta.Concedida)))
    }

    // MARK: - Lo de siempre

    @Test fun `cada razon de la fila da su gris aunque la abrio aqui`() {
        assertEquals(Gris("Sin conexión: para cobrar esta cuenta necesitas internet"), decide(datos(fila(saldo = null))))
        assertEquals(Gris("Sin conexión: las tarjetas de regalo necesitan internet"), decide(datos(fila(regalo = 1))))
        assertEquals(Gris("Esta cuenta ya tiene un abono: cóbrala con internet."), decide(datos(fila(pagos = 1, pago = "PARTIAL"))))
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

    // MARK: - Otra caja: le pregunta a la que la abrió

    @Test fun `otra caja sin PREMIUM o sin otra caja con D queda en gris, con el nombre si se sabe`() {
        assertEquals(Gris(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA), decide(datos(abiertaAqui = false, premiumAcreditado = false)))
        assertEquals(Gris(TextosDeCuentaSinRed.noLaEncuentro("Caja 1")),
            decide(datos(abiertaAqui = false, hayOtraCajaDelNegocio = false, cajaQueLaAbrio = "Caja 1")))
    }

    @Test fun `otra caja PREMIUM - antes del toque ofrece cobrar, y luego manda lo que contestó la que la abrió`() {
        val d = datos(abiertaAqui = false)
        assertEquals(Cobrar(6000), decide(d))
        assertEquals(Cobrar(6000), decide(d, contesta(Respuesta.Concedida)))
        assertEquals(Gris("Ana la está cobrando en Caja 2. Espera a que termine."), decide(d, contesta(Respuesta.Ocupada("Ana", "Caja 2"))))
        assertEquals(Gris("Cobrada en Caja 2 · falta subirla"), decide(d, contesta(Respuesta.Cerrada("Caja 2", true))))
        assertEquals(Gris(TextosDeCuentaSinRed.noLaEncuentro("Caja 1")), decide(d, contesta(null)))
        assertEquals(Gris(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA), decide(d, contesta(null, caja = null)))
    }

    @Test fun `la copia que no es vigente llega como cuenta nula y queda en gris aunque la abrio aqui`() {
        assertEquals(Gris(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED), decide(datos(cuenta = null, abiertaAqui = true)))
    }

    @Test fun `llave de la cuenta`() {
        assertEquals("cuenta:o1", llaveDeCuenta("o1"))
    }
}
