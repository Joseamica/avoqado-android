package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

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
    fun `cambia la version del plano y pide el plano y las mesas`() = runTest {
        // Publicar sólo paredes o un nombre de área no mueve `tablesVersion` (el servidor publica por diferencias). Si no
        // se piden también las mesas, la versión del plano que ellas anuncian se queda atrás y el plano nuevo se vería
        // «atrasado» (enmienda A10).
        respuesta = ConsultaDeVersiones.Versiones("t1", "p2")
        poller.consultar("v1")
        coVerify(exactly = 1) { mesas.refresh("v1") }
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
    fun `un error del servidor en la version baja las mesas completas`() = runTest {
        // Un 500 del manejador global: la red SÍ funciona. Esa vuelta baja /tables completo y la siguiente vuelve a preguntar.
        val api = mockk<ApiService>()
        val cuerpo = """{"message":"Ocurrió un error inesperado en el servidor."}""".toResponseBody("application/json".toMediaType())
        coEvery { api.getTablesVersion("v1") } throws HttpException(Response.error<Any>(500, cuerpo))
        val conServidor = TablesVersionPoller(mesas, plano, ApiFuenteDeVersiones(api))
        conServidor.consultar("v1")
        conServidor.consultar("v1")
        assertFalse(conServidor.servidorViejo)
        coVerify(exactly = 2) { api.getTablesVersion("v1") }
        coVerify(exactly = 2) { mesas.refresh("v1") }
        coVerify(exactly = 0) { plano.refresh(any()) }
    }

    @Test
    fun `una consulta tardia de otra sucursal no la vuelve a pintar`() = runTest {
        coEvery { mesas.refresh(any()) } returns Result.success(emptyList())
        coEvery { plano.refresh(any()) } returns Result.success(FloorPlanPayload(floorPlanVersion = "p1"))
        val respuestaDeA = CompletableDeferred<ConsultaDeVersiones>()
        val lenta = TablesVersionPoller(
            mesas,
            plano,
            object : FuenteDeVersiones {
                override suspend fun consultar(venueId: String): ConsultaDeVersiones =
                    if (venueId == "A") respuestaDeA.await() else ConsultaDeVersiones.Versiones("t1", "p1")
            },
        )
        lenta.entrar("A")
        val tardia = launch { lenta.consultar("A") }
        runCurrent() // la consulta de A queda esperando su respuesta
        lenta.entrar("B") // el mesero cambió de sucursal
        respuestaDeA.complete(ConsultaDeVersiones.Versiones("t2", "p2")) // A sí había cambiado
        tardia.join()
        coVerify(exactly = 1) { mesas.refresh("A") } // sólo la de entrar(A)
        coVerify(exactly = 0) { plano.refresh("A") }
        assertFalse(lenta.servidorViejo)
    }

    @Test
    fun `refrescar todo de otra sucursal no repinta`() = runTest {
        coEvery { mesas.refresh(any()) } returns Result.success(emptyList())
        coEvery { plano.refresh(any()) } returns Result.success(FloorPlanPayload(floorPlanVersion = "p1"))
        poller.entrar("A")
        val mesasDeA = CompletableDeferred<Result<List<DiningTable>>>()
        coEvery { mesas.refresh("A") } coAnswers { mesasDeA.await() }
        val tardio = launch { poller.refrescarTodo("A") }
        runCurrent() // el /tables de A queda esperando
        poller.entrar("B") // el mesero cambió de sucursal
        mesasDeA.complete(Result.success(emptyList()))
        tardio.join()
        coVerify(exactly = 0) { plano.refresh("A") } // no vuelve a apuntar el plano a A
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
