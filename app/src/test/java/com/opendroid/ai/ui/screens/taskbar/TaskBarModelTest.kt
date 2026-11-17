package com.opendroid.ai.ui.screens.taskbar

import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.usmanandumar.workspace.core.task.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBarModelTest {

    private fun task(
        id: String = "t1",
        title: String = "Example task",
        state: TaskState = TaskState.DRAFT,
        toolId: String? = null,
        notes: String = "",
        deadlineAt: Long? = null,
    ) = WorkspaceTaskEntity(
        id = id,
        title = title,
        description = "",
        projectId = null,
        category = "general",
        tagsJson = "[]",
        priority = 0,
        deadlineAt = deadlineAt,
        toolId = toolId,
        workflowId = null,
        inputJson = "{}",
        expectedOutcome = "",
        notes = notes,
        retryLimit = 0,
        timeoutMs = 0L,
        requiresApproval = false,
        schedule = null,
        state = state.name,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `every persisted state maps to a bucket and none is dropped`() {
        val buckets = TaskState.entries.map { TaskBarModel.bucketOf(it) }
        assertEquals(TaskState.entries.size, buckets.size)
    }

    @Test
    fun `running counts as active and succeeded as done`() {
        assertEquals(TaskBucket.ACTIVE, TaskBarModel.bucketOf(TaskState.RUNNING))
        assertEquals(TaskBucket.DONE, TaskBarModel.bucketOf(TaskState.SUCCEEDED))
        assertEquals(TaskBucket.FAILED, TaskBarModel.bucketOf(TaskState.FAILED))
        assertEquals(TaskBucket.WAITING, TaskBarModel.bucketOf(TaskState.WAITING_APPROVAL))
        assertEquals(TaskBucket.QUEUED, TaskBarModel.bucketOf(TaskState.DRAFT))
    }

    @Test
    fun `unknown state string is treated as a draft rather than crashing`() {
        assertEquals(TaskState.DRAFT, TaskBarModel.parseState("SOMETHING_NEW"))
    }

    @Test
    fun `summary counts each bucket and the total is the task count`() {
        val tasks = listOf(
            task(id = "1", state = TaskState.RUNNING),
            task(id = "2", state = TaskState.QUEUED),
            task(id = "3", state = TaskState.WAITING_APPROVAL),
            task(id = "4", state = TaskState.SUCCEEDED),
            task(id = "5", state = TaskState.FAILED),
            task(id = "6", state = TaskState.DRAFT),
        )
        val summary = TaskBarModel.summary(tasks)
        assertEquals(1, summary.active)
        assertEquals(2, summary.queued)
        assertEquals(1, summary.waiting)
        assertEquals(1, summary.done)
        assertEquals(1, summary.failed)
        assertEquals(tasks.size, summary.total)
    }

    @Test
    fun `progress is only shown where it can be stated truthfully`() {
        assertEquals(TaskProgress.Indeterminate, TaskBarModel.progressOf(TaskState.RUNNING))
        assertEquals(TaskProgress.Complete, TaskBarModel.progressOf(TaskState.SUCCEEDED))
        assertEquals(TaskProgress.None, TaskBarModel.progressOf(TaskState.DRAFT))
        assertEquals(TaskProgress.None, TaskBarModel.progressOf(TaskState.FAILED))
    }

    @Test
    fun `actions match the state machine so no card offers an illegal transition`() {
        assertEquals(listOf(TaskAction.RUN), TaskBarModel.actionsFor(TaskState.DRAFT, null, false))
        assertEquals(
            listOf(TaskAction.PAUSE, TaskAction.CANCEL),
            TaskBarModel.actionsFor(TaskState.RUNNING, "hybrid:CLOUD_OPENSWARM:auto", false),
        )
        assertTrue(TaskBarModel.actionsFor(TaskState.RUNNING, "hybrid:LOCAL_AI_APP:com.x", false).isEmpty())
        assertEquals(
            listOf(TaskAction.APPROVE, TaskAction.CANCEL),
            TaskBarModel.actionsFor(TaskState.WAITING_APPROVAL, null, false),
        )
        assertTrue(TaskBarModel.actionsFor(TaskState.SUCCEEDED, null, false).isEmpty())
    }

    @Test
    fun `save output appears only when a real output exists`() {
        assertFalse(TaskBarModel.actionsFor(TaskState.SUCCEEDED, null, hasOutput = false).contains(TaskAction.SAVE_OUTPUT))
        assertTrue(TaskBarModel.actionsFor(TaskState.SUCCEEDED, null, hasOutput = true).contains(TaskAction.SAVE_OUTPUT))
    }

    @Test
    fun `output is read only from a succeeded task with the execution prefix`() {
        val succeeded = task(state = TaskState.SUCCEEDED, notes = "Execution output:\nhello world")
        assertEquals("hello world", TaskBarModel.outputOf(succeeded))
        val running = task(state = TaskState.RUNNING, notes = "Execution output:\nhello world")
        assertEquals("", TaskBarModel.outputOf(running))
        val plain = task(state = TaskState.SUCCEEDED, notes = "no prefix here")
        assertEquals("", TaskBarModel.outputOf(plain))
    }

    @Test
    fun `mode label reflects the tool id and never invents a mode`() {
        assertEquals("Cloud OpenSwarm", TaskBarModel.modeLabel("hybrid:CLOUD_OPENSWARM:auto"))
        assertEquals("Local AI · com.example.app", TaskBarModel.modeLabel("hybrid:LOCAL_AI_APP:com.example.app"))
        assertEquals("Local Termux · bash", TaskBarModel.modeLabel("hybrid:TERMUX_SCRIPT:bash"))
        assertEquals("No execution mode", TaskBarModel.modeLabel(null))
    }

    @Test
    fun `approval note strips the storage prefix and stays bounded`() {
        val note = TaskBarModel.noteFor(TaskState.WAITING_APPROVAL, "OpenSwarm approval request:\ndelete file")
        assertTrue(note.contains("delete file"))
        assertFalse(note.contains("OpenSwarm approval request:"))
    }

    @Test
    fun `filename is safe for the filesystem`() {
        assertEquals("task-output.txt", TaskBarModel.filenameFor("   "))
        assertTrue(TaskBarModel.filenameFor("a/b\\c:d").none { it == '/' || it == '\\' || it == ':' })
    }

    @Test
    fun `item view model carries a status label for every state`() {
        TaskState.entries.forEach { state ->
            val item = TaskBarModel.itemOf(task(state = state))
            assertTrue("state $state has a blank label", item.statusLabel.isNotBlank())
        }
    }
}
