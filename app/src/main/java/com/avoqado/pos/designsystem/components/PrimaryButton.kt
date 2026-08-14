package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * Botón primario de Avoqado (píldora, alto `buttonLarge`).
 *
 * @param destructive acción que destruye o cobra de menos —anular una cuenta,
 * borrar, dar de cortesía—. Pinta el botón con el color de error del tema en vez
 * de un `Color(0xFFD32F2F)` a mano, que era lo que se venía haciendo en los
 * diálogos de Mesas y no responde a modo claro/oscuro.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    fullWidth: Boolean = false,
    destructive: Boolean = false,
) {
    val adaptive = AvoqadoTheme.adaptive
    val container = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val content = if (destructive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(AvoqadoTheme.dimensions.buttonLarge),
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = adaptive.primaryButtonHorizontalPadding),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = content,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
