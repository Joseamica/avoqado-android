package com.avoqado.pos.designsystem.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// Tier accent colors (mirror the dashboard: Pro = emerald star, Premium = amber crown).
private val ProAccent = Color(0xFF10B981)
private val PremiumAccent = Color(0xFFF59E0B)

private data class TierBadgeStyle(
    val accent: Color,
    val icon: ImageVector,
)

private fun styleForTier(requiredTierLabel: String): TierBadgeStyle =
    if (requiredTierLabel.contains("Premium", ignoreCase = true)) {
        TierBadgeStyle(accent = PremiumAccent, icon = Icons.Filled.WorkspacePremium)
    } else {
        TierBadgeStyle(accent = ProAccent, icon = Icons.Filled.Star)
    }

/**
 * Small pill badge marking a feature as part of a paid plan
 * (visible-teaser pattern: gated features stay DISCOVERABLE).
 *
 * @param tierLabel "Pro" (emerald star) or "Premium" (amber crown).
 */
@Composable
fun TierBadge(
    tierLabel: String,
    modifier: Modifier = Modifier,
) {
    val style = styleForTier(tierLabel)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(style.accent.copy(alpha = 0.14f))
            .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = null,
            tint = style.accent,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = tierLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = style.accent,
        )
    }
}

/**
 * Blur-preview paywall WRAPPER (mirrors the dashboard FeatureGate).
 *
 * Unlocked → renders [content] untouched. Locked → renders [content] as a
 * blurred (API 31+; dimmed fallback below), NON-INTERACTIVE preview hidden
 * from accessibility, with the upsell card centered on top. The user sees
 * WHAT they are missing instead of a replacement screen.
 *
 * Presentation-only: the entitlement decision ([locked]) stays at the call
 * site (ViewModel + PlanManager — fail-open / exempt behavior untouched).
 * Instructional CTA only — no purchase links or in-app payment (store
 * compliance): the plan is activated from the web dashboard.
 *
 * Use for FULL SCREENS / section panes. For small inline surfaces keep the
 * compact teasers ([PlanGateInlineNote], [TierBadge]).
 */
@Composable
fun PlanGate(
    locked: Boolean,
    featureName: String,
    requiredTierLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!locked) {
        content()
        return
    }

    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(modifier = modifier.fillMaxSize()) {
        // Real content as backdrop: blurred on API 31+ (RenderEffect),
        // alpha-dimmed below. Hidden from TalkBack/accessibility.
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (supportsBlur) {
                        Modifier.blur(8.dp)
                    } else {
                        Modifier.graphicsLayer { alpha = 0.4f }
                    },
                )
                .clearAndSetSemantics { },
        ) {
            content()
        }
        // Scrim: dims the preview AND consumes every pointer event on the
        // Initial pass so the content underneath is fully inert (no clicks,
        // no scroll). Heavier dim when blur isn't available.
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                                .changes
                                .forEach { it.consume() }
                        }
                    }
                }
                .background(
                    MaterialTheme.colorScheme.surface.copy(
                        alpha = if (supportsBlur) 0.45f else 0.7f,
                    ),
                ),
        )
        // Upsell card centered on top of the preview.
        PlanGateCard(
            featureName = featureName,
            requiredTierLabel = requiredTierLabel,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(AvoqadoTheme.spacing.xl),
        )
    }
}

/**
 * The centered upsell card (mirrors the dashboard FeatureGate card:
 * accent icon circle + "INCLUIDO EN PRO/PREMIUM" eyebrow + instructional
 * body — no purchase links, store compliance).
 */
@Composable
private fun PlanGateCard(
    featureName: String,
    requiredTierLabel: String,
    modifier: Modifier = Modifier,
) {
    val style = styleForTier(requiredTierLabel)
    Surface(
        modifier = modifier.widthIn(max = 380.dp),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.xl),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(AvoqadoTheme.spacing.xxl),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(style.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.accent,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
            Text(
                text = "INCLUIDO EN ${requiredTierLabel.uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = style.accent,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
            Text(
                text = featureName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            Text(
                text = "Esta función es parte del plan $requiredTierLabel. " +
                    "Actívala desde tu dashboard web (Configuración → Plan).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Standalone upsell teaser for gated surfaces that have NO real content to
 * preview behind the card. Prefer the [PlanGate] wrapper (blur-preview)
 * whenever the screen naturally renders something.
 */
@Composable
fun PlanGateScreen(
    featureName: String,
    requiredTierLabel: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        PlanGateCard(
            featureName = featureName,
            requiredTierLabel = requiredTierLabel,
        )
    }
}

/**
 * Compact one-row teaser for small gated surfaces (e.g. the referral capture
 * section in the cart) where a full-screen gate would be disproportionate.
 */
@Composable
fun PlanGateInlineNote(
    requiredTierLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TierBadge(tierLabel = requiredTierLabel)
        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
        Text(
            text = "Esta función es parte del plan $requiredTierLabel. " +
                "Actívala desde tu dashboard web (Configuración → Plan).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Previews
// ──────────────────────────────────────────────────────────────────────────

@Preview(name = "PlanGate wrapper - locked (Pro)", showBackground = true)
@Composable
private fun PreviewPlanGateLockedPro() {
    PlanGate(locked = true, featureName = "Descuentos", requiredTierLabel = "Pro") {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            repeat(8) {
                Text(
                    text = "Descuento de ejemplo #$it — 10% en bebidas",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Preview(name = "PlanGateScreen - Premium", showBackground = true)
@Composable
private fun PreviewPlanGatePremium() {
    PlanGateScreen(featureName = "Inventario avanzado", requiredTierLabel = "Premium")
}

@Preview(name = "TierBadge", showBackground = true)
@Composable
private fun PreviewTierBadges() {
    Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TierBadge(tierLabel = "Pro")
        TierBadge(tierLabel = "Premium")
    }
}
