package com.opendroid.ai.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubRepositoryAddressTest {
    @Test
    fun `parses plain owner repository and canonical https URL`() {
        assertEquals(GitHubRepositoryAddress("openai", "whisper"), GitHubRepositoryAddress.parse("openai/whisper"))
        assertEquals(GitHubRepositoryAddress("openai", "whisper"), GitHubRepositoryAddress.parse("https://github.com/openai/whisper.git"))
    }

    @Test
    fun `rejects non-github or insecure URL`() {
        listOf("http://github.com/owner/repo", "https://evil.example/owner/repo", "https://github.com.evil.example/owner/repo").forEach {
            assertThrows(IllegalArgumentException::class.java) { GitHubRepositoryAddress.parse(it) }
        }
    }

    @Test
    fun `rejects credentials, query, fragments, traversal and extra path segments`() {
        listOf(
            "https://token@github.com/owner/repo",
            "https://github.com/owner/repo?x=1",
            "https://github.com/owner/repo#readme",
            "https://github.com/owner/../repo",
            "https://github.com/owner/repo/tree/main"
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { GitHubRepositoryAddress.parse(it) }
        }
    }
}
