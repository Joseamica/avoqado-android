package com.avoqado.pos.articles

import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.model.CreateProductRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogGovernanceProductErrorTest {
    private val governanceBody =
        """{"message":"Este producto debe crearse o activarse desde el Catálogo maestro.","code":"CATALOG_GOVERNANCE_REQUIRED"}"""

    @Test
    fun `POS product creation preserves the master catalog explanation`() = runTest {
        val repository = ProductsRepository(
            secureStorage = secureStorage(),
            client = clientReturning(422, governanceBody),
            payloadCache = mockk<PayloadCache>(relaxed = true),
        )

        val result = repository.createProduct(
            CreateProductRequest(
                name = "Agua",
                price = 25.0,
                categoryId = "category-1",
            ),
        )

        assertTrue(result.isFailure)
        assertEquals(
            "Este producto debe crearse o activarse desde el Catálogo maestro.",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `Articles create and update preserve the master catalog explanation`() = runTest {
        val createRepository = ArticlesRepository(secureStorage(), clientReturning(422, governanceBody))
        val updateRepository = ArticlesRepository(secureStorage(), clientReturning(422, governanceBody))

        assertTrue(!createRepository.createProduct("{}"))
        assertTrue(!updateRepository.updateProduct("product-1", "{}"))
        assertEquals(
            "Este producto debe crearse o activarse desde el Catálogo maestro.",
            createRepository.errorMessage.value,
        )
        assertEquals(
            "Este producto debe crearse o activarse desde el Catálogo maestro.",
            updateRepository.errorMessage.value,
        )
    }

    private fun secureStorage(): SecureStorage = mockk<SecureStorage> {
        every { venueId } returns "venue-1"
    }

    private fun clientReturning(status: Int, body: String): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://api.avoqado.io/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Unprocessable Entity")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        return mockk<OkHttpClient> {
            every { newCall(any()) } returns call
        }
    }
}
