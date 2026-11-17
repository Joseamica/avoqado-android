package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ponytail: tope de 3 dígitos (999) contra un dedo de más; el «+» puede pasar de ahí. Subirlo si un negocio vende más por renglón.
private const val MAX_DIGITOS = 3

internal fun soloDigitosDeCantidad(texto: String): String = texto.filter(Char::isDigit).take(MAX_DIGITOS)

/** Vacío o 0 no es una cantidad: no se aplica, y al salir de la casilla vuelve la de antes. */
internal fun cantidadTecleada(texto: String): Int? = texto.toIntOrNull()?.takeIf { it >= 1 }

/**
 * El número entre «−» y «+» que también se teclea (founder, 17-nov: «poder teclear un número grande, para no sumar de
 * 1 en 1»; pan en Windows). Al tocarlo se vacía y la cantidad de antes queda de guía en gris: «24» se escribe directo, sin
 * borrar el 1 (con el 1 adentro saldría 124). Cada número válido se aplica al momento, igual que el «+»; Enter o «Listo»
 * sueltan la casilla. En Windows la pistola no se mete: con un campo escribiendo, `LectorDePistola` no toca nada.
 */
@Composable
fun CantidadTecleable(cantidad: Int, onCantidad: (Int) -> Unit, modifier: Modifier = Modifier) {
    var texto by remember(cantidad) { mutableStateOf(cantidad.toString()) }
    val foco = LocalFocusManager.current
    val color = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = texto,
        onValueChange = { nuevo ->
            texto = soloDigitosDeCantidad(nuevo)
            cantidadTecleada(texto)?.let { if (it != cantidad) onCantidad(it) }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = color, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(color),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { foco.clearFocus() }),
        // Al entrar se vacía; al salir enseña la cantidad real (una línea que no cambia, como la ya enviada, no miente).
        modifier = modifier.width(48.dp).onFocusChanged { texto = if (it.isFocused) "" else cantidad.toString() },
        decorationBox = { campo ->
            Box(contentAlignment = Alignment.Center) {
                if (texto.isEmpty()) {
                    Text(
                        text = cantidad.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                    )
                }
                campo()
            }
        },
    )
}
