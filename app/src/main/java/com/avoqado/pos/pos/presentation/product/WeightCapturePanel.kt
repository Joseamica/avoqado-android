// Venta por peso (báscula) — panel de captura, hermano de [ProductDetailPanel] (mismo overlay +
// panel derecho de 400dp en tablet, full-screen en teléfono). Mientras el perfil físico de la
// terminal no esté certificado, el operador captura el peso a mano; el total en vivo =
// round(weightKg × precio/kg), la misma aritmética half-up que aplica el server.
//
// Un producto por peso NUNCA abre modificadores (el peso manda en el MVP): el tap del producto
// enruta aquí directo desde CheckoutScreen.handleProductTap.
package com.avoqado.pos.pos.presentation.product

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.MAX_WEIGHT_KG
import com.avoqado.pos.pos.data.model.MIN_WEIGHT_KG
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.availableWeightLabel
import com.avoqado.pos.pos.data.model.formatWeightKg
import com.avoqado.pos.pos.data.model.parseWeightKg
import com.avoqado.pos.pos.data.model.weightTotalCents
import com.avoqado.pos.scale.ScaleConnectionState
import java.util.Locale

private fun money(cents: Int): String = String.format(Locale.US, "$%.2f", cents / 100.0)

@Composable
fun WeightCapturePanel(
    product: Product,
    isTablet: Boolean,
    scaleState: ScaleConnectionState = ScaleConnectionState.NotConfigured,
    onRetryScale: () -> Unit = {},
    onAdd: (weightKg: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    if (isTablet) {
        // Side panel overlay from the right (mismo blueprint que ProductDetailPanel/CartItemDetailPanel).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(onClick = onDismiss),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(400.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            ) {
                WeightCaptureContent(
                    product = product,
                    scaleState = scaleState,
                    onRetryScale = onRetryScale,
                    onAdd = onAdd,
                    onDismiss = onDismiss,
                )
            }
        }
    } else {
        // Phone: full-screen overlay.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            WeightCaptureContent(
                product = product,
                scaleState = scaleState,
                onRetryScale = onRetryScale,
                onAdd = onAdd,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun WeightCaptureContent(
    product: Product,
    scaleState: ScaleConnectionState,
    onRetryScale: () -> Unit,
    onAdd: (weightKg: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var weightText by remember(product.id) { mutableStateOf("") }
    val scaleConfigured = scaleState !is ScaleConnectionState.NotConfigured
    var manualMode by remember(product.id, scaleConfigured) {
        mutableStateOf(!scaleConfigured)
    }

    LaunchedEffect(scaleState, manualMode) {
        if (!manualMode) {
            weightText = when (scaleState) {
                is ScaleConnectionState.Stable -> scaleState.reading.netKg
                else -> ""
            }
        }
    }

    val weightKg = parseWeightKg(weightText)
    val totalCents = weightKg?.let { weightTotalCents(it, product.priceInCents) }
    val showError = weightText.isNotBlank() && weightKg == null

    fun submit() {
        val kg = parseWeightKg(weightText) ?: return
        onAdd(kg)
        onDismiss()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header: precio por kg + nombre + cerrar (espejo del header de ProductDetailPanel).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${money(product.priceInCents)}/kg",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Top)
                    .size(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.xl),
        ) {
            Text(
                text = "Peso",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(AvoqadoTheme.spacing.sm))

            if (scaleConfigured && !manualMode) {
                ScaleStatusCard(
                    state = scaleState,
                    onRetry = onRetryScale,
                )
                TextButton(
                    onClick = {
                        manualMode = true
                        weightText = ""
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Capturar manualmente")
                }
            } else {
                AvoqadoPillTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    placeholder = "Peso (kg)",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.testTag("weight-input"),
                )

                Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
                Text(
                    text = "Captura el peso en kilogramos (ej. 0.435). También acepta coma decimal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Cuánto queda, antes de pesar. Sin esto el mostrador emite el
                // vale a ciegas y se entera de que no alcanzaba al ir por el
                // producto.
                product.availableWeightLabel?.let { available ->
                    Text(
                        text = available,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (scaleConfigured) {
                    TextButton(
                        onClick = {
                            manualMode = false
                            weightText = ""
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Usar báscula")
                    }
                }
            }

            Spacer(Modifier.height(AvoqadoTheme.spacing.md))

            // Preview en vivo del total mientras se escribe — o el error de rango/formato.
            when {
                showError -> Text(
                    text = "Peso inválido — captura entre ${formatWeightKg(MIN_WEIGHT_KG)} y ${formatWeightKg(MAX_WEIGHT_KG)} kg",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                weightKg != null && totalCents != null -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${formatWeightKg(weightKg)} kg × ${money(product.priceInCents)}/kg",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = money(totalCents),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Aviso de stock (no bloquea — el backend es la autoridad al cobrar), espejo del
            // aviso de ProductDetailPanel.
            if (product.isOutOfStock) {
                Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = "⚠ Sin existencias — el cobro puede ser rechazado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        HorizontalDivider()
        PrimaryButton(
            text = if (totalCents != null) "Agregar • ${money(totalCents)}" else "Agregar",
            onClick = { submit() },
            enabled = weightKg != null,
            fullWidth = true,
            modifier = Modifier.padding(AvoqadoTheme.spacing.xl),
        )
    }
}

@Composable
private fun ScaleStatusCard(
    state: ScaleConnectionState,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(AvoqadoTheme.spacing.lg)
            .testTag("scale-status"),
    ) {
        when (state) {
            ScaleConnectionState.NotConfigured -> Unit
            is ScaleConnectionState.Connecting -> {
                Text(
                    text = "Conectando con ${state.profileName}…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Revisa que el cable esté conectado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is ScaleConnectionState.Ready -> {
                Text(
                    text = "${state.profileName} conectada",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Coloca el producto sobre la báscula.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is ScaleConnectionState.Unstable -> {
                Text(
                    text = "${state.reading.netKg} kg",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Peso inestable · espera para agregar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is ScaleConnectionState.Stable -> {
                Text(
                    text = "${state.reading.netKg} kg",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Lectura estable · ${state.profileName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is ScaleConnectionState.Problem -> {
                Text(
                    text = "Báscula no disponible",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Reintentar")
                }
            }
        }
    }
}
