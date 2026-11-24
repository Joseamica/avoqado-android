package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.core.util.avisoDelTelefono
import com.avoqado.pos.core.util.digitosDelCampo
import com.avoqado.pos.core.util.digitosNacionales
import com.avoqado.pos.core.util.masBienPuesto
import com.avoqado.pos.core.util.filtrarTecleoDelCampoConBandera
import com.avoqado.pos.core.util.textoVisibleDelCampo

/**
 * International phone input with country selector.
 *
 * Emits the raw digits (not the dial code) — the caller composes E.164 as
 * `"+${country.dialCode}${digits}"` when submitting. [country] tracks the
 * selected country so the caller can read the dial code.
 *
 * 🔴 Antes de mandar, quien lo usa revisa `telefonoCompleto(digits, country.dialCode)` (hz:nube0000:na519219): el campo
 * dejaba mandar «55918739221» con México. El campo dice debajo qué falta («Falta 1 dígito: un número de México lleva 10»).
 * Si la persona escribe o pega con «+» la lada de otro país de la lista, la bandera se la queda y el texto se queda con el
 * número («+34 612 345 678» → 🇪🇸 «612345678»): lo que se ve es lo que se manda. Si después se borra todo, la bandera regresa
 * a la de antes. Lo que no se puede atribuir (lada fuera de la lista, «+» fuera de lugar, la lada sin número) llega como
 * «+<dígitos>» y no pasa. Reglas: [alEscribirEnElCampo] y [avisoDelCampo].
 *
 * Example:
 * ```
 * var country by remember { mutableStateOf(Countries.byIso("MX")) }
 * var digits by remember { mutableStateOf("") }
 * AvoqadoPhoneInput(
 *     country = country,
 *     onCountryChange = { country = it },
 *     digits = digits,
 *     onDigitsChange = { digits = it },
 * )
 * // Submit:
 * val e164 = "+${country.dialCode}$digits"
 * ```
 */
@Composable
fun AvoqadoPhoneInput(
    country: Country,
    onCountryChange: (Country) -> Unit,
    digits: String,
    onDigitsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Número",
) {
    var showPicker by remember { mutableStateOf(false) }
    var escrito by remember { mutableStateOf(digits) }
    // La bandera que había antes de que la tomara una lada escrita: vuelve si se borra todo (Codex r1 del 24-nov).
    var paisAntes by remember { mutableStateOf<Country?>(null) }
    val aviso = avisoDelCampo(textoVisibleDelCampo(escrito, digits, country.dialCode), country)

    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Country selector (flag + dial code + dropdown arrow)
        Row(
            modifier = Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (enabled) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .clickable(enabled = enabled) { showPicker = true }
                .padding(horizontal = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
        ) {
            Text(
                text = country.flag,
                fontSize = 20.sp,
            )
            Text(
                text = "+${country.dialCode}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Seleccionar país",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        // Digits input (pill)
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (enabled) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(horizontal = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                val visible = textoVisibleDelCampo(escrito, digits, country.dialCode)
                // La ayuda sale sólo con el campo vacío: con «+52 1» escrito todavía no hay dígitos, pero sí texto (Codex r5).
                if (visible.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    // Se ve lo escrito (con su «+»); a quien lo usa le llegan sólo los dígitos, sin la lada repetida: con México
                    // elegido, «+52 55 9187 3922» da «5591873922» (antes «525591873922» y el WhatsApp salía a «+52525591873922»),
                    // igual pegado que tecleado. Regla en core/util/Telefono.kt.
                    value = visible,
                    onValueChange = { nuevo ->
                        // «+34 612…» con México elegido: la bandera pasa sola a España (antes salía a «+5234612…»).
                        val despues = alEscribirEnElCampo(nuevo, EstadoDelCampo(escrito, country, paisAntes))
                        escrito = despues.escrito
                        paisAntes = despues.paisAntes
                        if (despues.pais != country) onCountryChange(despues.pais)
                        onDigitsChange(digitosDelCampo(despues.escrito, despues.pais.dialCode))
                    },
                    singleLine = true,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    if (aviso != null) {
        Text(
            text = aviso.texto,
            style = MaterialTheme.typography.bodySmall,
            // Mientras faltan dígitos o se escribe la lada es una guía; lo que sobra o la lada que no cuadra sí es un error.
            color = if (aviso.esError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = AvoqadoTheme.spacing.md, top = AvoqadoTheme.spacing.xxs),
        )
    }
    }

    if (showPicker) {
        CountryPickerSheet(
            selected = country,
            onSelect = {
                // Con lo escrito se vuelven a sacar los dígitos con la lada nueva: con EE. UU. «15123456789» quedaba en
                // «5123456789» y al pasar a Alemania se mandaba «+495123456789» (Codex r3).
                val visible = textoVisibleDelCampo(escrito, digits, country.dialCode)
                escrito = visible
                paisAntes = null   // la eligió la persona: ya no hay bandera «de antes» a la cual volver
                onCountryChange(it)
                onDigitsChange(digitosDelCampo(visible, it.dialCode))
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** Lo que el campo recuerda entre teclazos: lo escrito, la bandera y la que había antes de que la tomara una lada. */
internal data class EstadoDelCampo(val escrito: String, val pais: Country, val paisAntes: Country?)

/**
 * Un teclazo o un pegado en el campo. Si lo escrito empieza con «+<lada>» de otro país de la lista, la bandera pasa a ese
 * país y el texto se queda con el número nacional (la lada ya la dice la bandera: así no se ve dos veces ni se queda
 * pegada al borrar el «+», Codex r1 del 24-nov). Si se borra todo, la bandera regresa a la que había antes.
 */
internal fun alEscribirEnElCampo(nuevo: String, antes: EstadoDelCampo): EstadoDelCampo {
    val escrito = filtrarTecleoDelCampoConBandera(nuevo)
    val porLada = Countries.porLadaEscrita(escrito, antes.pais)
    if (porLada != null) {
        return EstadoDelCampo(
            escrito = digitosNacionales(escrito, porLada.dialCode),
            pais = porLada,
            paisAntes = antes.paisAntes ?: antes.pais,
        )
    }
    // Regresa sólo al BORRAR lo que había (antes había dígitos y ya no): tras tomar «+34» el texto queda vacío y el espacio
    // que sigue («+34 612…» tecleado) no debe regresarla.
    val borroTodo = antes.escrito.any { it in '0'..'9' } && escrito.none { it in '0'..'9' || it == '+' }
    if (borroTodo && antes.paisAntes != null) {
        return EstadoDelCampo(escrito, antes.paisAntes, null)
    }
    return antes.copy(escrito = escrito)
}

/** El aviso bajo el campo: qué le falta a lo escrito. [esError] = rojo; si no, gris de guía. */
internal data class AvisoDelCampo(val texto: String, val esError: Boolean)

internal fun avisoDelCampo(escrito: String, pais: Country): AvisoDelCampo? {
    val digitos = digitosDelCampo(escrito, pais.dialCode)
    if (digitos.isEmpty()) return null
    if (!digitos.startsWith("+")) {
        val texto = avisoDelTelefono(digitos, pais.dialCode, pais.name) ?: return null
        return AvisoDelCampo(texto, esError = !texto.startsWith("Falta"))
    }
    val escritos = digitos.drop(1)
    if (!masBienPuesto(escrito)) return AvisoDelCampo("El «+» sólo va al principio, antes de la lada", esError = true)
    if (escritos.isEmpty()) return AvisoDelCampo("Escribe la lada y el número", esError = false)
    if (escritos.startsWith(pais.dialCode)) return AvisoDelCampo("Escribe el número después de la lada", esError = false)
    if (Countries.all.any { it.dialCode.startsWith(escritos) }) return AvisoDelCampo("Sigue escribiendo la lada", esError = false)
    val otro = Countries.all.filter { escritos.startsWith(it.dialCode) }.maxByOrNull { it.dialCode.length }
    return if (otro != null) {
        AvisoDelCampo("El número trae la lada +${otro.dialCode} (${otro.name}): elige ese país en la bandera o quítala", esError = true)
    } else {
        AvisoDelCampo("La lada de ese número no está en la lista de países", esError = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPickerSheet(
    selected: Country,
    onSelect: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AvoqadoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = AvoqadoTheme.spacing.xl,
            ),
        ) {
            item {
                Text(
                    text = "Frecuentes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = AvoqadoTheme.spacing.xl,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                )
            }
            items(Countries.pinned, key = { "pinned-${it.isoCode}" }) { country ->
                CountryRow(country = country, selected = country.isoCode == selected.isoCode, onClick = { onSelect(country) })
            }

            item {
                Text(
                    text = "Otros países",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = AvoqadoTheme.spacing.xl,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                )
            }
            items(Countries.others.sortedBy { it.name }, key = { "other-${it.isoCode}" }) { country ->
                CountryRow(country = country, selected = country.isoCode == selected.isoCode, onClick = { onSelect(country) })
            }
        }
    }
}

@Composable
private fun CountryRow(
    country: Country,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = AvoqadoTheme.spacing.xl,
                vertical = AvoqadoTheme.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Text(text = country.flag, fontSize = 22.sp)
        Text(
            text = country.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "+${country.dialCode}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
