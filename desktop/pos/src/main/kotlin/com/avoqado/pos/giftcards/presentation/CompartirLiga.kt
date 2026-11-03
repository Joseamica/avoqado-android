package com.avoqado.pos.giftcards.presentation

import android.content.Context
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

fun compartirLiga(context: Context, liga: String, alCopiar: () -> Unit) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(liga), null)
    alCopiar()
}
