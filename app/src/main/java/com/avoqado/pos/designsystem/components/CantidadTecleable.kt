package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// ponytail: tope de 3 dígitos (999) contra un dedo de más; el «+» puede pasar de ahí. Subirlo si un negocio vende más por renglón.
private const val MAX_DIGITOS = 3

/** Hasta dónde un número recién tecleado puede ser el 1.er carácter suelto de un código (un código entero tarda ~0.1–1.5 s). */
private const val VENTANA_DE_LA_PISTOLA_NS = 2_000_000_000L

/** Los códigos que leyó la pistola. ANDROID lo provee en `MainActivity` (`LectorHidBus`); sin proveer, nunca llega nada. */
val LocalCodigosDeLaPistola = staticCompositionLocalOf<Flow<String>> { emptyFlow() }

internal fun soloDigitosDeCantidad(texto: String): String = texto.filter(Char::isDigit)

/** Una letra o más de [MAX_DIGITOS] dígitos no es una cantidad: es un código de la pistola (o un dedo de más). */
internal fun noEsCantidad(texto: String): Boolean =
    texto.any(Char::isLetter) || soloDigitosDeCantidad(texto).length > MAX_DIGITOS

/** Vacío o 0 no es una cantidad: no se aplica, y al salir de la casilla vuelve la de antes. */
internal fun cantidadTecleada(texto: String): Int? =
    texto.takeUnless(::noEsCantidad)?.toIntOrNull()?.takeIf { it >= 1 }

/**
 * El número entre «−» y «+» que también se teclea (founder, 17-nov: «poder teclear un número grande, para no sumar de
 * 1 en 1»; pan en Windows). Al tocarlo se vacía y la cantidad de antes queda de guía en gris: «24» se escribe directo, sin
 * borrar el 1 (con el 1 adentro saldría 124). Cada número válido se aplica al momento, igual que el «+» (en una tableta
 * nadie suelta la casilla antes de tocar «Agregar»); Enter o «Listo» la sueltan.
 *
 * 🔴 La pistola no puede cambiar la cantidad (Codex r1, 17-nov):
 * - WINDOWS: con un campo escribiendo, `LectorDePistola` deja que el código caiga entero en la casilla («24» + `7501…` = 247;
 *   `ABC123` = 123). Con una letra o el 4.º dígito, la casilla regresa a la cantidad que tenía al tocarla y se queda sorda
 *   hasta que se suelte (se come el resto del código; se vuelve a escanear).
 * - ANDROID: la primera vez que dispara una pistola nueva, `LectorHid` deja pasar su 1.er carácter y se come el resto: sólo
 *   llega un «7». Cuando el código termina, si el último número se tecleó hace menos de [VENTANA_DE_LA_PISTOLA_NS], se deshace.
 */
@Composable
fun CantidadTecleable(cantidad: Int, onCantidad: (Int) -> Unit, modifier: Modifier = Modifier) {
    var texto by remember(cantidad) { mutableStateOf(cantidad.toString()) }
    var enfocada by remember { mutableStateOf(false) }
    var alTocarla by remember { mutableIntStateOf(cantidad) }
    var sorda by remember { mutableStateOf(false) }
    var antesDelUltimo by remember { mutableIntStateOf(cantidad) }
    var ultimoNs by remember { mutableLongStateOf(0L) }
    val cantidadAhora by rememberUpdatedState(cantidad)
    val aplicar by rememberUpdatedState(onCantidad)
    val codigos = LocalCodigosDeLaPistola.current
    LaunchedEffect(codigos) {
        codigos.collect {
            if (enfocada && System.nanoTime() - ultimoNs < VENTANA_DE_LA_PISTOLA_NS && antesDelUltimo != cantidadAhora) {
                aplicar(antesDelUltimo)
            }
        }
    }
    val foco = LocalFocusManager.current
    val color = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = texto,
        onValueChange = { nuevo ->
            when {
                sorda -> Unit
                noEsCantidad(nuevo) -> {
                    sorda = true
                    texto = alTocarla.toString()
                    if (alTocarla != cantidad) onCantidad(alTocarla)
                }
                else -> {
                    texto = soloDigitosDeCantidad(nuevo)
                    cantidadTecleada(texto)?.let {
                        if (it != cantidad) {
                            antesDelUltimo = cantidad
                            ultimoNs = System.nanoTime()
                            onCantidad(it)
                        }
                    }
                }
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = color, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(color),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { foco.clearFocus() }),
        // Al entrar se vacía; al salir enseña la cantidad real (una línea que no cambia, como la ya enviada, no miente).
        modifier = modifier.width(48.dp).onFocusChanged {
            enfocada = it.isFocused
            if (it.isFocused) {
                alTocarla = cantidad
                sorda = false
                texto = ""
            } else {
                texto = cantidad.toString()
            }
        },
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
