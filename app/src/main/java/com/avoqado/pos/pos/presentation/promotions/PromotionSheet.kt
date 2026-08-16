package com.avoqado.pos.pos.presentation.promotions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.components.ImmersiveWindow
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.PromotionGroup
import com.avoqado.pos.pos.data.model.PromotionOption
import java.util.Locale

// ──────────────────────────────────────────────────────────────────────────
// Qué eligió el cajero, y cuánto cuesta — lógica PURA, espejada en iOS (Task 7)
// ──────────────────────────────────────────────────────────────────────────

/** Un grupo de la promoción y la opción que quedó elegida en él. */
data class OpcionDePromocion(val grupo: PromotionGroup, val opcion: PromotionOption)

/**
 * Qué opción quedó elegida en CADA grupo.
 *
 * 🔴 `null` si algún grupo se quedó sin elegir: **media promoción no se agrega**.
 * El server resuelve el combo desde las `selections`, así que mandarle una
 * incompleta cobraría algo distinto de lo que el cajero cree. Los grupos de una
 * sola opción no se preguntan (decisión del founder) y se resuelven solos.
 */
fun opcionesElegidas(promocion: Promotion, selecciones: Map<String, String>): List<OpcionDePromocion>? {
    if (promocion.groups.isEmpty()) return null
    return promocion.groups.map { grupo ->
        val opcion = grupo.options.firstOrNull { it.id == selecciones[grupo.id] }
            ?: grupo.options.singleOrNull()
            ?: return null
        OpcionDePromocion(grupo, opcion)
    }
}

/** Qué grupos SÍ se preguntan: los que tienen más de una opción. */
fun gruposConEleccion(promocion: Promotion): List<PromotionGroup> =
    promocion.groups.filter { it.options.size > 1 }

/** Los que no se preguntan pero sí se enseñan ("Incluye: papas"). */
fun gruposIncluidos(promocion: Promotion): List<PromotionOption> =
    promocion.groups.filter { it.options.size == 1 }.map { it.options.single() }

/**
 * Lo que va a costar la promoción, en centavos. `null` = **no sabemos calcularla**
 * (un `pricingMode` que esta versión de la app no conoce) y entonces no se
 * muestra precio. Un `0` NO es `null`: es una promoción gratis, y se cobra en 0.
 *
 * 🔴 Esto NO es sólo para pintar: en la venta rápida el importe que se cobra
 * sale del carrito (`PaymentFlowViewModel.currentBaseAmount()`), así que este
 * número es el que termina en la terminal. Por eso reproduce la aritmética que
 * el server ya tiene escrita en `resolvePromotionLines.ts` —incluido el tope de
 * "una promoción nunca cobra MÁS que el catálogo"— en vez de inventar una
 * propia. El precio del PEDIDO lo sigue calculando el server.
 */
fun estimadoDePromocion(promocion: Promotion, selecciones: Map<String, String>): Int? =
    opcionesElegidas(promocion, selecciones)?.let { estimadoDeOpciones(promocion, it) }

/**
 * @see estimadoDePromocion — variante para cuando las opciones ya están resueltas.
 *
 * 🔴 **$0 es un precio, no un "no sé".** `validatePromotionForPublish` sólo
 * rechaza `priceCents < 0`, o sea que una promoción **gratis se publica** y el
 * server la resuelve dejando todas las líneas en cero. Tratar el 0 como "no lo
 * sé" y caer al precio de lista cobraría $150 por lo que la orden registra en
 * $0. Por eso lo único que devuelve `null` es un `pricingMode` que esta versión
 * de la app no conoce: ahí de verdad no sabemos la semántica.
 */
fun estimadoDeOpciones(promocion: Promotion, elegidas: List<OpcionDePromocion>): Int? {
    if (elegidas.isEmpty()) return null
    val bruto = elegidas.sumOf { brutoDeLinea(it.opcion) }
    val objetivo: Long = when (promocion.pricingMode) {
        // El precio lo pone la promoción; el sobreprecio de cada opción se suma.
        // `priceCents = 0` = promoción gratis, y así se cobra.
        "FIXED_TOTAL" ->
            promocion.priceCents.toLong() + elegidas.sumOf { it.opcion.priceDeltaCents.toLong() }

        // El precio sale del PRODUCTO: `chargedQuantity` es lo que se cobra
        // (2x1 = entran 2, se cobra 1; `chargedQuantity = 0` = regalo). Si el
        // catálogo no trae precios, el bruto es 0 y el server también resuelve
        // 0 — se reproduce eso, no se inventa un precio de lista.
        "PER_UNIT" ->
            elegidas.sumOf { it.opcion.productPriceCents.toLong() * it.opcion.chargedQuantity.coerceAtLeast(0) }

        // Modo desconocido (server más nuevo): se degrada a "no sé", nunca a un
        // precio equivocado. Misma ley que `ganchoDePromocion`.
        else -> return null
    }
    // Espejo EXACTO de `resolvePromotionLines`: `discount = max(0, bruto − objetivo)`
    // y `neto = bruto − discount`, o sea `neto = min(bruto, objetivo)`. La
    // promoción nunca cobra más que el catálogo. El `coerceAtLeast(0)` es por si
    // un `priceDeltaCents` negativo pusiera el objetivo bajo cero: dinero
    // negativo no se cobra.
    return minOf(bruto, objetivo).coerceAtLeast(0L).toInt()
}

/**
 * Precio unitario de cada línea —en el mismo orden que [elegidas]— de forma que
 * la suma de las líneas dé EXACTAMENTE el estimado.
 *
 * El reparto es proporcional al bruto, igual que `allocateByWeights` en el
 * server, para que las dos aritméticas no diverjan. El server hace su propio
 * desglose (cada línea a precio de lista + su parte del descuento); lo que aquí
 * tiene que cuadrar es el TOTAL, que es lo que se cobra.
 *
 * ⚠️ **El total del carrito puede diferir del neto del server, y no por
 * centavos impares.** `CartItem` sólo sabe `unitPrice × quantity`, y el neto que
 * le toca a una línea rara vez es múltiplo de su cantidad: un 3x2 de $50 vale
 * 10000 en el server y 3 × round(10000/3) = 9999 aquí. **Cota:
 * `Σ floor(cantidad_i / 2)` centavos.** Medido con fuzz sobre configuraciones
 * publicables (300k casos): 59% difieren, peor caso +11 centavos; con precios
 * "normales" y ≤3 líneas, 36% difieren, hasta ±5. Sólo desaparece cuando todas
 * las cantidades son 1 (el combo típico), que es el caso mayoritario.
 *
 * El estimado ([estimadoDeOpciones]) SÍ es exacto: la desviación nace al bajarlo
 * a un precio unitario entero. El server sigue siendo la autoridad del precio
 * del pedido; cerrar el hueco de raíz —cobrar el total que devuelve el server
 * cuando hay red— es decisión de la Task 8, que es la que habla con él.
 */
fun preciosUnitariosDePromocion(promocion: Promotion, elegidas: List<OpcionDePromocion>): List<Int> {
    val cantidades = elegidas.map { it.opcion.quantity.coerceAtLeast(1) }
    // Sin estimado (modo de precio desconocido) la línea entra al **precio de
    // catálogo pelón**, sin el `priceDeltaCents`: el delta es el sobreprecio que
    // cobra la promoción, y sumarlo cuando ni siquiera sabemos calcularla
    // rompería la única invariante que el server enuncia explícito — una
    // promoción nunca cobra MÁS que el catálogo (`resolvePromotionLines.ts:70-72`).
    val neto = estimadoDeOpciones(promocion, elegidas)
        ?: return elegidas.map { it.opcion.productPriceCents }
    val brutos = elegidas.map { brutoDeLinea(it.opcion) }
    val pesos = if (brutos.sum() > 0) brutos else cantidades.map { it.toLong() }
    return repartirPorPesos(neto.toLong(), pesos).mapIndexed { i, monto ->
        val cantidad = cantidades[i]
        ((monto + cantidad / 2) / cantidad).toInt()
    }
}

private fun brutoDeLinea(opcion: PromotionOption): Long =
    opcion.productPriceCents.toLong() * opcion.quantity.coerceAtLeast(1)

/**
 * Reparte [total] en proporción a [pesos], con los centavos sobrantes al mayor
 * residuo. Las partes suman [total] EXACTO — que es justo lo que un reparto
 * "a ojo" no garantiza.
 */
fun repartirPorPesos(total: Long, pesos: List<Long>): List<Long> {
    if (pesos.isEmpty()) return emptyList()
    val suma = pesos.sum()
    if (suma <= 0L) {
        val base = total / pesos.size
        return pesos.indices.map { i -> base + if (i == 0) total - base * pesos.size else 0L }
    }
    val partes = pesos.map { total * it / suma }.toMutableList()
    var sobrante = total - partes.sum()
    val porResiduo = pesos.indices.sortedByDescending { (total * pesos[it]) % suma }
    var i = 0
    while (sobrante > 0 && i < porResiduo.size) {
        partes[porResiduo[i]] = partes[porResiduo[i]] + 1
        sobrante--
        i++
    }
    return partes
}

private fun pesos(centavos: Int): String = String.format(Locale.US, "$%.2f", centavos / 100.0)

// ──────────────────────────────────────────────────────────────────────────
// La hoja
// ──────────────────────────────────────────────────────────────────────────

/**
 * Hoja para elegir las opciones de una promoción.
 *
 * **Decisión del founder (2026-08-15): TODOS los grupos en una sola pantalla**
 * que scrollea si hay muchos — igual que los modificadores de un producto hoy.
 * No es paso a paso. Los grupos de una sola opción no se preguntan: se listan
 * como "Incluye", para que el cajero pueda decirle al cliente qué trae.
 *
 * Si `promocion.requiereEleccion` es false esta hoja NO se abre: la promoción
 * entra directo al carrito (ver `CheckoutScreen`).
 *
 * Plan: .superpowers/sdd/2026-08-15-promociones-pos-cliente/task-6-brief.md
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromotionSheet(
    promocion: Promotion,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    var selecciones by remember(promocion.id) { mutableStateOf(emptyMap<String, String>()) }
    val preguntables = remember(promocion.id) { gruposConEleccion(promocion) }
    val incluidos = remember(promocion.id) { gruposIncluidos(promocion) }
    val completa = opcionesElegidas(promocion, selecciones) != null
    val estimado = estimadoDePromocion(promocion, selecciones)
    // Techo explícito: dentro de una hoja la altura llega sin acotar, y un
    // `weight` sobre altura infinita truena. Mismo recurso que AvoqadoDialog.
    val alturaMaximaLista = (LocalConfiguration.current.screenHeightDp * 0.55f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ImmersiveWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.xl)
                .padding(bottom = AvoqadoTheme.spacing.xl),
        ) {
            Text(
                text = promocion.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            resumenDePromocion(promocion).takeIf { it.isNotBlank() }?.let { resumen ->
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
                Text(
                    text = resumen,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

            LazyColumn(
                modifier = Modifier.heightIn(max = alturaMaximaLista),
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
            ) {
                if (incluidos.isNotEmpty()) {
                    item(key = "incluye") {
                        Column {
                            TituloDeGrupo(texto = "Incluye")
                            incluidos.forEach { opcion ->
                                Text(
                                    text = etiquetaDeOpcion(opcion),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.xxs),
                                )
                            }
                        }
                    }
                }

                items(
                    count = preguntables.size,
                    key = { index -> preguntables[index].id },
                ) { index ->
                    val grupo = preguntables[index]
                    Column {
                        TituloDeGrupo(texto = grupo.name)
                        grupo.options.forEach { opcion ->
                            FilaDeOpcion(
                                opcion = opcion,
                                seleccionada = selecciones[grupo.id] == opcion.id,
                                onSelect = { selecciones = selecciones + (grupo.id to opcion.id) },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            PrimaryButton(
                // Sin estimado no se escribe un precio inventado.
                text = if (estimado != null) "Agregar al carrito · ${pesos(estimado)}" else "Agregar al carrito",
                onClick = { onConfirm(selecciones) },
                enabled = completa,
                fullWidth = true,
            )
            if (!completa) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
                // Nunca un botón muerto sin explicación.
                Text(
                    text = "Elige una opción de cada grupo para agregar la promoción.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TituloDeGrupo(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.xs),
    )
}

@Composable
private fun FilaDeOpcion(
    opcion: PromotionOption,
    seleccionada: Boolean,
    onSelect: () -> Unit,
) {
    // 🔴 TODA la fila selecciona, no sólo el radio (~20dp): el cajero captura
    // con el dedo y con prisa. Mismo patrón que `ModifierGroupSection`.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = seleccionada, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = AvoqadoTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = seleccionada, onClick = null)
        Text(
            text = etiquetaDeOpcion(opcion),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = AvoqadoTheme.spacing.xs),
        )
        if (opcion.priceDeltaCents > 0) {
            Text(
                text = "+${pesos(opcion.priceDeltaCents)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Cerveza" · "Cerveza × 2" cuando entran varias unidades por esa opción. */
private fun etiquetaDeOpcion(opcion: PromotionOption): String {
    val nombre = opcion.productName.ifBlank { "Producto" }
    return if (opcion.quantity > 1) "$nombre × ${opcion.quantity}" else nombre
}

@Preview(showBackground = true)
@Composable
private fun PromotionSheetPreview() {
    PromotionSheet(
        promocion = Promotion(
            id = "p1",
            name = "Combo del día",
            description = "Plato fuerte + bebida",
            pricingMode = "FIXED_TOTAL",
            priceCents = 9900,
            groups = listOf(
                PromotionGroup(
                    id = "g1",
                    name = "Plato fuerte",
                    options = listOf(
                        PromotionOption(id = "o1", productId = "p-h", productName = "Hamburguesa", productPriceCents = 12000),
                        PromotionOption(
                            id = "o2",
                            productId = "p-e",
                            priceDeltaCents = 1500,
                            productName = "Ensalada de la casa",
                            productPriceCents = 11000,
                        ),
                    ),
                ),
                PromotionGroup(
                    id = "g2",
                    name = "Papas",
                    options = listOf(
                        PromotionOption(id = "o3", productId = "p-p", productName = "Papas a la francesa", productPriceCents = 4000),
                    ),
                ),
            ),
        ),
        onDismiss = {},
        onConfirm = {},
    )
}
