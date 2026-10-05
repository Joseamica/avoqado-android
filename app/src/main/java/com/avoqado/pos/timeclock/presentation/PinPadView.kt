package com.avoqado.pos.timeclock.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Una tecla del teclado FÍSICO sobre el PIN: dígito agrega, Retroceso borra. `null` = la tecla
 * no le toca al PIN (que siga su camino: Esc cierra el diálogo, Tab mueve el foco).
 */
internal fun pinConTecla(pin: String, caracter: Char?, borrar: Boolean, maxLength: Int): String? = when {
    borrar -> pin.dropLast(1)
    caracter != null && caracter in '0'..'9' -> if (pin.length < maxLength) pin + caracter else pin
    else -> null
}

@Composable
fun PinPadView(
    pin: String,
    onPinChange: (String) -> Unit,
    maxLength: Int = 10,
    minLength: Int = 4,
    compact: Boolean = false,
) {
    val dotSize = if (compact) 12.dp else 14.dp
    val dotSpacing = if (compact) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md
    val keySize = if (compact) 56.dp else 64.dp
    val rowWidth = if (compact) 0.82f else 0.7f
    val rowGap = if (compact) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm

    // Teclado físico (PC con Windows, tableta con teclado): sin esto los dígitos no entraban y
    // el PIN sólo se podía poner con clic o con el dedo. Sin campo de texto: no abre teclado en pantalla.
    // Se pide al abrir Y cada vez que el PIN vuelve a quedar vacío: tras un PIN equivocado con Enter
    // en el botón, el foco se quedaba allá y los dígitos ya no entraban (Codex, 4-oct).
    val foco = remember { FocusRequester() }
    LaunchedEffect(pin.isEmpty()) { if (pin.isEmpty()) foco.requestFocus() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .focusRequester(foco)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val nuevo = pinConTecla(
                    pin,
                    caracter = e.utf16CodePoint.takeIf { it > 0 }?.toChar(),
                    borrar = e.key == Key.Backspace,
                    maxLength = maxLength,
                ) ?: return@onPreviewKeyEvent false
                if (nuevo != pin) onPinChange(nuevo)
                true
            },
    ) {
        // PIN dots — dynamic: show one filled dot per entered digit
        Row(
            modifier = Modifier.height(20.dp),
            horizontalArrangement = Arrangement.spacedBy(dotSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pin.isEmpty()) {
                // Sin puntos de relleno: cuatro casillas vacías se leen como "el PIN
                // es de 4", y el rango real es 4 a 10. El texto de abajo lo dice con
                // palabras. El Row conserva su altura fija, así que nada salta.
            } else {
                // Show one filled dot per entered digit
                repeat(pin.length) {
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (compact) AvoqadoTheme.spacing.xl else AvoqadoTheme.spacing.xxl))

        // Number pad
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "del"),
        )

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(rowWidth),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { key ->
                    when (key) {
                        "" -> Spacer(modifier = Modifier.size(keySize))
                        "del" -> {
                            IconButton(
                                onClick = {
                                    if (pin.isNotEmpty()) onPinChange(pin.dropLast(1))
                                },
                                modifier = Modifier.size(keySize),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Borrar",
                                )
                            }
                        }
                        else -> {
                            Surface(
                                onClick = {
                                    if (pin.length < maxLength) onPinChange(pin + key)
                                },
                                modifier = Modifier.size(keySize),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = key,
                                        style = if (compact) {
                                            MaterialTheme.typography.titleLarge
                                        } else {
                                            MaterialTheme.typography.headlineMedium
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(rowGap))
        }
    }
}
