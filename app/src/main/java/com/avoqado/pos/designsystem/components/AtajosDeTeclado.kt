package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Atajos de teclado del POS de Windows (founder, 19-oct-2026: «solo en Windows», fijos). Los cajeros con mouse y
 * teclado cobran sin soltar el teclado: F12 cobra, F5 efectivo, F4 terminal, Esc regresa (las teclas viven en cada
 * pantalla). En Android no se registra nada: [AtajoDeTeclado] sólo actúa con [LocalEsEscritorio].
 *
 * La ventana de Windows (`desktop/.../Main.kt`) llama a [atender] con cada tecla que la pantalla no usó. Gana el
 * atajo registrado más recientemente (la pantalla de encima). Mientras haya CUALQUIER [BarreraDeAtajos] (una hoja,
 * un diálogo, un aviso o un panel encima) ninguna tecla responde: el orden en que se registran no es el orden en que
 * se ven (un aviso que ya estaba puede quedar encima de una pantalla que se registra después; Codex r1 #4).
 */
object AtajosDeTeclado {
    /** `tecla == null` = barrera. */
    private class Registro(val tecla: Key?, val intentar: () -> Boolean)

    private val pila = mutableListOf<Registro>()

    /** true = la tecla era de un atajo activo y ya se hizo lo de su botón. */
    fun atender(tecla: Key): Boolean {
        val vigentes = pila.toList()
        if (vigentes.any { it.tecla == null }) return false
        return vigentes.asReversed().any { it.tecla == tecla && it.intentar() }
    }

    @Composable
    internal fun Registrar(tecla: Key?, activo: Boolean, accion: () -> Unit) {
        if (!LocalEsEscritorio.current) return
        val activoAhora by rememberUpdatedState(activo)
        val accionAhora by rememberUpdatedState(accion)
        DisposableEffect(tecla) {
            val registro = Registro(tecla) { activoAhora.also { if (it) accionAhora() } }
            pila += registro
            onDispose { pila -= registro }
        }
    }
}

/** La [tecla] hace lo mismo que el clic en su botón mientras esta pantalla esté a la vista. Apagado = como si no existiera. */
@Composable
fun AtajoDeTeclado(tecla: Key, activo: Boolean = true, accion: () -> Unit) =
    AtajosDeTeclado.Registrar(tecla, activo, accion)

/** Una hoja, diálogo o panel encima: ninguna tecla de atajo responde hasta que se cierra. */
@Composable
fun BarreraDeAtajos() = AtajosDeTeclado.Registrar(tecla = null, activo = true) {}

/**
 * En el cobro de Windows sólo las teclas F y el mouse cobran: Enter y Espacio no «pican» el botón que quedó con el foco.
 * La pistola termina cada código con Enter, y con una terminal o un monto enfocados eso mandaba el cobro (Codex r5). Va
 * en cada control que registra dinero o manda un cobro, ANTES de su `clickable`; los demás siguen con el teclado (r6).
 */
fun Modifier.sinTeclasDeClic(enWindows: Boolean): Modifier =
    if (!enWindows) this else onPreviewKeyEvent { it.key == Key.Enter || it.key == Key.NumPadEnter || it.key == Key.Spacebar }

/**
 * Mientras [cobroAbierto], lo de atrás (la venta) sólo recibe las teclas de [pasaDetrasDelCobro]: un producto que quedó con
 * el foco se «picaba» con Enter —la pistola termina cada código con Enter— y entraba al carrito sin cobrarse (pendientes,
 * Codex r1 #1). Va en la raíz de la venta, hermana del cobro (en iOS el cobro es otra pantalla y no hace falta).
 */
fun Modifier.sinTeclasDetrasDelCobro(cobroAbierto: () -> Boolean): Modifier =
    onPreviewKeyEvent { cobroAbierto() && !pasaDetrasDelCobro(it) }

/**
 * Lista corta de lo que pasa: atajos (F1–F12, Esc: los atiende la ventana), las que mueven el foco hacia el cobro (Tab,
 * flechas, Inicio/Fin, Re/Av Pág) y las del sistema (volumen, atrás). Todo lo demás se frena. Adivinar «qué escribe» por
 * el carácter falló dos veces: en Windows las F y las flechas traen `CHAR_UNDEFINED` y en Android el centro del D-pad
 * trae 0, y las dos «picaban» o se perdían (pendientes, prueba en Windows real y Codex r3).
 */
internal fun pasaDetrasDelCobro(e: KeyEvent): Boolean = e.key in TECLAS_QUE_PASAN_DETRAS_DEL_COBRO

private val TECLAS_QUE_PASAN_DETRAS_DEL_COBRO = setOf(
    Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12, Key.Escape,
    Key.Tab, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown,
    Key.VolumeUp, Key.VolumeDown, Key.VolumeMute, Key.Back,
)

/** La tecla en chiquito a la derecha de su botón. Sólo en Windows. */
@Composable
fun TeclaDeAtajo(
    texto: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (!LocalEsEscritorio.current) return
    Text(
        text = texto,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
            .padding(horizontal = AvoqadoTheme.spacing.xxs, vertical = 1.dp),
    )
}
