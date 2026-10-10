package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** Cómo se lee la respuesta de `/tables/version` (spec 2026-10-09 §4.1). Espejo: avoqado-ios `ConsultaDeVersionesTests`. */
class ConsultaDeVersionesTest {

    private val api = mockk<ApiService>()
    private val fuente = ApiFuenteDeVersiones(api)

    @Test
    fun `un 404 es servidor viejo`() = runTest {
        // Lo que contesta Express cuando la ruta no existe: HTML «Cannot GET».
        val cuerpo = "<!DOCTYPE html><pre>Cannot GET /api/v1/mobile/venues/v1/tables/version</pre>".toResponseBody("text/html".toMediaType())
        coEvery { api.getTablesVersion("v1") } throws HttpException(Response.error<Any>(404, cuerpo))
        assertEquals(ConsultaDeVersiones.ServidorViejo, fuente.consultar("v1"))
    }

    @Test
    fun `un error de red es sin respuesta`() = runTest {
        coEvery { api.getTablesVersion("v1") } throws IOException("sin red")
        assertEquals(ConsultaDeVersiones.SinRespuesta, fuente.consultar("v1"))
    }

    @Test
    fun `un 200 trae las dos versiones`() = runTest {
        coEvery { api.getTablesVersion("v1") } returns TablesVersionResponse(data = TablesVersions("t1", "p1"))
        assertEquals(ConsultaDeVersiones.Versiones("t1", "p1"), fuente.consultar("v1"))
    }
}
