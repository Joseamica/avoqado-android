package com.avoqado.pos.cashdrawer.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.cashdrawer.data.EndOfDaySummary
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import java.util.Locale

private val WarnColor = Color(0xFFE8A33D)
private val OkColor = Color(0xFF10B981)

private fun money(cents: Int): String = "$${String.format(Locale.US, "%.2f", cents / 100.0)}"

/** Human label for a PaymentMethod enum value. Compartido con el corte de caja. */
internal fun tenderLabel(method: String): String = when (method) {
    "CASH" -> "Efectivo"
    "CREDIT_CARD" -> "Tarjeta de crédito"
    "DEBIT_CARD" -> "Tarjeta de débito"
    "DIGITAL_WALLET" -> "Billetera digital"
    "BANK_TRANSFER" -> "Transferencia"
    else -> "Otros"
}

private fun hhmm(iso: String): String = try {
    java.time.Instant.parse(iso)
        .atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
} catch (_: Exception) {
    ""
}

/**
 * "Cierre del día" (Square's end-of-day): the day's sales summary plus the
 * checklist of things blocking the close — open checks, cash drawers still
 * open, and staff still clocked in. Read-only: it tells the manager what to
 * resolve; each item is resolved in its own screen.
 */
@Composable
fun EndOfDayScreen(
    onDismiss: () -> Unit,
    viewModel: CashDrawerViewModel = hiltViewModel(),
) {
    val summary by viewModel.endOfDay.collectAsState()
    val isLoading by viewModel.isLoadingEndOfDay.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadEndOfDay() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleBackButton(onClick = onDismiss)
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Text(
                text = "Cierre del día",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (isLoading && summary == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { AvoqadoBrandLoader(size = 72.dp) }
            return
        }

        val s = summary
        if (s == null) {
            // El cierre del día se arma en el server (necesita TODAS las ventas del
            // día, no sólo las de este POS), así que sin conexión no hay nada que
            // mostrar. Decir sólo "no se pudo cargar" deja al dueño sin saber si es
            // un problema suyo, de la app, o de la red — y sin forma de reintentar.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AvoqadoTheme.spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No se pudo cargar el cierre del día",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = "Necesita conexión: reúne las ventas de todo el día, no sólo " +
                        "las de este equipo. El corte de la caja sí funciona sin conexión.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                Button(onClick = { viewModel.loadEndOfDay() }) {
                    Text("Reintentar")
                }
            }
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            StatusBanner(s)
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            ReportSectionTitle(text = "Resumen del día")
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            ReportRow(label = "Ventas totales", value = money(s.sales.totalCents), isBold = true)
            ReportRow(label = "Transacciones", value = "${s.sales.transactionCount}")
            ReportRow(label = "Ticket promedio", value = money(s.sales.averageTicketCents))
            ReportRow(label = "Propinas", value = money(s.sales.tipsCents))

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))
            ReportSectionTitle(text = "Desglose por método de pago")
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            if (s.sales.tenders.isEmpty()) {
                ReportRow(label = "Sin ventas hoy", value = money(0))
            } else {
                s.sales.tenders.forEach { t ->
                    ReportRow(label = tenderLabel(t.method), value = money(t.totalCents))
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))
            ReportSectionTitle(text = "Antes de cerrar")
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            ChecklistItem(
                icon = Icons.Filled.Receipt,
                ok = s.openChecks.count == 0,
                okText = "Sin cuentas abiertas",
                warnText = "${s.openChecks.count} cuentas abiertas (${money(s.openChecks.totalCents)})",
                detail = if (s.openChecks.count > 0) "Cóbralas o anúlalas antes de cerrar" else null,
            )
            ChecklistItem(
                icon = Icons.Filled.PointOfSale,
                ok = s.openDrawers.isEmpty(),
                okText = "Sin cajas abiertas",
                warnText = "${s.openDrawers.size} caja(s) sin cerrar",
                detail = s.openDrawers.joinToString("\n") {
                    "${it.openedByName} · abierta ${hhmm(it.openedAt)} · inicial ${money(it.startingAmountCents)}"
                }.ifEmpty { null },
            )
            ChecklistItem(
                icon = Icons.Filled.Schedule,
                ok = s.clockedInStaff.isEmpty(),
                okText = "Nadie con entrada marcada",
                warnText = "${s.clockedInStaff.size} empleado(s) con entrada marcada",
                detail = s.clockedInStaff.joinToString("\n") {
                    "${it.name} · desde ${hhmm(it.clockInTime)}" + if (it.status == "ON_BREAK") " (en descanso)" else ""
                }.ifEmpty { null },
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))
        }
    }
}

@Composable
private fun StatusBanner(s: EndOfDaySummary) {
    val ok = s.readyToClose
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background((if (ok) OkColor else WarnColor).copy(alpha = 0.12f))
            .padding(AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) OkColor else WarnColor,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
        Column {
            Text(
                text = if (ok) "Todo listo para cerrar" else "Hay pendientes por resolver",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (ok) {
                    "No hay cuentas ni cajas abiertas."
                } else {
                    "Revisa la lista de abajo antes de cerrar el día."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChecklistItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ok: Boolean,
    okText: String,
    warnText: String,
    detail: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.sm),
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else icon,
            contentDescription = null,
            tint = if (ok) OkColor else WarnColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (ok) okText else warnText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (ok) FontWeight.Normal else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!ok && detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
