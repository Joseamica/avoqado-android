package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * D26 (Codex código r4–r5): las dos lecturas del Calendario (reservas y clases) se cortan DE VERDAD si se cancela su carga
 * (otra fecha, salir de la pantalla). Antes la llamada seguía viva aunque la corrutina se cancelara, y su 403 podía abrir o
 * sostener un PIN de gerente para una carga que ya nadie iba a mostrar.
 */
class CalendarioSeCortaTest {

    /** Un cliente cuya petición se queda «en el servidor» hasta que la llamada se cancela. */
    private fun clienteQueEspera(llego: CountDownLatch, seCorto: CountDownLatch): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            llego.countDown()
            val limite = System.currentTimeMillis() + 5_000
            while (!chain.call().isCanceled() && System.currentTimeMillis() < limite) Thread.sleep(10)
            if (chain.call().isCanceled()) seCorto.countDown()
            throw IOException("Canceled")
        }.build()

    private val storage = mockk<SecureStorage>().also { every { it.venueId } returns "v1" }

    @Test
    fun `cancelar la carga corta la lectura de reservas`() {
        val llego = CountDownLatch(1)
        val seCorto = CountDownLatch(1)
        val api = ReservationApi(storage, clienteQueEspera(llego, seCorto)) { "http://localhost:1/api/v1" }
        runBlocking {
            val carga = launch(Dispatchers.Default) { api.calendar("2026-11-05", "2026-11-05", rafaga = "carga-1") }
            assertTrue(llego.await(3, TimeUnit.SECONDS))
            carga.cancel()
        }
        assertTrue("la llamada se cortó al cancelar la carga", seCorto.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun `cancelar la carga corta la lectura de clases`() {
        val llego = CountDownLatch(1)
        val seCorto = CountDownLatch(1)
        val api = ClassSessionApi(storage, clienteQueEspera(llego, seCorto)) { "http://localhost:1/api/v1" }
        runBlocking {
            val carga = launch(Dispatchers.Default) { api.list("2026-11-05", "2026-11-05", rafaga = "carga-1") }
            assertTrue(llego.await(3, TimeUnit.SECONDS))
            carga.cancel()
        }
        assertTrue("la llamada se cortó al cancelar la carga", seCorto.await(3, TimeUnit.SECONDS))
    }
}
