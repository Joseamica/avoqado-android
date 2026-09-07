package com.avoqado.pos.cashdrawer.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.avoqado.pos.cashdrawer.data.ConteoSospechoso
import com.avoqado.pos.cashdrawer.data.RetiroDelDia
import com.avoqado.pos.cashdrawer.data.SospechaDeConteo
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error
import com.avoqado.pos.designsystem.theme.Success

/** Cuántos retiros se listan en la hoja antes de resumir «y N más». */
private const val RETIROS_VISIBLES = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloseDrawerSheet(
    expectedAmountCents: Int,
    onConfirm: (actualAmountCents: Int, note: String?) -> Unit,
    onDismiss: () -> Unit,
    onClosed: ((actualAmountCents: Int) -> Unit)? = null,
    /**
     * Los retiros de la sesión y el efectivo cobrado (Testarudo 6-sep, ver [ConteoSospechoso]).
     * Sirven para dos cosas: listar en la hoja lo que YA salió del cajón, y reconocer un «conteo»
     * que en realidad es uno de esos números. Ninguno revela el esperado.
     */
    retiros: List<RetiroDelDia> = emptyList(),
    efectivoCobradoCents: Int = 0,
) {
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var closed by remember { mutableStateOf(false) }
    var sospechaPendiente by remember { mutableStateOf<SospechaDeConteo?>(null) }

    // P2 Codex: `toInt()` TRUNCA — $128.14 → 12813.999… → $128.13 y un centavo de faltante inventado. iOS redondea.
    val actualCents = ((amountText.toDoubleOrNull() ?: 0.0) * 100).roundToInt()

    fun confirmar() {
        // Doble toque (visto en /full-testing 27-ago): mandaba DOS cierres; el server
        // salvaba con su CAS, pero el botón debe apagarse tras el primero.
        closed = true
        val note = noteText.ifBlank { null }
        onConfirm(actualCents, note)
        onClosed?.invoke(actualCents)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        Column(
            modifier = Modifier
                // El teclado no tapa esta hoja: Material3 le fija ADJUST_NOTHING
                // a su ventana, asi que el ajuste va en el contenido.
                .imePadding()
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .padding(bottom = AvoqadoTheme.spacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Cerrar caja",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            // 🔴 «Cuenta todo el efectivo de la caja» se leía igual de bien como «todo el efectivo
            // que pasó por la caja hoy» — y así lo leyó Testarudo dos días seguidos. Se dice qué
            // contar (lo que hay DENTRO ahora, con el fondo) y qué no (lo que ya salió).
            Text(
                text = ConteoSospechoso.INSTRUCCION,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (retiros.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                RetirosDelDia(retiros)
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            // 🔴 CONTEO CIEGO (fase 5 de la unificación de caja). Antes el sheet enseñaba el
            // "Monto esperado" ANTES de contar y el sobrante/faltante EN VIVO mientras se tecleaba:
            // el cajero podía "contar hacia el sistema" y ajustar la cifra hasta cuadrar. Un
            // faltante es evidencia con peso laboral (LFT 107/110), así que el esperado y la
            // diferencia se enseñan DESPUÉS de confirmar, en el reporte de cierre — igual que el
            // cierre ciego de Toast. El dato sigue viajando (`expectedAmountCents`) porque el
            // reporte lo necesita; sólo deja de mostrarse aquí.
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            OutlinedTextField(
                value = amountText,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                        amountText = newValue
                    }
                },
                label = { Text("Conteo real ($)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("Nota (opcional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            Button(
                onClick = {
                    // Un «conteo» que es exactamente lo que acaba de retirar, o el efectivo
                    // cobrado del día, no se manda a ciegas: se pregunta UNA vez. Si insiste,
                    // se cierra con su número — es su conteo y su responsabilidad.
                    val sospecha = ConteoSospechoso.evaluar(actualCents, retiros, efectivoCobradoCents)
                    if (sospecha != null) sospechaPendiente = sospecha else confirmar()
                },
                enabled = !closed && amountText.isNotBlank() && amountText.toDoubleOrNull() != null,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    text = "Cerrar caja",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    sospechaPendiente?.let { sospecha ->
        AvoqadoDialog(
            title = ConteoSospechoso.TITULO,
            description = ConteoSospechoso.mensaje(sospecha),
            onDismiss = { sospechaPendiente = null },
            actionButton = {
                PrimaryButton(
                    text = ConteoSospechoso.VOLVER_A_CONTAR,
                    onClick = {
                        sospechaPendiente = null
                        amountText = ""
                    },
                    fullWidth = true,
                )
            },
            content = {
                TextButton(
                    onClick = {
                        sospechaPendiente = null
                        confirmar()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = ConteoSospechoso.CERRAR_ASI,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/** Lo que ya salió del cajón hoy, para que el cajero no lo vuelva a «contar». */
@Composable
private fun RetirosDelDia(retiros: List<RetiroDelDia>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
    ) {
        Text(
            text = ConteoSospechoso.ENCABEZADO_DE_RETIROS,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        retiros.takeLast(RETIROS_VISIBLES).forEach { retiro ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = ConteoSospechoso.etiqueta(retiro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = ConteoSospechoso.pesos(retiro.amountCents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (retiros.size > RETIROS_VISIBLES) {
            Text(
                text = "y ${retiros.size - RETIROS_VISIBLES} retiros más",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = ConteoSospechoso.TOTAL_RETIRADO,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = ConteoSospechoso.pesos(retiros.sumOf { it.amountCents }),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
