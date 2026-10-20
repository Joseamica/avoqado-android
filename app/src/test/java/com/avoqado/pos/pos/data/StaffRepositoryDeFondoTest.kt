package com.avoqado.pos.pos.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Codex r1 (19-oct, «Operaciones por revisar»): la lista de nombres de quien revisó es un dato auxiliar; un 403 no
 *  debe sacar el aviso de permisos (header de fondo) y cae a la copia guardada, igual que sin red. */
class StaffRepositoryDeFondoTest {
    private val headers = mutableListOf<String?>()
    private fun repo(code: Int, cache: String?): StaffRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            headers.add(chain.request().header(ForbiddenInterceptor.BACKGROUND_HEADER))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body("""{"error":"Forbidden","message":"Permission 'teams:read' required"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "FULLTEST-A" }
        val payloads = mockk<PayloadCache>(relaxed = true).also {
            coEvery { it.load("staff", "FULLTEST-A") } returns cache?.let { json -> PayloadCache.Cached(json, 0) }
        }
        return StaffRepository(storage, client, payloads)
    }

    @Test fun de_fondo_manda_el_header_y_un_403_usa_la_copia_guardada() = runTest {
        val r = repo(403, """{"success":true,"data":[{"id":"staff-9","firstName":"Ana","lastName":"López"}]}""")
        assertEquals(listOf("staff-9"), r.getActiveStaff(deFondo = true).getOrThrow().map { it.id })
        assertEquals(listOf("1"), headers)
    }

    @Test fun normal_no_cambia_un_403_sigue_siendo_falla_sin_header() = runTest {
        val r = repo(403, """{"success":true,"data":[{"id":"staff-9","firstName":"Ana"}]}""")
        assertTrue(r.getActiveStaff().isFailure)
        assertEquals(listOf<String?>(null), headers)
    }
}
