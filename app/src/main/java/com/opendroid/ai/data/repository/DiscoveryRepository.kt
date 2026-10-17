package com.opendroid.ai.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.opendroid.ai.data.models.GitHubProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

class DiscoveryException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class GitHubRepositoryAddress(val owner: String, val repository: String) {
    companion object {
        private val ownerPattern = Regex("[A-Za-z0-9-]{1,39}")
        private val repositoryPattern = Regex("[A-Za-z0-9_.-]{1,100}")

        fun parse(input: String): GitHubRepositoryAddress {
            val value = input.trim()
            val owner: String
            val repository: String
            if (value.contains("://")) {
                val uri = runCatching { URI(value) }.getOrElse {
                    throw IllegalArgumentException("Enter a valid GitHub repository URL or owner/name.")
                }
                require(uri.scheme.equals("https", true) &&
                    (uri.host.equals("github.com", true) || uri.host.equals("www.github.com", true))) {
                    "Only public https://github.com/owner/repository links are supported."
                }
                require(uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) {
                    "GitHub repository links cannot include credentials, ports, queries, or fragments."
                }
                val parts = uri.path.trim('/').split('/')
                require(parts.size == 2) { "Enter a repository URL in the form https://github.com/owner/repository." }
                owner = parts[0]
                repository = parts[1].removeSuffix(".git")
            } else {
                val parts = value.trim('/').split('/')
                require(parts.size == 2) { "Enter a GitHub repository URL or owner/name." }
                owner = parts[0]
                repository = parts[1].removeSuffix(".git")
            }
            require(ownerPattern.matches(owner) && repositoryPattern.matches(repository) && repository !in setOf(".", "..")) {
                "The GitHub owner or repository name is invalid."
            }
            return GitHubRepositoryAddress(owner, repository)
        }
    }
}

@Singleton
class DiscoveryRepository @Inject constructor(
    private val client: OkHttpClient
) {
    private val gson = Gson()

    suspend fun searchGitHub(query: String): List<GitHubProject> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        val url = "https://api.github.com/search/repositories".toHttpUrl().newBuilder()
            .addQueryParameter("q", trimmed)
            .addQueryParameter("sort", "stars")
            .addQueryParameter("order", "desc")
            .addQueryParameter("per_page", "20")
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "USMAN-AND-UMAR-Android-App")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw githubFailure("search", response.code)
            val root = gson.fromJson(response.body.string(), JsonObject::class.java)
            root.getAsJsonArray("items")?.mapNotNull { element ->
                runCatching { parseProject(element.asJsonObject) }.getOrNull()
            }.orEmpty()
        }
    }

    /** Fetches public repository metadata, README and root file names; does not persist the result. */
    suspend fun loadGitHubRepository(urlOrPath: String): GitHubProject = withContext(Dispatchers.IO) {
        val address = GitHubRepositoryAddress.parse(urlOrPath)
        val item = getObject(githubApiUrl("repos", address.owner, address.repository), "repository lookup")
            ?: throw DiscoveryException("GitHub repository lookup returned no repository metadata.")
        parseProject(
            item,
            readme = fetchReadme(address),
            rootFiles = fetchRootFiles(address)
        )
    }

    private fun parseProject(item: JsonObject, readme: String = "", rootFiles: List<String> = emptyList()) = GitHubProject(
        id = item.get("id")?.asLong ?: 0L,
        fullName = item.get("full_name")?.asString.orEmpty(),
        description = item.get("description")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        htmlUrl = item.get("html_url")?.asString.orEmpty(),
        cloneUrl = item.get("clone_url")?.asString.orEmpty(),
        language = item.get("language")?.takeUnless { it.isJsonNull }?.asString,
        stars = item.get("stargazers_count")?.asInt ?: 0,
        topics = item.getAsJsonArray("topics")?.map { it.asString }.orEmpty(),
        defaultBranch = item.get("default_branch")?.asString.orEmpty(),
        forks = item.get("forks_count")?.asInt ?: 0,
        openIssues = item.get("open_issues_count")?.asInt ?: 0,
        updatedAt = item.get("updated_at")?.asString.orEmpty(),
        readme = readme,
        rootFiles = rootFiles
    )

    private fun fetchReadme(address: GitHubRepositoryAddress): String {
        val item = getObject(githubApiUrl("repos", address.owner, address.repository, "readme"), "README lookup", allowNotFound = true)
            ?: return ""
        val encoded = item.get("content")?.asString.orEmpty().filterNot(Char::isWhitespace)
        if (encoded.isBlank()) return ""
        val decoded = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }
            .getOrElse { throw DiscoveryException("GitHub returned README content in an unsupported format.", it) }
        return decoded.take(MAX_README_CHARS)
    }

    private fun fetchRootFiles(address: GitHubRepositoryAddress): List<String> {
        val url = githubApiUrl("repos", address.owner, address.repository, "contents")
        val request = request(url).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw githubFailure("root-file lookup", response.code)
            val root = gson.fromJson(response.body.string(), JsonArray::class.java)
            return root.take(MAX_ROOT_FILES).mapNotNull { element ->
                runCatching {
                    val obj = element.asJsonObject
                    val name = obj.get("name")?.asString.orEmpty()
                    when {
                        name.isBlank() -> null
                        obj.get("type")?.asString == "dir" -> "$name/"
                        else -> name
                    }
                }.getOrNull()
            }
        }
    }

    private fun getObject(url: okhttp3.HttpUrl, action: String, allowNotFound: Boolean = false): JsonObject? {
        client.newCall(request(url).build()).execute().use { response ->
            if (allowNotFound && response.code == 404) return null
            if (!response.isSuccessful) throw githubFailure(action, response.code)
            return gson.fromJson(response.body.string(), JsonObject::class.java)
        }
    }

    private fun request(url: okhttp3.HttpUrl) = Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "USMAN-AND-UMAR-Android-App")
        .get()

    private fun githubApiUrl(vararg path: String) =
        "https://api.github.com".toHttpUrl().newBuilder().apply { path.forEach(::addPathSegment) }.build()

    private fun githubFailure(action: String, code: Int): DiscoveryException = when (code) {
        403, 429 -> DiscoveryException("GitHub rate limit reached during $action. Wait briefly and try again.")
        404 -> DiscoveryException("GitHub repository was not found or is private. This lookup supports public repositories only.")
        else -> DiscoveryException("GitHub $action failed (HTTP $code).")
    }

    private companion object {
        const val MAX_README_CHARS = 20_000
        const val MAX_ROOT_FILES = 100
    }
}
