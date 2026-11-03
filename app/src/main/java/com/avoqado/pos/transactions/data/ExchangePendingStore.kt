package com.avoqado.pos.transactions.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

private val Context.exchangePendingDataStore by preferencesDataStore(name = "exchange_pending")

@Serializable
data class PendingExchange(val venueId: String, val originalPaymentId: String, val originalOrderNumber: String,
    val actorName: String, val body: String, val result: ExchangeResult? = null,
    val cardStarted: Boolean = false, val cardRequestId: String? = null)

/** Manual recovery only: reading notices never sends or schedules an exchange. */
@Singleton
class ExchangePendingStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.exchangePendingDataStore)
    private fun key(venue: String, payment: String) = stringPreferencesKey("$venue:$payment")

    suspend fun load(venue: String, payment: String): PendingExchange? =
        dataStore.data.first()[key(venue, payment)]?.let { jsonReembolsos.decodeFromString<PendingExchange>(it) }

    suspend fun forActor(venue: String, actor: String): List<PendingExchange> =
        dataStore.data.first().asMap().entries.filter { it.key.name.startsWith("$venue:") }
            .map { jsonReembolsos.decodeFromString<PendingExchange>(it.value as String) }
            .filter { it.venueId == venue &&
                jsonReembolsos.parseToJsonElement(it.body).jsonObject["staffId"]?.jsonPrimitive?.contentOrNull == actor }

    suspend fun save(pending: PendingExchange): PendingExchange {
        var saved = pending
        dataStore.edit { prefs ->
            val key = key(pending.venueId, pending.originalPaymentId)
            val old = prefs[key]?.let { jsonReembolsos.decodeFromString<PendingExchange>(it) }
            check(old == null || old.body == pending.body)
            saved = pending.copy(cardStarted = pending.cardStarted || old?.cardStarted == true,
                cardRequestId = pending.cardRequestId ?: old?.cardRequestId, result = pending.result ?: old?.result)
            prefs[key] = jsonReembolsos.encodeToString(saved)
        }
        return saved
    }

    suspend fun remove(pending: PendingExchange) {
        dataStore.edit { prefs ->
            val key = key(pending.venueId, pending.originalPaymentId)
            val old = prefs[key]?.let { jsonReembolsos.decodeFromString<PendingExchange>(it) }
            if (old?.body == pending.body) prefs.remove(key)
        }
    }
}
