package com.opendroid.ai.di

import android.content.Context
import android.os.Build
import com.opendroid.ai.core.security.AndroidProviderCredentialStore
import com.opendroid.ai.core.security.AndroidSensitiveMemoryStore
import com.opendroid.ai.core.security.AndroidUserProfileStore
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.core.security.SensitiveMemoryStore
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.core.settings.AndroidAppSettingsStore
import com.opendroid.ai.core.settings.AppSettingsStore
import com.opendroid.ai.accessibility.AndroidCallFlowVerifier
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.actions.AndroidMediaPlaybackVerifier
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.MediaPlaybackVerifier
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.accessibility.AndroidAgentToolEnvironment
import com.opendroid.ai.core.agent.AgentToolEnvironment
import com.opendroid.ai.core.remote.DeviceKeyStore
import com.opendroid.ai.core.remote.KeystorePairedDesktopStore
import com.opendroid.ai.core.remote.PairedDesktopStore
import com.opendroid.ai.core.remote.RemoteDesktopClient
import com.opendroid.ai.core.remote.LanRemoteClient
import com.opendroid.ai.core.remote.LanRemoteControlServer
import com.opendroid.ai.core.remote.RemoteApprovalGate
import com.opendroid.ai.core.remote.RemoteTransportController
import com.opendroid.ai.core.remote.PersistentDeviceKeyStore
import com.opendroid.ai.core.remote.RemoteControlCoordinator
import com.opendroid.ai.core.remote.RemoteControlService
import com.opendroid.ai.core.remote.RemoteSessionManager
import com.opendroid.ai.core.remote.EncryptedReconnectStateStore
import com.opendroid.ai.core.remote.ReconnectStateStore
import com.opendroid.ai.core.remote.ReconnectionCoordinator
import com.opendroid.ai.core.remote.RemoteReconnectSupervisor
import com.opendroid.ai.core.plugin.EncryptedPluginGrantStore
import com.opendroid.ai.core.plugin.PersistentAuthorizationStore
import com.opendroid.ai.core.plugin.PluginExecutionGate
import com.opendroid.ai.core.plugin.PluginRegistry
import com.opendroid.ai.core.security.KeystoreSecretBytesStore
import com.opendroid.ai.core.security.SecretBytesStore
import com.usmanandumar.workspace.core.workflow.ExecutionReceiptStore
import com.usmanandumar.workspace.core.workflow.FileExecutionReceiptStore
import com.usmanandumar.workspace.core.workflow.FileWorkflowRunStore
import com.usmanandumar.workspace.core.workflow.WorkflowRunStore
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SharedPreferencesSecretRecordStorage
import com.opendroid.ai.core.security.AndroidKeyStoreAeadCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun provideProviderCredentialStore(@ApplicationContext context: Context): ProviderCredentialStore {
        return AndroidProviderCredentialStore(context)
    }

    @Provides
    @Singleton
    fun provideUserProfileStore(@ApplicationContext context: Context): UserProfileStore {
        return AndroidUserProfileStore(context)
    }

    @Provides
    @Singleton
    fun provideSensitiveMemoryStore(@ApplicationContext context: Context): SensitiveMemoryStore {
        return AndroidSensitiveMemoryStore(context)
    }

    @Provides
    @Singleton
    fun provideAppSettingsStore(@ApplicationContext context: Context): AppSettingsStore {
        return AndroidAppSettingsStore(context)
    }

    @Provides
    @Singleton
    fun provideSocialCredentialStore(@ApplicationContext context: Context): com.opendroid.ai.core.security.SocialCredentialStore {
        return com.opendroid.ai.core.security.AndroidSocialCredentialStore(context)
    }

    @Provides
    @Singleton
    fun provideCallFlowVerifier(): CallFlowVerifier = AndroidCallFlowVerifier()

    @Provides
    @Singleton
    fun provideMediaPlaybackVerifier(): MediaPlaybackVerifier = AndroidMediaPlaybackVerifier()

    /**
     * Encrypted, Keystore-backed storage for paired-device keys, kept in the same SharedPreferences
     * file the other secret stores use. A value that cannot be decrypted is treated as absent, so a
     * lost key fails closed (the device reads as unpaired) instead of falling back to plaintext.
     */
    @Provides
    @Singleton
    fun provideRemoteDeviceKeyStore(@ApplicationContext context: Context): DeviceKeyStore =
        PersistentDeviceKeyStore(
            KeystoreSecretBytesStore(
                KeystoreSecretRecords(
                    storage = SharedPreferencesSecretRecordStorage(
                        context.applicationContext.getSharedPreferences(REMOTE_PREFERENCES, Context.MODE_PRIVATE)
                    ),
                    cipher = AndroidKeyStoreAeadCipher(REMOTE_KEY_ALIAS),
                )
            )
        )

    @Provides
    @Singleton
    fun provideRemoteControlService(keyStore: DeviceKeyStore): RemoteControlService =
        RemoteControlService(keyStore = keyStore)

    /**
     * Pairing secrets for the desktop companion agent, kept in the same Keystore-backed record store
     * as the other remote secrets. Separate alias and preferences file from the LAN control keys, so
     * the two features cannot overwrite each other's records.
     */
    @Provides
    @Singleton
    fun providePairedDesktopStore(@ApplicationContext context: Context): PairedDesktopStore =
        KeystorePairedDesktopStore(
            KeystoreSecretBytesStore(
                KeystoreSecretRecords(
                    storage = SharedPreferencesSecretRecordStorage(
                        context.applicationContext.getSharedPreferences(DESKTOP_PREFERENCES, Context.MODE_PRIVATE)
                    ),
                    cipher = AndroidKeyStoreAeadCipher(DESKTOP_KEY_ALIAS),
                )
            )
        )

    @Provides
    @Singleton
    fun provideRemoteDesktopClient(
        store: PairedDesktopStore,
        @ApplicationContext context: Context,
    ): RemoteDesktopClient = RemoteDesktopClient(
        store = store,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    )

    @Provides
    @Singleton
    fun provideRemoteSessionManager(): RemoteSessionManager = RemoteSessionManager()

    /**
     * Keystore-backed storage for authorization and reconnect records. A separate alias and
     * preferences file from the pairing stores, so the three features cannot overwrite each other's
     * records. No plaintext fallback: a value that cannot be decrypted reads as absent.
     */
    @Provides
    @Singleton
    fun provideAuthorizationSecretStore(@ApplicationContext context: Context): SecretBytesStore =
        KeystoreSecretBytesStore(
            KeystoreSecretRecords(
                storage = SharedPreferencesSecretRecordStorage(
                    context.applicationContext.getSharedPreferences(AUTH_PREFERENCES, Context.MODE_PRIVATE)
                ),
                cipher = AndroidKeyStoreAeadCipher(AUTH_KEY_ALIAS),
            )
        )

    @Provides
    @Singleton
    fun provideReconnectStateStore(secrets: SecretBytesStore): ReconnectStateStore =
        EncryptedReconnectStateStore(secrets)

    @Provides
    @Singleton
    fun provideReconnectionCoordinator(store: ReconnectStateStore): ReconnectionCoordinator =
        ReconnectionCoordinator(store)

    @Provides
    @Singleton
    fun provideRemoteReconnectSupervisor(
        coordinator: ReconnectionCoordinator,
        store: PairedDesktopStore,
        client: RemoteDesktopClient,
    ): RemoteReconnectSupervisor = RemoteReconnectSupervisor(
        coordinator = coordinator,
        paired = store,
        connect = { desktop ->
            client.connect(
                host = desktop.host,
                port = desktop.port,
                pairingCode = null,
                onState = { },
                onScreenFrame = { },
                onMessage = { },
            ).isSuccess
        },
    )

    @Provides
    @Singleton
    fun providePluginRegistry(): PluginRegistry = PluginRegistry()

    @Provides
    @Singleton
    fun providePersistentAuthorizationStore(
        secrets: SecretBytesStore,
        pluginRegistry: PluginRegistry,
    ): PersistentAuthorizationStore = PersistentAuthorizationStore(
        grants = EncryptedPluginGrantStore(secrets),
        pluginRegistry = pluginRegistry,
    )

    @Provides
    @Singleton
    fun providePluginExecutionGate(
        pluginRegistry: PluginRegistry,
        authorizationStore: PersistentAuthorizationStore,
    ): PluginExecutionGate = PluginExecutionGate(pluginRegistry, authorizationStore)

    /**
     * Durable workflow state, so an interrupted multi-plugin workflow resumes after an application
     * or process restart. The receipt store is separate: it records that an external effect has
     * already been requested, so a resume never repeats it.
     */
    @Provides
    @Singleton
    fun provideWorkflowRunStore(@ApplicationContext context: Context): WorkflowRunStore =
        FileWorkflowRunStore(File(context.filesDir, "workflows/runs.json"))

    @Provides
    @Singleton
    fun provideExecutionReceiptStore(@ApplicationContext context: Context): ExecutionReceiptStore =
        FileExecutionReceiptStore(File(context.filesDir, "workflows/receipts.json"))

    @Provides
    @Singleton
    fun provideRemoteControlCoordinator(
        service: RemoteControlService,
        sessions: RemoteSessionManager,
    ): RemoteControlCoordinator = RemoteControlCoordinator(service = service, sessions = sessions)

    @Provides
    @Singleton
    fun provideRemoteApprovalGate(): RemoteApprovalGate = RemoteApprovalGate()

    @Provides
    @Singleton
    fun provideLanRemoteControlServer(
        service: RemoteControlService,
        approvalGate: RemoteApprovalGate,
        environment: AgentToolEnvironment,
    ): LanRemoteControlServer = LanRemoteControlServer(
        service = service,
        approvalGate = approvalGate,
        environment = environment,
    )

    @Provides
    @Singleton
    fun provideRemoteTransportController(server: LanRemoteControlServer): RemoteTransportController =
        RemoteTransportController(server)

    @Provides
    @Singleton
    fun provideLanRemoteClient(): LanRemoteClient = LanRemoteClient()

    @Provides
    @Singleton
    fun provideAgentToolEnvironment(environment: AndroidAgentToolEnvironment): AgentToolEnvironment =
        environment

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideActionSequenceExecutor(
        actionDispatcher: dagger.Lazy<ActionDispatcher>
    ): ActionSequenceExecutor = ActionSequenceExecutor(
        executeAction = { action, params, context ->
            actionDispatcher.get().execute(action, params, context)
        },
        hasAction = { action -> actionDispatcher.get().hasAction(action) }
    )

    private const val REMOTE_PREFERENCES = "remote_control_secure"
    private const val REMOTE_KEY_ALIAS = "opendroid_remote_control_key"
    private const val DESKTOP_PREFERENCES = "remote_desktop_secure"
    private const val DESKTOP_KEY_ALIAS = "opendroid_remote_desktop_key"
    private const val AUTH_PREFERENCES = "authorization_secure"
    private const val AUTH_KEY_ALIAS = "opendroid_authorization_key"
}
