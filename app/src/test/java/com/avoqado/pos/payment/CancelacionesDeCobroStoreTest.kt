package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.CancelacionesDeCobroEnTexto
import com.avoqado.pos.payment.data.FaseDeCancelacion
import com.avoqado.pos.payment.data.IntencionDeCancelarCobro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La intención de cancelar un cobro vive en DISCO, escrita antes de tocar la red.
 *
 * Es lo que hace que sobreviva a la muerte del proceso entre el toque de «Cancelar» y el POST: sin
 * esto, una app que muere a media cancelación dejaba la orden abierta y la terminal quizá cobrando,
 * sin nadie que volviera a intentarlo.
 */
class CancelacionesDeCobroStoreTest {

    private fun intencion(
        id: String = "req-1",
        fase: FaseDeCancelacion = FaseDeCancelacion.PEDIR_CANCEL,
        creadaEn: Long = 1_000,
    ) = IntencionDeCancelarCobro(
        id = id,
        requestId = id,
        venueId = "venue-1",
        terminalId = "t1",
        orderId = "order-1",
        borrarOrden = true,
        actorStaffId = "staff-1",
        montoCents = 1500,
        creadaEn = creadaEn,
        fase = fase.name,
    )

    @Test
    fun `P1 registrar escribe en disco con commit y se puede leer`() {
        val almacen = AlmacenQuePuedeFallar()
        val store = CancelacionesDeCobroEnTexto(almacen)

        assertTrue(store.registrar(intencion()))

        assertEquals(1, almacen.escrituras)
        assertEquals(listOf(intencion()), store.todas())
        assertEquals(intencion(), store.leer("req-1"))
    }

    @Test
    fun `P1 la intencion sobrevive a un reinicio del proceso`() {
        val almacen = AlmacenQuePuedeFallar()
        CancelacionesDeCobroEnTexto(almacen).registrar(intencion())

        // Otro proceso: una instancia nueva sobre el MISMO disco.
        val despuesDelReinicio = CancelacionesDeCobroEnTexto(almacen)

        assertEquals(listOf(intencion()), despuesDelReinicio.todas())
    }

    @Test
    fun `P1 un doble toque no duplica ni reinicia la intencion`() {
        val store = CancelacionesDeCobroEnTexto(AlmacenQuePuedeFallar())
        store.registrar(intencion())
        store.actualizar(intencion(fase = FaseDeCancelacion.ESPERAR_DESENLACE))

        // El segundo toque llega con la intención recién armada (PEDIR_CANCEL).
        assertTrue(store.registrar(intencion()))

        assertEquals(1, store.todas().size)
        assertEquals(FaseDeCancelacion.ESPERAR_DESENLACE, store.leer("req-1")!!.faseActual)
    }

    @Test
    fun `P1 si el disco no acepta la escritura se DICE que no se guardo`() {
        val almacen = AlmacenQuePuedeFallar().apply { fallarAlEscribir = true }
        val store = CancelacionesDeCobroEnTexto(almacen)

        assertFalse(store.registrar(intencion()))
        assertTrue(store.todas().isEmpty())
    }

    @Test
    fun `quitar borra la intencion del disco`() {
        val almacen = AlmacenQuePuedeFallar()
        val store = CancelacionesDeCobroEnTexto(almacen)
        store.registrar(intencion("req-1"))
        store.registrar(intencion("req-2", creadaEn = 2_000))

        assertTrue(store.quitar("req-1"))

        assertEquals(listOf("req-2"), CancelacionesDeCobroEnTexto(almacen).todas().map { it.id })
    }

    @Test
    fun `actualizar una intencion que no existe no la crea`() {
        val store = CancelacionesDeCobroEnTexto(AlmacenQuePuedeFallar())
        assertFalse(store.actualizar(intencion()))
        assertNull(store.leer("req-1"))
    }

    @Test
    fun `las intenciones salen en el orden en que se pidieron`() {
        val store = CancelacionesDeCobroEnTexto(AlmacenQuePuedeFallar())
        store.registrar(intencion("req-b", creadaEn = 2_000))
        store.registrar(intencion("req-a", creadaEn = 1_000))

        assertEquals(listOf("req-a", "req-b"), store.todas().map { it.id })
    }

    @Test
    fun `un archivo ilegible no tumba la app y la siguiente intencion se guarda`() {
        val almacen = AlmacenQuePuedeFallar().apply { texto = "{esto no es json" }
        val store = CancelacionesDeCobroEnTexto(almacen)

        assertTrue(store.todas().isEmpty())
        assertTrue(store.registrar(intencion()))
        assertEquals(1, store.todas().size)
    }

    @Test
    fun `P1 una fase desconocida se lee como esperar desenlace, nunca como cerrada`() {
        val almacen = AlmacenQuePuedeFallar()
        val store = CancelacionesDeCobroEnTexto(almacen)
        store.registrar(intencion().copy(fase = "UNA_FASE_DEL_FUTURO"))

        assertEquals(FaseDeCancelacion.ESPERAR_DESENLACE, CancelacionesDeCobroEnTexto(almacen).leer("req-1")!!.faseActual)
    }
}
