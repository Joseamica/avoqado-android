package com.avoqado.pos.transactions.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ClaseDeFallo
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.data.network.LecturaFallida
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * D22 (diseños r3 y r4): la lista de Ventas con la recarga automática. La paginación sólo cambia con un reemplazo bueno;
 * una sola carga a la vez (también «Cargar más»), que se planea sobre la lista que hay al tomar el turno; la automática va
 * marcada de fondo y se corta de verdad al cancelarla.
 */
class TransactionRepositoryRecargaSolaTest {
    private val server = MockWebServer()
    private val storage = mockk<SecureStorage>(relaxed = true) {
        every { venueId } returns "venue"
        every { accessToken } returns "token"
        every { userId } returns "ana"
    }
    private var sinRed = false
    private val pedidas = CopyOnWriteArrayList<RecordedRequest>()
    private val enCurso = AtomicInteger(0)
    private val maximoAlMismoTiempo = AtomicInteger(0)

    /** Lo que contesta el servidor a cada página (ids por página) y cuántas páginas hay. */
    private var paginas: Map<Int, List<String>> = mapOf(1 to (1..20).map { "p$it" }, 2 to (21..40).map { "p$it" }, 3 to (41..60).map { "p$it" })
    private var retenerPagina1: CompletableDeferred<Unit>? = null
    private var codigoPagina1 = 200
    private var cuerpoDelError = ""

    private val repo = TransactionRepository(storage, OkHttpClient.Builder()
        .addInterceptor { chain ->
            if (sinRed) throw UnknownHostException("sin red de prueba")
            chain.proceed(chain.request())
        }
        .readTimeout(20, TimeUnit.SECONDS)
        .build()).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pedidas += request
                val ahora = enCurso.incrementAndGet()
                maximoAlMismoTiempo.accumulateAndGet(ahora) { a, b -> maxOf(a, b) }
                try {
                    val pagina = request.requestUrl!!.queryParameter("page")!!.toInt()
                    if (pagina == 1) {
                        retenerPagina1?.let { runBlocking { it.await() } }
                        if (codigoPagina1 != 200) return MockResponse().setResponseCode(codigoPagina1).setBody(cuerpoDelError)
                    }
                    val ids = paginas[pagina].orEmpty().joinToString(",") { """{"id":"$it"}""" }
                    return MockResponse().setBody("""{"success":true,"data":[$ids],"meta":{"pageCount":${paginas.size}}}""")
                } finally {
                    enCurso.decrementAndGet()
                }
            }
        }
    }

    @After fun apagar() = server.shutdown()

    private fun paginaDe(request: RecordedRequest) = request.requestUrl!!.queryParameter("page")!!.toInt()

    private suspend fun tresPaginas() {
        assertTrue(repo.fetchTransactions(page = 1).isSuccess)
        assertTrue(repo.fetchTransactions(page = 2).isSuccess)
        assertTrue(repo.fetchTransactions(page = 3).isSuccess)
        assertEquals(3, repo.paginasCargadas.value)
    }

    @Test fun `r2-A - un Actualizar que falla no toca la paginacion`() = runBlocking<Unit> {
        paginas = paginas + (4 to listOf("p61"))
        tresPaginas()

        sinRed = true
        assertTrue(repo.fetchTransactions(page = 1).isFailure)

        assertEquals(3, repo.paginasCargadas.value)
        assertEquals(60, repo.transactions.value.size)
        assertTrue("sigue pudiendo cargar más", repo.canLoadMore())
        assertEquals(4, repo.getNextPage())
    }

    @Test fun `un reemplazo bueno de la pagina 1 regresa a una pagina`() = runBlocking<Unit> {
        tresPaginas()
        assertTrue(repo.fetchTransactions(page = 1).isSuccess)
        assertEquals(1, repo.paginasCargadas.value)
        assertEquals(20, repo.transactions.value.size)
    }

    @Test fun `r3-2 - cargar mas tras un reemplazo con los mismos ids si llega`() = runBlocking<Unit> {
        assertTrue(repo.fetchTransactions(page = 1).isSuccess)
        val retenida = CompletableDeferred<Unit>()
        retenerPagina1 = retenida
        val primera = async(Dispatchers.IO) { repo.fetchTransactions(page = 1, enFondo = true) } // la automática, en camino
        while (pedidas.size < 2) delay(10)
        val siguiente = async(Dispatchers.IO) { repo.fetchTransactions(page = repo.getNextPage()) } // llegó al final: espera turno
        delay(100)
        retenerPagina1 = null
        retenida.complete(Unit) // la página 1 se reemplaza con los mismos ids

        assertTrue(primera.await().isSuccess)
        assertTrue(siguiente.await().isSuccess)
        assertEquals(listOf(1, 1, 2), pedidas.map(::paginaDe))
        assertEquals(40, repo.transactions.value.size)
        assertEquals(2, repo.paginasCargadas.value)
    }

    @Test fun `una sola carga de la lista a la vez - cargar mas espera a la pagina 1`() = runBlocking<Unit> {
        assertTrue(repo.fetchTransactions(page = 1).isSuccess)
        val retenida = CompletableDeferred<Unit>()
        retenerPagina1 = retenida
        val primera = async(Dispatchers.IO) { repo.fetchTransactions(page = 1) }
        while (pedidas.size < 2) delay(10)
        val mas = async(Dispatchers.IO) { repo.fetchTransactions(page = 2) }
        delay(200)
        assertEquals("la página 2 no sale mientras va la 1", 2, pedidas.size)
        retenerPagina1 = null
        retenida.complete(Unit)
        primera.await()
        mas.await()
        assertEquals(1, maximoAlMismoTiempo.get())
    }

    @Test fun `la recarga automatica va marcada de fondo y la del cajero no`() = runBlocking<Unit> {
        repo.fetchTransactions(page = 1, enFondo = true)
        repo.fetchTransactions(page = 1)
        assertEquals("1", pedidas[0].getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertNull(pedidas[1].getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
    }

    @Test fun `r1-8 - cancelar la carga corta la peticion de verdad`() = runBlocking<Unit> {
        val retenida = CompletableDeferred<Unit>()
        retenerPagina1 = retenida
        try {
            val carga = launch(Dispatchers.IO) { repo.fetchTransactions(page = 1, enFondo = true) }
            while (pedidas.isEmpty()) delay(10)
            carga.cancel()
            withTimeout(3_000) { carga.join() } // sin call.cancel() esperaría la respuesta, que nunca llega sola
            assertEquals(false, repo.isLoading.value)
            assertNull("cancelar no es un fallo de lectura", repo.falloDeCarga.value)
        } finally {
            retenida.complete(Unit) // suelta al servidor de prueba para que pueda apagarse
        }
    }

    @Test fun `un 403 de nuestra API detiene y el de un portal reintenta`() = runBlocking<Unit> {
        codigoPagina1 = 403
        cuerpoDelError = """{"error":"Forbidden","message":"Permission 'tpv-payments:read' required"}"""
        val propia = repo.fetchTransactions(page = 1).exceptionOrNull() as LecturaFallida
        assertEquals(listOf(ClaseDeFallo.DENEGACION_PROPIA), propia.clases)

        cuerpoDelError = "<html>Portal del hotel</html>"
        val portal = repo.fetchTransactions(page = 1).exceptionOrNull() as LecturaFallida
        assertEquals(listOf(ClaseDeFallo.REINTENTABLE), portal.clases)
    }
}
