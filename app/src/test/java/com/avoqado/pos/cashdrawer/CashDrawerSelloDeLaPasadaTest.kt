package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.DesenlaceDelIntento
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.intentoLlegoALaRed
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.PasadaConSello
import com.avoqado.pos.core.data.network.SesionCambiada
import com.avoqado.pos.core.data.network.sello
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * D80 (hz:8024bc1c:D80): una pasada de la cola sin red empieza con la sesión de A; a media pasada el cierre automático saca a A y
 * entra B (sin `stop()` del servicio). El replay del cajón que corre esa pasada mandaba los ingresos, retiros, aperturas y cierres de
 * A con la sesión de B, y a la sucursal de B: un 403 de B podía marcar RECHAZADO un retiro de A y trabar el cierre de su caja.
 * Ahora cada corrida del replay sella sus peticiones con la sesión de la pasada (o, sin pasada, con la que tenía al empezar): si
 * cambió, `AuthInterceptor` no las deja salir (`SesionCambiada`) y la operación se queda en la cola, sin marcarse ni contar intentos.
 */
class CashDrawerSelloDeLaPasadaTest {

    private val json = Json { encodeDefaults = false }

    private fun almacen(generacion: Long, vararg cola: PendingDrawerOp): SecureStorage = cashDrawerSecureStorageConCola().also {
        every { it.generacion } returns generacion
        it.setPendingDrawerOpsJson(VENUE_ID, json.encodeToString(ListSerializer(PendingDrawerOp.serializer()), cola.toList()))
    }

    private fun colaDe(st: SecureStorage): List<PendingDrawerOp> =
        st.pendingDrawerOpsJson(VENUE_ID)?.let { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), it) }
            ?: emptyList()

    /** Un cliente que guarda cada petición y contesta con [falla] (sin red, o la sesión que cambió). */
    private fun cliente(pedidas: MutableList<Request>, falla: () -> IOException = { IOException("sin red") }): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            pedidas += firstArg<Request>()
            mockk<Call> { every { execute() } throws falla() }
        }
    }

    private val ingreso = PendingDrawerOp("PAY_IN", "caja-1", PAY_IN_CENTS, null, "ingreso-1", 1_000)

    @Test
    fun `P1 dentro de una pasada el cajon manda con el sello de la pasada aunque haya entrado otra persona`() = runTest {
        val pedidas = CopyOnWriteArrayList<Request>()
        val st = almacen(generacion = 8L, ingreso) // ya entró B (8); la pasada empezó con A (7)
        val repo = cashDrawerRepo(FakeCashDrawerDao(), cliente(pedidas), secureStorage = st)

        withContext(PasadaConSello(7L)) { repo.sincronizarCajonPrimero() }

        assertTrue("El replay sí intentó mandar el ingreso", pedidas.isNotEmpty())
        assertEquals(listOf(7L), pedidas.map { it.sello }.distinct())
    }

    @Test
    fun `P1 fuera de una pasada el cajon sella con la sesion con que empezo la corrida`() = runTest {
        val pedidas = CopyOnWriteArrayList<Request>()
        val st = almacen(generacion = 5L, ingreso)
        val repo = cashDrawerRepo(FakeCashDrawerDao(), cliente(pedidas), secureStorage = st)

        repo.sincronizarCajonPrimero()

        assertTrue(pedidas.isNotEmpty())
        assertEquals(listOf(5L), pedidas.map { it.sello }.distinct())
    }

    @Test
    fun `P1 un movimiento que no salio porque cambio la sesion sigue en la cola sin marcarse rechazado`() = runTest {
        val pedidas = CopyOnWriteArrayList<Request>()
        val st = almacen(generacion = 8L, ingreso)
        val repo = cashDrawerRepo(FakeCashDrawerDao(), cliente(pedidas) { SesionCambiada() }, secureStorage = st)

        withContext(PasadaConSello(7L)) { repo.sincronizarCajonPrimero() }

        val cola = colaDe(st)
        assertEquals(listOf("ingreso-1"), cola.map { it.localId })
        assertNull("No es un rechazo del servidor: no traba el cierre de su caja", cola.single().rechazadaEn)
    }

    @Test
    fun `P2 una peticion que no salio porque cambio la sesion no llego a la red`() {
        // Una apertura frenada por el sello no arranca el reloj de la barrera ni su espera de 30 s: el servidor ni se enteró.
        assertFalse(intentoLlegoALaRed(DesenlaceDelIntento.Fallo(SesionCambiada())))
    }
}
