package com.opendroid.ai.core.browser

import com.opendroid.ai.data.db.MigrationTestApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class CloudServerClientTest {
    @Test
    fun `health ranking puts fastest HTTP 200 server first and offline servers last`() {
        val ranked = listOf(
            CloudServerHealth("slow", "Server 1", online = true, latencyMs = 240, httpCode = 200),
            CloudServerHealth("offline", "Server 2", online = false, latencyMs = 15, httpCode = 503),
            CloudServerHealth("fast", "Server 3", online = true, latencyMs = 32, httpCode = 200),
        ).rankCloudServerHealth()

        assertEquals(listOf("fast", "slow", "offline"), ranked.map { it.serverId })
    }

    @Test
    fun `OpenSwarm payload includes shared token and stable task fields`() {
        val body = buildOpenSwarmPayload(
            taskId = "cluster-task-7",
            prompt = "Summarize status",
            pluginId = "openswarm_agent",
            pluginIds = listOf("openswarm_agent", "openswarm_agent"),
            thinkHarder = true,
            imageBase64 = null,
            appClientToken = "same-cluster-token",
        )
        val json = JSONObject(body)

        assertEquals("cluster-task-7", json.getString("task_id"))
        assertEquals("Summarize status", json.getString("prompt"))
        assertEquals("openswarm_agent", json.getString("plugin_id"))
        assertTrue(json.getBoolean("think_harder"))
        assertEquals("same-cluster-token", json.getString("APP_CLIENT_TOKEN"))
        assertEquals(30, json.getInt("browser_max_steps"))
        assertEquals(1, json.getJSONArray("plugins").length())
        assertFalse(json.has("image_base64"))
    }
}
