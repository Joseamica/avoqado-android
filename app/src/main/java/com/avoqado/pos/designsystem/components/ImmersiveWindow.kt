package com.avoqado.pos.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Mantiene la pantalla completa del POS dentro de un diálogo o bottom sheet.
 *
 * 🔴 Cada Dialog/ModalBottomSheet de Compose abre su PROPIA ventana, y esa
 * ventana NO hereda el modo inmersivo de la Activity: al abrir cualquier hoja
 * reaparecían la barra de estado y la de navegación de Android —en la Sunmi,
 * además, el dock de apps— justo encima del POS. Con el cliente enfrente eso
 * son botones tocables que sacan de la app a media transacción.
 *
 * Llamar en el PRIMER composable del contenido de la hoja.
 */
@Composable
fun ImmersiveWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(window) {
        val w = window ?: return@LaunchedEffect
        WindowCompat.setDecorFitsSystemWindows(w, false)
        WindowCompat.getInsetsController(w, w.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
