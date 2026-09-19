package com.lian.plus

import com.lian.plus.tools.builtin.CalculatorTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorToolTest {

    private val tool = CalculatorTool()

    private suspend fun eval(expr: String) =
        tool.execute(JsonObject(mapOf("expression" to JsonPrimitive(expr))))

    @Test
    fun `respects operator precedence`() = runTest {
        assertEquals("2+3*4 = 14", eval("2+3*4").content)
    }

    @Test
    fun `handles parentheses and unary minus`() = runTest {
        assertEquals("-(2+3)*2 = -10", eval("-(2+3)*2").content)
    }

    @Test
    fun `power is right associative`() = runTest {
        assertEquals("2^3^2 = 512", eval("2^3^2").content)
    }

    @Test
    fun `supports functions and constants`() = runTest {
        assertEquals("sqrt(144) = 12", eval("sqrt(144)").content)
        assertEquals("max(3, 9) = 9", eval("max(3, 9)").content)
    }

    @Test
    fun `parses scientific notation`() = runTest {
        assertEquals("1.5e3 = 1500", eval("1.5e3").content)
    }

    @Test
    fun `reports division by zero instead of returning infinity`() = runTest {
        val result = eval("5/0")
        assertTrue(result.isError)
        assertTrue(result.content.contains("division by zero"))
    }

    @Test
    fun `rejects anything that is not arithmetic`() = runTest {
        assertTrue(eval("System.exit(0)").isError)
        assertTrue(eval("2 +").isError)
        assertTrue(eval("drop table users").isError)
    }
}
