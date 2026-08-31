package com.avoqado.pos.cashdrawer

import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 UN MOVIMIENTO RECHAZADO TIENE QUE SEGUIR BLOQUEANDO EL CIERRE EN LA SIGUIENTE PASADA.
 *
 * El cierre es una BARRERA: si un retiro de esa caja no llegó al servidor, mandar el cierre encima
 * hace que el servidor firme un faltante falso — le faltan $50 que sí salieron del cajón.
 *
 * Un movimiento REINTENTABLE bloquea de forma natural: sigue en la cola y el bucle lo ve. Uno
 * RECHAZADO no, y ahí estuvo el defecto: para no reintentarlo se filtraba de la lista ANTES del
 * bucle, así que también dejaba de sembrar la barrera. En la MISMA corrida funcionaba (el rechazo
 * acababa de ocurrir); en la SEGUNDA —tras reabrir la app o volver a entrar a Caja— el cierre ya
 * no encontraba quién lo detuviera y se mandaba solo. El daño llega tarde y sin nadie mirando,
 * que es la peor forma. (Lo encontraron a la vez esta sesión y la 4ª auditoría de Codex.)
 *
 * Estas pruebas simulan la SEGUNDA pasada: la cola ya trae el rechazo guardado de antes.
 */
class CashDrawerBarreraDelCierreTest {

    /** Un `SecureStorage` que de verdad recuerda la cola entre llamadas (el de fixtures no guarda). */
    private fun almacenConCola(colaInicial: String) = mockk<SecureStorage>(relaxed = true).also { st ->
        var cola: String? = colaInicial
        every { st.venueId } returns VENUE_ID
        every { st.pendingDrawerOpsJson(any()) } answers { cola }
        every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
    }

    private fun colaCon(vararg filas: String) = "[" + filas.joinToString(",") + "]"

    private fun retiroRechazado(sessionId: String, cents: Int, localId: String) =
        """{"kind":"PAY_OUT","sessionId":"$sessionId","amountCents":$cents,"localId":"$localId","at":1,"rechazadaEn":99,"motivoDelRechazo":"Monto inválido"}"""

    private fun cierre(sessionId: String, cents: Int) =
        """{"kind":"CLOSE","sessionId":"$sessionId","amountCents":$cents,"at":2}"""

    /** Ninguna ruta configurada ⇒ toda llamada revienta como "sin red", pero queda capturada. */
    private fun repoCon(cola: String, capturadas: MutableList<LlamadaCapturada>) =
        com.avoqado.pos.cashdrawer.data.CashDrawerRepository(
            dao = FakeCashDrawerDao(),
            secureStorage = almacenConCola(cola),
            client = cashDrawerClient(capturadas = capturadas),
            pendingCashSales = sinCobrosEnCola(),
        )

    @Test
    fun `el cierre NO se manda si esa caja tiene un retiro rechazado de una corrida anterior`() = runTest {
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoCon(colaCon(retiroRechazado("srv-1", 5000, "loc-a"), cierre("srv-1", 25000)), llamadas)

        repo.reproducirPendientes()

        assertFalse(
            "el cierre se mandó pese al retiro rechazado — el server firmaría un faltante falso de \$50: $llamadas",
            llamadas.any { it.path.endsWith("/close") },
        )
    }

    /** Un rechazo de OTRA caja no puede detener el cierre de ésta. */
    @Test
    fun `un rechazo de otra caja no bloquea este cierre`() = runTest {
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoCon(colaCon(retiroRechazado("srv-OTRA", 5000, "loc-b"), cierre("srv-1", 25000)), llamadas)

        repo.reproducirPendientes()

        assertTrue("el cierre de srv-1 debía intentarse: $llamadas", llamadas.any { it.path.endsWith("/close") })
    }

    /** Y el aviso tiene que seguir ahí para que alguien pueda resolverlo. */
    @Test
    fun `el rechazo guardado se sigue viendo`() {
        val repo = repoCon(colaCon(retiroRechazado("srv-1", 5000, "loc-a")), mutableListOf())
        val avisos = repo.operacionesRechazadas()

        assertEquals(1, avisos.size)
        assertEquals(5000, avisos[0].amountCents)
        assertEquals("PAY_OUT", avisos[0].kind)
        assertEquals("Monto inválido", avisos[0].motivo)
    }

    /**
     * 🔴 Dos retiros del MISMO monto y la MISMA caja son avisos DISTINTOS.
     *
     * Con la identidad por monto, tocar "Ya lo vi" en uno borraba los dos, y el segundo
     * desaparecía sin que nadie lo hubiera reconocido — dinero fuera del cajón que ya no
     * aparece por ningún lado.
     */
    @Test
    fun `dos retiros iguales de la misma caja son avisos distintos`() {
        val repo = repoCon(
            colaCon(retiroRechazado("srv-1", 5000, "loc-a"), retiroRechazado("srv-1", 5000, "loc-b")),
            mutableListOf(),
        )

        val avisos = repo.operacionesRechazadas()
        assertEquals("los dos retiros deben verse", 2, avisos.size)
        assertEquals("y con identidades distintas", 2, avisos.map { it.localKey }.toSet().size)

        repo.descartarRechazada(avisos[0].localKey)
        assertEquals("descartar uno no puede llevarse el otro", 1, repo.operacionesRechazadas().size)
    }
}
