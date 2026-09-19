package com.lian.plus.llm

/** One turn of a conversation as the model sees it. */
data class ChatTurn(val role: String, val content: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"
    }
}

/**
 * Turns a conversation into a prompt string.
 *
 * The GGUF file's own Jinja template is always preferred — llama.cpp applies it
 * natively and it is the only thing guaranteed to match how the model was
 * trained. The hand-written templates below exist for the minority of files
 * published without one; getting these wrong shows up as a model that rambles
 * or never stops, so each follows the format published with its architecture.
 */
enum class ChatFormat(val id: String, val label: String) {
    AUTO("auto", "From the model file"),
    CHATML("chatml", "ChatML (Qwen, Yi, Hermes)"),
    LLAMA3("llama3", "Llama 3.x"),
    GEMMA("gemma", "Gemma"),
    PHI3("phi3", "Phi-3"),
    MISTRAL("mistral", "Mistral / Mixtral"),
    ZEPHYR("zephyr", "Zephyr"),
    PLAIN("plain", "Plain text");

    companion object {
        /** Best guess from the GGUF `general.architecture` value. */
        fun forArchitecture(arch: String?): ChatFormat = when (arch?.lowercase()) {
            "qwen2", "qwen3", "qwen2moe", "qwen3moe" -> CHATML
            "llama" -> LLAMA3
            "gemma", "gemma2", "gemma3" -> GEMMA
            "phi2", "phi3" -> PHI3
            else -> CHATML
        }
    }
}

object ChatFormatter {

    /**
     * Renders [turns] with the built-in template for [format].
     * `AUTO` falls back to ChatML, which is the most common convention.
     */
    fun render(format: ChatFormat, turns: List<ChatTurn>, addGenerationPrompt: Boolean): String =
        when (format) {
            ChatFormat.LLAMA3 -> llama3(turns, addGenerationPrompt)
            ChatFormat.GEMMA -> gemma(turns, addGenerationPrompt)
            ChatFormat.PHI3 -> phi3(turns, addGenerationPrompt)
            ChatFormat.MISTRAL -> mistral(turns, addGenerationPrompt)
            ChatFormat.ZEPHYR -> zephyr(turns, addGenerationPrompt)
            ChatFormat.PLAIN -> plain(turns, addGenerationPrompt)
            ChatFormat.AUTO, ChatFormat.CHATML -> chatml(turns, addGenerationPrompt)
        }

    /** Sequences that should terminate generation for [format]. */
    fun stopSequences(format: ChatFormat): List<String> = when (format) {
        ChatFormat.LLAMA3 -> listOf("<|eot_id|>", "<|end_of_text|>")
        ChatFormat.GEMMA -> listOf("<end_of_turn>")
        ChatFormat.PHI3 -> listOf("<|end|>", "<|user|>")
        ChatFormat.MISTRAL -> listOf("</s>", "[INST]")
        ChatFormat.ZEPHYR -> listOf("</s>", "<|user|>")
        ChatFormat.PLAIN -> listOf("\nUser:", "\nuser:")
        ChatFormat.AUTO, ChatFormat.CHATML -> listOf("<|im_end|>", "<|im_start|>")
    }

    private fun chatml(turns: List<ChatTurn>, addGen: Boolean) = buildString {
        for (t in turns) {
            append("<|im_start|>").append(t.role).append('\n')
            append(t.content).append("<|im_end|>\n")
        }
        if (addGen) append("<|im_start|>assistant\n")
    }

    private fun llama3(turns: List<ChatTurn>, addGen: Boolean) = buildString {
        append("<|begin_of_text|>")
        for (t in turns) {
            append("<|start_header_id|>").append(t.role).append("<|end_header_id|>\n\n")
            append(t.content).append("<|eot_id|>")
        }
        if (addGen) append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private fun gemma(turns: List<ChatTurn>, addGen: Boolean) = buildString {
        // Gemma has no system role; the system prompt is folded into the first
        // user turn, which is what the reference template does too.
        val system = turns.firstOrNull { it.role == ChatTurn.SYSTEM }?.content
        var systemUsed = system == null
        for (t in turns) {
            if (t.role == ChatTurn.SYSTEM) continue
            val role = if (t.role == ChatTurn.ASSISTANT) "model" else "user"
            append("<start_of_turn>").append(role).append('\n')
            if (!systemUsed && role == "user") {
                append(system).append("\n\n")
                systemUsed = true
            }
            append(t.content).append("<end_of_turn>\n")
        }
        if (addGen) append("<start_of_turn>model\n")
    }

    private fun phi3(turns: List<ChatTurn>, addGen: Boolean) = buildString {
        for (t in turns) {
            append("<|").append(t.role).append("|>\n")
            append(t.content).append("<|end|>\n")
        }
        if (addGen) append("<|assistant|>\n")
    }

    private fun mistral(turns: List<ChatTurn>, addGen: Boolean) = buildString {
        append("<s>")
        val system = turns.firstOrNull { it.role == ChatTurn.SYSTEM }?.content
        var systemUsed = system == null
        for (t in turns) {
            when (t.role) {
                ChatTurn.SYSTEM -> Unit
                ChatTurn.ASSISTANT -> append(' ').append(t.content).append("</s>")
                else -> {
                    append("[INST] ")
                    if (!systemUsed) { append(system).append("\n\n"); systemUsed = true }
                    append(t.content).append(" [/INST]")
                }
            }
        }
        if (addGen) Unit // [/INST] already opens the assistant turn
    }

    private fun zephyr(turns: List<ChatTurn>, addGen: Boolean) = buildString {
        for (t in turns) {
            append("<|").append(t.role).append("|>\n").append(t.content).append("</s>\n")
        }
        if (addGen) append("<|assistant|>\n")
    }

    private fun plain(turns: List<ChatTurn>, addGen: Boolean) = buildString {
        for (t in turns) {
            val label = when (t.role) {
                ChatTurn.SYSTEM -> "System"
                ChatTurn.ASSISTANT -> "Assistant"
                ChatTurn.TOOL -> "Tool"
                else -> "User"
            }
            append(label).append(": ").append(t.content).append("\n\n")
        }
        if (addGen) append("Assistant: ")
    }
}
