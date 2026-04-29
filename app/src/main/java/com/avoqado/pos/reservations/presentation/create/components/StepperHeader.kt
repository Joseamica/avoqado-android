package com.avoqado.pos.reservations.presentation.create.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.domain.CreateStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepperHeader(
    step: CreateStep,
    canContinue: Boolean,
    isFirstStep: Boolean,
    isLastStep: Boolean,
    isSubmitting: Boolean,
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
    Column {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = if (isFirstStep) onClose else onBack) {
                    Icon(
                        if (isFirstStep) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (isFirstStep) "Cerrar" else "Atrás",
                    )
                }
            },
            actions = {
                FilledTonalButton(
                    onClick = onContinue,
                    enabled = canContinue && !isSubmitting,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(if (isLastStep) "Crear" else "Continuar")
                }
                Spacer(Modifier.width(8.dp))
            },
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
