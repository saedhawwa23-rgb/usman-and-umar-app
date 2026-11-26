package com.opendroid.ai.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.db.entities.NotebookEntity
import com.opendroid.ai.data.repository.NotebookImportResult
import com.opendroid.ai.data.repository.NotebookLibraryStore
import com.opendroid.ai.data.repository.NotebookPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotebookViewModelTest {
    private lateinit var repository: FakeNotebookStore

    @Before
    fun setUp() {
        repository = FakeNotebookStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun overlappingImportsAreSerializedAndBusyRemainsTrueUntilAllFinish() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        repository.importGates["content://notebooks/one"] = CompletableDeferred()
        repository.importGates["content://notebooks/two"] = CompletableDeferred()
        val viewModel = NotebookViewModel(repository)
        runCurrent() // Drain the initial list and built-in initialization collectors.

        viewModel.import(Uri.parse("content://notebooks/one"))
        viewModel.import(Uri.parse("content://notebooks/two"))
        runCurrent()

        assertTrue(viewModel.state.value.busy)
        assertEquals(listOf("content://notebooks/one"), repository.startedImports.toList())

        repository.importGates.getValue("content://notebooks/one").complete(
            Result.success(NotebookImportResult(notebook("one"), duplicate = false)),
        )
        runCurrent()

        assertTrue("busy must remain true while the queued import runs", viewModel.state.value.busy)
        assertEquals(
            listOf("content://notebooks/one", "content://notebooks/two"),
            repository.startedImports.toList(),
        )

        repository.importGates.getValue("content://notebooks/two").complete(
            Result.success(NotebookImportResult(notebook("two"), duplicate = false)),
        )
        advanceUntilIdle()

        assertFalse(viewModel.state.value.busy)
        assertEquals("Notebook imported and saved privately on this device.", viewModel.state.value.message)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun cancellingAnImportReleasesBusyStateWithoutReportingFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        repository.importGates["content://notebooks/pending"] = CompletableDeferred()
        val viewModel = NotebookViewModel(repository)
        runCurrent()

        viewModel.import(Uri.parse("content://notebooks/pending"))
        runCurrent()
        assertTrue(viewModel.state.value.busy)
        assertEquals(listOf("content://notebooks/pending"), repository.startedImports.toList())

        viewModel.viewModelScope.cancel()
        runCurrent()

        assertFalse("finally must clear busy state after cancellation", viewModel.state.value.busy)
        assertNull("cancellation must not become a user-visible import error", viewModel.state.value.error)
    }

    private class FakeNotebookStore : NotebookLibraryStore {
        override val notebooks: Flow<List<NotebookEntity>> = MutableStateFlow(emptyList())
        val importGates = mutableMapOf<String, CompletableDeferred<Result<NotebookImportResult>>>()
        val startedImports = CopyOnWriteArrayList<String>()

        override suspend fun ensureBuiltInNotebook() = Unit

        override suspend fun import(uri: Uri): Result<NotebookImportResult> {
            val key = uri.toString()
            startedImports += key
            return importGates.getValue(key).await()
        }

        override suspend fun getPreview(entity: NotebookEntity): Result<NotebookPreview> =
            Result.success(NotebookPreview(0, 0, 0, ""))

        override suspend fun rename(id: String, name: String, description: String) = Unit
        override suspend fun delete(entity: NotebookEntity) = Unit
        override suspend fun markOpened(id: String) = Unit
        override suspend fun export(entity: NotebookEntity, destination: Uri): Result<Unit> = Result.success(Unit)
    }

    private companion object {
        fun notebook(id: String) = NotebookEntity(
            id = id,
            name = "$id.ipynb",
            description = "",
            sourceUri = null,
            localPath = "/data/$id.ipynb",
            mimeType = "application/x-ipynb+json",
            sizeBytes = 100,
            contentSha256 = id,
            builtIn = false,
            createdAtMillis = 1,
            updatedAtMillis = 1,
        )
    }
}
