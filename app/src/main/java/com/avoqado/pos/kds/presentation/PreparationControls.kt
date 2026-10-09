package com.avoqado.pos.kds.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.MotionDurationScale
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.*
import com.avoqado.pos.kds.domain.*
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.PrimaryButton

/** The label carries meaning; the accent adds a quick visual cue in both themes. */
@Composable
fun PreparationBadges(counts: PreparationCounts, pending: Boolean = false, pulseUrgent: Boolean = false) {
    val motion = (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
    var highlighted by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (highlighted) .3f else .14f, tween(400), label = "Aviso urgente")
    LaunchedEffect(counts.urgency?.requestId, counts.urgency?.acknowledged, pulseUrgent, motion) {
        highlighted = false
        if (pulseUrgent && motion && counts.urgent && counts.urgency?.acknowledged == false) {
            repeat(3) { highlighted = true; delay(800); highlighted = false; delay(800) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs)) {
        if (counts.urgent) Text("Urgente" + if (counts.urgency?.acknowledged == true) " · Visto" else "",
            modifier = Modifier.background(MaterialTheme.colorScheme.error.copy(alpha = alpha), MaterialTheme.shapes.small)
                .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = AvoqadoTheme.spacing.xxs),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        PreparationState.entries.filter { counts[it] > 0 }.forEach { state ->
            val accent = when (state) {
                PreparationState.HELD -> MaterialTheme.colorScheme.onSurfaceVariant
                PreparationState.PENDING -> Warning
                PreparationState.PREPARING -> Info
                PreparationState.READY -> Success
                PreparationState.DELIVERED -> ActionTeal
                PreparationState.CANCELLED -> Error
            }
            Row(Modifier.background(accent.copy(alpha = .14f), MaterialTheme.shapes.small)
                .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = AvoqadoTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Box(Modifier.size(8.dp).background(accent, CircleShape))
                Text("${counts[state]} · ${state.label}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (pending) Text("Pendiente de sincronizar", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PreparationControls(productName: String, counts: PreparationCounts,
    actions: List<PreparationAction>, can: (PreparationAction) -> Boolean,
    onAction: (PreparationAction, Int, PreparationState?, String?) -> Unit,
    modifier: Modifier = Modifier,
    limit: ((PreparationAction) -> Int)? = null,
) {
    var selected by remember { mutableStateOf<PreparationAction?>(null) }
    var from by remember { mutableStateOf<PreparationState?>(null) }
    FlowRow(modifier.fillMaxWidth(), maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
        actions.filter { can(it) }.forEach { action ->
            val available = limit?.invoke(action) ?: counts.available(action)
            if (available > 0) OutlinedButton(onClick = {
                if (action == PreparationAction.ACK_URGENT) onAction(action, 1, null, null)
                else { selected = action; from = action.defaultSource }
            },
                modifier = Modifier.weight(1f).testTag("preparation-${action.name}-$productName")) {
                Text(if (action.priority) action.label else "${action.label} · $available", textAlign = TextAlign.Center)
            }
        }
    }
    selected?.let { action ->
        val source = from
        val maximum = limit?.invoke(action) ?: counts.available(action)
        var quantity by remember(action, source, maximum) { mutableIntStateOf(maximum) }
        var reason by remember(action, source) { mutableStateOf("") }
        val needsReason = action == PreparationAction.CANCEL || action == PreparationAction.REOPEN
        AvoqadoDialog(onDismiss = { selected = null },
            title = "${action.label}: $productName",
            centerTitle = true,
            actionButton = { PrimaryButton(text = if (action.priority) action.label else "${action.label} $quantity", fullWidth = true,
                enabled = maximum > 0 && (!needsReason || reason.isNotBlank()), onClick = {
                    onAction(action, quantity, source, reason.takeIf { needsReason }); selected = null
                }) }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
                Text(if (action.priority) "Se quita la prioridad. El producto conserva su tiempo y avance; no vuelve a quedar retenido."
                    else "${source?.label ?: "Preparación de este producto"} · $maximum ${if (maximum == 1) "disponible" else "disponibles"}",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!action.priority) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg, Alignment.CenterHorizontally)) {
                    PreparationQuantityButton(increase = false, enabled = quantity > 1) { quantity-- }
                    Text("$quantity", style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.width(AvoqadoTheme.dimensions.buttonLarge), textAlign = TextAlign.Center)
                    PreparationQuantityButton(increase = true, enabled = quantity < maximum) { quantity++ }
                }
                if (needsReason) {
                    AvoqadoPillTextField(value = reason, onValueChange = { if (it.length <= 500) reason = it }, placeholder = "Motivo")
                    Text("Esta acción cambia la preparación. Para modificar la venta, usa las acciones de la cuenta.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PreparationQuantityButton(increase: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) .45f else .2f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
        ),
        modifier = Modifier.size(AvoqadoTheme.dimensions.buttonLarge)
            .semantics { contentDescription = if (increase) "Aumentar cantidad" else "Disminuir cantidad" },
    ) {
        Icon(if (increase) Icons.Default.Add else Icons.Default.Remove, contentDescription = null,
            modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge))
    }
}
