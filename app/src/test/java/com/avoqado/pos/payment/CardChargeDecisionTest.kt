package com.avoqado.pos.payment

import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import com.avoqado.pos.payment.domain.ChargeWaitEnding
import com.avoqado.pos.payment.domain.ProbeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Camino del DINERO. Esta es la decisión que evitó — y que si se rompe vuelve a producir — el
 * doble cobro medido el 2026-08-10 en un Sunmi D3: el backend se reinició a media espera, ngrok
 * devolvió 503, la app declaró "Error en el pago", el cajero tocó Reintentar y la tarjeta se
 * cobró DOS veces (dos órdenes, una sola venta).
 *
 * La regla: un fallo de TRANSPORTE no es un fallo de COBRO. Sólo el estado durable del server
 * decide, y cuando no se puede saber, se dice que no se sabe — nunca "falló".
 */
class CardChargeDecisionTest {

    // MARK: - ¿Qué es un fallo de transporte?

    @Test
    fun `los 5xx son fallos de transporte — el cobro pudo haber ocurrido`() {
        // 503 es EXACTAMENTE el del incidente (ngrok mientras el backend reiniciaba).
        assertTrue(CardChargeDecision.isTransportFailure(503))
        assertTrue(CardChargeDecision.isTransportFailure(500))
        assertTrue(CardChargeDecision.isTransportFailure(502))
        assertTrue(CardChargeDecision.isTransportFailure(504))
        // 408 = el proxy/servidor cortó la espera: mismo desconocimiento.
        assertTrue(CardChargeDecision.isTransportFailure(408))
    }

    @Test
    fun `los rechazos de negocio NO son fallos de transporte`() {
        // El server contestó de verdad: la terminal no está conectada / está ocupada.
        // Consta que NADIE pasó una tarjeta, así que estos siguen siendo error normal.
        assertFalse(CardChargeDecision.isTransportFailure(404))
        assertFalse(CardChargeDecision.isTransportFailure(409))
        assertFalse(CardChargeDecision.isTransportFailure(422))
        assertFalse(CardChargeDecision.isTransportFailure(400))
        assertFalse(CardChargeDecision.isTransportFailure(401))
        assertFalse(CardChargeDecision.isTransportFailure(200))
    }

    // MARK: - Desenlace 1: COBRÓ

    @Test
    fun `COMPLETED es cobro exitoso y conserva el paymentId`() {
        val decision = CardChargeDecision.decide(
            probe = ChargeStatusProbe.Known(status = "COMPLETED", inProgress = false, paymentId = "pay_1"),
            isFinalAttempt = false,
        )

        val outcome = (decision as ProbeDecision.Resolved).outcome
        assertEquals(CardChargeOutcome.Charged("pay_1"), outcome)
    }

    @Test
    fun `el escenario exacto del doble cobro — transporte 503 con la terminal YA cobrada`() {
        // 1) La espera larga murió con 503 → hay que ir a preguntar, no concluir nada.
        assertTrue(CardChargeDecision.isTransportFailure(503))

        // 2) El server SÍ registró el pago (la terminal reportó success mientras la app ya se había ido).
        val decision = CardChargeDecision.decide(
            probe = ChargeStatusProbe.Known(status = "COMPLETED", inProgress = false, paymentId = "pay_tarde"),
            isFinalAttempt = false,
        )

        // 3) El desenlace DEBE ser "cobró". Si esto vuelve a dar error, el cajero cobra dos veces.
        assertEquals(
            CardChargeOutcome.Charged("pay_tarde"),
            (decision as ProbeDecision.Resolved).outcome,
        )
    }

    // MARK: - Desenlace 2: NO COBRÓ (Reintentar es seguro)

    @Test
    fun `FAILED es rechazo real — no se cobró, se puede reintentar`() {
        val decision = CardChargeDecision.decide(
            probe = ChargeStatusProbe.Known(status = "FAILED", inProgress = false),
            isFinalAttempt = false,
        )

        val outcome = (decision as ProbeDecision.Resolved).outcome
        assertTrue(outcome is CardChargeOutcome.NotCharged)
    }

    @Test
    fun `CANCELLED tampoco cobró`() {
        val decision = CardChargeDecision.decide(
            probe = ChargeStatusProbe.Known(status = "CANCELLED", inProgress = false),
            isFinalAttempt = false,
        )

        assertTrue((decision as ProbeDecision.Resolved).outcome is CardChargeOutcome.NotCharged)
    }

    @Test
    fun `404 confirmado significa que la solicitud nunca existió — no se cobró`() {
        // El server nunca persistió la solicitud → la terminal jamás fue invocada.
        val decision = CardChargeDecision.decide(ChargeStatusProbe.NotFound, isFinalAttempt = true)

        assertTrue((decision as ProbeDecision.Resolved).outcome is CardChargeOutcome.NotCharged)
    }

    @Test
    fun `un 404 aislado NO se cree a la primera — puede ser un POST rezagado`() {
        // Declarar "no se cobró" con una sola lectura abre la puerta a cobrar encima de un
        // request que todavía venía en camino. Se vuelve a preguntar.
        val decision = CardChargeDecision.decide(ChargeStatusProbe.NotFound, isFinalAttempt = false)

        assertEquals(ProbeDecision.KeepPolling, decision)
    }

    // MARK: - Desenlace 3: NO SE SABE (jamás ofrecer un reintento a ciegas)

    @Test
    fun `si no se puede consultar al server hasta el final, el desenlace es indeterminado`() {
        val decision = CardChargeDecision.decide(ChargeStatusProbe.Unreachable, isFinalAttempt = true)

        val outcome = (decision as ProbeDecision.Resolved).outcome
        assertTrue(outcome is CardChargeOutcome.Undetermined)
        // El texto es el que ve el cajero: ni éxito ni fracaso, y le dice qué hacer.
        assertEquals(CardChargeDecision.UNDETERMINED_MESSAGE, (outcome as CardChargeOutcome.Undetermined).message)
    }

    @Test
    fun `un server inalcanzable a la primera solo pide reintentar la CONSULTA`() {
        val decision = CardChargeDecision.decide(ChargeStatusProbe.Unreachable, isFinalAttempt = false)

        assertEquals(ProbeDecision.KeepPolling, decision)
    }

    @Test
    fun `TIMED_OUT y UNKNOWN son indeterminados — NUNCA se pintan como fracaso`() {
        listOf("TIMED_OUT", "UNKNOWN").forEach { status ->
            val decision = CardChargeDecision.decide(
                ChargeStatusProbe.Known(status = status, inProgress = false),
                isFinalAttempt = true,
            )
            assertTrue(
                "$status debe ser indeterminado, no fracaso",
                (decision as ProbeDecision.Resolved).outcome is CardChargeOutcome.Undetermined,
            )
        }
    }

    @Test
    fun `un estado que no conocemos es indeterminado, no un exito ni un fracaso`() {
        // Si el server agrega un estado nuevo, el cliente viejo NO puede adivinar.
        val decision = CardChargeDecision.decide(
            ChargeStatusProbe.Known(status = "PARTIALLY_REFUNDED_WHATEVER", inProgress = false),
            isFinalAttempt = true,
        )

        assertTrue((decision as ProbeDecision.Resolved).outcome is CardChargeOutcome.Undetermined)
    }

    // MARK: - Sigue en curso

    @Test
    fun `mientras la solicitud sigue en curso se vuelve a preguntar`() {
        listOf("PENDING", "SENT", "CANCEL_REQUESTED").forEach { status ->
            val decision = CardChargeDecision.decide(
                ChargeStatusProbe.Known(status = status, inProgress = true),
                isFinalAttempt = false,
            )
            assertEquals("$status debe seguir consultándose", ProbeDecision.KeepPolling, decision)
        }
    }

    @Test
    fun `si se agotan las consultas y seguia en curso, el desenlace es indeterminado`() {
        // Nunca "falló": la tarjeta pudo haberse cobrado en ese mismo instante.
        val outcome = CardChargeDecision.exhausted()

        assertTrue(outcome is CardChargeOutcome.Undetermined)
        assertEquals(CardChargeDecision.UNDETERMINED_MESSAGE, (outcome as CardChargeOutcome.Undetermined).message)
    }

    // MARK: - Cómo terminó la espera: ¿hay que ir a preguntar, o ya consta?

    @Test
    fun `un 5xx obliga a ir a preguntar el estado, nunca a concluir`() {
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(503)))
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(504)))
    }

    @Test
    fun `un corte de red obliga a ir a preguntar el estado`() {
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.NetworkError))
    }

    @Test
    fun `los rechazos de negocio NO mandan a preguntar — ya consta que no se cobró`() {
        assertFalse(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(404)))
        assertFalse(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(409)))
        assertFalse(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(422)))
    }

    // MARK: - Desenlace 4: SE VENCIÓ EL PLAZO

    @Test
    fun `vencerse el plazo manda a preguntar el estado — no es un fracaso`() {
        // El operador canceló DESDE la terminal (Nexgo) y la terminal nunca reportó nada:
        // el POS se quedaba en "Procesando pago…" para siempre. Ahora se corta la espera,
        // pero el desenlace lo sigue decidiendo el server, no el reloj.
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.CeilingExceeded))
    }

    @Test
    fun `si al vencer el plazo tampoco se puede consultar, queda indeterminado — jamás fracaso`() {
        val decision = CardChargeDecision.decide(ChargeStatusProbe.Unreachable, isFinalAttempt = true)

        assertTrue((decision as ProbeDecision.Resolved).outcome is CardChargeOutcome.Undetermined)
    }

    @Test
    fun `el plazo maximo del POS es MAS LARGO que la ventana que la terminal tiene a proposito`() {
        // 🔴 El número no es libre. La terminal espera 310 s A PROPÓSITO: alguien tiene que
        // llegar físicamente a pasar la tarjeta (.claude/rules/offline-first-y-hub-lan.md).
        // El server corta su long-poll a los 300 s. Un plazo más corto que esa ventana
        // rompería cobros legítimos — peor que el bug que estamos cerrando.
        assertTrue(
            "el plazo del POS debe exceder los 310 s de la espera de terminal",
            CardChargeDecision.WAIT_CEILING_MS > 310_000L,
        )
        // Y tampoco puede ser eterno: es la salida del cajero con fila enfrente.
        assertTrue(CardChargeDecision.WAIT_CEILING_MS <= 420_000L)
    }

    @Test
    fun `el mensaje indeterminado le dice al cajero que revise la terminal`() {
        // Blindaje del texto: es lo único que evita el segundo cargo cuando nadie sabe nada.
        assertTrue(CardChargeDecision.UNDETERMINED_MESSAGE.contains("No pudimos confirmar el cobro"))
        assertTrue(CardChargeDecision.UNDETERMINED_MESSAGE.contains("Revisa la terminal"))
    }
}
