package com.avoqado.pos.designsystem.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import kotlinx.coroutines.delay

/**
 * Reusable error overlay that mirrors [AvoqadoSuccessToast]. Red circle + X,
 * auto-dismisses after [durationMs].
 *
 * Use for recoverable failures that don't need a blocking decision — printer
 * not configured, receipt send failed, sync error, etc. For blocking errors
 * that require action, use [AvoqadoDialog] instead.
 */
@Composable
fun AvoqadoErrorToast(
    message: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    durationMs: Long = 2200L,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        delay(durationMs)
        onDismiss()
    }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.6f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "errorScale",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 260.dp, max = 360.dp)
                .padding(horizontal = AvoqadoTheme.spacing.xl)
                .scale(scale),
            shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.xl),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = AvoqadoTheme.spacing.xxl,
                    vertical = AvoqadoTheme.spacing.xxl,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }

                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

                Text(
                    text = message,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
