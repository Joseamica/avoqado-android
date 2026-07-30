package com.avoqado.pos.pos.data.model

/**
 * Lectura neutral que debe producir cualquier adaptador físico de báscula.
 *
 * El transporte (USB serial, bridge de escritorio, etc.) es responsable de convertir su trama
 * propietaria a este contrato. El POS sólo acepta el peso neto cuando la lectura es estable,
 * reciente y está dentro del rango soportado por [parseWeightKg].
 */
data class NormalizedScaleReading(
    val deviceId: String,
    val grossKg: String? = null,
    val tareKg: String? = null,
    val netKg: String,
    val stable: Boolean,
    val observedAtEpochMillis: Long,
)

enum class ScaleReadingRejection {
    UNSTABLE,
    STALE,
    INVALID_WEIGHT,
}

data class ScaleReadingValidation(
    val weightKg: Double? = null,
    val rejection: ScaleReadingRejection? = null,
) {
    val accepted: Boolean
        get() = weightKg != null && rejection == null
}

const val DEFAULT_SCALE_READING_MAX_AGE_MILLIS = 2_000L

/**
 * Aplica las reglas comunes a una lectura automática. Una lectura futura también se considera
 * inválida para impedir que un reloj o simulador defectuoso reutilice datos fuera de secuencia.
 */
fun validateScaleReading(
    reading: NormalizedScaleReading,
    nowEpochMillis: Long = System.currentTimeMillis(),
    maxAgeMillis: Long = DEFAULT_SCALE_READING_MAX_AGE_MILLIS,
): ScaleReadingValidation {
    if (!reading.stable) {
        return ScaleReadingValidation(rejection = ScaleReadingRejection.UNSTABLE)
    }

    val ageMillis = nowEpochMillis - reading.observedAtEpochMillis
    if (ageMillis < 0 || ageMillis > maxAgeMillis) {
        return ScaleReadingValidation(rejection = ScaleReadingRejection.STALE)
    }

    val weightKg = parseWeightKg(reading.netKg)
        ?: return ScaleReadingValidation(rejection = ScaleReadingRejection.INVALID_WEIGHT)

    return ScaleReadingValidation(weightKg = weightKg)
}
