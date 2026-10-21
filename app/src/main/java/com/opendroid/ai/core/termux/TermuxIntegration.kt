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
    ),
    OPENMANUS_PREFLIGHT(
        "Check OpenManus prerequisites",
        "python",
        arrayOf(
            "-c",
            """
            import pathlib, sys
            root = pathlib.Path.home() / "FoundationAgents-OpenManus"
            checks = {
                "Python 3.12+": sys.version_info >= (3, 12),
                "OpenManus checkout": root.is_dir(),
                "requirements.txt": (root / "requirements.txt").is_file(),
                "isolated .venv interpreter": (root / ".venv" / "bin" / "python").is_file(),
            }
            print("Python: " + sys.version.split()[0])
            for label, ok in checks.items():
                print(f"{label}: {'present' if ok else 'missing'}")
            sys.exit(0 if all(checks.values()) else 2)
            """.trimIndent()
        )
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

data class TermuxActiveJob(val executionId: Int, val taskLabel: String)

internal object TermuxProcessControl {
    fun interruptCommand(executionId: Int): TermuxCommand {
        require(executionId > 0) { "Invalid Termux job ID." }
        val script = "pid_file=\"\$HOME/.usman-umar-job-$executionId.pid\"; " +
            "if [ ! -r \"\$pid_file\" ]; then echo 'Termux process is already finished.'; exit 1; fi; " +
            "record=\$(cat \"\$pid_file\"); pid=\${record%%:*}; started=\${record#*:}; " +
            "case \"\$pid:\$started\" in *[!0-9:]*) echo 'Invalid process ID file.'; exit 2;; esac; " +
            "current=\$(sed 's/.*) //' \"/proc/\$pid/stat\" 2>/dev/null | awk '{print \$20}'); " +
            "if [ -z \"\$current\" ] || [ \"\$current\" != \"\$started\" ]; then echo 'Termux process is already finished.'; rm -f \"\$pid_file\"; exit 1; fi; " +
            "kill -INT \"\$pid\" && rm -f \"\$pid_file\""
        return TermuxCommand("Send SIGINT to Termux job $executionId", "sh", listOf("-c", script))
    }
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
        private const val PID_FILE_PREFIX = ".usman-umar-job-"

        private val nextExecutionId = AtomicInteger(SystemClock.elapsedRealtime().toInt().and(Int.MAX_VALUE).coerceAtLeast(1))
    }

    private val pendingTasks = ConcurrentHashMap<Int, TermuxCommand>()
    private val _results = MutableStateFlow<List<TermuxResult>>(emptyList())
    val results: StateFlow<List<TermuxResult>> = _results.asStateFlow()
    private val _activeJobs = MutableStateFlow<List<TermuxActiveJob>>(emptyList())
    val activeJobs: StateFlow<List<TermuxActiveJob>> = _activeJobs.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
            val task = pendingTasks.remove(id) ?: return
            _activeJobs.value = _activeJobs.value.filterNot { it.executionId == id }
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
     * Runs a fixed Termux sh wrapper which records process identity, then execs the
     * allowlisted executable with arguments passed as argv, not shell source.
     * Termux must separately enable `allow-external-apps` and grant RUN_COMMAND permission.
     */
    fun run(task: TermuxTask): Result<Int> = run(TermuxCommand(task.label, task.executable, task.arguments.toList()))

    /** Copies the reviewed APK asset into Termux home and runs that fixed script. */
    fun installAgentRuntimes(): Result<Int> = runCatching {
        val script = context.assets.open("termux/setup.sh").bufferedReader().use { it.readText() }
        require(script.isNotBlank() && script.length <= 100_000) { "Bundled Termux setup script is missing or unexpectedly large." }
        val encoded = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val fixedInstall = "mkdir -p \"\$HOME/.local/bin\"; printf '%s' '$encoded' | base64 -d > \"\$HOME/.local/bin/usman-umar-setup.sh\" && chmod 700 \"\$HOME/.local/bin/usman-umar-setup.sh\" && exec bash \"\$HOME/.local/bin/usman-umar-setup.sh\""
        TermuxCommand("Install OpenManus and OpenSwarm dependencies", "bash", listOf("-lc", fixedInstall))
    }.fold(onSuccess = ::run, onFailure = { Result.failure(it) })

    fun cloneGitHubRepository(url: String): Result<Int> = runCatching { TermuxGitHubClone.command(url) }
        .fold(onSuccess = ::run, onFailure = { Result.failure(it) })

    /** Sends SIGINT to the process associated with an active Termux command. */
    fun abort(executionId: Int): Result<Int> {
        if (!pendingTasks.containsKey(executionId)) return Result.failure(IllegalArgumentException("That Termux job is no longer running."))
        return run(TermuxProcessControl.interruptCommand(executionId))
    }

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
        val shellExecutable = File(termuxDir, "files/usr/bin/sh").absolutePath
        val workdir = File(termuxDir, "files/home").absolutePath
        val command = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_PATH, shellExecutable)
            val pidFile = "\$HOME/$PID_FILE_PREFIX$executionId.pid"
            val pidWrapper = "started=\$(sed 's/.*) //' \"/proc/\$\$/stat\" | awk '{print \$20}'); printf '%s:%s\\n' \"\$\$\" \"\$started\" > \"$pidFile\"; exec \"\$@\""
            putExtra(EXTRA_ARGUMENTS, (listOf("-c", pidWrapper, "uua-termux", executable) + task.arguments).toTypedArray())
            putExtra(EXTRA_WORKDIR, workdir)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            putExtra("com.termux.RUN_COMMAND_LABEL", task.label)
            putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "Started explicitly by the user from USMAN AND UMAR.")
        }
        pendingTasks[executionId] = task
        try {
            context.startService(command)
            _activeJobs.value = _activeJobs.value + TermuxActiveJob(executionId, task.label)
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
