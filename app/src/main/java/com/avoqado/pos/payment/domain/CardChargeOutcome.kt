package com.avoqado.pos.payment.domain

/**
 * Lo que el server sabe de una solicitud de cobro cuando le preguntamos por su `requestId`.
 *
 * 🔴 `NotFound` y `Unreachable` NO son lo mismo, y confundirlos es lo que cuesta dinero:
 * el primero sólo dice que la consulta no encontró la solicitud;
 * el segundo es ignorancia pura (no pudimos preguntar).
 */
sealed class ChargeStatusProbe {
    /**
     * El server contestó con el estado durable de la solicitud.
     *
     * Los campos desde [outcome] son el DESENLACE CANÓNICO del servidor (diseño §C.1, 11-sep) y son
     * **opcionales**: un servidor que todavía no los manda los deja en `null` y cada cliente decide
     * con lo de siempre. Se leen como texto libre, nunca como enum estricto — un valor nuevo del
     * servidor no puede tumbar la lectura del estado de un cobro.
     */
    data class Known(
        val status: String,
        val inProgress: Boolean,
        val paymentId: String? = null,
        val cancelDisposition: String? = null,
        /** `CHARGED` · `NOT_CHARGED` · `UNRESOLVED`, o lo que el servidor agregue después. */
        val outcome: String? = null,
        val outcomeEvidence: String? = null,
        val evidenceClass: String? = null,
        /** Crudo, sólo diagnóstico. La única decisión que lo mira es la lápida de admisión `REJECTED_*`. */
        val failureCode: String? = null,
        val reconciliationRequired: Boolean? = null,
        /** La orden del cobro según el servidor: completa una intención de cancelar que no la conocía. */
        val orderId: String? = null,
        val terminalId: String? = null,
    ) : ChargeStatusProbe()

    /** 404: no se encontró la solicitud; no demuestra ausencia de cargo. */
    data object NotFound : ChargeStatusProbe()

    /** No se pudo consultar (server caído, sin red, 5xx del proxy): NO se sabe nada. */
    data object Unreachable : ChargeStatusProbe()
}

/**
 * Desenlace de un cobro con tarjeta. Son TRES, no dos — el tercero es el que faltaba
 * y por eso un fallo de transporte se pintaba como fracaso y el cajero cobraba dos veces.
 */
sealed class CardChargeOutcome {
    /** Consta que la tarjeta SÍ se cobró. Se sigue el flujo normal, sin error a la vista. */
    data class Charged(val paymentId: String?) : CardChargeOutcome()

    /**
     * Consta que NO se cobró: reintentar es seguro.
     *
     * 🔴 Sólo nace de un estado terminal que lo AFIRME (`FAILED`, `CANCELLED`). Nunca de una
     * ausencia (404) ni de una ignorancia (no se pudo preguntar) — ésas son `Undetermined`.
     */
    data class NotCharged(val message: String) : CardChargeOutcome()

    /** No se pudo determinar. Ni éxito ni fracaso: jamás ofrecer un reintento a ciegas. */
    data class Undetermined(val message: String) : CardChargeOutcome()
}

/**
 * Cómo terminó la espera del resultado de la terminal. Tres finales son AMBIGUOS —el server
 * nunca nos dijo el desenlace— y uno es una respuesta real de negocio.
 */
sealed class ChargeWaitEnding {
    /** El server contestó con un código HTTP. */
    data class Http(val code: Int) : ChargeWaitEnding()

    /** Se cayó la red / se cortó el socket antes de saber nada. */
    data object NetworkError : ChargeWaitEnding()

    /**
     * Se venció el plazo máximo que el POS está dispuesto a esperar.
     *
     * Existe porque hay avisos que NUNCA van a llegar: la terminal se queda sin batería,
     * pierde el WiFi, o alguien la apaga a media transacción (medido: cancelar desde una
     * Nexgo no emitía nada y el POS se quedaba en "Procesando pago…" para siempre).
     */
    data object CeilingExceeded : ChargeWaitEnding()
}

/** Qué hacer con UNA lectura del estado: ya alcanza para decidir, o hay que volver a preguntar. */
sealed class ProbeDecision {
    data class Resolved(val outcome: CardChargeOutcome) : ProbeDecision()
    data object KeepPolling : ProbeDecision()
}

/**
 * Lo que el server dijo al RECHAZAR la creación del intento (cuerpo de un 4xx del POST), ya leído.
 *
 * `blockingRequestId` es la solicitud que, según el server, ocupa la terminal. Es la llave de la
 * correlación: si nombra a OTRA, este intento nunca existió; si nombra a ÉSTA, es mi propio
 * intento —de un POST anterior que sí llegó— y hay que preguntar cómo quedó.
 */
data class CreationRejection(
    val code: String?,
    val blockingRequestId: String?,
    val amountCents: Int? = null,
    val ageSeconds: Int? = null,
    val senderDevice: String? = null,
)

/**
 * La decisión del camino del dinero, PURA y sin red: dado lo que el server dice de una
 * solicitud de cobro, ¿cobró, no cobró, o no se sabe?
 *
 * Contexto (incidente del 2026-08-10, Sunmi D3): el backend se reinició a media espera larga,
 * ngrok devolvió 503, la app concluyó "Error en el pago" — y la terminal SÍ había cobrado 8s
 * después. El cajero tocó Reintentar y la tarjeta se cobró dos veces.
 *
 * Regla que codifica esta clase: **un fallo de TRANSPORTE no es un fallo de COBRO.**
 * Sólo el estado durable del server resuelve, y ante la duda se dice que hay duda.
 */
object CardChargeDecision {

    /** El texto que ve el cajero cuando nadie sabe si se cobró. Ni éxito ni fracaso. */
    const val UNDETERMINED_MESSAGE =
        "Estamos confirmando el cobro. No vuelvas a pasar la tarjeta."

    /**
     * Plazo máximo que el POS espera el resultado de la terminal antes de cortar la espera
     * e ir a consultar el estado durable. **Vencerse NO es fracasar**: sólo deja de esperar.
     *
     * 🔴 El número no es libre — tiene que ser MÁS LARGO que la ventana que la terminal tiene
     * a propósito, no más corto:
     *  - el server corta su long-poll a los **300 s** ("La terminal no respondió en 5 minutos");
     *  - este cliente da **310 s** de `readTimeout` porque alguien tiene que llegar físicamente
     *    a pasar la tarjeta y el cliente puede tardar (`.claude/rules/offline-first-y-hub-lan.md`);
     *  - **330 s** deja 20 s de holgura sobre ese 310 s para que la respuesta alcance a viajar.
     *
     * Es un tope de reloj de pared: cubre el caso en que el socket se queda vivo pero mudo
     * (proxy con keep-alive, TCP a medio cerrar) y el `readTimeout` nunca dispara.
     * Acortarlo rompería cobros legítimos, que es peor que el bug que cierra.
     */
    const val WAIT_CEILING_MS = 330_000L

    /** Código con el que el server rechaza CREAR un intento porque la terminal está tomada. */
    const val TERMINAL_BUSY_CODE = "TERMINAL_BUSY"

    /** Frase que hace seguro reintentar: este intento no llegó a la terminal. */
    const val NOT_SENT_NOTICE =
        "Este cobro NO se envió a la terminal, no se cobró nada. Espera a que termine o elige otra terminal."

    /**
     * ¿El server se NEGÓ a crear ESTE intento porque OTRA solicitud ocupa la terminal?
     *
     * Es la única lectura de un 409 que permite concluir sin preguntar: el server sólo lanza
     * `TERMINAL_BUSY` cuando la fila de ESTE `requestId` no existe (si existiera, haría replay
     * de su desenlace, `terminal-payment.service.ts` → `isPrismaUniqueViolation`). Así que nada
     * de este cobro llegó a la terminal y ninguna tarjeta pudo cobrarse con esta llave.
     *
     * Tratarlo como incertidumbre dejaba una llave armada que el GET contestaba con 404 para
     * siempre: la tablet se quedaba en «Cobro sin confirmar» por un cobro que jamás se envió
     * (Testarudo, 2026-09-10: la PAX con el slot tomado bloqueó a la tablet durante horas).
     *
     * 🔴 Lo que lo hace seguro es la CORRELACIÓN, no el código: un `TERMINAL_BUSY` que nombre a
     * ESTA misma solicitud —o que no nombre a nadie (server viejo)— NO alcanza y se conserva la
     * duda. Un cancel tampoco lo cambia: cancelar un intento que nunca se creó no pudo cobrar.
     */
    fun refusedForAnotherRequest(rejection: CreationRejection?, requestId: String): Boolean {
        if (rejection?.code != TERMINAL_BUSY_CODE) return false
        val blocker = rejection.blockingRequestId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return blocker != requestId
    }

    /**
     * Lo que ve el cajero cuando la terminal está tomada: qué la ocupa, cuánto, desde cuándo y
     * desde qué aparato — y, con todas sus letras, que ESTE cobro no se envió. Sin esa frase el
     * cajero no sabe si puede volver a cobrar; con ella, elegir otra terminal es seguro.
     */
    fun busyMessage(rejection: CreationRejection, serverMessage: String?): String {
        val amountCents = rejection.amountCents
        val head = if (amountCents != null) {
            val minutes = (rejection.ageSeconds ?: 0) / 60
            val since = if (minutes < 1) "hace un momento" else "hace $minutes min"
            val device = rejection.senderDevice?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            val amount = java.math.BigDecimal(amountCents).movePointLeft(2).setScale(2).toPlainString()
            "Terminal ocupada: otro cobro de $$amount $since$device."
        } else {
            serverMessage?.takeIf { it.isNotBlank() }?.trimEnd('.')?.plus(".") ?: "Terminal ocupada por otro cobro."
        }
        return "$head $NOT_SENT_NOTICE"
    }

    /** Emitted commands require status recovery after transport, ambiguous HTTP, or cancellation. */
    fun mustReconcile(ending: ChargeWaitEnding, cancelRequested: Boolean): Boolean = when {
        cancelRequested -> true
        else -> when (ending) {
            is ChargeWaitEnding.Http -> isTransportFailure(ending.code) || ending.code in setOf(400, 404, 409, 422)
            ChargeWaitEnding.NetworkError -> true
            ChargeWaitEnding.CeilingExceeded -> true
        }
    }

    /** Transport classification only; other ambiguous HTTP codes also reconcile above. */
    fun isTransportFailure(httpCode: Int): Boolean = httpCode >= 500 || httpCode == 408

    /**
     * Decide con UNA lectura del estado. `isFinalAttempt` marca la última consulta del ciclo:
     * antes de esa, las respuestas ambiguas sólo piden volver a preguntar.
     */
    fun decide(probe: ChargeStatusProbe, isFinalAttempt: Boolean): ProbeDecision = when (probe) {
        // 🔴 Un 404 NO prueba que no se haya cobrado, aunque lo parezca. El server crea la
        // fila ANTES de emitir a la terminal, pero entre que el request llega y la fila se
        // escribe corren `validateStaffVenue` y la query de `order.paymentStatus`: si el
        // socket murió con el request ya enviado, esa ventana supera los ~2.5 s del sondeo
        // en un backend cargado o recién arrancado. Y aquí sólo se llega tras un final de
        // transporte, o sea que YA hay duda. Ante la duda se dice que hay duda.
        is ChargeStatusProbe.NotFound ->
            if (isFinalAttempt) {
                ProbeDecision.Resolved(CardChargeOutcome.Undetermined(UNDETERMINED_MESSAGE))
            } else {
                ProbeDecision.KeepPolling
            }

        // No pudimos preguntar. Se insiste; si se acaba el ciclo así, es indeterminado.
        is ChargeStatusProbe.Unreachable ->
            if (isFinalAttempt) {
                ProbeDecision.Resolved(CardChargeOutcome.Undetermined(UNDETERMINED_MESSAGE))
            } else {
                ProbeDecision.KeepPolling
            }

        is ChargeStatusProbe.Known ->
            if (probe.status == "COMPLETED" && !probe.paymentId.isNullOrBlank()) ProbeDecision.Resolved(CardChargeOutcome.Charged(probe.paymentId))
            else if (probe.cancelDisposition == "ACTIVE") ProbeDecision.Resolved(fromTerminalStatus(probe))
            else if (probe.inProgress) ProbeDecision.KeepPolling
            else ProbeDecision.Resolved(fromTerminalStatus(probe))
    }

    /** Se agotaron las consultas y la solicitud seguía viva: NUNCA "falló" — pudo estar cobrando. */
    fun exhausted(): CardChargeOutcome = CardChargeOutcome.Undetermined(UNDETERMINED_MESSAGE)

    /**
     * El cajero canceló y el desenlace llegó TARDE (la espera dura hasta [WAIT_CEILING_MS]).
     * ¿Qué llave durable queda armada para la próxima venta?
     *
     * 🔴 **"Cancelé" no es "no se cobró".** El cancel es una PETICIÓN: si la tarjeta ya se pasó,
     * la terminal cobra igual y el server reconcilia la fila a COMPLETED. Descartar el resultado
     * obsoleto vale para la NAVEGACIÓN (el cajero ya se fue de esa pantalla), pero jamás para el
     * DINERO: tirar el desenlace entero dejaba la venta pintada como impaga con el cobro ya
     * hecho — y el siguiente "Cobrar" cobraba por segunda vez. Es el incidente del 2026-08-10
     * por otro pasillo.
     *
     * Sólo un [CardChargeOutcome.NotCharged] COMPROBADO cierra el asunto; todo lo demás queda
     * pendiente de avisar y se resuelve en la próxima venta por la ruta "cobro anterior", que
     * informa del cargo viejo SIN marcar como pagada la venta nueva.
     *
     * @param armedKey lo que ya gobierna el disco. Si pertenece a OTRO cobro, ese otro es más
     *   nuevo y sigue vivo: pisarlo con un rezagado perdería la única llave que permite
     *   resolverlo. La ranura es una sola, así que gana el cobro que todavía puede tener dinero
     *   encima.
     */
    fun unresolvedKeyAfterStaleResult(
        outcome: CardChargeOutcome,
        requestId: String?,
        armedKey: String?,
    ): String? {
        // 🔴 Una llave EN BLANCO no es una referencia: no se puede consultar (el GET del estado
        // iría sin id) y dejaría la ranura ocupada para siempre, bloqueando la venta siguiente
        // con una pantalla que nadie puede resolver. Vacío = ranura libre, nunca "otro cobro".
        val mine = requestId?.takeIf { it.isNotBlank() }
        val armed = armedKey?.takeIf { it.isNotBlank() }
        if (armed != null && armed != mine) return armed
        return when (outcome) {
            is CardChargeOutcome.NotCharged -> null
            is CardChargeOutcome.Charged, is CardChargeOutcome.Undetermined -> mine
        }
    }

    private fun fromTerminalStatus(probe: ChargeStatusProbe.Known): CardChargeOutcome =
        when {
            probe.cancelDisposition == "ACTIVE" -> CardChargeOutcome.Undetermined("El cobro sigue activo. Confirma en la terminal. No vuelvas a pasar la tarjeta.")
            probe.status == "COMPLETED" -> CardChargeOutcome.Undetermined(UNDETERMINED_MESSAGE)
            probe.status == "FAILED" -> CardChargeOutcome.NotCharged("El cobro fue rechazado. No se cobró la tarjeta.")
            probe.status == "CANCELLED" && probe.cancelDisposition == "ACCEPTED" -> CardChargeOutcome.NotCharged("El cobro se canceló. No se cobró la tarjeta.")
            // TIMED_OUT, UNKNOWN — y cualquier estado que este cliente no conozca todavía.
            // Adivinar aquí es exactamente el bug: se dice que no se sabe.
            else -> CardChargeOutcome.Undetermined(UNDETERMINED_MESSAGE)
        }
}
