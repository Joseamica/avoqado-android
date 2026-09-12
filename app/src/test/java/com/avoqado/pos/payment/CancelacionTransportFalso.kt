package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.CancelacionDeCobroTransport
import com.avoqado.pos.payment.data.ConsultaDeEstado
import com.avoqado.pos.payment.data.RespuestaDeCancelacion
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden
import com.avoqado.pos.printing.data.AlmacenDeTexto

/**
 * Transporte FALSO de la cancelación durable: registra cada llamada EN ORDEN (es lo que prueban
 * los tests del orden cancel → desenlace → borrado) y contesta lo que cada prueba le programa.
 *
 * Las respuestas se programan como COLAS: se consume una por llamada y, cuando se acaba, se repite
 * la última. Así «CANCEL_REQUESTED, CANCEL_REQUESTED, CANCELLED+ACCEPTED» describe una terminal que
 * tarda dos sondeos en confirmar.
 */
class CancelacionTransportFalso : CancelacionDeCobroTransport {

    /** Cada llamada, en el orden en que salió: `CANCEL:<requestId>`, `GET:<requestId>`, `DELETE:<orderId>`. */
    val llamadas = mutableListOf<String>()

    /** Venue con el que salió cada llamada (misma posición que [llamadas]). */
    val venues = mutableListOf<String>()

    var respuestasCancel = ArrayDeque(listOf(RespuestaDeCancelacion(http = 200, success = true)))
    var respuestasEstado = ArrayDeque(listOf(ConsultaDeEstado(ChargeStatusProbe.Known("CANCEL_REQUESTED", inProgress = true), sinRespuesta = false)))
    var respuestasBorrar = ArrayDeque<ResultadoDeCancelarOrden>(listOf(ResultadoDeCancelarOrden.Cancelada))

    /** Se ejecuta ANTES de contestar el cancel: permite retenerlo (orden de llegada invertido). */
    var antesDeContestarCancel: suspend () -> Unit = {}

    /** La llave durable del cobro, en memoria. */
    var llave: String? = null

    val llamadasCancel get() = llamadas.filter { it.startsWith("CANCEL:") }
    val llamadasEstado get() = llamadas.filter { it.startsWith("GET:") }
    val llamadasBorrar get() = llamadas.filter { it.startsWith("DELETE:") }

    fun programarEstados(vararg estados: ChargeStatusProbe) {
        respuestasEstado = ArrayDeque(estados.map { ConsultaDeEstado(it, sinRespuesta = it is ChargeStatusProbe.Unreachable) })
    }

    fun programarBorrados(vararg resultados: ResultadoDeCancelarOrden) {
        respuestasBorrar = ArrayDeque(resultados.toList())
    }

    fun programarCancels(vararg respuestas: RespuestaDeCancelacion) {
        respuestasCancel = ArrayDeque(respuestas.toList())
    }

    private fun <T> ArrayDeque<T>.siguiente(): T = if (size > 1) removeFirst() else first()

    override suspend fun pedirCancelacion(venueId: String, terminalId: String, requestId: String): RespuestaDeCancelacion {
        llamadas += "CANCEL:$requestId"
        venues += venueId
        antesDeContestarCancel()
        return respuestasCancel.siguiente()
    }

    override suspend fun consultarEstado(venueId: String, requestId: String): ConsultaDeEstado {
        llamadas += "GET:$requestId"
        venues += venueId
        return respuestasEstado.siguiente()
    }

    override suspend fun cancelarOrden(venueId: String, orderId: String): ResultadoDeCancelarOrden {
        llamadas += "DELETE:$orderId"
        venues += venueId
        return respuestasBorrar.siguiente()
    }

    override suspend fun soltarLlaveSiEs(requestId: String) {
        if (llave == requestId) llave = null
    }

    override suspend fun armarLlaveSiLibre(requestId: String): Boolean {
        val armada = llave
        if (armada != null && armada != requestId) return false
        llave = requestId
        return true
    }
}

/** Almacén en memoria que puede fallar al escribir, para probar que «no se pudo guardar» se DICE. */
class AlmacenQuePuedeFallar : AlmacenDeTexto {
    var texto: String? = null
    var fallarAlEscribir = false
    var escrituras = 0
    override fun leer(): String? = texto
    override fun escribir(texto: String): Boolean {
        if (fallarAlEscribir) return false
        escrituras++
        this.texto = texto
        return true
    }
    override fun borrar() { texto = null }
}
