package com.avoqado.pos.pos.data.model

import kotlin.math.abs

/**
 * Protocolos que Avoqado conoce de forma explícita.
 *
 * Un nombre de marca no es un protocolo. Rhino se habilita únicamente para el BAR-8RS investigado;
 * Kretz Report sigue usando etiquetas impresas mientras no exista acceso al protocolo propietario.
 */
enum class ScaleProtocol(
    val profileType: String,
    val pollCommand: ByteArray? = null,
    val defaultBaudRate: Int,
    val pollIntervalMillis: Long = 250L,
    val requiresSyntheticStability: Boolean = false,
) {
    JUSTA_LP7516_ASCII(
        profileType = "JUSTA_LP7516_ASCII",
        pollCommand = byteArrayOf('R'.code.toByte()),
        defaultBaudRate = 9_600,
    ),
    RHINO_BAR8RS_ASCII(
        profileType = "RHINO_BAR8RS_ASCII",
        pollCommand = byteArrayOf('P'.code.toByte()),
        defaultBaudRate = 9_600,
        pollIntervalMillis = 500L,
        requiresSyntheticStability = true,
    ),
    TORREY_PCR_ASCII(
        profileType = "TORREY_PCR_ASCII",
        pollCommand = byteArrayOf('P'.code.toByte()),
        defaultBaudRate = 115_200,
        requiresSyntheticStability = true,
    ),
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
    ScaleProtocol.RHINO_BAR8RS_ASCII ->
        decodeSimplePolledWeightFrame(deviceId, rawFrame, observedAtEpochMillis)
    ScaleProtocol.TORREY_PCR_ASCII ->
        decodeSimplePolledWeightFrame(deviceId, rawFrame, observedAtEpochMillis)
}

/**
 * LP7516, modo PC continuo o respuesta al comando R:
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
 * Rhino BAR-8RS y familia Torrey PCR: el host envía `P` y la báscula responde peso + unidad.
 * Ninguno de los protocolos documentados publica un bit de estabilidad, así que cada trama nace
 * inestable y
 * [ScaleStabilityTracker] exige lecturas consecutivas iguales antes de habilitar el peso.
 */
private fun decodeSimplePolledWeightFrame(
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

    val match = SIMPLE_WEIGHT_PATTERN.find(frame)
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

private val SIMPLE_WEIGHT_PATTERN = Regex(
    pattern = """([+-]?\s*[0-9]+(?:[.,][0-9]+)?)\s*(kg|lb|oz)""",
    option = RegexOption.IGNORE_CASE,
)

/**
 * Arma tramas a partir de paquetes USB arbitrarios.
 *
 * USB serial no conserva fronteras de mensaje: una respuesta puede llegar partida en varios
 * callbacks o varias respuestas pueden llegar juntas. Rhino BAR-8RS tampoco tiene documentación
 * pública que garantice CR/LF. Por eso se extraen primero tramas completas conocidas y se conserva
 * cualquier sufijo parcial para el siguiente paquete.
 */
class ScaleFrameAssembler(
    private val maxBufferLength: Int = 512,
) {
    private val buffer = StringBuilder()

    init {
        require(maxBufferLength >= 64)
    }

    fun append(
        protocol: ScaleProtocol,
        incoming: String,
    ): List<String> {
        if (incoming.isEmpty()) return emptyList()
        buffer.append(incoming)
        val frames = mutableListOf<String>()
        val completePattern = protocol.completeFramePattern()

        while (buffer.isNotEmpty()) {
            val separator = buffer.indexOfAny(charArrayOf('\r', '\n'))
            val match = completePattern.find(buffer)
            when {
                match != null && (separator < 0 || match.range.last < separator) -> {
                    frames += match.value.trim()
                    buffer.delete(0, match.range.last + 1)
                }
                separator >= 0 -> {
                    val line = buffer.substring(0, separator).trim()
                    buffer.delete(0, separator + 1)
                    if (line.isNotEmpty()) frames += line
                }
                else -> break
            }
        }

        if (buffer.length > maxBufferLength) {
            buffer.delete(0, buffer.length - maxBufferLength / 2)
        }
        return frames
    }

    fun clear() {
        buffer.clear()
    }
}

private fun ScaleProtocol.completeFramePattern(): Regex = when (this) {
    ScaleProtocol.JUSTA_LP7516_ASCII -> LP7516_EXTRACT_PATTERN
    ScaleProtocol.RHINO_BAR8RS_ASCII,
    ScaleProtocol.TORREY_PCR_ASCII,
    -> SIMPLE_WEIGHT_PATTERN
}

private val LP7516_EXTRACT_PATTERN = Regex(
    pattern = """(?:ST|US|OL)\s*,?\s*(?:GS|NT)\s*,?\s*[+-]\s*[0-9]+(?:[.,][0-9]+)?\s*(?:kg|lb)""",
    option = RegexOption.IGNORE_CASE,
)
