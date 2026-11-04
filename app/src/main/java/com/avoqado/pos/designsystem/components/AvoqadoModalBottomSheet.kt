package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** true en el POS de Windows (lo pone `desktop/`). En Android siempre false. */
val LocalEsEscritorio = staticCompositionLocalOf { false }

/**
 * La hoja modal de la app. En Android es el `ModalBottomSheet` de Material, con la pantalla completa del POS ya puesta
 * (`ImmersiveWindow`), igual que `AvoqadoDialog`: antes cada hoja tenía que acordarse de llamarla, y 10 no lo hacían
 * (la de «Agregar cliente» en una venta ya cobrada dejaba ver la barra de Android en el SAMSUNG; en la D3, el dock de
 * Sunmi; 1-nov). Las hojas que todavía la llaman por su cuenta no cambian: es la misma ventana y sólo se aplica una vez.
 * En Windows no hay barras de Android y no se llama.
 *
 * En Windows lleva una **X** arriba a la derecha en lugar de la barrita de arrastre: con mouse no hay gesto de «bajar
 * la hoja» ni botón de atrás, y arrastrarla hasta abajo la dejaba fuera de la vista con la app bloqueada detrás
 * (La Galeterie, 8-oct). La X baja la hoja con su animación y luego avisa a `onDismissRequest`, igual que Esc o tocar
 * fuera de ella.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvoqadoModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    /**
     * La hoja ya trae su propia X en la cabecera: en Windows no se le agrega otra (salían dos, 19-oct). En Android no
     * cambia nada.
     */
    conXPropia: Boolean = false,
    /**
     * Una pantalla, no una hoja: todo el ancho y todo el alto, sin esquinas, y arrastrar no la cierra (seguir bajando una
     * lista que ya llegó arriba tiraría lo capturado). Se cierra con su X y con atrás. Va en esta ventana y no en un `Dialog`
     * porque la de un `Dialog` nace del alto de la configuración y por la franja se toca el tab bar (1920×972 en la Sunmi, ver
     * `TableOrderScreen`).
     */
    aPantallaCompleta: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    BarreraDeAtajos()
    val anchoMaximo = if (aPantallaCompleta) Dp.Unspecified else BottomSheetDefaults.SheetMaxWidth
    val forma = if (aPantallaCompleta) RectangleShape else BottomSheetDefaults.ExpandedShape
    val contenido: @Composable ColumnScope.() -> Unit = if (aPantallaCompleta) ({ ContenidoSinArrastre(content) }) else content
    if (!LocalEsEscritorio.current) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            sheetMaxWidth = anchoMaximo,
            shape = forma,
            containerColor = containerColor,
            dragHandle = dragHandle,
        ) {
            ImmersiveWindow()
            contenido()
        }
        return
    }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = anchoMaximo,
        shape = forma,
        containerColor = containerColor,
        dragHandle = if (conXPropia) null else ({
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), contentAlignment = Alignment.CenterEnd) {
                IconButton(
                    onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() } },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cerrar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }),
        content = contenido,
    )
}

/**
 * Material3 1.3.1 no trae cómo apagar el arrastre de la hoja. Lo que queda es que no le llegue: el `pointerInput` se queda el
 * gesto sobre lo que no se desliza, y la conexión se queda lo que una lista ya no pudo moverse.
 */
@Composable
private fun ContenidoSinArrastre(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(SobranteQueNoLlegaALaHoja)
            .pointerInput(Unit) { detectVerticalDragGestures { cambio, _ -> cambio.consume() } }
            // La barra de estado va oculta: en un celular con cámara dentro de la pantalla, el título o la ✕ quedarían debajo. Después
            // del `pointerInput`, para que esa franja tampoco arrastre la hoja.
            .displayCutoutPadding(),
        content = content,
    )
}

private val SobranteQueNoLlegaALaHoja = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
}
