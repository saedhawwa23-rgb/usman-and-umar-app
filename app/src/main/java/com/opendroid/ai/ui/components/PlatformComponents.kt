package com.opendroid.ai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.DesignTokens

/**
 * v3.10.4: the shared visual language for the Plugin and Remote dashboards.
 *
 * One place for the brand gradient header, the status pills, the metric tiles and the card shell, so
 * both platforms look like the same product and the look can be asserted in a JVM test. Every
 * interactive element keeps a >= 48 dp touch target (see [DesignTokens.MIN_TOUCH_TARGET_DP]).
 */

/** The semantic status a pill or live dot is reporting. Colour is chosen from the active theme. */
enum class PlatformStatus { HEALTHY, ACTIVE, WARNING, ERROR, IDLE, INFO }

@Composable
private fun statusColor(status: PlatformStatus): Color {
    val c = AppTheme.colors
    return when (status) {
        PlatformStatus.HEALTHY -> c.emerald
        PlatformStatus.ACTIVE -> c.electricBlue
        PlatformStatus.WARNING -> c.accentOrange
        PlatformStatus.ERROR -> c.accentRed
        PlatformStatus.IDLE -> c.textSecondary
        PlatformStatus.INFO -> c.electricBlueSoft
    }
}

/** A compact status pill: a coloured dot plus a short label. Non-interactive, so no touch target. */
@Composable
fun StatusPill(
    text: String,
    status: PlatformStatus,
    modifier: Modifier = Modifier,
) {
    val color = statusColor(status)
    Surface(
        modifier = modifier.semantics { contentDescription = "$text: ${status.name.lowercase()}" },
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A pulsing "live" dot for a genuinely active connection. Respects reduced-motion via a slow fade. */
@Composable
fun LiveDot(status: PlatformStatus, modifier: Modifier = Modifier) {
    val target = statusColor(status)
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        modifier = modifier
            .size(10.dp)
            .alpha(if (status == PlatformStatus.IDLE) 1f else pulse)
            .background(target, CircleShape)
            .semantics { contentDescription = "Live status ${status.name.lowercase()}" }
    )
}

/** A metric tile: a big number/value with a label, for the dashboard summary rows. */
@Composable
fun MetricTile(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(DesignTokens.RADIUS_MD.dp),
        color = c.navySurface.copy(alpha = if (c.isDark) 0.55f else 0.06f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, color = c.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = c.textSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The premium brand header: a navy gradient banner with a title, subtitle and optional trailing. */
@Composable
fun BrandHeader(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val accent = c.electricBlue
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = c.navyDeep,
        shape = RoundedCornerShape(bottomStart = DesignTokens.RADIUS_LG.dp, bottomEnd = DesignTokens.RADIUS_LG.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(c.navyDeep, c.navySurface, accent.copy(alpha = 0.30f))
                    )
                )
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = accent.copy(alpha = 0.18f),
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = c.electricBlueSoft,
                            modifier = Modifier.padding(10.dp).size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = c.onBrand, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(subtitle, color = c.onBrand.copy(alpha = 0.72f), fontSize = 12.sp, maxLines = 2)
                }
                trailing?.invoke()
            }
        }
    }
}

/** A dashboard card shell with an optional icon, title, subtitle, status pill and body. */
@Composable
fun PlatformCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    status: Pair<String, PlatformStatus>? = null,
    content: @Composable () -> Unit,
) {
    val c = AppTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignTokens.RADIUS_MD.dp),
        color = c.cardBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.borderColor),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = c.electricBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(subtitle, color = c.textSecondary, fontSize = 11.sp)
                    }
                }
                status?.let { StatusPill(text = it.first, status = it.second) }
            }
            content()
        }
    }
}

/** The primary brand button (emerald). Keeps a 48 dp minimum height. */
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = AppTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = DesignTokens.MIN_TOUCH_TARGET_DP.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.emeraldButton,
            contentColor = c.onBrand,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** The secondary brand button (outlined electric-blue). Keeps a 48 dp minimum height. */
@Composable
fun BrandOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = AppTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = DesignTokens.MIN_TOUCH_TARGET_DP.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.electricBlue),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.electricBlue.copy(alpha = 0.6f)),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** A single segment in a [SegmentedTabs] control. */
data class PlatformTab(val label: String, val badge: Int? = null)

/** A horizontal segmented control for grouping a dashboard into sections. */
@Composable
fun SegmentedTabs(
    tabs: List<PlatformTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = c.navySurface.copy(alpha = if (c.isDark) 0.45f else 0.05f),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.borderColor),
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val bg by animateColorAsState(
                    if (selected) c.electricBlue.copy(alpha = 0.22f) else Color.Transparent,
                    label = "tabbg",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = DesignTokens.MIN_TOUCH_TARGET_DP.dp)
                        .background(bg, RoundedCornerShape(11.dp))
                        .clickable { onSelect(index) }
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.label,
                        color = if (selected) c.electricBlue else c.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (tab.badge != null && tab.badge > 0) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = CircleShape, color = c.electricBlue.copy(alpha = 0.25f)) {
                            Text(
                                tab.badge.toString(),
                                color = c.electricBlue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A section heading with an optional trailing count, for grouping cards under a title. */
@Composable
fun SectionHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(modifier.fillMaxWidth()) {
        Text(title, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(subtitle, color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A friendly empty state: an icon, a title and a hint. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(32.dp))
        Text(title, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(hint, color = c.textSecondary, fontSize = 12.sp)
    }
}

/** Padding helper for dashboard lists. */
val PlatformListPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
