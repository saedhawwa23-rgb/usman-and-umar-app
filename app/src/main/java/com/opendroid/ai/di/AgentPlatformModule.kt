package com.opendroid.ai.di

import android.content.Context
import com.opendroid.ai.core.agentplatform.AgentPlatformDatabase
import com.opendroid.ai.core.agentplatform.AgentPlatformDatabaseFactory
import com.opendroid.ai.core.agentplatform.AgentPlatformTaskDao
import com.opendroid.ai.core.agentplatform.AndroidPlatformConnectionStore
import com.opendroid.ai.core.agentplatform.PlatformConnectionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Bindings for the agent-platform feature, kept separate from the app's main database. */
@Module
@InstallIn(SingletonComponent::class)
object AgentPlatformModule {

    @Provides
    @Singleton
    fun providePlatformConnectionStore(
        @ApplicationContext context: Context,
    ): PlatformConnectionStore = AndroidPlatformConnectionStore(context)

    @Provides
    @Singleton
    fun provideAgentPlatformDatabase(
        @ApplicationContext context: Context,
    ): AgentPlatformDatabase = AgentPlatformDatabaseFactory.create(context)

    @Provides
    fun provideAgentPlatformTaskDao(database: AgentPlatformDatabase): AgentPlatformTaskDao =
        database.taskDao()
}
