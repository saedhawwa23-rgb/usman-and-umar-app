package com.usmanandumar.workspace.core.task

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TaskQuickAddParserTest {
    private val reference = ZonedDateTime.of(2026, 10, 26, 10, 30, 0, 0, ZoneId.of("Europe/Istanbul"))

    @Test
    fun parsesTomorrowAtFivePmLocally() {
        val parsed = TaskQuickAddParser.parse("Fix bug tomorrow at 5pm", reference)
        assertEquals("Fix bug", parsed.title)
        assertNotNull(parsed.deadlineAt)
        assertEquals(17, java.time.Instant.ofEpochMilli(parsed.deadlineAt!!).atZone(reference.zone).hour)
        assertEquals(27, java.time.Instant.ofEpochMilli(parsed.deadlineAt).atZone(reference.zone).dayOfMonth)
    }

    @Test
    fun parsesTodayAtTimeWithoutCallingAnExternalParser() {
        val parsed = TaskQuickAddParser.parse("Review PR today at 9:15 am", reference)
        assertEquals("Review PR", parsed.title)
        val deadline = java.time.Instant.ofEpochMilli(parsed.deadlineAt!!).atZone(reference.zone)
        assertEquals(9, deadline.hour)
        assertEquals(15, deadline.minute)
        assertEquals(26, deadline.dayOfMonth)
    }

    @Test
    fun preservesPlainTaskTextWithoutDeadline() {
        val parsed = TaskQuickAddParser.parse("Write the release notes", reference)
        assertEquals("Write the release notes", parsed.title)
        assertEquals(null, parsed.deadlineAt)
    }
}
