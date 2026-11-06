package com.avoqado.pos.onlineorders.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import java.time.Instant

/** Claves de las filas de los folios (la lista las usa para «si estabas hasta arriba, te quedas hasta arriba»). */
internal const val CLAVE_BUSCADOR_FOLIOS = "folios-buscador"
internal const val CLAVE_ERROR_FOLIOS = "folios-error"

/** Toque mínimo de 48 dp (los botones de Material miden 40). */
private val ALTO_MINIMO = 48.dp

/**
 * Un folio de «Pedir por QR y pagar en caja»: «Folio W7K3P» (letras separadas y más grande que el nombre), el nombre, qué pidió y
 * el dinero. «Por pagar» trae «Cobrar» («Cobrar en efectivo» sin red) y, sin dinero y con red, «Cancelar folio» a 16 dp de él
 * (A3). «Por entregar» trae «Listo» (con borde) y «Entregado» (relleno), apagados sin red porque el servidor es quien los cierra;
 * lo cobrado en efectivo en ESTE aparato que aún no sube ([cobradoEnElAparatoCents]) dice «Cobro en efectivo guardado…».
 */
@Composable
fun FolioPorPagarCard(
    pedido: OnlineOrder,
    porEntregar: Boolean,
    ahora: Instant,
    sinConexion: Boolean,
    puedeActualizar: Boolean,
    ocupado: Boolean,
    onListo: () -> Unit,
    onEntregado: () -> Unit,
    modifier: Modifier = Modifier,
    /** Lo cobrado en efectivo en ESTE aparato que la cola aún no sube (`amountCents` de su fila); null = nada. */
    cobradoEnElAparatoCents: Long? = null,
    puedeCobrar: Boolean = false,
    puedeCancelar: Boolean = false,
    onCobrar: () -> Unit = {},
    onCancelar: () -> Unit = {},
) {
    val espacio = AvoqadoTheme.spacing
    Card(
        modifier = modifier.fillMaxWidth().testTag("folio-${pedido.orderId}"),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(espacio.lg), verticalArrangement = Arrangement.spacedBy(espacio.sm)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        append("Folio ")
                        // El código más grande que el nombre y con las letras separadas: la clienta lo dice en voz alta.
                        withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)) { append(pedido.orderNumber) }
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(horaDelPedido(pedido), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (porEntregar) {
                Text(TextosPedidosEnLinea.PARA_RECOGER, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pedido.customerName?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (pedido.items.isNotEmpty()) {
                Text(
                    pedido.items.joinToString(" · ") { "${it.quantity} × ${it.name}" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            pedido.note?.takeIf { it.isNotBlank() }?.let { Text(TextosPedidosEnLinea.notaDelPedido(it), style = MaterialTheme.typography.bodyMedium) }

            if (porEntregar) {
                Text(
                    // Lo cobrado aquí sin red: lo que guardó la cola (no el total ni el saldo de la lista).
                    if (cobradoEnElAparatoCents != null) TextosPedidosEnLinea.pagadoEnEfectivo(formatoPesos(cobradoEnElAparatoCents))
                    else TextosPedidosEnLinea.pagado(formatoPesos(pedido.totalCents)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (cobradoEnElAparatoCents != null) {
                    Text(
                        TextosPedidosEnLinea.COBRO_EN_EFECTIVO_GUARDADO,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("folio-guardado-${pedido.orderId}"),
                    )
                } else {
                    textoDePago(pedido)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (puedeActualizar) {
                    // Los dos pesan igual en la TPV y en el panel: «Listo» con borde y «Entregado» relleno, en ese orden.
                    // Lo cobrado aquí sin subir todavía no es «pagado» para el servidor: esperan a que suba.
                    val enLinea = !sinConexion && !ocupado && cobradoEnElAparatoCents == null
                    // «Entregado» no se deshace: más aire entre los dos para no tocar el equivocado.
                    Row(horizontalArrangement = Arrangement.spacedBy(espacio.md)) {
                        OutlinedButton(onClick = onListo, enabled = enLinea, modifier = Modifier.heightIn(min = ALTO_MINIMO).testTag("folio-listo-${pedido.orderId}")) {
                            Text(TextosPedidosEnLinea.LISTO)
                        }
                        Button(onClick = onEntregado, enabled = enLinea, modifier = Modifier.heightIn(min = ALTO_MINIMO).testTag("folio-entregado-${pedido.orderId}")) {
                            Text(TextosPedidosEnLinea.ENTREGADO)
                        }
                    }
                }
            } else {
                textoDeVencimiento(pedido, ahora, sinConexion)?.let { vence ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(espacio.xs)) {
                        // El ámbar de «vence pronto» va en el punto: como texto no llega a 4.5:1 sobre blanco.
                        if (vence.pronto) Box(Modifier.size(8.dp).background(Warning, CircleShape))
                        Text(vence.texto, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("vence-${pedido.orderId}"))
                    }
                }
                if (hayAbono(pedido)) {
                    Text(
                        TextosPedidosEnLinea.yaPago(formatoPesos(yaPagadoCents(pedido)), formatoPesos(pedido.totalCents)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // La cifra grande es lo que hay que cobrar, con «Cobrar» a su lado (mockup 3a).
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(espacio.md)) {
                    Text(
                        if (hayAbono(pedido)) TextosPedidosEnLinea.falta(formatoPesos(pedido.remainingCents))
                        else TextosPedidosEnLinea.totalACobrar(formatoPesos(pedido.remainingCents)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (puedeCobrar) {
                        Button(
                            onClick = onCobrar,
                            enabled = !ocupado,
                            modifier = Modifier.heightIn(min = ALTO_MINIMO).testTag("folio-cobrar-${pedido.orderId}"),
                        ) { Text(textoDeCobro(sinConexion)) }
                    }
                }
                if (muestraCancelarFolio(pedido, puedeCancelar, sinConexion)) {
                    // Al menos 16 dp de «Cobrar»: los 8 del renglón + estos 8. Siempre pregunta antes (diálogo fuera de la lista).
                    TextButton(
                        onClick = onCancelar,
                        enabled = !ocupado,
                        modifier = Modifier.padding(top = espacio.sm).heightIn(min = ALTO_MINIMO).testTag("folio-cancelar-${pedido.orderId}"),
                    ) { Text(TextosPedidosEnLinea.CANCELAR_FOLIO, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/**
 * Las filas de la pantalla para los folios: buscador, «Por pagar» y «Por entregar» (más sus estados: error con «Reintentar»,
 * vacío con su porqué, sin resultados, «de hace N min», «Cargar más»). El número de cada cabecera nunca contradice su lista.
 */
internal fun LazyListScope.seccionesDeFolios(
    lista: ListaPorPagar?,
    error: Boolean,
    leyendo: Boolean,
    busqueda: String,
    ahora: Instant,
    sinConexion: Boolean,
    puedeActualizar: Boolean,
    ocupados: Set<String>,
    cargandoMas: Boolean,
    onBuscar: (String) -> Unit,
    onListo: (String) -> Unit,
    onEntregado: (OnlineOrder) -> Unit,
    onCargarMas: (seccion: String) -> Unit,
    onReintentar: () -> Unit,
    // A3: cobrar y cancelar un folio; lo cobrado aquí sin subir ya viene movido a «Por entregar» (`conLoCobradoEnElAparato`).
    cobradosEnElAparato: Map<String, Long> = emptyMap(),
    puedeCobrar: Boolean = false,
    puedeCancelar: Boolean = false,
    onCobrar: (OnlineOrder) -> Unit = {},
    onCancelar: (orderId: String) -> Unit = {},
) {
    if (lista == null) {
        if (!error && leyendo) {
            item(key = CLAVE_ERROR_FOLIOS) {
                Text(TextosPedidosEnLinea.FOLIOS_LEYENDO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("folios-leyendo"))
            }
        }
        if (error) {
            item(key = CLAVE_ERROR_FOLIOS) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                    Text(TextosPedidosEnLinea.FOLIOS_ERROR, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onReintentar, modifier = Modifier.heightIn(min = ALTO_MINIMO).testTag("folios-reintentar")) {
                        Text(TextosPedidosEnLinea.REINTENTAR)
                    }
                }
            }
        }
        return
    }
    item(key = CLAVE_BUSCADOR_FOLIOS) { BuscadorDeFolios(busqueda, onBuscar) }

    val busca = busqueda.replace("-", "").replace(" ", "").length >= 3
    val porPagar = if (busca) filtrarFolios(lista.porPagar, busqueda) else lista.porPagar
    val porEntregar = if (busca) filtrarFolios(lista.porEntregar, busqueda) else lista.porEntregar
    val hayMasPorPagar = lista.nextCursors.toPay != null
    val hayMasPorEntregar = lista.nextCursors.toDeliver != null

    if (busca && porPagar.isEmpty() && porEntregar.isEmpty()) {
        // Con más páginas por traer, el folio pudo quedar en una que no se ha cargado: nunca se dice «no encontramos».
        if (hayMasPorPagar || hayMasPorEntregar) {
            item(key = "folios-sin-resultados") {
                Mensaje(
                    TextosPedidosEnLinea.sinResultadosEntre(lista.porPagar.size + lista.porEntregar.size),
                    TextosPedidosEnLinea.SIN_RESULTADOS_ENTRE_AYUDA, "folios-sin-resultados-parciales",
                )
            }
        } else {
            item(key = "folios-sin-resultados") {
                Mensaje(TextosPedidosEnLinea.sinResultados(busqueda.trim()), TextosPedidosEnLinea.SIN_RESULTADOS_AYUDA, "folios-sin-resultados")
            }
            return
        }
    }

    if (porPagar.isNotEmpty() || (!busca && lista.showTile) || (busca && hayMasPorPagar)) {
        item(key = "h-folios-por-pagar") {
            val n = if (busca) porPagar.size else numeroDeSeccion(lista.counts.toPay, porPagar.size, hayMasPorPagar)
            CabeceraDeFolios(TextosPedidosEnLinea.SECCION_POR_PAGAR, n, lista.guardadoHaceMin, "seccion-por-pagar")
        }
        if (porPagar.isEmpty() && !busca) {
            item(key = "folios-por-pagar-vacio") {
                Mensaje(TextosPedidosEnLinea.POR_PAGAR_VACIO, TextosPedidosEnLinea.POR_PAGAR_VACIO_AYUDA, "folios-por-pagar-vacio")
            }
        }
        items(porPagar, key = { "folio-pagar-${it.orderId}" }) { pedido ->
            FolioPorPagarCard(
                pedido, false, ahora, sinConexion, puedeActualizar, pedido.orderId in ocupados, {}, {},
                puedeCobrar = puedeCobrar, puedeCancelar = puedeCancelar,
                onCobrar = { onCobrar(pedido) }, onCancelar = { onCancelar(pedido.orderId) },
            )
        }
        if (hayMasPorPagar) {
            item(key = "folios-pagar-mas") {
                CargarMas(lista.porPagar.size, lista.counts.toPay, sinConexion || cargandoMas, "cargar-mas-por-pagar") { onCargarMas(OnlineOrdersViewModel.SECCION_POR_PAGAR) }
            }
        }
    }
    if (porEntregar.isNotEmpty() || (busca && hayMasPorEntregar)) {
        item(key = "h-folios-por-entregar") {
            val n = if (busca) porEntregar.size else numeroDeSeccion(lista.counts.toDeliver, porEntregar.size, hayMasPorEntregar)
            CabeceraDeFolios(TextosPedidosEnLinea.SECCION_POR_ENTREGAR, n, if (porPagar.isEmpty()) lista.guardadoHaceMin else null, "seccion-por-entregar")
        }
        items(porEntregar, key = { "folio-entregar-${it.orderId}" }) { pedido ->
            FolioPorPagarCard(
                pedido, true, ahora, sinConexion, puedeActualizar, pedido.orderId in ocupados,
                onListo = { onListo(pedido.orderId) }, onEntregado = { onEntregado(pedido) },
                cobradoEnElAparatoCents = cobradosEnElAparato[pedido.orderId],
            )
        }
        if (hayMasPorEntregar) {
            item(key = "folios-entregar-mas") {
                CargarMas(lista.porEntregar.size, lista.counts.toDeliver, sinConexion || cargandoMas, "cargar-mas-por-entregar") { onCargarMas(OnlineOrdersViewModel.SECCION_POR_ENTREGAR) }
            }
        }
    }
}

@Composable
private fun BuscadorDeFolios(texto: String, onCambio: (String) -> Unit) {
    OutlinedTextField(
        value = texto,
        onValueChange = onCambio,
        singleLine = true,
        placeholder = { Text(TextosPedidosEnLinea.BUSCAR_FOLIO) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (texto.isNotEmpty()) {
                IconButton(onClick = { onCambio("") }, modifier = Modifier.size(ALTO_MINIMO)) {
                    Icon(Icons.Outlined.Close, contentDescription = TextosPedidosEnLinea.LIMPIAR_BUSQUEDA)
                }
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("buscador-folios"),
    )
}

@Composable
private fun CabeceraDeFolios(titulo: String, numero: Int, guardadoHaceMin: Long?, etiqueta: String) {
    Row(
        Modifier.padding(top = AvoqadoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Text("$titulo ($numero)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(etiqueta))
        // Sin red la lista sale del aparato: se dice qué tan vieja es (la hora de cada vencimiento ya es fija).
        guardadoHaceMin?.let {
            Text(TextosPedidosEnLinea.deHaceMin(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Mensaje(titulo: String, ayuda: String, etiqueta: String) {
    Column(Modifier.fillMaxWidth().testTag(etiqueta), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs)) {
        Text(titulo, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Text(ayuda, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CargarMas(mostrados: Int, total: Int, apagado: Boolean, etiqueta: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            TextosPedidosEnLinea.mostrandoDe(mostrados, maxOf(total, mostrados)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onClick, enabled = !apagado, modifier = Modifier.fillMaxWidth().heightIn(min = ALTO_MINIMO).testTag(etiqueta)) {
            Text(TextosPedidosEnLinea.CARGAR_MAS)
        }
    }
}
