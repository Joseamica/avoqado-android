package com.avoqado.pos.designsystem.components

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
 * Full upsell teaser shown IN PLACE of a gated feature's content
 * (mirrors the dashboard FeatureGate: "Incluido en el Plan Pro").
 *
 * Instructional CTA only — no purchase links or in-app payment
 * (store compliance): the plan is activated from the web dashboard.
 */
@Composable
fun PlanGateScreen(
    featureName: String,
    requiredTierLabel: String,
    modifier: Modifier = Modifier,
) {
    val style = styleForTier(requiredTierLabel)
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(AvoqadoTheme.spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(style.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.accent,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = featureName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
            TierBadge(tierLabel = requiredTierLabel)
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = "Incluido en el Plan $requiredTierLabel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xs))
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

@Preview(name = "PlanGate - Pro", showBackground = true)
@Composable
private fun PreviewPlanGatePro() {
    PlanGateScreen(featureName = "Reservas", requiredTierLabel = "Pro")
}

@Preview(name = "PlanGate - Premium", showBackground = true)
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
