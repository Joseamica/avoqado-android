package com.avoqado.pos.escritorio.dibujo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.escritorio.diagnostico.DatosDelEquipo
import com.avoqado.pos.escritorio.diagnostico.veredicto
import com.avoqado.pos.settings.RenglonDeEscritorio

internal const val ETIQUETA_DE_LA_BANDA = "aviso-de-dibujo"
private const val NO_SE_GUARDO = "No se pudo guardar el cambio. Inténtalo de nuevo."

// Los botones de la banda se tocan, pero nunca se llevan el foco: el campo o la pantalla de abajo siguen recibiendo las teclas.
private val SIN_FOCO = Modifier.focusProperties { canFocus = false }

/**
 * Las bandas del modo de dibujo (hz:a5fa2439:D2), una a la vez, en su PROPIO renglón arriba de la app: la app se recorre lo
 * que mide mientras se ve, así que no tapa nada (en el laboratorio, flotando, tapaba «Agregar cliente» y la franja de «Sin
 * conexión»). No es una ventana ni un `Popup`: no toma el foco ni se come teclas (F12, F5, F4 siguen llegando a la pantalla).
 * Sin medición no sale nada, y aparece y se va sin animación.
 */
@Composable
fun AvisoDeDibujo() {
    val e = DibujoDeLaCaja.estado.value
    val recienElegido = DibujoDeLaCaja.recienElegido.value
    var noSeGuardo by remember(e.aviso, recienElegido) { mutableStateOf(false) }
    when {
        recienElegido && e.aplicaAlReabrir -> Banda("Listo. Cierra y vuelve a abrir el POS para aplicarlo.") {
            Boton("Entendido", DibujoDeLaCaja::callarConfirmacion)
        }
        e.aviso == Aviso.FALLO_RAPIDO -> Banda("El modo rápido no funcionó en esta computadora. La app regresó al modo automático.") {
            Boton("Entendido") { DibujoDeLaCaja.callarAviso() }
        }
        e.aviso == Aviso.NO_ALCANZA -> Banda(
            "Esta computadora tarda en dibujar la pantalla, aun en modo rápido. Puedes seguir trabajando, pero la app va a responder lento.",
        ) {
            Boton("Entendido") { DibujoDeLaCaja.callarAviso() }
        }
        e.aviso == Aviso.OFRECER_RAPIDO -> Banda(
            "Esta computadora tarda en dibujar la pantalla. El modo rápido puede ayudar.",
            error = NO_SE_GUARDO.takeIf { noSeGuardo },
        ) {
            Boton("Activar modo rápido") { noSeGuardo = !DibujoDeLaCaja.elegir(Modo.RAPIDO) }
            TextButton(onClick = { DibujoDeLaCaja.callarAviso() }, modifier = SIN_FOCO) { Text("Ahora no") }
        }
    }
}

// Una fila a todo lo ancho: texto a la izquierda (en una ventana angosta parte en dos líneas) y botones a la derecha.
@Composable
private fun Banda(texto: String, error: String? = null, botones: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).testTag(ETIQUETA_DE_LA_BANDA)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (error != null) {
                    Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            botones()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** Píldora compacta (40 dp, como manda el sistema de diseño para la acción primaria), que se toca sin llevarse el foco. */
@Composable
private fun Boton(texto: String, alTocar: () -> Unit) = Button(onClick = alTocar, modifier = SIN_FOCO) { Text(texto) }

private fun nombre(modo: Modo) = when (modo) {
    Modo.AUTO -> "Automático"
    Modo.RAPIDO -> "Modo rápido"
}

/**
 * Los dos renglones de «Más» que sólo tiene WINDOWS, y sus hojas. Lo pone la raíz del escritorio en `LocalRenglonesDeEscritorio`.
 * Lo puede cambiar quien esté en sesión: no toca dinero y se deshace.
 */
@Composable
internal fun renglonesDeDibujo(datosDelEquipo: () -> DatosDelEquipo?): List<RenglonDeEscritorio> {
    var verModo by remember { mutableStateOf(false) }
    var verDiagnostico by remember { mutableStateOf(false) }
    if (verModo) HojaDeModoDeDibujo(onDismiss = { verModo = false })
    if (verDiagnostico) HojaDeDiagnostico(datosDelEquipo(), onDismiss = { verDiagnostico = false })
    // Con el apagador de soporte no hay modo que elegir (todo intento diría «No se pudo guardar»); el diagnóstico sí.
    return listOfNotNull(
        RenglonDeEscritorio(Icons.Outlined.Speed, "Modo de dibujo", nombre(DibujoDeLaCaja.estado.value.modo)) { verModo = true }
            .takeUnless { DibujoDeLaCaja.apagado },
        RenglonDeEscritorio(Icons.Outlined.Computer, "Diagnóstico de esta computadora", null) { verDiagnostico = true },
    )
}

/** Las dos opciones, como la hoja de «Modo» de «Más». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HojaDeModoDeDibujo(onDismiss: () -> Unit) {
    val e = DibujoDeLaCaja.estado.value
    var noSeGuardo by remember { mutableStateOf(false) }
    AvoqadoModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.lg)) {
            Titulo("Modo de dibujo")
            Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = "El modo rápido dibuja sin la tarjeta de video y con menos animaciones. Sirve en computadoras con tarjeta de " +
                    "video antigua; en una moderna puede ir más lento. Se aplica al volver a abrir el POS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
            Modo.entries.forEachIndexed { i, modo ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = modo == e.modo, role = Role.RadioButton) {
                            if (modo != e.modo) noSeGuardo = !DibujoDeLaCaja.elegir(modo)
                        }
                        .padding(vertical = AvoqadoTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = nombre(modo),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    RadioButton(selected = modo == e.modo, onClick = null)
                }
            }
            val estado = when {
                e.aplicaAlReabrir -> "Se aplica al volver a abrir el POS."
                e.aMano -> "Fijado a mano en esta computadora${e.motor?.let { " ($it)" }.orEmpty()}."
                else -> null
            }
            if (estado != null) {
                Spacer(Modifier.height(AvoqadoTheme.spacing.md))
                Text(text = estado, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            if (noSeGuardo) {
                Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
                Text(text = NO_SE_GUARDO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        }
    }
}

/** El veredicto de `diagnostico.txt`, con cómo dibuja AHORA (el modo pudo cambiar después de escribirlo). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaDeDiagnostico(datos: DatosDelEquipo?, onDismiss: () -> Unit) {
    val dibujo = DibujoDeLaCaja.paraElDiagnostico()
    AvoqadoModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(AvoqadoTheme.spacing.lg)) {
            Titulo("Diagnóstico de esta computadora")
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))
            Text(
                text = datos?.let { veredicto(it.copy(dibujo = dibujo)).comoTexto() } ?: "Todavía no hay diagnóstico de esta computadora.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        }
    }
}

@Composable
private fun Titulo(texto: String) =
    Text(text = texto, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
