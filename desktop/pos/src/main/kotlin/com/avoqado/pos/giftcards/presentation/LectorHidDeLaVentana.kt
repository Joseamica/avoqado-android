package com.avoqado.pos.giftcards.presentation

import android.view.KeyEvent
import androidx.compose.runtime.Composable

// Windows ya captura por PistolaDeLaVentana; el campo con foco envía con Enter/IME.
@Composable
fun LectorHidDeLaVentana(procesar: (KeyEvent) -> Boolean) = Unit
