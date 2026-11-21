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

private val FECHA_Y_HORA = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm[:ss]")
    .withResolverStyle(java.time.format.ResolverStyle.STRICT)

/**
 * R5#8 y Codex código r1 #15: la hora del comprobante de la máquina como instante, en la zona del negocio. Con su fecha («2026-11-18
 * 15:48», como la imprime la máquina) vale tal cual; sólo con la hora («15:48») es del día en que EMPEZÓ el cobro ([inicio]), o del
 * siguiente si es antes de que empezara: el gerente que revisa el lunes un comprobante del viernes no lo manda al lunes. Vacío o ilegible
 * = null («hora y turno por conciliar»), nunca la hora de inicio ni la de ahora.
 */
internal fun horaDelComprobante(texto: String, inicio: ZonedDateTime): Instant? {
    val t = texto.trim()
    runCatching { return java.time.LocalDateTime.parse(t, FECHA_Y_HORA).atZone(inicio.zone).toInstant() }
    val hora = try {
        LocalTime.parse(t.padStart(5, '0'))
    } catch (e: DateTimeParseException) {
        return null
    }
    val ese = inicio.with(hora).withSecond(0).withNano(0)
    return (if (ese.isBefore(inicio.withSecond(0).withNano(0))) ese.plusDays(1) else ese).toInstant()
}

/** ponytail: lo último del diario cabe en el diálogo; el completo lo tiene el SERVIDOR (y el DASHBOARD). */
private const val EVIDENCIA_VISIBLE = 800

/** Codex código r3 #8: un folio que la máquina ya cerró y sólo falta registrar (relevaron su PC). */
internal const val TEXTO_POR_TERMINAR = "por terminar"

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
    ResolverAMano(vista.puedeResolver, vista.resolviendo, vista.avisoDeResolver, vista.inicioDelCobro, onResolver, onCerrar, vista.duda)

/** Codex código r1 #7: el mismo formulario para la duda de esta venta, para las otras dudas de Cobrar y para Ajustes. */
@Composable
internal fun ResolverAMano(
    puedeResolver: Boolean,
    resolviendo: Boolean,
    aviso: String?,
    /** Cuándo empezó ese cobro (la hora sola se lee desde ese día). */
    inicio: Long?,
    onResolver: (ResolucionDelGerente) -> Unit,
    onCerrar: () -> Unit,
    /**
     * Codex código r2 H7: la evidencia que el gerente tiene en pantalla y su versión: la que se manda es ESTA. Cambia sólo cuando el
     * SERVIDOR dice que llegó otra (409), y entonces se vuelve a confirmar.
     */
    duda: com.avoqado.pos.maquina.CobroEnEspera? = null,
) {
    // Codex código r3 #8: un POR_TERMINAR no se teclea: la máquina ya lo cerró; el gerente sólo lo termina con la versión que ve.
    if (duda?.porTerminar == true) return TerminarPendiente(puedeResolver, resolviendo, aviso, duda, onResolver, onCerrar)
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
                            horaDelComprobante = horaDelComprobante(
                                hora, VenueTimeZone.at(Instant.ofEpochMilli(inicio ?: System.currentTimeMillis())),
                            ),
                            cancelMode = modo?.valor.takeIf { o != Opcion.COBRO },
                            evidencia = vio,
                            version = duda?.version,
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
            duda?.let { d ->
                // Codex código r3 #9: lo que entró, del campo numérico (no de la cola del texto, que se recorta).
                d.creditCents?.let { Text("Entró, según la PC: ${com.avoqado.pos.core.util.formatMoneyFromCents(it)}", style = MaterialTheme.typography.titleSmall) }
                d.evidencia?.let { e ->
                    Text("Lo que vio la PC (versión ${d.version})", style = MaterialTheme.typography.titleSmall)
                    Text(e.takeLast(EVIDENCIA_VISIBLE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            AvoqadoPillTextField(value = hora, onValueChange = { hora = it }, placeholder = "Fecha y hora del comprobante (ej. 2026-11-18 15:48)")
            AvoqadoPillTextField(value = vio, onValueChange = { vio = it }, placeholder = "Lo que viste en la máquina")
            aviso?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
        }
    }
}

/** Codex código r3 #8: «Terminar» un folio POR_TERMINAR: lo que guardó la máquina, a la vista, y un solo botón con su versión. */
@Composable
private fun TerminarPendiente(
    puedeResolver: Boolean,
    resolviendo: Boolean,
    aviso: String?,
    duda: com.avoqado.pos.maquina.CobroEnEspera,
    onResolver: (ResolucionDelGerente) -> Unit,
    onCerrar: () -> Unit,
) {
    val desenlace = Desenlace.entries.firstOrNull { it.name == duda.outcome } ?: Desenlace.PAGADO
    AvoqadoDialog(
        title = "Terminar el cobro",
        description = "La máquina ya cerró este cobro y su PC se relevó antes de registrarlo. Termínalo con lo que guardó.",
        onDismiss = onCerrar,
        dismissOnClickOutside = false,
        actionButton = {
            PrimaryButton(
                text = "Terminar",
                fullWidth = true,
                isLoading = resolviendo,
                enabled = puedeResolver,
                onClick = {
                    onResolver(
                        ResolucionDelGerente(
                            desenlace = desenlace,
                            creditCents = duda.creditCents ?: 0,
                            changeDispensedCents = duda.changeDispensedCents ?: 0,
                            horaDelComprobante = null,
                            cancelMode = null,
                            evidencia = "",
                            version = duda.version,
                            porTerminar = true,
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
            Text("Folio ${duda.referenceCC} (versión ${duda.version})", style = MaterialTheme.typography.titleSmall)
            Text("Desenlace: ${desenlace.name}", style = MaterialTheme.typography.bodyMedium)
            duda.creditCents?.let { Text("Entró: ${com.avoqado.pos.core.util.formatMoneyFromCents(it)}", style = MaterialTheme.typography.bodyMedium) }
            duda.changeDispensedCents?.let { Text("Cambio: ${com.avoqado.pos.core.util.formatMoneyFromCents(it)}", style = MaterialTheme.typography.bodyMedium) }
            aviso?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
