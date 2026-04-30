package com.avoqado.pos.reservations.presentation.create.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoFullscreenHeader
import com.avoqado.pos.designsystem.components.FullscreenHeaderNav
import com.avoqado.pos.reservations.domain.CreateStep

@Composable
fun StepperHeader(
    step: CreateStep,
    canContinue: Boolean,
    isFirstStep: Boolean,
    isLastStep: Boolean,
    isSubmitting: Boolean,
    isEditing: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onContinue: () -> Unit,
) {
    val title = when (step) {
        CreateStep.CUSTOMER -> "Cliente"
        CreateStep.SERVICE -> "Servicio"
        CreateStep.DATETIME -> "Fecha y hora"
        CreateStep.DETAILS -> "Detalles"
        CreateStep.CONFIRM -> "Confirmar"
    }
    val finalLabel = if (isEditing) "Guardar" else "Crear"
    val actionLabel = if (isLastStep) finalLabel else "Continuar"

    Column {
        AvoqadoFullscreenHeader(
            title = title,
            onNav = if (isFirstStep) onClose else onBack,
            navStyle = if (isFirstStep) FullscreenHeaderNav.CLOSE else FullscreenHeaderNav.BACK,
            primaryActionText = actionLabel,
            onPrimaryAction = onContinue,
            primaryActionEnabled = canContinue && !isSubmitting,
        )
        StepDots(current = step.ordinal, total = CreateStep.entries.size)
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { i ->
            val activeColor = MaterialTheme.colorScheme.primary
            val inactiveColor = MaterialTheme.colorScheme.outlineVariant
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .background(if (i <= current) activeColor else inactiveColor, CircleShape),
            )
        }
    }
}
