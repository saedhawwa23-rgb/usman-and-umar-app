package com.opendroid.ai.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * USMAN AND UMAR color palette — resolved via CompositionLocal so all screens
 * automatically adapt to the active theme (light / dark).
 */
data class OpenDroidColors(
    val background: Color,
    val surface: Color,
    val cardBackground: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentNeonGreen: Color,
    /** Calmer green for large filled button surfaces (neon is for thin marks only). */
    val accentGreenButton: Color,
    val accentPurple: Color,
    val accentCyan: Color,
    val accentRed: Color,
    val accentOrange: Color = Color(0xFFFF9500),
    val isDark: Boolean,
    // ── v3.10.4 brand identity: navy · emerald · electric-blue ──────────────────
    /** Deep navy page background for the Plugin and Remote dashboards. */
    val navyDeep: Color = Color(0xFF0B1220),
    /** Elevated navy surface for dashboard cards. */
    val navySurface: Color = Color(0xFF111C33),
    /** Emerald: the brand's primary "healthy / connected" accent. */
    val emerald: Color = Color(0xFF10B981),
    /** Emerald tuned for large filled button surfaces. */
    val emeraldButton: Color = Color(0xFF059669),
    /** Electric blue: the brand's secondary "active / informational" accent. */
    val electricBlue: Color = Color(0xFF3B82F6),
    /** Softer electric blue for thin marks and borders. */
    val electricBlueSoft: Color = Color(0xFF60A5FA),
    /** A muted success tint for card backgrounds (low alpha applied at the call site). */
    val onBrand: Color = Color(0xFFFFFFFF),
)

// ── Dark palette: Classic Pure Black (#000000) ───────────────
val DarkPalette = OpenDroidColors(
    background = Color(0xFF000000),      // Classic pure OLED black
    surface = Color(0xFF0A0A0A),         // Deep charcoal surface
    cardBackground = Color(0xFF141416),  // Elevated card surface
    borderColor = Color(0xFF27272A),     // Zinc 800 hairline border
    textPrimary = Color(0xFFFFFFFF),     // Pure white
    textSecondary = Color(0xFFA1A1AA),   // Zinc 400 silver
    accentNeonGreen = Color(0xFFFFFFFF), // High-contrast monochrome (replaces green)
    accentGreenButton = Color(0xFFFFFFFF), // Crisp white button
    accentPurple = Color(0xFFA855F7),    // Electric purple
    accentCyan = Color(0xFF38BDF8),      // Sky sapphire
    accentRed = Color(0xFFEF4444),       // Clean alert red
    accentOrange = Color(0xFFFF9500),
    isDark = true,
    // Brand identity on the dark (OLED) surface: emerald and electric-blue read as vivid marks.
    navyDeep = Color(0xFF0B1220),
    navySurface = Color(0xFF111C33),
    emerald = Color(0xFF34D399),         // emerald-400: high contrast on navy/black
    emeraldButton = Color(0xFF047857),   // emerald-700: white text meets WCAG AA (>=4.5)
    electricBlue = Color(0xFF60A5FA),    // blue-400: high contrast on navy/black
    electricBlueSoft = Color(0xFF93C5FD),
    onBrand = Color(0xFFFFFFFF)
)

// ── Light palette: Classic Pure White (#FFFFFF) ─────────────
val LightPalette = OpenDroidColors(
    background = Color(0xFFFFFFFF),      // Classic pure white
    surface = Color(0xFFF8F9FA),         // Crisp light surface
    cardBackground = Color(0xFFFFFFFF),  // Pure white card
    borderColor = Color(0xFFE4E4E7),     // Zinc 200 hairline border
    textPrimary = Color(0xFF09090B),     // Deep obsidian black
    textSecondary = Color(0xFF71717A),   // Zinc 500 slate
    accentNeonGreen = Color(0xFF09090B), // High-contrast monochrome (replaces green)
    accentGreenButton = Color(0xFF09090B), // Crisp black button
    accentPurple = Color(0xFF7E22CE),    // Purple
    accentCyan = Color(0xFF0284C7),      // Ocean sapphire
    accentRed = Color(0xFFDC2626),       // Clean alert red
    accentOrange = Color(0xFFD97706),
    isDark = false,
    // Brand identity on the light surface: darker emerald/blue so text and marks stay AA-legible.
    navyDeep = Color(0xFF0B1220),
    navySurface = Color(0xFF111C33),
    emerald = Color(0xFF047857),         // emerald-700
    emeraldButton = Color(0xFF047857),   // emerald-700: white text meets WCAG AA (>=4.5)
    electricBlue = Color(0xFF1D4ED8),    // blue-700
    electricBlueSoft = Color(0xFF2563EB),
    onBrand = Color(0xFFFFFFFF)
)

val LocalOpenDroidColors = compositionLocalOf { DarkPalette }

/** Access the active palette from any @Composable */
object AppTheme {
    val colors: OpenDroidColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenDroidColors.current
}

// ── Dynamic Composable top-level aliases ────────────────────
// These allow all existing screens and composables to automatically
// adapt to Light / Dark theme dynamically without hardcoding DarkPalette!

val DarkBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.background

val DarkSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surface

val CardBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.cardBackground

val BorderColor: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.borderColor

val TextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.textPrimary

val TextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.textSecondary

val AccentNeonGreen: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentNeonGreen

val AccentGreenButton: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentGreenButton

val AccentPurple: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentPurple

val AccentCyan: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentCyan

val AccentRed: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentRed

val AccentOrange: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentOrange

// ── v3.10.4 brand identity aliases (navy · emerald · electric-blue) ──────────
val NavyDeep: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.navyDeep

val NavySurface: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.navySurface

val Emerald: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.emerald

val EmeraldButton: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.emeraldButton

val ElectricBlue: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.electricBlue

val ElectricBlueSoft: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.electricBlueSoft

val OnBrand: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.onBrand

