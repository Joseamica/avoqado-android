package com.avoqado.pos.customerdisplay

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Mini-journal privado, durable y scopeado por venue + identidad del aparato. */
@Singleton
class DisplayModeRequestStore internal constructor(
    private val prefs: SharedPreferences,
) : DisplayModeRequestJournal {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun readState(venueId: String, deviceId: String): DisplayModeJournalState =
        runCatching { prefs.getString(storageKey(venueId, deviceId), null) }.fold(
            onSuccess = { raw ->
                if (raw == null) {
                    DisplayModeJournalState.Empty
                } else {
                    runCatching { decode(raw, venueId, deviceId) }.fold(
                        onSuccess = DisplayModeJournalState::Ready,
                        onFailure = { DisplayModeJournalState.Unreadable },
                    )
                }
            },
            onFailure = { DisplayModeJournalState.Unreadable },
        )

    @Synchronized
    override fun save(entry: JournalEntry) {
        requireValid(entry)
        val key = storageKey(entry.venueId, entry.deviceId)
        val previousRaw = prefs.getString(key, null)
        commitOrRestore(
            key = key,
            previousRaw = previousRaw,
            nextRaw = encode(entry),
            failureMessage = "No se pudo persistir el journal de display mode",
        )
    }

    @Synchronized
    override fun clear(venueId: String, deviceId: String, requestId: String) {
        val key = storageKey(venueId, deviceId)
        val previousRaw = prefs.getString(key, null) ?: return
        val current = runCatching { decode(previousRaw, venueId, deviceId) }.getOrNull() ?: return
        if (current.requestId != requestId) return
        commitOrRestore(
            key = key,
            previousRaw = previousRaw,
            nextRaw = null,
            failureMessage = "No se pudo limpiar el journal de display mode",
        )
    }

    /**
     * SharedPreferences publica primero en memoria y después escribe a disco.
     * Por eso `commit=false` NO revierte la mutación visible en este proceso.
     * Compensamos con el raw anterior antes de propagar el fallo. Aunque el
     * commit compensatorio también devuelva false, Android ya publicó el valor
     * anterior en memoria y el disco conserva el estado previo al primer fallo.
     */
    private fun commitOrRestore(
        key: String,
        previousRaw: String?,
        nextRaw: String?,
        failureMessage: String,
    ) {
        val persisted = prefs.edit().let { editor ->
            if (nextRaw == null) editor.remove(key) else editor.putString(key, nextRaw)
        }.commit()
        if (persisted) return

        val restorationFailure = runCatching {
            prefs.edit().let { editor ->
                if (previousRaw == null) editor.remove(key) else editor.putString(key, previousRaw)
            }.commit()
        }.exceptionOrNull()
        throw IllegalStateException(failureMessage).also { error ->
            restorationFailure?.let(error::addSuppressed)
        }
    }

    private fun encode(entry: JournalEntry): String = buildJsonObject {
        put("venueId", entry.venueId)
        put("deviceId", entry.deviceId)
        put("terminalId", entry.terminalId)
        put("requestId", entry.requestId)
        put("desiredInverted", entry.desiredInverted)
        put("appliedLocally", entry.appliedLocally)
        put("ackPending", entry.ackPending)
        put("localGenerationAtJournal", entry.localGenerationAtJournal)
        put("requestExpiresAtEpochMillis", entry.requestExpiresAt.toEpochMilli())
        put("journaledAtEpochMillis", entry.journaledAt.toEpochMilli())
        entry.applyStartedAt?.let { put("applyStartedAtEpochMillis", it.toEpochMilli()) }
        entry.appliedAt?.let { put("appliedAtEpochMillis", it.toEpochMilli()) }
        entry.ackPreparedAt?.let { put("ackPreparedAtEpochMillis", it.toEpochMilli()) }
        entry.ackOutcome?.let { put("ackOutcome", it.name) }
        entry.ackResultCode?.let { put("ackResultCode", it.name) }
        entry.confirmedInverted?.let { put("confirmedInverted", it) }
        entry.confirmedLocalGeneration?.let { put("confirmedLocalGeneration", it) }
    }.toString()

    private fun decode(raw: String, expectedVenueId: String, expectedDeviceId: String): JournalEntry {
        val value = json.parseToJsonElement(raw).jsonObject
        val entry = JournalEntry(
            venueId = value.requiredString("venueId"),
            deviceId = value.requiredString("deviceId"),
            terminalId = value.requiredString("terminalId"),
            requestId = value.requiredString("requestId"),
            desiredInverted = value.requiredBoolean("desiredInverted"),
            appliedLocally = value.requiredBoolean("appliedLocally"),
            ackPending = value.requiredBoolean("ackPending"),
            localGenerationAtJournal = value.requiredLong("localGenerationAtJournal"),
            requestExpiresAt = value.requiredInstant("requestExpiresAtEpochMillis"),
            journaledAt = value.requiredInstant("journaledAtEpochMillis"),
            applyStartedAt = value.optionalInstant("applyStartedAtEpochMillis"),
            appliedAt = value.optionalInstant("appliedAtEpochMillis"),
            ackPreparedAt = value.optionalInstant("ackPreparedAtEpochMillis"),
            ackOutcome = value.optionalEnum<DisplayModeAckOutcome>("ackOutcome"),
            ackResultCode = value.optionalEnum<DisplayModeAckResultCode>("ackResultCode"),
            confirmedInverted = value["confirmedInverted"]?.jsonPrimitive?.booleanOrNull,
            confirmedLocalGeneration = value["confirmedLocalGeneration"]?.jsonPrimitive?.longOrNull,
        )
        check(entry.venueId == expectedVenueId && entry.deviceId == expectedDeviceId)
        requireValid(entry)
        return entry
    }

    private fun requireValid(entry: JournalEntry) {
        require(entry.venueId.isNotBlank())
        require(entry.deviceId.isNotBlank())
        require(entry.terminalId.isNotBlank())
        require(entry.requestId.isNotBlank())
        require(entry.localGenerationAtJournal >= 0)
        require(entry.confirmedLocalGeneration == null || entry.confirmedLocalGeneration >= 0)
        if (entry.ackPending) {
            require(entry.ackOutcome != null)
            require(entry.confirmedInverted != null)
            require(entry.ackOutcome != DisplayModeAckOutcome.REJECTED || entry.ackResultCode != null)
            require(entry.ackOutcome != DisplayModeAckOutcome.APPLIED || entry.ackResultCode == null)
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("Falta $name")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        get(name)?.jsonPrimitive?.booleanOrNull ?: error("Falta $name")

    private fun JsonObject.requiredLong(name: String): Long =
        get(name)?.jsonPrimitive?.longOrNull ?: error("Falta $name")

    private fun JsonObject.requiredInstant(name: String): Instant = Instant.ofEpochMilli(requiredLong(name))

    private fun JsonObject.optionalInstant(name: String): Instant? =
        get(name)?.jsonPrimitive?.longOrNull?.let(Instant::ofEpochMilli)

    private inline fun <reified T : Enum<T>> JsonObject.optionalEnum(name: String): T? {
        val enumName = get(name)?.jsonPrimitive?.contentOrNull ?: return null
        return enumValues<T>().firstOrNull { it.name == enumName } ?: error("$name no valido")
    }

    companion object {
        private const val PREFS_NAME = "avoqado_display_mode_request_journal"

        /** Length-prefix evita colisiones aunque ids contengan separadores. */
        internal fun storageKey(venueId: String, deviceId: String): String =
            "entry:${venueId.length}:$venueId:${deviceId.length}:$deviceId"
    }
}
