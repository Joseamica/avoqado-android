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
    /**
     * D6 (QA en la N86, 3-nov): la terminal ya se soltó, así que ya no hay nada que confirme solo: esa venta sigue en duda hasta que
     * una persona lo declare o llegue el aviso del banco. Igual en iOS, letra por letra.
     */
    const val CUERPO_TERMINAL_LIBRE =
        "La terminal ya está libre, pero no consta si esta venta se cobró. Si no se cobró, decláralo aquí y la venta se cancela."
    const val CUERPO_SIN_RED =
        "Sin conexión en esta tablet: la cancelación se enviará en cuanto vuelva la red. La terminal podría seguir mostrando el cobro."
    const val BOTON_VOLVER_A_CONSULTAR = "Volver a consultar"
    const val BOTON_SALIR_PENDIENTE = "Salir (queda pendiente)"
    const val BOTON_CANCELAR_VENTA = "Cancelar la venta"

    // ── La declaración del cajero: «ya revisé la terminal y no se cobró» (etapa 2, 19-sep).
    //    🔴 Los MISMOS textos, cadena por cadena, que iOS: la divergencia `Atendio/Atendió` del
    //    ticket nació de dos apps escribiendo por su cuenta lo que parecía lo mismo.
    const val TERMINAL_LIBRE_VENTA_EN_DUDA = "La terminal ya está libre · ¿se cobró esta venta?"
    const val NO_SE_COBRO_COBRAR_OTRA_VEZ = "No se cobró → cobrar otra vez"

    /**
     * Revisión de iOS (3-nov): esas dos frases hablan de ESTA venta. Con el cobro de otra venta («Revisar» desde el aviso) sería
     * «¿se cobró esta venta?» sobre otra, y «cobrar otra vez» no abre el cobro. Igual en iOS.
     */
    fun hablaDeTerminalLibre(terminalLibre: Boolean, fromPreviousSale: Boolean): Boolean = terminalLibre && !fromPreviousSale
    const val BOTON_DECLARAR_NO_COBRADO = "No se cobró"

    // ── «Cobro sin confirmar» (D11, founder 3-nov: «tiene 4 botones confusos y se ve mal»): una sola pregunta y una sola decisión.
    const val SI_DICE_APROBADO = "Si la terminal dice aprobado:"
    const val SI_DICE_APROBADO_QUE_HACER = "espera aquí, se registra solo."
    const val SI_NO_SE_COBRO = "Si no se cobró:"
    const val SI_NO_SE_COBRO_QUE_HACER = "dilo con el botón."
    const val CONSULTA_AUTOMATICA = "Preguntando sola cada 5 segundos"

    /** El importe es el del cobro que se pregunta, nunca el del carrito; si no consta, la pregunta va sin él. */
    fun tituloSinConfirmar(montoDelCobro: String?, fromPreviousSale: Boolean): String {
        val venta = if (fromPreviousSale) "la venta anterior" else "esta venta"
        return if (montoDelCobro == null) "¿Se cobró $venta?" else "¿Se cobró $venta de $montoDelCobro?"
    }
    const val DECLARACION_TITULO = "Confirma que no se cobró"
    const val DECLARACION_CONFIRMAR = "Sí, no se cobró"
    const val DECLARACION_CANCELAR = "Mejor no"
    const val DECLARACION_LISTO = "Listo, puedes cobrar"

    /**
     * 🔴 Online-only A PROPÓSITO, y por eso este texto NO dice «se guardó». El cajero afirma lo que
     * ve AHORA en la pantalla de la terminal, y esa afirmación caduca: reproducida diez minutos
     * después podría caer sobre una venta que entretanto sí se cobró.
     *
     * SIN USO desde C1 (22-oct): lo sustituyen [SIN_CONEXION_REVISION] y [SIN_CONEXION_REVISION_ESTA_VENTA]. Se conserva
     * porque más de 20 ramas vivas de ANDROID parten de develop con ella: borrarla arriesga choques al unir.
     */
    const val DECLARACION_SIN_RED = "Necesitas conexión para confirmar esta declaración."

    /**
     * C1 (spec §Sin red, 22-oct): una revisión —«Revisar ese cobro», «Volver a consultar» o la declaración «Ya revisé la
     * terminal: no se cobró»— que no se pudo hacer por falta de red, cuando la duda es de OTRA venta. Ésta sí se puede
     * cobrar en efectivo: el efectivo no se frena. C2, C4 y C5 lo reusan; iOS, igual, letra por letra.
     *
     * 🔴 Online-only A PROPÓSITO: no dice «se guardó» y nunca se encola (ver [DECLARACION_SIN_RED]).
     */
    const val SIN_CONEXION_REVISION = "Sin conexión: la revisión necesita internet; puedes cobrar en efectivo"

    /**
     * C1, fix round 2 (controlador, 22-oct): la MISMA situación cuando la duda es de la venta EN CURSO —su «Cobro sin
     * confirmar» propio, su declaración, o «Revisar ese cobro» con alcance `ORDER`—. No invita al efectivo: cobrar en
     * efectivo la misma venta con la tarjeta en duda es un hueco que el founder todavía no decide (D10). Igual en iOS.
     */
    const val SIN_CONEXION_REVISION_ESTA_VENTA =
        "Sin conexión: la revisión necesita internet. No cobres esta venta otra vez hasta confirmarla."

    /** La sesión venció y se renovó sola; la declaración NO se reenvió a propósito. */
    const val DECLARACION_SESION_RENOVADA =
        "Tu sesión se renovó. Vuelve a confirmar que no se cobró."

    /** Sólo para una respuesta del servidor sin mensaje legible: el suyo SIEMPRE tiene prioridad. */
    const val DECLARACION_RECHAZO_GENERICO =
        "Este cobro todavía no se puede declarar. Vuelve a consultar en un momento."

    /**
     * C4 (P5, textos honestos, 22-oct): la declaración se rechazó con `EXECUTION_STILL_ACTIVE` —la terminal todavía reporta
     * ese cobro en curso—. El texto del servidor («Espera unos segundos y vuelve a consultar») invitaba a insistir sobre algo
     * que no cede en segundos; éste dice lo que de verdad pasa y qué hacer si urge. Es la ÚNICA excepción a «el mensaje del
     * rechazo viaja tal cual desde el servidor». Igual en iOS, letra por letra.
     */
    const val TERMINAL_REPORTA_SIN_TERMINAR =
        "La terminal reporta este cobro sin terminar. Se libera sola en unos minutos; si urge, llama a soporte de Avoqado."

    /**
     * F1 (QA en la N86, 3-nov): un motivo en pantalla que una respuesta del servidor ya desmintió: «Sin conexión…» cuando ya
     * contestó, o «Se libera sola…» y «todavía no se puede declarar» con la terminal ya libre (D6). Los demás rechazos se quedan: son
     * de una persona que aún no los lee.
     */
    fun motivoCaducado(mensaje: String, terminalLibre: Boolean): Boolean =
        mensaje.endsWith(SIN_CONEXION_REVISION) ||
            mensaje.endsWith(SIN_CONEXION_REVISION_ESTA_VENTA) ||
            (terminalLibre && (mensaje.endsWith(TERMINAL_REPORTA_SIN_TERMINAR) || mensaje.endsWith(DECLARACION_RECHAZO_GENERICO)))

    /**
     * C4 (P4): título del teclado donde un gerente teclea su PIN para que el mesero —sin `payments:reconcile-uncharged`—
     * declare «no se cobró». Igual en iOS, letra por letra.
     */
    const val PIN_DE_GERENTE_TITULO = "Pide a un gerente que ingrese su PIN"

    /**
     * El permiso de la declaración, por nombre EXACTO de `avoqado-server/src/lib/permissions.ts`. Sólo nombra la acción en
     * el teclado (`PermissionLabels`): quién puede declarar lo decide el SERVIDOR, nunca la tablet.
     */
    const val PERMISO_DECLARAR_NO_COBRADO = "payments:reconcile-uncharged"

    /** 403 de la declaración: la sesión no tiene el permiso y falta el PIN de un gerente, o el PIN no sirve. */
    const val CODIGO_PIDE_PIN_DE_GERENTE = "SUPERVISOR_AUTHORIZATION_REQUIRED"

    /** 409 de la declaración: la terminal todavía reporta el cobro en curso. */
    const val CODIGO_EJECUCION_AUN_ACTIVA = "EXECUTION_STILL_ACTIVE"

    /** 429 del servidor: demasiados PIN probados (la cubeta del PIN de gerente, 15 min). */
    const val CODIGO_DEMASIADOS_INTENTOS = "RATE_LIMIT_EXCEEDED"

    /**
     * El cuerpo del diálogo lleva el monto DEL COBRO PENDIENTE, para que nadie declare sobre el
     * cobro equivocado. 🔴 Vacío ⇒ se dice «ese cobro»: un importe inventado —el del carrito, por
     * ejemplo— haría que el cajero firmara una afirmación falsa (P1 de Codex, 19-sep).
     */
    fun cuerpoDeLaDeclaracion(monto: String?) =
        if (monto.isNullOrBlank()) {
            "Estás declarando que miraste la pantalla de la terminal y ese cobro no pasó. " +
                "Tu nombre queda en el registro."
        } else {
            "Estás declarando que miraste la pantalla de la terminal y el cobro de $monto no pasó. " +
                "Tu nombre queda en el registro."
        }
    const val NO_SE_PUDO_GUARDAR = "No se pudo guardar la cancelación en este equipo. Inténtalo de nuevo."
    /** D2 v7: «Cancelar» no pudo borrar los apartados de efectivo de la venta (disco). La pantalla no se cierra. Igual en iOS. */
    const val NO_SE_PUDO_CANCELAR_EL_COBRO = "No se pudo cancelar el cobro. Intenta de nuevo."
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
