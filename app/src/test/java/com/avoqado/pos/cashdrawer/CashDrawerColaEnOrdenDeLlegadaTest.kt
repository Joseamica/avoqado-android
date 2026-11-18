package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * hz:nube0000:na293737 (17-nov-2026): la cola de la caja se ordenaba por la HORA DEL APARATO (`at`), que alguien puede mover.
 *
 * El caso de la tarjeta: el retiro se hizo sin red a las 20:03, pero el reloj estaba adelantado y lo guardó como 21:03. Se corrige
 * el reloj y la cajera cierra a las 20:05, todavía sin red. Ordenando por `at`, el CIERRE (20:05) subía antes que el RETIRO
 * (21:03): el servidor firmaba el arqueo sin el retiro, un faltante falso del tamaño del retiro.
 *
 * La cola se guarda SIEMPRE agregando al final (`encolar`), así que la lista guardada YA es el orden en que la cajera hizo las
 * cosas. Estas pruebas guardan la cola en ese orden real, con las horas que deja un reloj que se movió.
 */
class CashDrawerColaEnOrdenDeLlegadaTest {

    private val t = 1_763_420_000_000L // 17-nov-2026, una hora creíble
    private val minuto = 60_000L

    private fun almacenConCola(colaInicial: String) = mockk<SecureStorage>(relaxed = true).also { st ->
        var cola: String? = colaInicial
        every { st.venueId } returns VENUE_ID
        every { st.userId } returns "staff-1"
        every { st.pendingDrawerOpsJson(any()) } answers { cola }
        every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
    }

    private fun repo(st: SecureStorage, vararg rutas: Pair<String, String>, llamadas: MutableList<LlamadaCapturada>) =
        CashDrawerRepository(
            dao = FakeCashDrawerDao(),
            secureStorage = st,
            client = cashDrawerClient(*rutas, capturadas = llamadas),
            pendingCashSales = sinCobrosEnCola(),
            conectividad = conectividadDePrueba(),
        )

    private fun colaCon(vararg filas: String) = "[" + filas.joinToString(",") + "]"
    private fun apertura(sessionId: String, localId: String, at: Long) =
        """{"kind":"OPEN","sessionId":"$sessionId","amountCents":200000,"localId":"$localId","at":$at}"""
    private fun ingreso(sessionId: String, localId: String, at: Long) =
        """{"kind":"PAY_IN","sessionId":"$sessionId","amountCents":3000,"localId":"$localId","at":$at}"""
    private fun retiro(sessionId: String, localId: String, at: Long) =
        """{"kind":"PAY_OUT","sessionId":"$sessionId","amountCents":5000,"localId":"$localId","at":$at}"""
    private fun cierre(sessionId: String, at: Long) =
        """{"kind":"CLOSE","sessionId":"$sessionId","amountCents":95000,"at":$at}"""

    @Test
    fun `P1 el reloj se corrigio entre el retiro y el cierre y el retiro sin red frena el cierre`() = runTest {
        val llamadas = mutableListOf<LlamadaCapturada>()
        val st = almacenConCola(
            colaCon(
                retiro("srv-A", "loc-retiro", at = t + 60 * minuto), // 20:03 real, guardado como 21:03
                cierre("srv-A", at = t + 2 * minuto), // 20:05, con el reloj ya corregido
            ),
        )
        // El retiro no llega (sin red para esa ruta); el cierre sí llegaría.
        val repo = repo(st, "/cash-drawer/close" to sesionJson("srv-A"), llamadas = llamadas)

        repo.reproducirPendientes()

        assertFalse(
            "el cierre subió antes que el retiro anterior de su caja: el servidor firmaría un faltante falso de \$50 — $llamadas",
            llamadas.any { it.path.endsWith("/close") },
        )
    }

    @Test
    fun `con todo llegando el retiro sube antes que el cierre aunque su hora diga lo contrario`() = runTest {
        val llamadas = mutableListOf<LlamadaCapturada>()
        val st = almacenConCola(
            colaCon(
                retiro("srv-A", "loc-retiro", at = t + 60 * minuto),
                cierre("srv-A", at = t + 2 * minuto),
            ),
        )
        val repo = repo(
            st,
            "/cash-drawer/pay-out" to eventoJson("srv-e1", "PAY_OUT", "50.00", localId = "loc-retiro"),
            "/cash-drawer/close" to sesionJson("srv-A"),
            llamadas = llamadas,
        )

        repo.reproducirPendientes()

        assertEquals(
            "el orden del replay no es el orden en que se hicieron: ${llamadas.map { it.path }}",
            listOf("pay-out", "close"),
            llamadas.map { it.path.substringAfterLast('/') },
        )
    }

    /** C1 con el reloj movido: el cierre de A se guardó adelantado y la apertura de B, ya corregida, «antes» que él. */
    @Test
    fun `P1 con el reloj corregido la apertura de la caja siguiente no se adelanta al cierre sin red`() = runTest {
        val llamadas = mutableListOf<LlamadaCapturada>()
        val st = almacenConCola(
            colaCon(
                cierre("srv-A", at = t + 65 * minuto), // reloj adelantado
                apertura("prov-B", "loc-open-b", at = t + 6 * minuto), // reloj corregido
                ingreso("prov-B", "loc-b", at = t + 7 * minuto),
            ),
        )
        // El cierre no llega; la apertura llegaría y el servidor la LIGARÍA a la caja A que sigue abierta.
        val repo = repo(st, "/cash-drawer/open" to sesionJson("srv-A"), llamadas = llamadas)

        repo.reproducirPendientes()

        assertFalse(
            "la apertura de B se mandó antes que el cierre de A (C1): ${llamadas.map { it.path }}",
            llamadas.any { it.path.endsWith("/open") },
        )
    }
}
