package com.avoqado.pos.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * 🔴 Y el CUÁNDO importa tanto como el qué. Esto vivía en un `LaunchedEffect`,
 * que se despacha a una corrutina DESPUÉS de que la ventana ya se agregó, tomó
 * el foco y se dibujó con las barras visibles. El `hide()` posterior entonces
 * no las evitaba: las animaba hacia afuera. Medido en la D3 grabando la
 * pantalla, el dock de Sunmi quedaba encima del POS ~18 frames (**600 ms**)
 * cada vez que se abría una hoja.
 *
 * Ahora corre durante la COMPOSICIÓN (`remember`), que es lo más temprano que
 * se puede tocar la ventana desde aquí: antes del primer dibujo de la hoja. Con
 * eso el dock ya no alcanza a pintarse — medido igual, 0 frames.
 *
 * Se probó además el truco clásico de `FLAG_NOT_FOCUSABLE` (configurar las
 * flags antes de que la ventana tome el foco) y NO aporta nada sobre esto: 0
 * frames en los dos casos. Se descartó para no arriesgar el teclado de las
 * hojas con campos de texto.
 *
 * 🔴 **El teclado NO se arregla desde aquí — se intentó y ROMPE la hoja.**
 * El problema existe: `ModalBottomSheetDialogWrapper` (Material3 1.3.1) le fija
 * a SU ventana `setSoftInputMode(48)` = `ADJUST_NOTHING` en API 30+, así que el
 * sistema no mueve nada y el teclado tapa la hoja entera. Pero el arreglo NO
 * puede ser tocar la ventana: se probó aplicarle al `android.R.id.content` un
 * padding inferior del alto del teclado y, medido en un OrderPAD 3, la hoja de
 * "Abrir caja" **se salió de la pantalla por arriba** — el sheet se ancla al
 * borde inferior del contenedor, así que encogerlo lo descoloca entero.
 *
 * El arreglo va en el CONTENIDO de cada hoja: `Modifier.imePadding()` en su
 * columna raíz. La ventana principal sí se arregla de una vez, en `MainActivity`.
 *
 * Llamar en el PRIMER composable del contenido de la hoja.
 */
@Composable
fun ImmersiveWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    // `remember` y no un efecto: los efectos se despachan tarde y la barra
    // alcanza a pintarse. Esto corre una sola vez por ventana, en composición.
    remember(window) {
        window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowCompat.getInsetsController(w, w.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        window
    }
}
