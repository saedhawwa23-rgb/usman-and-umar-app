package com.opendroid.ai.core.connector

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opendroid.ai.core.browser.CloudServerClient
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.repository.SettingsRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.opendroid.ai.data.db.MigrationTestApplication

/**
 * Area 4: a discovered FMHY host must not become callable until the owner
 * approves that exact host. Discovery only suggests; approval grants.
 *
 * These are real end-to-end paths: the production [OpenSwarmConnector] talks to a
 * MockWebServer through the real [CloudServerClient] and reads approvals from a
 * real [SettingsRepository] backed by a temp DataStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class OpenSwarmConnectorFmhyApprovalTest {

    private val pageText = "Try https://good.example/page and https://other.example/page"

    private fun extractedBody() =
        """{"status":"executed","capability_id":"browser.extract","result":{"text":${JSONObject.quote(pageText)}}}"""

    private fun extractedResultBody() = """{"status":"executed","result":"${pageText.replace("\"", "\\\"")}"}"""

    @Test
    fun `unapproved discovered hosts are not callable`() = runBlocking {
        withConnector { connector, _ ->
            val discovery = connector.execute(discoveryInvocation(), NoopCredentialResolver)
            assertTrue(discovery.success)

            val body = JSONObject(discovery.output)
            assertEquals(emptyList<String>(), body.getJSONArray("callable_hosts").strings())
            assertEquals(2, body.getJSONArray("resources").length())
            assertFalse(connector.tools().any { it.host != null })
        }
    }

    @Test
    fun `approving one host makes only that host callable and revoking removes it`() = runBlocking {
        withConnector { connector, repository ->
            connector.execute(discoveryInvocation(), NoopCredentialResolver)
            assertFalse(connector.tools().any { it.host == "good.example" })

            connector.setFmhyHostApproved("good.example", true)
            assertEquals(
                listOf("good.example"),
                connector.tools().filter { it.host != null }.map { it.host },
            )

            connector.setFmhyHostApproved("good.example", false)
            assertFalse(connector.tools().any { it.host == "good.example" })
            assertTrue(repository.approvedFmhyHosts.first().isEmpty())
        }
    }

    @Test
    fun `discovery after approval reports only the approved host as callable`() = runBlocking {
        withConnector { connector, _ ->
            connector.setFmhyHostApproved("other.example", true)
            val discovery = connector.execute(discoveryInvocation(), NoopCredentialResolver)

            val body = JSONObject(discovery.output)
            assertEquals(listOf("other.example"), body.getJSONArray("callable_hosts").strings())
            assertEquals(
                listOf("site.other.example"),
                body.getJSONArray("site_tools").strings(),
            )
        }
    }

    @Test
    fun `a revoked host cannot be reached through a stale site tool`() = runBlocking {
        withConnector { connector, _ ->
            connector.execute(discoveryInvocation(), NoopCredentialResolver)
            connector.setFmhyHostApproved("good.example", true)
            val siteTool = connector.tools().first { it.host == "good.example" }
            connector.setFmhyHostApproved("good.example", false)

            val result = connector.execute(
                ConnectorInvocation(tool = siteTool, argumentsJson = """{"path":"/page"}"""),
                NoopCredentialResolver,
            )
            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("approved"))
        }
    }

    private suspend fun withConnector(block: suspend (OpenSwarmConnector, SettingsRepository) -> Unit) {
        val server = MockWebServer()
        // Every test needs one browser.extract response per discovery call.
        repeat(3) {
            server.enqueue(MockResponse.Builder().code(200).body(extractedBody()).build())
        }
        server.start()
        try {
            val repository = newSettingsRepository("https://mock-node.loca.lt", "fmhy-token")
            val client = CloudServerClient(repository).apply {
                endpointBaseOverrideForTests = server.url("/").toString().trimEnd('/')
            }
            block(OpenSwarmConnector(client, repository), repository)
        } finally {
            server.close()
        }
    }

    private fun org.json.JSONArray.strings(): List<String> =
        (0 until length()).map { getString(it) }

    private fun discoveryInvocation() = ConnectorInvocation(
        tool = ConnectorTool(
            connectorId = "colab",
            name = "discover_fmhy_resources",
            description = "discover",
            parametersJsonSchema = "{\"type\":\"object\"}",
        ),
        argumentsJson = "{}",
    )

    private fun newSettingsRepository(url: String, token: String): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("fmhy-approval-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            },
        )
        runBlocking {
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("openswarm_server_1_url")] = url
                preferences[booleanPreferencesKey("openswarm_server_1_enabled")] = true
            }
        }
        return SettingsRepository(dataStore, TestCredentialStore(token), runStartupMigration = false)
    }

    private object NoopCredentialResolver : ConnectorCredentialResolver {
        override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T =
            block("secret")
    }

    private class TestCredentialStore(private val serverToken: String) : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(
                if (credential == ProviderCredentialId.OpenSwarmServer1Token) serverToken else null,
            )

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())

        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }
}
