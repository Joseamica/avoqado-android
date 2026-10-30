package com.avoqado.pos.giftcards

import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RevisionR17Test {
    @Test fun resolutorRecreadoRespeta429SesentaYLuegoMismosBytes() = runBlocking {
        val prefs = SolicitudesDeTarjetaTest.Preferencias().apply {
            values["venueId"]="a"; values["userId"]="staff"; values["accessToken"]="fixture"
        }
        val storage = prefs.almacen()
        val store = SolicitudesDeTarjetaStore(storage)
        val posts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gets = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body: String
            val status: Int
            if (request.method == "POST") {
                val bytes = okio.Buffer(); request.body!!.writeTo(bytes); posts.add(bytes.readUtf8())
                status = if (posts.size == 1) 429 else 503
                body = if (status == 429) """{"code":"RATE_LIMITED","message":"Espera","retryAfter":60}""" else """{"message":"temporal"}"""
            } else {
                gets.add(request.url.encodedPath); status=200
                body="""{"success":true,"data":{"found":false}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = GiftCardsRepository(client, storage)
        val dao=mockk<PendingPaymentDao> { coEvery { forVenue(any()) } returns emptyList() }
        val local=CobrosLocalesDeLaCuenta(dao,storage,store)
        val primero=CobroConSaldoCoordinator(repo,store,storage,local)
        assertTrue(primero.cobrar("a","o",PruebaDeTarjeta.Codigo("fixture"),8000,0) is DesenlaceDeCobro.Reintentable)
        val inicial=posts.single(); val key=Json.parseToJsonElement(inicial).jsonObject["idempotencyKey"]!!.jsonPrimitive.content
        val limite = System.currentTimeMillis()+60_500
        // Reabrir el diario y el motor pierde toda espera volátil del ViewModel.
        val reabierto=SolicitudesDeTarjetaStore(storage)
        val segundo=CobroConSaldoCoordinator(repo,reabierto,storage,CobrosLocalesDeLaCuenta(dao,storage,reabierto))
        val red=mockk<ConnectivityMonitor> {
            every { isConnected } returns MutableStateFlow(true)
            every { isServerReachable } returns MutableStateFlow(true)
        }
        val llamador=ResolutorDeSolicitudesDeTarjeta(repo,reabierto,storage,red,segundo)
        llamador.start()
        try {
            delay(5600)
            assertTrue("Consultar por la misma llave sí está permitido", gets.any { it.endsWith("/payments/$key") })
            assertEquals("El fondo no reenvía antes de retryAfter=60",1,posts.size)
            delay((limite-System.currentTimeMillis()).coerceAtLeast(0))
            assertEquals("Vencido el plazo se permite un solo reenvío",2,posts.size)
            assertEquals(inicial,posts[1])
            assertEquals(inicial,reabierto.delNegocio("a").single().cuerpo)
        } finally { reabierto.quitar(key); llamador.onSessionChanged(false) }
    }
}
