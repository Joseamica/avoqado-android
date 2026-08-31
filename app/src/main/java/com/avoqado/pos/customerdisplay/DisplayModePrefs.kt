package com.avoqado.pos.customerdisplay

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RemoteDisplayModeApplyResult {
    data class Applied(val inverted: Boolean) : RemoteDisplayModeApplyResult
    data class LocalOverride(
        val currentInverted: Boolean,
        val currentGeneration: Long,
    ) : RemoteDisplayModeApplyResult
}

data class DisplayModePreferenceSnapshot(
    val inverted: Boolean,
    val generation: Long,
)

internal interface DisplayModePreferenceStore {
    val invertedValue: Boolean
    val dirtyValue: Boolean
    val localGeneration: Long

    fun applyRemoteIntent(
        value: Boolean,
        localGenerationAtJournal: Long,
    ): RemoteDisplayModeApplyResult

    fun markSynced(expectedGeneration: Long, expectedValue: Boolean): Boolean

    fun snapshot(): DisplayModePreferenceSnapshot

    /** [persist] must be non-suspending; it runs under the local-mutation monitor. */
    fun persistIfUnchanged(
        expected: DisplayModePreferenceSnapshot,
        persist: () -> Unit,
    ): Boolean
}

/** Preferencia durable por aparato; la generación distingue incluso A→B→A. */
@Singleton
class DisplayModePrefs internal constructor(
    private val prefs: SharedPreferences,
) : DisplayModePreferenceStore {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val _inverted = MutableStateFlow(prefs.getBoolean(KEY_INVERTED, false))
    val inverted: StateFlow<Boolean> = _inverted.asStateFlow()

    private val _dirty = MutableStateFlow(prefs.getBoolean(KEY_DIRTY, false))
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _generation = MutableStateFlow(prefs.getLong(KEY_GENERATION, 0L).coerceAtLeast(0L))
    val generation: StateFlow<Long> = _generation.asStateFlow()

    override val invertedValue: Boolean get() = _inverted.value
    override val dirtyValue: Boolean get() = _dirty.value
    override val localGeneration: Long get() = _generation.value

    @Synchronized
    override fun snapshot(): DisplayModePreferenceSnapshot =
        DisplayModePreferenceSnapshot(_inverted.value, _generation.value)

    /** Linearization point shared with [setInverted]; never wraps HTTP/suspension. */
    @Synchronized
    override fun persistIfUnchanged(
        expected: DisplayModePreferenceSnapshot,
        persist: () -> Unit,
    ): Boolean {
        if (snapshot() != expected) return false
        persist()
        return true
    }

    /** Cambio local: los tres valores llegan a disco antes de publicarse. */
    @Synchronized
    fun setInverted(value: Boolean) {
        check(_generation.value < Long.MAX_VALUE) { "La generación local de display mode se agotó" }
        val nextGeneration = _generation.value + 1L
        commitStateOrThrow(value, dirty = true, generation = nextGeneration)
        _inverted.value = value
        _dirty.value = true
        _generation.value = nextGeneration
    }

    /** Confirmación legacy libre; jamás pisa una mutación local pendiente. */
    @Synchronized
    fun adoptFromServer(value: Boolean) {
        if (_dirty.value || _inverted.value == value) return
        commitStateOrThrow(value, dirty = false, generation = _generation.value)
        _inverted.value = value
    }

    /**
     * Una intención tipada sí tiene autoridad acotada. La comparación y el
     * commit exacto comparten la misma sección crítica que [setInverted].
     */
    @Synchronized
    override fun applyRemoteIntent(
        value: Boolean,
        localGenerationAtJournal: Long,
    ): RemoteDisplayModeApplyResult {
        if (_generation.value > localGenerationAtJournal) {
            return RemoteDisplayModeApplyResult.LocalOverride(
                currentInverted = _inverted.value,
                currentGeneration = _generation.value,
            )
        }
        commitStateOrThrow(value, dirty = false, generation = _generation.value)
        _inverted.value = value
        _dirty.value = false
        return RemoteDisplayModeApplyResult.Applied(value)
    }

    /** Una respuesta vieja sólo limpia exactamente la generación que produjo. */
    @Synchronized
    override fun markSynced(expectedGeneration: Long, expectedValue: Boolean): Boolean {
        if (_generation.value != expectedGeneration || _inverted.value != expectedValue) return false
        if (!_dirty.value) return true
        commitStateOrThrow(_inverted.value, dirty = false, generation = _generation.value)
        _dirty.value = false
        return true
    }

    /**
     * SharedPreferences publica primero en memoria incluso si `commit=false`.
     * Restauramos los tres valores anteriores antes de propagar el fallo para
     * que ni este proceso ni el siguiente observen un éxito no durable.
     */
    private fun commitStateOrThrow(value: Boolean, dirty: Boolean, generation: Long) {
        val previous = PersistedState(
            inverted = prefs.getBoolean(KEY_INVERTED, false),
            dirty = prefs.getBoolean(KEY_DIRTY, false),
            generation = prefs.getLong(KEY_GENERATION, 0L),
            hadInverted = prefs.contains(KEY_INVERTED),
            hadDirty = prefs.contains(KEY_DIRTY),
            hadGeneration = prefs.contains(KEY_GENERATION),
        )
        val committed = prefs.edit()
            .putBoolean(KEY_INVERTED, value)
            .putBoolean(KEY_DIRTY, dirty)
            .putLong(KEY_GENERATION, generation)
            .commit()
        if (committed) return

        runCatching {
            prefs.edit().also { editor ->
                if (previous.hadInverted) editor.putBoolean(KEY_INVERTED, previous.inverted) else editor.remove(KEY_INVERTED)
                if (previous.hadDirty) editor.putBoolean(KEY_DIRTY, previous.dirty) else editor.remove(KEY_DIRTY)
                if (previous.hadGeneration) editor.putLong(KEY_GENERATION, previous.generation) else editor.remove(KEY_GENERATION)
            }.commit()
        }
        throw IllegalStateException("No se pudo persistir el modo de pantallas")
    }

    private data class PersistedState(
        val inverted: Boolean,
        val dirty: Boolean,
        val generation: Long,
        val hadInverted: Boolean,
        val hadDirty: Boolean,
        val hadGeneration: Boolean,
    )

    internal companion object {
        const val PREFS_NAME = "avoqado_display_mode"
        const val KEY_INVERTED = "customer_display_inverted"
        const val KEY_DIRTY = "customer_display_inverted_dirty"
        const val KEY_GENERATION = "customer_display_local_generation"
    }
}
