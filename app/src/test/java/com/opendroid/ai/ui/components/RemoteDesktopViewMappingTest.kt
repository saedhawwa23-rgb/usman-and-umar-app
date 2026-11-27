package com.opendroid.ai.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The touch surface letterboxes the remote image, so a pointer position is not a remote coordinate
 * until it is un-scaled and un-centred. Getting this wrong makes every click land away from where
 * the user aimed, which is why the maths is a pure function with its own tests.
 */
class RemoteDesktopViewMappingTest {

    @Test
    fun `a matching aspect ratio maps proportionally`() {
        // A 1600x900 remote image shown in a 800x450 box: exactly half scale, no bars.
        val (x, y) = mapToRemote(
            positionX = 400f, positionY = 225f,
            containerWidth = 800, containerHeight = 450,
            remoteWidth = 1600, remoteHeight = 900,
        )

        assertEquals(800, x)
        assertEquals(450, y)
    }

    @Test
    fun `the top left corner maps to the origin`() {
        val (x, y) = mapToRemote(0f, 0f, 800, 450, 1600, 900)

        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun `horizontal bars are subtracted before scaling`() {
        // A 16:9 image in a taller 800x800 box leaves 175px bars above and below.
        // The image occupies y = 175..625, so the vertical centre of the box is the image centre.
        val (_, yCentre) = mapToRemote(400f, 400f, 800, 800, 1600, 900)
        assertEquals(450, yCentre)

        // The top edge of the drawn image is the top of the remote screen.
        val (_, yTop) = mapToRemote(400f, 175f, 800, 800, 1600, 900)
        assertEquals(0, yTop)
    }

    @Test
    fun `vertical bars are subtracted before scaling`() {
        // A 9:16 portrait remote image in a wide 1000x500 box leaves 359px bars each side.
        val (xCentre, _) = mapToRemote(500f, 250f, 1000, 500, 900, 1600)
        assertEquals(450, xCentre)
    }

    @Test
    fun `a pointer in the letterbox bar clamps instead of going negative`() {
        // y = 10 is inside the top bar of a 16:9 image in an 800x800 box.
        val (_, y) = mapToRemote(400f, 10f, 800, 800, 1600, 900)

        assertEquals(0, y)
    }

    @Test
    fun `a pointer past the far edge clamps to the last pixel`() {
        val (x, y) = mapToRemote(5000f, 5000f, 800, 450, 1600, 900)

        assertEquals(1599, x)
        assertEquals(899, y)
    }

    @Test
    fun `a degenerate container does not divide by zero`() {
        val (x, y) = mapToRemote(10f, 10f, 0, 0, 1600, 900)

        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun `a degenerate remote size does not divide by zero`() {
        val (x, y) = mapToRemote(10f, 10f, 800, 450, 0, 0)

        assertEquals(0, x)
        assertEquals(0, y)
    }
}
