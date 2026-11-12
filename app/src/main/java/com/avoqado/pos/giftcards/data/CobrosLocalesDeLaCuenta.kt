package com.avoqado.pos.giftcards.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.payment.domain.PendientesDeTarjeta
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class CobrosLocalesDeLaCuenta @Inject constructor(
    private val pendingPaymentDao: PendingPaymentDao,
    private val secureStorage: SecureStorage,
    private val store: SolicitudesDeTarjetaStore,
    private val outbox: com.avoqado.pos.core.data.sync.SyncOutbox? = null,
) {
    /**
     * 🔴 D13: la foto de la puerta del efectivo para «Pedidos → detalle → Cobrar», tomada ANTES de leer la cuenta del SERVIDOR: lo que ya
     * consta que el servidor cuenta (efectivo ya subido y marcas que se soltaron). Lo que se confirme después es nuevo para la puerta.
     */
    suspend fun dineroConocido(venueId: String, orderId: String): com.avoqado.pos.payment.domain.DineroConocido {
        val soltadasHasta = com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta.secuencia()
        return com.avoqado.pos.payment.domain.DineroConocido.antesDeLeerElSaldo(
            pendingPaymentDao.forVenue(venueId).filter { it.orderId == orderId }.map { com.avoqado.pos.payment.data.dineroDeLaFila(it) }, soltadasHasta)
    }

    /**
     * [enLinea] (D12 · Opción D, I4): para quien cobra con internet, una fila FAILED ya la contestó el SERVIDOR (su saldo manda): no es
     * dinero por subir. Sin red cuenta como siempre.
     * [incluirColaDeMesas] = `false` SÓLO en el saldo sin red de una mesa del servidor (D5 · B2b), que ya cuenta su propio efectivo de
     * la cola de mesas. Quien decide si se OFRECE el saldo de regalo (`conductaDeSaldo`) conserva el default (acuerdo (b)).
     */
    suspend fun hayPendiente(venueId: String, orderId: String, enLinea: Boolean = false, incluirColaDeMesas: Boolean = true): Boolean {
        if (pendingPaymentDao.forVenue(venueId).any {
                it.orderId == orderId && it.syncStatus != "SYNCED" && !(enLinea && it.syncStatus == "FAILED")
            }) return true
        if (PendientesDeTarjeta.leer(secureStorage.pendingCardChargesJson).any {
            it.orderId == orderId && runCatching {
                Json.parseToJsonElement(it.contexto).jsonObject["venueId"]?.jsonPrimitive?.contentOrNull == venueId
            }.getOrDefault(false)
        }) return true
        if (store.delNegocio(venueId).any {
            it.tipo == "GIFT_CARD_REDEEM" && it.orderId == orderId && it.mandado && !it.porTerminar && !it.porRecuperar
        }) return true
        if (!incluirColaDeMesas) return false
        return try {
            outbox?.trabajoSinResolverDeLaOrden(venueId, setOf(orderId))?.tipos?.contains("PAY_CASH") == true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { true }
    }
}
