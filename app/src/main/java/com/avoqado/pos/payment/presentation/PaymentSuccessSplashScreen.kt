package com.avoqado.pos.payment.presentation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Success
import kotlinx.coroutines.delay

/**
 * Quick full-screen celebratory splash shown immediately after a payment succeeds.
 * Auto-advances via [onFinished] after [durationMs] ms.
 */
@Composable
fun PaymentSuccessSplashScreen(
    onFinished: () -> Unit,
    durationMs: Long = 1400L,
) {
    var circleVisible by remember { mutableStateOf(false) }
    var checkVisible by remember { mutableStateOf(false) }
    var labelVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        circleVisible = true
        delay(140L)
        checkVisible = true
        delay(80L)
        labelVisible = true
        delay(durationMs)
        onFinished()
    }

    val circleScale by animateFloatAsState(
        targetValue = if (circleVisible) 1f else 0.2f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "circleScale",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (checkVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "checkScale",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (labelVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "labelAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(circleScale)
                    .background(Success, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(72.dp)
                        .scale(checkScale),
                )
            }

            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

            Text(
                text = "Pago exitoso",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alpha(labelAlpha),
            )
        }
    }
}
