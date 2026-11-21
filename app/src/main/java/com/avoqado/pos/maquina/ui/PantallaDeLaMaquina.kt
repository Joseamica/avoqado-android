package com.avoqado.pos.maquina.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.core.util.formatMoneyFromCents
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.maquina.CajaConMaquina
import com.avoqado.pos.maquina.Desenlace
import com.avoqado.pos.maquina.EstadoDelCobro
import com.avoqado.pos.maquina.FilaDeLaMaquina
import com.avoqado.pos.maquina.TEXTO_AYUDA
import com.avoqado.pos.maquina.TEXTO_ESPERANDO
import com.avoqado.pos.maquina.TEXTO_ESPERANDO_AL_PANEL
import com.avoqado.pos.maquina.TEXTO_SIGUES_AHI
import com.avoqado.pos.maquina.VistaDeLaMaquina

/**
 * La fila «Efectivo en la máquina» de la selección de método (C5, sólo WINDOWS con la máquina activa). En gris con su razón si
 * no se puede; con poco cambio avisa y deja seguir. [aviso]: por qué el último toque no se mandó (centavos, descuento, premio…).
 */
@Composable
internal fun FilaEfectivoEnLaMaquina(fila: FilaDeLaMaquina, aviso: String?, avisoDeSubida: String?, habilitada: Boolean, onClick: () -> Unit) {
    if (!fila.visible) return
    val activa = habilitada && fila.habilitada
    val gris = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = activa, onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Efectivo en la máquina",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (activa) MaterialTheme.colorScheme.onSurface else gris,
            )
            val razon = fila.motivo ?: fila.avisoDeCambio
            razon?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            aviso?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            avisoDeSubida?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (activa) MaterialTheme.colorScheme.onSurfaceVariant else gris,
        )
    }
}

/** Codex código r1 #12: «Hay N tickets sin imprimir» con su botón (nunca se imprimen solos). */
@Composable
internal fun TicketsSinImprimirDeLaMaquina(cuantos: Int, onImprimir: () -> Unit) {
    if (cuantos <= 0) return
    Row(Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(
            com.avoqado.pos.maquina.textoDeTicketsSinImprimir(cuantos),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onImprimir) { Text("Imprimir") }
    }
}

/**
 * La pantalla del cobro en la máquina (C5): lo que hay que pagar, lo que entró y el cambio; «Cancelar y devolverme mi dinero»
 * sólo con el cobro propio; «¿Sigues ahí?» del abandono; «Esperando a la máquina», que un gerente resuelve en el panel (recorte R2).
 * El dinero lo dice la máquina, nunca esta pantalla: aquí sólo se enseña.
 */
@Composable
fun PantallaDeLaMaquina(vista: VistaDeLaMaquina, caja: CajaConMaquina, onSalir: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val c = vista.cobro) {
                is EstadoDelCobro.Iniciando -> {
                    Titulo("Preparando la máquina…")
                    Monto("Por pagar", c.dueCents)
                }
                is EstadoDelCobro.Cobrando -> {
                    Titulo("Paga en la máquina")
                    Importes(c.dueCents, c.creditCents)
                    vista.fila.avisoDeCambio?.let { Nota(it) }
                    if (c.propio) {
                        Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
                        OutlinedButton(onClick = caja::cancelarPorElCliente) { Text("Cancelar y devolverme mi dinero") }
                    }
                    Nota(TEXTO_AYUDA)
                }
                is EstadoDelCobro.SiguesAhi -> {
                    Importes(c.dueCents, c.creditCents)
                    Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
                    PrimaryButton(text = TEXTO_SIGUES_AHI, onClick = caja::sigoAqui, fullWidth = true)
                    Nota(TEXTO_AYUDA)
                }
                is EstadoDelCobro.Cancelando -> {
                    Titulo("Cancelando…")
                    Nota("Espera a que la máquina termine.")
                }
                is EstadoDelCobro.Reconectando -> {
                    Titulo("Reconectando con la máquina…")
                    Importes(c.dueCents, c.creditCents)
                    Nota(TEXTO_AYUDA)
                }
                is EstadoDelCobro.Cerrado -> {
                    if (c.desenlace == Desenlace.GUARDADO) {
                        // GUARDADO no enseña monto ni imprime (C7).
                        Titulo("Se canceló el cobro")
                    } else {
                        Titulo(if (c.desenlace == Desenlace.CANCELADO) "Cobro cancelado: recoge tu dinero" else "Pagado")
                        // Codex código r1 #13: también el cambio que no dio un PAGADO (no sólo lo que no devolvió un CANCELADO).
                        val debe = com.avoqado.pos.maquina.deudaFisica(c.desenlace, c.dueCents, c.creditCents, c.changeDispensedCents)
                        if (debe > 0) {
                            Nota(
                                if (c.desenlace == Desenlace.PAGADO) com.avoqado.pos.maquina.textoDeLaDeuda(debe.toLong(), c.referenceCC)
                                else "Te debemos ${formatMoneyFromCents(debe)}: pasa a caja a cobrarlo · Folio ${c.referenceCC}",
                            )
                        }
                    }
                    Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
                    PrimaryButton(text = "Listo", onClick = caja::listo, fullWidth = true)
                }
                is EstadoDelCobro.Esperando -> {
                    // Recorte R2: la caja no resuelve; consulta el folio y, cuando el panel lo resuelve, enseña cómo terminó.
                    Titulo(TEXTO_ESPERANDO)
                    Nota(TEXTO_ESPERANDO_AL_PANEL)
                    Nota("La máquina no confirmó cómo terminó este cobro. No lo cobres otra vez.")
                    Monto("Por pagar", c.dueCents)
                    Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
                    OutlinedButton(onClick = onSalir) { Text("Salir") }
                }
                EstadoDelCobro.Libre, null -> if (vista.incidencia != null) {
                    // Codex código r1 #11: el gerente dijo PAGADO, pero el SERVIDOR lo guardó como incidencia: no hay venta.
                    Titulo(vista.incidencia)
                    Nota("No se cobró la venta: el dinero quedó registrado para que el gerente lo revise.")
                    Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
                    PrimaryButton(text = "Listo", onClick = caja::listo, fullWidth = true)
                } else vista.resuelto?.let { d ->
                    Titulo(if (d == Desenlace.CANCELADO) "El gerente lo resolvió en el panel: la máquina devolvió el dinero" else "El gerente lo resolvió en el panel: la máquina guardó el dinero")
                    Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
                    PrimaryButton(text = "Listo", onClick = caja::listo, fullWidth = true)
                }
            }
        }
    }
}

@Composable
private fun Titulo(texto: String) = Text(
    texto,
    style = MaterialTheme.typography.headlineMedium,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.lg),
)

@Composable
private fun Nota(texto: String) = Text(
    texto,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier.padding(top = AvoqadoTheme.spacing.md),
)

@Composable
private fun Monto(etiqueta: String, cents: Int) {
    Text(etiqueta, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(formatMoneyFromCents(cents), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Importes(dueCents: Int, creditCents: Int) {
    Monto("Por pagar", dueCents)
    Spacer(Modifier.height(AvoqadoTheme.spacing.md))
    Text("Recibido: ${formatMoneyFromCents(creditCents)}", style = MaterialTheme.typography.titleLarge)
    val falta = dueCents - creditCents
    Text(
        if (falta > 0) "Falta: ${formatMoneyFromCents(falta)}" else "Tu cambio: ${formatMoneyFromCents(-falta)}",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}
