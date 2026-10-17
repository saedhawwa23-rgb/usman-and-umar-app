package com.opendroid.ai.data.models

import kotlinx.serialization.Serializable

/** A provider discovered at runtime; it is not part of the built-in provider catalog. */
@Serializable
data class DynamicAIProvider(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val model: String,
    val description: String = "",
    val sourceUrl: String = ""
)

@Serializable
data class GitHubProject(
    val id: Long,
    val fullName: String,
    val description: String = "",
    val htmlUrl: String,
    val cloneUrl: String = "",
    val language: String? = null,
    val stars: Int = 0,
    val topics: List<String> = emptyList(),
    val defaultBranch: String = "",
    val forks: Int = 0,
    val openIssues: Int = 0,
    val updatedAt: String = "",
    val readme: String = "",
    val rootFiles: List<String> = emptyList()
)

@Serializable
data class DiscoveryConfig(
    val dynamicAIProviders: List<DynamicAIProvider> = emptyList(),
    val supportedGitHubProjects: List<GitHubProject> = emptyList()
)
