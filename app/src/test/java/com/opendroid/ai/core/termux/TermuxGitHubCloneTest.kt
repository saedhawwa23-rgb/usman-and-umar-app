package com.opendroid.ai.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxGitHubCloneTest {
    @Test
    fun `canonical github repository becomes safe argv without shell`() {
        val command = TermuxGitHubClone.command("https://github.com/example/mobile-app.git")
        assertEquals("Clone example/mobile-app", command.label)
        assertEquals("git", command.executable)
        assertEquals(
            listOf(
                "clone", "--depth=1", "https://github.com/example/mobile-app.git",
                "example-mobile-app"
            ),
            command.arguments
        )
    }

    @Test
    fun `reject non-github hosts and credentials`() {
        assertThrows(IllegalArgumentException::class.java) {
            TermuxGitHubClone.command("https://evil.example/owner/repo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TermuxGitHubClone.command("https://user@github.com/owner/repo")
        }
    }

    @Test
    fun `reject shell syntax paths and URL suffixes`() {
        listOf(
            "https://github.com/owner/repo;id",
            "https://github.com/owner/repo?command=id",
            "https://github.com/owner/repo/extra",
            "https://github.com/owner/../repo"
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { TermuxGitHubClone.command(url) }
        }
    }

    @Test
    fun `OpenManus preflight uses fixed Python argv and only checks prerequisites`() {
        val task = TermuxTask.OPENMANUS_PREFLIGHT
        assertEquals("sh", task.executable)
        assertEquals("-lc", task.arguments[0])
        val script = task.arguments[1]
        assertTrue(script.contains("uua-proot"))
        assertTrue(script.contains("Python 3.12+"))
        assertTrue(script.contains("/opt/usman-and-umar/agents"))
        assertTrue(script.contains("OpenSwarm source"))
        assertTrue(script.contains("requirements.txt"))
        assertTrue(script.contains(".venv"))
        assertFalse(script.contains("subprocess"))
        assertFalse(script.contains("os.system"))
        assertFalse(script.contains("pip install"))
    }

    @Test
    fun `SIGINT command validates process id and start time before signaling`() {
        val command = TermuxProcessControl.interruptCommand(1234)
        assertEquals("sh", command.executable)
        assertEquals("-c", command.arguments.first())
        val script = command.arguments.last()
        assertTrue(script.contains(".usman-umar-job-1234.pid"))
        assertTrue(script.contains("/proc/\$pid/stat"))
        assertTrue(script.contains("kill -INT \"\$pid\""))
        assertTrue(script.contains("\$current\" != \"\$started"))
        assertThrows(IllegalArgumentException::class.java) {
            TermuxProcessControl.interruptCommand(-1)
        }
    }

    @Test
    fun `ARM32 PRoot installer passes bounded archive as argv not shell source`() {
        val command = TermuxProotRuntime.installCommand("QUJD")
        assertEquals("sh", command.executable)
        assertEquals("-c", command.arguments[0])
        assertEquals("uua-proot-installer", command.arguments[2])
        assertEquals("QUJD", command.arguments[3])
        assertFalse(command.arguments[1].contains("QUJD"))
        assertTrue(command.arguments[1].contains("base64 -d | tar -xz"))
        assertTrue(command.arguments[1].contains("PROOT_LOADER=\"${'$'}tmp/loader\""))
        assertTrue(command.arguments[1].contains("installed_new=1"))
        assertTrue(command.arguments[1].contains("installed_wrapper=1"))
        assertTrue(command.arguments[1].contains("committed=1"))
        assertTrue(command.arguments[1].contains("mv \"${'$'}previous\" \"${'$'}base\""))
        assertThrows(IllegalArgumentException::class.java) {
            TermuxProotRuntime.installCommand("QUJD; id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TermuxProotRuntime.installCommand("A".repeat(400_004))
        }
    }
}
