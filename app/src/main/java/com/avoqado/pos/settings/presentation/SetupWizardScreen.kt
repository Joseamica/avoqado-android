package com.avoqado.pos.settings.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Propinas
//
// 🔴 NO volver a meter aquí una lista de "tareas de configuración".
//
// Esta pantalla tenía un checklist de arranque del negocio (crear producto, agregar empleados,
// configurar impuestos, personalizar recibos) con dos defectos que sólo se ven usándola:
// los renglones NO tenían manejador de clic —el chevron ">" era un adorno que prometía una
// navegación inexistente, y el cliente lo reportó como "no puedo clickear"— y el progreso
// "2 de 5 completados" venía de valores fijos en el código, no del estado real del negocio.
// De fondo: 3 de esas 5 tareas pertenecen al dashboard, no al POS. Es la misma frontera que
// respeta Square: su POS trae los ajustes del mostrador y el checklist de arranque vive en su
// Dashboard web.
//
// 🔴 Y NO volver a meter el interruptor "Incluir IVA en base de propina" (retirado el
// 2026-09-18, decisión del founder). Era una convención fiscal de EE.UU. —allá el impuesto se
// suma aparte y el cliente lo ve; aquí el precio en pantalla ya lo incluye, y Fudo, el POS
// nativo de LatAm, ni siquiera ofrece esa opción—. Peor: se guardaba sólo en la memoria del
// aparato, así que dos cajas del mismo negocio podían sugerir propinas distintas y el dashboard
// no lo veía. La base ahora es fija y sin IVA, con candado en `PaymentFlowViewModelTest`.
//
// Lo que sí pertenece aquí es lo que se decide para ESTE aparato.

@Composable
fun SetupWizardScreen(
    onDismiss: () -> Unit,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val configurables by viewModel.configurables.collectAsState()
    val guardandoCobro by viewModel.guardandoCobro.collectAsState()
    val avisoCobro by viewModel.avisoCobro.collectAsState()

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
                text = "Propinas",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
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
            PantallasDelCobroCard(
                configurables = configurables,
                mostrarCalificacion = settings.showReviewScreen,
                mostrarPropina = settings.showTipScreen,
                guardando = guardandoCobro,
                aviso = avisoCobro,
                onCalificacion = viewModel::mostrarCalificacion,
                onPropina = viewModel::mostrarPropina,
            )

            PorcentajesSugeridosCard(
                editable = AjustesDelCobro.PORCENTAJES in configurables,
                porcentajes = settings.tipSuggestions,
                guardando = guardandoCobro,
                onCambiar = viewModel::cambiarPorcentajes,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            ) {
                Column(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
                    Text(
                        text = "Cómo se calcula",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                    Text(
                        text = "El porcentaje de propina se calcula sobre el consumo, sin IVA. " +
                            "En una cuenta de $116 pesos, el 10 % sugerido son $10.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                    Text(
                        text = "Lo de arriba es de ESTE aparato: otra caja del mismo negocio " +
                            "puede tener otros porcentajes. El panel web de Avoqado los cambia " +
                            "para todas de una vez.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * «Pantallas del cobro»: lo que el cliente ve entre que se cobra y se acaba.
 *
 * Por qué vive aquí y no sólo en el dashboard (founder, 2026-09-18): la pestaña de Configuración
 * del dashboard sólo se muestra para las terminales de COBRO (`TPV_ANDROID`), así que para una
 * tablet `POS_ANDROID` no había NINGÚN lugar donde apagar la calificación. El valor se guarda en
 * la ficha de ESTE aparato en el servidor, no en el aparato: así sobrevive a reinstalar la app y
 * el dashboard ve lo mismo.
 *
 * 🔴 Sólo se pueden TOCAR los interruptores que el SERVIDOR autoriza para este tipo de aparato
 * (`configurables`, de `device-capabilities.service.ts`). Si no autoriza ninguno, la tarjeta NO
 * desaparece: muestra el estado y dice dónde se cambia — un ajuste que se esconde deja al dueño
 * buscando en un menú que no existe, que es justo el problema que originó esto.
 *
 * Estructura tomada de Square (POS → Más → Ajustes → «Firma y recibo»): nombre + switch, y debajo
 * una línea con la CONSECUENCIA, no una descripción del campo.
 */
@Composable
private fun PantallasDelCobroCard(
    configurables: List<String>,
    mostrarCalificacion: Boolean,
    mostrarPropina: Boolean,
    guardando: Boolean,
    aviso: String?,
    onCalificacion: (Boolean) -> Unit,
    onPropina: (Boolean) -> Unit,
) {
    val puedeCalificacion = AjustesDelCobro.CALIFICACION in configurables
    val puedePropina = AjustesDelCobro.PROPINA in configurables
    val soloLectura = !puedeCalificacion && !puedePropina

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        Column(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
            Text(
                text = "Pantallas del cobro",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
            Text(
                text = "Qué se le muestra al cliente durante el cobro, en este aparato.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            AjusteDelCobroRow(
                titulo = "Pedir calificación",
                consecuencia = if (mostrarCalificacion) {
                    "Antes de cobrar se le piden las estrellas al cliente."
                } else {
                    "Se cobra directo, sin pedir estrellas."
                },
                checked = mostrarCalificacion,
                enabled = !guardando && puedeCalificacion,
                onCheckedChange = onCalificacion,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            AjusteDelCobroRow(
                titulo = "Pedir propina",
                consecuencia = if (mostrarPropina) {
                    "Se muestra la pantalla de propina antes de confirmar."
                } else {
                    "No se ofrece propina en el cobro."
                },
                checked = mostrarPropina,
                enabled = !guardando && puedePropina,
                onCheckedChange = onPropina,
            )

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = if (soloLectura) {
                    "Estos ajustes se cambian desde el panel web de Avoqado."
                } else {
                    "Se guarda en tu cuenta, así que necesita conexión."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (aviso != null) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Text(
                    text = aviso,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
}

@Composable
private fun AjusteDelCobroRow(
    titulo: String,
    consecuencia: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AvoqadoTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titulo,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = consecuencia,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * Los porcentajes de propina que se le sugieren al cliente (founder, 2026-09-18).
 *
 * 🔴 Se manda la lista COMPLETA en cada cambio, no «agrega este»: el servidor valida el conjunto
 * (1 a 100, sin repetidos, máximo 6, al menos uno) y es la única autoridad. Repetir esas reglas
 * aquí crearía una segunda versión que se desfasa de la del dashboard; lo único que se impide
 * localmente es quedarse sin ninguno, porque eso deja al cliente sin un solo botón que tocar.
 */
@Composable
private fun PorcentajesSugeridosCard(
    editable: Boolean,
    porcentajes: List<Int>,
    guardando: Boolean,
    onCambiar: (List<Int>) -> Unit,
) {
    var nuevo by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        Column(modifier = Modifier.padding(AvoqadoTheme.spacing.lg)) {
            Text(
                text = "Porcentajes sugeridos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
            Text(
                text = "Los botones que verá el cliente al dejar propina.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))

            Row(verticalAlignment = Alignment.CenterVertically) {
                porcentajes.forEach { porcentaje ->
                    AssistChip(
                        onClick = {
                            // Nunca dejar la lista vacía: sin un solo porcentaje el cliente se
                            // queda sin botones y el servidor rechazaría el guardado igual.
                            if (editable && !guardando && porcentajes.size > 1) {
                                onCambiar(porcentajes.filterNot { it == porcentaje })
                            }
                        },
                        enabled = editable && !guardando && porcentajes.size > 1,
                        label = { Text("$porcentaje %") },
                        trailingIcon = if (editable) {
                            { Icon(Icons.Outlined.Close, contentDescription = "Quitar $porcentaje por ciento") }
                        } else {
                            null
                        },
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xs))
                }
            }

            if (editable) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = nuevo,
                        onValueChange = { texto -> nuevo = texto.filter { it.isDigit() }.take(3) },
                        label = { Text("Agregar %") },
                        singleLine = true,
                        enabled = !guardando && porcentajes.size < 6,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(160.dp),
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                    TextButton(
                        onClick = {
                            val valor = nuevo.toIntOrNull()
                            if (valor != null && valor !in porcentajes) {
                                onCambiar((porcentajes + valor).sorted())
                                nuevo = ""
                            }
                        },
                        enabled = !guardando && porcentajes.size < 6 && nuevo.toIntOrNull() != null,
                    ) {
                        Text("Agregar")
                    }
                }
                if (porcentajes.size >= 6) {
                    Text(
                        text = "Ya son seis: quita uno para agregar otro.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                Text(
                    text = "Se cambian desde el panel web de Avoqado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
}
