package com.opendroid.ai.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveQueryTest {
    @Test
    fun `search excludes trashed files and searches across accessible Drive`() {
        assertEquals(
            "trashed = false and name contains 'quarterly plan'",
            GoogleDriveQuery.list(" quarterly plan ", GoogleDriveRepository.ROOT_FOLDER_ID)
        )
    }

    @Test
    fun `folder browse query escapes apostrophes and backslashes`() {
        val query = GoogleDriveQuery.list("", "folder'\\id")
        assertTrue(query.startsWith("trashed = false and 'folder\\'\\\\id' in parents"))
        assertEquals(
            "trashed = false and name contains 'O\\'Brien\\\\draft'",
            GoogleDriveQuery.list("O'Brien\\draft", "root")
        )
    }

    @Test
    fun `empty search keeps current-folder filter`() {
        assertEquals(
            "trashed = false and 'folder-123' in parents",
            GoogleDriveQuery.list("  ", "folder-123")
        )
    }
}
