package com.avoqado.pos.payment.domain

/**
 * Qué consta de un cobro con tarjeta que el cajero pidió CANCELAR.
 *
 * Son tres, y la del medio es la que sostiene todo: mientras no conste que no se cobró, la orden
 * NO se toca.
 */
sealed class DesenlaceDeCancelacion {
    /** La terminal SÍ cobró. La orden jamás se borra; el cobro se aplica a la venta. */
    data class SeCobro(val paymentId: String?) : DesenlaceDeCancelacion()

    /** Consta —con evidencia acreditada— que NO se cobró. Recién aquí se puede cancelar la orden. */
    data object NoSeCobro : DesenlaceDeCancelacion()

    /**
     * No consta. La intención sigue viva y se vuelve a preguntar.
     *
     * @param enVuelo la solicitud sigue viva en el servidor (PENDING/SENT/CANCEL_REQUESTED): el
     *   cancel se REENVÍA con el mismo `requestId`, porque pudo llegar antes que la fila.
     * @param sinFila el servidor no encontró la solicitud (404). No prueba ausencia de cargo.
     * @param sinRed no se pudo preguntar.
     */
    data class Pendiente(
        val enVuelo: Boolean = false,
        val sinFila: Boolean = false,
        val sinRed: Boolean = false,
    ) : DesenlaceDeCancelacion()
}

/** Qué hacer después de pedirle al servidor que cancele la ORDEN. */
sealed class PasoTrasBorrar {
    /** Listo: la cuenta quedó cancelada (o ya no existe). */
    data object Cerrar : PasoTrasBorrar()

    /** La cuenta tiene dinero encima: no se cancela por aquí, y la intención se cierra con el motivo. */
    data class CerrarConDinero(val mensaje: String?) : PasoTrasBorrar()

    /** El servidor dice que MI cobro sigue sin desenlace: se vuelve a esperar antes de borrar. */
    data object VolverAEsperar : PasoTrasBorrar()

    /** Nada concluyente (sin red, 5xx, 403, otro cobro bloqueando): se reintenta más tarde. */
    data class Reintentar(val sinRed: Boolean) : PasoTrasBorrar()
}

/** Lo que contestó el servidor al DELETE de una orden, ya leído. */
sealed class ResultadoDeCancelarOrden {
    data object Cancelada : ResultadoDeCancelarOrden()

    /** 409 `ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE`: un cobro de terminal sin desenlace la bloquea. */
    data class BloqueadaPorCobro(val requestId: String?, val mensaje: String?) : ResultadoDeCancelarOrden()

    /** Otro 4xx de NUESTRA API: cuenta pagada/parcial, sin permiso, conflicto sin código. */
    data class RechazoDeNegocio(val http: Int, val code: String?, val mensaje: String?) : ResultadoDeCancelarOrden()

    /** 404 de nuestra API: la orden ya no existe. */
    data object NoExiste : ResultadoDeCancelarOrden()

    /** No hubo respuesta de la API (sin red, túnel caído, 4xx de un intermediario). */
    data object SinRed : ResultadoDeCancelarOrden()

    data class ErrorDeServidor(val http: Int) : ResultadoDeCancelarOrden()
}

/**
 * La decisión PURA que autoriza —o no— a cancelar la orden de un cobro que se pidió cancelar.
 *
 * 🔴 Es más estricta que [CardChargeDecision] a propósito, y confundirlas cuesta dinero:
 * aquélla contesta «¿puedo volver a cobrar?» y para eso un `FAILED` a secas alcanza; ésta contesta
 * «¿puedo BORRAR la cuenta?», y ahí un `FAILED` sin evidencia, un `CANCELLED` por gracia o un 404
 * no alcanzan — si la terminal sí cobró, la venta quedaría sin la cuenta que explica su dinero.
 *
 * Contexto (producción, 11-sep-2026): «Cancelar» mandaba el cancel y el DELETE de la orden EN
 * PARALELO. Cuando el DELETE llegaba primero, el servidor contestaba 409 y la orden quedaba
 * huérfana sin que nadie volviera a intentarlo.
 */
object CancelacionDeCobro {

    // MARK: - Textos (IDÉNTICOS en Android e iOS, palabra por palabra)

    const val TEXTO_CANCELANDO = "Cancelando el cobro…"
    const val TITULO_PENDIENTE = "Cancelación pendiente"
    const val CUERPO_PENDIENTE =
        "La terminal todavía no confirma que el cobro se detuvo. La venta se cancelará sola en cuanto conste que no se cobró."
    const val CUERPO_SIN_RED =
        "Sin conexión en esta tablet: la cancelación se enviará en cuanto vuelva la red. La terminal podría seguir mostrando el cobro."
    const val BOTON_VOLVER_A_CONSULTAR = "Volver a consultar"
    const val BOTON_SALIR_PENDIENTE = "Salir (queda pendiente)"
    const val BOTON_CANCELAR_VENTA = "Cancelar la venta"
    const val NO_SE_PUDO_GUARDAR = "No se pudo guardar la cancelación en este equipo. Inténtalo de nuevo."
    const val SE_COBRO_AL_FINAL = "La terminal sí cobró este pago: la venta queda pagada."
    const val RECHAZO_TERMINAL_NO_CONECTADA = "La terminal no está conectada. Este cobro NO se envió; no se cobró nada."
    const val RECHAZO_CUENTA_CANCELADA = "La cuenta está cancelada: este cobro NO se envió, no se cobró nada."
    const val RECHAZO_CUENTA_PAGADA = "La cuenta ya está pagada. Este cobro NO se envió."
    const val RECHAZO_CUENTA_INEXISTENTE = "La cuenta ya no existe. Este cobro NO se envió."
    const val COBRO_EN_CURSO_BLOQUEA_CUENTA =
        "Hay un cobro con tarjeta en curso para esta cuenta: cancélalo o espera a que termine."

    /** Banner fuera del flujo de cobro: lo que queda pendiente se VE, no se deduce. */
    fun bannerPendientes(cuantas: Int): String = "Cancelaciones pendientes: $cuantas"

    // MARK: - Contrato con el servidor

    /** Código del 409 que impide cancelar/anular una cuenta con un cobro de terminal vivo. */
    const val ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE = "ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE"

    /** 503 del servidor: la admisión no pudo decidir ahora. Se reintenta con el MISMO `requestId`. */
    const val ADMISSION_RETRY = "TERMINAL_PAYMENT_ADMISSION_RETRY"

    private const val OUTCOME_CHARGED = "CHARGED"
    private const val OUTCOME_NOT_CHARGED = "NOT_CHARGED"

    /** Prefijo de la lápida de admisión del servidor: la fila existe para decir que NO se creó el cobro. */
    private const val LAPIDA_DE_ADMISION = "REJECTED_"

    private val EN_VUELO = setOf("PENDING", "SENT", "CANCEL_REQUESTED")

    /**
     * Rechazos de admisión que, CORRELACIONADOS con la propia solicitud, prueban que este cobro
     * nunca se creó (H.5 del diseño: todo «no se creó» deja lápida en el servidor).
     */
    private val RECHAZOS_QUE_PRUEBAN_QUE_NO_SE_CREO = mapOf(
        "TERMINAL_NOT_CONNECTED" to RECHAZO_TERMINAL_NO_CONECTADA,
        "TERMINAL_NO_SOCKET" to RECHAZO_TERMINAL_NO_CONECTADA,
        "TERMINAL_NOT_IN_VENUE" to RECHAZO_TERMINAL_NO_CONECTADA,
        "ORDER_CANCELLED_NO_NEW_CHARGE" to RECHAZO_CUENTA_CANCELADA,
        "ORDER_ALREADY_PAID" to RECHAZO_CUENTA_PAGADA,
        "ORDER_NOT_FOUND" to RECHAZO_CUENTA_INEXISTENTE,
    )

    /**
     * ¿Qué consta de este cobro?
     *
     * Orden de las preguntas, y no es cosmético:
     *  1. **¿Cobró?** Cualquier señal de pago gana — un `Payment` registrado o el desenlace
     *     canónico `CHARGED`. Es el lado que no pierde dinero: una orden cobrada no se borra.
     *  2. **¿Sigue viva?** Una fila en vuelo puede todavía cobrar: se espera y se reenvía el cancel.
     *  3. **¿Consta que NO cobró?** Sólo con el `outcome` canónico del servidor o, contra un
     *     servidor que todavía no lo manda, con las DOS evidencias que existían: un `CANCELLED`
     *     con `cancelDisposition = ACCEPTED` (la terminal aceptó el cancel antes de cobrar) y una
     *     lápida de admisión (`FAILED` + `failureCode` `REJECTED_*`: el cobro no llegó a crearse).
     *  4. Todo lo demás: **no consta**.
     */
    fun decidir(probe: ChargeStatusProbe): DesenlaceDeCancelacion = when (probe) {
        is ChargeStatusProbe.Unreachable -> DesenlaceDeCancelacion.Pendiente(sinRed = true)
        is ChargeStatusProbe.NotFound -> DesenlaceDeCancelacion.Pendiente(sinFila = true)
        is ChargeStatusProbe.Known -> decidirConEstado(probe)
    }

    private fun decidirConEstado(probe: ChargeStatusProbe.Known): DesenlaceDeCancelacion {
        val paymentId = probe.paymentId?.trim()?.takeIf { it.isNotEmpty() }
        val outcome = probe.outcome?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

        if (outcome == OUTCOME_CHARGED || (probe.status == "COMPLETED" && paymentId != null)) {
            return DesenlaceDeCancelacion.SeCobro(paymentId)
        }
        if (probe.inProgress || probe.status in EN_VUELO) {
            return DesenlaceDeCancelacion.Pendiente(enVuelo = true)
        }
        // Con el campo canónico presente manda el servidor: la regla vieja NO se usa de respaldo,
        // o un `UNRESOLVED` explícito quedaría autorizado por una disposición vieja.
        if (outcome != null) {
            return if (outcome == OUTCOME_NOT_CHARGED) DesenlaceDeCancelacion.NoSeCobro else DesenlaceDeCancelacion.Pendiente()
        }
        val lapidaDeAdmision = probe.status == "FAILED" && probe.failureCode?.trim()?.startsWith(LAPIDA_DE_ADMISION) == true
        val canceladoPorLaTerminal = probe.status == "CANCELLED" && probe.cancelDisposition == "ACCEPTED"
        return if (lapidaDeAdmision || canceladoPorLaTerminal) DesenlaceDeCancelacion.NoSeCobro else DesenlaceDeCancelacion.Pendiente()
    }

    /**
     * El servidor RECHAZÓ crear el cobro y nombró a MI solicitud: consta que no se envió nada.
     *
     * 🔴 Lo que lo hace seguro es la CORRELACIÓN, no el código —misma regla que el `TERMINAL_BUSY`
     * de [CardChargeDecision.refusedForAnotherRequest]—: un rechazo que no nombra a nadie (servidor
     * viejo) o que nombra otra solicitud no prueba nada de ésta, y se sigue consultando.
     *
     * @return el texto que ve el cajero, o `null` si el rechazo no prueba nada.
     */
    fun rechazoQueProbaNoSeCreo(code: String?, detailsRequestId: String?, miRequestId: String): String? {
        val texto = RECHAZOS_QUE_PRUEBAN_QUE_NO_SE_CREO[code?.trim()] ?: return null
        val nombrada = detailsRequestId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return texto.takeIf { nombrada == miRequestId }
    }

    /** Qué sigue después del DELETE de la orden. */
    fun trasBorrar(resultado: ResultadoDeCancelarOrden, miRequestId: String?): PasoTrasBorrar = when (resultado) {
        is ResultadoDeCancelarOrden.Cancelada, is ResultadoDeCancelarOrden.NoExiste -> PasoTrasBorrar.Cerrar
        is ResultadoDeCancelarOrden.BloqueadaPorCobro ->
            // Sólo MI cobro devuelve la intención a esperar: si bloquea OTRO, esperar el mío no lo
            // desbloquea — se reintenta el borrado más tarde, cuando ese otro termine.
            if (miRequestId != null && resultado.requestId == miRequestId) {
                PasoTrasBorrar.VolverAEsperar
            } else {
                PasoTrasBorrar.Reintentar(sinRed = false)
            }
        is ResultadoDeCancelarOrden.RechazoDeNegocio ->
            // 400 = la cuenta tiene dinero (PAID/PARTIAL). No es huérfana: alguien la pagó. Se
            // cierra la intención con el motivo en vez de insistir contra una regla que no cede.
            if (resultado.http == 400) PasoTrasBorrar.CerrarConDinero(resultado.mensaje) else PasoTrasBorrar.Reintentar(sinRed = false)
        is ResultadoDeCancelarOrden.SinRed -> PasoTrasBorrar.Reintentar(sinRed = true)
        is ResultadoDeCancelarOrden.ErrorDeServidor -> PasoTrasBorrar.Reintentar(sinRed = false)
    }
}
