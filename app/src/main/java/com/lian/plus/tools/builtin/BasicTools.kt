package com.lian.plus.tools.builtin

import android.content.Context
import com.lian.plus.core.device.DeviceProfiler
import com.lian.plus.core.model.formatBytes
import com.lian.plus.tools.Tool
import com.lian.plus.tools.ToolParameter
import com.lian.plus.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

internal fun JsonObject.num(key: String): Double? =
    runCatching { this[key]?.jsonPrimitive?.content?.toDouble() }.getOrNull()

/** Gives the model a reliable clock — models have no idea what day it is. */
class ClockTool : Tool {
    override val name = "get_current_time"
    override val description =
        "The current date and time on this device. Call this before answering anything " +
            "that depends on today's date."
    override val parameters = listOf(
        ToolParameter(
            name = "timezone",
            type = "string",
            description = "IANA timezone such as Africa/Cairo. Defaults to the device timezone.",
            required = false,
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val tz = args.str("timezone")?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        val fmt = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm:ss z", Locale.US)
        fmt.timeZone = tz
        return ToolResult(fmt.format(Date()))
    }
}

/**
 * Arithmetic, evaluated by a small recursive-descent parser.
 *
 * Deliberately not a scripting engine: this runs text the model wrote, so the
 * grammar is limited to numbers, the five operators, parentheses and a fixed
 * set of functions. There is nothing here that can reach the device.
 */
class CalculatorTool : Tool {
    override val name = "calculate"
    override val description =
        "Evaluates an arithmetic expression. Supports + - * / %, ^, parentheses and " +
            "sqrt, abs, min, max, floor, round, sin, cos, tan, log, ln."
    override val parameters = listOf(
        ToolParameter("expression", "string", "For example: (1920*1080)/1e6"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val expr = args.str("expression")
            ?: return ToolResult("No expression given.", isError = true)
        return try {
            val value = Evaluator(expr).parse()
            val text = if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
                value.toLong().toString()
            } else {
                value.toString()
            }
            ToolResult("$expr = $text")
        } catch (e: Exception) {
            ToolResult("Could not evaluate '$expr': ${e.message}", isError = true)
        }
    }

    private class Evaluator(private val src: String) {
        private var pos = 0

        fun parse(): Double {
            val v = expression()
            skipSpace()
            require(pos >= src.length) { "unexpected '${src[pos]}' at position $pos" }
            return v
        }

        private fun skipSpace() { while (pos < src.length && src[pos].isWhitespace()) pos++ }

        private fun eat(c: Char): Boolean {
            skipSpace()
            if (pos < src.length && src[pos] == c) { pos++; return true }
            return false
        }

        private fun expression(): Double {
            var x = term()
            while (true) {
                x = when {
                    eat('+') -> x + term()
                    eat('-') -> x - term()
                    else -> return x
                }
            }
        }

        private fun term(): Double {
            var x = power()
            while (true) {
                x = when {
                    eat('*') -> x * power()
                    eat('/') -> {
                        val d = power()
                        require(d != 0.0) { "division by zero" }
                        x / d
                    }
                    eat('%') -> {
                        val d = power()
                        require(d != 0.0) { "modulo by zero" }
                        x % d
                    }
                    else -> return x
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            // Right-associative, so 2^3^2 is 2^(3^2).
            return if (eat('^')) Math.pow(base, power()) else base
        }

        private fun unary(): Double {
            if (eat('-')) return -unary()
            if (eat('+')) return unary()
            return atom()
        }

        private fun atom(): Double {
            skipSpace()
            require(pos < src.length) { "expression ended early" }

            if (eat('(')) {
                val v = expression()
                require(eat(')')) { "missing ')'" }
                return v
            }

            val start = pos
            if (src[pos].isDigit() || src[pos] == '.') {
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                // Scientific notation: 1e6, 2.5E-3
                if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                    val mark = pos
                    pos++
                    if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                    if (pos < src.length && src[pos].isDigit()) {
                        while (pos < src.length && src[pos].isDigit()) pos++
                    } else pos = mark
                }
                return src.substring(start, pos).toDouble()
            }

            if (src[pos].isLetter()) {
                while (pos < src.length && src[pos].isLetter()) pos++
                val fn = src.substring(start, pos).lowercase()
                when (fn) {
                    "pi" -> return Math.PI
                    "e" -> return Math.E
                }
                require(eat('(')) { "'$fn' needs parentheses" }
                val a = expression()
                val b = if (eat(',')) expression() else null
                require(eat(')')) { "missing ')' after '$fn'" }
                return when (fn) {
                    "sqrt" -> { require(a >= 0) { "sqrt of a negative number" }; kotlin.math.sqrt(a) }
                    "abs" -> kotlin.math.abs(a)
                    "floor" -> kotlin.math.floor(a)
                    "ceil" -> kotlin.math.ceil(a)
                    "round" -> kotlin.math.round(a)
                    "sin" -> kotlin.math.sin(a)
                    "cos" -> kotlin.math.cos(a)
                    "tan" -> kotlin.math.tan(a)
                    "ln" -> { require(a > 0) { "ln of a non-positive number" }; kotlin.math.ln(a) }
                    "log" -> { require(a > 0) { "log of a non-positive number" }; kotlin.math.log10(a) }
                    "min" -> kotlin.math.min(a, requireNotNull(b) { "min needs two arguments" })
                    "max" -> kotlin.math.max(a, requireNotNull(b) { "max needs two arguments" })
                    else -> throw IllegalArgumentException("unknown function '$fn'")
                }
            }
            throw IllegalArgumentException("unexpected '${src[pos]}' at position $pos")
        }
    }
}

/** Lets the model answer questions about the phone it is running on. */
class DeviceInfoTool(private val context: Context) : Tool {
    override val name = "get_device_info"
    override val description =
        "Hardware details for the phone this assistant is running on: memory, CPU, " +
            "storage and thermal state."
    override val parameters = emptyList<ToolParameter>()

    override suspend fun execute(args: JsonObject): ToolResult {
        val p = DeviceProfiler.profile(context)
        val text = buildString {
            appendLine("Device: ${p.manufacturer} ${p.model}")
            appendLine("SoC: ${p.socModel}")
            appendLine("Android ${p.androidRelease} (API ${p.sdkInt})")
            appendLine("RAM: ${formatBytes(p.totalRamBytes)} total, ${formatBytes(p.availableRamBytes)} free")
            appendLine("CPU: ${p.cpuCores} cores (${p.performanceCores} performance)")
            appendLine("Storage free: ${formatBytes(p.freeStorageBytes)}")
            append("Thermal: ${p.thermalStatus.name.lowercase()}")
        }
        return ToolResult(text, displaySummary = "Read device information")
    }
}
