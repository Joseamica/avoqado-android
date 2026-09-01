package com.avoqado.pos.pos.presentation.cart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.presentation.checkout.OrderDiscountsContent

/**
 * Descuentos de la CUENTA COMPLETA, abiertos desde el carrito.
 *
 * Es el equivalente del "Review sale → Add discount" de Square: con la cuenta
 * armada, el descuento del total se aplica donde el cajero ya está mirando.
 * Antes el único camino era la pestaña Shortcuts, y un venue con descuentos de
 * orden concluía que no le funcionaban (founder, 2026-09-01).
 *
 * 🔴 NO reimplementa la lista: llama a [OrderDiscountsContent], la misma que
 * pinta el atajo de Shortcuts — incluidos el descuento manual y el botón de
 * quitar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDiscountSheet(
    cartViewModel: CartViewModel,
    discountsRepository: DiscountsRepository,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.xl)
                .padding(bottom = AvoqadoTheme.spacing.xl),
        ) {
            Text(
                text = "Descuento de la cuenta",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            // Alto acotado a propósito: el contenido pide `fillMaxSize` (viene
            // de una sub-vista a pantalla completa) y dentro de una hoja eso se
            // comería la pantalla entera.
            OrderDiscountsContent(
                cartViewModel = cartViewModel,
                discountsRepository = discountsRepository,
                modifier = Modifier.heightIn(max = 520.dp),
            )
        }
    }
}
