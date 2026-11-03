package com.avoqado.pos.reservations.presentation.undo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoErrorToast
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.AvoqadoWarningToast
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.reservations.domain.UndoNoShowTexts

/**
 * El botón «Sí llegó». Sin red queda en gris y dice por qué (nunca en rojo: sin red es un estado, no un error).
 */
@Composable
fun SiLlegoButton(
    online: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
        OutlinedButton(
            onClick = onClick,
            enabled = online && !busy,
            shape = RoundedCornerShape(50),
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(if (busy) "…" else "↩︎ ${UndoNoShowTexts.BUTTON}", fontWeight = FontWeight.SemiBold) }
        if (!online) {
            Text(UndoNoShowTexts.OFFLINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Caja ámbar con un título y renglones: el pendiente (franja del detalle) y lo que quedará pendiente (confirmación). */
@Composable
fun PendingPanel(title: String, lines: List<String>, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Surface(
        color = Warning.copy(alpha = 0.15f),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(AvoqadoTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            action?.invoke()
        }
    }
}

/** Confirmación, aviso del pase, resultado (con su pendiente) y error de [flow]. Va una vez por pantalla. */
@Composable
fun UndoNoShowOverlays(flow: UndoNoShowFlow) {
    val ui by flow.ui.collectAsState()
    ui.prompt?.let { p ->
        val enviando = ui.busyId != null
        AvoqadoDialog(
            title = p.title,
            onDismiss = { flow.dismiss() },
            dismissOnClickOutside = !enviando,
            actionButton = {
                PrimaryButton(text = UndoNoShowTexts.CONFIRM, onClick = flow::confirm, isLoading = enviando, enabled = !enviando, fullWidth = true)
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm), modifier = Modifier.padding(end = AvoqadoTheme.spacing.md)) {
                Text(UndoNoShowTexts.INTRO, style = MaterialTheme.typography.bodyMedium)
                p.rows.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                if (p.pending.isNotEmpty()) PendingPanel(UndoNoShowTexts.PENDING_INTRO, p.pending)
            }
        }
    }
    ui.blocked?.let { msg ->
        AvoqadoDialog(title = UndoNoShowTexts.PASS_TITLE, description = msg, onDismiss = { flow.dismiss() }) {}
    }
    ui.done?.let { done ->
        if (done.pending.isEmpty()) {
            AvoqadoSuccessToast(message = done.message, onDismiss = { flow.dismiss() })
        } else {
            // Con pendiente no basta la palomita: quien lo corrigió tiene que leer qué falta (la fila puede desaparecer).
            AvoqadoDialog(
                title = done.message,
                onDismiss = { flow.dismiss() },
                actionButton = { PrimaryButton(text = UndoNoShowTexts.OK, onClick = { flow.dismiss() }, fullWidth = true) },
            ) {
                PendingPanel(UndoNoShowTexts.PENDING_LEFT, done.pending, Modifier.padding(end = AvoqadoTheme.spacing.md))
            }
        }
    }
    ui.notice?.let { NoticeToast(it) { flow.dismiss() } }
}

/** Sin red en ámbar; un rechazo en rojo, con su título y el texto ya humanizado. */
@Composable
fun NoticeToast(n: UndoNoShowFlow.Notice, onDismiss: () -> Unit) {
    if (n.warning) AvoqadoWarningToast(message = n.title, subtitle = n.message, onDismiss = onDismiss)
    else AvoqadoErrorToast(message = n.title, subtitle = n.message, onDismiss = onDismiss)
}
