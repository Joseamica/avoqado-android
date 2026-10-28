package com.avoqado.pos.giftcards.data

import com.avoqado.pos.pos.data.model.CambioDePrecio
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

sealed interface PruebaDeTarjeta {
    data class Codigo(val code: String) : PruebaDeTarjeta { override fun toString() = "Codigo(****)" }
    data class PageToken(val pageToken: String) : PruebaDeTarjeta { override fun toString() = "PageToken(****)" }
    data class Telefono(val proofToken: String, val giftCardId: String) : PruebaDeTarjeta { override fun toString() = "Telefono(****)" }
}
fun PruebaDeTarjeta.aJson(): JsonObject = buildJsonObject {
    when (val prueba = this@aJson) {
        is PruebaDeTarjeta.Codigo -> { put("kind", "CODE"); put("code", prueba.code) }
        is PruebaDeTarjeta.PageToken -> { put("kind", "PAGE_TOKEN"); put("pageToken", prueba.pageToken) }
        is PruebaDeTarjeta.Telefono -> { put("kind", "PHONE_OTP"); put("proofToken", prueba.proofToken); put("giftCardId", prueba.giftCardId) }
    }
}

@Serializable data class VentaPermitida(val sellable: Boolean, val code: String? = null, val message: String? = null, val howToEnable: String? = null, val operationLimit: Double? = null)
@Serializable data class AjustesDeTarjetas(
    val enabled: Boolean, val suggestedAmounts: List<Double>, val minAmount: Double, val maxAmount: Double,
    val validityMonths: Int? = null, val termsVersion: String? = null, val topePorOperacion: Double? = null,
    val llavesConfiguradas: Boolean = false, val sale: VentaPermitida,
    val capacidades: Set<String> = emptySet(), val tieneTarjetas: Boolean = false,
) {
    val vende get() = "ENTREGA" in capacidades
    val porLiga get() = "PAGE_TOKEN" in capacidades
    val porTelefono get() = "TELEFONO" in capacidades
    val filaDeCobro get() = enabled || tieneTarjetas
}
sealed interface EstadoDeAjustes {
    data object Desconocido : EstadoDeAjustes
    data object NoDisponible : EstadoDeAjustes
    data object SinPermiso : EstadoDeAjustes
    data class Disponible(val ajustes: AjustesDeTarjetas) : EstadoDeAjustes
}
@Serializable data class SobreVendido(
    val id: String, val codigo: String? = null, val ultimos4: String, val monto: Double,
    val pageToken: String? = null, val pageUrl: String? = null, val codigoOculto: Boolean = false,
    val deliveryChannel: String, val venceEnMeses: Int? = null, val termsVersion: String? = null, val status: String? = null,
) { override fun toString() = "SobreVendido(****)" }
@Serializable data class VentaDeTarjetas(val orderId: String, val orderNumber: String, val total: Double, val replay: Boolean = false, val status: String? = null, val sobres: List<SobreVendido>)
@Serializable data class TarjetaConsultada(val giftCardId: String, val last4: String, val balance: Double, val status: String, val expiresAt: String? = null, val usable: Boolean, val notUsableReason: String? = null)
data class TarjetaIdentificada(val prueba: PruebaDeTarjeta, val tarjeta: TarjetaConsultada) {
    override fun toString() = "TarjetaIdentificada(****)"
}
@Serializable data class ConsultaDeSaldo(val usableBalance: Double, val cards: List<TarjetaConsultada>)
@Serializable data class Redimido(val giftCardId: String, val last4: String, val amount: Double, val balanceAfter: Double)
data class CobroConSaldo(
    val paymentId: String, val orderId: String, val orderNumber: String, val amount: Double, val tipAmount: Double,
    val redeemed: List<Redimido>, val orderRemaining: Double, val orderPaymentStatus: String,
    val receiptAccessKey: String?, val receiptUrl: String?, val replay: Boolean = false,
    val priceChanges: List<CambioDePrecio>? = null,
)
@Serializable data class Cancelacion(val allowed: Boolean, val reason: String? = null, val message: String? = null)
@Serializable data class SobreDeLaCuenta(
    val id: String, val ultimos4: String, val monto: Double, val saldo: Double, val status: String, val venceEl: String? = null,
    val deliveryChannel: String, val deliveryStatus: String? = null, val deliveredAt: String? = null,
    val deliveryErrorCode: String? = null, val deliveryError: String? = null, val destino: String? = null, val recipientName: String? = null,
    val usada: Boolean, val puedeReenviar: Boolean, val puedeCambiarDestino: Boolean, val puedeRevelar: Boolean,
    val cambiarDestinoPideGerente: Boolean = true, val revelarPideGerente: Boolean = true,
)
@Serializable data class CuentaDeTarjetas(val orderId: String, val orderNumber: String, val total: Double, val paymentStatus: String, val orderStatus: String, val purchaserName: String? = null, val sobres: List<SobreDeLaCuenta>, val cancel: Cancelacion)
@Serializable data class CodigoRevelado(
    val giftCardId: String? = null, val codigo: String, val pageToken: String, val pageUrl: String? = null,
    val ultimos4: String, val monto: Double, val saldo: Double, val venceEl: String? = null,
    val termsVersion: String? = null, val recipientName: String? = null, val purchaserName: String? = null, val message: String? = null,
) { override fun toString() = "CodigoRevelado(****)" }
@Serializable data class VerificacionPorTelefono(val proofToken: String, val expiresAt: String, val usableBalance: Double, val cards: List<TarjetaConsultada>, val hayMas: Boolean = false) {
    override fun toString() = "VerificacionPorTelefono(****)"
}
@Serializable data class ResultadoDeReenvio(val giftCardId: String, val deliveryStatus: String, val repuesta: Boolean, val ultimos4: String)
data class FalloDeTarjeta(val http: Int, val code: String?, val message: String?, val retryAfterSeg: Int?, val saldoDisponible: Double?)
sealed interface Respuesta<out T> {
    data class Ok<T>(val valor: T) : Respuesta<T>
    data class Rechazo(val fallo: FalloDeTarjeta) : Respuesta<Nothing>
    data object SinRespuesta : Respuesta<Nothing>
}
