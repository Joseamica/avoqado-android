package com.avoqado.pos.payment.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import com.avoqado.pos.designsystem.theme.Warning
import com.avoqado.pos.loyalty.domain.SELLO_MAXIMO
import com.avoqado.pos.loyalty.domain.TarjetaDelSello
import com.avoqado.pos.loyalty.domain.tamanoDeSello

/**
 * La tarjetita de sellos de «Cobro exitoso» (21-oct, mockup aprobado por el founder). 🔴 Espejo de iOS
 * (`TarjetaDeSellosView`): mismos textos, misma regla de «¿caben los círculos?» ([tamanoDeSello]).
 *
 * Nunca se desacomoda: el título baja de línea antes que empujar «N de M», los círculos se encogen hasta 22 dp y, si
 * ni así caben (o son más de 12), va una barra. Toda la tarjeta se lee como UNA frase en TalkBack.
 */
@Composable
fun TarjetaDeSellos(tarjeta: TarjetaDelSello, modifier: Modifier = Modifier) {
    val completa = tarjeta.completa
    Surface(
        color = if (completa) Warning.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
        border = BorderStroke(
            if (completa) 1.5.dp else 1.dp,
            if (completa) Warning.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier.clearAndSetSemantics { contentDescription = tarjeta.descripcion },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
            ) {
                Icon(
                    imageVector = if (completa) Icons.Filled.CardGiftcard else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (completa) Warning else Success,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = tarjeta.titulo,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = tarjeta.cuenta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val tamano = tamanoDeSello(maxWidth.value, tarjeta.total)
                if (tamano != null) {
                    FilaDeCirculos(tarjeta, tamano.dp)
                } else {
                    BarraDeSellos(tarjeta)
                }
            }
            Text(
                text = tarjeta.pie,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilaDeCirculos(tarjeta: TarjetaDelSello, tamano: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (numero in 1..tarjeta.total) {
            val lleno = numero <= tarjeta.llenos
            // El sello de ESTA venta es el último lleno: entra con un «pop», una sola vez.
            val escala = remember { Animatable(if (numero == tarjeta.llenos) 0.3f else 1f) }
            LaunchedEffect(Unit) {
                if (escala.value < 1f) escala.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(tamano)
                    .graphicsLayer {
                        scaleX = escala.value
                        scaleY = escala.value
                    }
                    .clip(CircleShape)
                    .background(if (lleno) Success else MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (lleno) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(tamano * 0.55f))
                } else {
                    // Tamaño atado al CÍRCULO, no a la letra del sistema: con letra grande el número no se sale (TalkBack
                    // lee la frase completa de la tarjeta).
                    Text(
                        text = "$numero",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = with(LocalDensity.current) { (tamano * 0.42f).toSp() },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
        CirculoDelPremio(tarjeta.completa, tamano)
    }
}

@Composable
private fun BarraDeSellos(tarjeta: TarjetaDelSello) {
    // Arranca donde estaba antes de ESTA venta y avanza al nuevo total: el mismo «pop» que los círculos, en barra.
    var avance by remember { mutableStateOf((tarjeta.llenos - 1).coerceAtLeast(0) / tarjeta.total.toFloat()) }
    LaunchedEffect(Unit) { avance = tarjeta.llenos / tarjeta.total.toFloat() }
    val visible by animateFloatAsState(avance, label = "avanceDeSellos")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(visible)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(Success),
            )
        }
        CirculoDelPremio(tarjeta.completa, SELLO_MAXIMO.dp)
    }
}

@Composable
private fun CirculoDelPremio(completa: Boolean, tamano: Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(tamano)
            .clip(CircleShape)
            .background(if (completa) Warning else Warning.copy(alpha = 0.15f)),
    ) {
        Icon(
            Icons.Filled.CardGiftcard,
            contentDescription = null,
            tint = if (completa) Color.White else Warning,
            modifier = Modifier.size(tamano * 0.58f),
        )
    }
}
