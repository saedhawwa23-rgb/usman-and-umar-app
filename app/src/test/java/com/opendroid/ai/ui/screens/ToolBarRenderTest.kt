package com.opendroid.ai.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opendroid.ai.ui.screens.toolbar.ToolBarContext
import com.opendroid.ai.ui.theme.UsmanAndUmarTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Tool Bar ([CapabilitiesScreenContent]) on the JVM via Robolectric and asserts
 * what the user actually sees and what each entry navigates to. This is rendering and routing
 * evidence, not a device test: it proves the screen composes, the three sections and their entries
 * appear, the availability pill matches the runtime context, and every action invokes the correct
 * navigation callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolBarRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var searchAi = 0
    private var searchGitHub = 0
    private var termux = 0
    private var universalAgent = 0
    private var pluginPlatform = 0
    private var agentPlatforms = 0

    private fun render(context: ToolBarContext = ToolBarContext()) {
        compose.setContent {
            UsmanAndUmarTheme(isDarkTheme = true) {
                CapabilitiesScreenContent(
                    context = context,
                    onSearchAI = { searchAi++ },
                    onSearchGitHub = { searchGitHub++ },
                    onTermux = { termux++ },
                    onUniversalAgent = { universalAgent++ },
                    onOpenPluginPlatform = { pluginPlatform++ },
                    onOpenAgentPlatforms = { agentPlatforms++ },
                )
            }
        }
        compose.waitForIdle()
    }

    /** Clicks a segmented tab and waits for the recomposition to settle. */
    private fun selectSection(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    @Test
    fun showsTheThreeToolBarSections() {
        render()
        compose.onNodeWithText("Tool Bar").assertIsDisplayed()
        compose.onNodeWithText("AI").assertIsDisplayed()
        compose.onNodeWithText("GitHub").assertIsDisplayed()
        compose.onNodeWithText("Linux").assertIsDisplayed()
    }

    @Test
    fun aiSectionListsProvidersCloudAgentAndIntegrations() {
        render()
        compose.onNodeWithText("AI providers & web agents").assertIsDisplayed()
        compose.onNodeWithText("AI providers & models").assertIsDisplayed()
        compose.onNodeWithText("Cloud AI tool agent").assertIsDisplayed()
        compose.onNodeWithText("Integrations, MCP & OpenAPI").assertIsDisplayed()
    }

    @Test
    fun availabilityPillsAreHonestForTheRuntimeContext() {
        // Nothing configured: the cloud agent must not read "Ready".
        render(ToolBarContext(termuxInstalled = false, cloudServerConfigured = false))
        compose.onAllNodesWithText("Needs setup")[0].assertIsDisplayed()
        compose.onAllNodesWithText("Read-only")[0].assertIsDisplayed()
    }

    @Test
    fun cloudAgentReadsReadyOnlyWhenACloudServerIsConfigured() {
        render(ToolBarContext(cloudServerConfigured = true))
        compose.onAllNodesWithText("Ready")[0].assertIsDisplayed()
    }

    @Test
    fun githubSectionListsSearchInspectCloneAndProjects() {
        render()
        selectSection("GitHub")
        compose.onNodeWithText("Search public repositories").assertIsDisplayed()
        compose.onNodeWithText("Inspect a repository").assertIsDisplayed()
        compose.onNodeWithText("Clone a repository").assertIsDisplayed()
        compose.onNodeWithText("Manage projects").assertIsDisplayed()
        // Clone needs Termux, so without it the pill must say so.
        compose.onAllNodesWithText("Needs Termux")[0].assertIsDisplayed()
    }

    @Test
    fun linuxSectionListsTerminalToolsAndAiAssisted() {
        render()
        selectSection("Linux")
        compose.onNodeWithText("Termux terminal").assertIsDisplayed()
        compose.onNodeWithText("Development tools").assertIsDisplayed()
        compose.onNodeWithText("AI-assisted commands").assertIsDisplayed()
    }

    @Test
    fun providerEntryRoutesToProviderDiscovery() {
        render()
        compose.onNodeWithText("Search providers").performClick()
        compose.waitForIdle()
        assertEquals(1, searchAi)
    }

    @Test
    fun cloudAgentEntryRoutesToUniversalAgent() {
        render()
        compose.onNodeWithText("Open agent").performClick()
        compose.waitForIdle()
        assertEquals(1, universalAgent)
    }

    @Test
    fun githubSearchEntryRoutesToGitHubDiscovery() {
        render()
        selectSection("GitHub")
        compose.onAllNodesWithText("Open GitHub")[0].performClick()
        compose.waitForIdle()
        assertEquals(1, searchGitHub)
    }

    @Test
    fun cloneEntryRoutesToTermuxWhenTermuxIsInstalled() {
        render(ToolBarContext(termuxInstalled = true))
        selectSection("GitHub")
        // Clone is a Termux action, so it opens the terminal, not GitHub.
        compose.onAllNodesWithText("Open terminal")[0].performClick()
        compose.waitForIdle()
        assertEquals(1, termux)
    }

    @Test
    fun integrationPlatformButtonRoutesToPluginPlatform() {
        render()
        compose.onNodeWithText("Open integrations").performClick()
        compose.waitForIdle()
        assertEquals(1, pluginPlatform)
    }

    @Test
    fun agentPlatformCenterRoutesToAgentPlatforms() {
        render()
        compose.onNodeWithText("Open platforms").performClick()
        compose.waitForIdle()
        assertEquals(1, agentPlatforms)
    }
}
