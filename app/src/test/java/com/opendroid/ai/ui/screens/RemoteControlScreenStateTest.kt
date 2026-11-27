package com.opendroid.ai.ui.screens

import com.opendroid.ai.ui.viewmodel.RemoteControlViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlScreenStateTest {
    @Test
    fun `desktop view is hidden before a connection`() {
        assertFalse(shouldRenderRemoteDesktop(RemoteControlViewModel.UiState()))
    }

    @Test
    fun `desktop view is exposed immediately after connection before first frame`() {
        assertTrue(shouldRenderRemoteDesktop(RemoteControlViewModel.UiState(desktopConnected = true)))
    }

    @Test
    fun `desktop view remains exposed while streaming`() {
        assertTrue(shouldRenderRemoteDesktop(RemoteControlViewModel.UiState(desktopStreaming = true)))
    }
}
