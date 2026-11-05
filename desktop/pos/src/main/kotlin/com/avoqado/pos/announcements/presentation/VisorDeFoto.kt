package com.avoqado.pos.announcements.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Reemplazo de escritorio de `app/src/main/java/.../announcements/presentation/VisorDeFoto.kt` (ver excluidos.txt).
 *
 * En Android el visor es una ventana propia de pantalla completa (ComponentDialog), porque el `Dialog` de Compose allá
 * mide la pantalla menos las barras del sistema. En escritorio no hay barras: el `Dialog` de Compose es una capa sobre
 * la ventana del POS y, sin el ancho de plataforma, ocupa la ventana completa. Esc (o tocar fuera) lo cierra.
 */
@Composable
fun VisorDeFoto(url: String, alt: String?, onCerrar: () -> Unit) {
    Dialog(onDismissRequest = onCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ContenidoDelVisor(url, alt, onCerrar)
    }
}
