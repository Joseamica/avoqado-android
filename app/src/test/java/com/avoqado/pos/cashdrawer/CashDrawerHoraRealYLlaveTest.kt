package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 LA APERTURA REPRODUCIDA LLEVA SU LLAVE Y SU HORA REAL — y la caja local ya no miente (N1 + F3,
 * Task 8b, 5-sep-2026).
 *
 * Medido en una Samsung SM-X133: la caja se abrió sin red a las 10:22, se vendieron $80 en efectivo a
 * las 10:25, y al volver la red el servidor registró la apertura a las 10:31 — la hora del REPLAY. Al
 * adoptar la caja del servidor, `adoptServerSession` mudaba los eventos locales acotados a
 * `server.openedAt`, así que la venta de las 10:25 quedaba en la sesión provisional: la pantalla decía
 * «Ventas $0.00 · Efectivo esperado $500.00» y el ticket del corte se contradecía a sí mismo («Ventas
 * totales $80.00» arriba, que viene del servidor). El dinero del servidor era correcto; mentían la
 * pantalla y el papel del cajero.
 *
 * Dos arreglos, cada uno con su prueba aquí:
 *  1. el `POST /open` lleva `localId` (la llave de la cola) y `openedAt` (la hora a la que la caja se
 *     abrió EN EL APARATO), para que el servidor estampe la hora real y deduplique el reintento;
 *  2. la sesión provisional cuya apertura se está reproduciendo muda TODOS sus eventos a la caja del
 *     servidor —son suyos por construcción—, y la ventana `server.openedAt` se conserva sólo para las
 *     OTRAS cajas locales abiertas (la de un turno anterior que este aparato nunca vio cerrar).
 *
 * Y con el echo de la llave, «¿es MI caja?» deja de adivinarse por aparato+persona+fondo.
 */
class CashDrawerHoraRealYLlaveTest {

    // MARK: - Andamio (mismo contrato que CashDrawerAperturaDurableTest)

    /**
     * El `deviceName` que el repositorio compone en un test JVM: los stubs del SDK devuelven null, así
     * que el aparato «propio» es literalmente «null null». Es EL valor que el código produce aquí.
     */
    private val miAparato = "null null"

    private fun almacenConCola() = mockk<SecureStorage>(relaxed = true).also { st ->
        var cola: String? = null
        every { st.venueId } returns VENUE_ID
        every { st.userId } returns "staff-1"
        every { st.userFirstName } returns "Ana"
        every { st.userLastName } returns "Ruiz"
        every { st.pendingDrawerOpsJson(any()) } answers { cola }
        every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
        var avisos: String? = null
        every { st.drawerAdoptionNoticesJson(any()) } answers { avisos }
        every { st.setDrawerAdoptionNoticesJson(any(), any()) } answers { avisos = secondArg() }
    }

    private fun cola(st: SecureStorage): List<PendingDrawerOp> =
        st.pendingDrawerOpsJson(VENUE_ID)
            ?.let { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), it) }
            ?: emptyList()

    /**
     * `hayRed` es lo que el aparato dice de su red ANTES de intentar. 🔴 El repo «sin red» tiene que
     * construirse con `hayRed = false`: si dijera que hay red, su intento fallido con `IOException`
     * contaría como «llegó al servidor» (R2-P2-1: lo desconocido cuenta como sí), arrancaría el
     * backoff de 30 s de la apertura y el replay CON red se la saltaría — exactamente lo que pasó en
     * la primera versión de estas pruebas.
     */
    private fun repo(st: SecureStorage, client: OkHttpClient, dao: FakeCashDrawerDao, hayRed: Boolean) = CashDrawerRepository(
        dao = dao,
        secureStorage = st,
        client = client,
        pendingCashSales = sinCobrosEnCola(),
        conectividad = conectividadDePrueba(hayRed),
    )

    /** La forma REAL de la respuesta del `POST /open`, con APARATO, PERSONA y (opcional) la LLAVE. */
    private fun cuerpoDeCaja(id: String, cajaCreada: Boolean, fondo: Double, aparato: String, staffId: String, localId: String? = null) =
        """{"success":true,"data":{"id":"$id","venueId":"$VENUE_ID","deviceName":"$aparato","status":"OPEN",""" +
            """"openedByStaffId":"$staffId","openedByName":"Ana Ruiz",""" +
            """"openedAt":"2026-09-05T16:22:00.000Z","startingAmount":$fondo,""" +
            """"closedByStaffId":null,"closedByName":null,"closedAt":null,""" +
            """"actualAmount":null,"overShort":null,"closingNote":null,""" +
            """"cajaCreada":$cajaCreada,${if (localId == null) "" else "\"localId\":\"$localId\","}"events":[]}}"""

    // MARK: - 1. Lo que viaja en el POST

    /**
     * 🔴 Sin `openedAt` el servidor estampa la hora del replay; sin `localId` no puede reconocer un
     * reintento. Se mira el CUERPO que salió por el cable: mirar sólo el resultado no distinguiría una
     * apertura con llave de una sin ella.
     */
    @Test
    fun `el POST de la apertura lleva la llave de la cola y la hora a la que se abrio la caja en el aparato`() = runTest {
        val st = almacenConCola()
        val dao = FakeCashDrawerDao()
        val local = repo(st, cashDrawerClient(), dao, hayRed = false).openSession(50_000) // sin red: queda encolada
        val op = cola(st).single { it.kind == "OPEN" }
        // La llave de la apertura ES el id del evento OPEN local — se comprueba ANTES del replay, porque
        // al adoptar la caja del servidor esa copia local se fusiona con la suya y deja de existir con ese id.
        assertTrue("la llave de la apertura tiene que ser la del evento OPEN local", op.localId != null && dao.events.containsKey(op.localId!!))

        val llamadas = mutableListOf<LlamadaCapturada>()
        repo(st, cashDrawerClient("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer, startingAmount = 500.00, localId = op.localId), capturadas = llamadas), dao, hayRed = true)
            .reproducirPendientes()

        val cuerpo = llamadas.first { it.path.endsWith("/open") }.body
        assertTrue("el POST no lleva la llave de la cola: $cuerpo", cuerpo.contains("\"localId\":\"${op.localId}\""))
        assertTrue("el POST no lleva la hora REAL de la apertura local: $cuerpo", cuerpo.contains("\"openedAt\":\"${isoDe(local.openedAt)}\""))
    }

    // MARK: - 2. F3 — la venta hecha sin red cuenta en la caja del servidor

    /**
     * 🔴 EL CASO MEDIDO. La venta de $80 se hizo 10 minutos ANTES de que el servidor registrara la
     * apertura. Con la ventana `server.openedAt` la venta se quedaba en la provisional y el esperado
     * decía $500; tiene que decir $580, que es lo que hay en el cajón.
     */
    @Test
    fun `F3 la venta hecha sin red antes del replay se muda a la caja del servidor aunque el servidor la registre despues`() = runTest {
        val st = almacenConCola()
        val dao = FakeCashDrawerDao()
        val local = repo(st, cashDrawerClient(), dao, hayRed = false).openSession(50_000)
        dao.insertEvent(eventoLocal("venta-80", local.id, "CASH_SALE", 8_000, orderId = "o-1", createdAt = haceMinutos(10)))

        // El servidor registra la apertura AHORA (la hora del replay), después de la venta.
        val conRed = repo(st, cashDrawerClient("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer, openedAt = haceMinutos(0), startingAmount = 500.00)), dao, hayRed = true)
        conRed.reproducirPendientes()

        val abierta = conRed.getOpenSession()
        assertEquals("srv-1", abierta?.id)
        assertEquals("quedó más de una caja abierta: ${dao.sessions.values.filter { it.status == "OPEN" }.map { it.id }}", 1, dao.sessions.values.count { it.status == "OPEN" })
        val eventos = dao.getSessionEvents("srv-1")
        assertTrue("la venta hecha sin red se quedó FUERA de la caja del servidor: ${eventos.map { it.id }}", eventos.any { it.id == "venta-80" })
        assertEquals("el esperado del cajero tiene que incluir la venta", 58_000, conRed.computeExpectedAmount("srv-1", 50_000))
        assertFalse("la provisional promovida no puede seguir viva", dao.sessions.containsKey(local.id) && dao.sessions[local.id]?.status == "OPEN")
    }

    // MARK: - 3. La ventana sigue protegiendo a la caja AJENA

    /**
     * 🔴 Mover la ventana fue lo que produjo la «caja fantasma» del 27-ago, y antes de eso el retiro de
     * AYER colándose al arqueo de HOY. La regla nueva sólo alcanza a la provisional cuya apertura se
     * está reproduciendo; una caja de otro día que este aparato nunca vio cerrar sigue acotada.
     */
    @Test
    fun `la caja de otro dia que este aparato nunca vio cerrar sigue sin colarse a la caja de hoy`() = runTest {
        val st = almacenConCola()
        val dao = FakeCashDrawerDao()
        dao.insertSession(
            CashDrawerSessionEntity(
                id = "vieja",
                venueId = VENUE_ID,
                deviceName = null,
                openedByStaffId = "staff-1",
                openedByName = "Ana Ruiz",
                openedAt = haceMinutos(24 * 60),
                startingAmountCents = 10_000,
                status = "OPEN",
            ),
        )
        dao.insertEvent(eventoLocal("retiro-de-ayer", "vieja", "PAY_OUT", 5_000, createdAt = haceMinutos(23 * 60)))
        val local = repo(st, cashDrawerClient(), dao, hayRed = false).openSession(50_000)
        dao.insertEvent(eventoLocal("venta-80", local.id, "CASH_SALE", 8_000, orderId = "o-1", createdAt = haceMinutos(10)))

        val conRed = repo(st, cashDrawerClient("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer, openedAt = haceMinutos(0), startingAmount = 500.00)), dao, hayRed = true)
        conRed.reproducirPendientes()

        val eventos = dao.getSessionEvents("srv-1")
        assertTrue(eventos.any { it.id == "venta-80" })
        assertFalse("el retiro de AYER se coló a la caja de hoy", eventos.any { it.id == "retiro-de-ayer" })
        assertEquals(58_000, conRed.computeExpectedAmount("srv-1", 50_000))
        assertEquals("la caja de ayer se conserva, pero CERRADA", "CLOSED", dao.sessions["vieja"]?.status)
        assertTrue("el retiro de ayer sigue colgado de su caja", dao.getSessionEvents("vieja").any { it.id == "retiro-de-ayer" })
    }

    // MARK: - 4. «¿Es MI caja?» se decide por la LLAVE cuando el servidor la manda

    /**
     * 🔴 El residuo declarado en `esMiPropiaCaja`: dos tablets del mismo modelo, con la misma cuenta y
     * el mismo fondo compartían caja SIN aviso. Con el echo de la llave el servidor dice de quién es la
     * apertura, y una llave ajena es una adopción aunque todo lo demás coincida.
     */
    @Test
    fun `ligada a una caja con OTRA llave avisa aunque coincidan aparato, persona y fondo`() = runTest {
        val st = almacenConCola()
        val dao = FakeCashDrawerDao()
        repo(st, cashDrawerClient(), dao, hayRed = false).openSession(50_000)

        val cuerpo = cuerpoDeCaja("srv-9", cajaCreada = false, fondo = 500.00, aparato = miAparato, staffId = "staff-1", localId = "llave-de-la-otra-tablet")
        val conRed = repo(st, cashDrawerClient("/cash-drawer/open" to cuerpo), dao, hayRed = true)
        conRed.reproducirPendientes()

        val avisos = conRed.cajasAdoptadas()
        assertEquals("una llave ajena ES una adopción, coincida lo que coincida: $avisos", 1, avisos.size)
        assertEquals("srv-9", avisos.first().sessionId)
    }

    /** Y sin llave en la respuesta (servidor anterior a N1) se sigue decidiendo como antes: misma caja ⇒ sin aviso. */
    @Test
    fun `sin llave en la respuesta la regla heuristica de antes sigue mandando`() = runTest {
        val st = almacenConCola()
        val dao = FakeCashDrawerDao()
        repo(st, cashDrawerClient(), dao, hayRed = false).openSession(50_000)

        val cuerpo = cuerpoDeCaja("srv-9", cajaCreada = false, fondo = 500.00, aparato = miAparato, staffId = "staff-1")
        val conRed = repo(st, cashDrawerClient("/cash-drawer/open" to cuerpo), dao, hayRed = true)
        conRed.reproducirPendientes()

        assertTrue("mismo aparato, misma persona y mismo fondo sin llave: no hay nada que avisar", conRed.cajasAdoptadas().isEmpty())
    }
}
