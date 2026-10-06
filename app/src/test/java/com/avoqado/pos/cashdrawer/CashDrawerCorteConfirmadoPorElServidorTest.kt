package com.avoqado.pos.cashdrawer

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.CorteTicketBuilder
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.cashdrawer.presentation.CashDrawerViewModel
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.printing.data.PrinterService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 EL CORTE DE LA TABLET TIENE QUE DECIR LO MISMO QUE EL SERVIDOR.
 *
 * Caso real (Testarudo, 28-sep-2026, tarea de Asana «No cuadra corte z con efectivo real»): a
 * las 10:06 se devolvieron $145 en efectivo desde el dashboard. El servidor anotó el egreso en la
 * caja (`srv-refund:<id>`) y cerró con diferencia $0.00, pero la tablet marcó «Faltante $145».
 * La pantalla de Caja sólo le pedía al servidor sus movimientos al crear el ViewModel, que vivía
 * días enteros: Better Stack no registró un solo `GET /cash-drawer/current` en dos días. El
 * dinero estaba completo; el corte que archivó el negocio decía que no.
 *
 * Tres puertas, una prueba por puerta:
 *  1. entrar a Caja pregunta al servidor CADA vez (no sólo la primera);
 *  2. el cierre confirmado adopta la caja que devuelve el servidor, con todos sus movimientos;
 *  3. el historial trae los movimientos de las cajas cerradas, así que reimprimir corrige.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashDrawerCorteConfirmadoPorElServidorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val pantallas = mutableListOf<CashDrawerViewModel>()

    @After
    fun esperarALasPantallas() = pantallas.forEach { it.esperarSuTrabajo() }

    // MARK: - Los números del caso (en centavos)

    private val fondo = 200_000 // $2,000.00
    private val ventas = 413_575 // $4,135.75
    private val servilletas = 10_500 // $105.00
    private val reembolso = 14_500 // $145.00
    private val enElCajon = fondo + ventas - servilletas - reembolso // $5,885.75: lo que de verdad había

    private fun pesos(cents: Int) = String.format(java.util.Locale.US, "%.2f", cents / 100.0)

    /** Un `SecureStorage` que de verdad guarda la cola: sin eso el cierre nunca sale. */
    private fun almacen(): SecureStorage = mockk<SecureStorage>(relaxed = true).also { st ->
        var cola: String? = null
        every { st.venueId } returns VENUE_ID
        every { st.userId } returns "staff-1"
        every { st.pendingDrawerOpsJson(any()) } answers { cola }
        every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
    }

    private fun repo(dao: FakeCashDrawerDao, vararg rutas: Pair<String, String>, capturadas: MutableList<LlamadaCapturada>? = null) =
        CashDrawerRepository(
            dao = dao,
            secureStorage = almacen(),
            client = cashDrawerClient(*rutas, capturadas = capturadas),
            pendingCashSales = sinCobrosEnCola(),
            conectividad = conectividadDePrueba(),
        )

    /** La caja de la tablet tal como estaba: sus ventas y su retiro, pero SIN el reembolso. */
    private fun cajaDeLaTablet(status: String = "OPEN", overShortCents: Int? = null): FakeCashDrawerDao {
        val abierta = haceMinutos(600)
        return FakeCashDrawerDao().apply {
            sessions["srv-1"] = CashDrawerSessionEntity(
                id = "srv-1",
                venueId = VENUE_ID,
                deviceName = "SUNMI D3",
                openedByStaffId = "staff-1",
                openedByName = "Ana Ruiz",
                openedAt = abierta,
                startingAmountCents = fondo,
                status = status,
                actualAmountCents = if (status == "CLOSED") enElCajon else null,
                overShortCents = overShortCents,
                closedAt = if (status == "CLOSED") haceMinutos(5) else null,
            )
            fun evento(id: String, type: CashDrawerEventType, cents: Int, note: String? = null, min: Long) {
                events[id] = CashDrawerEventEntity(
                    id = id, sessionId = "srv-1", venueId = VENUE_ID, type = type.name, amountCents = cents,
                    note = note, staffId = "staff-1", staffName = "Ana Ruiz", createdAt = haceMinutos(min),
                )
            }
            evento("srv-open", CashDrawerEventType.OPEN, fondo, min = 600)
            // La venta que escribió la propia tablet: copia local, con SU id.
            evento("loc-venta", CashDrawerEventType.CASH_SALE, ventas, min = 500)
            // El retiro de servilletas ya confirmado (promovido al id del servidor al escribirse).
            evento("srv-servilletas", CashDrawerEventType.PAY_OUT, servilletas, "servilletas", min = 300)
            if (status == "CLOSED") evento("loc-close", CashDrawerEventType.CLOSE, enElCajon, min = 5)
        }
    }

    /** La caja como la conoce el SERVIDOR: con el egreso del reembolso que escribió él solo. */
    private fun eventosDelServidor(conCierre: Boolean) = buildList {
        add(eventoJson("srv-open", "OPEN", pesos(fondo), createdAt = haceMinutos(600)))
        add(eventoJson("srv-venta", "CASH_SALE", pesos(ventas), localId = "srv-cash-sale:pay-1", createdAt = haceMinutos(500)))
        add(eventoJson("srv-refund-1", "PAY_OUT", pesos(reembolso), note = "Reembolso: Cobro por error", localId = "srv-refund:ref-1", createdAt = haceMinutos(480)))
        add(eventoJson("srv-servilletas", "PAY_OUT", pesos(servilletas), note = "servilletas", localId = "loc-serv", createdAt = haceMinutos(300)))
        if (conCierre) add(eventoJson("srv-close", "CLOSE", pesos(enElCajon), createdAt = haceMinutos(1)))
    }.toTypedArray()

    private fun cajaCerradaJson(vararg eventos: String) = """
        {"id":"srv-1","venueId":"venue-1","deviceName":"SUNMI D3","status":"CLOSED",
         "openedByStaffId":"staff-1","openedByName":"Ana Ruiz",
         "openedAt":"${isoDe(haceMinutos(600))}","startingAmount":${pesos(fondo)},
         "closedByStaffId":"staff-1","closedByName":"Ana Ruiz","closedAt":"${isoDe(haceMinutos(1))}",
         "actualAmount":${pesos(enElCajon)},"expectedAmount":${pesos(enElCajon)},"overShort":0,
         "closingNote":null,"events":[${eventos.joinToString(",")}]}
    """.trimIndent()

    private suspend fun FakeCashDrawerDao.egresosDe(sessionId: String) =
        getSessionEvents(sessionId).filter { it.type == CashDrawerEventType.PAY_OUT.name }

    // MARK: - 2 · El cierre adopta lo que el servidor sabe

    /**
     * 🔴 EL CASO DE TESTARUDO: el reembolso nunca bajó a la tablet. Al cerrar, el servidor contesta
     * con la caja entera; esa respuesta ya no se tira. El corte que se pinta y se imprime sale de
     * ella, así que dice $0.00 como el servidor y no «Faltante $145».
     */
    @Test
    fun `P1 el cierre confirmado trae el reembolso que la tablet no tenia`() = runTest {
        val dao = cajaDeLaTablet()
        val repo = repo(dao, "/cash-drawer/close" to """{"success":true,"data":${cajaCerradaJson(*eventosDelServidor(conCierre = true))}}""")

        repo.closeSession(enElCajon, "salvaguardar")

        val egresos = dao.egresosDe("srv-1")
        assertTrue(
            "el reembolso de \$145 no llegó al corte: $egresos",
            egresos.any { it.amountCents == reembolso && it.note?.startsWith(CorteTicketBuilder.PREFIJO_REEMBOLSO) == true },
        )
        assertEquals("el esperado tiene que ser lo que había en el cajón", enElCajon, repo.computeExpectedAmount("srv-1", fondo))
        assertEquals("la diferencia es la del servidor, no un faltante inventado", 0, dao.getSession("srv-1")?.overShortCents)
    }

    /** Adoptar no puede DUPLICAR: la venta y el cierre de la tablet son los mismos que los del servidor. */
    @Test
    fun `adoptar el cierre no cuenta dos veces la venta ni el cierre`() = runTest {
        val dao = cajaDeLaTablet()
        val repo = repo(dao, "/cash-drawer/close" to """{"success":true,"data":${cajaCerradaJson(*eventosDelServidor(conCierre = true))}}""")

        repo.closeSession(enElCajon, "salvaguardar")

        val eventos = dao.getSessionEvents("srv-1")
        assertEquals("ventas: $eventos", listOf(ventas), eventos.filter { it.type == CashDrawerEventType.CASH_SALE.name }.map { it.amountCents })
        assertEquals("cierres: $eventos", 1, eventos.count { it.type == CashDrawerEventType.CLOSE.name })
        assertEquals("el retiro de servilletas va UNA vez", 1, eventos.count { it.amountCents == servilletas })
    }

    /**
     * SIN RED el cierre sigue en la cola y el corte se queda con lo que sabe la tablet — no se
     * inventa nada ni se pierde nada. Que el corte lo DIGA es trabajo de la pantalla; aquí se fija
     * que el repositorio reporte que falta confirmar.
     */
    @Test
    fun `sin red el cierre queda pendiente y los movimientos locales intactos`() = runTest {
        val dao = cajaDeLaTablet()
        val repo = repo(dao) // ninguna ruta: todo revienta como "sin red"

        repo.closeSession(enElCajon, "salvaguardar")

        assertTrue("el cierre tiene que quedar en la cola", repo.tieneCierrePendiente("srv-1"))
        assertEquals("nada se inventa sin red", listOf(servilletas), dao.egresosDe("srv-1").map { it.amountCents })
    }

    /**
     * Si el servidor contesta con OTRA caja (no la que se mandó a cerrar), no se adopta: meterla
     * sería pegarle a esta tablet los movimientos de una caja ajena.
     */
    @Test
    fun `una respuesta de cierre con otra caja no se adopta`() = runTest {
        val dao = cajaDeLaTablet()
        val ajena = cajaCerradaJson(*eventosDelServidor(conCierre = true)).replace("\"id\":\"srv-1\"", "\"id\":\"srv-OTRA\"")
        val repo = repo(dao, "/cash-drawer/close" to """{"success":true,"data":$ajena}""")

        repo.closeSession(enElCajon, "salvaguardar")

        assertEquals("no se mezclan cajas", listOf(servilletas), dao.egresosDe("srv-1").map { it.amountCents })
        assertEquals("ni aparece una caja de más", null, dao.getSession("srv-OTRA"))
    }

    // MARK: - 3 · El historial corrige los cortes viejos

    /**
     * 🔴 El corte del 28 ya quedó guardado en la tablet con el faltante falso. El historial del
     * servidor trae los movimientos de cada caja cerrada; antes se tiraban y reimprimir repetía
     * el error para siempre.
     */
    @Test
    fun `P1 el historial trae los movimientos de una caja cerrada`() = runTest {
        val dao = cajaDeLaTablet(status = "CLOSED", overShortCents = -reembolso)
        val repo = repo(
            dao,
            "/cash-drawer/current" to """{"success":true,"data":null}""",
            "/cash-drawer/history" to """{"success":true,"sessions":[${cajaCerradaJson(*eventosDelServidor(conCierre = true))}],"pagination":{"page":1,"pageSize":20,"total":1,"totalPages":1}}""",
        )

        repo.syncFromApi()

        assertTrue(
            "el reembolso tenía que llegar a la caja cerrada: ${dao.egresosDe("srv-1")}",
            dao.egresosDe("srv-1").any { it.amountCents == reembolso },
        )
        assertEquals(enElCajon, repo.computeExpectedAmount("srv-1", fondo))
        assertEquals(0, dao.getSession("srv-1")?.overShortCents)
        assertEquals("un solo cierre", 1, dao.getSessionEvents("srv-1").count { it.type == CashDrawerEventType.CLOSE.name })
    }

    // MARK: - El sync dice si el servidor contestó

    @Test
    fun `syncFromApi dice si el servidor contesto`() = runTest {
        val sinRed = repo(cajaDeLaTablet())
        assertFalse("sin red no hay confirmación", sinRed.syncFromApi())

        val conRed = repo(
            cajaDeLaTablet(),
            "/cash-drawer/current" to sesionJson("srv-1", *eventosDelServidor(conCierre = false), openedAt = haceMinutos(600), startingAmount = fondo / 100.0),
            "/cash-drawer/history" to """{"success":true,"sessions":[]}""",
        )
        assertTrue(conRed.syncFromApi())
    }

    // MARK: - 1 · Entrar a Caja pregunta al servidor CADA vez

    /** El POST/GET del cajón vive en `Dispatchers.IO`: se espera al estado, con tope. */
    private fun esperar(que: String, cond: () -> Boolean) {
        val limite = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < limite) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail(que)
    }

    /**
     * 🔴 LA CAUSA RAÍZ: el ViewModel de Caja sobrevive entre visitas, y volver a entrar sólo leía
     * Room. El reembolso hecho después de la primera visita no aparecía nunca. Ahora cada entrada
     * pregunta al servidor, como ya hacía iOS (`CashDrawerView.onAppear → loadCurrentDrawer`).
     */
    @Test
    fun `P1 volver a entrar a Caja vuelve a preguntar al servidor y trae el reembolso`() {
        val llamadas = java.util.Collections.synchronizedList(mutableListOf<LlamadaCapturada>())
        val dao = cajaDeLaTablet()
        val vm = CashDrawerViewModel(
            repository = repo(
                dao,
                "/cash-drawer/current" to sesionJson("srv-1", *eventosDelServidor(conCierre = false), openedAt = haceMinutos(600), startingAmount = fondo / 100.0),
                "/cash-drawer/history" to """{"success":true,"sessions":[]}""",
                capturadas = llamadas,
            ),
            printerService = mockk<PrinterService>(relaxed = true),
            roleManager = mockk<RoleManager>(relaxed = true).also { every { it.hasVenuePermission(any(), any()) } returns true },
        ).also { pantallas += it }
        fun consultas() = synchronized(llamadas) { llamadas.count { it.path.endsWith("/cash-drawer/current") } }

        esperar("la primera visita no preguntó al servidor") { consultas() >= 1 }
        val antes = consultas()

        vm.alEntrar()

        esperar("volver a entrar NO preguntó al servidor: la tablet se queda con su copia vieja") { consultas() > antes }
        esperar("el reembolso no llegó a la pantalla de Caja: ${vm.events.value}") {
            vm.events.value.any { it.amountCents == reembolso && it.type == CashDrawerEventType.PAY_OUT.name }
        }
        esperar("el esperado de la pantalla no cuadra con el cajón: ${vm.expectedAmountCents.value}") {
            vm.expectedAmountCents.value == enElCajon
        }
    }
}
