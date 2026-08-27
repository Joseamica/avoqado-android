package com.avoqado.pos.pos.presentation.checkout

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.presentation.cart.CartViewModel

// MARK: - Preferences Keys

private const val MOSAIC_PREFS_NAME = "avoqado_mosaic_prefs"
private const val MOSAIC_PRODUCT_IDS_KEY = "mosaic_product_ids"

// MARK: - Mosaic Preferences Helper

object MosaicPreferences {
    fun getSavedProductIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(MOSAIC_PREFS_NAME, Context.MODE_PRIVATE)
        val idsString = prefs.getString(MOSAIC_PRODUCT_IDS_KEY, null)
        return idsString?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun saveProductIds(context: Context, ids: List<String>) {
        val prefs = context.getSharedPreferences(MOSAIC_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(MOSAIC_PRODUCT_IDS_KEY, ids.joinToString(",")).apply()
    }
}

// MARK: - Mosaic Config View

@Composable
fun MosaicConfigView(
    cartViewModel: CartViewModel,
    /** Las pestañas que HOY se ven. Se reordenan éstas y no `InputTab.entries`:
     *  una apagada desde el dashboard no puede resucitar desde aquí. */
    pestanasVisiblesHoy: List<InputTab> = InputTab.entries,
    /** Avisa al mostrador que releea la preferencia, para que el cambio se vea
     *  al instante en vez de al reabrir la app. */
    onLayoutChanged: () -> Unit = {},
) {
    val allProducts by cartViewModel.products.collectAsState()
    val context = LocalContext.current

    // Densidad y orden se aplican AL INSTANTE, sin botón de guardar: el efecto
    // se ve en la pestaña de al lado, así que confirmarlo sería ceremonia. El
    // mosaico sí conserva su "Guardar" porque ahí se marcan muchos productos.
    var tamano by remember { mutableStateOf(CheckoutLayoutPrefs.tileSize(context)) }
    // La llave es `pestanasVisiblesHoy`: si promociones se prende o se apaga
    // mientras esta pantalla está abierta, la lista tiene que reflejarlo en vez
    // de quedarse con una foto vieja.
    var orden by remember(pestanasVisiblesHoy) {
        mutableStateOf(ordenarPestanas(CheckoutLayoutPrefs.ordenGuardado(context), pestanasVisiblesHoy))
    }

    // Load saved state
    val savedIds = remember { MosaicPreferences.getSavedProductIds(context) }

    // Local mutable state: selected product IDs (in order)
    var selectedIds by remember {
        mutableStateOf(savedIds.filter { id -> allProducts.any { it.id == id } })
    }
    var saved by remember { mutableStateOf(false) }

    // Build the display list: selected products first (in order), then unselected (alphabetical)
    val selectedProducts = selectedIds.mapNotNull { id -> allProducts.find { it.id == id } }
    val unselectedProducts = allProducts
        .filter { it.id !in selectedIds }
        .sortedBy { it.name }
    val displayList = selectedProducts + unselectedProducts

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Todo en UNA lista que baja: en un teléfono los tres bloques no caben
        // de golpe, y un ajuste que hay que adivinar que existe no existe.
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = AvoqadoTheme.spacing.lg),
        ) {
            item {
                SeccionTitulo(
                    titulo = "Tamaño de los productos",
                    detalle = "Sólo en este aparato. El cambio se ve al instante.",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AvoqadoTheme.spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    TileSize.entries.forEach { opcion ->
                        ChipTamano(
                            opcion = opcion,
                            seleccionado = opcion == tamano,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                tamano = opcion
                                CheckoutLayoutPrefs.guardarTileSize(context, opcion)
                                onLayoutChanged()
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                SeccionTitulo(
                    titulo = "Orden de las pestañas",
                    detalle = "La primera es la que abre al entrar al mostrador.",
                )
            }

            itemsIndexed(orden, key = { _, tab -> "tab_${tab.name}" }) { index, tab ->
                FilaPestana(
                    tab = tab,
                    esPrimera = index == 0,
                    esUltima = index == orden.lastIndex,
                    onMover = { delta ->
                        orden = moverPestana(orden, index, index + delta)
                        CheckoutLayoutPrefs.guardarOrden(context, orden)
                        onLayoutChanged()
                    },
                )
            }

            item {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SeccionTitulo(
                    titulo = "Configurar mosaico",
                    detalle = if (selectedIds.isNotEmpty()) {
                        "${selectedIds.size} productos en la cuadrícula rápida"
                    } else {
                        "Selecciona los productos que aparecerán en la cuadrícula rápida"
                    },
                )
            }

            if (displayList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AvoqadoTheme.spacing.xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No hay productos disponibles",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(displayList, key = { it.id }) { product ->
                    val isChecked = product.id in selectedIds

                    Column(modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg)) {
                        MosaicProductRow(
                            product = product,
                            isChecked = isChecked,
                            onToggle = {
                                selectedIds = if (isChecked) {
                                    selectedIds - product.id
                                } else {
                                    selectedIds + product.id
                                }
                                saved = false
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        // Save button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
        ) {
            PrimaryButton(
                text = if (saved) "Guardado" else "Guardar",
                onClick = {
                    MosaicPreferences.saveProductIds(context, selectedIds)
                    saved = true
                },
                enabled = !saved,
            )
        }
    }
}

// MARK: - Cómo se ve el mostrador (densidad + orden de pestañas)

@Composable
private fun SeccionTitulo(titulo: String, detalle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            ),
    ) {
        Text(
            text = titulo,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
        Text(
            text = detalle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChipTamano(
    opcion: TileSize,
    seleccionado: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fondo = if (seleccionado) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val tinta = if (seleccionado) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // Ancho fijo del dibujito para que las tres miniaturas se comparen entre sí.
    val anchoBarra = (48f - (opcion.columnasDeMuestra - 1) * 2f) / opcion.columnasDeMuestra

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(fondo)
            .border(
                width = 1.dp,
                color = if (seleccionado) fondo else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
            )
            .clickable(onClick = onClick)
            .padding(AvoqadoTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Una miniatura del efecto real: mismas columnas y misma proporción que
        // va a tomar el tile. Se elige mirando, no leyendo una etiqueta.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(opcion.columnasDeMuestra) {
                Box(
                    modifier = Modifier
                        .width(anchoBarra.dp)
                        .height((anchoBarra / opcion.categoryAspect).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tinta.copy(alpha = 0.55f)),
                )
            }
        }
        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = opcion.etiqueta,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (seleccionado) FontWeight.SemiBold else FontWeight.Normal,
            color = tinta,
        )
    }
}

@Composable
private fun FilaPestana(
    tab: InputTab,
    esPrimera: Boolean,
    esUltima: Boolean,
    onMover: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
        )

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tab.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Decir cuál abre primero es la mitad del ajuste: sin esto, mover el
            // orden parece cosmético y nadie descubre que cambió el arranque.
            if (esPrimera) {
                Text(
                    text = "Abre en esta",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        BotonMover(
            icono = Icons.Filled.KeyboardArrowUp,
            descripcion = "Subir ${tab.label}",
            habilitado = !esPrimera,
            onClick = { onMover(-1) },
        )
        BotonMover(
            icono = Icons.Filled.KeyboardArrowDown,
            descripcion = "Bajar ${tab.label}",
            habilitado = !esUltima,
            onClick = { onMover(1) },
        )
    }
}

@Composable
private fun BotonMover(
    icono: ImageVector,
    descripcion: String,
    habilitado: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = habilitado) {
        Icon(
            imageVector = icono,
            contentDescription = descripcion,
            tint = if (habilitado) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
        )
    }
}

// MARK: - Product Row with Checkbox

@Composable
private fun MosaicProductRow(
    product: Product,
    isChecked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Checkbox icon
        Icon(
            imageVector = if (isChecked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (isChecked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
        )

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        // Product initials avatar
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = product.name.take(2).uppercase(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        // Product name + price
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = product.displayPrice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Drag handle hint (visual only)
        if (isChecked) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
            )
        }
    }
}
