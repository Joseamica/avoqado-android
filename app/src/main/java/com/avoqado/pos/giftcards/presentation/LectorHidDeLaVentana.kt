package com.avoqado.pos.giftcards.presentation

import android.app.Dialog
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

@Composable
fun LectorHidDeLaVentana(procesar: (KeyEvent) -> Boolean) {
    val actual by rememberUpdatedState(procesar)
    val dialogo = (LocalView.current.parent as? DialogWindowProvider)?.window?.callback as? Dialog
    DisposableEffect(dialogo) {
        val soltar = dialogo?.let { escucharLectorEnDialogo(it) { evento -> actual(evento) } }
        onDispose { soltar?.invoke() }
    }
}

internal fun escucharLectorEnDialogo(dialogo: Dialog, procesar: (KeyEvent) -> Boolean): () -> Unit {
    dialogo.setOnKeyListener { _, _, evento -> procesar(evento) }
    return { dialogo.setOnKeyListener(null) }
}
