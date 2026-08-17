package com.avoqado.pos.cashdrawer.data

import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Los cobros en efectivo que este aparato YA cobró y que el server TODAVÍA no ha
 * visto, identificados por la orden a la que pertenecen.
 *
 * 🔴 Para qué existe: `CASH_SALE` es un tipo del que el server es dueño, así que el
 * sync del cajón borra las copias locales que no vengan confirmadas (si no, la misma
 * venta suma dos veces). Pero una venta cobrada SIN RED todavía no puede venir
 * confirmada: su cobro está esperando en una cola. Borrarla en esa ventana le
 * desaparece al cajero dinero que sí está en el cajón, y encima justo cuando abre la
 * pantalla de caja para cerrar su turno.
 *
 * Esto NO es una inferencia sobre "qué tan nueva se ve" una venta: es la cola,
 * diciendo que el cobro sigue pendiente. Mientras un cobro esté aquí, el gemelo del
 * server no existe **por construcción**, así que conservar la copia local no puede
 * duplicar nada. En cuanto se reproduce, deja de aparecer y el siguiente sync la
 * borra como siempre.
 *
 * Las DOS colas cuentan, porque un cobro sin red cae en una o en la otra:
 *  - **outbox de intents** (`PAY_CASH`) cuando la mesa nació provisional; el
 *    `localOrderId` del payload es el MISMO id con el que la venta entró al cajón.
 *  - **`pending_payments`** cuando la orden ya existía y falló el cobro.
 *
 * 🔴 `FAILED` NO cuenta a propósito. Un cobro fallido es ambiguo: puede haber
 * aterrizado en el server ("Order is already paid" es un 400 permanente) o no. Ante
 * la duda se mantiene el comportamiento de hoy —borrar la copia local—, que deja al
 * cajero con un SOBRANTE aparente en vez de con un faltante: nadie acusa a nadie de
 * un dinero que le sobra. Además esos cobros ya son visibles en cuarentena.
 */
@Singleton
class PendingCashSales @Inject constructor(
    private val intentDao: SyncIntentDao,
    private val pendingPaymentDao: PendingPaymentDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Órdenes cuyo cobro en efectivo sigue esperando a reproducirse. */
    suspend fun unreplayedOrderIds(venueId: String): Set<String> {
        val deIntents = intentDao.pendingPayloads(venueId, SyncIntentTypes.PAY_CASH).mapNotNull { payload ->
            runCatching {
                (json.parseToJsonElement(payload) as JsonObject)["localOrderId"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
        val deCobros = pendingPaymentDao.unsyncedOrderIds(venueId)
        return (deIntents + deCobros).filter { it.isNotBlank() }.toSet()
    }
}
