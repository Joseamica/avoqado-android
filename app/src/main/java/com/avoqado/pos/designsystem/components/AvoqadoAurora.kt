package com.avoqado.pos.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import com.avoqado.pos.designsystem.theme.AvoqadoLoaderGreen
import com.avoqado.pos.designsystem.theme.AvoqadoLoaderSeed
import com.avoqado.pos.designsystem.theme.Success
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fondo negro con "aurora" de marca: manchas de luz verde-lima y coral que
 * derivan muy despacio, mezcladas de forma ADITIVA para que se vean como luz y
 * no como círculos de pintura encima.
 *
 * Pensado para pantallas que viven horas encendidas mostrando lo mismo (el
 * letrero de cara al cliente, el landing de la caja):
 *
 * - **El movimiento es lento a propósito, y también es la defensa contra el
 *   burn-in.** Nada se queda quieto el tiempo suficiente para marcar el panel.
 * - **Los periodos son primos entre sí** (17s / 23s / 29s / 37s): el patrón
 *   tarda semanas en repetirse igual, así que nadie que esté formado en la fila
 *   lo ve "hacer loop".
 * - **Barato de dibujar**: cuatro gradientes radiales y dos capas de sombra por
 *   frame, sin blur (que además pide API 31+ y estas terminales no siempre
 *   llegan).
 */
@Composable
fun AvoqadoAuroraBackground(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = rememberInfiniteTransition(label = "aurora")

    // Una fase 0→1 por mancha. LinearEasing porque un ease haría que la luz
    // frene y acelere en los extremos, y eso se nota como tirón.
    val phases = AuroraBlobs.map { blob ->
        t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(blob.periodMs, easing = LinearEasing)),
            label = "phase",
        )
    }

    Box(modifier = modifier, contentAlignment = contentAlignment) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // El negro se pinta AQUÍ, no en un `background()` del Box: el
            // BlendMode.Plus de las manchas necesita tener el negro como
            // destino en esta misma capa para sumarse como luz.
            drawRect(Color.Black)

            val major = maxOf(size.width, size.height)
            AuroraBlobs.forEachIndexed { i, blob ->
                val angle = phases[i].value * 2f * Math.PI.toFloat()
                val center = Offset(
                    x = size.width * (blob.baseX + blob.driftX * cos(angle)),
                    y = size.height * (blob.baseY + blob.driftY * sin(angle)),
                )
                val radius = major * blob.radius
                // 🔴 Elipse girada, no círculo. Con círculos esto se leía como
                // pelotas de lava lamp: el ojo encuentra el borde redondo y ya
                // no ve luz, ve una bola. Estirada en un eje y girando despacio
                // el borde deja de ser reconocible y por fin parece aurora.
                withTransform({
                    rotate(
                        degrees = blob.baseRotation + 22f * sin(angle),
                        pivot = center,
                    )
                    scale(blob.stretchX, blob.stretchY, pivot = center)
                }) {
                    drawCircle(
                        // 🔴 La caída manda más que el color. Con radios enormes
                        // y caída lineal las manchas se solapaban en TODA la
                        // pantalla: verde militar plano y sucio, luz de sótano.
                        // Con el núcleo concentrado (el 40% interior) y caída
                        // rápida quedan vetas de luz separadas por negro real.
                        brush = Brush.radialGradient(
                            0f to blob.color.copy(alpha = blob.alpha),
                            0.40f to blob.color.copy(alpha = blob.alpha * 0.30f),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                        // Aditivo: donde dos manchas se cruzan la luz se suma,
                        // que es justo lo que la hace leerse como aurora.
                        blendMode = BlendMode.Plus,
                    )
                }
            }

            // 🔴 Sombra CENTRAL, no sólo viñeta de orillas. Con las manchas
            // sueltas el centro se llenaba de luz y el logo —que es verde—
            // desaparecía contra un fondo verde brillante. Esta capa devuelve
            // el negro justo donde va el contenido: la luz se queda en las
            // orillas, el logo y el texto siempre caen sobre negro limpio.
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.72f),
                    0.55f to Color.Black.copy(alpha = 0.30f),
                    1f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = major * 0.46f,
                ),
            )

            // Viñeta de orillas: encuadra y evita que la luz se derrame fuera
            // de la pantalla como un halo barato.
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.70f to Color.Black.copy(alpha = 0.10f),
                    1f to Color.Black.copy(alpha = 0.58f),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = major * 0.85f,
                ),
            )
        }
        content()
    }
}

/**
 * Una mancha de luz. Posiciones y desplazamientos son FRACCIONES del tamaño de
 * la pantalla, para que se vea igual en la pantalla chica del cliente
 * (1280x800) que en una tablet.
 */
private data class AuroraBlob(
    val color: Color,
    val alpha: Float,
    val baseX: Float,
    val baseY: Float,
    val driftX: Float,
    val driftY: Float,
    val radius: Float,
    val periodMs: Int,
    /** Estiramiento de la elipse. Nunca 1:1 — un círculo se ve como pelota. */
    val stretchX: Float,
    val stretchY: Float,
    val baseRotation: Float,
)

/**
 * Solo colores que YA son de la marca: el verde y el hueso del logo, más el
 * verde de éxito como tercera capa de profundidad. Nada inventado.
 */
private val AuroraBlobs = listOf(
    // 🔴 El núcleo va casi a color pleno. Con alpha ~0.5 sobre negro el verde
    // lima se convierte en verde bosque (alpha sobre negro es literalmente
    // multiplicar el color): parecía luz de sótano. Lo que da el aire caro es
    // núcleo brillante + caída amplia, no una mancha entera a media luz.
    AuroraBlob(
        color = AvoqadoLoaderGreen,
        alpha = 0.90f,
        baseX = 0.18f, baseY = 0.22f,
        driftX = 0.11f, driftY = 0.10f,
        radius = 0.44f,
        periodMs = 17_000,
        stretchX = 1.55f, stretchY = 0.80f, baseRotation = -18f,
    ),
    AuroraBlob(
        color = AvoqadoLoaderSeed,
        alpha = 0.72f,
        baseX = 0.84f, baseY = 0.76f,
        driftX = 0.11f, driftY = 0.11f,
        radius = 0.40f,
        periodMs = 23_000,
        stretchX = 1.40f, stretchY = 0.85f, baseRotation = 24f,
    ),
    AuroraBlob(
        color = Success,
        alpha = 0.46f,
        baseX = 0.86f, baseY = 0.20f,
        driftX = 0.12f, driftY = 0.10f,
        radius = 0.36f,
        periodMs = 29_000,
        stretchX = 1.30f, stretchY = 0.90f, baseRotation = -40f,
    ),
    // Cuarta, abajo a la izquierda: sin ella esa esquina quedaba muerta y la
    // composición se sentía cargada hacia una diagonal.
    AuroraBlob(
        color = AvoqadoLoaderGreen,
        alpha = 0.46f,
        baseX = 0.16f, baseY = 0.82f,
        driftX = 0.10f, driftY = 0.09f,
        radius = 0.38f,
        periodMs = 37_000,
        stretchX = 1.45f, stretchY = 0.78f, baseRotation = 12f,
    ),
)
