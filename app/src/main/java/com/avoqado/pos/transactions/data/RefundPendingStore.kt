package com.avoqado.pos.transactions.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.avoqado.pos.transactions.data.model.esRechazoDefinitivoDeSaldo
import com.avoqado.pos.transactions.presentation.exchange.TextosCambio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

private val Context.refundPendingDataStore by preferencesDataStore(name = "refund_pending")

/**
 * Un Reembolso a saldo a favor ya mandado (o por mandar) cuya respuesta no consta: el cuerpo EXACTO, su llave y el nombre de QUIEN
 * lo pidió ([staffName], de la sesión al tocar «Reembolsar»), sólo para el aviso. Un pendiente guardado por una versión anterior no
 * lo trae (`null`); el `staffId` que guardaba una versión de esta rama se ignora al leer (`ignoreUnknownKeys`).
 */
@Serializable
data class PendingRefund(val venueId: String, val paymentId: String, val body: String, val idempotencyKey: String,
                         val staffName: String? = null)

/** El aviso del pendiente: de quién es, si se sabe. */
fun avisoDelPendiente(pending: PendingRefund): String =
    pending.staffName?.takeIf { it.isNotBlank() }?.let { TextosCambio.REEMBOLSO_SIN_CONFIRMAR_DE.replace("{nombre}", it) }
        ?: TextosCambio.REEMBOLSO_SIN_CONFIRMAR

/**
 * Lo pendiente del Reembolso a saldo a favor (spec 2026-10-30 §7, Codex r1 h4), en disco como `ExchangePendingStore`. Se guarda
 * ANTES del POST. Opción A (founder, 2-nov): al abrir la venta NO se reenvía nada; el detalle lo muestra y sólo «Confirmar estado»
 * (un toque humano, con la sesión de quien toca) lo reenvía con la MISMA llave (el SERVIDOR reconoce la repetición y no abona dos
 * veces). Se borra sólo con éxito o con un rechazo definitivo. Espejo de `RefundPendingStore.swift`.
 */
@Singleton
class RefundPendingStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.refundPendingDataStore)
    private fun key(venue: String, payment: String) = stringPreferencesKey("$venue:$payment")

    suspend fun pendiente(venue: String, payment: String): PendingRefund? =
        dataStore.data.first()[key(venue, payment)]?.let { jsonReembolsos.decodeFromString<PendingRefund>(it) }

    /** Nunca pisa un pendiente de OTRA llave: ése puede estar escrito en el servidor. */
    suspend fun guardar(pending: PendingRefund) {
        dataStore.edit { prefs ->
            val key = key(pending.venueId, pending.paymentId)
            val old = prefs[key]?.let { jsonReembolsos.decodeFromString<PendingRefund>(it) }
            if (old != null && old.idempotencyKey != pending.idempotencyKey) throw OtroReembolsoSinConfirmar()
            prefs[key] = jsonReembolsos.encodeToString(pending)
        }
    }

    /** Sólo borra si lo guardado es ESE envío (misma llave). */
    suspend fun borrar(pending: PendingRefund) {
        dataStore.edit { prefs ->
            val key = key(pending.venueId, pending.paymentId)
            val old = prefs[key]?.let { jsonReembolsos.decodeFromString<PendingRefund>(it) }
            if (old?.idempotencyKey == pending.idempotencyKey) prefs.remove(key)
        }
    }
}

/** Ya hay en el aparato un reembolso a saldo a favor de esa venta con OTRA llave: no se pisa (puede estar escrito en el servidor). */
class OtroReembolsoSinConfirmar : IllegalStateException("Hay otro reembolso a saldo a favor sin confirmar")

/** El desenlace de mandar (o reenviar) un Reembolso a saldo a favor. */
sealed interface DesenlaceDeSaldo {
    /** El servidor lo escribió (o reconoció la llave y contestó lo de la primera vez): lo pendiente se borró. */
    data class Hecho(val data: AssociatedRefundData) : DesenlaceDeSaldo
    /** Rechazo definitivo (nada se escribió): lo pendiente se borró y la cajera corrige con lo capturado. */
    data class Rechazado(val mensaje: String, val code: String?) : DesenlaceDeSaldo
    /** No consta: lo pendiente se CONSERVA y «Confirmar estado» lo reenvía con la misma llave. */
    data class Pendiente(val mensaje: String) : DesenlaceDeSaldo
    /** Ya había otro sin confirmar de esa venta (otra llave): no se mandó nada; el detalle lo muestra y se confirma ése primero. */
    data object OtroPendiente : DesenlaceDeSaldo
    /** No se pudo guardar en el aparato: no se mandó nada. */
    data object GuardadoFallido : DesenlaceDeSaldo
}

/** Qué decirle a la cajera cuando lo pendiente se conserva. */
fun mensajeDeReembolsoPendiente(status: Int?, code: String?, mensaje: String?): String = when {
    code == "IDEMPOTENCY_KEY_REUSED" -> TextosCambio.REEMBOLSO_LLAVE_REUSADA
    status == 403 -> TextosCambio.REEMBOLSO_PIDE_GERENTE
    status != null && status in 400..499 && status !in setOf(401, 408, 429) && !mensaje.isNullOrBlank() -> mensaje
    else -> TextosCambio.REEMBOLSO_NO_CONFIRMADO
}

/**
 * Manda lo pendiente con SU cuerpo y SU llave. Borra sólo con éxito o con un rechazo definitivo (`esRechazoDefinitivoDeSaldo`) que
 * trae el eco de su petición. Siempre lo pide una persona («Reembolsar», «Confirmar estado»): va en primer plano y un 403 puede pedir
 * el PIN de gerente, que reenvía AHÍ la misma petición con la misma llave (su consentimiento).
 *
 * 🔴 Un 403 NUNCA borra lo pendiente, ni con el eco (Opción A, Codex apps r7 #2): el SERVIDOR revisa el permiso ANTES de reconocer la
 * llave, así que un 403 no prueba que no se escribió (el gerente autorizó con PIN, se perdió la respuesta y quien reabre no tiene
 * permiso). Se conserva y se pide que lo confirme un gerente.
 */
suspend fun enviarReembolsoPendiente(repo: RefundRepository, store: RefundPendingStore, pending: PendingRefund): DesenlaceDeSaldo {
    val resultado = repo.enviarCuerpo(pending.paymentId, pending.body)
    resultado.getOrNull()?.let { borrarALoMejor(store, pending); return DesenlaceDeSaldo.Hecho(it) }
    val e = resultado.exceptionOrNull()
    if (e is CancellationException) throw e
    // Un rechazo sin el eco de SU X-Correlation-ID (un proxy, un portal, un túnel) se trata como la red: lo pendiente se conserva.
    val api = (e as? RefundApiException)?.takeIf { it.delServidor }
    if (api != null && esRechazoDefinitivoDeSaldo(api.statusCode, api.code)) {
        borrarALoMejor(store, pending)
        return DesenlaceDeSaldo.Rechazado(api.message.ifBlank { TextosCambio.REEMBOLSO_NO_CONFIRMADO }, api.code)
    }
    return DesenlaceDeSaldo.Pendiente(mensajeDeReembolsoPendiente(api?.statusCode, api?.code, api?.message))
}

/**
 * Borrar después del desenlace es a lo mejor posible (como el `try?` de iOS): si el disco falla, lo pendiente se queda y «Confirmar
 * estado» lo reenvía con la misma llave; el SERVIDOR repite la respuesta. Nunca tumba la app.
 */
private suspend fun borrarALoMejor(store: RefundPendingStore, pending: PendingRefund) {
    try {
        store.borrar(pending)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("💸 RefundPending", "No se pudo borrar el reembolso pendiente ya confirmado", e)
    }
}

/** El envío: guarda ANTES de tocar la red y manda. Si no se pudo guardar, no se manda nada. */
class SaldoAFavorEnvio(private val repo: RefundRepository, private val store: RefundPendingStore) {
    suspend fun enviar(venue: String, paymentId: String, body: String, idempotencyKey: String, staffName: String? = null): DesenlaceDeSaldo {
        val pending = PendingRefund(venue, paymentId, body, idempotencyKey, staffName?.takeIf { it.isNotBlank() })
        try {
            store.guardar(pending)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OtroReembolsoSinConfirmar) {
            return DesenlaceDeSaldo.OtroPendiente
        } catch (_: Exception) {
            return DesenlaceDeSaldo.GuardadoFallido
        }
        return enviarReembolsoPendiente(repo, store, pending)
    }
}
