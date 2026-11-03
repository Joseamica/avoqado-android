package com.avoqado.pos.transactions

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.*
import com.avoqado.pos.transactions.data.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ExchangeConfirmationPinTest {
    @Test fun `confirmation consumes fresh entry PIN once or prompts again only after expiry`()=runBlocking {
        for(expired in listOf(false,true)) {
            val clock=AtomicLong(0)
            val pinRepo=mockk<PermissionOverrideRepository>()
            coEvery { pinRepo.requestToken(any(),any(),any()) } returnsMany listOf(OverrideResult.Granted("entry-token","Manager"),OverrideResult.Granted("new-token","Manager"))
            val coordinator=object:ManagerOverrideCoordinator(pinRepo) { override val nowNanos:()->Long={clock.get()} }
            suspend fun waitForPrompt()=withTimeout(3000) { while(coordinator.prompt.value==null) delay(5) }
            val entry=async(Dispatchers.Default) { coordinator.preauthorize("payments:refund") }
            waitForPrompt(); coordinator.submitPin("venue","1234"); assertTrue(entry.await())
            if(expired) clock.set(46_000_000_000)
            val server=MockWebServer(); server.start()
            try {
                server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"permission required","required":"payments:refund","overridable":true}"""))
                val fixture=Json.parseToJsonElement(javaClass.getResource("/exchange/receipt-exchange.json")!!.readText()).jsonObject.getValue("exchange")
                server.enqueue(MockResponse().setBody("""{"data":{"exchange":$fixture}}"""))
                val storage=mockk<SecureStorage> { every { venueId } returns "venue" }
                val client=OkHttpClient.Builder().addInterceptor(ForbiddenInterceptor(ErrorNotifier(),coordinator)).build()
                val repository=ExchangeRepository(storage,client).also { it.baseUrl=server.url("/api/v1").toString().trimEnd('/') }
                val response=async(Dispatchers.Default) { repository.create("{}") }
                if(expired) { waitForPrompt(); coordinator.submitPin("venue","1234") }
                assertEquals("COMPLETED",response.await().status)
                assertNull(server.takeRequest().getHeader(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
                assertEquals(if(expired) "new-token" else "entry-token",server.takeRequest().getHeader(ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER))
                coVerify(exactly=if(expired) 2 else 1) { pinRepo.requestToken(any(),any(),"payments:refund") }
                assertNull(coordinator.prompt.value)
            } finally { server.shutdown() }
        }
    }
}
