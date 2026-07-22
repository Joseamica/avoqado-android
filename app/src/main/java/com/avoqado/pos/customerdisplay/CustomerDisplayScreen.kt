package com.avoqado.pos.customerdisplay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

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
) {
    val content by state.content.collectAsState()
    val venueName by state.venueName.collectAsState()
    val venueLogoUrl by state.venueLogoUrl.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val c = content) {
            is CustomerContent.Idle -> IdleBranding(venueName, venueLogoUrl)
            is CustomerContent.Cart -> CartMirror(c)
            is CustomerContent.Rating -> RatingPrompt(c, onRating)
            is CustomerContent.Tip -> TipPrompt(c, onTip)
            is CustomerContent.Total -> TotalOnly(c)
            is CustomerContent.Charging -> ChargingPrompt(c)
            is CustomerContent.Done -> DonePrompt(c)
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

@Composable
private fun IdleBranding(venueName: String?, logoUrl: String?) {
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
}

// MARK: - Carrito en vivo

@Composable
private fun CartMirror(cart: CustomerContent.Cart) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Tu compra",
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
            items(cart.items, key = { it.id }) { item ->
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
                        text = money(item.totalPrice),
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
            if (cart.discountCents > 0) {
                TotalRow("Subtotal", money(cart.subtotalCents))
                // El descuento se DESTACA en verde: que el cliente vea que le
                // rebajaron, no que pase como una línea gris más.
                TotalRow(
                    label = "Descuento",
                    value = "−${money(cart.discountCents)}",
                    highlight = true,
                )
            }
            if (cart.taxCents > 0) TotalRow("Impuestos", money(cart.taxCents))
            Spacer(Modifier.height(AvoqadoTheme.spacing.xs))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = money(cart.totalCents),
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
private fun DonePrompt(c: CustomerContent.Done) {
    // El QR del recibo digital solo existe cuando el server dio una URL; sin ella
    // la pantalla NO miente con un código que no lleva a nada. El cliente escanea
    // con SU teléfono y ahí elige cómo quiere el recibo (WhatsApp, correo),
    // califica y factura. Teclear en esta pantalla NO es posible: el teclado de
    // Android sale en la del cajero, no en la del cliente (verificado en hardware).
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
        // Con QR el monto se achica para que quepan encabezado + QR + subtítulo
        // en los 800px de alto de la pantalla del cliente; sin QR luce en grande.
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
            // Fondo blanco fijo con marco: el QR debe escanear igual en modo
            // oscuro (un QR claro sobre fondo oscuro no lee en muchos teléfonos).
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

private fun money(cents: Int): String = "$%,.2f".format(cents / 100.0)
