package com.avoqado.pos.referrals.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.referrals.domain.model.ValidationResult

/**
 * UI states the section can be in. Kept separate from [ValidationResult] so
 * the section can also surface in-flight + idle states without expanding
 * the domain enum.
 */
sealed class ReferralCaptureUiState {
    data object Idle : ReferralCaptureUiState()
    data object Validating : ReferralCaptureUiState()
    data class Valid(val referrerName: String, val discountPercent: Int) : ReferralCaptureUiState()
    data class Invalid(val reason: ValidationResult.Reason) : ReferralCaptureUiState()
}

/**
 * "¿Te recomendó alguien?" referral capture section, embedded in the cart
 * panel of the checkout flow.
 *
 * **States:**
 * - [ReferralCaptureUiState.Idle] — empty input + "Validar" button (disabled
 *   until a code is typed).
 * - [ReferralCaptureUiState.Validating] — spinner overlay on the button.
 * - [ReferralCaptureUiState.Valid] — green confirmation + "Quitar" CTA.
 * - [ReferralCaptureUiState.Invalid] — red copy + reason. The EXISTING_CUSTOMER
 *   case adds a "Forzar atribución" outlined button (disabled — v1 placeholder
 *   for the manager-PIN override flow that lands in v2).
 *
 * Stateless composable — all data flows down via [code] + [uiState], all
 * actions bubble up via the lambdas. Hosted by [CartPanelView] (Plan 5B
 * integration point).
 *
 * @param customerSelected required for validation. When false, the section
 *   shows a hint to pick a customer first and disables the input.
 */
@Composable
fun ReferralCaptureSection(
    code: String,
    uiState: ReferralCaptureUiState,
    customerSelected: Boolean,
    onCodeChange: (String) -> Unit,
    onValidate: () -> Unit,
    onClear: () -> Unit,
    onForceOverride: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        // Header — gift icon + label
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CardGiftcard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xs))
            Text(
                text = "¿Te recomendó alguien?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (!customerSelected) {
            Text(
                text = "Selecciona un cliente primero para usar un código de referido.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        when (val state = uiState) {
            ReferralCaptureUiState.Idle,
            ReferralCaptureUiState.Validating -> InputRow(
                code = code,
                onCodeChange = onCodeChange,
                onValidate = {
                    keyboard?.hide()
                    onValidate()
                },
                isValidating = state is ReferralCaptureUiState.Validating,
            )

            is ReferralCaptureUiState.Valid -> ValidBanner(
                referrerName = state.referrerName,
                discountPercent = state.discountPercent,
                onClear = onClear,
            )

            is ReferralCaptureUiState.Invalid -> InvalidBanner(
                reason = state.reason,
                onTryAgain = onClear,
                onForceOverride = onForceOverride,
            )
        }
    }
}

@Composable
private fun InputRow(
    code: String,
    onCodeChange: (String) -> Unit,
    onValidate: () -> Unit,
    isValidating: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { raw -> onCodeChange(raw.uppercase().take(64)) },
            placeholder = { Text("CÓDIGO") },
            singleLine = true,
            enabled = !isValidating,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.Text,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (code.isNotBlank()) onValidate() },
            ),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onValidate,
            enabled = !isValidating && code.isNotBlank(),
            modifier = Modifier.height(52.dp),
        ) {
            if (isValidating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Validar")
            }
        }
    }
}

@Composable
private fun ValidBanner(
    referrerName: String,
    discountPercent: Int,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
            .background(Color(0xFF22C55E).copy(alpha = 0.12f))
            .padding(AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF15803D),
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Referido por $referrerName",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Se aplicó $discountPercent% descuento",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
        OutlinedButton(
            onClick = onClear,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text("Quitar")
        }
    }
}

@Composable
private fun InvalidBanner(
    reason: ValidationResult.Reason,
    onTryAgain: () -> Unit,
    onForceOverride: () -> Unit,
) {
    val errorColor = Color(0xFFDC2626)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
            .background(errorColor.copy(alpha = 0.08f))
            .padding(AvoqadoTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = errorColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reasonHeadline(reason),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = errorColor,
                )
                Text(
                    text = reasonExplanation(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            OutlinedButton(onClick = onTryAgain, modifier = Modifier.weight(1f)) {
                Text("Intentar otro código")
            }
            if (reason == ValidationResult.Reason.EXISTING_CUSTOMER) {
                // v1 placeholder — the manager-PIN override flow lands in v2.
                // We render the affordance (so cashiers know it exists) but
                // keep it disabled. See ReferralsRepository.forceOverride.
                Button(
                    onClick = onForceOverride,
                    enabled = false,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Forzar atribución (próximamente)")
                }
            }
        }
        if (reason == ValidationResult.Reason.EXISTING_CUSTOMER) {
            Text(
                text = "Requiere autorización de un manager.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun reasonHeadline(reason: ValidationResult.Reason): String = when (reason) {
    ValidationResult.Reason.PROGRAM_INACTIVE -> "Programa inactivo"
    ValidationResult.Reason.CODE_NOT_FOUND -> "Código no encontrado"
    ValidationResult.Reason.SELF_REFERRAL -> "No puedes referirte a ti mismo"
    ValidationResult.Reason.EXISTING_CUSTOMER -> "Cliente recurrente"
    ValidationResult.Reason.UNKNOWN -> "No se pudo validar el código"
}

private fun reasonExplanation(reason: ValidationResult.Reason): String = when (reason) {
    ValidationResult.Reason.PROGRAM_INACTIVE -> "Este venue no tiene el programa de referidos activo."
    ValidationResult.Reason.CODE_NOT_FOUND -> "No encontramos a quién pertenece este código."
    ValidationResult.Reason.SELF_REFERRAL -> "El código pertenece al mismo cliente seleccionado."
    ValidationResult.Reason.EXISTING_CUSTOMER -> "Este cliente ya compró aquí antes. Necesitas a un manager para forzar la atribución."
    ValidationResult.Reason.UNKNOWN -> "Intenta de nuevo o usa otro código."
}

// ──────────────────────────────────────────────────────────────────────────
// Previews
// ──────────────────────────────────────────────────────────────────────────

@Preview(name = "Referral - Idle", showBackground = true)
@Composable
private fun PreviewIdle() {
    Box(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
        ReferralCaptureSection(
            code = "",
            uiState = ReferralCaptureUiState.Idle,
            customerSelected = true,
            onCodeChange = {},
            onValidate = {},
            onClear = {},
            onForceOverride = {},
        )
    }
}

@Preview(name = "Referral - Valid", showBackground = true)
@Composable
private fun PreviewValid() {
    Box(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
        ReferralCaptureSection(
            code = "ANA-2026",
            uiState = ReferralCaptureUiState.Valid(
                referrerName = "Ana López",
                discountPercent = 10,
            ),
            customerSelected = true,
            onCodeChange = {},
            onValidate = {},
            onClear = {},
            onForceOverride = {},
        )
    }
}

@Preview(name = "Referral - Existing customer", showBackground = true)
@Composable
private fun PreviewExistingCustomer() {
    Box(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
        ReferralCaptureSection(
            code = "ANA-2026",
            uiState = ReferralCaptureUiState.Invalid(
                ValidationResult.Reason.EXISTING_CUSTOMER,
            ),
            customerSelected = true,
            onCodeChange = {},
            onValidate = {},
            onClear = {},
            onForceOverride = {},
        )
    }
}

@Preview(name = "Referral - No customer", showBackground = true)
@Composable
private fun PreviewNoCustomer() {
    Box(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
        ReferralCaptureSection(
            code = "",
            uiState = ReferralCaptureUiState.Idle,
            customerSelected = false,
            onCodeChange = {},
            onValidate = {},
            onClear = {},
            onForceOverride = {},
        )
    }
}
