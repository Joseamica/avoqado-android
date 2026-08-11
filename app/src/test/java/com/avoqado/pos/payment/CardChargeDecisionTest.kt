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
    fun `un 404 NO alcanza para declarar que no se cobro — queda indeterminado`() {
        // 🔴 Tentador y equivocado: "no existe la solicitud ⇒ nadie pasó una tarjeta".
        // El server crea la fila ANTES de emitir a la terminal, pero entre que el request
        // llega y la fila se escribe corren validateStaffVenue y la query de paymentStatus.
        // Si el socket murió con el request ya enviado, esa ventana supera de sobra los
        // 2.5 s del sondeo en un backend cargado o recién arrancado.
        // Sólo se llega aquí tras un final de transporte, o sea que ya hay duda: ante la
        // duda se dice que hay duda. Un NotCharged aquí = pantalla de Error = Reintentar
        // SIN advertencia = el doble cobro otra vez, por otra puerta.
        val decision = CardChargeDecision.decide(ChargeStatusProbe.NotFound, isFinalAttempt = true)

        assertTrue((decision as ProbeDecision.Resolved).outcome is CardChargeOutcome.Undetermined)
    }

    @Test
    fun `un 404 aislado NO se cree a la primera — puede ser un POST rezagado`() {
        val decision = CardChargeDecision.decide(ChargeStatusProbe.NotFound, isFinalAttempt = false)

        assertEquals(ProbeDecision.KeepPolling, decision)
    }

    @Test
    fun `NO COBRO solo sale de un estado terminal que lo diga — nunca de una ausencia`() {
        // La única prueba válida de que no hubo cargo es que el server lo AFIRME.
        val afirmados = listOf("FAILED", "CANCELLED")
        afirmados.forEach { status ->
            val d = CardChargeDecision.decide(
                ChargeStatusProbe.Known(status = status, inProgress = false),
                isFinalAttempt = true,
            )
            assertTrue("$status debe constar como no cobrado", (d as ProbeDecision.Resolved).outcome is CardChargeOutcome.NotCharged)
        }
        // Y de nada más: ausencia (404) e ignorancia (no se pudo preguntar) NO califican.
        listOf(ChargeStatusProbe.NotFound, ChargeStatusProbe.Unreachable).forEach { probe ->
            val d = CardChargeDecision.decide(probe, isFinalAttempt = true)
            assertFalse(
                "$probe no puede declarar que no se cobró",
                (d as ProbeDecision.Resolved).outcome is CardChargeOutcome.NotCharged,
            )
        }
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
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(503), cancelRequested = false))
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(504), cancelRequested = false))
    }

    @Test
    fun `un corte de red obliga a ir a preguntar el estado`() {
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.NetworkError, cancelRequested = false))
    }

    @Test
    fun `tras cancelar, NINGUN codigo permite concluir — ni siquiera el 409`() {
        // 🔴 MEDIDO CON TARJETA REAL el 2026-08-10 (Sunmi T3 Pro + terminal N860, $0.15):
        //
        //   22:34:57  el cobro sale hacia la terminal
        //   22:35:16  el cajero cancela  →  la petición original termina en 409
        //   22:35:22  la TERMINAL cobra y registra el pago          ← SEIS SEGUNDOS DESPUÉS
        //   22:35:24  el webhook confirma $0.15
        //
        // El 409 llegó ANTES de que se supiera la verdad. Tratarlo como "rechazo de negocio"
        // hizo que la app dijera "consta que no se cobró" con la tarjeta ya cobrada: venta
        // abierta, cero aviso, y el siguiente "Cobrar" cobrando por segunda vez.
        //
        // Cancelar es una PETICIÓN, no una garantía. Un 409 tras cancelar significa "tu
        // petición quedó superada", jamás "no hubo cargo": la terminal llevaba 19 segundos
        // con la solicitud en la mano y la tarjeta ya estaba pasando.
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(409), cancelRequested = true))
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(404), cancelRequested = true))
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(422), cancelRequested = true))
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(200), cancelRequested = true))
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.NetworkError, cancelRequested = true))
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.CeilingExceeded, cancelRequested = true))
    }

    @Test
    fun `los rechazos de negocio NO mandan a preguntar — ya consta que no se cobró`() {
        // Sigue valiendo cuando NADIE canceló: ahí el 409 sí es "terminal ocupada", o sea que
        // el cobro nunca se despachó. Es el matiz que distingue este caso del de arriba.
        assertFalse(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(404), cancelRequested = false))
        assertFalse(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(409), cancelRequested = false))
        assertFalse(CardChargeDecision.mustReconcile(ChargeWaitEnding.Http(422), cancelRequested = false))
    }

    // MARK: - Desenlace 4: SE VENCIÓ EL PLAZO

    @Test
    fun `vencerse el plazo manda a preguntar el estado — no es un fracaso`() {
        // El operador canceló DESDE la terminal (Nexgo) y la terminal nunca reportó nada:
        // el POS se quedaba en "Procesando pago…" para siempre. Ahora se corta la espera,
        // pero el desenlace lo sigue decidiendo el server, no el reloj.
        assertTrue(CardChargeDecision.mustReconcile(ChargeWaitEnding.CeilingExceeded, cancelRequested = false))
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

    // MARK: - Cancelar es una PETICIÓN, no una garantía

    // 🔴 El cajero cancela desde el POS, pero si la tarjeta ya se pasó la terminal cobra igual
    // y el desenlace llega TARDE (la espera dura hasta 330 s). El guard de resultado obsoleto
    // tiraba ese desenlace ENTERO —incluido el cobro exitoso—: el dinero salía y la venta
    // quedaba marcada como impaga. Lo que sigue es predecible: el cajero cobra otra vez.
    //
    // La regla: descartar un resultado obsoleto vale para la NAVEGACIÓN, jamás para el DINERO.

    @Test
    fun `un cobro que SI paso tras cancelar no se tira a la basura`() {
        val key = CardChargeDecision.unresolvedKeyAfterStaleResult(
            outcome = CardChargeOutcome.Charged("pay_1"),
            requestId = "req-1",
            armedKey = null,
        )

        assertEquals("canceló, pero la tarjeta SÍ se cobró: no puede desaparecer en silencio", "req-1", key)
    }

    @Test
    fun `tras cancelar, un desenlace que sigue sin saberse queda pendiente de resolver`() {
        val key = CardChargeDecision.unresolvedKeyAfterStaleResult(
            outcome = CardChargeOutcome.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE),
            requestId = "req-1",
            armedKey = null,
        )

        assertEquals("req-1", key)
    }

    @Test
    fun `solo un NO COBRO comprobado cierra el asunto al cancelar`() {
        // Éste es el camino feliz: cancelar antes de que la terminal haga nada. El server
        // contesta 409 'Cancelado' → consta que no hubo cargo → no queda referencia colgada
        // ni pantalla de "Cobro sin confirmar" fantasma en la venta siguiente.
        val key = CardChargeDecision.unresolvedKeyAfterStaleResult(
            outcome = CardChargeOutcome.NotCharged("El cobro se canceló. No se cobró la tarjeta."),
            requestId = "req-1",
            armedKey = null,
        )

        assertEquals("consta que no hubo cargo: no hay nada pendiente de avisar", null, key)
    }

    @Test
    fun `un desenlace tardio NO pisa la llave de un cobro POSTERIOR que sigue vivo`() {
        // 🔴 La venta ya avanzó a otra cosa: el cajero asumió el riesgo del cobro viejo y mandó
        // uno NUEVO, que quedó sin confirmar y es el que gobierna el disco. Si el rezagado
        // pisara esa llave, "Volver a consultar" resolvería el cobro VIEJO y el nuevo —el que
        // de verdad puede tener dinero encima— se perdería para siempre. Sólo hay UNA ranura.
        val key = CardChargeDecision.unresolvedKeyAfterStaleResult(
            outcome = CardChargeOutcome.Charged("pay_viejo"),
            requestId = "req-viejo",
            armedKey = "req-nuevo",
        )

        assertEquals("el cobro vivo manda sobre el rezagado", "req-nuevo", key)
    }

    @Test
    fun `un rezagado que consta como no cobrado tampoco borra la llave de otro cobro`() {
        val key = CardChargeDecision.unresolvedKeyAfterStaleResult(
            outcome = CardChargeOutcome.NotCharged("El cobro se canceló. No se cobró la tarjeta."),
            requestId = "req-viejo",
            armedKey = "req-nuevo",
        )

        assertEquals("req-nuevo", key)
    }

    @Test
    fun `si la llave armada es la de ESTE mismo cobro, el desenlace manda`() {
        // No es "otro cobro": es el propio, que se armó al empezar a enviarlo. Aquí sí resuelve.
        assertEquals(
            null,
            CardChargeDecision.unresolvedKeyAfterStaleResult(
                outcome = CardChargeOutcome.NotCharged("El cobro fue rechazado. No se cobró la tarjeta."),
                requestId = "req-1",
                armedKey = "req-1",
            ),
        )
        assertEquals(
            "req-1",
            CardChargeDecision.unresolvedKeyAfterStaleResult(
                outcome = CardChargeOutcome.Charged("pay_1"),
                requestId = "req-1",
                armedKey = "req-1",
            ),
        )
    }

    @Test
    fun `sin requestId no se puede armar nada, aunque el cobro haya pasado`() {
        // Defensivo: un desenlace sin llave no es consultable. Nunca se inventa una.
        val key = CardChargeDecision.unresolvedKeyAfterStaleResult(
            outcome = CardChargeOutcome.Charged("pay_1"),
            requestId = null,
            armedKey = null,
        )

        assertEquals(null, key)
    }

    @Test
    fun `una llave EN BLANCO no ocupa la ranura — no es un cobro ajeno`() {
        // 🔴 Un "" tratado como llave ajena congelaría la ranura: la venta siguiente mostraría
        // "Cobro sin confirmar" con una referencia que el server no puede resolver (el GET del
        // estado iría sin id), y el cajero se quedaría sin salida. Vacío = ranura libre.
        assertEquals(
            "req-1",
            CardChargeDecision.unresolvedKeyAfterStaleResult(
                outcome = CardChargeOutcome.Charged("pay_1"),
                requestId = "req-1",
                armedKey = "",
            ),
        )
        // Y tampoco se ARMA una llave en blanco: nunca se escribe basura en disco.
        assertEquals(
            null,
            CardChargeDecision.unresolvedKeyAfterStaleResult(
                outcome = CardChargeOutcome.Charged("pay_1"),
                requestId = "   ",
                armedKey = null,
            ),
        )
    }
}
