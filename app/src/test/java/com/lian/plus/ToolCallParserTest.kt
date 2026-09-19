package com.lian.plus

import com.lian.plus.tools.ToolCallParser
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    private val known = setOf("web_search", "calculate", "get_current_time")

    @Test
    fun `parses the tool_call tag form`() {
        val calls = ToolCallParser.parse(
            """Let me look that up.
               <tool_call>{"name": "web_search", "arguments": {"query": "cairo weather"}}</tool_call>""",
            known,
        )
        assertEquals(1, calls.size)
        assertEquals("web_search", calls[0].name)
        assertEquals("cairo weather", calls[0].arguments["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parses the llama function tag form`() {
        val calls = ToolCallParser.parse(
            """<function=calculate>{"expression": "2+2"}</function>""",
            known,
        )
        assertEquals(1, calls.size)
        assertEquals("calculate", calls[0].name)
    }

    @Test
    fun `parses a fenced json block`() {
        val calls = ToolCallParser.parse(
            """```json
               {"name": "get_current_time", "arguments": {}}
               ```""",
            known,
        )
        assertEquals(1, calls.size)
        assertEquals("get_current_time", calls[0].name)
    }

    @Test
    fun `accepts stringified arguments`() {
        val calls = ToolCallParser.parse(
            """<tool_call>{"name":"calculate","arguments":"{\"expression\":\"7*6\"}"}</tool_call>""",
            known,
        )
        assertEquals("7*6", calls[0].arguments["expression"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ignores json that is not a known tool`() {
        val calls = ToolCallParser.parse(
            """Here is the config you asked for: {"name": "my-service", "port": 8080}""",
            known,
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `ignores prose with no call at all`() {
        assertTrue(ToolCallParser.parse("The capital of Egypt is Cairo.", known).isEmpty())
    }

    @Test
    fun `strips the call span from the visible text`() {
        val text = """Sure.<tool_call>{"name": "calculate", "arguments": {"expression": "1+1"}}</tool_call>"""
        val calls = ToolCallParser.parse(text, known)
        assertEquals("Sure.", ToolCallParser.stripCalls(text, calls))
    }
}
