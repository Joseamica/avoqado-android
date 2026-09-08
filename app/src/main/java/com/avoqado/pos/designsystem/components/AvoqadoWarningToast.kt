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
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning
import kotlinx.coroutines.delay

/**
 * Aviso ámbar NO bloqueante — el hermano de advertencia de [AvoqadoSuccessToast]
 * (misma anatomía y animación; cambia el color y el icono).
 *
 * Úsalo cuando una acción SÍ procedió pero dejó algo que el usuario debe saber
 * — p. ej. vender un producto que marcaba 0 ("quedará en negativo, revisa tus
 * existencias"). NUNCA para errores que detienen el flujo: eso va inline en el
 * formulario, como manda la regla de la casa.
 *
 * Auto-dismiss un poco más largo que el de éxito: hay texto que leer.
 *
 * @param primaryLabel botón primario OPCIONAL (p. ej. "Volver a imprimir"). Requiere
 *   [onPrimary]; sin los dos, no se dibuja nada extra — así ningún consumidor existente
 *   cambia con sólo actualizar el componente.
 * @param primaryLoading el botón primario está OCUPADO (p. ej. un reintento manual ya en
 *   vuelo) — se deshabilita y muestra su spinner en vez de aceptar otro toque. Sin esto un
 *   doble toque sobre "Volver a imprimir" podía disparar DOS ciclos de reintento en paralelo
 *   y duplicar el ticket de cocina.
 * @param secondaryLabel acción secundaria OPCIONAL, de texto (p. ej. "Ya la canté"). Cierra
 *   el aviso igual que [onDismiss] — es otra forma de decir "ya lo vi", no una acción propia.
 *
 * 🔴 Con un botón presente el aviso deja de autodesaparecer: un "Volver a imprimir" que se
 * esfuma en 2.6 s mientras el cajero todavía está leyendo la causa es el mismo bug que esto
 * vino a arreglar (Testarudo, 2026-08-31) — el cajero se queda sin poder actuar.
 */
@Composable
fun AvoqadoWarningToast(
    message: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    durationMs: Long = 2600L,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    primaryLoading: Boolean = false,
    secondaryLabel: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    val esAccionable = (primaryLabel != null && onPrimary != null) || secondaryLabel != null

    // 🔴 Ronda de arreglo 1 (hallazgo del revisor): la llave depende del CONTENIDO, no de
    // `Unit` — si el aviso se recompone con un texto NUEVO (p. ej. "intento 2 de 6" → "intento
    // 3 de 6"), el timer de autodesaparición viejo no puede seguir corriendo con la cuenta
    // regresiva de la versión anterior: eso producía el parpadeo (aparece, se desvanece a los
    // ~2.6s, reaparece) durante los ~50s de reintento. Con la llave nueva, cada mensaje
    // distinto arranca SU PROPIA cuenta — el timer viejo se cancela sin llegar a disparar su
    // `onDismiss` sobre un aviso que, para entonces, ya cambió.
    LaunchedEffect(message, subtitle) {
        visible = true
        if (!esAccionable) {
            delay(durationMs)
            onDismiss()
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.6f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "warningScale",
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
                        .background(Warning, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PriorityHigh,
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

                if (primaryLabel != null && onPrimary != null) {
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                    PrimaryButton(
                        text = primaryLabel,
                        onClick = onPrimary,
                        fullWidth = true,
                        isLoading = primaryLoading,
                    )
                }

                if (secondaryLabel != null) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = secondaryLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
