package com.lian.plus.core.model

/**
 * What a `.gguf` file actually is.
 *
 * A model repository rarely contains only models. Alongside the weights sit
 * vision projectors, draft heads, adapters and the shards of a split model —
 * all with the same extension. Offering every one of them as something to
 * "use" produces exactly one outcome: a download, then `llama.cpp could not
 * load this file`.
 */
enum class GgufRole(val label: String) {
    /** A complete model that an engine can load on its own. */
    STANDALONE("Model"),

    /** Multimodal vision projector — a companion to a model, not a model. */
    VISION_PROJECTOR("Vision projector"),

    /** Multi-token-prediction or draft head, used to speed up a base model. */
    DRAFT("Draft head"),

    /** LoRA or control adapter applied on top of a model. */
    ADAPTER("Adapter"),

    /** One piece of a model split across several files. */
    SHARD("Model shard"),

    /** A text encoder or VAE belonging to a diffusion pipeline. */
    DIFFUSION_COMPONENT("Pipeline component");

    val isLoadable: Boolean get() = this == STANDALONE

    /** Why this cannot be loaded on its own, for the UI to show. */
    val explanation: String
        get() = when (this) {
            STANDALONE -> ""
            VISION_PROJECTOR ->
                "This is the vision half of a multimodal model. It only works " +
                    "alongside the language model from the same repository."
            DRAFT ->
                "A draft head used to speed up a base model. It has no vocabulary " +
                    "or layers of its own."
            ADAPTER ->
                "An adapter applied on top of a base model, not a model in itself."
            SHARD ->
                "One piece of a model split across several files. All of the pieces " +
                    "have to be downloaded together."
            DIFFUSION_COMPONENT ->
                "A text encoder or VAE from an image pipeline. Load it with the " +
                    "checkpoint it belongs to."
        }
}

object GgufRoleDetector {

    private val SHARD = Regex("""-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)

    /**
     * Guesses the role from the file name.
     *
     * Names are a convention rather than a guarantee, so this is only used to
     * label things in the browser before anything is downloaded;
     * [roleFromMetadata] has the last word once the file is on disk.
     */
    fun roleFromName(fileName: String): GgufRole {
        val name = fileName.lowercase()
        return when {
            SHARD.containsMatchIn(name) -> GgufRole.SHARD
            name.startsWith("mmproj") || name.contains("mmproj") ||
                name.contains("vision-proj") -> GgufRole.VISION_PROJECTOR
            name.startsWith("mtp") || name.contains("-mtp.") ||
                name.contains("draft") || name.contains("eagle") -> GgufRole.DRAFT
            name.contains("lora") || name.contains("adapter") ||
                name.contains("control") -> GgufRole.ADAPTER
            name.startsWith("t5") || name.startsWith("clip_") ||
                name.startsWith("clip-") || name.contains("text_encoder") ||
                name == "vae.gguf" || name.startsWith("vae-") -> GgufRole.DIFFUSION_COMPONENT
            else -> GgufRole.STANDALONE
        }
    }

    /**
     * The authoritative answer, from the file's own header.
     *
     * A language model declares an architecture and a block count. A vision
     * projector declares a `clip`-family architecture and no transformer
     * blocks, which is why it loads as nothing.
     */
    fun roleFromMetadata(info: GgufInspector.Info?, fileName: String): GgufRole {
        if (info == null) return roleFromName(fileName)

        val arch = info.architecture?.lowercase().orEmpty()
        return when {
            info.splitCount != null && info.splitCount > 1 -> GgufRole.SHARD
            arch.startsWith("clip") || arch == "mmproj" -> GgufRole.VISION_PROJECTOR
            arch == "t5encoder" || arch == "vae" -> GgufRole.DIFFUSION_COMPONENT
            // No layers means nothing to run: adapters, projectors and draft
            // heads all land here whatever they call themselves.
            info.blockCount == null || info.blockCount <= 0 -> roleFromName(fileName)
            else -> GgufRole.STANDALONE
        }
    }

    /** The shard set a file belongs to, or null when it stands alone. */
    fun splitBaseName(fileName: String): String? =
        SHARD.find(fileName)?.let { fileName.removeRange(it.range) }

    fun shardTotal(fileName: String): Int? =
        SHARD.find(fileName)?.groupValues?.get(2)?.toIntOrNull()
}
