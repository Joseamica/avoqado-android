package com.avoqado.pos.transactions.data.model

import com.avoqado.pos.pos.data.model.OpcionesDeVarianteTolerantes
import com.avoqado.pos.pos.data.model.VariantOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder

/** Additive wire fields; absence is unknown, never permission to exchange. */
@Serializable
data class ExchangeDetailFields(
    val exchangeable: Boolean? = null,
    val notExchangeableReason: String? = null,
    val tenderSystemKind: String? = null,
    val chainRefundMethod: String? = null,
    val chainRefundLabel: String? = null,
    val chainRefundBlockedCode: String? = null,
    val chainRefundBlockedReason: String? = null,
    @Serializable(with = PayoutOptionsTolerante::class)
    val payoutOptions: List<PayoutOption>? = null,
    @Serializable(with = ExchangeLinksTolerante::class)
    val exchange: ExchangeLinks? = null,
)

@Serializable
data class ExchangeItemFields(
    val productId: String? = null,
    val chargedTotalCents: Long? = null,
    val quantity: Int? = null,
    val returnedQtyOrder: Int? = null,
    val returnedCentsOrder: Long? = null,
    val remainingQtyOrder: Int? = null,
    val productGroupId: String? = null,
    @Serializable(with = OpcionesDeVarianteTolerantes::class)
    val variantOptions: List<VariantOption>? = null,
    val productType: String? = null,
    val isPromotionLine: Boolean? = null,
    val orderPromotionId: String? = null,
    val notExchangeableReason: String? = null,
    val restockable: Boolean? = null,
    val currentListPriceCents: Long? = null,
)

@Serializable
data class PayoutOption(
    val method: String? = null,
    val maxCents: Long? = null,
    val maxSameCents: Long? = null,
    val needsRefundToCash: Boolean? = null,
)

@Serializable
data class ExchangeLinks(
    val asOriginalTotal: Int? = null,
    val asOriginal: List<ExchangeAsOriginal>? = null,
    val asNew: ExchangeAsNew? = null,
)

@Serializable
data class ExchangeAsOriginal(
    val exchangeId: String? = null,
    val newOrderId: String? = null,
    val newOrderNumber: String? = null,
    val status: String? = null,
    val reviewedAt: String? = null,
)

@Serializable
data class ExchangeAsNew(
    val exchangeId: String? = null,
    val originalOrderId: String? = null,
    val originalOrderNumber: String? = null,
    val status: String? = null,
    val reviewedAt: String? = null,
)

/** A bad optional block must not prevent viewing or refunding the original sale. */
internal object ExchangeLinksTolerante : KSerializer<ExchangeLinks?> {
    private val strict = ExchangeLinks.serializer()
    override val descriptor: SerialDescriptor = strict.nullable.descriptor

    override fun deserialize(decoder: Decoder): ExchangeLinks? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(strict.nullable)
        val element = json.decodeJsonElement()
        return try {
            json.json.decodeFromJsonElement(strict, element).takeIf { links ->
                links.asOriginalTotal != null && links.asOriginalTotal >= 0 && links.asOriginal != null &&
                    links.asOriginal.all { it.exchangeId != null && it.newOrderId != null && it.newOrderNumber != null && it.status != null } &&
                    (links.asNew == null || links.asNew.let {
                        it.exchangeId != null && it.originalOrderId != null && it.originalOrderNumber != null && it.status != null
                    })
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: ExchangeLinks?) = encoder.encodeSerializableValue(strict.nullable, value)
}

internal object PayoutOptionsTolerante : KSerializer<List<PayoutOption>?> {
    private val strict = ListSerializer(PayoutOption.serializer())
    override val descriptor: SerialDescriptor = strict.nullable.descriptor

    override fun deserialize(decoder: Decoder): List<PayoutOption>? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(strict.nullable)
        val element = json.decodeJsonElement()
        return try {
            json.json.decodeFromJsonElement(strict, element).takeIf { options ->
                options.all {
                    it.method != null &&
                        it.maxCents != null && it.maxCents >= 0 && it.maxSameCents != null && it.maxSameCents >= 0 &&
                        it.needsRefundToCash != null
                }
            }?.filter { it.method in setOf("CASH", "BANK_TRANSFER", "GIFT_CARD_BALANCE") }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: List<PayoutOption>?) = encoder.encodeSerializableValue(strict.nullable, value)
}
