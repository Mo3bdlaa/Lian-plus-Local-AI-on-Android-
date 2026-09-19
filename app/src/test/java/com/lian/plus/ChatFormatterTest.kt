package com.lian.plus

import com.lian.plus.llm.ChatFormat
import com.lian.plus.llm.ChatFormatter
import com.lian.plus.llm.ChatTurn
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFormatterTest {

    private val turns = listOf(
        ChatTurn(ChatTurn.SYSTEM, "Be brief."),
        ChatTurn(ChatTurn.USER, "Hi"),
    )

    @Test
    fun `chatml opens an assistant turn`() {
        val out = ChatFormatter.render(ChatFormat.CHATML, turns, addGenerationPrompt = true)
        assertTrue(out.contains("<|im_start|>system\nBe brief.<|im_end|>"))
        assertTrue(out.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `llama3 uses header tokens`() {
        val out = ChatFormatter.render(ChatFormat.LLAMA3, turns, addGenerationPrompt = true)
        assertTrue(out.startsWith("<|begin_of_text|>"))
        assertTrue(out.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    @Test
    fun `gemma folds the system prompt into the first user turn`() {
        val out = ChatFormatter.render(ChatFormat.GEMMA, turns, addGenerationPrompt = true)
        // Gemma has no system role, so it must not emit one.
        assertTrue(!out.contains("<start_of_turn>system"))
        assertTrue(out.contains("<start_of_turn>user\nBe brief.\n\nHi<end_of_turn>"))
        assertTrue(out.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun `every format declares stop sequences`() {
        ChatFormat.entries.forEach { format ->
            assertTrue(
                "$format has no stop sequences",
                ChatFormatter.stopSequences(format).isNotEmpty(),
            )
        }
    }
}
