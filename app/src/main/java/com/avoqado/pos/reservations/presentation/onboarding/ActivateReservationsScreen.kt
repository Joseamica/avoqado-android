package com.avoqado.pos.reservations.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.components.PlanGate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivateReservationsScreen(
    onActivated: () -> Unit,
    onBack: () -> Unit,
    viewModel: ActivateReservationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.didSucceed) {
        if (state.didSucceed) onActivated()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activar reservas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        // Plan gate (Pro), blur-preview: when this venue's plan doesn't
        // include reservations, the real activation UI renders blurred and
        // inert behind the upsell card (entitlement decision unchanged —
        // the scaffold's back button stays usable above the gate overlay).
        PlanGate(
            locked = !viewModel.hasReservationsFeature,
            featureName = "Reservas",
            requiredTierLabel = viewModel.requiredTierLabel,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 32.dp)
                        .size(96.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Permite a tu negocio recibir citas, manejar clases y administrar tu calendario desde Avoqado.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Gratis hoy.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { viewModel.activate() },
                    enabled = !state.isActivating,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(if (state.isActivating) "Activando..." else "Activar reservas")
                }
            }
        }
    }
}
