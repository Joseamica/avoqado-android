package com.avoqado.pos.pos.presentation.promotions

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.components.TierBadge
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.pos.data.EstadoCatalogo
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.PromotionGroup
import com.avoqado.pos.pos.data.model.PromotionOption
import com.avoqado.pos.pos.presentation.checkout.InputTab
import com.avoqado.pos.tpvsettings.data.PanelMode
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

// ──────────────────────────────────────────────────────────────────────────
// La caída automática — lógica PURA, espejada en iOS (Task 5)
// ──────────────────────────────────────────────────────────────────────────

/** Lo que mide una celda de producto legible. */
const val ANCHO_MINIMO_CELDA_PRODUCTO_DP = 120

/** Columnas que quiere la cuadrícula de productos. */
const val COLUMNAS_CUADRICULA_PRODUCTOS = 3

/** Qué fracción del ancho se queda la columna de entrada. NO cambia al abrir el lateral. */
const val FRACCION_COLUMNA_ENTRADA = 0.5

/**
 * Piso ESTRICTO que impone la cuadrícula de productos: 720dp.
 *
 * La columna de entrada se queda con el 50% del ancho **también con el panel
 * lateral abierto** — quien paga la tercera columna es el carrito, no la
 * cuadrícula (ver el `Row` de `CheckoutScreen`). Así que lo único que la
 * cuadrícula exige es que ese 50% dé para 3 celdas de 120dp: 360 / 0.5 = 720.
 */
const val ANCHO_ESTRICTO_PANEL_LATERAL_DP: Int =
    ((ANCHO_MINIMO_CELDA_PRODUCTO_DP * COLUMNAS_CUADRICULA_PRODUCTOS) / FRACCION_COLUMNA_ENTRADA).toInt()

/**
 * Ancho mínimo REAL para ofrecer el panel lateral.
 *
 * 🔴 **No es una derivación: es una elección con margen, y hay que decirlo.** El
 * piso estricto de la cuadrícula es 720 ([ANCHO_ESTRICTO_PANEL_LATERAL_DP]),
 * pero a 720 el otro 50% se parte en DOS columnas de ~180dp, y ahí una tarjeta
 * con gancho + nombre + imagen se ve apretada. A 960 cada columna lateral queda
 * en ~240dp, que es lo que se eligió.
 *
 * Queda pendiente ajustarlo con una tablet real enfrente — es un juicio de
 * legibilidad, no un cálculo.
 *
 * 🔴 iOS usa la MISMA cifra en puntos (Task 5). Si cambia aquí, cambia allá en
 * el mismo trabajo: si divergen, el mismo local ve el panel lateral en la
 * tablet y no en el iPad, y nadie entiende por qué.
 */
const val ANCHO_MINIMO_PANEL_LATERAL_DP = 960

/** A partir de cuántas promociones vale la pena el buscador. */
const val PROMOCIONES_PARA_BUSCADOR = 8

/** Confirmado con el server: este local no tiene promociones. */
const val TEXTO_SIN_PROMOCIONES = "Aún no hay promociones. Créalas desde el dashboard."

/** No confirmamos nada: no se pudo preguntar y no había nada en disco. */
const val TEXTO_NO_SE_PUDO_CARGAR = "No pudimos cargar las promociones. Revisa tu conexión."

/**
 * Qué escribe el panel cuando no hay NINGUNA tarjeta que pintar. `null` = todavía
 * no sabemos, va el spinner.
 *
 * 🔴 Los dos textos son distintos porque los dos estados son distintos, y la
 * diferencia es lo que el cajero hace después: "créalas desde el dashboard" manda
 * a REHACER algo que probablemente ya existe, y sólo se puede decir cuando el
 * server contestó. Si no se pudo preguntar, lo que toca es reintentar con red.
 * Colapsar los dos en un booleano es el defecto que este mapeo existe para
 * impedir.
 */
fun mensajeSinTarjetas(estado: EstadoCatalogo): String? = when (estado) {
    EstadoCatalogo.SIN_CARGAR, EstadoCatalogo.CARGANDO -> null
    EstadoCatalogo.CARGADO -> TEXTO_SIN_PROMOCIONES
    EstadoCatalogo.NO_SE_PUDO -> TEXTO_NO_SE_PUDO_CARGAR
}

/**
 * Permiso que el server exige para aplicar una promoción, en los DOS caminos
 * (`createOrderWithItems` y el reducer de `ADD_ITEMS`). Se reusa el de
 * descuentos a propósito: aplicar una promoción regala mercancía, es el mismo
 * acto de negocio. Espejo por nombre EXACTO con server e iOS.
 */
const val PERMISO_APLICAR_PROMOCION = "discounts:apply"

/**
 * Dónde va el panel de verdad: el ajuste del local, corregido por el ancho REAL
 * de la superficie.
 *
 * `HIDDEN` nunca se vuelve visible — es una preferencia de layout del propio
 * negocio, no un candado que haya que explicar.
 */
fun resolverModoPanel(ajuste: PanelMode, anchoDp: Int): PanelMode =
    if (ajuste == PanelMode.SIDE_PANEL && anchoDp < ANCHO_MINIMO_PANEL_LATERAL_DP) PanelMode.TAB else ajuste

/**
 * Qué pestañas se pintan en la pantalla de cobro.
 *
 * 🔴 `PROMOS` entra SÓLO cuando el panel va como pestaña: con el panel lateral
 * el cajero tendría DOS entradas a lo mismo, y con `HIDDEN` el local lo apagó
 * desde su dashboard.
 *
 * Vive aquí y no en la pantalla porque es la misma regla que iOS tiene que
 * copiar, y porque `private` dentro del archivo de UI no se puede testear.
 *
 * @param siempreComoPestana para el layout de un solo panel (teléfono), donde no
 *   existe la tercera columna: cualquier modo que no sea `HIDDEN` se ve como
 *   pestaña, para que no desaparezca en silencio.
 */
fun pestanasVisibles(
    modoPanelPromos: PanelMode,
    siempreComoPestana: Boolean = false,
): List<InputTab> {
    val hayPestanaPromos = modoPanelPromos == PanelMode.TAB ||
        (siempreComoPestana && modoPanelPromos != PanelMode.HIDDEN)
    return InputTab.entries.filter { it != InputTab.PROMOS || hayPestanaPromos }
}

// ──────────────────────────────────────────────────────────────────────────
// Textos derivados de la promoción — puros y testeables
// ──────────────────────────────────────────────────────────────────────────

/**
 * El gancho grande de la tarjeta.
 *
 * 🔴 `priceCents` SÓLO existe en `FIXED_TOTAL` (schema.prisma:15358). En un
 * `PER_UNIT` el precio sale del producto elegido, así que pintar `priceCents`
 * ahí mostraría "$0.00" — dinero que miente. Por eso el 2x1 se deriva de
 * `quantity`/`chargedQuantity` y, si no se puede, se dice "Promo" y ya.
 *
 * 🔴 El `else` es para un `pricingMode` que esta versión de la app NO conoce
 * (el campo viaja como String y el server puede estrenar modos). NO puede caer
 * al camino de `FIXED_TOTAL`: pintaría un `priceCents` cuya semántica
 * desconocemos. Un modo nuevo degrada a "Promo", nunca a un precio equivocado.
 */
fun ganchoDePromocion(promocion: Promotion): String = when (promocion.pricingMode) {
    "PER_UNIT" -> promocion.groups.firstOrNull()?.options?.firstOrNull()
        ?.takeIf { it.chargedQuantity > 0 && it.quantity > it.chargedQuantity }
        ?.let { "${it.quantity}x${it.chargedQuantity}" }
        ?: GANCHO_SIN_DATO

    "FIXED_TOTAL" -> promocion.priceCents.takeIf { it > 0 }?.let { dinero(it) } ?: GANCHO_SIN_DATO

    else -> GANCHO_SIN_DATO
}

const val GANCHO_SIN_DATO = "Promo"

/** Qué trae el combo, para leerlo sin abrir nada. */
fun resumenDePromocion(promocion: Promotion): String {
    promocion.description?.takeIf { it.isNotBlank() }?.let { return it }
    val productos = promocion.groups
        .flatMap { it.options }
        .map { it.productName }
        .filter { it.isNotBlank() }
        .distinct()
    return when {
        productos.isEmpty() -> ""
        productos.size <= 3 -> productos.joinToString(" + ")
        else -> productos.take(3).joinToString(" + ") + " +${productos.size - 3}"
    }
}

/** "18:00" → "6:00 pm". `null` si no se puede leer (nunca se inventa una hora). */
fun horaLegible(hhmm: String?): String? {
    val partes = hhmm?.split(":") ?: return null
    val hora = partes.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val minuto = partes.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
    if (hora !in 0..23 || minuto !in 0..59) return null
    val sufijo = if (hora < 12) "am" else "pm"
    val hora12 = (hora % 12).takeIf { it != 0 } ?: 12
    return String.format(Locale.US, "%d:%02d %s", hora12, minuto, sufijo)
}

/**
 * Cuánto falta para que abra, en minutos.
 *
 * 🔴 `ahora` entra por parámetro y sale de la hora DEL NEGOCIO
 * (`VenueTimeZone`), nunca del reloj del aparato: una tablet con la zona
 * equivocada diría "faltan 3 horas" en la cara del cliente.
 */
fun minutosFaltantes(hhmm: String?, ahora: LocalTime): Int? {
    val partes = hhmm?.split(":") ?: return null
    val hora = partes.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val minuto = partes.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
    if (hora !in 0..23 || minuto !in 0..59) return null
    val faltan = ChronoUnit.MINUTES.between(ahora, LocalTime.of(hora, minuto)).toInt()
    // Negativo = abre pasada la medianoche (el server sólo manda lo que abre
    // dentro de 4h, así que una hora "pasada" es de mañana).
    return if (faltan < 0) faltan + MINUTOS_POR_DIA else faltan
}

private const val MINUTOS_POR_DIA = 24 * 60

/**
 * Lo que dice la tarjeta apagada: "Empieza a las 6:00 pm · Faltan 40 minutos".
 *
 * Sin `startsAt` legible devuelve `null` y la tarjeta simplemente no escribe
 * hora — jamás una hora inventada.
 */
fun etiquetaProxima(startsAt: String?, ahora: LocalTime): String? {
    val hora = horaLegible(startsAt) ?: return null
    val faltan = minutosFaltantes(startsAt, ahora)
    val cuenta = faltan?.takeIf { it > 0 }?.let { " · ${faltanTexto(it)}" }.orEmpty()
    return "Empieza a las $hora$cuenta"
}

private fun faltanTexto(minutos: Int): String = when {
    minutos == 1 -> "Falta 1 minuto"
    minutos < 60 -> "Faltan $minutos minutos"
    else -> {
        val horas = minutos / 60
        val resto = minutos % 60
        if (resto == 0) "Faltan $horas h" else "Faltan $horas h $resto min"
    }
}

private fun dinero(centavos: Int): String = String.format(Locale.US, "$%.2f", centavos / 100.0)

// ──────────────────────────────────────────────────────────────────────────
// El panel
// ──────────────────────────────────────────────────────────────────────────

/**
 * Panel de promociones de la pantalla de cobro — pestaña o columna lateral, lo
 * decide [resolverModoPanel] en `CheckoutScreen`.
 *
 * 🔴 **Ningún estado desaparece en silencio.** Sin plan PRO se ve el candado y
 * qué plan lo prende; sin el permiso de aplicar se ve el panel y a quién
 * pedírselo; sin promociones publicadas se dice dónde crearlas. Lo único que
 * oculta el panel entero es `HIDDEN`, que es una preferencia de layout que el
 * propio dueño eligió en el dashboard y puede revertir ahí.
 *
 * Composable SIN estado: todo entra por parámetros y el toque sale por
 * [onPromotionTap]. Quien mete la promoción al carrito (y abre la hoja de
 * opciones de un combo) es la Task 6 — aquí sólo se ve y se toca.
 *
 * Plan: .superpowers/sdd/2026-08-15-promociones-pos-cliente/task-4-brief.md
 */
@Composable
fun PromotionsPanel(
    vigentes: List<Promotion>,
    proximas: List<Promotion>,
    estado: EstadoCatalogo,
    planPermitido: Boolean,
    puedeAplicar: Boolean,
    onPromotionTap: (Promotion) -> Unit,
    modifier: Modifier = Modifier,
    ahora: LocalTime = LocalTime.now(VenueTimeZone.zoneId()),
) {
    var busqueda by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AvoqadoTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        EncabezadoPanel(planPermitido = planPermitido)

        // Candado de plan (PRO). Mismo bloque manual que usa el carrito para
        // referidos (ReferralCaptureSection): la superficie es una columna, no
        // una pantalla, y el `PlanGate` de desenfoque no tendría NADA que
        // desenfocar — sin el plan el catálogo llega vacío a propósito.
        if (!planPermitido) {
            Text(
                text = "Esta función es parte del plan Pro. " +
                    "Actívala desde tu dashboard web (Configuración → Plan).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        // 🔴 Tres cosas distintas que un booleano colapsaría en una: "todavía no
        // sé" (spinner), "el server dijo que no hay" (créalas en el dashboard) y
        // "no pude preguntar" (revisa tu conexión). El catálogo arranca vacío y se
        // llena cuando responde el fetch: decir "créalas desde el dashboard" antes
        // de tiempo, o cuando lo que falló fue la red, es mentirle al cajero de un
        // local que SÍ tiene promociones — y encima mandarlo a rehacerlas.
        // Ojo al ORDEN: esto sólo corre si no hay NINGUNA tarjeta. Con cache viejo
        // en pantalla se pintan las promociones y nunca se habla de errores.
        if (vigentes.isEmpty() && proximas.isEmpty()) {
            val mensaje = mensajeSinTarjetas(estado)
            if (mensaje == null) {
                CargandoPromociones()
            } else {
                Text(
                    text = mensaje,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // Sin el permiso el panel NO desaparece: el server exige
        // `discounts:apply` en los dos caminos, así que tocar una tarjeta
        // devolvería un 403 pelón. Se dice antes y a quién pedírselo.
        if (!puedeAplicar) {
            AvisoSinPermiso()
        }

        val hayMuchas = vigentes.size + proximas.size > PROMOCIONES_PARA_BUSCADOR
        if (hayMuchas) {
            SearchPillField(
                query = busqueda,
                onQueryChange = { busqueda = it },
                placeholder = "Buscar promoción",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val vigentesFiltradas = vigentes.filtradasPor(busqueda)
        val proximasFiltradas = proximas.filtradasPor(busqueda)

        if (vigentesFiltradas.isEmpty() && proximasFiltradas.isEmpty()) {
            Text(
                text = "Ninguna promoción coincide con «$busqueda».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            contentPadding = PaddingValues(bottom = AvoqadoTheme.spacing.xxl),
        ) {
            items(vigentesFiltradas, key = { it.id }) { promocion ->
                TarjetaPromocion(
                    promocion = promocion,
                    habilitada = puedeAplicar,
                    onTap = { onPromotionTap(promocion) },
                )
            }

            if (proximasFiltradas.isNotEmpty()) {
                item(key = "encabezado-proximas") {
                    Text(
                        text = "Próximamente",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = AvoqadoTheme.spacing.sm),
                    )
                }
                items(proximasFiltradas, key = { "proxima-${it.id}" }) { promocion ->
                    // No tocables a propósito: todavía no abre. La tarjeta dice
                    // a qué hora y cuánto falta, que es la herramienta de venta
                    // ("a las 6 son 2x1"), no un botón muerto.
                    TarjetaPromocion(
                        promocion = promocion,
                        habilitada = false,
                        onTap = {},
                        apagada = true,
                        pieDePagina = etiquetaProxima(promocion.startsAt, ahora),
                    )
                }
            }
        }
    }
}

private fun List<Promotion>.filtradasPor(busqueda: String): List<Promotion> {
    val texto = busqueda.trim()
    if (texto.isEmpty()) return this
    return filter { promocion ->
        promocion.name.contains(texto, ignoreCase = true) ||
            promocion.description?.contains(texto, ignoreCase = true) == true ||
            promocion.groups.any { grupo ->
                grupo.options.any { it.productName.contains(texto, ignoreCase = true) }
            }
    }
}

@Composable
private fun EncabezadoPanel(planPermitido: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.LocalOffer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xs))
        Text(
            text = "Promociones",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!planPermitido) {
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            TierBadge(tierLabel = "Pro")
        }
    }
}

@Composable
private fun CargandoPromociones() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
        Text(
            text = "Cargando promociones…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AvisoSinPermiso() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
        Text(
            text = "Pídele a tu administrador el permiso para aplicar promociones.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TarjetaPromocion(
    promocion: Promotion,
    habilitada: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    apagada: Boolean = false,
    pieDePagina: String? = null,
) {
    val colorTexto = if (apagada || !habilitada) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val acento = if (apagada || !habilitada) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (apagada) 0.25f else 0.5f))
            .then(if (habilitada) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        // Gancho grande: es lo que el cajero lee de reojo con fila enfrente.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                .background(acento.copy(alpha = 0.12f))
                .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = AvoqadoTheme.spacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ganchoDePromocion(promocion),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = acento,
                maxLines = 1,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = promocion.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = colorTexto,
                maxLines = 2,
            )
            resumenDePromocion(promocion).takeIf { it.isNotBlank() }?.let { resumen ->
                Text(
                    text = resumen,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            pieDePagina?.let { pie ->
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xxs))
                    Text(
                        text = pie,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }

        if (!promocion.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = promocion.imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Previews
// ──────────────────────────────────────────────────────────────────────────

private fun promocionDemo(
    id: String = "promo-1",
    nombre: String = "Martes de 2x1",
    pricingMode: String = "PER_UNIT",
    priceCents: Int = 0,
    startsAt: String? = null,
) = Promotion(
    id = id,
    name = nombre,
    description = null,
    imageUrl = null,
    type = "BUNDLE",
    pricingMode = pricingMode,
    priceCents = priceCents,
    startsAt = startsAt,
    groups = listOf(
        PromotionGroup(
            id = "g1",
            name = "Cervezas",
            options = listOf(
                PromotionOption(
                    id = "o1",
                    productId = "p1",
                    quantity = 2,
                    chargedQuantity = 1,
                    productName = "Cerveza Victoria",
                    productPriceCents = 4500,
                ),
            ),
        ),
    ),
)

@Preview(name = "Panel - con promociones", showBackground = true, widthDp = 340, heightDp = 520)
@Composable
private fun PreviewPanelConPromociones() {
    PromotionsPanel(
        vigentes = listOf(
            promocionDemo(),
            promocionDemo(id = "p2", nombre = "Combo comida", pricingMode = "FIXED_TOTAL", priceCents = 9900),
        ),
        proximas = listOf(promocionDemo(id = "p3", nombre = "Happy hour", startsAt = "18:00")),
        estado = EstadoCatalogo.CARGADO,
        planPermitido = true,
        puedeAplicar = true,
        onPromotionTap = {},
        ahora = LocalTime.of(17, 20),
    )
}

@Preview(name = "Panel - sin plan Pro", showBackground = true, widthDp = 340, heightDp = 320)
@Composable
private fun PreviewPanelSinPlan() {
    PromotionsPanel(
        vigentes = emptyList(),
        proximas = emptyList(),
        estado = EstadoCatalogo.CARGADO,
        planPermitido = false,
        puedeAplicar = true,
        onPromotionTap = {},
    )
}

@Preview(name = "Panel - sin permiso", showBackground = true, widthDp = 340, heightDp = 420)
@Composable
private fun PreviewPanelSinPermiso() {
    PromotionsPanel(
        vigentes = listOf(promocionDemo()),
        proximas = emptyList(),
        estado = EstadoCatalogo.CARGADO,
        planPermitido = true,
        puedeAplicar = false,
        onPromotionTap = {},
    )
}

@Preview(name = "Panel - sin promociones", showBackground = true, widthDp = 340, heightDp = 320)
@Composable
private fun PreviewPanelVacio() {
    PromotionsPanel(
        vigentes = emptyList(),
        proximas = emptyList(),
        estado = EstadoCatalogo.CARGADO,
        planPermitido = true,
        puedeAplicar = true,
        onPromotionTap = {},
    )
}
