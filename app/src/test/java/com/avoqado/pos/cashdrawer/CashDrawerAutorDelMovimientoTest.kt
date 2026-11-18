package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * hz:nube0000:na7ab987 (ANDROID, 17-nov-2026): un ingreso o retiro que la cajera A hizo SIN RED se quedaba en la cola sin decir
 * quién lo hizo. Si después entraba B (cierre automático de la sesión de A), la pasada de B lo subía con SU sesión y el servidor lo
 * registraba a nombre de B. Ahora la cola guarda el autor en la MISMA escritura de la operación, antes de tocar la red.
 */
class CashDrawerAutorDelMovimientoTest {

    private val dao = FakeCashDrawerDao().apply { runBlocking { insertSession(sesionLocal("srv-1", openedAt = haceMinutos(60))) } }

    private fun cola(st: SecureStorage): List<PendingDrawerOp> =
        st.pendingDrawerOpsJson(VENUE_ID)
            ?.let { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), it) }
            ?: emptyList()

    /** Contrato del SERVIDOR (17-nov): `staffId` opcional en el cuerpo de `pay-in` y `pay-out`. */
    private fun autorEnElCuerpo(cuerpo: String): String? =
        Json.parseToJsonElement(cuerpo).jsonObject["staffId"]?.jsonPrimitive?.contentOrNull

    @Test fun `P1 un ingreso sin red guarda en la cola quien lo hizo y su primer envio lo lleva`() = runTest {
        val st = cashDrawerSecureStorageConCola().also { every { it.userId } returns "staff-A" }
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = cashDrawerRepo(dao, cashDrawerClient(capturadas = llamadas), secureStorage = st)

        repo.addPayIn(10_000, "Cambio")

        assertEquals("staff-A", cola(st).single { it.kind == "PAY_IN" }.autor)
        assertEquals("staff-A", autorEnElCuerpo(llamadas.first { it.path.endsWith("/pay-in") }.body))
    }

    @Test fun `P1 un retiro sin red guarda en la cola quien lo hizo y su primer envio lo lleva`() = runTest {
        val st = cashDrawerSecureStorageConCola().also { every { it.userId } returns "staff-A" }
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = cashDrawerRepo(dao, cashDrawerClient(capturadas = llamadas), secureStorage = st)

        repo.addPayOut(5_000, "Proveedor")

        assertEquals("staff-A", cola(st).single { it.kind == "PAY_OUT" }.autor)
        assertEquals("staff-A", autorEnElCuerpo(llamadas.first { it.path.endsWith("/pay-out") }.body))
    }

    /** El caso de la tarjeta: A lo hizo sin red, su sesión se cerró sola y la pasada que lo sube es la de B. */
    @Test fun `P1 el reenvio lleva al autor guardado aunque la sesion vigente sea de otra persona`() = runTest {
        val st = cashDrawerSecureStorageConCola().also { every { it.userId } returns "staff-B" }
        st.setPendingDrawerOpsJson(
            VENUE_ID,
            """[{"kind":"PAY_IN","sessionId":"srv-1","amountCents":10000,"localId":"loc-1","at":1763420000000,"autor":"staff-A"},""" +
                """{"kind":"PAY_OUT","sessionId":"srv-1","amountCents":5000,"localId":"loc-2","at":1763420060000,"autor":"staff-A"}]""",
        )
        val llamadas = mutableListOf<LlamadaCapturada>()
        val servidor = cashDrawerClient(
            "/cash-drawer/pay-in" to eventoJson("srv-e1", "PAY_IN", "100.00", localId = "loc-1"),
            "/cash-drawer/pay-out" to eventoJson("srv-e2", "PAY_OUT", "50.00", localId = "loc-2"),
            capturadas = llamadas,
        )
        val repo = cashDrawerRepo(dao, servidor, secureStorage = st)

        repo.reproducirPendientes()

        assertEquals("staff-A", autorEnElCuerpo(llamadas.first { it.path.endsWith("/pay-in") }.body))
        assertEquals("staff-A", autorEnElCuerpo(llamadas.first { it.path.endsWith("/pay-out") }.body))
    }

    @Test fun `una fila vieja sin autor sube sin el campo, como hoy`() = runTest {
        val st = cashDrawerSecureStorageConCola().also { every { it.userId } returns "staff-B" }
        st.setPendingDrawerOpsJson(VENUE_ID, """[{"kind":"PAY_IN","sessionId":"srv-1","amountCents":10000,"localId":"loc-1","at":1763420000000}]""")
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = cashDrawerRepo(dao, cashDrawerClient(capturadas = llamadas), secureStorage = st)

        repo.reproducirPendientes()

        assertNull(autorEnElCuerpo(llamadas.single { it.path.endsWith("/pay-in") }.body))
    }

    @Test fun `una cola de una version anterior, sin autor, se lee igual`() {
        val vieja = """[{"kind":"PAY_IN","sessionId":"srv-1","amountCents":10000,"localId":"loc-1","at":1763420000000}]"""
        val op = Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), vieja).single()
        assertNull(op.autor)
    }
}
