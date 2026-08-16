package com.avoqado.pos.cashdrawer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 EL ID DE LA CAJA: la tablet lo INVENTA, y el del server nunca lo adopta.
 *
 * Medido con sqlite3 sobre los datos reales (2026-08-16): `openSession()` crea la
 * sesión con un `UUID.randomUUID()` y el sync inserta DESPUÉS otra sesión OPEN con
 * el id del server. Room queda con DOS cajas abiertas del mismo local, y
 * `getOpenSession` —`LIMIT 1` sin `ORDER BY`— devuelve siempre la local. Como
 * `computeExpectedAmount` suma POR sessionId, los movimientos que manda el server
 * (el reembolso, entre ellos) caen en la sesión que nadie lee:
 *
 *   - la tablet que ABRIÓ la caja  → no ve el PAY_OUT del reembolso → sobra $150
 *   - la tablet que NO la abrió    → ve su CASH_SALE local Y el del server → sobra la venta
 *
 * O sea: el server ya resta bien el reembolso (`08a3fe6f`), pero en la pantalla del
 * cajero el sobrante no se movió ni un peso.
 *
 * El arreglo es el patrón que este repo YA usa para las órdenes offline: la fila
 * local nace con un id provisional y **se promueve** al id real cuando el server
 * confirma (`TableSession.promoteProvisional`, `localOrderId → orderId`). Aquí es lo
 * mismo, sólo que la promoción vive en Room: la sesión adopta el id del server y sus
 * eventos se mudan con ella.
 *
 * Los números de estos tests son los del caso real:
 *   fondo $5,000.00 · venta en efectivo $280.00 · reembolso $150.00 → esperado $5,130.00
 *
 * El andamio (DAO falso, HTTP por path, payloads) vive en `CashDrawerTestFixtures.kt`,
 * compartido con [CashDrawerEventKeyMergeTest].
 */
class CashDrawerSessionPromotionTest {

    // MARK: - 1. Abrir con red

    /**
     * La tablet abre la caja, el server contesta con SU id. Al terminar tiene que
     * quedar UNA sola caja abierta en Room, y con el id del server — si no, todo lo
     * que el server mande después (el reembolso, la venta de otra tablet) aterriza
     * en una sesión que esta pantalla no lee.
     */
    @Test
    fun `abrir la caja con red deja UNA sola sesion, con el id del server`() = runTest {
        val dao = FakeCashDrawerDao()
        val repo = cashDrawerRepo(dao, cashDrawerClient("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer)))

        repo.openSession(FONDO_CENTS)

        assertEquals(
            "Room quedó con más de una caja abierta: ${dao.sessions.keys}",
            1,
            dao.sessions.size,
        )
        assertEquals("srv-1", dao.sessions.keys.first())
        assertEquals("srv-1", repo.getOpenSession()?.id)
    }

    // MARK: - 2. Abrir sin red (no se puede romper)

    @Test
    fun `abrir la caja SIN red sigue funcionando`() = runTest {
        val dao = FakeCashDrawerDao()
        val repo = cashDrawerRepo(dao, cashDrawerClient()) // nada responde

        val sesion = repo.openSession(FONDO_CENTS)

        assertEquals(1, dao.sessions.size)
        assertEquals("OPEN", sesion.status)
        assertEquals(FONDO_CENTS, sesion.startingAmountCents)
        assertEquals(sesion.id, repo.getOpenSession()?.id)
    }

    /**
     * Y al reconectar, la caja que nació local ADOPTA el id del server en vez de
     * dejar una segunda fila abierta.
     */
    @Test
    fun `la caja abierta sin red se promueve al reconectar y sigue habiendo UNA`() = runTest {
        val dao = FakeCashDrawerDao()
        val sinRed = cashDrawerRepo(dao, cashDrawerClient())
        val local = sinRed.openSession(FONDO_CENTS)

        val conRed = cashDrawerRepo(dao, cashDrawerClient("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer)))
        conRed.syncFromApi()

        assertEquals(
            "la sesión local no se promovió: ${dao.sessions.keys}",
            1,
            dao.sessions.size,
        )
        assertEquals("srv-1", dao.sessions.keys.first())
        assertTrue("la fila provisional sigue viva", !dao.sessions.containsKey(local.id))
    }

    // MARK: - 3. Los eventos se mudan con la sesión

    /**
     * Un retiro registrado SIN red vive contra el id local. Si la promoción no se
     * lleva los eventos, ese dinero deja de contar y el cajón "aparece" con $200 de
     * más al cerrar.
     */
    @Test
    fun `los eventos creados contra el id local siguen contando despues de la promocion`() = runTest {
        val dao = FakeCashDrawerDao()
        val sinRed = cashDrawerRepo(dao, cashDrawerClient())
        sinRed.openSession(FONDO_CENTS)
        sinRed.addPayOut(20_000, "Compra de hielo") // $200.00, sin red: no llega al server

        val conRed = cashDrawerRepo(dao, cashDrawerClient("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer)))
        conRed.syncFromApi()

        val sesion = conRed.getOpenSession()
        assertNotNull(sesion)
        assertEquals("srv-1", sesion!!.id)
        assertEquals(
            "el retiro registrado sin red se perdió al promover la sesión",
            FONDO_CENTS - 20_000,
            conRed.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - 4. 🔴 EL NÚMERO QUE VE EL CAJERO

    /**
     * El caso completo, con los números medidos: fondo $5,000 + venta $280 −
     * reembolso $150 = **$5,130.00**.
     *
     * El reembolso lo resta el SERVER (`postCashRefundToDrawer`) y baja como PAY_OUT
     * por el sync. Si la tablet sigue leyendo su sesión local, ese PAY_OUT no existe
     * para ella y el corte dice $5,280 — los $150 de sobrante que el cajero no puede
     * explicar.
     */
    @Test
    fun `el esperado del corte CUADRA con lo que dice el server`() = runTest {
        val dao = FakeCashDrawerDao()
        val alAbrir = cashDrawerRepo(dao, cashDrawerClient("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer)))
        alAbrir.openSession(FONDO_CENTS)
        alAbrir.addCashSale(VENTA_CENTS, "order-9") // el POS pinta la venta al instante

        val alSincronizar = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "280.00"),
                    eventoJson("srv-ev-reembolso", "PAY_OUT", "150.00", note = "Reembolso: Producto defectuoso"),
                ),
            ),
        )
        alSincronizar.syncFromApi()

        val sesion = alSincronizar.getOpenSession()!!
        assertEquals(
            "el esperado no cuadra con el server (fondo 5000 + venta 280 − reembolso 150)",
            513_000,
            alSincronizar.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - 5. La tablet que NO abrió la caja

    /**
     * Esta tablet nunca tuvo sesión local: lee la del server desde el primer sync.
     * Su venta en efectivo se escribe local Y vuelve confirmada por el server con
     * otro id. Sin reconciliación, la MISMA venta se cuenta dos veces.
     */
    @Test
    fun `la tablet que NO abrio la caja no cuenta la venta dos veces`() = runTest {
        val dao = FakeCashDrawerDao()
        val primerSync = cashDrawerRepo(dao, cashDrawerClient("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer)))
        primerSync.syncFromApi()

        primerSync.addCashSale(VENTA_CENTS, "order-9")

        val segundoSync = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "280.00"),
                ),
            ),
        )
        segundoSync.syncFromApi()

        val sesion = segundoSync.getOpenSession()!!
        assertEquals(
            "la venta de \$280 se contó dos veces",
            FONDO_CENTS + VENTA_CENTS,
            segundoSync.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - 6. Los movimientos que sí son del cliente

    /**
     * Un retiro CON red lo escribe la tablet y lo confirma el server con su propio
     * id. Es el mismo problema de identidad que la sesión, un nivel abajo: si la
     * fila local no adopta el id confirmado, el eco del sync la duplica y el cajón
     * resta $100 por un retiro de $50.
     */
    @Test
    fun `un retiro confirmado por el server no se cuenta dos veces`() = runTest {
        val dao = FakeCashDrawerDao()
        val abrir = cashDrawerRepo(dao, cashDrawerClient("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer)))
        abrir.openSession(FONDO_CENTS)

        val retirar = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/pay-out" to
                    """{"success":true,"data":${eventoJson("srv-ev-retiro", "PAY_OUT", "50.00", note = "Propinas")}}""",
            ),
        )
        retirar.addPayOut(5_000, "Propinas")

        val sincronizar = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-retiro", "PAY_OUT", "50.00", note = "Propinas"),
                ),
            ),
        )
        sincronizar.syncFromApi()

        val sesion = sincronizar.getOpenSession()!!
        assertEquals(
            "el retiro de \$50 se restó dos veces",
            FONDO_CENTS - 5_000,
            sincronizar.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    /**
     * El contrapeso del test anterior: la limpieza NO puede borrar un movimiento que
     * el server todavía no conoce. Un retiro hecho sin red tiene que seguir
     * restando, o el cajero cierra con un faltante que sí existe físicamente.
     */
    @Test
    fun `un retiro que el server aun no conoce NO se borra al sincronizar`() = runTest {
        val dao = FakeCashDrawerDao()
        val abrir = cashDrawerRepo(dao, cashDrawerClient("/cash-drawer/open" to sesionJson("srv-1", aperturaDelServer)))
        abrir.openSession(FONDO_CENTS)

        val sinRed = cashDrawerRepo(dao, cashDrawerClient()) // el POST del retiro no sale
        sinRed.addPayOut(5_000, "Compra de hielo")

        val sincronizar = cashDrawerRepo(dao, cashDrawerClient("/cash-drawer/current" to sesionJson("srv-1", aperturaDelServer)))
        sincronizar.syncFromApi()

        val sesion = sincronizar.getOpenSession()!!
        assertEquals(
            "el retiro pendiente de sincronizar se borró",
            FONDO_CENTS - 5_000,
            sincronizar.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - 7. Las tablets que YA quedaron con las dos filas

    /**
     * 🔴 El estado en el que están los aparatos HOY: dos sesiones OPEN del mismo
     * local y los eventos repartidos entre las dos.
     *
     * La decisión es NO migrar la base: ninguna migración puede saber cuál fila es
     * la del server ni qué eventos locales ya viajaron, así que cualquier fusión a
     * ciegas o pierde movimientos o los cuenta doble. Se reconcilia en el PRIMER
     * sync, que es cuando la única fuente que puede desempatar —el server— está
     * disponible. Este test es esa promesa.
     *
     * Las dos filas son del MISMO turno: la local nació minutos después de que el
     * server abriera la caja, que es como se ve el estado heredado real. Ese detalle
     * dejó de ser cosmético cuando la reconciliación se acotó a la ventana de la caja
     * del server — una fila de OTRO turno ya no se fusiona (ver
     * [CashDrawerEventKeyMergeTest]).
     */
    @Test
    fun `una tablet que ya tiene las DOS filas se reconcilia en el siguiente sync`() = runTest {
        val dao = FakeCashDrawerDao()
        val cajaDelServerAbrioHace = haceMinutos(60)

        // Estado heredado: la fila local del mismo turno …
        val local = sesionLocal("local-uuid", openedAt = haceMinutos(55))
        dao.insertSession(local)
        dao.insertEvent(
            eventoLocal("local-ev-open", local.id, "OPEN", FONDO_CENTS, createdAt = haceMinutos(55)),
        )
        dao.insertEvent(
            eventoLocal("local-ev-venta", local.id, "CASH_SALE", VENTA_CENTS, orderId = "order-9", createdAt = haceMinutos(40)),
        )
        // … y la del server, insertada después por el sync viejo.
        dao.insertSession(sesionLocal("srv-1", openedAt = cajaDelServerAbrioHace))

        val repo = cashDrawerRepo(
            dao,
            cashDrawerClient(
                "/cash-drawer/current" to sesionJson(
                    "srv-1",
                    aperturaDelServer,
                    eventoJson("srv-ev-venta", "CASH_SALE", "280.00"),
                    eventoJson("srv-ev-reembolso", "PAY_OUT", "150.00", note = "Reembolso: Producto defectuoso"),
                    openedAt = cajaDelServerAbrioHace,
                ),
            ),
        )
        repo.syncFromApi()

        assertEquals("quedó más de una caja abierta: ${dao.sessions.keys}", 1, dao.sessions.size)
        assertEquals("srv-1", dao.sessions.keys.first())
        val sesion = repo.getOpenSession()!!
        assertEquals(
            "la venta heredada se contó dos veces tras la reconciliación",
            513_000,
            repo.computeExpectedAmount(sesion.id, sesion.startingAmountCents),
        )
    }

    // MARK: - 8. Cinturón y tirantes: la consulta tiene que ser determinista

    /**
     * `LIMIT 1` sin `ORDER BY` deja que SQLite escoja: hoy devuelve el rowid más
     * bajo, pero eso es un detalle de implementación, no un contrato — un `VACUUM`
     * o un índice nuevo lo cambian sin avisar. Aunque la promoción ya evite el
     * duplicado, la consulta que decide QUÉ CAJA está abierta no puede depender del
     * azar. Criterio: la más reciente por `openedAt`, con `id` como desempate — el
     * mismo orden que ya usa iOS (`CashDrawerStore`, `.order(Column("openedAt").desc)`),
     * porque una caja vieja pegaría las ventas de hoy al fondo de ayer.
     */
    @Test
    fun `la consulta de la caja abierta es determinista`() {
        val dao = leerFuente("app/src/main/java/com/avoqado/pos/cashdrawer/data/CashDrawerDao.kt")
        val consulta = dao.substringAfter("status = 'OPEN'").substringBefore("suspend fun getOpenSession(")
        assertTrue(
            "getOpenSession sigue siendo LIMIT 1 sin ORDER BY: qué caja está abierta lo decide SQLite.",
            consulta.contains("ORDER BY"),
        )
    }

    private fun leerFuente(rutaRelativa: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidato = File(dir, rutaRelativa)
            if (candidato.isFile) return candidato.readText()
            val sinModulo = File(dir, rutaRelativa.removePrefix("app/"))
            if (sinModulo.isFile) return sinModulo.readText()
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró $rutaRelativa desde ${System.getProperty("user.dir")}")
    }
}
