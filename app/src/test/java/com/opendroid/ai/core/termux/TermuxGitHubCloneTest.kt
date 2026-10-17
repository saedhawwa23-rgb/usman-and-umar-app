package com.opendroid.ai.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
