package com.avoqado.pos.customerdisplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.components.AvoqadoAuroraBackground
import com.avoqado.pos.designsystem.components.Countries
import com.avoqado.pos.designsystem.components.Country
import com.avoqado.pos.designsystem.theme.AvoqadoLoaderGreen
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * Pantalla de cara al CLIENTE. Pensada para la pantalla CHICA del POS de
 * mostrador: tipografía grande, jerarquía de una sola idea por vista y áreas
 * de toque generosas (el cliente la usa de pie, sin lentes puestos a veces).
 */
@Composable
fun CustomerDisplayScreen(
    state: CustomerDisplayState,
    onRating: (Int) -> Unit,
    onTip: (Int) -> Unit,
    onWhatsApp: (String) -> Unit = {},
    onEmail: (String) -> Unit = {},
) {
    val content by state.content.collectAsState()
    val venueName by state.venueName.collectAsState()
    val venueLogoUrl by state.venueLogoUrl.collectAsState()
    // Solo donde el dedo del cliente sí llega a la app (pantalla física táctil):
    // ahí ofrecemos que él mismo teclee su WhatsApp/correo. En pantallas no
    // táctiles queda el QR (lo hace en su teléfono).
    val canInteract by state.customerCapturesInput.collectAsState()
    val receiptSend by state.receiptSend.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val c = content) {
            is CustomerContent.Idle -> IdleBranding(venueName, venueLogoUrl)
            is CustomerContent.Cart -> CartMirror(c)
            is CustomerContent.Upsell ->
                UpsellPrompt(
                    c = c,
                    canInteract = canInteract,
                    onToggle = { state.onUpsellToggled?.invoke(it) },
                    onConfirm = { state.onUpsellConfirmed?.invoke() },
                    onDismiss = { state.onUpsellDismissed?.invoke() },
                )
            is CustomerContent.Rating -> RatingPrompt(c, onRating)
            is CustomerContent.Tip -> TipPrompt(c, onTip)
            is CustomerContent.Total -> TotalOnly(c)
            is CustomerContent.Charging -> ChargingPrompt(c)
            is CustomerContent.Done -> DonePrompt(c, canInteract, receiptSend, onWhatsApp, onEmail) { state.keepAlive() }
        }
    }
}


/**
 * 🔴 Escala propia, NO la de Material. La escala tipográfica de Material está
 * pensada para UI que se mira a un brazo de distancia; esto es SEÑALIZACIÓN
 * que el cliente lee desde el otro lado del mostrador, en una pantalla de
 * 1280x800. Con los estilos normales todo salía chico y flotando en una banda
 * central, con el 60% de la pantalla vacío.
 */
private val CdTitle = 40.sp        // pregunta principal
private val CdAmount = 76.sp       // el número que importa
private val CdBody = 26.sp         // apoyo
private val CdActionMain = 44.sp   // porcentaje dentro del botón
private val CdActionSub = 26.sp    // monto dentro del botón

// MARK: - Sin venta: la marca

/**
 * Sin sesión no hay negocio del cual mostrar marca: `venueDisplayName` y
 * `venueLogo` viven en el almacenamiento que llena el login, así que mientras
 * la caja está en la pantalla de acceso los dos vienen vacíos. Ese es el ÚNICO
 * momento en que la pantalla del cliente habla por Avoqado; en cuanto alguien
 * entra, la marca vuelve a ser la del negocio.
 */
internal fun idleShowsAvoqadoBrand(venueName: String?, logoUrl: String?): Boolean =
    venueName.isNullOrBlank() && logoUrl.isNullOrBlank()

@Composable
private fun IdleBranding(venueName: String?, logoUrl: String?) {
    if (idleShowsAvoqadoBrand(venueName, logoUrl)) {
        AvoqadoIdleBranding()
        return
    }
    VenueIdleBranding(venueName, logoUrl)
}

/**
 * La caja está en el login: del otro lado del mostrador esto es lo primero que
 * ve un cliente, y antes era el nombre "Avoqado" en texto pelón sobre blanco.
 * Ahora es la marca sobre negro con la aurora — el equivalente nativo del video
 * de fondo que iOS tiene en su landing.
 */
@Composable
private fun AvoqadoIdleBranding() {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(1200)) }
    // El halo late más despacio que el logo para que no se sienta un latido
    // único y mecánico.
    val halo by rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 0.82f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(5200), RepeatMode.Reverse),
        label = "haloScale",
    )

    AvoqadoAuroraBackground(modifier = Modifier.fillMaxSize()) {
        // Fracciones del alto real, no dp fijos: la pantalla del cliente es de
        // 1280x800 px pero la densidad cambia entre modelos, y un tamaño fijo
        // se veía bien en una terminal y diminuto en la siguiente.
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val logoSize = maxHeight * 0.30f
            Column(
                modifier = Modifier
                    .padding(AvoqadoTheme.spacing.xxl)
                    .graphicsLayer {
                        alpha = enter.value
                        val s = 0.94f + 0.06f * enter.value
                        scaleX = s; scaleY = s
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(id = com.avoqado.pos.R.drawable.avoqado_logo_mark),
                    contentDescription = "Avoqado",
                    modifier = Modifier
                        .size(logoSize)
                        // 🔴 El resplandor va en `drawBehind` y NO en un Canvas
                        // aparte: un Canvas del tamaño del halo MIDE, y ese
                        // tamaño empujaba el nombre hasta la orilla de abajo
                        // dejando un hueco enorme en medio. Aquí el halo pinta
                        // fuera de los límites del logo sin ocupar layout.
                        .drawBehind {
                            val r = size.minDimension * 1.15f * halo
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0f to AvoqadoLoaderGreen.copy(alpha = 0.40f),
                                    0.42f to AvoqadoLoaderGreen.copy(alpha = 0.12f),
                                    1f to Color.Transparent,
                                    center = center,
                                    radius = r,
                                ),
                                radius = r,
                                blendMode = BlendMode.Plus,
                            )
                        },
                )
                Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
                Text(
                    text = "Avoqado",
                    fontSize = CdTitle,
                    lineHeight = CdTitle * 1.2f,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                    // Blancos explícitos: el tema de esta pantalla es CLARO
                    // (ver CustomerDisplayPresentation), así que `onBackground`
                    // aquí sería negro sobre negro.
                    color = Color.White,
                )
                Spacer(Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    text = "Bienvenido",
                    fontSize = CdBody,
                    color = Color.White.copy(alpha = 0.68f),
                )
            }
        }
    }
}

@Composable
private fun VenueIdleBranding(venueName: String?, logoUrl: String?) {
    // Screensaver del negocio: entra con un fade + leve zoom, y luego "respira"
    // muy despacio (escala sutil) para que la pantalla no se vea muerta ni queme
    // pixeles. Nada llamativo — es un letrero en reposo, no una animación.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(900)) }
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(4000), RepeatMode.Reverse),
        label = "scale",
    )
    val name = venueName?.takeIf { it.isNotBlank() } ?: "Avoqado"

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AvoqadoTheme.spacing.xxl)
                .graphicsLayer {
                    alpha = enter.value
                    val s = (0.96f + 0.04f * enter.value) * breathe
                    scaleX = s; scaleY = s
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 🔴 La marca del NEGOCIO, no la nuestra. El cliente está en la taquería,
            // no en Avoqado. Logo Y nombre juntos: el logo manda, el nombre lo ancla.
            var logoFailed by remember(logoUrl) { mutableStateOf(false) }
            if (!logoUrl.isNullOrBlank() && !logoFailed) {
                coil.compose.AsyncImage(
                    model = logoUrl,
                    contentDescription = name,
                    modifier = Modifier.fillMaxWidth(0.5f).heightIn(max = 360.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    onError = { logoFailed = true },
                )
                Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
            }
            Text(
                text = name,
                fontSize = CdTitle,
                lineHeight = CdTitle * 1.2f,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))
            Text(
                text = "Bienvenido",
                fontSize = CdBody,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Fuera de la Column a propósito: el pie no debe "respirar" con el
        // letrero ni empujar su centrado — es firma, no contenido.
        PoweredByAvoqado(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = AvoqadoTheme.spacing.lg),
        )
    }
}

/**
 * "Powered by Avoqado", muy chico y hasta abajo (founder, 2026-09-01). Sólo en
 * el reposo con marca del negocio: la pantalla sin sesión ya habla entera por
 * Avoqado y ahí el pie sobraría.
 */
@Composable
private fun PoweredByAvoqado(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
    ) {
        Text(
            text = "Powered by",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
        Image(
            painter = painterResource(id = com.avoqado.pos.R.drawable.avoqado_logo_mark),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Avoqado",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Carrito en vivo

@Composable
private fun CartMirror(cart: CustomerContent.Cart) {
    // Mientras el cajero teclea: mismo desglose, sin propina (aún no se pide).
    ReceiptBreakdown(
        title = "Tu compra",
        items = cart.items,
        subtotalCents = cart.subtotalCents,
        discountCents = cart.discountCents,
        taxCents = cart.taxCents,
        tipCents = 0,
        totalCents = cart.totalCents,
    )
}

/**
 * Desglose tipo recibo para la pantalla del cliente: lista de productos arriba
 * y, abajo, subtotal + descuento + impuestos + propina + total. Cada línea
 * opcional aparece solo cuando aplica (descuento/impuesto/propina > 0). Lo usan
 * tanto el espejo del carrito como el paso de cobro.
 */
@Composable
private fun ReceiptBreakdown(
    title: String,
    items: List<com.avoqado.pos.pos.data.model.CartItem>,
    subtotalCents: Int,
    discountCents: Int,
    taxCents: Int,
    tipCents: Int,
    totalCents: Int,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                horizontal = AvoqadoTheme.spacing.xl,
                vertical = AvoqadoTheme.spacing.lg,
            ),
        )
        HorizontalDivider()

        // El último artículo agregado importa más que el primero: la lista
        // crece hacia abajo y el cliente sigue lo que acaba de pasar.
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = AvoqadoTheme.spacing.xl,
                vertical = AvoqadoTheme.spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
        ) {
            items(items, key = { it.id }) { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${item.quantity}×",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // BRUTO: abajo se pinta Subtotal y Descuento por separado,
                        // así que la línea va a precio de lista o los renglones no
                        // sumarían el subtotal que ve el cliente.
                        text = money(item.grossPrice),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Totales: lo que el cliente revisa antes de pagar.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(AvoqadoTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xs),
        ) {
            // Subtotal se muestra cuando hay algo que restar/sumar aparte (para
            // que "Total" no sea idéntico y confunda).
            if (discountCents > 0 || tipCents > 0) {
                TotalRow("Subtotal", money(subtotalCents))
            }
            if (discountCents > 0) {
                // El descuento se DESTACA en verde: que el cliente vea que le
                // rebajaron, no que pase como una línea gris más.
                TotalRow(
                    label = "Descuento",
                    value = "−${money(discountCents)}",
                    highlight = true,
                )
            }
            if (taxCents > 0) TotalRow("Impuestos", money(taxCents))
            // Propina: solo si el cliente dejó algo.
            if (tipCents > 0) TotalRow("Propina", money(tipCents))
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = money(totalCents),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String, highlight: Boolean = false) {
    val color = if (highlight) com.avoqado.pos.designsystem.theme.Success
                else MaterialTheme.colorScheme.onSurfaceVariant
    val weight = if (highlight) FontWeight.Bold else FontWeight.Normal
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            fontWeight = weight,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            fontWeight = weight,
        )
    }
}

// MARK: - Estrellas

// MARK: - "¿Algo más?" — el momento de upsell

/**
 * 🔴 Regla de oro de una pantalla de cliente: NUNCA tapar el total. La barra de
 * arriba se queda fija y el "+ $X" de la vista previa aparece a su lado, para que el
 * cliente siempre sepa cuánto va a pagar.
 *
 * 🔴 Marcar NO cobra. El botón primario nace APAGADO y sólo se prende cuando hay algo
 * marcado: la pantalla nunca invita a confirmar la nada, y refuerza que el toque
 * marca en vez de agregar.
 *
 * En pantallas no táctiles (`canInteract=false`) se pinta lo mismo SIN botones: es
 * señalización para que el cajero la señale. Cero controles muertos esperando un
 * toque que el firmware de la NP511 se queda.
 */
@Composable
private fun UpsellPrompt(
    c: CustomerContent.Upsell,
    canInteract: Boolean,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
    ) {
        // El total, siempre visible.
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Tu total", fontSize = CdBody, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(AvoqadoTheme.spacing.md))
            Text(money(c.cartTotalCents), fontSize = CdAmount, lineHeight = CdAmount * 1.1f, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (c.selectedDeltaCents > 0) {
                Text(
                    "+ ${money(c.selectedDeltaCents)}",
                    fontSize = CdBody,
                    fontWeight = FontWeight.Bold,
                    color = UpsellAccent,
                )
            }
        }
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.surfaceVariant)

        Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
        Text(
            text = "¿Algo más?",
            fontSize = CdTitle,
            lineHeight = CdTitle * 1.2f,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            c.cards.forEach { card ->
                UpsellCardView(
                    card = card,
                    selected = card.ruleId in c.selectedRuleIds,
                    enabled = canInteract,
                    modifier = Modifier.weight(1f),
                    onClick = { onToggle(card.ruleId) },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (canInteract) {
            val hasSelection = c.selectedRuleIds.isNotEmpty()
            Button(
                onClick = onConfirm,
                enabled = hasSelection,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
            ) {
                Text(
                    text = if (hasSelection) "Agregar · ${money(c.selectedDeltaCents)}" else "Agregar",
                    fontSize = CdActionMain,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))
            // "No, gracias" es una salida legítima, no letra chica gris — la misma
            // postura que ya tomó "Sin propina" más abajo en este archivo.
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(74.dp),
                shape = RoundedCornerShape(50),
            ) {
                Text("No, gracias", fontSize = CdBody, fontWeight = FontWeight.Bold)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = AvoqadoTheme.spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Pídelo en la caja",
                    fontSize = CdBody,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** El verde de marca aparece SÓLO en el estado marcado: un acento único significa algo. */
private val UpsellAccent = Color(0xFF7ADD2C)

@Composable
private fun UpsellCardView(
    card: com.avoqado.pos.pos.data.model.UpsellCard,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .height(190.dp)
            .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .then(
                if (selected) {
                    Modifier.border(4.dp, UpsellAccent, RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else 0.62f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!card.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = card.imageUrl,
                        contentDescription = card.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
                Text(
                    card.name,
                    fontSize = CdBody,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        money(card.displayPriceCents),
                        fontSize = CdBody,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    card.badge?.let {
                        Spacer(Modifier.width(AvoqadoTheme.spacing.sm))
                        Text(it, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = UpsellAccent)
                    }
                }
                card.headline?.let {
                    Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
                    Text(
                        it,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingPrompt(c: CustomerContent.Rating, onRating: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "¿Cómo estuvo tu experiencia?",
            fontSize = CdTitle,
            lineHeight = CdTitle * 1.2f,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg)) {
            (1..5).forEach { star ->
                // Área de toque generosa: la usa un desconocido, de pie y de
                // paso. 64dp era el mínimo de una UI de app, no de señalización.
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "$star estrellas",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onRating(star) }
                        .padding(AvoqadoTheme.spacing.sm),
                )
            }
        }
    }
}

// MARK: - Propina

@Composable
private fun TipPrompt(c: CustomerContent.Tip, onTip: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "¿Deseas dejar propina?",
            fontSize = CdTitle,
            lineHeight = CdTitle * 1.2f,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = money(c.amountCents),
            fontSize = CdAmount,
            lineHeight = CdAmount * 1.1f,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.xl))
        // Los botones ocupan el ANCHO de la pantalla. Antes medían ~115px en
        // una pantalla de 1280 y quedaban pegados entre sí: un blanco difícil
        // para alguien que toca de paso, y errarle cuesta dinero al mesero.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
        ) {
            c.suggestions.forEach { percent ->
                val tipCents = c.amountCents * percent / 100
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(150.dp)
                        .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                        .clickable { onTip(tipCents) },
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("$percent%", fontSize = CdActionMain, fontWeight = FontWeight.Bold)
                        Text(money(tipCents), fontSize = CdActionSub, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
        // "Sin propina" es una salida legítima, no letra chica gris.
        Text(
            text = "Sin propina",
            fontSize = CdBody,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onTip(0) }
                .padding(horizontal = AvoqadoTheme.spacing.xxl, vertical = AvoqadoTheme.spacing.lg),
        )
    }
}

// MARK: - Le toca al cajero: solo el total, nada tocable

@Composable
private fun TotalOnly(c: CustomerContent.Total) {
    // Con productos: desglose tipo recibo (lo que el cliente revisa antes de
    // pagar). Sin productos (monto personalizado): el total en grande y ya.
    if (c.items.isNotEmpty()) {
        ReceiptBreakdown(
            title = "Tu compra",
            items = c.items,
            subtotalCents = c.subtotalCents,
            discountCents = c.discountCents,
            taxCents = c.taxCents,
            tipCents = c.tipCents,
            totalCents = c.totalCents,
        )
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Total",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = money(c.totalCents),
            fontSize = CdAmount,
            lineHeight = CdAmount * 1.1f,
            fontWeight = FontWeight.Bold,
        )
    }
}

// MARK: - Cobrando

@Composable
private fun ChargingPrompt(c: CustomerContent.Charging) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = money(c.totalCents),
            fontSize = CdAmount,
            lineHeight = CdAmount * 1.1f,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
        Text(
            text = c.message,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Gracias + recibo

@Composable
private fun DonePrompt(
    c: CustomerContent.Done,
    canInteract: Boolean,
    receiptSend: CustomerDisplayState.ReceiptSend,
    onWhatsApp: (String) -> Unit,
    onEmail: (String) -> Unit,
    onKeepAlive: () -> Unit,
) {
    val url = c.receiptUrl
    // Pantalla TÁCTIL + hay recibo → el cliente elige y TECLEA aquí mismo su
    // WhatsApp/correo (teclado propio). Si no es táctil (o no hay recibo), queda
    // el QR: el cliente lo hace en su teléfono. La detección de táctil es por
    // hardware (ver CustomerDisplayState.setTouchCapable).
    if (canInteract && url != null) {
        DoneInteractive(c.totalCents, url, receiptSend, onWhatsApp, onEmail, onKeepAlive)
    } else {
        DoneQrOnly(c)
    }
}

@Composable
private fun DoneQrOnly(c: CustomerContent.Done) {
    val url: String? = c.receiptUrl
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "¡Gracias por tu compra!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = money(c.totalCents),
            fontSize = if (url != null) 52.sp else CdAmount,
            lineHeight = (if (url != null) 52.sp else CdAmount) * 1.1f,
            fontWeight = FontWeight.Bold,
        )
        url?.let { link ->
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))
            Text(
                text = "Escanea tu recibo",
                fontSize = CdActionMain,
                lineHeight = CdActionMain * 1.15f,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(AvoqadoTheme.spacing.md),
            ) {
                QrCode(content = link, size = 240.dp)
            }
            Spacer(Modifier.height(AvoqadoTheme.spacing.md))
            Text(
                text = "Recíbelo por WhatsApp o correo,\ncalifícanos y factura",
                fontSize = CdBody,
                lineHeight = CdBody * 1.3f,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private enum class DoneMode { Options, WhatsApp, Email }

@Composable
private fun DoneInteractive(
    totalCents: Int,
    url: String,
    receiptSend: CustomerDisplayState.ReceiptSend,
    onWhatsApp: (String) -> Unit,
    onEmail: (String) -> Unit,
    onKeepAlive: () -> Unit,
) {
    var mode by remember { mutableStateOf(DoneMode.Options) }
    var input by remember { mutableStateOf("") }
    // Mismo país/lada que el cajero (misma lista de Countries). México por default.
    var country by remember { mutableStateOf(com.avoqado.pos.designsystem.components.Countries.pinned[0]) }
    var showCountryPicker by remember { mutableStateOf(false) }
    // Cada interacción reinicia el temporizador de regreso al reposo: mientras el
    // cliente teclea, la pantalla NO se le desaparece.
    fun touched() = onKeepAlive()

    // Enviado con éxito: confirmación y ya (el timer de la pantalla la regresa
    // sola al reposo).
    if (receiptSend == CustomerDisplayState.ReceiptSend.Sent) {
        SentConfirmation()
        return
    }

    when (mode) {
        DoneMode.Options -> DoneOptions(
            totalCents = totalCents,
            url = url,
            onWhatsApp = { touched(); input = ""; mode = DoneMode.WhatsApp },
            onEmail = { touched(); input = ""; mode = DoneMode.Email },
        )
        DoneMode.WhatsApp -> Box(modifier = Modifier.fillMaxSize()) {
            PhoneEntryPad(
                country = country,
                national = input,
                sending = receiptSend == CustomerDisplayState.ReceiptSend.Sending,
                error = receiptSend == CustomerDisplayState.ReceiptSend.Error,
                // E.164 mínimo/razonable: 8–15 dígitos nacionales (MX = 10).
                canSend = input.length in 8..15,
                onPickCountry = { touched(); showCountryPicker = true },
                onKey = { touched(); if (input.length < 15) input += it },
                onDelete = { touched(); input = input.dropLast(1) },
                onBack = { touched(); mode = DoneMode.Options },
                // Mismo formato que el cajero: "+{lada}{dígitos}".
                onSend = { touched(); onWhatsApp("+${country.dialCode}$input") },
            )
            if (showCountryPicker) {
                CountryPicker(
                    onPick = { touched(); country = it; showCountryPicker = false },
                    onClose = { touched(); showCountryPicker = false },
                )
            }
        }
        DoneMode.Email -> EntryPad(
            title = "Tu correo",
            value = input,
            placeholder = "correo@ejemplo.com",
            numeric = false,
            sending = receiptSend == CustomerDisplayState.ReceiptSend.Sending,
            error = receiptSend == CustomerDisplayState.ReceiptSend.Error,
            canSend = input.contains("@") && input.substringAfter("@").contains("."),
            onKey = { touched(); input += it },
            onDelete = { touched(); input = input.dropLast(1) },
            onBack = { touched(); mode = DoneMode.Options },
            onSend = { touched(); onEmail(input) },
        )
    }
}

@Composable
private fun SentConfirmation() {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "✓", fontSize = 96.sp, color = com.avoqado.pos.designsystem.theme.Success, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
        Text(
            text = "¡Recibo enviado!",
            fontSize = CdTitle,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        Text(
            text = "Gracias por tu compra",
            fontSize = CdBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneOptions(
    totalCents: Int,
    url: String,
    onWhatsApp: () -> Unit,
    onEmail: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "¡Gracias por tu compra!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
        Text(text = money(totalCents), fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text(
            text = "¿Cómo quieres tu recibo?",
            fontSize = CdActionMain,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            BigChoice("📱", "WhatsApp", Modifier.weight(1f), onWhatsApp)
            BigChoice("✉️", "Correo", Modifier.weight(1f), onEmail)
        }
        Spacer(Modifier.height(AvoqadoTheme.spacing.lg))
        Text(
            text = "o escanéalo con tu teléfono",
            fontSize = CdBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(AvoqadoTheme.spacing.sm),
        ) {
            QrCode(content = url, size = 150.dp)
        }
    }
}

@Composable
private fun BigChoice(emoji: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .height(150.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = emoji, fontSize = 52.sp)
        Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        Text(text = label, fontSize = CdActionSub, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EntryPad(
    title: String,
    value: String,
    placeholder: String,
    numeric: Boolean,
    sending: Boolean,
    error: Boolean,
    canSend: Boolean,
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text(text = title, fontSize = CdActionMain, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(AvoqadoTheme.spacing.sm))
        // Lo tecleado, grande; si está vacío, la pista.
        Text(
            text = value.ifBlank { placeholder },
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (error) {
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            Text(
                text = "No se pudo enviar. Revisa e intenta de nuevo.",
                fontSize = CdBody,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        if (numeric) NumericPad(onKey, onDelete) else EmailPad(onKey, onDelete)
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            PadButton(
                label = "Atrás",
                modifier = Modifier.weight(1f),
                filled = false,
                enabled = !sending,
                onClick = onBack,
            )
            PadButton(
                label = if (sending) "Enviando…" else "Enviar",
                modifier = Modifier.weight(2f),
                filled = true,
                enabled = canSend && !sending,
                onClick = onSend,
            )
        }
    }
}

@Composable
private fun PhoneEntryPad(
    country: Country,
    national: String,
    sending: Boolean,
    error: Boolean,
    canSend: Boolean,
    onPickCountry: () -> Unit,
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AvoqadoTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Text(text = "Tu WhatsApp", fontSize = CdActionMain, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Chip de país + lada, tocable → abre el selector.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onPickCountry)
                    .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                Text(text = country.flag, fontSize = 30.sp)
                Text(text = "+${country.dialCode}", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(text = "▾", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Número nacional tecleado.
            Text(
                text = national.ifBlank { "número" },
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = if (national.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (error) {
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            Text(
                text = "No se pudo enviar. Revisa e intenta de nuevo.",
                fontSize = CdBody,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        NumericPad(onKey, onDelete)
        Spacer(Modifier.height(AvoqadoTheme.spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            PadButton("Atrás", Modifier.weight(1f), filled = false, enabled = !sending, onClick = onBack)
            PadButton(
                label = if (sending) "Enviando…" else "Enviar",
                modifier = Modifier.weight(2f),
                filled = true,
                enabled = canSend && !sending,
                onClick = onSend,
            )
        }
    }
}

@Composable
private fun CountryPicker(onPick: (Country) -> Unit, onClose: () -> Unit) {
    // Overlay a pantalla completa con un scrim; la lista se toca con el dedo.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                // Consumir el clic para que tocar dentro NO cierre.
                .clickable(enabled = false) {},
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Elige tu país",
                    fontSize = CdActionMain,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✕", fontSize = 30.sp)
                }
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(Countries.all, key = { it.isoCode + it.dialCode }) { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c) }
                            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
                    ) {
                        Text(text = c.flag, fontSize = 32.sp)
                        Text(text = c.name, fontSize = 26.sp, modifier = Modifier.weight(1f))
                        Text(
                            text = "+${c.dialCode}",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun NumericPad(onKey: (String) -> Unit, onDelete: () -> Unit, compact: Boolean = false) {
    // `compact` existe por la cara del cliente de la Sunmi D3: 800 px de alto. Con teclas
    // de 96 dp el teclado NO cabía y su última fila —el 0, el borrar y el botón de abajo—
    // quedaba fuera de la pantalla. Con 72 dp entra completo, que es lo que hace usable el
    // respaldo de "no aparezco en la lista" en el aparato real.
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
    Column(verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
                row.forEach { k -> Key(k, Modifier.weight(1f), small = compact) { onKey(k) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm)) {
            Spacer(Modifier.weight(1f))
            Key("0", Modifier.weight(1f), small = compact) { onKey("0") }
            Key("⌫", Modifier.weight(1f), small = compact, onClick = onDelete)
        }
    }
}

@Composable
private fun EmailPad(onKey: (String) -> Unit, onDelete: () -> Unit) {
    val rows = listOf(
        "1234567890".map { it.toString() },
        "qwertyuiop".map { it.toString() },
        "asdfghjkl".map { it.toString() },
        "zxcvbnm".map { it.toString() },
        listOf("@", ".", "_", "-"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k -> Key(k, Modifier.weight(1f), small = true) { onKey(k) } }
                if (row === rows.last()) Key("⌫", Modifier.weight(1f), small = true, onClick = onDelete)
            }
        }
    }
}

@Composable
internal fun Key(label: String, modifier: Modifier, small: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(if (small) 72.dp else 96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = if (small) 26.sp else 34.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainer
        filled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .height(84.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = CdActionSub, fontWeight = FontWeight.Bold, color = fg)
    }
}

private fun money(cents: Int): String = "$%,.2f".format(cents / 100.0)
