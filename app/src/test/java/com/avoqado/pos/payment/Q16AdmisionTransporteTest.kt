package com.avoqado.pos.payment

import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class Q16AdmisionTransporteTest {
    private fun api(server:MockWebServer,client:OkHttpClient)=Retrofit.Builder().baseUrl(server.url("/"))
        .client(client).addConverterFactory(Json { ignoreUnknownKeys=true }.asConverterFactory("application/json".toMediaType()))
        .build().create(ApiService::class.java)
    @Test fun `writer durante headers invalida mismo token antes de primer POST`()=runTest {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                entered.complete(Unit);runBlocking { release.await() };chain.proceed(chain.request())
            }.addNetworkInterceptor(InterceptorDePrecioQ16).build()
            val token=AdmisionDePrecioQ16.snapshot("headers-A")
            val call=api(server,client).syncIntentsAdmittedQ16("headers-A",SyncIntentsRequest("original-device",emptyList()),token)
            val result=async(Dispatchers.IO) { runCatching { call.execute().code() } }
            try {
                entered.await();AdmisionDePrecioQ16.write("headers-A") { }
                release.complete(Unit)
                assertTrue(result.await().exceptionOrNull() is AdmisionDePrecioQ16.Changed)
                assertEquals(0,server.requestCount)
            } finally { release.complete(Unit);call.cancel();result.cancelAndJoin() }
        }
    }
    @Test fun `401 refresh no renueva token y no manda segundo POST tras captura`()=runTest {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val client=OkHttpClient.Builder().authenticator { _,response ->
                entered.complete(Unit);runBlocking { release.await() }
                response.request.newBuilder().header("Authorization","Bearer fixture").build()
            }.addNetworkInterceptor(InterceptorDePrecioQ16).build()
            val token=AdmisionDePrecioQ16.snapshot("refresh-A")
            val call=api(server,client).syncIntentsAdmittedQ16("refresh-A",SyncIntentsRequest("original-device",emptyList()),token)
            val result=async(Dispatchers.IO) { runCatching { call.execute().code() } }
            try {
                entered.await();AdmisionDePrecioQ16.write("refresh-A") { }
                release.complete(Unit)
                assertTrue(result.await().exceptionOrNull() is AdmisionDePrecioQ16.Changed)
                assertEquals(1,server.requestCount)
                assertTrue(server.takeRequest().body.readUtf8().contains("original-device"))
            } finally { release.complete(Unit);call.cancel();result.cancelAndJoin() }
        }
    }
    @Test fun `otro venue no impide retry original y body permanece identico`()=runTest {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(401));server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            val client=OkHttpClient.Builder().authenticator { _,response ->
                runBlocking { AdmisionDePrecioQ16.write("refresh-other-B") { } }
                response.request.newBuilder().header("Authorization","Bearer fixture").build()
            }.addNetworkInterceptor(InterceptorDePrecioQ16).build()
            val token=AdmisionDePrecioQ16.snapshot("refresh-other-A")
            val call=api(server,client).syncIntentsAdmittedQ16("refresh-other-A",SyncIntentsRequest("original-device",emptyList()),token)
            val response=withContext(Dispatchers.IO) { call.execute() };assertTrue(response.isSuccessful)
            assertEquals(2,server.requestCount)
            assertEquals(server.takeRequest().body.readUtf8(),server.takeRequest().body.readUtf8())
        }
    }
}
