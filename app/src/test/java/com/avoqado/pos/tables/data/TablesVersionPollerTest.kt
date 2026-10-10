package com.avoqado.pos.tables.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Qué se pide al entrar, cada 10 s y al refrescar (spec 2026-10-09 §4.1). Espejo: avoqado-ios `TablesVersionPollerTests`. */
class TablesVersionPollerTest {

    private val mesas = mockk<TableServiceRepository>(relaxed = true)
    private val plano = mockk<FloorPlanRepository>(relaxed = true)
    private var respuesta: ConsultaDeVersiones = ConsultaDeVersiones.Versiones("t1", "p1")
    private var consultas = 0
    private val poller = TablesVersionPoller(
        mesas,
        plano,
        object : FuenteDeVersiones {
            override suspend fun consultar(venueId: String): ConsultaDeVersiones {
                consultas++
                return respuesta
            }
        },
    )

    @Before
    fun todoAlDia() {
        coEvery { mesas.refresh("v1") } returns Result.success(emptyList())
        every { mesas.tablesVersion } returns "t1"
        every { mesas.floorPlanVersionFromTables } returns "p1"
        every { plano.floorPlanVersion } returns "p1"
        coEvery { plano.refresh("v1") } returns Result.success(FloorPlanPayload(floorPlanVersion = "p1"))
    }

    @Test
    fun `al entrar pinta lo guardado y luego pide las mesas`() = runTest {
        poller.entrar("v1")
        coVerifyOrder {
            mesas.hydrate("v1")
            plano.hydrate("v1")
            mesas.refresh("v1")
        }
    }

    @Test
    fun `al entrar pide el plano si no hay plano guardado`() = runTest {
        every { plano.floorPlanVersion } returns null
        poller.entrar("v1")
        coVerify(exactly = 1) { plano.refresh("v1") }
    }

    @Test
    fun `al entrar pide el plano si las mesas traen otra version del plano`() = runTest {
        every { mesas.floorPlanVersionFromTables } returns "p2"
        poller.entrar("v1")
        coVerify(exactly = 1) { plano.refresh("v1") }
    }

    @Test
    fun `al entrar no pide el plano si la version coincide`() = runTest {
        poller.entrar("v1")
        coVerify(exactly = 0) { plano.refresh(any()) }
    }

    @Test
    fun `version igual no pide mesas ni plano`() = runTest {
        poller.consultar("v1")
        assertEquals(1, consultas)
        coVerify(exactly = 0) { mesas.refresh(any()) }
        coVerify(exactly = 0) { plano.refresh(any()) }
    }

    @Test
    fun `cambia la version de mesas y pide solo mesas`() = runTest {
        respuesta = ConsultaDeVersiones.Versiones("t2", "p1")
        poller.consultar("v1")
        coVerify(exactly = 1) { mesas.refresh("v1") }
        coVerify(exactly = 0) { plano.refresh(any()) }
    }

    @Test
    fun `cambia la version del plano y pide solo el plano`() = runTest {
        respuesta = ConsultaDeVersiones.Versiones("t1", "p2")
        poller.consultar("v1")
        coVerify(exactly = 0) { mesas.refresh(any()) }
        coVerify(exactly = 1) { plano.refresh("v1") }
    }

    @Test
    fun `servidor viejo 404 vuelve a pedir las mesas completas cada vez`() = runTest {
        respuesta = ConsultaDeVersiones.ServidorViejo
        poller.consultar("v1")
        poller.consultar("v1")
        assertTrue(poller.servidorViejo)
        assertEquals("la versión se pregunta una vez y se recuerda", 1, consultas)
        coVerify(exactly = 2) { mesas.refresh("v1") }
    }

    @Test
    fun `mesas sin version es servidor viejo y no pide plano ni version`() = runTest {
        every { mesas.tablesVersion } returns null
        poller.entrar("v1")
        poller.consultar("v1")
        assertTrue(poller.servidorViejo)
        assertEquals(0, consultas)
        coVerify(exactly = 0) { plano.refresh(any()) }
        coVerify(exactly = 2) { mesas.refresh("v1") }
    }

    @Test
    fun `sin red no pide nada y no borra lo guardado`() = runTest {
        respuesta = ConsultaDeVersiones.SinRespuesta
        poller.consultar("v1")
        assertFalse(poller.servidorViejo)
        coVerify(exactly = 0) { mesas.refresh(any()) }
        coVerify(exactly = 0) { plano.refresh(any()) }
    }

    @Test
    fun `refrescar pide mesas y plano sin mirar versiones`() = runTest {
        poller.refrescarTodo("v1")
        assertEquals(0, consultas)
        coVerify(exactly = 1) { mesas.refresh("v1") }
        coVerify(exactly = 1) { plano.refresh("v1") }
    }

    @Test
    fun `volver a entrar olvida el servidor viejo`() = runTest {
        respuesta = ConsultaDeVersiones.ServidorViejo
        poller.consultar("v1")
        assertTrue(poller.servidorViejo)
        poller.entrar("v1")
        assertFalse(poller.servidorViejo)
    }
}
