package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.CancelacionesDeCobroEnTexto
import com.avoqado.pos.payment.data.EstadoDeCancelacion
import com.avoqado.pos.payment.data.FaseDeCancelacion
import com.avoqado.pos.payment.data.IntencionDeCancelarCobro
import com.avoqado.pos.payment.data.RespuestaDeCancelacion
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import com.avoqado.pos.payment.domain.DesenlaceDeCancelacion
import com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El coordinador que reproduce la cancelación durable: cancel → desenlace → borrado, EN ESE ORDEN.
 *
 * El incidente que lo origina (producción, 11-sep): «Cancelar» mandaba el POST de cancel y el
 * DELETE de la orden EN PARALELO. Cuando el DELETE llegaba primero, el servidor contestaba 409
 * `ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE` y la orden quedaba huérfana — nadie volvía a
 * intentarlo. Aquí el borrado es BARRERA: nunca sale antes de que conste que no se cobró.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelacionDeCobroCoordinatorTest {

    private val almacen = AlmacenQuePuedeFallar()
    private val store = CancelacionesDeCobroEnTexto(almacen)
    private val conectado = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)

    private fun TestScope.coordinador(
        transporte: CancelacionTransportFalso,
        scope: CoroutineScope = backgroundScope,
        tickMs: Long = 30_000,
    ) = CancelacionDeCobroCoordinator(
        store = store,
        transporte = transporte,
        conectado = conectado,
        servidorAlcanzable = servidor,
        scope = scope,
        reloj = { testScheduler.currentTime },
        esperasDeSondeo = listOf(1_000L, 2_000L),
        tickMs = tickMs,
    )

    private fun intencion(
        id: String = "req-1",
        requestId: String? = id,
        orderId: String? = "order-1",
        borrarOrden: Boolean = true,
        terminalId: String? = "t1",
        fase: FaseDeCancelacion = FaseDeCancelacion.PEDIR_CANCEL,
    ) = IntencionDeCancelarCobro(
        id = id,
        requestId = requestId,
        venueId = "venue-1",
        terminalId = terminalId,
        orderId = orderId,
        borrarOrden = borrarOrden,
        actorStaffId = "staff-1",
        montoCents = 1500,
        creadaEn = 1,
        fase = fase.name,
    )

    private val cancelado = ChargeStatusProbe.Known("CANCELLED", inProgress = false, cancelDisposition = "ACCEPTED")
    private val cancelando = ChargeStatusProbe.Known("CANCEL_REQUESTED", inProgress = true)

    // MARK: - El borrado es barrera

    @Test
    fun `P1 el DELETE nunca sale antes de que conste que no se cobro`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            llave = "req-1"
            programarEstados(cancelando, cancelado)
        }
        val c = coordinador(transporte)
        assertTrue(c.registrar(intencion()))

        c.procesar("req-1")

        assertEquals(
            listOf("CANCEL:req-1", "GET:req-1", "CANCEL:req-1", "GET:req-1", "DELETE:order-1"),
            transporte.llamadas,
        )
        assertTrue("la intención se cierra al terminar", store.todas().isEmpty())
        assertEquals(EstadoDeCancelacion.Cerrada, c.estadoActual("req-1"))
        assertNull("consta que no se cobró: se suelta la llave del cobro", transporte.llave)
        assertEquals(DesenlaceDeCancelacion.NoSeCobro, c.desenlaceConocido("req-1"))
    }

    @Test
    fun `P1 el DELETE espera la respuesta del cancel aunque llegue tarde`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transporte = CancelacionTransportFalso().apply {
            antesDeContestarCancel = { gate.await() }
            programarEstados(cancelado)
        }
        val c = coordinador(transporte)
        c.registrar(intencion())

        val corrida = launch { c.procesar("req-1") }
        runCurrent()
        // El cancel sigue sin respuesta: ni consulta ni borrado.
        assertEquals(listOf("CANCEL:req-1"), transporte.llamadas)

        gate.complete(Unit)
        corrida.join()
        assertEquals(listOf("CANCEL:req-1", "GET:req-1", "DELETE:order-1"), transporte.llamadas)
    }

    @Test
    fun `P1 un 409 del borrado bloqueado por MI cobro vuelve a esperar y no cierra`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            programarEstados(cancelado)
            programarBorrados(ResultadoDeCancelarOrden.BloqueadaPorCobro("req-1", "Hay un cobro en curso"))
        }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertEquals(listOf("CANCEL:req-1", "GET:req-1", "DELETE:order-1"), transporte.llamadas)
        assertEquals(FaseDeCancelacion.ESPERAR_DESENLACE, store.leer("req-1")!!.faseActual)
        assertEquals(EstadoDeCancelacion.Pendiente(sinRed = false), c.estadoActual("req-1"))
    }

    // MARK: - Lo que NO autoriza a borrar

    @Test
    fun `P1 un CANCELLED por gracia sin disposicion queda pendiente y no borra`() = runTest {
        val transporte = CancelacionTransportFalso().apply { programarEstados(ChargeStatusProbe.Known("CANCELLED", false)) }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertEquals(listOf("CANCEL:req-1", "GET:req-1"), transporte.llamadas)
        assertEquals(EstadoDeCancelacion.Pendiente(sinRed = false), c.estadoActual("req-1"))
        assertEquals(1, store.todas().size)
    }

    @Test
    fun `P1 un 404 sostenido queda pendiente y no borra`() = runTest {
        val transporte = CancelacionTransportFalso().apply { programarEstados(ChargeStatusProbe.NotFound) }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertTrue(transporte.llamadasBorrar.isEmpty())
        assertEquals(3, transporte.llamadasEstado.size)
        assertEquals(EstadoDeCancelacion.Pendiente(sinRed = false), c.estadoActual("req-1"))
    }

    @Test
    fun `P1 sin red al cancelar no consulta ni borra y lo dice`() = runTest {
        val transporte = CancelacionTransportFalso().apply { programarCancels(RespuestaDeCancelacion(http = null)) }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertEquals(listOf("CANCEL:req-1"), transporte.llamadas)
        assertEquals(EstadoDeCancelacion.Pendiente(sinRed = true), c.estadoActual("req-1"))
        assertEquals(FaseDeCancelacion.PEDIR_CANCEL, store.leer("req-1")!!.faseActual)
        assertTrue(store.leer("req-1")!!.sinRed)
    }

    @Test
    fun `P1 sin red al consultar queda pendiente y lo dice`() = runTest {
        val transporte = CancelacionTransportFalso().apply { programarEstados(ChargeStatusProbe.Unreachable) }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertEquals(listOf("CANCEL:req-1", "GET:req-1"), transporte.llamadas)
        assertEquals(EstadoDeCancelacion.Pendiente(sinRed = true), c.estadoActual("req-1"))
    }

    @Test
    fun `P1 un 409 sin codigo del borrado deja la orden pendiente de borrar`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            programarEstados(cancelado)
            programarBorrados(ResultadoDeCancelarOrden.RechazoDeNegocio(409, null, "Conflicto"))
        }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertEquals(FaseDeCancelacion.BORRAR_ORDEN, store.leer("req-1")!!.faseActual)
        assertEquals(EstadoDeCancelacion.NoSeCobroOrdenPendiente(sinRed = false), c.estadoActual("req-1"))
    }

    @Test
    fun `P1 una cuenta con dinero no se borra y la intencion se cierra`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            programarEstados(cancelado)
            programarBorrados(ResultadoDeCancelarOrden.RechazoDeNegocio(400, null, "Cannot cancel a paid order"))
        }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertEquals(1, transporte.llamadasBorrar.size)
        assertTrue(store.todas().isEmpty())
        assertEquals(EstadoDeCancelacion.Cerrada, c.estadoActual("req-1"))
    }

    // MARK: - Se cobró al final

    @Test
    fun `P1 COMPLETED no borra la orden, arma la llave y avisa que se cobro`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            llave = null
            programarEstados(ChargeStatusProbe.Known("COMPLETED", false, paymentId = "pay-1"))
        }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertTrue("jamás se borra una orden cobrada", transporte.llamadasBorrar.isEmpty())
        assertEquals(EstadoDeCancelacion.SeCobro("pay-1"), c.estadoActual("req-1"))
        assertEquals("la llave carga el cobro para que la próxima venta lo muestre", "req-1", transporte.llave)
        assertTrue("entregado a la llave, la intención se cierra", store.todas().isEmpty())
        assertEquals(DesenlaceDeCancelacion.SeCobro("pay-1"), c.desenlaceConocido("req-1"))
    }

    @Test
    fun `P1 con la llave ocupada por OTRO cobro, el aviso de cobro se conserva hasta poder entregarlo`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            llave = "req-otro"
            programarEstados(ChargeStatusProbe.Known("COMPLETED", false, paymentId = "pay-1"))
        }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertEquals("la llave del otro cobro no se toca", "req-otro", transporte.llave)
        assertEquals(FaseDeCancelacion.SE_COBRO, store.leer("req-1")!!.faseActual)
        assertTrue(transporte.llamadasBorrar.isEmpty())

        transporte.llave = null
        c.procesar("req-1")

        assertEquals("req-1", transporte.llave)
        assertTrue(store.todas().isEmpty())
        assertTrue(transporte.llamadasBorrar.isEmpty())
    }

    // MARK: - Reenvío del cancel

    @Test
    fun `P1 reenvia el cancel con el MISMO requestId mientras la fila siga PENDING o SENT`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            programarEstados(
                ChargeStatusProbe.Known("PENDING", inProgress = true),
                ChargeStatusProbe.Known("SENT", inProgress = true),
                cancelado,
            )
        }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertEquals(
            listOf("CANCEL:req-1", "GET:req-1", "CANCEL:req-1", "GET:req-1", "CANCEL:req-1", "GET:req-1", "DELETE:order-1"),
            transporte.llamadas,
        )
    }

    @Test
    fun `usa el desenlace que trae la respuesta del cancel sin otra consulta`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            programarCancels(
                RespuestaDeCancelacion(
                    http = 200,
                    success = false,
                    cancelIntent = "ALREADY_FINAL",
                    estado = ChargeStatusProbe.Known("FAILED", false, outcome = "NOT_CHARGED"),
                ),
            )
        }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertEquals(listOf("CANCEL:req-1", "DELETE:order-1"), transporte.llamadas)
    }

    // MARK: - Qué orden se borra, y cuál nunca

    @Test
    fun `P1 la cuenta de una mesa o de un split nunca se borra`() = runTest {
        val transporte = CancelacionTransportFalso().apply { programarEstados(cancelado) }
        val c = coordinador(transporte)
        c.registrar(intencion(orderId = "order-mesa", borrarOrden = false))

        c.procesar("req-1")

        assertTrue(transporte.llamadasBorrar.isEmpty())
        assertEquals(EstadoDeCancelacion.Cerrada, c.estadoActual("req-1"))
        assertTrue(store.todas().isEmpty())
    }

    @Test
    fun `P1 sin cobro enviado se borra la orden sin preguntarle a la terminal`() = runTest {
        val transporte = CancelacionTransportFalso()
        val c = coordinador(transporte)
        c.registrar(intencion(id = "orden:o1", requestId = null, orderId = "o1", terminalId = null, fase = FaseDeCancelacion.BORRAR_ORDEN))

        c.procesar("orden:o1")

        assertEquals(listOf("DELETE:o1"), transporte.llamadas)
        assertEquals(EstadoDeCancelacion.Cerrada, c.estadoActual("orden:o1"))
    }

    @Test
    fun `la orden que la intencion no conocia se toma de la consulta`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            programarEstados(ChargeStatusProbe.Known("CANCELLED", false, cancelDisposition = "ACCEPTED", orderId = "order-9"))
        }
        val c = coordinador(transporte)
        c.registrar(intencion(orderId = null, borrarOrden = true))

        c.procesar("req-1")

        assertEquals(listOf("CANCEL:req-1", "GET:req-1", "DELETE:order-9"), transporte.llamadas)
    }

    @Test
    fun `sin terminal conocida consulta primero y cancela en la terminal que diga el servidor`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            programarEstados(ChargeStatusProbe.Known("CANCEL_REQUESTED", inProgress = true, terminalId = "t9"), cancelado)
        }
        val c = coordinador(transporte)
        c.registrar(intencion(terminalId = null))

        c.procesar("req-1")

        assertEquals(listOf("GET:req-1", "CANCEL:req-1", "GET:req-1", "DELETE:order-1"), transporte.llamadas)
    }

    // MARK: - Llave del cobro

    @Test
    fun `P1 la llave de OTRO cobro nunca se suelta`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            llave = "req-otro"
            programarEstados(cancelado)
        }
        val c = coordinador(transporte)
        c.registrar(intencion())

        c.procesar("req-1")

        assertEquals("req-otro", transporte.llave)
    }

    // MARK: - Una corrida a la vez, disparadores

    @Test
    fun `P1 una sola corrida por intencion aunque se pida dos veces`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transporte = CancelacionTransportFalso().apply {
            antesDeContestarCancel = { gate.await() }
            programarEstados(cancelado)
        }
        val c = coordinador(transporte)
        c.registrar(intencion())

        val primera = launch { c.procesar("req-1") }
        runCurrent()
        c.procesarAhora("req-1")
        val segunda = launch { c.procesar("req-1") }
        runCurrent()
        gate.complete(Unit)
        primera.join()
        segunda.join()
        runCurrent()

        assertEquals(1, transporte.llamadasCancel.size)
        assertEquals(1, transporte.llamadasBorrar.size)
    }

    @Test
    fun `P1 al arrancar con red se reproduce lo que quedo del proceso anterior`() = runTest {
        // Otro proceso dejó la intención en disco y murió.
        store.registrar(intencion())
        val transporte = CancelacionTransportFalso().apply { programarEstados(cancelado) }
        val c = coordinador(transporte)

        c.start()
        runCurrent()

        assertEquals(listOf("CANCEL:req-1", "GET:req-1", "DELETE:order-1"), transporte.llamadas)
        c.stop()
    }

    @Test
    fun `P1 al volver la red se reproduce sola tras 2 segundos de estabilizacion`() = runTest {
        conectado.value = false
        val transporte = CancelacionTransportFalso().apply { programarEstados(cancelado) }
        val c = coordinador(transporte)
        c.registrar(intencion())
        c.start()
        runCurrent()
        assertTrue("sin red no sale nada", transporte.llamadas.isEmpty())

        conectado.value = true
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue("todavía estabilizando", transporte.llamadas.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("CANCEL:req-1", "GET:req-1", "DELETE:order-1"), transporte.llamadas)
        c.stop()
    }

    @Test
    fun `el temporizador reintenta lo pendiente respetando la espera entre intentos`() = runTest {
        val transporte = CancelacionTransportFalso().apply { programarEstados(ChargeStatusProbe.Known("UNKNOWN", false)) }
        val c = coordinador(transporte, tickMs = 10_000)
        c.registrar(intencion())
        c.start()
        runCurrent()
        val tras1 = transporte.llamadasEstado.size
        assertEquals(1, tras1)

        // Un tic antes de que venza la espera (30 s tras el primer intento): no insiste.
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(tras1, transporte.llamadasEstado.size)

        // Vencida la espera, el tic vuelve a preguntar.
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(tras1 + 1, transporte.llamadasEstado.size)
        c.stop()
    }

    @Test
    fun `volver a consultar ignora la espera entre intentos`() = runTest {
        val transporte = CancelacionTransportFalso().apply { programarEstados(ChargeStatusProbe.Known("UNKNOWN", false)) }
        val c = coordinador(transporte)
        c.registrar(intencion())
        c.procesar("req-1")
        val antes = transporte.llamadasEstado.size

        c.procesarAhora("req-1")
        runCurrent()

        assertEquals(antes + 1, transporte.llamadasEstado.size)
    }

    @Test
    fun `los pendientes se publican para el banner y la hoja`() = runTest {
        val transporte = CancelacionTransportFalso().apply { programarEstados(ChargeStatusProbe.Known("UNKNOWN", false)) }
        val c = coordinador(transporte)

        c.registrar(intencion("req-1"))
        c.registrar(intencion("req-2"))
        assertEquals(listOf("req-1", "req-2"), c.pendientes.value.map { it.id })

        transporte.programarEstados(cancelado)
        c.procesar("req-1")
        assertEquals(listOf("req-2"), c.pendientes.value.map { it.id })
    }

    @Test
    fun `cobro aplicado quita el aviso que quedo en espera`() = runTest {
        val transporte = CancelacionTransportFalso().apply {
            llave = "req-otro"
            programarEstados(ChargeStatusProbe.Known("COMPLETED", false, paymentId = "pay-1"))
        }
        val c = coordinador(transporte)
        c.registrar(intencion())
        c.procesar("req-1")
        assertEquals(1, store.todas().size)

        c.cobroAplicado("req-1")

        assertTrue(store.todas().isEmpty())
    }
}
