package com.opendroid.ai.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveSessionTest {
    @Test
    fun `connect publishes token and account together for current generation`() {
        val session = GoogleDriveSession()
        val generation = session.currentGeneration()

        assertTrue(session.connect("access-token", "user@example.com", generation))
        val snapshot = session.snapshots.value
        assertTrue(snapshot.isAuthorized)
        assertEquals("access-token", snapshot.accessToken)
        assertEquals("user@example.com", snapshot.accountEmail)
        assertEquals(generation, snapshot.generation)
    }

    @Test
    fun `clear invalidates an outstanding authorization result`() {
        val session = GoogleDriveSession()
        val requestGeneration = session.currentGeneration()
        val nextGeneration = session.clear()

        assertTrue(nextGeneration > requestGeneration)
        assertFalse(session.connect("stale-token", "old@example.com", requestGeneration))
        assertFalse(session.snapshots.value.isAuthorized)
        assertNull(session.snapshots.value.accessToken)
        assertNull(session.snapshots.value.accountEmail)
    }

    @Test
    fun `clear removes token and account as one snapshot`() {
        val session = GoogleDriveSession()
        session.connect("access-token", "user@example.com", session.currentGeneration())

        val newGeneration = session.clear()
        val snapshot = session.snapshots.value
        assertEquals(newGeneration, snapshot.generation)
        assertNull(snapshot.accessToken)
        assertNull(snapshot.accountEmail)
        assertFalse(snapshot.isAuthorized)
    }
}
