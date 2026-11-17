package com.avoqado.pos.designsystem.components

import androidx.compose.ui.input.key.KeyEvent

/**
 * La hora (ms) en que el aparato recibió la tecla, no cuándo la procesa la app: con la app ocupada, dos teclas de una
 * persona se procesan pegadas y por la hora de proceso parecerían la pistola. ANDROID: `eventTime` (reloj de arranque).
 * WINDOWS la reemplaza con la hora del evento de AWT (desktop/pos/excluidos.txt).
 */
internal fun horaDeLaTeclaMs(e: KeyEvent): Long = e.nativeKeyEvent.eventTime
