package com.opendroid.ai.core.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** Summary of an app-owned local repository. The repository path is never exposed to UI. */
data class GitWorkspaceRepositoryInfo(
    val id: String,
    val name: String,
    val remoteUrl: String,
    val currentBranch: String,
    val headCommit: String?
)

data class GitWorkspaceBranch(val name: String, val isCurrent: Boolean, val isRemote: Boolean)
data class GitWorkspaceCommit(
    val id: String,
    val shortMessage: String,
    val author: String,
    val timestampMillis: Long
)
data class GitWorkspaceStatus(
    val stagedPatch: String,
    val workingPatch: String,
    val changedPaths: List<String>,
    val commitCount: Int
)

/** Pure JGit implementation; directory is private app storage, never Termux storage. */
class LocalGitWorkspace(private val root: File) {
    init { root.mkdirs() }

    fun listRepositories(): List<GitWorkspaceRepositoryInfo> = root.listFiles()
        .orEmpty()
        .filter { it.isDirectory && File(it, ".git").isDirectory }
        .mapNotNull { directory -> runCatching { open(directory.name) { summary(it) } }.getOrNull() }
        .sortedBy { it.name.lowercase() }

    /** Clones all refs and complete history; no shallow/depth option is configured. */
    fun clone(url: String, onProgress: (String) -> Unit = {}): GitWorkspaceRepositoryInfo {
        val canonical = canonicalGitHubUrl(url)
        val parsed = URI(canonical)
        val owner = parsed.path.trim('/').substringBefore('/')
        val repository = parsed.path.trim('/').substringAfter('/').removeSuffix(".git")
        val id = "${owner}-${repository}".lowercase()
        val destination = child(id)
        if (destination.exists()) {
            if (!File(destination, ".git").isDirectory) {
                error("A non-repository folder already uses this workspace name.")
            }
            return open(id) { git ->
                val existing = git.repository.config.getString("remote", "origin", "url")
                require(normalizeRemote(existing.orEmpty()) == normalizeRemote(canonical)) {
                    "A different repository already uses this workspace name. Remove or rename it first."
                }
                summary(git)
            }
        }
        onProgress("Cloning full repository history…")
        try {
            val git = Git.cloneRepository()
                .setURI(canonical)
                .setDirectory(destination)
                .setCloneAllBranches(true)
                .setProgressMonitor(object : org.eclipse.jgit.lib.ProgressMonitor {
                    private var title: String = ""
                    override fun start(totalTasks: Int) = Unit
                    override fun beginTask(title: String, totalWork: Int) { this.title = title; onProgress(title) }
                    override fun update(completed: Int) = Unit
                    override fun endTask() = Unit
                    override fun isCancelled(): Boolean = false
                    override fun showDuration(enabled: Boolean) = Unit
                })
                .call()
            return try { summary(git) } finally { git.close() }
        } catch (failure: Throwable) {
            destination.deleteRecursively()
            throw IllegalStateException(failure.message ?: "Git clone failed", failure)
        }
    }

    fun branches(id: String): List<GitWorkspaceBranch> = open(id) { git ->
        val head = git.repository.fullBranch.orEmpty()
        git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()
            .map { ref ->
                val full = ref.name
                val remote = full.startsWith("refs/remotes/")
                val name = when {
                    full.startsWith("refs/heads/") -> full.removePrefix("refs/heads/")
                    full.startsWith("refs/remotes/") -> full.removePrefix("refs/remotes/")
                    else -> full
                }
                GitWorkspaceBranch(name, full == head, remote)
            }
            .sortedWith(compareBy<GitWorkspaceBranch> { it.isRemote }.thenBy { it.name.lowercase() })
    }

    fun checkout(id: String, requested: String): GitWorkspaceRepositoryInfo = open(id) { git ->
        val branch = requested.trim()
        require(branch.isNotBlank() && branch.length <= 200 && !branch.startsWith("-") && !branch.contains("..")) {
            "Branch name is invalid."
        }
        val repository = git.repository
        val localRef = "refs/heads/$branch"
        val remoteRef = "refs/remotes/$branch"
        require(Repository.isValidRefName(localRef) || Repository.isValidRefName(remoteRef)) { "Branch name is invalid." }
        val localExists = repository.findRef(localRef) != null
        val remoteExists = repository.findRef(remoteRef) != null
        when {
            localExists -> git.checkout().setName(branch).call()
            remoteExists -> {
                val localName = branch.substringAfter('/', branch)
                git.checkout().setCreateBranch(true).setName(localName).setStartPoint(remoteRef)
                    .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK).call()
            }
            else -> throw IllegalArgumentException("Branch '$branch' does not exist in this clone.")
        }
        summary(git)
    }

    fun createBranch(id: String, branchName: String): GitWorkspaceRepositoryInfo = open(id) { git ->
        val name = branchName.trim()
        require(name.length <= 100 && Repository.isValidRefName("refs/heads/$name")) { "Enter a valid new branch name." }
        require(git.repository.findRef("refs/heads/$name") == null) { "That branch already exists." }
        git.branchCreate().setName(name).call()
        git.checkout().setName(name).call()
        summary(git)
    }

    fun history(id: String, offset: Int = 0, limit: Int = 100): List<GitWorkspaceCommit> = open(id) { git ->
        require(offset >= 0 && limit in 1..500) { "Invalid commit history page." }
        val result = mutableListOf<GitWorkspaceCommit>()
        val head = git.repository.resolve("HEAD") ?: return@open emptyList()
        val walk = RevWalk(git.repository)
        try {
            walk.markStart(walk.parseCommit(head))
            val iterator = walk.iterator()
            repeat(offset) { if (iterator.hasNext()) iterator.next() }
            repeat(limit) {
                if (iterator.hasNext()) {
                    val commit = iterator.next()
                    result += GitWorkspaceCommit(
                        id = commit.name,
                        shortMessage = commit.shortMessage,
                        author = commit.authorIdent?.name.orEmpty(),
                        timestampMillis = commit.commitTime.toLong() * 1000L
                    )
                }
            }
        } finally { walk.close() }
        result
    }

    fun status(id: String): GitWorkspaceStatus = open(id) { git ->
        val status = git.status().call()
        val paths = (status.added + status.changed + status.removed + status.missing + status.modified + status.untracked + status.conflicting)
            .distinct().sorted()
        val staged = git.diff().setCached(true).call()
        val workingPatch = formatWorkingPatch(git.repository)
        GitWorkspaceStatus(
            stagedPatch = formatPatch(git.repository, staged),
            workingPatch = workingPatch,
            changedPaths = paths,
            commitCount = historyCount(git)
        )
    }

    fun commit(id: String, message: String): GitWorkspaceRepositoryInfo = open(id) { git ->
        val cleanMessage = message.trim()
        require(cleanMessage.isNotBlank() && cleanMessage.length <= 500) { "Commit message must be 1–500 characters." }
        git.add().addFilepattern(".").call()
        val result = git.commit()
            .setMessage(cleanMessage)
            .setAuthor("USMAN AND UMAR", "usmanandumar@local.invalid")
            .setCommitter("USMAN AND UMAR", "usmanandumar@local.invalid")
            .call()
        require(result != null) { "Git could not create a commit." }
        summary(git)
    }

    fun commitDiff(id: String, commitId: String): String = open(id) { git ->
        require(commitId.matches(Regex("[0-9a-fA-F]{7,40}"))) { "Invalid commit ID." }
        val commit = git.repository.resolve(commitId) ?: error("Commit not found.")
        val repo = git.repository
        val output = ByteArrayOutputStream()
        val formatter = DiffFormatter(output)
        val reader = repo.newObjectReader()
        val walk = RevWalk(repo)
        try {
            formatter.setRepository(repo)
            val current = walk.parseCommit(commit)
            val newTree = CanonicalTreeParser().apply { reset(reader, current.tree) }
            val oldTree: AbstractTreeIterator = if (current.parentCount > 0) {
                CanonicalTreeParser().apply { reset(reader, walk.parseCommit(current.getParent(0)).tree) }
            } else EmptyTreeIterator()
            formatter.format(oldTree, newTree)
            output.toString(Charsets.UTF_8.name()).take(100_000)
        } finally {
            formatter.close(); reader.close(); walk.close()
        }
    }

    private fun historyCount(git: Git): Int {
        val head = git.repository.resolve("HEAD") ?: return 0
        val walk = RevWalk(git.repository)
        return try {
            walk.markStart(walk.parseCommit(head))
            val iterator = walk.iterator()
            var count = 0
            while (iterator.hasNext()) { iterator.next(); count++ }
            count
        } finally { walk.close() }
    }

    private fun formatPatch(repository: Repository, entries: List<DiffEntry>): String {
        if (entries.isEmpty()) return ""
        val output = ByteArrayOutputStream()
        val formatter = DiffFormatter(output)
        try {
            formatter.setRepository(repository)
            entries.forEach { formatter.format(it) }
            return output.toString(Charsets.UTF_8.name()).take(100_000)
        } finally { formatter.close() }
    }

    private fun formatWorkingPatch(repository: Repository): String {
        val output = ByteArrayOutputStream()
        val formatter = DiffFormatter(output)
        try {
            formatter.setRepository(repository)
            val entries = formatter.scan(DirCacheIterator(repository.readDirCache()), FileTreeIterator(repository))
            entries.forEach { formatter.format(it) }
            return output.toString(Charsets.UTF_8.name()).take(100_000)
        } finally { formatter.close() }
    }

    private fun summary(git: Git): GitWorkspaceRepositoryInfo {
        val repository = git.repository
        val folder = repository.directory.parentFile ?: error("Repository workspace path is unavailable.")
        val head = repository.resolve("HEAD")?.name
        return GitWorkspaceRepositoryInfo(
            id = folder.name,
            name = folder.name,
            remoteUrl = repository.config.getString("remote", "origin", "url").orEmpty(),
            currentBranch = repository.branch ?: "(detached HEAD)",
            headCommit = head
        )
    }

    private fun <T> open(id: String, block: (Git) -> T): T {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{1,200}"))) { "Invalid repository workspace ID." }
        val directory = child(id)
        require(File(directory, ".git").isDirectory) { "Repository is not present in the app workspace." }
        val git = Git.open(directory)
        return try { block(git) } finally { git.close() }
    }

    private fun child(id: String): File {
        val directory = File(root, id).canonicalFile
        require(directory.parentFile == root.canonicalFile) { "Repository path escapes the app workspace." }
        return directory
    }

    private fun normalizeRemote(value: String): String = value.removeSuffix(".git").trimEnd('/').lowercase()

    private fun canonicalGitHubUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrElse { throw IllegalArgumentException("Enter a valid HTTPS GitHub repository URL.") }
        require(uri.scheme.equals("https", true) && uri.host.equals("github.com", true)) {
            "Only public https://github.com/owner/repository URLs are supported."
        }
        require(uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) {
            "Repository URLs cannot include credentials, ports, queries, or fragments."
        }
        val segments = uri.path.trim('/').split('/')
        require(segments.size == 2) { "Use https://github.com/owner/repository." }
        val owner = segments[0]
        val repository = segments[1].removeSuffix(".git")
        require(owner.matches(Regex("[A-Za-z0-9-]{1,39}")) && repository.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && repository !in setOf(".", "..")) {
            "GitHub owner or repository name is invalid."
        }
        return "https://github.com/$owner/$repository.git"
    }
}

@Singleton
class GitWorkspaceRepository @Inject constructor(@ApplicationContext context: Context) {
    private val workspace = LocalGitWorkspace(File(context.filesDir, "git-workspace"))
    fun listRepositories() = workspace.listRepositories()
    fun clone(url: String, onProgress: (String) -> Unit = {}) = workspace.clone(url, onProgress)
    fun branches(id: String) = workspace.branches(id)
    fun checkout(id: String, branch: String) = workspace.checkout(id, branch)
    fun createBranch(id: String, branch: String) = workspace.createBranch(id, branch)
    fun history(id: String, offset: Int = 0, limit: Int = 100) = workspace.history(id, offset, limit)
    fun status(id: String) = workspace.status(id)
    fun commit(id: String, message: String) = workspace.commit(id, message)
    fun commitDiff(id: String, commitId: String) = workspace.commitDiff(id, commitId)
}
