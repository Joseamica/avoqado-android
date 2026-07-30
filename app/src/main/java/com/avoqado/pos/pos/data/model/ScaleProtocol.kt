package com.avoqado.pos.pos.data.model

import kotlin.math.abs

/**
 * Protocolos que Avoqado conoce de forma explícita.
 *
 * Un nombre de marca no es un protocolo. Por eso Rhino permanece pendiente hasta conocer el
 * modelo y Kretz Report usa etiquetas impresas mientras no exista acceso al protocolo propietario.
 */
enum class ScaleProtocol(
    val profileType: String,
    val pollCommand: ByteArray? = null,
) {
    JUSTA_LP7516_ASCII("JUSTA_LP7516_ASCII"),
    TORREY_PCR_ASCII("TORREY_PCR_ASCII", byteArrayOf('P'.code.toByte())),
    ;

    companion object {
        fun fromProfileType(raw: String?): ScaleProtocol? =
            entries.firstOrNull { it.profileType == raw?.trim()?.uppercase() }
    }
}

enum class ScaleFrameRejection {
    EMPTY,
    MALFORMED,
    OVERLOAD,
    NEGATIVE_WEIGHT,
    UNSUPPORTED_UNIT,
}

data class ScaleFrameResult(
    val reading: NormalizedScaleReading? = null,
    val rejection: ScaleFrameRejection? = null,
) {
    val accepted: Boolean
        get() = reading != null && rejection == null
}

fun decodeScaleFrame(
    protocol: ScaleProtocol,
    deviceId: String,
    rawFrame: String,
    observedAtEpochMillis: Long = System.currentTimeMillis(),
): ScaleFrameResult = when (protocol) {
    ScaleProtocol.JUSTA_LP7516_ASCII ->
        decodeLp7516Frame(deviceId, rawFrame, observedAtEpochMillis)
    ScaleProtocol.TORREY_PCR_ASCII ->
        decodeTorreyPcrFrame(deviceId, rawFrame, observedAtEpochMillis)
}

/**
 * LP7516, modo PC continuo o comando R:
 * `ST,GS,+  0.435kg\r\n`
 *
 * ST/US/OL significan estable, inestable y sobrecarga. GS/NT indican peso bruto o neto.
 */
private fun decodeLp7516Frame(
    deviceId: String,
    rawFrame: String,
    observedAtEpochMillis: Long,
): ScaleFrameResult {
    val frame = rawFrame.trim()
    if (frame.isEmpty()) return ScaleFrameResult(rejection = ScaleFrameRejection.EMPTY)

    val match = LP7516_PATTERN.matchEntire(frame)
        ?: return ScaleFrameResult(rejection = ScaleFrameRejection.MALFORMED)
    val status = match.groupValues[1].uppercase()
    val mode = match.groupValues[2].uppercase()
    val sign = match.groupValues[3]
    val rawWeight = match.groupValues[4].replace(',', '.')
    val unit = match.groupValues[5].lowercase()

    if (status == "OL") return ScaleFrameResult(rejection = ScaleFrameRejection.OVERLOAD)
    if (unit != "kg") return ScaleFrameResult(rejection = ScaleFrameRejection.UNSUPPORTED_UNIT)
    if (sign == "-") return ScaleFrameResult(rejection = ScaleFrameRejection.NEGATIVE_WEIGHT)

    val kg = parseWeightKg(rawWeight)
        ?: return ScaleFrameResult(rejection = ScaleFrameRejection.MALFORMED)
    val formatted = formatWeightKg(kg)
    return ScaleFrameResult(
        reading = NormalizedScaleReading(
            deviceId = deviceId,
            grossKg = formatted.takeIf { mode == "GS" },
            netKg = formatted,
            stable = status == "ST",
            observedAtEpochMillis = observedAtEpochMillis,
        ),
    )
}

/**
 * Familia Torrey PCR: el host envía `P` y la báscula responde una trama ASCII terminada en CR.
 * El manual no publica un bit de estabilidad, así que cada trama nace inestable y
 * [ScaleStabilityTracker] exige lecturas consecutivas iguales antes de habilitar el peso.
 */
private fun decodeTorreyPcrFrame(
    deviceId: String,
    rawFrame: String,
    observedAtEpochMillis: Long,
): ScaleFrameResult {
    val frame = rawFrame.trim()
    if (frame.isEmpty()) return ScaleFrameResult(rejection = ScaleFrameRejection.EMPTY)
    val uppercase = frame.uppercase()
    if ("OVERLOAD" in uppercase || "OUT OF RANGE" in uppercase || "RANGE" == uppercase) {
        return ScaleFrameResult(rejection = ScaleFrameRejection.OVERLOAD)
    }

    val match = TORREY_PCR_PATTERN.find(frame)
        ?: return ScaleFrameResult(rejection = ScaleFrameRejection.MALFORMED)
    val rawWeight = match.groupValues[1].replace(" ", "").replace(',', '.')
    val unit = match.groupValues[2].lowercase()
    if (unit != "kg") return ScaleFrameResult(rejection = ScaleFrameRejection.UNSUPPORTED_UNIT)
    if (rawWeight.startsWith("-") || "NEG" in uppercase) {
        return ScaleFrameResult(rejection = ScaleFrameRejection.NEGATIVE_WEIGHT)
    }
    val kg = parseWeightKg(rawWeight)
        ?: return ScaleFrameResult(rejection = ScaleFrameRejection.MALFORMED)
    val formatted = formatWeightKg(kg)
    return ScaleFrameResult(
        reading = NormalizedScaleReading(
            deviceId = deviceId,
            grossKg = formatted,
            netKg = formatted,
            stable = false,
            observedAtEpochMillis = observedAtEpochMillis,
        ),
    )
}

/**
 * Para protocolos sin indicador de estabilidad. Marca estable sólo después de recibir el mismo
 * peso [requiredMatches] veces dentro de [maxGapMillis].
 */
class ScaleStabilityTracker(
    private val requiredMatches: Int = 2,
    private val toleranceKg: Double = 0.001,
    private val maxGapMillis: Long = 1_000L,
) {
    private var previousWeightKg: Double? = null
    private var previousObservedAt: Long? = null
    private var matches: Int = 0

    init {
        require(requiredMatches >= 2)
        require(toleranceKg >= 0)
        require(maxGapMillis > 0)
    }

    fun observe(reading: NormalizedScaleReading): NormalizedScaleReading {
        if (reading.stable) {
            reset()
            return reading
        }
        val weightKg = parseWeightKg(reading.netKg)
        val previousWeight = previousWeightKg
        val previousAt = previousObservedAt
        val consecutive = weightKg != null &&
            previousWeight != null &&
            previousAt != null &&
            reading.observedAtEpochMillis >= previousAt &&
            reading.observedAtEpochMillis - previousAt <= maxGapMillis &&
            abs(weightKg - previousWeight) <= toleranceKg
        matches = if (consecutive) matches + 1 else 1
        previousWeightKg = weightKg
        previousObservedAt = reading.observedAtEpochMillis
        return reading.copy(stable = weightKg != null && matches >= requiredMatches)
    }

    fun reset() {
        previousWeightKg = null
        previousObservedAt = null
        matches = 0
    }
}

private val LP7516_PATTERN = Regex(
    pattern = """^(ST|US|OL)\s*,?\s*(GS|NT)\s*,?\s*([+-])\s*([0-9]+(?:[.,][0-9]+)?)\s*(kg|lb)$""",
    option = RegexOption.IGNORE_CASE,
)

private val TORREY_PCR_PATTERN = Regex(
    pattern = """([+-]?\s*[0-9]+(?:[.,][0-9]+)?)\s*(kg|lb|oz)""",
    option = RegexOption.IGNORE_CASE,
)
