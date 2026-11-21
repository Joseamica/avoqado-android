package com.avoqado.pos.maquina.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.maquina.Desenlace
import com.avoqado.pos.maquina.ResolucionDelGerente
import com.avoqado.pos.maquina.TEXTO_SIN_PERMISO_RESOLVER
import com.avoqado.pos.maquina.VistaDeLaMaquina
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** Pesos tecleados («187.50», «1,500») → centavos. null si no es un importe ≥ 0 con a lo más dos decimales. */
internal fun centavosDeTexto(texto: String): Int? {
    val pesos = texto.trim().removePrefix("$").replace(",", "").toBigDecimalOrNull() ?: return null
    if (pesos.signum() < 0) return null
    return runCatching { pesos.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact() }.getOrNull()
}

/**
 * R5#8: la hora del comprobante de la máquina («18:05») como instante, en la zona del negocio. Es de hoy, o de ayer si a esta
 * hora todavía no llega. Vacío o ilegible = null («hora y turno por conciliar»), nunca la hora de inicio ni la de ahora.
 */
internal fun horaDelComprobante(texto: String, ahora: ZonedDateTime): Instant? {
    val hora = try {
        LocalTime.parse(texto.trim().padStart(5, '0'))
    } catch (e: DateTimeParseException) {
        return null
    }
    val hoy = ahora.with(hora).withSecond(0).withNano(0)
    return (if (hoy.isAfter(ahora)) hoy.minusDays(1) else hoy).toInstant()
}

private enum class Opcion(val etiqueta: String, val desenlace: Desenlace) {
    COBRO("Cobró", Desenlace.PAGADO),
    CANCELO("Canceló", Desenlace.CANCELADO),
    GUARDO("Se quedó el dinero", Desenlace.GUARDADO),
}

private enum class Modo(val etiqueta: String, val valor: String?) { DEVOLVIO("Lo devolvió", "1"), GUARDO("Lo guardó", "2"), NO_SE("No sé", null) }

/**
 * «Resolver con gerente» (R4-4): el gerente, con SU sesión, teclea lo que dice la pantalla o el comprobante de la máquina. Sin el
 * permiso se explica cómo entrar (cambio de usuario por PIN, como Ajustes de la máquina). Con «No sé» el cobro sigue esperando.
 */
@Composable
internal fun ResolverAMano(vista: VistaDeLaMaquina, onResolver: (ResolucionDelGerente) -> Unit, onCerrar: () -> Unit) =
    ResolverAMano(vista.puedeResolver, vista.resolviendo, vista.avisoDeResolver, onResolver, onCerrar)

/** Codex código r1 #7: el mismo formulario para la duda de esta venta, para las otras dudas de Cobrar y para Ajustes. */
@Composable
internal fun ResolverAMano(
    puedeResolver: Boolean,
    resolviendo: Boolean,
    aviso: String?,
    onResolver: (ResolucionDelGerente) -> Unit,
    onCerrar: () -> Unit,
) {
    var opcion by remember { mutableStateOf<Opcion?>(null) }
    var modo by remember { mutableStateOf<Modo?>(null) }
    var entro by remember { mutableStateOf("") }
    var cambio by remember { mutableStateOf("0") }
    var hora by remember { mutableStateOf("") }
    var vio by remember { mutableStateOf("") }
    AvoqadoDialog(
        title = "Resolver con gerente",
        description = "Escribe lo que dice la máquina de este cobro.",
        onDismiss = onCerrar,
        dismissOnClickOutside = false,
        actionButton = {
            val credit = centavosDeTexto(entro)
            val change = centavosDeTexto(cambio)
            val o = opcion
            PrimaryButton(
                text = "Resolver",
                fullWidth = true,
                isLoading = resolviendo,
                enabled = puedeResolver && o != null && credit != null && change != null && vio.isNotBlank() &&
                    (o == Opcion.COBRO || modo != null),
                onClick = {
                    if (o == null || credit == null || change == null) return@PrimaryButton
                    onResolver(
                        ResolucionDelGerente(
                            desenlace = o.desenlace,
                            creditCents = credit,
                            changeDispensedCents = change,
                            horaDelComprobante = horaDelComprobante(hora, VenueTimeZone.now()),
                            cancelMode = modo?.valor.takeIf { o != Opcion.COBRO },
                            evidencia = vio,
                        ),
                    )
                },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            if (!puedeResolver) {
                Text(TEXTO_SIN_PERMISO_RESOLVER, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Text("¿Cómo terminó?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                Opcion.entries.forEach { o -> FilterChip(selected = opcion == o, onClick = { opcion = o }, label = { Text(o.etiqueta) }) }
            }
            if (opcion != null && opcion != Opcion.COBRO) {
                Text("¿Qué hizo con los billetes y monedas?", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    Modo.entries.forEach { m -> FilterChip(selected = modo == m, onClick = { modo = m }, label = { Text(m.etiqueta) }) }
                }
                if (modo == Modo.NO_SE) Text("Sin saberlo, el cobro sigue «Esperando a la máquina».", style = MaterialTheme.typography.bodySmall)
            }
            AvoqadoPillTextField(value = entro, onValueChange = { entro = it }, placeholder = "Entró (ej. 200.00)", keyboardType = KeyboardType.Decimal)
            AvoqadoPillTextField(value = cambio, onValueChange = { cambio = it }, placeholder = "Cambio que dio (ej. 12.50)", keyboardType = KeyboardType.Decimal)
            AvoqadoPillTextField(value = hora, onValueChange = { hora = it }, placeholder = "Hora del comprobante (ej. 18:05)")
            AvoqadoPillTextField(value = vio, onValueChange = { vio = it }, placeholder = "Lo que viste en la máquina")
            aviso?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
        }
    }
}
