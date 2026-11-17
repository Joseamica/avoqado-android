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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * ¿Hay una casilla de cantidad con el foco? La leen los lectores de la pistola: ANDROID (`LectorHidBus`, y la hoja del
 * teléfono) no le deja pasar teclas físicas, y WINDOWS (`LectorDePistola`) decide por ella qué es pistola. También recibe
 * cada código leído ([avisarCodigo]) para la casilla activa. Una dueña a la vez: soltar sólo suelta si sigue siendo suya.
 */
object CasillaDeCantidad {
    @Volatile private var duena: Any? = null
    @Volatile private var alLeer: ((String) -> Unit)? = null
    val activa: Boolean get() = duena != null
    fun tomar(quien: Any, alLeerCodigo: (String) -> Unit) { duena = quien; alLeer = alLeerCodigo }
    fun soltar(quien: Any) { if (duena === quien) { duena = null; alLeer = null } }
    /** La pistola (o el lector integrado de una Sunmi) leyó [codigo]: la casilla activa revisa si se le coló. */
    fun avisarCodigo(codigo: String) { alLeer?.invoke(codigo) }
}

/** Cuánto vale un código avisado para compararlo con lo que la casilla recibe después (el lector de una Sunmi avisa y teclea). */
private const val VIGENCIA_DEL_CODIGO_MS = 2_000L

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
 * 🔴 La pistola nunca cambia la cantidad (Codex r1–r5; founder, 17-nov: «tienes que arreglarlo»). Decide quien ya sabe
 * separar pistola de persona, sin borrarle nada a la persona (un detector de ritmo aquí deshacía un «24» rápido, r5 #3):
 * - ANDROID: con la casilla activa ([CasillaDeCantidad]) no le llegan teclas físicas, ni en la ventana principal
 *   (`LectorHidBus`) ni en la hoja del teléfono (`ProductDetailPanel`); sólo vale el teclado de la pantalla.
 * - WINDOWS: `LectorDePistola` retiene y decide; con la casilla, una ráfaga de 1 carácter o más que cierra con Enter/Tab ya
 *   es pistola, y antes de un clic entrega lo que retenía de la persona.
 * - Lo que aun así caiga aquí (el lector integrado de una Sunmi teclea Y avisa el código): si la casilla tiene justo el
 *   código avisado, o una letra, o un 4.º dígito, regresa a la cantidad que tenía al tocarla y se queda sorda hasta que se
 *   suelte (se vuelve a escanear).
 */
@Composable
fun CantidadTecleable(cantidad: Int, onCantidad: (Int) -> Unit, modifier: Modifier = Modifier) {
    var texto by remember { mutableStateOf(cantidad.toString()) }
    // Si la cantidad cambió por fuera (el «+», otro renglón, la vuelta tras un código) la casilla la enseña; si es la que
    // ya dice lo tecleado («02» = 2) no se toca: reescribirla perdía el 0 y «0234» de la pistola acababa en 234 (Codex r4 #1).
    LaunchedEffect(cantidad) { if (cantidadTecleada(texto) != cantidad) texto = cantidad.toString() }
    var alTocarla by remember { mutableIntStateOf(cantidad) }
    var sorda by remember { mutableStateOf(false) }
    var codigoAvisado by remember { mutableStateOf<Pair<String, Long>?>(null) }   // el código y cuándo se avisó (ms)
    val aplicar by rememberUpdatedState(onCantidad)
    val yo = remember { Any() }
    // Una vez que habló la pistola, se regresa a la cantidad de antes y se ignora lo demás hasta soltar la casilla.
    fun ensordecer() {
        sorda = true
        texto = alTocarla.toString()
        aplicar(alTocarla)
    }
    // ¿Lo que tiene la casilla es justo el código que se acaba de leer? (el lector integrado lo teclea antes o después del aviso)
    fun esElCodigoLeido(): Boolean = codigoAvisado?.let { (codigo, ms) ->
        texto.isNotEmpty() && texto == codigo && System.currentTimeMillis() - ms < VIGENCIA_DEL_CODIGO_MS
    } == true
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
                noEsCantidad(nuevo) -> ensordecer()
                else -> {
                    texto = soloDigitosDeCantidad(nuevo)
                    if (esElCodigoLeido()) ensordecer() else cantidadTecleada(texto)?.let(onCantidad)
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
                if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                    if (e.type == KeyEventType.KeyUp) foco.clearFocus()
                    return@onPreviewKeyEvent true
                }
                false
            }
            .onFocusChanged {
                if (it.isFocused) {
                    CasillaDeCantidad.tomar(yo) { codigo ->
                        codigoAvisado = codigo.trim() to System.currentTimeMillis()
                        if (!sorda && esElCodigoLeido()) ensordecer()
                    }
                    alTocarla = cantidad
                    sorda = false
                    codigoAvisado = null
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
