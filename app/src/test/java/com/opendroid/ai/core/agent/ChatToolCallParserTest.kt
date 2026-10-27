package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatToolCallParserTest {
    @Test
    fun parsesPlainShellToolCall() {
        val call = ChatToolCallParser.parse("""{"action":"shell","command":"pm list packages"}""")
        assertEquals("shell", call?.action)
        assertEquals("pm list packages", call?.command)
    }

    @Test
    fun parsesMarkdownWrappedAppAndUrlCalls() {
        val app = ChatToolCallParser.parse("""```json
{"action":"open_app","package":"com.example.app"}
```""")
        val url = ChatToolCallParser.parse("""{"action":"open_url","url":"https://example.com"}""")
        assertEquals("com.example.app", app?.packageName)
        assertEquals("https://example.com", url?.url)
    }

    @Test
    fun doesNotTurnProseExamplesIntoExecutableActions() {
        val call = ChatToolCallParser.parse("Example: {\"action\":\"shell\",\"command\":\"git status\"}")
        assertNull(call)
    }

    @Test
    fun leavesOrdinaryAssistantTextUnchanged() {
        assertNull(ChatToolCallParser.parse("I can help you list packages if you want."))
    }

    @Test
    fun doesNotAcceptTruncatedToolJson() {
        assertNull(ChatToolCallParser.parse("""{"action":"shell","command":"pm list packages"""))
    }
}
