package com.avoqado.pos.pos.presentation.product.variantes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.AvoqadoModalBottomSheet
import com.avoqado.pos.designsystem.components.ImmersiveWindow
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import java.util.concurrent.atomic.AtomicBoolean

/** El «deshabilitado» de Material 3: el chip se ve apagado pero sigue tocable (Q3). */
private const val ALFA_SIN_COMBINACION = 0.38f

/**
 * La hoja para elegir la variante de una familia (spec 2026-10-14 §11). Molde `PromotionSheet`: un grupo por eje, botón apagado
 * hasta completar y, debajo, por qué. La lógica de chips y combinaciones vive en `Familias.kt`. Espejo de `VariantPickerView.swift`.
 * «Agotado» avisa y nunca bloquea (Q5): una variante agotada se puede agregar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HojaDeVariantes(
    familia: Familia,
    onElegir: (Product) -> Unit,
    onCerrar: () -> Unit,
    struckProductIds: Set<String> = emptySet(),
    disableOutOfStock: Boolean = false,
) {
    var seleccion by remember(familia.groupId) { mutableStateOf(seleccionInicial(familia)) }
    // Una entrega por apertura (Codex P1-2): dos activaciones de «Agregar» pendientes antes de que Compose desmonte la hoja no suman dos
    // unidades. Se marca SINCRÓNICAMENTE antes de entregar; deshabilitar el botón no cubriría eventos que ya están en cola.
    val entregada = remember(familia.groupId) { AtomicBoolean(false) }
    val elegida: Product? = varianteElegida(familia, seleccion)
    val puedeAgregar = elegida?.let { variantSelectable(it, struckProductIds, disableOutOfStock) } == true
    // Techo explícito, como PromotionSheet: dentro de una hoja la altura llega sin acotar.
    val alturaMaximaLista = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    // El chip ELEGIDO va relleno con el contraste de `primary` (oscuro en tema claro, claro en el oscuro), como el negro de iOS: el
    // relleno gris por defecto de Material 3 era menos visible que el borde de los no elegidos (QA del bloque 3).
    val coloresDelChip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )

    AvoqadoModalBottomSheet(
        onDismissRequest = onCerrar,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.xl)
                .padding(bottom = AvoqadoTheme.spacing.xl),
        ) {
            Text(text = familia.nombre, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            LazyColumn(
                modifier = Modifier.heightIn(max = alturaMaximaLista),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
            ) {
                items(familia.ejes, key = { it.nombre }) { eje ->
                    Column {
                        Text(
                            text = eje.nombre,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.xs),
                        )
                        // Cada chip mide lo que su texto y el renglón se parte: «Azul marino» y «Azul cielo» nunca se cortan.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                        ) {
                            eje.valores.forEach { valor ->
                                val disponible = valorDisponible(familia, seleccion, eje.nombre, valor)
                                val candidatas = familia.variantes.filter { v ->
                                    val valores = valoresDe(v)
                                    valores[eje.nombre] == valor && seleccion.all { (e, x) -> e == eje.nombre || valores[e] == x }
                                }
                                val tachada = candidatas.isNotEmpty() && candidatas.all { it.id in struckProductIds }
                                val restringida = (struckProductIds.isNotEmpty() || disableOutOfStock) &&
                                    candidatas.none { variantSelectable(it, struckProductIds, disableOutOfStock) }
                                FilterChip(
                                    selected = seleccion[eje.nombre] == valor,
                                    onClick = { seleccion = elegir(familia, seleccion, eje.nombre, valor) },
                                    label = { Text(valor, textDecoration = if (tachada) TextDecoration.LineThrough else null) },
                                    enabled = !restringida,
                                    colors = coloresDelChip,
                                    modifier = if (disponible) Modifier else Modifier
                                        .alpha(ALFA_SIN_COMBINACION)
                                        .semantics { contentDescription = "$valor, ${TextosVariantes.SIN_COMBINACION}" },
                                )
                            }
                        }
                    }
                }
            }

            elegida?.let { v ->
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                Text(text = v.displayPrice, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                avisoDeExistencia(v)?.let { aviso ->
                    Text(
                        text = aviso,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (v.isOutOfStock) Warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            PrimaryButton(
                // Con la variante, su precio con la regla del tile (`displayPrice`, «/kg» incluido); sin ella, sin un precio inventado.
                text = elegida?.let { TextosVariantes.agregarCon(it.displayPrice) } ?: TextosVariantes.AGREGAR,
                onClick = { elegida?.let {
                    if (variantSelectable(it, struckProductIds, disableOutOfStock) && entregada.compareAndSet(false, true)) onElegir(it)
                } },
                enabled = puedeAgregar,
                fullWidth = true,
            )
            if (elegida == null) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                // Nunca un botón muerto sin explicación.
                Text(
                    text = TextosVariantes.FALTA_ELEGIR,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Sólo si la variante rastrea inventario; sin número no se dice nada (nunca un cero inventado). */
internal fun avisoDeExistencia(v: Product): String? = when {
    v.trackInventory != true -> null
    v.isOutOfStock -> TextosVariantes.AGOTADO_AVISO
    else -> v.availableQuantity?.let { TextosVariantes.disponibles(it) }
}

/**
 * La hoja de la familia VIGENTE, igual en la cuadrícula y en el buscador (Codex 7): quien la abre guarda sólo el `groupId`. Si la
 * familia deja de existir (cambio de negocio, separada, refresco) se cierra sola; la variante elegida se re-resuelve por id contra el
 * catálogo vigente y se entrega a `onElegir`, que es el MISMO camino que tocar un producto suelto en esa pantalla (R1).
 */
@Composable
fun HojaDeLaFamiliaAbierta(viewModel: CartViewModel, abierta: String?, onCerrar: () -> Unit, onElegir: (Product) -> Unit) {
    val familias by viewModel.familias.collectAsState()
    val familia = abierta?.let { familias[it] }
    LaunchedEffect(abierta, familia == null) { if (abierta != null && familia == null) onCerrar() }
    familia?.let { f ->
        HojaDeVariantes(
            familia = f,
            onElegir = { v ->
                onCerrar()
                viewModel.products.value.firstOrNull { it.id == v.id }?.let(onElegir)
            },
            onCerrar = onCerrar,
        )
    }
}
