package com.avoqado.pos.escritorio.teclado

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * El contenido se encoge por la altura del teclado, y las teclas van en un Popup que se crea al mostrarse: su capa queda
 * ARRIBA de los diálogos ya abiertos (un Dialog es otra capa con su fondo oscuro y se comería los toques).
 */
@Composable
fun ConTecladoEnPantalla(visible: Boolean, teclado: @Composable () -> Unit, generacion: Int = 0, contenido: @Composable () -> Unit) {
    var altoPx by remember { mutableStateOf(0) }
    val alto = if (altoPx > 0) with(LocalDensity.current) { altoPx.toDp() } else 260.dp   // 5 filas de 52 dp hasta medirlo
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().weight(1f)) { contenido() }
        if (visible) Spacer(Modifier.height(alto))
    }
    // Los diálogos leen esta altura como «ime» (ConfiguracionDeInsetsDeEscritorio): su contenido sube y no queda tapado.
    SideEffect { InsetsDelTeclado.alto.floatValue = if (visible) alto.value else 0f }
    if (visible) {
        // key(generacion): una sesión de escritura nueva recrea el Popup, y así su capa queda arriba de los diálogos recién abiertos.
        key(generacion) {
            Popup(
                alignment = Alignment.BottomCenter,
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                Box(Modifier.fillMaxWidth().onSizeChanged { altoPx = it.height }) { teclado() }
            }
        }
    }
}
