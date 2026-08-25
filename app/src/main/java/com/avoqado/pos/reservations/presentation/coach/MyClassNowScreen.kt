package com.avoqado.pos.reservations.presentation.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Mi clase ahora" — Fase 8 del kiosco.
 *
 * SÓLO LECTURA, y es la decisión de diseño principal. Quien da la clase no marca
 * asistencia desde aquí: eso lo hace el propio cliente en el kiosco, o el mostrador. Si
 * esta pantalla pudiera marcar, la instructora se volvería la recepcionista de su propia
 * clase — que es justo lo que el kiosco existe para evitar.
 *
 * Se refresca sola cada 20 s: se deja puesta en la tablet y se ve llegar a la gente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyClassNowScreen(
    onBack: () -> Unit,
    viewModel: MyClassNowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Mi clase ahora") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.myClass == null -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.error != null && state.myClass == null -> {
                    Empty(
                        title = "No se pudo cargar",
                        body = state.error ?: "",
                        actionLabel = "Reintentar",
                        onAction = viewModel::refresh,
                    )
                }

                // 🔴 Sin clase NO es un error: es el estado normal casi todo el día.
                state.noClass -> {
                    Empty(
                        title = "No tienes clase ahora",
                        body = "Esta pantalla se enciende sola cuando tu clase está por empezar.",
                        actionLabel = "Actualizar",
                        onAction = viewModel::refresh,
                    )
                }

                else -> state.myClass?.let { Loaded(it, viewModel.zone) }
            }
        }
    }
}

@Composable
private fun Loaded(c: com.avoqado.pos.reservations.data.CoachClassApi.MyClass, zone: ZoneId) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.lg)) {
            Text(c.productName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            Text(
                "${hora(c.startsAt, zone)} · ${c.checkedIn} de ${c.booked} ya llegaron",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        if (c.attendees.isEmpty()) {
            Empty(
                title = "Nadie reservó todavía",
                body = "Cuando alguien aparte su lugar, lo verás aquí.",
                actionLabel = null,
                onAction = {},
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = AvoqadoTheme.spacing.sm)) {
                items(c.attendees, key = { it.reservationId }) { a ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.displayName, style = MaterialTheme.typography.bodyLarge)
                            // El lugar sólo si el negocio tiene acomodo configurado — que
                            // hoy es lo normal que NO lo tenga.
                            a.spotLabel?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (a.checkedIn) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = "Ya llegó", tint = Success)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(title: String, body: String, actionLabel: String?, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(AvoqadoTheme.spacing.xl),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null) {
                Spacer(Modifier.height(AvoqadoTheme.spacing.md))
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * 🔴 Se formatea en la zona del NEGOCIO, que llega del ViewModel — nunca en la del
 * aparato. Una tablet mal configurada pintaría la clase de las 7 a otra hora.
 */
private fun hora(iso: String, zone: ZoneId): String = runCatching {
    DateTimeFormatter.ofPattern("h:mm a").format(Instant.parse(iso).atZone(zone))
}.getOrDefault("")
