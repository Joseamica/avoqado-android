package com.avoqado.pos.auth.data

import android.util.Log
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reemplazo de escritorio: no hay biometría en Windows todavía. Sólo lo que la app usa afuera
 * (`authenticate` y `error`, desde SignInViewModel): contesta «no» y deja el motivo en `error`,
 * que la pantalla de acceso muestra.
 */
@Singleton
class BiometricAuthManager @Inject constructor() {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun authenticate(activity: FragmentActivity): Boolean {
        Log.w("Escritorio", "No disponible en Windows todavía: biometría")
        _error.value = "La biometría no está disponible en Windows todavía."
        return false
    }
}
