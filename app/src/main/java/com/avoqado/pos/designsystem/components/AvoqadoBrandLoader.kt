package com.avoqado.pos.designsystem.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.AvoqadoLoaderGreen
import com.avoqado.pos.designsystem.theme.AvoqadoLoaderSeed
import kotlin.math.min

private const val AVOQADO_LOADER_CYCLE_MS = 1_800
private const val AVOQADO_VIEWBOX_SIZE = 800f
private const val AVOQADO_GROWTH_STROKE_WIDTH = 194f

internal const val AVOQADO_GROWTH_PATH_DATA =
    "M 595 641 L 570 608 C 544 578 533 552 548 533 C 602 475 633 400 " +
        "611 314 C 586 219 509 158 395 163 C 283 167 198 255 186 367 C 175 455 " +
        "222 519 298 527 C 359 533 394 483 403 430"

private const val AVOQADO_GREEN_PATH_DATA =
    "M395.5 22.7 c-19.1 1 -66.8 8.3 -87 13.2 c-66.4 16.4 -134.1 58.8 -174.8 109.6 " +
        "c-39.4 49.2 -61.2 105.5 -66.8 173 c-1.6 19 -0.6 56.6 2 76.1 c8.1 60.1 27.8 113.5 59.6 161.4 " +
        "c18.1 27.2 36.4 48.2 58 66.4 c24 20.4 38.7 30.1 64 42.6 c32.1 15.8 63.5 24.7 99 28 " +
        "c14.3 1.3 94.7 1.3 109 0 c16.6 -1.6 28.9 -3.8 40.5 -7.5 c18.3 -5.8 22.6 -5.6 29.2 1.3 " +
        "c1.9 2 11.4 15.2 21.1 29.2 c19.2 27.9 24.9 34.8 36.3 43.9 c15.6 12.6 27.8 16.4 52.4 16.5 " +
        "c18.5 0.1 21.5 -0.5 30.8 -6.2 c17.1 -10.3 29.4 -22.8 36.7 -37.3 c5.3 -10.7 7 -19.6 6.2 -32.9 " +
        "c-0.7 -11.9 -3.4 -21.6 -9.8 -34.9 c-5.8 -12.2 -10.8 -19.4 -21.8 -31.6 c-14 -15.4 -21.1 -27.7 -21.1 -36.7 " +
        "c0 -4.7 2.4 -9.1 10.9 -19.7 c35.1 -44.2 56.6 -97.1 62.7 -155.1 c1.9 -18.8 1.5 -55.8 -1 -74.8 " +
        "c-12.5 -95.6 -59.6 -186.8 -126.6 -245.6 c-53.6 -47.1 -113.7 -73.4 -179.4 -78.6 c-12.4 -1 -16.8 -1 -30.1 -0.3 z " +
        "m10.3 153.4 c39.9 4.3 78.7 19.6 112.2 44.2 c12 8.8 33.7 30.5 41.7 41.7 c26 36.6 35.1 75.8 26.8 116.5 " +
        "c-7.9 39 -22.4 81.6 -36 105.5 c-21.5 38 -42.3 52.6 -85 59.8 c-23.8 4.1 -98.4 7.9 -119.5 6.2 " +
        "c-38.9 -3.1 -67.1 -14.9 -89.5 -37.5 c-24.5 -24.5 -38.5 -58.4 -42.6 -102.9 c-2.8 -30.2 1.2 -84.2 8.2 -111.8 " +
        "c13.8 -54.4 50.6 -94.6 103.1 -112.7 c25.9 -8.9 53.6 -12 80.6 -9 z"

private const val AVOQADO_SEED_PATH_DATA =
    "M388.2 267.4 c-23.9 4.6 -43.8 15.2 -61.1 32.7 c-21.2 21.4 -32.4 47.5 -32.3 75.9 " +
        "c0 28.3 9.9 47.3 35.4 68.6 c17.4 14.5 26.4 18.3 57.4 24.1 c18 3.4 21.5 3.7 36.9 3.7 " +
        "c14.6 0.1 18.2 -0.3 25.4 -2.2 c25.6 -6.9 40.9 -21.9 48.1 -47.2 c3.1 -10.8 3.8 -32.8 1.6 -47.3 " +
        "c-5.8 -36.7 -21 -69.6 -40.9 -88.3 c-9.8 -9.2 -18.5 -14.2 -31.2 -17.9 c-11.2 -3.3 -28.3 -4.2 -39.3 -2.1 z"

internal data class AvoqadoLoaderFrame(
    val seedAlpha: Float,
    val tracedGreenAlpha: Float,
    val completeGreenAlpha: Float,
    val growthProgress: Float,
    val markScale: Float,
)

internal fun avoqadoLoaderFrameAt(cycleProgress: Float): AvoqadoLoaderFrame {
    val progress = cycleProgress.coerceIn(0f, 1f)
    return AvoqadoLoaderFrame(
        seedAlpha = when {
            progress < 0.07f -> progress / 0.07f
            progress <= 0.91f -> 1f
            else -> (1f - progress) / 0.09f
        }.coerceIn(0f, 1f),
        tracedGreenAlpha = when {
            progress < 0.12f -> 0f
            progress < 0.18f -> (progress - 0.12f) / 0.06f
            progress <= 0.83f -> 1f
            else -> (1f - progress) / 0.17f
        }.coerceIn(0f, 1f),
        completeGreenAlpha = when {
            progress < 0.52f -> 0f
            progress < 0.64f -> (progress - 0.52f) / 0.12f
            progress <= 0.84f -> 1f
            else -> (1f - progress) / 0.16f
        }.coerceIn(0f, 1f),
        growthProgress = ((progress - 0.13f) / (0.62f - 0.13f)).coerceIn(0f, 1f),
        markScale = when {
            progress < 0.55f -> 0.985f
            progress < 0.70f -> 0.985f + ((progress - 0.55f) / 0.15f) * 0.015f
            progress <= 0.91f -> 1f
            else -> 0.985f + ((1f - progress) / 0.09f) * 0.015f
        },
    )
}

/**
 * Signature loader: the seed appears first and the green mark grows from its
 * rounded lower tail. The vector is drawn directly so it stays sharp at every size.
 */
@Composable
fun AvoqadoBrandLoader(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    if (ValueAnimator.areAnimatorsEnabled()) {
        val transition = rememberInfiniteTransition(label = "avoqadoLoader")
        val cycleProgress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(AVOQADO_LOADER_CYCLE_MS, easing = LinearEasing),
            ),
            label = "avoqadoLoaderCycle",
        )
        AvoqadoBrandLoaderCanvas(
            frame = avoqadoLoaderFrameAt(cycleProgress),
            size = size,
            modifier = modifier,
        )
    } else {
        AvoqadoBrandLoaderCanvas(
            frame = avoqadoLoaderFrameAt(0.72f),
            size = size,
            modifier = modifier,
        )
    }
}

@Composable
private fun AvoqadoBrandLoaderCanvas(
    frame: AvoqadoLoaderFrame,
    size: Dp,
    modifier: Modifier,
) {
    val greenPath = remember { PathParser().parsePathString(AVOQADO_GREEN_PATH_DATA).toPath() }
    val seedPath = remember { PathParser().parsePathString(AVOQADO_SEED_PATH_DATA).toPath() }
    val growthPath = remember { PathParser().parsePathString(AVOQADO_GROWTH_PATH_DATA).toPath() }
    val tracedPath = remember { Path() }
    val growthMeasure = remember(growthPath) {
        PathMeasure().apply { setPath(growthPath, false) }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        tracedPath.reset()
        growthMeasure.getSegment(
            startDistance = 0f,
            stopDistance = growthMeasure.length * frame.growthProgress,
            destination = tracedPath,
            startWithMoveTo = true,
        )

        val canvasSide = min(this.size.width, this.size.height)
        val viewBoxScale = canvasSide / AVOQADO_VIEWBOX_SIZE
        val horizontalOffset = (this.size.width - canvasSide) / 2f
        val verticalOffset = (this.size.height - canvasSide) / 2f

        withTransform({
            translate(horizontalOffset, verticalOffset)
            scale(viewBoxScale, viewBoxScale, pivot = Offset.Zero)
            scale(
                frame.markScale,
                frame.markScale,
                pivot = Offset(AVOQADO_VIEWBOX_SIZE / 2f, AVOQADO_VIEWBOX_SIZE / 2f),
            )
        }) {
            drawPath(seedPath, AvoqadoLoaderSeed.copy(alpha = frame.seedAlpha))

            clipPath(greenPath) {
                drawPath(
                    path = tracedPath,
                    color = AvoqadoLoaderGreen.copy(alpha = frame.tracedGreenAlpha),
                    style = Stroke(
                        width = AVOQADO_GROWTH_STROKE_WIDTH,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }

            drawPath(greenPath, AvoqadoLoaderGreen.copy(alpha = frame.completeGreenAlpha))
        }
    }
}
