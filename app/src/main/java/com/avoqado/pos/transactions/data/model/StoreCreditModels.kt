package com.avoqado.pos.transactions.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull

// Saldo a favor (spec 2026-10-30). Todo es aditivo y opcional: un servidor viejo no manda nada de esto y la app se comporta como hoy.

/**
 * Lo que se le abonó a la clienta. Mismo bloque en la respuesta del Reembolso (`data.storeCredit`), la del Cambio
 * (`settlement.storeCredit`) y, con menos campos, en el detalle de una devolución (`storeCredit`: sólo [customerName] y
 * [amountCents], para la reimpresión). Dinero en CENTAVOS, como el resto del carril de reembolso.
 */
@Serializable
data class StoreCreditAbono(
    val customerId: String? = null,
    val customerName: String? = null,
    val amountCents: Long? = null,
    val newBalanceCents: Long? = null,
    /** Los últimos 4 del teléfono con que se gasta el saldo (el del sobre, no el de la ficha). */
    val phoneLast4: String? = null,
    /** El teléfono de la ficha no es el del sobre: la cajera lo avisa. */
    val phoneMismatch: Boolean? = null,
)

/** `storeCreditOption` del detalle de una venta: ¿puede la cajera escoger «Saldo a favor»? Si no, se pinta en gris con [reason]. */
@Serializable
data class StoreCreditOption(
    val available: Boolean? = null,
    val reason: String? = null,
)

/** `storeCredit` del cliente en la lista de clientes: saldo en CENTAVOS; [blocked] = sólo hay sobres congelados (saldo 0). */
@Serializable
data class CustomerStoreCredit(
    val balanceCents: Long? = null,
    val phoneLast4: String? = null,
    val giftCardId: String? = null,
    val blocked: Boolean? = null,
)

/** Un bloque opcional ilegible vale `null`: nunca tumba la venta, el cliente ni el resultado del Cambio que lo trae. */
internal open class OpcionalTolerante<T : Any>(private val estricto: KSerializer<T>) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = estricto.nullable.descriptor

    override fun deserialize(decoder: Decoder): T? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(estricto.nullable)
        val element = json.decodeJsonElement()
        if (element is JsonNull) return null
        return try {
            json.json.decodeFromJsonElement(estricto, element)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: T?) = encoder.encodeSerializableValue(estricto.nullable, value)
}

internal object StoreCreditAbonoTolerante : OpcionalTolerante<StoreCreditAbono>(StoreCreditAbono.serializer())
internal object StoreCreditOptionTolerante : OpcionalTolerante<StoreCreditOption>(StoreCreditOption.serializer())
internal object CustomerStoreCreditTolerante : OpcionalTolerante<CustomerStoreCredit>(CustomerStoreCredit.serializer())
