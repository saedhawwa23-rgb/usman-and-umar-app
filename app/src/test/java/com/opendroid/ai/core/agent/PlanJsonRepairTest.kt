package com.opendroid.ai.core.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanJsonRepairTest {
    @Test
    fun stripsMarkdownFenceAndSurroundingProse() {
        val result = PlanJsonRepair.parse(
            "Here is the plan:\n```json\n{\"planId\":\"p1\",\"steps\":[]}\n```\nDone.",
        )
        assertEquals("p1", result.jsonObject["planId"]?.jsonPrimitive?.content)
        assertTrue(result.jsonObject["steps"] != null)
    }

    @Test
    fun closesTruncatedPlanObjectAtEof() {
        val result = PlanJsonRepair.parse(
            """{"planId":"p1","goal":"test","estimatedDuration":"1m","estimatedSteps":1,"steps":[]""",
        )
        assertEquals("p1", result.jsonObject["planId"]?.jsonPrimitive?.content)
        assertEquals(1, result.jsonObject["estimatedSteps"]?.jsonPrimitive?.int)
    }

    @Test
    fun unwrapsDoubleEncodedJsonString() {
        val inner = """{"planId":"p2","steps":[]}"""
        val result = PlanJsonRepair.parse(JsonPrimitive(inner).toString())
        assertEquals("p2", result.jsonObject["planId"]?.jsonPrimitive?.content)
    }

    @Test
    fun gsonLenientFallbackAcceptsUnquotedKeysAndSingleQuotedStrings() {
        val result = PlanJsonRepair.parse(
            "{planId:'p3', goal:'lenient', estimatedDuration:'1m', estimatedSteps:0, steps:[],}",
        )
        assertEquals("p3", result.jsonObject["planId"]?.jsonPrimitive?.content)
        assertEquals("lenient", result.jsonObject["goal"]?.jsonPrimitive?.content)
    }

    @Test
    fun removesAnIncompleteTrailingPropertyBeforeClosingContainers() {
        val result = PlanJsonRepair.parse(
            """{"planId":"p4","goal":"safe","estimatedDuration":"1m","estimatedSteps":1,"steps":[],"unfinished":""",
        )
        assertEquals("p4", result.jsonObject["planId"]?.jsonPrimitive?.content)
        assertTrue("unfinished" !in result.jsonObject)
    }
}
