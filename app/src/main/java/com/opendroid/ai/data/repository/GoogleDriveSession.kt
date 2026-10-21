package com.opendroid.ai.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-only Drive authorization state shared by screens and local-tool ViewModels.
 * The account and token are published atomically, and the generation invalidates stale async work.
 */
data class GoogleDriveSessionSnapshot(
    val accessToken: String?,
    val accountEmail: String?,
    val generation: Long
) {
    val isAuthorized: Boolean get() = !accessToken.isNullOrBlank() && !accountEmail.isNullOrBlank()
}

@Singleton
class GoogleDriveSession @Inject constructor() {
    private val mutableSnapshot = MutableStateFlow(GoogleDriveSessionSnapshot(null, null, 0L))
    val snapshots: StateFlow<GoogleDriveSessionSnapshot> = mutableSnapshot.asStateFlow()

    @Synchronized
    fun clear(): Long {
        val generation = mutableSnapshot.value.generation + 1
        mutableSnapshot.value = GoogleDriveSessionSnapshot(null, null, generation)
        return generation
    }

    fun currentGeneration(): Long = mutableSnapshot.value.generation

    fun accessToken(): String? = mutableSnapshot.value.accessToken

    fun email(): String? = mutableSnapshot.value.accountEmail

    /** Returns false when account selection/disconnect superseded this authorization request. */
    @Synchronized
    fun connect(accessToken: String, email: String, expectedGeneration: Long): Boolean {
        require(accessToken.isNotBlank() && email.isNotBlank())
        val current = mutableSnapshot.value
        if (current.generation != expectedGeneration) return false
        mutableSnapshot.value = GoogleDriveSessionSnapshot(accessToken, email, current.generation)
        return true
    }
}
