package com.opendroid.ai.data.repository

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTriageParserTest {
    @Test
    fun parsesStructuredTasksAndLocalDateDeadline() {
        val tasks = TaskTriageParser.parse(
            """```json
                {"tasks":[{"title":"Send report","description":"Prepare a weekly report","expectedOutcome":"PDF ready","category":"work","priority":12,"dueDate":"2026-10-30"}]}
                ```""".trimIndent(),
            ZoneId.of("UTC"),
        )
        assertEquals(1, tasks.size)
        assertEquals("Send report", tasks.single().title)
        assertEquals("Work", tasks.single().category)
        assertEquals(10, tasks.single().priority)
        assertEquals(LocalDate.parse("2026-10-30").atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(), tasks.single().deadlineAt)
        assertTrue(tasks.single().dependsOn.isEmpty())
    }

    @Test
    fun missingDateStaysNullAndUnknownCategoryIsRestricted() {
        val tasks = TaskTriageParser.parse("""{"tasks":[{"title":"Buy groceries","category":"Other","dueDate":null}]}""")
        assertNull(tasks.single().deadlineAt)
        assertEquals("Personal", tasks.single().category)
        assertEquals(0, tasks.single().priority)
    }

    @Test
    fun parsesOrderedPlanPrerequisitesAndRejectsForwardDependencies() {
        val tasks = TaskTriageParser.parse(
            """{"tasks":[{"title":"Get approval","dependsOn":[]},{"title":"Send invoice","dependsOn":[0]}]}""",
        )
        assertEquals(listOf(0), tasks[1].dependsOn)
        assertThrows(IllegalArgumentException::class.java) {
            TaskTriageParser.parse("""{"tasks":[{"title":"First","dependsOn":[1]},{"title":"Second"}]}""")
        }
    }

    @Test
    fun refusesBlankTitlesAndMalformedResponses() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskTriageParser.parse("""{"tasks":[{"title":" "}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskTriageParser.parse("no structured tasks here")
        }
    }

    @Test
    fun rejectsUnboundedTaskLists() {
        val task = """{"title":"Task"}"""
        val response = """{"tasks":[${List(31) { task }.joinToString(",")}]}"""
        val failure = assertThrows(IllegalArgumentException::class.java) { TaskTriageParser.parse(response) }
        assertTrue(failure.message.orEmpty().contains("30 tasks"))
    }
}
