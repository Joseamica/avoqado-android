package com.avoqado.pos.tables.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
import com.avoqado.pos.designsystem.components.ImmersiveWindow
import com.avoqado.pos.pos.presentation.product.NoteSubView

/**
 * «Nota del artículo» para una línea de mesa que todavía no se envía (p. ej. «sin aceite»). Es la misma sub-vista
 * del panel de producto y del carrito de Mostrador, en el mismo contenedor: panel lateral en tablet, hoja en teléfono.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotaDeLineaPendiente(
    isTablet: Boolean,
    nota: String,
    onGuardar: (String?) -> Unit,
    onCerrar: () -> Unit,
) {
    val contenido = @Composable {
        NoteSubView(currentNote = nota, onSave = { onGuardar(it) }, onClear = { onGuardar(null) }, onBack = onCerrar)
    }
    if (isTablet) {
        // En tablet es un panel, no una hoja modal: sin esto, Atrás llegaba a la cuenta y ofrecía «Descartar y salir».
        androidx.activity.compose.BackHandler(onBack = onCerrar)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(onClick = onCerrar),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(400.dp)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .shadow(8.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(enabled = false, onClick = {}),
            ) { contenido() }
        }
    } else {
        AvoqadoModalBottomSheet(onDismissRequest = onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ImmersiveWindow()
            contenido()
        }
    }
}
