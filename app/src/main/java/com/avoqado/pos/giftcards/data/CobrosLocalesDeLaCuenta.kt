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
    suspend fun hayPendiente(venueId: String, orderId: String): Boolean {
        if (pendingPaymentDao.forVenue(venueId).any { it.orderId == orderId && it.syncStatus != "SYNCED" }) return true
        if (PendientesDeTarjeta.leer(secureStorage.pendingCardChargesJson).any {
            it.orderId == orderId && runCatching {
                Json.parseToJsonElement(it.contexto).jsonObject["venueId"]?.jsonPrimitive?.contentOrNull == venueId
            }.getOrDefault(false)
        }) return true
        if (store.delNegocio(venueId).any {
            it.tipo == "GIFT_CARD_REDEEM" && it.orderId == orderId && it.mandado && !it.porTerminar && !it.porRecuperar
        }) return true
        return try {
            outbox?.trabajoSinResolverDeLaOrden(venueId, setOf(orderId))?.tipos?.contains("PAY_CASH") == true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { true }
    }
}
