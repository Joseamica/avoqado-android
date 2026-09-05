package com.avoqado.pos.cashdrawer

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.presentation.CashDrawerViewModel
import com.avoqado.pos.cashdrawer.presentation.encabezadoDeRechazos
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.printing.data.PrinterService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 🔴 EL AVISO DE ADOPCION APARECE EN EL MOMENTO EN QUE LA ADOPCION OCURRE — hallazgo N1 de la
 * re-revision de la ronda 1 (Task 8b, 5-sep-2026).
 *
 * El aviso durable (I1) estaba bien escrito y bien pintado, y aun asi el cajero no lo veia: el
 * unico instante en que el servidor LIGA es al abrir la caja, y ese camino no volvia a leer los
 * avisos. `_cajasAdoptadas` solo se llenaba en `loadCurrentSession()`, que la pantalla llama al
 * ENTRAR — asi que el cajero tecleaba $2,000, tocaba «Abrir caja» y se quedaba operando la caja
 * de $500 de otra persona sin un solo indicio, hasta que salia y volvia a entrar (normalmente ya
 * para cerrarla, con el sobrante encima).
 *
 * Aqui se ejercita el ViewModel de verdad, con el repositorio real contra un servidor falso: era
 * el CABLE lo que faltaba, y ninguna prueba de las funciones puras lo veia.
 *
 * 🔴 Y el `GET /current` del arranque se deja BLOQUEADO a proposito (`portero`): sin eso, el
 * `loadCurrentSession()` del `init` converge al mismo valor por su cuenta y la prueba de N4
 * pasaria aunque el arreglo no estuviera — el caso clasico de la prueba que pasa por el motivo
 * equivocado. Con el portero cerrado, el UNICO escritor durante la prueba es `openSession`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashDrawerAvisoAlAbrirTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    /** Mantiene bloqueado el `GET /current` del arranque hasta que la prueba termina. */
    private val portero = CountDownLatch(1)

    /**
     * Se suelta con `Dispatchers.Main` todavia puesto (los `@After` corren ANTES de que la regla
     * lo restaure): asi el `loadCurrentSession()` del arranque termina donde puede terminar, en
     * vez de despertar sobre un dispatcher que ya no existe.
     */
    @After
    fun soltarElPortero() {
        portero.countDown()
        Thread.sleep(100)
    }

    // MARK: - Andamio

    private fun almacen() = mockk<SecureStorage>(relaxed = true).also { st ->
        var cola: String? = null
        var avisos: String? = null
        every { st.venueId } returns VENUE_ID
        every { st.venueName } returns "Testarudo Cafe"
        every { st.userId } returns "staff-1"
        every { st.userFirstName } returns "Ana"
        every { st.userLastName } returns "Ruiz"
        every { st.pendingDrawerOpsJson(any()) } answers { cola }
        every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
        every { st.drawerAdoptionNoticesJson(any()) } answers { avisos }
        every { st.setDrawerAdoptionNoticesJson(any(), any()) } answers { avisos = secondArg() }
    }

    private fun respuesta(code: Int, body: String, url: String) = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    /** Contesta por path; `/current` espera al portero y lo demas revienta como "sin red". */
    private fun cliente(vararg rutas: Pair<String, Pair<Int, String>>): OkHttpClient {
        val porRuta = rutas.toMap()
        return mockk {
            every { newCall(any()) } answers {
                val request = firstArg<Request>()
                val path = request.url.encodedPath
                val configurada = porRuta.entries.firstOrNull { path.endsWith(it.key) }?.value
                val call = mockk<Call>()
                if (path.endsWith("/current")) {
                    every { call.execute() } answers {
                        portero.await(10, TimeUnit.SECONDS)
                        throw IOException("sin red: $path")
                    }
                } else if (configurada == null) {
                    every { call.execute() } throws IOException("sin red: $path")
                } else {
                    every { call.execute() } returns respuesta(configurada.first, configurada.second, request.url.toString())
                }
                call
            }
        }
    }

    private fun viewModel(vararg rutas: Pair<String, Pair<Int, String>>): CashDrawerViewModel {
        val repo = CashDrawerRepository(
            dao = FakeCashDrawerDao(),
            secureStorage = almacen(),
            client = cliente(*rutas),
            pendingCashSales = sinCobrosEnCola(),
            conectividad = conectividadDePrueba(),
        )
        return CashDrawerViewModel(
            repository = repo,
            printerService = mockk<PrinterService>(relaxed = true),
            roleManager = mockk<RoleManager>(relaxed = true).also {
                every { it.hasVenuePermission(any(), any()) } returns true
            },
        )
    }

    /** El POST del cajon vive en `Dispatchers.IO`: se espera al estado, con tope. */
    private fun esperar(que: String, cond: () -> Boolean) {
        val limite = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < limite) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail(que)
    }

    // MARK: - N1: el aviso sale sin salir y volver a entrar a Caja

    /**
     * 🔴 EL CASO REAL: el servidor contesta 201 con `cajaCreada:false` —LIGO mi apertura a la caja
     * de otro— y la pantalla tiene que decirlo EN ESA MISMA CORRIDA.
     */
    @Test
    fun `abrir sobre una caja que ya estaba deja el aviso a la vista de inmediato`() {
        val vm = viewModel(
            "/cash-drawer/open" to (
                201 to sesionJson(
                    "srv-1",
                    cajaCreada = false,
                    startingAmount = 500.00,
                    openedByName = "Hector Diaz",
                )
                ),
        )

        vm.openSession(200_000) // el cajero tecleo $2,000

        esperar("el aviso de adopcion no llego a la pantalla al abrir") { vm.cajasAdoptadas.value.isNotEmpty() }
        val avisos = vm.cajasAdoptadas.value
        assertEquals(1, avisos.size)
        assertEquals("srv-1", avisos[0].sessionId)
        assertEquals("Hector Diaz", avisos[0].openedByName)
        assertEquals("el fondo del servidor", 50_000, avisos[0].fondoServidorCents)
        assertEquals("y el que tecleo el cajero, que no quedo registrado", 200_000, avisos[0].fondoLocalCents)
    }

    /**
     * 🔴 N4: y el encabezado NO puede pintar el monto tecleado sobre una caja ajena.
     *
     * Con $2,000 tecleados encima de una caja de $500, «Efectivo esperado $2,000.00» es un numero
     * que miente hasta el siguiente `loadCurrentSession()` — que puede tardar todo el turno.
     */
    @Test
    fun `tras adoptar, el esperado es el de la caja adoptada y no el monto tecleado`() {
        val vm = viewModel(
            "/cash-drawer/open" to (201 to sesionJson("srv-1", cajaCreada = false, startingAmount = 500.00)),
        )

        vm.openSession(200_000)

        // El aviso se escribe DESPUES del esperado: cuando aparece, `openSession` ya termino.
        esperar("la apertura nunca llego al servidor falso") { vm.cajasAdoptadas.value.isNotEmpty() }
        assertEquals("srv-1", vm.currentSession.value?.id)
        assertEquals(
            "el esperado se quedo con el fondo TECLEADO en vez del de la caja adoptada",
            50_000,
            vm.expectedAmountCents.value,
        )
    }

    /** Y una apertura que SI creo la caja no inventa ningun aviso. */
    @Test
    fun `abrir una caja nueva no deja ningun aviso`() {
        val vm = viewModel(
            "/cash-drawer/open" to (201 to sesionJson("srv-1", cajaCreada = true, startingAmount = 2000.00)),
        )

        vm.openSession(200_000)

        esperar("la apertura nunca llego al servidor falso") { vm.currentSession.value?.id == "srv-1" }
        assertTrue(
            "una apertura normal no puede avisar de nada: ${vm.cajasAdoptadas.value}",
            vm.cajasAdoptadas.value.isEmpty(),
        )
        assertEquals(200_000, vm.expectedAmountCents.value)
    }

    /**
     * 🔴 Y una apertura RECHAZADA tambien se ve al momento: es dinero que va a quedar fuera del
     * turno y el cajero esta a punto de empezar a cobrar encima.
     */
    @Test
    fun `una apertura rechazada al abrir se ve de inmediato`() {
        val vm = viewModel(
            "/cash-drawer/open" to (400 to """{"message":"El fondo inicial no es valido."}"""),
        )

        vm.openSession(200_000)

        esperar("el rechazo de la apertura no llego a la pantalla") { vm.rechazadas.value.isNotEmpty() }
        val rechazadas = vm.rechazadas.value
        assertEquals(1, rechazadas.size)
        assertEquals("OPEN", rechazadas[0].kind)
        assertEquals("El fondo inicial no es valido.", rechazadas[0].motivo)
    }

    // MARK: - N5: el encabezado habla del tipo del PRIMER rechazo

    /**
     * 🔴 Con una APERTURA y un retiro rechazados a la vez, el aviso decia «El dinero ya se movio en
     * el cajon» — falso para el renglon de la apertura, que es justo el copy que I4 corrigio.
     */
    @Test
    fun `con una apertura y un movimiento rechazados el encabezado habla del primero`() {
        val (titulo, explicacion) = encabezadoDeRechazos(listOf("OPEN", "PAY_OUT"))

        assertEquals("La caja no se registró en el servidor", titulo)
        assertTrue("tiene que ofrecer Reintentar: $explicacion", explicacion.contains("Reintentar"))
        assertTrue(
            "no puede afirmar que el dinero se movio en el cajon: $explicacion",
            !explicacion.contains("cajón"),
        )
    }

    /** Y al reves: si el primero es un movimiento, manda el texto del movimiento. */
    @Test
    fun `si el primer rechazo es un movimiento el encabezado habla de dinero movido`() {
        val (titulo, explicacion) = encabezadoDeRechazos(listOf("PAY_OUT", "OPEN"))

        assertEquals("Un movimiento no se registró", titulo)
        assertTrue("el dinero de un retiro si se movio: $explicacion", explicacion.contains("cajón"))
    }

    /** El conteo es el de SU tipo: contar los dos diria «2 cajas» de una caja y un retiro. */
    @Test
    fun `el conteo del encabezado cuenta solo los de su tipo`() {
        assertEquals(
            "2 cajas no se registraron en el servidor",
            encabezadoDeRechazos(listOf("OPEN", "OPEN", "PAY_IN")).first,
        )
        assertEquals(
            "2 movimientos no se registraron",
            encabezadoDeRechazos(listOf("PAY_IN", "PAY_OUT", "OPEN")).first,
        )
    }

    /** Regresion: con una sola clase de rechazo el texto es EXACTAMENTE el de antes. */
    @Test
    fun `con un solo tipo de rechazo el encabezado no cambia`() {
        assertEquals("La caja no se registró en el servidor", encabezadoDeRechazos(listOf("OPEN")).first)
        assertEquals("2 cajas no se registraron en el servidor", encabezadoDeRechazos(listOf("OPEN", "OPEN")).first)
        assertEquals("Un movimiento no se registró", encabezadoDeRechazos(listOf("PAY_OUT")).first)
        assertEquals("2 movimientos no se registraron", encabezadoDeRechazos(listOf("PAY_OUT", "PAY_IN")).first)
    }
}
