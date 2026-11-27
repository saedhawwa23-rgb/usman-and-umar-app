package com.opendroid.ai.core.termux

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit, bounded local tasks. No AI-generated or free-form shell input is accepted. */
enum class TermuxTask(val label: String, val executable: String, val arguments: Array<String>) {
    CHECK_HOME("Check Termux home", "sh", arrayOf("-lc", "pwd")),
    LIST_FILES("List Termux files", "ls", arrayOf("-la")),
    SYSTEM_INFO("Read Termux system info", "uname", arrayOf("-a")),
    UPDATE_PACKAGES("Refresh package index", "pkg", arrayOf("update", "-y")),
    INSTALL_DEV_TOOLS(
        "Install Git, Python, Node.js and build tools",
        "pkg",
        arrayOf("install", "-y", "git", "python", "nodejs", "clang", "make", "cmake")
    )
}

data class TermuxCommand(val label: String, val executable: String, val arguments: List<String>)

/** Only canonical public GitHub repository URLs are accepted; argv is never shell-interpolated. */
object TermuxGitHubClone {
    private val repoPart = Regex("[A-Za-z0-9_.-]{1,100}")
    private val ownerPart = Regex("[A-Za-z0-9-]{1,39}")

    fun command(url: String): TermuxCommand {
        val uri = java.net.URI(url.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true)) {
            "Use an https://github.com/owner/repository URL."
        }
        require(uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) {
            "GitHub clone links cannot include credentials, custom ports, queries, or fragments."
        }
        val segments = uri.path.trim('/').split('/')
        require(segments.size == 2) { "Enter a repository URL in the form https://github.com/owner/repository." }
        val owner = segments[0]
        val repository = segments[1].removeSuffix(".git")
        require(ownerPart.matches(owner) && repoPart.matches(repository) && repository != "." && repository != "..") {
            "The GitHub owner or repository name is invalid."
        }
        val destination = "${owner}-${repository}"
        return TermuxCommand("Clone $owner/$repository", "git", listOf("clone", "--depth=1", "https://github.com/$owner/$repository.git", destination))
    }
}

data class TermuxResult(
    val executionId: Int,
    val taskLabel: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val error: String? = null,
    val completedAtMs: Long = System.currentTimeMillis()
) {
    val succeeded: Boolean get() = error == null && exitCode == 0
}

@Singleton
class TermuxIntegration @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        private const val RESULT_ACTION = "com.usmanandumar.app.TERMUX_RESULT"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

        // Official Termux RUN_COMMAND/TermuxConstants wire keys.
        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_RESULT_BUNDLE = "result"
        private const val EXTRA_STDOUT = "stdout"
        private const val EXTRA_STDERR = "stderr"
        private const val EXTRA_EXIT_CODE = "exitCode"
        private const val EXTRA_ERROR = "err"
        private const val EXTRA_ERROR_MESSAGE = "errmsg"
        private const val EXTRA_EXECUTION_ID = "execution_id"
        private const val MAX_RESULTS = 30

        private val nextExecutionId = AtomicInteger(SystemClock.elapsedRealtime().toInt().and(Int.MAX_VALUE).coerceAtLeast(1))
    }

    private val pendingTasks = ConcurrentHashMap<Int, TermuxCommand>()
    private val _results = MutableStateFlow<List<TermuxResult>>(emptyList())
    val results: StateFlow<List<TermuxResult>> = _results.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
            val task = pendingTasks.remove(id) ?: return
            val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
            val internalErrorCode = bundle?.getInt(EXTRA_ERROR, Activity.RESULT_OK) ?: Activity.RESULT_CANCELED
            val error = when {
                bundle == null -> "Termux returned no result bundle. Update Termux to version 0.109 or later."
                internalErrorCode != Activity.RESULT_OK -> bundle.getString(EXTRA_ERROR_MESSAGE).orEmpty()
                    .ifBlank { "Termux could not start or finish the command (error $internalErrorCode)." }
                else -> null
            }
            val result = TermuxResult(
                executionId = id,
                taskLabel = task.label,
                stdout = bundle?.getString(EXTRA_STDOUT).orEmpty(),
                stderr = bundle?.getString(EXTRA_STDERR).orEmpty(),
                exitCode = bundle?.takeIf { error == null }?.getInt(EXTRA_EXIT_CODE),
                error = error
            )
            _results.value = (_results.value + result).takeLast(MAX_RESULTS)
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(RESULT_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun hasRunCommandPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        RUN_COMMAND_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Sends one allowlisted executable and argv array to Termux; no shell concatenation is used.
     * User must separately enable Termux's `allow-external-apps` setting and grant RUN_COMMAND.
     */
    fun run(task: TermuxTask): Result<Int> = run(TermuxCommand(task.label, task.executable, task.arguments.toList()))

    /** Runs one explicitly selected bash/python script through Termux RUN_COMMAND. */
    fun runCode(language: String, source: String): Result<Int> = runCatching {
        require(source.isNotBlank()) { "The script is empty." }
        require(source.length <= 64_000) { "Scripts are limited to 64,000 characters." }
        val executable = when (language.lowercase()) {
            "bash", "sh" -> "bash"
            "python", "py" -> "python"
            else -> error("Only bash/sh and python/py code blocks can be executed.")
        }
        val label = "Execute $executable task script"
        val args = if (executable == "bash") listOf("-lc", source) else listOf("-c", source)
        run(TermuxCommand(label, executable, args)).getOrThrow()
    }

    fun cloneGitHubRepository(url: String): Result<Int> = runCatching { TermuxGitHubClone.command(url) }
        .fold(onSuccess = ::run, onFailure = { Result.failure(it) })

    private fun termuxDataDir(): File = File(context.packageManager.getApplicationInfo(TERMUX_PACKAGE, 0).dataDir)

    private fun run(task: TermuxCommand): Result<Int> = runCatching {
        check(isInstalled()) { "Termux is not installed." }
        check(hasRunCommandPermission()) {
            "Grant ‘Run commands in Termux environment’ to USMAN AND UMAR in Android app permissions."
        }

        val executionId = nextExecutionId.getAndUpdate { if (it >= Int.MAX_VALUE - 1) 1 else it + 1 }
        val callback = Intent(RESULT_ACTION).setPackage(context.packageName)
            .putExtra(EXTRA_EXECUTION_ID, executionId)
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, executionId, callback, flags)
        val termuxDir = termuxDataDir()
        val executable = File(termuxDir, "files/usr/bin/${task.executable}").absolutePath
        val workdir = File(termuxDir, "files/home").absolutePath
        val command = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_PATH, executable)
            putExtra(EXTRA_ARGUMENTS, task.arguments.toTypedArray())
            putExtra(EXTRA_WORKDIR, workdir)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            putExtra("com.termux.RUN_COMMAND_LABEL", task.label)
            putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "Started explicitly by the user from USMAN AND UMAR.")
        }
        pendingTasks[executionId] = task
        try {
            context.startService(command)
        } catch (failure: Throwable) {
            pendingTasks.remove(executionId)
            pendingIntent.cancel()
            throw failure
        }
        executionId
    }

    fun close() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
