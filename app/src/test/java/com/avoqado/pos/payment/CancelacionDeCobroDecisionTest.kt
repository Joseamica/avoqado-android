package com.avoqado.pos.payment

import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import com.avoqado.pos.payment.domain.DesenlaceDeCancelacion
import com.avoqado.pos.payment.domain.PasoTrasBorrar
import com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La decisión que AUTORIZA a cancelar una orden después de cancelar su cobro con tarjeta.
 *
 * Es más estricta que [com.avoqado.pos.payment.domain.CardChargeDecision] a propósito: aquélla
 * lee cualquier FAILED como «no se cobró» (sirve para ofrecer cobrar otra vez), y ésta decide si
 * se BORRA una cuenta. Un FAILED sin evidencia, un CANCELLED por gracia o un 404 NO autorizan el
 * borrado: si la terminal sí cobró, la venta quedaría huérfana de su dinero (incidente del 11-sep:
 * el DELETE en paralelo al cancel dejó órdenes huérfanas en producción).
 */
class CancelacionDeCobroDecisionTest {

    private fun known(
        status: String,
        inProgress: Boolean = false,
        paymentId: String? = null,
        cancelDisposition: String? = null,
        outcome: String? = null,
        failureCode: String? = null,
    ) = ChargeStatusProbe.Known(
        status = status,
        inProgress = inProgress,
        paymentId = paymentId,
        cancelDisposition = cancelDisposition,
        outcome = outcome,
        failureCode = failureCode,
    )

    // MARK: - Con el campo canónico `outcome` (servidor nuevo)

    @Test
    fun `P1 outcome NOT_CHARGED autoriza a cancelar la orden`() {
        assertEquals(
            DesenlaceDeCancelacion.NoSeCobro,
            CancelacionDeCobro.decidir(known("FAILED", outcome = "NOT_CHARGED", failureCode = "PROCESSOR_DECLINED")),
        )
    }

    @Test
    fun `P1 outcome CHARGED es cobro aunque el status no lo diga`() {
        assertEquals(
            DesenlaceDeCancelacion.SeCobro("pay-1"),
            CancelacionDeCobro.decidir(known("UNKNOWN", paymentId = "pay-1", outcome = "CHARGED")),
        )
    }

    @Test
    fun `P1 outcome UNRESOLVED queda pendiente aunque el status diga CANCELLED ACCEPTED`() {
        // Con el campo presente manda el servidor: la regla vieja no se usa de respaldo.
        val decision = CancelacionDeCobro.decidir(
            known("CANCELLED", cancelDisposition = "ACCEPTED", outcome = "UNRESOLVED"),
        )
        assertTrue("$decision", decision is DesenlaceDeCancelacion.Pendiente)
    }

    @Test
    fun `un outcome desconocido NUNCA autoriza a borrar`() {
        val decision = CancelacionDeCobro.decidir(known("FAILED", outcome = "ALGO_NUEVO"))
        assertTrue("$decision", decision is DesenlaceDeCancelacion.Pendiente)
    }

    @Test
    fun `un outcome en minusculas o con espacios se lee igual`() {
        assertEquals(
            DesenlaceDeCancelacion.NoSeCobro,
            CancelacionDeCobro.decidir(known("FAILED", outcome = " not_charged ")),
        )
    }

    @Test
    fun `P1 un pago registrado gana sobre un outcome NOT_CHARGED contradictorio`() {
        // Si hay Payment, la orden jamás se borra: es el lado que no pierde dinero.
        assertEquals(
            DesenlaceDeCancelacion.SeCobro("pay-2"),
            CancelacionDeCobro.decidir(known("COMPLETED", paymentId = "pay-2", outcome = "NOT_CHARGED")),
        )
    }

    @Test
    fun `P1 en vuelo queda pendiente aunque traiga outcome NOT_CHARGED`() {
        val decision = CancelacionDeCobro.decidir(known("CANCEL_REQUESTED", inProgress = true, outcome = "NOT_CHARGED"))
        assertEquals(DesenlaceDeCancelacion.Pendiente(enVuelo = true), decision)
    }

    // MARK: - Sin el campo `outcome` (servidor viejo)

    @Test
    fun `P1 servidor viejo CANCELLED con ACCEPTED es no se cobro`() {
        assertEquals(
            DesenlaceDeCancelacion.NoSeCobro,
            CancelacionDeCobro.decidir(known("CANCELLED", cancelDisposition = "ACCEPTED")),
        )
    }

    @Test
    fun `P1 servidor viejo CANCELLED por gracia sin disposicion queda pendiente`() {
        val decision = CancelacionDeCobro.decidir(known("CANCELLED"))
        assertEquals(DesenlaceDeCancelacion.Pendiente(), decision)
    }

    @Test
    fun `P1 servidor viejo CANCELLED con disposicion ACTIVE queda pendiente`() {
        val decision = CancelacionDeCobro.decidir(known("CANCELLED", cancelDisposition = "ACTIVE"))
        assertEquals(DesenlaceDeCancelacion.Pendiente(), decision)
    }

    @Test
    fun `P1 servidor viejo FAILED sin evidencia queda pendiente`() {
        // Es justo lo que CardChargeDecision lee como «no se cobró»: aquí NO alcanza para borrar.
        assertEquals(DesenlaceDeCancelacion.Pendiente(), CancelacionDeCobro.decidir(known("FAILED")))
        assertEquals(
            DesenlaceDeCancelacion.Pendiente(),
            CancelacionDeCobro.decidir(known("FAILED", failureCode = "TPV_ERROR")),
        )
    }

    @Test
    fun `P1 servidor viejo FAILED con lapida de admision REJECTED es no se cobro`() {
        for (code in listOf("REJECTED_TERMINAL_NOT_CONNECTED", "REJECTED_TERMINAL_BUSY", "REJECTED_ORDER_CANCELLED")) {
            assertEquals(
                code,
                DesenlaceDeCancelacion.NoSeCobro,
                CancelacionDeCobro.decidir(known("FAILED", failureCode = code)),
            )
        }
    }

    @Test
    fun `una lapida REJECTED con status distinto de FAILED no alcanza`() {
        val decision = CancelacionDeCobro.decidir(known("UNKNOWN", failureCode = "REJECTED_TERMINAL_BUSY"))
        assertTrue("$decision", decision is DesenlaceDeCancelacion.Pendiente)
    }

    @Test
    fun `P1 COMPLETED con paymentId es cobro`() {
        assertEquals(
            DesenlaceDeCancelacion.SeCobro("pay-3"),
            CancelacionDeCobro.decidir(known("COMPLETED", paymentId = "pay-3")),
        )
    }

    @Test
    fun `COMPLETED sin paymentId queda pendiente`() {
        assertEquals(DesenlaceDeCancelacion.Pendiente(), CancelacionDeCobro.decidir(known("COMPLETED")))
        assertEquals(DesenlaceDeCancelacion.Pendiente(), CancelacionDeCobro.decidir(known("COMPLETED", paymentId = "  ")))
    }

    @Test
    fun `P1 TIMED_OUT y UNKNOWN quedan pendientes`() {
        assertEquals(DesenlaceDeCancelacion.Pendiente(), CancelacionDeCobro.decidir(known("TIMED_OUT")))
        assertEquals(DesenlaceDeCancelacion.Pendiente(), CancelacionDeCobro.decidir(known("UNKNOWN")))
    }

    @Test
    fun `P1 PENDING SENT y CANCEL_REQUESTED son en vuelo y piden reenviar el cancel`() {
        for (status in listOf("PENDING", "SENT", "CANCEL_REQUESTED")) {
            assertEquals(
                status,
                DesenlaceDeCancelacion.Pendiente(enVuelo = true),
                CancelacionDeCobro.decidir(known(status, inProgress = true)),
            )
            // Aunque el servidor mande inProgress=false por error, el status manda.
            assertEquals(
                status,
                DesenlaceDeCancelacion.Pendiente(enVuelo = true),
                CancelacionDeCobro.decidir(known(status, inProgress = false)),
            )
        }
    }

    @Test
    fun `P1 un 404 queda pendiente sin pedir red`() {
        assertEquals(DesenlaceDeCancelacion.Pendiente(sinFila = true), CancelacionDeCobro.decidir(ChargeStatusProbe.NotFound))
    }

    @Test
    fun `P1 sin red queda pendiente y lo dice`() {
        assertEquals(DesenlaceDeCancelacion.Pendiente(sinRed = true), CancelacionDeCobro.decidir(ChargeStatusProbe.Unreachable))
    }

    // MARK: - Rechazos de admisión correlacionados (H.5): «este cobro no se creó»

    @Test
    fun `P1 un rechazo de admision que nombra MI solicitud prueba que no se creo`() {
        val casos = mapOf(
            "TERMINAL_NOT_CONNECTED" to CancelacionDeCobro.RECHAZO_TERMINAL_NO_CONECTADA,
            "TERMINAL_NO_SOCKET" to CancelacionDeCobro.RECHAZO_TERMINAL_NO_CONECTADA,
            "TERMINAL_NOT_IN_VENUE" to CancelacionDeCobro.RECHAZO_TERMINAL_NO_CONECTADA,
            "ORDER_CANCELLED_NO_NEW_CHARGE" to CancelacionDeCobro.RECHAZO_CUENTA_CANCELADA,
            "ORDER_ALREADY_PAID" to CancelacionDeCobro.RECHAZO_CUENTA_PAGADA,
            "ORDER_NOT_FOUND" to CancelacionDeCobro.RECHAZO_CUENTA_INEXISTENTE,
        )
        for ((code, texto) in casos) {
            assertEquals(code, texto, CancelacionDeCobro.rechazoQueProbaNoSeCreo(code, "req-mio", "req-mio"))
        }
    }

    @Test
    fun `P1 un rechazo de admision que nombra OTRA solicitud no prueba nada`() {
        assertNull(CancelacionDeCobro.rechazoQueProbaNoSeCreo("TERMINAL_NOT_CONNECTED", "req-otro", "req-mio"))
    }

    @Test
    fun `P1 un rechazo de admision sin solicitud nombrada no prueba nada`() {
        assertNull(CancelacionDeCobro.rechazoQueProbaNoSeCreo("ORDER_CANCELLED_NO_NEW_CHARGE", null, "req-mio"))
        assertNull(CancelacionDeCobro.rechazoQueProbaNoSeCreo("ORDER_CANCELLED_NO_NEW_CHARGE", "  ", "req-mio"))
    }

    @Test
    fun `un codigo que no es de admision no prueba nada aunque nombre mi solicitud`() {
        assertNull(CancelacionDeCobro.rechazoQueProbaNoSeCreo("TERMINAL_BUSY", "req-mio", "req-mio"))
        assertNull(CancelacionDeCobro.rechazoQueProbaNoSeCreo(null, "req-mio", "req-mio"))
        assertNull(CancelacionDeCobro.rechazoQueProbaNoSeCreo(CancelacionDeCobro.ADMISSION_RETRY, "req-mio", "req-mio"))
    }

    // MARK: - Después del DELETE de la orden

    @Test
    fun `P1 cuenta cancelada o inexistente cierra la intencion`() {
        assertEquals(PasoTrasBorrar.Cerrar, CancelacionDeCobro.trasBorrar(ResultadoDeCancelarOrden.Cancelada, "req-mio"))
        assertEquals(PasoTrasBorrar.Cerrar, CancelacionDeCobro.trasBorrar(ResultadoDeCancelarOrden.NoExiste, "req-mio"))
    }

    @Test
    fun `P1 un 409 bloqueado por MI cobro vuelve a esperar el desenlace`() {
        assertEquals(
            PasoTrasBorrar.VolverAEsperar,
            CancelacionDeCobro.trasBorrar(ResultadoDeCancelarOrden.BloqueadaPorCobro("req-mio", null), "req-mio"),
        )
    }

    @Test
    fun `P1 un 409 bloqueado por OTRO cobro o sin solicitud se queda pendiente sin borrar`() {
        assertEquals(
            PasoTrasBorrar.Reintentar(sinRed = false),
            CancelacionDeCobro.trasBorrar(ResultadoDeCancelarOrden.BloqueadaPorCobro("req-otro", null), "req-mio"),
        )
        assertEquals(
            PasoTrasBorrar.Reintentar(sinRed = false),
            CancelacionDeCobro.trasBorrar(ResultadoDeCancelarOrden.BloqueadaPorCobro(null, null), "req-mio"),
        )
        // Intención sólo de orden (nunca hubo cobro): cualquier cobro que bloquee es de otro.
        assertEquals(
            PasoTrasBorrar.Reintentar(sinRed = false),
            CancelacionDeCobro.trasBorrar(ResultadoDeCancelarOrden.BloqueadaPorCobro("req-x", null), null),
        )
    }

    @Test
    fun `P1 una cuenta con dinero no se borra y la intencion se cierra con el motivo`() {
        val paso = CancelacionDeCobro.trasBorrar(
            ResultadoDeCancelarOrden.RechazoDeNegocio(400, null, "Cannot cancel a paid order"),
            "req-mio",
        )
        assertEquals(PasoTrasBorrar.CerrarConDinero("Cannot cancel a paid order"), paso)
    }

    @Test
    fun `un 409 sin codigo o un 403 se quedan pendientes`() {
        assertEquals(
            PasoTrasBorrar.Reintentar(sinRed = false),
            CancelacionDeCobro.trasBorrar(ResultadoDeCancelarOrden.RechazoDeNegocio(409, null, "Conflicto"), "req-mio"),
        )
        assertEquals(
            PasoTrasBorrar.Reintentar(sinRed = false),
            CancelacionDeCobro.trasBorrar(ResultadoDeCancelarOrden.RechazoDeNegocio(403, null, "Permission required"), "req-mio"),
        )
    }

    @Test
    fun `sin red o error del servidor se reintenta despues`() {
        assertEquals(PasoTrasBorrar.Reintentar(sinRed = true), CancelacionDeCobro.trasBorrar(ResultadoDeCancelarOrden.SinRed, "req-mio"))
        assertEquals(
            PasoTrasBorrar.Reintentar(sinRed = false),
            CancelacionDeCobro.trasBorrar(ResultadoDeCancelarOrden.ErrorDeServidor(503), "req-mio"),
        )
    }

    // MARK: - Textos (idénticos en Android e iOS)

    @Test
    fun `los textos de la cancelacion son los acordados palabra por palabra`() {
        assertEquals("Cancelando el cobro…", CancelacionDeCobro.TEXTO_CANCELANDO)
        assertEquals("Cancelación pendiente", CancelacionDeCobro.TITULO_PENDIENTE)
        assertEquals(
            "La terminal todavía no confirma que el cobro se detuvo. La venta se cancelará sola en cuanto conste que no se cobró.",
            CancelacionDeCobro.CUERPO_PENDIENTE,
        )
        assertEquals("Volver a consultar", CancelacionDeCobro.BOTON_VOLVER_A_CONSULTAR)
        assertEquals("Salir (queda pendiente)", CancelacionDeCobro.BOTON_SALIR_PENDIENTE)
        assertEquals("Cancelar la venta", CancelacionDeCobro.BOTON_CANCELAR_VENTA)
        assertEquals(
            "Sin conexión en esta tablet: la cancelación se enviará en cuanto vuelva la red. La terminal podría seguir mostrando el cobro.",
            CancelacionDeCobro.CUERPO_SIN_RED,
        )
        assertEquals("No se pudo guardar la cancelación en este equipo. Inténtalo de nuevo.", CancelacionDeCobro.NO_SE_PUDO_GUARDAR)
        assertEquals("La terminal sí cobró este pago: la venta queda pagada.", CancelacionDeCobro.SE_COBRO_AL_FINAL)
        assertEquals("Cancelaciones pendientes: 3", CancelacionDeCobro.bannerPendientes(3))
        assertEquals(
            "La terminal no está conectada. Este cobro NO se envió; no se cobró nada.",
            CancelacionDeCobro.RECHAZO_TERMINAL_NO_CONECTADA,
        )
        assertEquals("La cuenta está cancelada: este cobro NO se envió, no se cobró nada.", CancelacionDeCobro.RECHAZO_CUENTA_CANCELADA)
        assertEquals("La cuenta ya está pagada. Este cobro NO se envió.", CancelacionDeCobro.RECHAZO_CUENTA_PAGADA)
        assertEquals("La cuenta ya no existe. Este cobro NO se envió.", CancelacionDeCobro.RECHAZO_CUENTA_INEXISTENTE)
        assertEquals(
            "Hay un cobro con tarjeta en curso para esta cuenta: cancélalo o espera a que termine.",
            CancelacionDeCobro.COBRO_EN_CURSO_BLOQUEA_CUENTA,
        )
    }
}
