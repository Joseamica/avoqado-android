package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Mirrors backend `DepositStatus` (avoqado-server `prisma/schema.prisma`):
 * PENDING, CARD_HOLD, PAID, REFUNDED, FORFEITED, EXPIRED, DISPUTED.
 *
 * Serialized with a custom [KSerializer] instead of the default enum handling
 * so that any value the backend sends which this client doesn't (yet) know
 * about decodes to [UNKNOWN] instead of throwing — matching iOS's `.unknown`
 * fallback. This makes the client future-proof against new backend statuses
 * without another crash-fix cycle.
 */
@Serializable(with = DepositStatusSerializer::class)
enum class DepositStatus {
    PENDING,
    CARD_HOLD,
    PAID,
    REFUNDED,
    FORFEITED,
    EXPIRED,
    DISPUTED,
    UNKNOWN,
}

object DepositStatusSerializer : KSerializer<DepositStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DepositStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DepositStatus) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): DepositStatus =
        runCatching { DepositStatus.valueOf(decoder.decodeString()) }
            .getOrDefault(DepositStatus.UNKNOWN)
}

/** Spanish labels for staff-facing UI. */
val DepositStatus.displayLabel: String
    get() = when (this) {
        DepositStatus.PENDING -> "Pendiente"
        DepositStatus.CARD_HOLD -> "Retención en tarjeta"
        DepositStatus.PAID -> "Pagado"
        DepositStatus.REFUNDED -> "Reembolsado"
        DepositStatus.FORFEITED -> "Retenido"
        DepositStatus.EXPIRED -> "Expirado"
        DepositStatus.DISPUTED -> "En disputa"
        DepositStatus.UNKNOWN -> "Desconocido"
    }
