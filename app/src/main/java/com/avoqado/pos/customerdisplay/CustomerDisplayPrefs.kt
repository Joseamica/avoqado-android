package com.avoqado.pos.customerdisplay

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ajuste POR EQUIPO (no por venue): ¿el cliente realmente alcanza la segunda
 * pantalla? Vive en SharedPreferences porque describe cómo está ARMADO este
 * mostrador — el mismo negocio puede tener una caja con la pantalla de frente
 * al cliente y otra con la pantalla mirando a la pared.
 *
 * Apagado por defecto: prenderlo por nuestra cuenta dejaría el cobro esperando
 * un toque que nadie va a dar.
 */
@Singleton
class CustomerDisplayPrefs @Inject constructor(
    @ApplicationContext context: Context,
    private val state: CustomerDisplayState,
) {
    private val prefs = context.getSharedPreferences("avoqado_customer_display", Context.MODE_PRIVATE)

    private val _customerCaptureEnabled = MutableStateFlow(prefs.getBoolean(KEY_CAPTURE, false))
    val customerCaptureEnabled: StateFlow<Boolean> = _customerCaptureEnabled.asStateFlow()

    init {
        state.setCustomerCaptureEnabled(_customerCaptureEnabled.value)
    }

    fun setCustomerCaptureEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_CAPTURE, value).apply()
        _customerCaptureEnabled.value = value
        state.setCustomerCaptureEnabled(value)
    }

    private companion object {
        const val KEY_CAPTURE = "customer_captures_tip_and_rating"
    }
}
