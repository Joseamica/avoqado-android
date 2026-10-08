package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** true en el POS de Windows (lo pone `desktop/`). En Android siempre false. */
val LocalEsEscritorio = staticCompositionLocalOf { false }

/**
 * La hoja modal de la app. En Android es el `ModalBottomSheet` de Material tal cual.
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
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalEsEscritorio.current) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            containerColor = containerColor,
            dragHandle = dragHandle,
            content = content,
        )
        return
    }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = containerColor,
        dragHandle = {
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
        },
        content = content,
    )
}
