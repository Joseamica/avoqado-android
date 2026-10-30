package com.avoqado.pos.giftcards.presentation

import android.content.Context
import android.content.Intent

fun compartirLiga(context: Context, liga: String, alCopiar: () -> Unit) {
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, liga)
    }
    context.startActivity(Intent.createChooser(envio, "Compartir liga"))
}
