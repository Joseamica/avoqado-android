package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.domain.WasteOutcome
import com.avoqado.pos.inventory.waste.domain.clasificarRespuestaDeMerma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El corazón de la fase: decide si una merma sin confirmar se reintenta para
 * siempre, espera a una persona, o se cierra. Copiado de la tabla «Respuestas»
 * del contrato del servidor (fase 1, `task-8-report.md`).
 */
class WasteOutcomeTest {

    @Test
    fun `201 es sincronizada`() {
        assertEquals(WasteOutcome.Sincronizada, clasificarRespuestaDeMerma(201, null, null))
    }

    /**
     * 🔴 El 201 de un REINTENTO del mismo folio llega aunque entre tanto se haya
     * revocado el permiso o vencido el plan (contrato del servidor). La fila se
     * cierra igual: la merma SÍ se registró.
     */
    @Test
    fun `409 WASTE_RETRYABLE_CONFLICT se reintenta con el MISMO folio`() {
        assertEquals(
            WasteOutcome.Reintentable,
            clasificarRespuestaDeMerma(409, "WASTE_RETRYABLE_CONFLICT", null),
        )
    }

    @Test
    fun `red caida y 5xx se reintentan`() {
        assertEquals(WasteOutcome.Reintentable, clasificarRespuestaDeMerma(0, null, null))
        assertEquals(WasteOutcome.Reintentable, clasificarRespuestaDeMerma(500, null, null))
        assertEquals(WasteOutcome.Reintentable, clasificarRespuestaDeMerma(503, null, null))
        assertEquals(WasteOutcome.Reintentable, clasificarRespuestaDeMerma(429, null, null))
    }

    /** 401: se refresca la sesión y se reintenta. La fila NUNCA se pierde. */
    /**
     * 🔴 Codex r1: un 408 (se agotó la espera) dice lo mismo que la red caída — nada del negocio. En
     * revisión, una merma válida quedaba trabada hasta que alguien la descartara y recapturara.
     */
    @Test
    fun `408 se reintenta`() {
        assertEquals(WasteOutcome.Reintentable, clasificarRespuestaDeMerma(408, null, null))
    }

    @Test
    fun `401 se reintenta`() {
        assertEquals(WasteOutcome.Reintentable, clasificarRespuestaDeMerma(401, null, null))
    }

    /**
     * 🔴 El 403 de PLAN se distingue por `featureCode`, no por `code` (los
     * middlewares compartidos de la plataforma no mandan `code`). Es reintentable
     * cuando el dueño pague — nunca «necesita revisión», que le pediría al cajero
     * arreglar algo que no está en sus manos.
     */
    @Test
    fun `403 con featureCode es bloqueo de plan, no revision`() {
        assertEquals(
            WasteOutcome.BloqueoDePlan,
            clasificarRespuestaDeMerma(403, null, "INVENTORY_TRACKING"),
        )
    }

    @Test
    fun `403 sin featureCode necesita revision`() {
        assertTrue(clasificarRespuestaDeMerma(403, null, null) is WasteOutcome.NecesitaRevision)
        assertTrue(
            clasificarRespuestaDeMerma(403, "WASTE_INVENTORY_DISABLED", null) is WasteOutcome.NecesitaRevision,
        )
        assertTrue(
            clasificarRespuestaDeMerma(403, "WASTE_ACCESS_REVOKED", null) is WasteOutcome.NecesitaRevision,
        )
    }

    /** `WASTE_VOIDED` es TERMINAL: alguien anuló el folio. Ni reintenta ni pide revisión. */
    @Test
    fun `409 WASTE_VOIDED es terminal`() {
        assertEquals(WasteOutcome.Anulada, clasificarRespuestaDeMerma(409, "WASTE_VOIDED", null))
    }

    /**
     * 🔴 `WASTE_VOIDED` sale del servidor ANTES que el candado de plan y que el de
     * permiso, así que puede llegar acompañado de `featureCode`. Sigue siendo
     * terminal: el folio está anulado, no hay plan que lo resucite.
     */
    @Test
    fun `WASTE_VOIDED gana aunque venga con featureCode`() {
        assertEquals(
            WasteOutcome.Anulada,
            clasificarRespuestaDeMerma(409, "WASTE_VOIDED", "INVENTORY_TRACKING"),
        )
    }

    @Test
    fun `los permanentes del contrato necesitan revision`() {
        listOf(
            404 to "ITEM_NOT_FOUND",
            409 to "IDEMPOTENCY_KEY_REUSED",
            409 to "UNIT_MISMATCH",
            422 to "INVALID_WASTE_PAYLOAD",
            422 to "INVALID_WASTE_KEY",
            422 to "INVALID_WASTE_REASON",
            422 to "QUANTITY_TOO_LARGE",
        ).forEach { (http, code) ->
            assertTrue(
                "$http $code debería necesitar revisión",
                clasificarRespuestaDeMerma(http, code, null) is WasteOutcome.NecesitaRevision,
            )
        }
    }

    /**
     * 🔴 Un 4xx DESCONOCIDO (servidor más nuevo que el APK) necesita revisión, no
     * reintento: reintentar para siempre algo que el servidor rechaza a propósito
     * es un bucle invisible que nadie ve hasta que el cajero pregunta por qué su
     * merma sigue ahí.
     */
    @Test
    fun `un 4xx desconocido necesita revision, no se reintenta`() {
        assertTrue(clasificarRespuestaDeMerma(418, "ALGO_NUEVO", null) is WasteOutcome.NecesitaRevision)
        assertTrue(clasificarRespuestaDeMerma(400, null, null) is WasteOutcome.NecesitaRevision)
    }

    /** El motivo viaja con la fila para poder explicárselo a una persona después. */
    @Test
    fun `la revision conserva el codigo para poder explicarla`() {
        val outcome = clasificarRespuestaDeMerma(409, "UNIT_MISMATCH", null)
        assertEquals("UNIT_MISMATCH", (outcome as WasteOutcome.NecesitaRevision).motivo)
    }
}
