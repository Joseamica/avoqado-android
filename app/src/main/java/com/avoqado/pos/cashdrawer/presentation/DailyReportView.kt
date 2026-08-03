package com.avoqado.pos.cashdrawer.presentation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error
import com.avoqado.pos.designsystem.theme.Success
import java.util.Locale
import kotlin.math.abs

// MARK: - Daily Report (Z-Report / Corte de Caja)

@Composable
fun DailyReportView(
    session: CashDrawerSessionEntity,
    events: List<CashDrawerEventEntity>,
    venueName: String = "Avoqado",
    // Payment-method breakdown from the server (card + cash + other) for the
    // session window. Empty = not loaded / offline → falls back to cash-only.
    tenderBreakdown: List<com.avoqado.pos.cashdrawer.data.CashDrawerRepository.TenderRow> = emptyList(),
    isPrinting: Boolean = false,
    onPrint: () -> Unit = {},
    /**
     * Reintentar la consulta del desglose por método.
     *
     * El corte definitivo es el papel que el negocio ARCHIVA, y basta un bache de
     * WiFi de segundos para que salga sin el desglose — pasó en la D3: la red se
     * cayó justo al cerrar la caja y el ticket se imprimió sólo con el efectivo.
     * Sin una forma de reintentar, la única salida era volver a buscarlo en el
     * historial sin saber que eso ayudaría.
     */
    onRetryBreakdown: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val zone = com.avoqado.pos.core.util.VenueTimeZone.zoneId()
    val datePattern = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("es", "MX"))
    val timePattern = java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale("es", "MX"))
    fun formatDate(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).format(datePattern)
    fun formatTime(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).format(timePattern)

    // Compute totals from events
    val cashSalesCents = events
        .filter { it.type == CashDrawerEventType.CASH_SALE.name }
        .sumOf { it.amountCents }
    val payInsCents = events
        .filter { it.type == CashDrawerEventType.PAY_IN.name }
        .sumOf { it.amountCents }
    val payOutsCents = events
        .filter { it.type == CashDrawerEventType.PAY_OUT.name }
        .sumOf { it.amountCents }

    // Payment-method breakdown: prefer the server tender data (all methods),
    // fall back to local cash-only when it hasn't loaded (offline).
    val cardCents = tenderBreakdown
        .filter { it.method == "CREDIT_CARD" || it.method == "DEBIT_CARD" }
        .sumOf { it.totalCents }
    val breakdownCashCents = tenderBreakdown.firstOrNull { it.method == "CASH" }?.totalCents
    val otherCents = tenderBreakdown
        .filter { it.method != "CASH" && it.method != "CREDIT_CARD" && it.method != "DEBIT_CARD" }
        .sumOf { it.totalCents }
    val displayCashCents = breakdownCashCents ?: cashSalesCents
    val hasServerBreakdown = tenderBreakdown.isNotEmpty()

    val transactionCount = events.count { it.type == CashDrawerEventType.CASH_SALE.name }
    // Total sales = all tenders when the server breakdown is available.
    val totalSalesCents = if (hasServerBreakdown) tenderBreakdown.sumOf { it.totalCents } else cashSalesCents
    val avgTicketCents = if (transactionCount > 0) totalSalesCents / transactionCount else 0

    val expectedCents = session.startingAmountCents + cashSalesCents + payInsCents - payOutsCents
    val actualCents = session.actualAmountCents ?: 0
    val differenceCents = actualCents - expectedCents

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleBackButton(onClick = onDismiss)
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Text(
                text = "Corte de caja",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            // Report header info
            Text(
                text = venueName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatDate(session.openedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Apertura: ${formatTime(session.openedAt)} - Cierre: ${
                    session.closedAt?.let(::formatTime) ?: "--"
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Operador: ${session.openedByName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Sales summary section
            ReportSectionTitle(text = "Resumen de ventas")
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // Sin el desglose del server sólo se conoce el efectivo, así que la
            // etiqueta lo dice: llamarle "Ventas totales" a una cifra que excluye
            // tarjeta le hace creer al dueño que vendió menos de lo que vendió.
            ReportRow(
                label = if (hasServerBreakdown) "Ventas totales" else "Ventas en efectivo",
                value = formatCurrency(totalSalesCents),
            )
            ReportRow(label = "No. de transacciones", value = "$transactionCount")
            ReportRow(label = "Ticket promedio", value = formatCurrency(avgTicketCents))

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // Payment method breakdown
            ReportSectionTitle(text = "Desglose por método de pago")
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            if (hasServerBreakdown) {
                // Un renglón por método REAL. Antes se colapsaba todo en tres cubetas
                // (Efectivo / Tarjeta / Otros), así que el dueño no podía distinguir
                // débito de crédito, ni ver por separado una transferencia o un cobro
                // con terminal ajena — y el server siempre mandó ese detalle. Para
                // cuadrar con el banco esa distinción es justo la que importa.
                tenderBreakdown
                    .sortedByDescending { it.totalCents }
                    .forEach { tender ->
                        ReportRow(
                            label = tenderLabel(tender.method),
                            value = formatCurrency(tender.totalCents),
                        )
                    }
            } else {
                ReportRow(label = "Efectivo", value = formatCurrency(displayCashCents))
                // Sin conexión no se pudo consultar el desglose. Pintar "Tarjeta $0.00"
                // aquí sería MENTIR: el POS no sabe cuánto se cobró con tarjeta, y el
                // dueño cerraría su turno creyendo que no hubo ni un cobro con terminal.
                // El efectivo de arriba sí es confiable — sale del cajón, no del server.
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = "Sin conexión: sólo se muestra el efectivo, que es lo que hay " +
                        "en el cajón. Los cobros con tarjeta y otros medios aparecerán en " +
                        "el corte al recuperar la conexión.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Button(
                    onClick = onRetryBreakdown,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(
                        text = "Reintentar",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // PROPINAS — sección propia, igual que en el ticket impreso.
            // La propina no es dinero del negocio: se le entrega al mesero. Iba
            // sumada dentro de cada método sin distinguirse, así que el corte
            // enseñaba el efectivo sin avisar cuánto de ahí hay que sacar.
            val propinas = tenderBreakdown.filter { it.tipsCents != 0 }
            if (propinas.isNotEmpty()) {
                ReportSectionTitle(text = "Propinas")
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                propinas.sortedByDescending { it.tipsCents }.forEach { t ->
                    ReportRow(label = tenderLabel(t.method), value = formatCurrency(t.tipsCents))
                }
                ReportRow(
                    label = "Total propinas",
                    value = formatCurrency(propinas.sumOf { it.tipsCents }),
                    isBold = true,
                )
                val propinaEfectivo = propinas.firstOrNull { it.method == "CASH" }?.tipsCents ?: 0
                if (propinaEfectivo > 0) {
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                    Text(
                        text = "De estas, ${formatCurrency(propinaEfectivo)} están en el cajón y se " +
                            "pagan al mesero. Regístralo como egreso para que el corte cuadre.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))
            }

            // Cash movements section
            ReportSectionTitle(text = "Movimientos de efectivo")
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            ) {
                Column(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
                    ReportRow(
                        label = "Monto inicial",
                        value = formatCurrency(session.startingAmountCents),
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
                    )
                    ReportRow(
                        label = "Ingresos (pay-in)",
                        value = "+${formatCurrency(payInsCents)}",
                        valueColor = Success,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
                    )
                    ReportRow(
                        label = "Egresos (pay-out)",
                        value = "-${formatCurrency(payOutsCents)}",
                        valueColor = Error,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
                    )
                    ReportRow(
                        label = "Ventas en efectivo",
                        value = "+${formatCurrency(cashSalesCents)}",
                        valueColor = Success,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
                    )
                    ReportRow(
                        label = "Efectivo esperado",
                        value = formatCurrency(expectedCents),
                        isBold = true,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
                    )
                    ReportRow(
                        label = "Conteo real",
                        value = formatCurrency(actualCents),
                        isBold = true,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
                    )

                    val (diffLabel, diffColor) = when {
                        differenceCents > 0 -> "Sobrante" to Success
                        differenceCents < 0 -> "Faltante" to Error
                        else -> "Diferencia" to MaterialTheme.colorScheme.onSurface
                    }
                    ReportRow(
                        label = diffLabel,
                        value = formatCurrency(abs(differenceCents)),
                        valueColor = diffColor,
                        isBold = true,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))

            // Print button. Antes era un stub que sólo lanzaba un Toast de "no
            // disponible" — y en la Sunmi el Toast sale detrás de la pantalla del
            // cliente, así que el cajero veía un botón que no hacía absolutamente
            // nada. Ahora imprime de verdad, igual que iOS.
            Button(
                onClick = onPrint,
                enabled = !isPrinting,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    text = if (isPrinting) "Imprimiendo…" else "Imprimir corte",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))
        }
    }
}

// MARK: - Helper Composables

@Composable
internal fun ReportSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
internal fun ReportRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    isBold: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
