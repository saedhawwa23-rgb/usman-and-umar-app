package com.opendroid.ai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Stage 3 of v3.10.0: one design system for every screen.
 *
 * These are the single source of truth for spacing, corner radii, touch targets, and contrast.
 * Values are plain constants so they can be asserted in JVM tests without a running UI.
 */
object DesignTokens {

    /** 8 dp spacing grid. Every gap is a multiple of this. */
    const val GRID_DP = 8

    /** The one deliberate half-step, for tight inline spacing only. */
    const val HALF_STEP_DP = 4

    val SPACE_XS = HALF_STEP_DP
    val SPACE_SM = 8
    val SPACE_MD = 16
    val SPACE_LG = 24
    val SPACE_XL = 32

    /** Corner radii scale. */
    val RADIUS_SM = 8
    val RADIUS_MD = 16
    val RADIUS_LG = 24

    /** WCAG 2.2 minimum interactive target. */
    const val MIN_TOUCH_TARGET_DP = 48

    /** The system font scale must stay readable up to 200%. */
    const val MAX_SUPPORTED_FONT_SCALE = 2.0f

    /** WCAG 2.2 AA contrast minimums. */
    const val CONTRAST_AA_NORMAL_TEXT = 4.5
    const val CONTRAST_AA_LARGE_TEXT = 3.0
    const val CONTRAST_AA_NON_TEXT = 3.0
}

/**
 * WCAG 2.x contrast maths, usable from plain JVM tests. [Color] values are 0..1 floats.
 */
object Contrast {

    /** Relative luminance per WCAG 2.2. */
    fun relativeLuminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /** Contrast ratio between two colours, from 1.0 (identical) to 21.0 (black/white). */
    fun ratio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun meetsNormalText(a: Color, b: Color): Boolean =
        ratio(a, b) >= DesignTokens.CONTRAST_AA_NORMAL_TEXT

    fun meetsLargeTextOrNonText(a: Color, b: Color): Boolean =
        ratio(a, b) >= DesignTokens.CONTRAST_AA_LARGE_TEXT
}
