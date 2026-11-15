package com.opendroid.ai.di

import android.content.Context
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
import com.opendroid.ai.core.remote.DeviceKeyStore
import com.opendroid.ai.core.remote.PersistentDeviceKeyStore
import com.opendroid.ai.core.remote.RemoteControlCoordinator
import com.opendroid.ai.core.remote.RemoteControlService
import com.opendroid.ai.core.security.KeystoreSecretBytesStore
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SharedPreferencesSecretRecordStorage
import com.opendroid.ai.core.security.AndroidKeyStoreAeadCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
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

    @Provides
    @Singleton
    fun provideRemoteControlCoordinator(service: RemoteControlService): RemoteControlCoordinator =
        RemoteControlCoordinator(service = service)

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
}
