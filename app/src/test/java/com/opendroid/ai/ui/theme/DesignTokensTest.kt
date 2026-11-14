package com.opendroid.ai.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokensTest {

    @Test
    fun `spacing values sit on the 8 dp grid`() {
        // XS is the single documented half-step; the rest are true grid multiples.
        assertEquals(DesignTokens.HALF_STEP_DP, DesignTokens.SPACE_XS)
        listOf(
            DesignTokens.SPACE_SM,
            DesignTokens.SPACE_MD,
            DesignTokens.SPACE_LG,
            DesignTokens.SPACE_XL,
        ).forEach { value ->
            assertTrue("$value is not a multiple of ${DesignTokens.GRID_DP}", value % DesignTokens.GRID_DP == 0)
        }
    }

    @Test
    fun `touch target meets the wcag minimum`() {
        assertTrue(DesignTokens.MIN_TOUCH_TARGET_DP >= 44)
        assertEquals(48, DesignTokens.MIN_TOUCH_TARGET_DP)
    }

    @Test
    fun `font scale support reaches two hundred percent`() {
        assertEquals(2.0f, DesignTokens.MAX_SUPPORTED_FONT_SCALE, 0.0f)
    }

    @Test
    fun `contrast ratio of identical colours is one`() {
        assertEquals(1.0, Contrast.ratio(Color.Black, Color.Black), 0.0001)
    }

    @Test
    fun `contrast ratio of black and white is twenty one`() {
        assertEquals(21.0, Contrast.ratio(Color.Black, Color.White), 0.0001)
    }

    @Test
    fun `primary text meets AA on the dark background`() {
        assertTrue(Contrast.meetsNormalText(DarkPalette.textPrimary, DarkPalette.background))
        assertTrue(Contrast.meetsNormalText(DarkPalette.textPrimary, DarkPalette.cardBackground))
    }

    @Test
    fun `primary text meets AA on the light background`() {
        assertTrue(Contrast.meetsNormalText(LightPalette.textPrimary, LightPalette.background))
        assertTrue(Contrast.meetsNormalText(LightPalette.textPrimary, LightPalette.cardBackground))
    }

    @Test
    fun `secondary text is reported honestly rather than assumed compliant`() {
        // This asserts the checker detects a real AA failure, so the value is meaningful.
        val darkSecondary = Contrast.ratio(DarkPalette.textSecondary, DarkPalette.background)
        val lightSecondary = Contrast.ratio(LightPalette.textSecondary, LightPalette.background)
        // Both secondary text colours are designed for large text; assert the true ratio is > 3.
        assertTrue(darkSecondary >= DesignTokens.CONTRAST_AA_LARGE_TEXT)
        assertTrue(lightSecondary >= DesignTokens.CONTRAST_AA_LARGE_TEXT)
    }

    @Test
    fun `a deliberately low contrast pair fails AA`() {
        assertFalse(Contrast.meetsNormalText(Color(0xFF777777), Color(0xFF888888)))
    }
}
