package com.opendroid.ai.core.git

import java.io.File
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalGitWorkspaceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `repository exposes branches full history status and working diffs`() {
        val root = temporaryFolder.newFolder("workspace")
        val repositoryDirectory = File(root, "example-repo").apply { mkdirs() }
        val git = Git.init().setDirectory(repositoryDirectory).call()
        try {
            val readme = File(repositoryDirectory, "README.md")
            readme.writeText("initial\n")
            git.add().addFilepattern(".").call()
            val first = git.commit().setMessage("initial commit").setAuthor("Test User", "test@example.invalid").call()
            readme.writeText("initial\nsecond line\n")
            git.add().addFilepattern(".").call()
            val second = git.commit().setMessage("add second line").setAuthor("Test User", "test@example.invalid").call()
            git.branchCreate().setName("feature/catalog").call()

            val workspace = LocalGitWorkspace(root)
            val info = workspace.listRepositories().single()
            assertEquals("example-repo", info.id)
            assertEquals(git.repository.branch, info.currentBranch)
            assertEquals(second.name, info.headCommit)
            assertEquals(2, workspace.history(info.id).size)
            assertTrue(workspace.branches(info.id).any { it.name == "feature/catalog" && !it.isRemote })
            assertTrue(workspace.commitDiff(info.id, second.name).contains("second line"))

            val untracked = File(repositoryDirectory, "new.txt")
            untracked.writeText("untracked content\n")
            val dirty = workspace.status(info.id)
            assertTrue(dirty.changedPaths.contains("new.txt"))
            assertTrue(dirty.workingPatch.contains("untracked content"))
            assertFalse(dirty.workingPatch.contains("second line"))

            val updated = workspace.createBranch(info.id, "feature/workspace")
            assertEquals("feature/workspace", updated.currentBranch)
            val switched = workspace.checkout(info.id, "feature/catalog")
            assertEquals("feature/catalog", switched.currentBranch)
            assertFalse(workspace.history(info.id).isEmpty())
            assertTrue(first.name.isNotBlank())
        } finally {
            git.close()
        }
    }

    @Test
    fun `commit stages current changes and appears in complete local history`() {
        val root = temporaryFolder.newFolder("workspace-commit")
        val repositoryDirectory = File(root, "sample-repo").apply { mkdirs() }
        val git = Git.init().setDirectory(repositoryDirectory).call()
        try {
            File(repositoryDirectory, "tracked.txt").writeText("one\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("first").setAuthor("Test User", "test@example.invalid").call()
            File(repositoryDirectory, "tracked.txt").appendText("two\n")
            val workspace = LocalGitWorkspace(root)
            val info = workspace.listRepositories().single()
            assertTrue(workspace.status(info.id).workingPatch.contains("two"))
            workspace.commit(info.id, "update tracked file")
            assertEquals(2, workspace.status(info.id).commitCount)
            assertEquals("update tracked file", workspace.history(info.id).first().shortMessage)
            assertTrue(workspace.status(info.id).changedPaths.isEmpty())
        } finally {
            git.close()
        }
    }

    @Test
    fun `clone accepts only public HTTPS GitHub repository URLs`() {
        val workspace = LocalGitWorkspace(temporaryFolder.newFolder("workspace-url"))
        assertThrows(IllegalArgumentException::class.java) { workspace.clone("https://evil.example/owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { workspace.clone("https://user@github.com/owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { workspace.clone("https://github.com/owner/repo?x=1") }
        assertThrows(IllegalArgumentException::class.java) { workspace.clone("https://github.com/owner/repo/extra") }
    }
}
