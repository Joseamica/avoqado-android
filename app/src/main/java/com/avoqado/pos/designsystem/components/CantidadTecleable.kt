package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ponytail: tope de 3 dígitos (999) contra un dedo de más; el «+» puede pasar de ahí. Subirlo si un negocio vende más por renglón.
private const val MAX_DIGITOS = 3

/**
 * ¿Hay una casilla de cantidad con el foco? La lee el lector de la pistola de ANDROID (`LectorHidBus`) para no dejarle
 * pasar teclas físicas. (WINDOWS no: retener ahí las teclas de la cajera dejaba que un clic rápido en «Agregar» llegara
 * antes que su último dígito; Codex r3 #1.) Una dueña a la vez: soltar sólo suelta si sigue siendo suya.
 */
object CasillaDeCantidad {
    @Volatile private var duena: Any? = null
    val activa: Boolean get() = duena != null
    fun tomar(quien: Any) { duena = quien }
    fun soltar(quien: Any) { if (duena === quien) duena = null }
}

internal fun soloDigitosDeCantidad(texto: String): String = texto.filter(Char::isDigit)

/** Una letra o más de [MAX_DIGITOS] dígitos no es una cantidad: es un código (o un dedo de más). */
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
 * 🔴 La pistola (Codex r1–r3):
 * - ANDROID, ventana principal: con la casilla activa ([CasillaDeCantidad]) `LectorHidBus` no le deja pasar teclas
 *   físicas; sólo vale el teclado de la pantalla.
 * - En lo que no pasa por ahí (WINDOWS, la hoja del teléfono, el lector integrado de una Sunmi en modo teclado), el código
 *   cae en la casilla: una letra o el 4.º dígito regresan la cantidad que había al tocarla y la casilla se queda sorda
 *   hasta que se suelte (se come el resto; se vuelve a escanear). Límite aceptado: un código de 1 a 3 dígitos sin letras
 *   cuenta como cantidad, igual que el lector de toda la app no reconoce códigos de menos de 4.
 */
@Composable
fun CantidadTecleable(cantidad: Int, onCantidad: (Int) -> Unit, modifier: Modifier = Modifier) {
    var texto by remember(cantidad) { mutableStateOf(cantidad.toString()) }
    var alTocarla by remember { mutableIntStateOf(cantidad) }
    var sorda by remember { mutableStateOf(false) }
    val yo = remember { Any() }
    DisposableEffect(yo) { onDispose { CasillaDeCantidad.soltar(yo) } }
    val foco = LocalFocusManager.current
    val color = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = texto,
        onValueChange = { nuevo ->
            when {
                sorda -> Unit
                // Siempre se avisa, sin comparar con `cantidad`: dentro de una ráfaga la pantalla no se ha vuelto a pintar y
                // ese valor va atrasado (Codex r2 #2, r3 #2). Avisar la misma cantidad no cambia nada.
                noEsCantidad(nuevo) -> {
                    sorda = true
                    texto = alTocarla.toString()
                    onCantidad(alTocarla)
                }
                else -> {
                    texto = soloDigitosDeCantidad(nuevo)
                    cantidadTecleada(texto)?.let(onCantidad)
                }
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = color, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(color),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { foco.clearFocus() }),
        // Al entrar se vacía; al salir enseña la cantidad real (una línea que no cambia, como la ya enviada, no miente).
        modifier = modifier.width(48.dp)
            // Un Enter de teclado (o de la pistola) se queda aquí: se traga al bajar y suelta la casilla al SUBIR. Si la
            // soltara al bajar, el foco brincaría a otro botón y el «subir» lo picaría (Buscar en la D3; Codex r2 #6).
            .onPreviewKeyEvent { e ->
                if (e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) foco.clearFocus()
                true
            }
            .onFocusChanged {
            if (it.isFocused) {
                CasillaDeCantidad.tomar(yo)
                alTocarla = cantidad
                sorda = false
                texto = ""
            } else {
                CasillaDeCantidad.soltar(yo)
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
