package com.avoqado.pos.giftcards.data

import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.pos.data.model.CambioDePrecio
import kotlinx.serialization.json.*

/** Contrato 1b: los lectores reciben data; sólo clasificar recibe el sobre HTTP completo. */
object GiftCardsApi {
    internal val json = Json { ignoreUnknownKeys = true }
    fun parsearAjustes(body: String): AjustesDeTarjetas = json.decodeFromString(body)
    fun parsearVenta(body: String): VentaDeTarjetas = json.decodeFromString(body)
    fun parsearConsulta(body: String): ConsultaDeSaldo = json.decodeFromString(body)
    fun parsearCuenta(body: String): CuentaDeTarjetas = json.decodeFromString(body)
    fun parsearTelefono(body: String): VerificacionPorTelefono = json.decodeFromString(body)
    fun parsearReenvio(body: String): ResultadoDeReenvio = json.decodeFromString(body)
    fun parsearRevelado(body: String): CodigoRevelado {
        val root = json.parseToJsonElement(body).jsonObject
        // La revelación por cuenta llama id al mismo identificador; la individual no lo devuelve.
        val normalized = root["id"]?.let { JsonObject(root + ("giftCardId" to it)) } ?: root
        return json.decodeFromJsonElement(normalized)
    }
    fun parsearCobro(body: String): CobroConSaldo {
        val root = json.parseToJsonElement(body).jsonObject
        val receipt = root["digitalReceipt"] as? JsonObject
        return CobroConSaldo(
            paymentId = root.getValue("paymentId").jsonPrimitive.content,
            orderId = root.getValue("orderId").jsonPrimitive.content,
            orderNumber = root.getValue("orderNumber").jsonPrimitive.content,
            amount = root.getValue("amount").jsonPrimitive.double,
            tipAmount = root.getValue("tipAmount").jsonPrimitive.double,
            redeemed = json.decodeFromJsonElement<List<Redimido>>(root.getValue("redeemed")),
            orderRemaining = root.getValue("orderRemaining").jsonPrimitive.double,
            orderPaymentStatus = root.getValue("orderPaymentStatus").jsonPrimitive.content,
            receiptAccessKey = receipt?.texto("accessKey"), receiptUrl = receipt?.texto("receiptUrl"),
            replay = (root["replay"] as? JsonPrimitive)?.booleanOrNull ?: false,
            priceChanges = root["priceChanges"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<List<CambioDePrecio>>(it) },
        )
    }
    fun parsearRecuperacion(body: String): CobroConSaldo? {
        val root = json.parseToJsonElement(body).jsonObject
        return if (root.getValue("found").jsonPrimitive.boolean) parsearCobro(root.getValue("payment").toString()) else null
    }
    fun cuerpoDeConsulta(prueba: PruebaDeTarjeta): String = buildJsonObject {
        when (prueba) {
            is PruebaDeTarjeta.Codigo -> put("code", prueba.code)
            is PruebaDeTarjeta.PageToken -> put("pageToken", prueba.pageToken)
            is PruebaDeTarjeta.Telefono -> error("La consulta por teléfono usa phone/verify")
        }
    }.toString()
    fun leerFallo(http: Int, body: String): FalloDeTarjeta {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val details = root?.get("details") as? JsonObject
        return FalloDeTarjeta(http, root?.texto("code"), root?.texto("message"),
            (root?.get("retryAfter") as? JsonPrimitive)?.intOrNull ?: (details?.get("retryAfter") as? JsonPrimitive)?.intOrNull,
            (root?.get("saldoDisponible") as? JsonPrimitive)?.doubleOrNull ?: (details?.get("saldoDisponible") as? JsonPrimitive)?.doubleOrNull)
    }
    fun clasificar(http: Int, contentType: String?, ngrokError: String?, body: String): Respuesta<JsonObject> {
        if (http >= 500 || http == 0) return Respuesta.SinRespuesta
        val fallo = leerFallo(http, body)
        val propio429 = http == 429 && fallo.code != null && ngrokError.isNullOrBlank() && contentType?.contains("text/html", ignoreCase = true) != true
        if (OrderRepository.isTransient4xx(http, contentType, ngrokError, body) && !propio429) return Respuesta.SinRespuesta
        if (http !in 200..299) return Respuesta.Rechazo(fallo)
        return runCatching<Respuesta<JsonObject>> {
            val root = json.parseToJsonElement(body).jsonObject
            check(root.getValue("success").jsonPrimitive.boolean)
            Respuesta.Ok(root.getValue("data").jsonObject)
        }.getOrDefault(Respuesta.SinRespuesta)
    }
    private fun JsonObject.texto(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
