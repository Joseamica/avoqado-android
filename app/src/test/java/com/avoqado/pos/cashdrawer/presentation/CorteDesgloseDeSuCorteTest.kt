package com.avoqado.pos.cashdrawer.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * hz:nube0000:na12fc7f (17-nov-2026): el desglose por método de pago era UN solo estado para todos los cortes.
 *  1. Abrir el corte B con su lectura en vuelo enseñaba (y dejaba imprimir) los importes del corte A.
 *  2. Imprimir el corte parcial escribía en la pantalla: una lectura fallida tapaba un desglose bueno, y una impresión que se
 *     quedó esperando escribía su desglose encima del corte que se abrió después.
 * Ahora cada corte tiene su propio desglose, por su llave ([llaveDelCorte]), y dentro de un corte sólo publica la lectura que
 * empezó al último (la de la pantalla o la de la impresión del parcial).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CorteDesgloseDeSuCorteTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private fun pantalla(repo: CashDrawerRepository) = CashDrawerViewModel(
        repository = repo,
        printerService = mockk(relaxed = true),
        roleManager = mockk(relaxed = true),
        tpvSettingsRepository = mockk(relaxed = true),
        conectividad = mockk(relaxed = true),
        permissionOverrideRepository = mockk(relaxed = true),
        comprobantes = mockk(relaxed = true),
        secureStorage = mockk(relaxed = true),
    )

    private fun caja(id: String, openedAt: Long, closedAt: Long? = null) = CashDrawerSessionEntity(
        id = id, venueId = "venue-1", deviceName = "D3", openedByStaffId = "staff-1", openedByName = "Ana",
        openedAt = openedAt, startingAmountCents = 50_000, status = if (closedAt == null) "OPEN" else "CLOSED", closedAt = closedAt,
    )

    private val deA = listOf(CashDrawerRepository.TenderRow("CASH", 12_000), CashDrawerRepository.TenderRow("CREDIT_CARD", 80_000))
    private val deB = listOf(CashDrawerRepository.TenderRow("CASH", 3_000))

    @Test fun `P1 el corte B con su lectura en vuelo no enseña el desglose del corte A`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val lecturaDeB = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        coEvery { repo.getTenderBreakdown(10L, 20L) } returns deA
        coEvery { repo.getTenderBreakdown(30L, 40L) } coAnswers { lecturaDeB.await() }
        val vm = pantalla(repo)
        val a = llaveDelCorte(caja("caja-A", 10L, 20L))
        val b = llaveDelCorte(caja("caja-B", 30L, 40L))

        vm.loadTenderBreakdown(a, 10L, 20L)
        assertEquals(deA, vm.desgloses.value[a]?.filas)
        vm.loadTenderBreakdown(b, 30L, 40L)

        assertNull("B, mientras consulta, enseña (e imprimiría) el desglose de A", vm.desgloses.value[b]?.filas)
        assertTrue("B dice «Consultando…»", estaConsultando(vm.desgloses.value[b]))
        assertEquals("y A conserva lo suyo", deA, vm.desgloses.value[a]?.filas)
        lecturaDeB.complete(deB)
        assertEquals(deB, vm.desgloses.value[b]?.filas)
    }

    @Test fun `el parcial y el definitivo de la misma caja son cortes distintos`() {
        val parcial = llaveDelCorte(caja("caja-A", 10L))
        val definitivo = llaveDelCorte(caja("caja-A", 10L, 20L))
        assertNotEquals(parcial, definitivo)
    }

    @Test fun `P1 imprimir el parcial sin red no borra el desglose bueno de la pantalla`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val parcial = caja("caja-A", 10L)
        coEvery { repo.getSession("caja-A") } returns parcial
        var lecturas = 0
        coEvery { repo.getTenderBreakdown(10L, any()) } coAnswers { lecturas++; if (lecturas == 1) deA else null }
        val vm = pantalla(repo)
        val llave = llaveDelCorte(parcial)

        vm.loadTenderBreakdown(llave, 10L, 15L)
        vm.printPartialCorte(parcial, emptyList()) // su propia lectura falla (sin red)

        assertEquals("imprimir escribió en la pantalla", deA, vm.desgloses.value[llave]?.filas)
    }

    @Test fun `P1 una impresion del parcial que se quedo esperando no escribe en el corte que se abrio despues`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val parcial = caja("caja-A", 10L)
        val historico = caja("caja-B", 30L, 40L)
        val espera = CompletableDeferred<Boolean>()
        coEvery { repo.getSession("caja-A") } returns parcial
        coEvery { repo.syncFromApi() } coAnswers { espera.await() }
        coEvery { repo.getTenderBreakdown(10L, any()) } returns deA
        coEvery { repo.getTenderBreakdown(30L, 40L) } returns deB
        val vm = pantalla(repo)

        vm.printPartialCorte(parcial, emptyList()) // se queda en sincronizar()
        val b = llaveDelCorte(historico)
        vm.loadTenderBreakdown(b, 30L, 40L)
        espera.complete(false)

        assertEquals("la impresión del parcial escribió encima del corte B", deB, vm.desgloses.value[b]?.filas)
    }

    /** Codex r1 #6: lo que SÍ servía de escribir desde la impresión: la lectura fresca del MISMO parcial, que sigue abierto. */
    @Test fun `la impresion exitosa del mismo parcial si pone su lectura fresca en la pantalla`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val parcial = caja("caja-A", 10L)
        coEvery { repo.getSession("caja-A") } returns parcial
        var lecturas = 0
        coEvery { repo.getTenderBreakdown(10L, any()) } coAnswers { lecturas++; if (lecturas == 1) deA else deB }
        val vm = pantalla(repo)
        val llave = llaveDelCorte(parcial)

        vm.loadTenderBreakdown(llave, 10L, 15L)
        vm.printPartialCorte(parcial, emptyList())

        assertEquals(deB, vm.desgloses.value[llave]?.filas)
    }

    /**
     * Codex r1 #4: mientras la impresión del parcial esperaba, otro aparato cerró la caja (y pudo abrir otra y cobrar). Pedir
     * «hasta ahora» sumaba los cobros de la caja siguiente: el servidor suma los pagos de la sucursal en esa ventana.
     */
    @Test fun `P1 si la caja se cerro mientras se imprimia el parcial la consulta termina en su cierre`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val parcial = caja("caja-A", 10L)
        coEvery { repo.getSession("caja-A") } returns caja("caja-A", 10L, closedAt = 50L)
        coEvery { repo.getTenderBreakdown(any(), any()) } returns deA
        val vm = pantalla(repo)

        vm.printPartialCorte(parcial, emptyList())

        coVerify { repo.getTenderBreakdown(10L, 50L) }
    }

    /** Codex r2 #4: dos toques en «Imprimir»; la primera lectura, lenta y más vieja, no pisa a la segunda. */
    @Test fun `P1 con dos impresiones del parcial la lectura mas vieja no pisa a la mas nueva`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val parcial = caja("caja-A", 10L)
        coEvery { repo.getSession("caja-A") } returns parcial
        val lenta = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        var lecturas = 0
        coEvery { repo.getTenderBreakdown(10L, any()) } coAnswers { lecturas++; if (lecturas == 1) lenta.await() else deB }
        coEvery { repo.getTenderBreakdown(10L, 15L) } returns deA
        val vm = pantalla(repo)
        val llave = llaveDelCorte(parcial)

        vm.loadTenderBreakdown(llave, 10L, 15L)
        vm.printPartialCorte(parcial, emptyList()) // se queda esperando su lectura
        vm.printPartialCorte(parcial, emptyList()) // la segunda llega primero
        lenta.complete(deA)

        assertEquals(deB, vm.desgloses.value[llave]?.filas)
    }

    /** Codex r3 #3: tras un «No se pudo consultar», «Reintentar» enseña «Consultando…» mientras llega, no el fallo viejo. */
    @Test fun `reintentar tras un fallo dice consultando mientras llega`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val segunda = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        var lecturas = 0
        coEvery { repo.getTenderBreakdown(10L, 20L) } coAnswers { lecturas++; if (lecturas == 1) null else segunda.await() }
        val vm = pantalla(repo)
        val a = llaveDelCorte(caja("caja-A", 10L, 20L))

        vm.loadTenderBreakdown(a, 10L, 20L)
        assertFalse("tras el fallo dice «No se pudo consultar»", estaConsultando(vm.desgloses.value[a]))
        vm.loadTenderBreakdown(a, 10L, 20L) // «Reintentar»
        assertTrue(estaConsultando(vm.desgloses.value[a]))
        segunda.complete(deA)
        assertEquals(deA, vm.desgloses.value[a]?.filas)
    }

    /** Codex r3 #2: el parcial abierto con su primera lectura lenta; la impresión consigue una buena y la pantalla la enseña. */
    @Test fun `la impresion del parcial con la primera lectura atorada si actualiza la pantalla`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val parcial = caja("caja-A", 10L)
        coEvery { repo.getSession("caja-A") } returns parcial
        val atorada = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        coEvery { repo.getTenderBreakdown(10L, any()) } returns deB
        coEvery { repo.getTenderBreakdown(10L, 15L) } coAnswers { atorada.await() }
        val vm = pantalla(repo)
        val llave = llaveDelCorte(parcial)

        vm.loadTenderBreakdown(llave, 10L, 15L)
        vm.printPartialCorte(parcial, emptyList())
        assertEquals(deB, vm.desgloses.value[llave]?.filas)
        atorada.complete(deA) // la vieja llega tarde y no pisa
        assertEquals(deB, vm.desgloses.value[llave]?.filas)
    }

    /** Codex r4 #2: el parcial que antes no tenía cobros, reabierto sin red, dice «No se pudo consultar», no «No hubo cobros». */
    @Test fun `un parcial reabierto sin red no se queda con la lista vacia de antes`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        coEvery { repo.getTenderBreakdown(10L, any()) } returnsMany listOf(emptyList(), null)
        val vm = pantalla(repo)
        val llave = llaveDelCorte(caja("caja-A", 10L))

        vm.loadTenderBreakdown(llave, 10L, 15L)
        assertEquals(emptyList<CashDrawerRepository.TenderRow>(), vm.desgloses.value[llave]?.filas)
        vm.loadTenderBreakdown(llave, 10L, 30L) // se reabre más tarde, sin red

        assertNull(vm.desgloses.value[llave]?.filas)
        assertFalse(estaConsultando(vm.desgloses.value[llave]))
    }

    /** Codex r4 #3: el reintento que pidió la vuelta de la red no se pierde porque se imprimió el parcial (sin red) en medio. */
    @Test fun `imprimir el parcial no borra el reintento pedido al volver la red`() {
        val repo = mockk<CashDrawerRepository>(relaxed = true)
        val parcial = caja("caja-A", 10L)
        coEvery { repo.getSession("caja-A") } returns parcial
        val primera = CompletableDeferred<List<CashDrawerRepository.TenderRow>?>()
        var dePantalla = 0
        coEvery { repo.getTenderBreakdown(10L, any()) } returns null // la del papel falla
        coEvery { repo.getTenderBreakdown(10L, 15L) } coAnswers { dePantalla++; if (dePantalla == 1) primera.await() else deA }
        val vm = pantalla(repo)
        val llave = llaveDelCorte(parcial)

        vm.loadTenderBreakdown(llave, 10L, 15L)
        vm.recargarDesgloseSiFalta(llave, 10L, 15L) // volvió la red con la lectura en vuelo
        vm.printPartialCorte(parcial, emptyList())
        primera.complete(null) // y la de la pantalla falló

        assertEquals(2, dePantalla)
        assertEquals(deA, vm.desgloses.value[llave]?.filas)
    }
}
