package com.lian.plus.hub

import com.lian.plus.core.device.DeviceTier
import com.lian.plus.core.model.ImageComponent
import com.lian.plus.core.model.ModelKind

/**
 * A short, opinionated list of models that are known to work well on phones.
 *
 * Search covers the rest of the Hub; this exists so a first-time user is not
 * asked to pick a quantisation out of four hundred files. Every entry below was
 * checked to exist as a public GGUF repository.
 */
data class CuratedModel(
    val id: String,
    val title: String,
    val repoId: String,
    /** Substring that identifies the preferred file inside the repo. */
    val preferredFileHint: String,
    val kind: ModelKind,
    val approxSizeBytes: Long,
    val minTier: DeviceTier,
    val blurb: String,
    val strengths: List<String>,
    val component: ImageComponent? = null,
)

/**
 * Several files that only make sense together.
 *
 * A modern image model is not a checkpoint any more; it is a transformer, a
 * text encoder and a VAE, and missing any one of them means nothing loads.
 */
data class CuratedPipeline(
    val id: String,
    val title: String,
    val repoIdOrNull: String? = null,
    val minTier: DeviceTier,
    val blurb: String,
    val strengths: List<String>,
    val parts: List<CuratedModel>,
) {
    val totalBytes: Long get() = parts.sumOf { it.approxSizeBytes }
}

object CuratedCatalog {

    private const val MB = 1024L * 1024
    private const val GB = 1024L * MB

    val text: List<CuratedModel> = listOf(
        CuratedModel(
            id = "qwen25-1_5b",
            title = "Qwen2.5 1.5B Instruct",
            repoId = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            preferredFileHint = "q4_k_m",
            kind = ModelKind.TEXT,
            approxSizeBytes = 1100 * MB,
            minTier = DeviceTier.MINIMAL,
            blurb = "The smallest model here that still holds a real conversation. " +
                "Fast enough to feel instant on any 64-bit phone.",
            strengths = listOf("Very fast", "Low memory", "Multilingual"),
        ),
        CuratedModel(
            id = "gemma2-2b",
            title = "Gemma 2 2B Instruct",
            repoId = "lmstudio-community/gemma-2-2b-it-GGUF",
            preferredFileHint = "Q4_K_M",
            kind = ModelKind.TEXT,
            approxSizeBytes = 1700 * MB,
            minTier = DeviceTier.ENTRY,
            blurb = "Strong writing quality for its size, with an 8K context window.",
            strengths = listOf("Good prose", "Balanced"),
        ),
        CuratedModel(
            id = "llama32-3b",
            title = "Llama 3.2 3B Instruct",
            repoId = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            preferredFileHint = "Q4_K_M",
            kind = ModelKind.TEXT,
            approxSizeBytes = 2000 * MB,
            minTier = DeviceTier.ENTRY,
            blurb = "Reliable instruction following and solid tool-calling behaviour.",
            strengths = listOf("Tool calling", "128K context", "Multilingual"),
        ),
        CuratedModel(
            id = "qwen25-3b",
            title = "Qwen2.5 3B Instruct",
            repoId = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            preferredFileHint = "q4_k_m",
            kind = ModelKind.TEXT,
            approxSizeBytes = 1900 * MB,
            minTier = DeviceTier.ENTRY,
            blurb = "A good default: broad knowledge, strong at structured output.",
            strengths = listOf("JSON output", "Coding", "Multilingual"),
        ),
        CuratedModel(
            id = "qwen3-4b",
            title = "Qwen3 4B Instruct",
            repoId = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
            preferredFileHint = "Q4_K_M",
            kind = ModelKind.TEXT,
            approxSizeBytes = 2500 * MB,
            minTier = DeviceTier.CAPABLE,
            blurb = "Noticeably better reasoning than the 3B class, still phone-sized.",
            strengths = listOf("Reasoning", "Long context", "Tool calling"),
        ),
        CuratedModel(
            id = "phi35-mini",
            title = "Phi-3.5 Mini Instruct",
            repoId = "bartowski/Phi-3.5-mini-instruct-GGUF",
            preferredFileHint = "Q4_K_M",
            kind = ModelKind.TEXT,
            approxSizeBytes = 2400 * MB,
            minTier = DeviceTier.CAPABLE,
            blurb = "Punches above its weight on reasoning and maths.",
            strengths = listOf("Reasoning", "Maths", "128K context"),
        ),
        CuratedModel(
            id = "qwen25-7b",
            title = "Qwen2.5 7B Instruct",
            repoId = "bartowski/Qwen2.5-7B-Instruct-GGUF",
            preferredFileHint = "Q4_K_M",
            kind = ModelKind.TEXT,
            approxSizeBytes = 4700 * MB,
            minTier = DeviceTier.HIGH_END,
            blurb = "Desktop-class quality. Wants 8 GB of RAM or more to stay resident.",
            strengths = listOf("Best quality", "Coding", "Tool calling"),
        ),
        CuratedModel(
            id = "llama31-8b",
            title = "Llama 3.1 8B Instruct",
            repoId = "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF",
            preferredFileHint = "Q4_K_M",
            kind = ModelKind.TEXT,
            approxSizeBytes = 4900 * MB,
            minTier = DeviceTier.HIGH_END,
            blurb = "The classic 8B workhorse, with dependable tool calling.",
            strengths = listOf("Tool calling", "General purpose"),
        ),
    )

    val embedding: List<CuratedModel> = listOf(
        CuratedModel(
            id = "minilm-l6",
            title = "all-MiniLM-L6-v2",
            repoId = "second-state/All-MiniLM-L6-v2-Embedding-GGUF",
            preferredFileHint = "Q8_0",
            kind = ModelKind.EMBEDDING,
            approxSizeBytes = 25 * MB,
            minTier = DeviceTier.MINIMAL,
            blurb = "Tiny 384-dimension embedder. Enough for searching your own notes.",
            strengths = listOf("Tiny", "Fast"),
        ),
        CuratedModel(
            id = "bge-small",
            title = "BGE Small EN v1.5",
            repoId = "ChristianAzinn/bge-small-en-v1.5-gguf",
            preferredFileHint = "q8_0",
            kind = ModelKind.EMBEDDING,
            approxSizeBytes = 36 * MB,
            minTier = DeviceTier.MINIMAL,
            blurb = "Better English retrieval quality than MiniLM for a few extra MB.",
            strengths = listOf("Retrieval quality"),
        ),
        CuratedModel(
            id = "nomic-embed",
            title = "Nomic Embed Text v1.5",
            repoId = "nomic-ai/nomic-embed-text-v1.5-GGUF",
            preferredFileHint = "Q4_K_M",
            kind = ModelKind.EMBEDDING,
            approxSizeBytes = 84 * MB,
            minTier = DeviceTier.ENTRY,
            blurb = "768 dimensions and an 8K input window — good for long documents.",
            strengths = listOf("Long inputs", "High quality"),
        ),
    )

    val image: List<CuratedModel> = listOf(
        CuratedModel(
            id = "sd-turbo",
            title = "SD Turbo",
            repoId = "Green-Sky/SD-Turbo-GGUF",
            preferredFileHint = "q8_0",
            kind = ModelKind.IMAGE,
            approxSizeBytes = 1930 * MB,
            minTier = DeviceTier.CAPABLE,
            blurb = "Produces a 512px image in one to four steps, which is what makes " +
                "image generation practical on a phone at all.",
            strengths = listOf("1-4 steps", "Fastest", "512px"),
        ),
        CuratedModel(
            id = "sd15",
            title = "Stable Diffusion 1.5",
            repoId = "second-state/stable-diffusion-v1-5-GGUF",
            preferredFileHint = "Q4_0",
            kind = ModelKind.IMAGE,
            approxSizeBytes = 1566 * MB,
            minTier = DeviceTier.CAPABLE,
            blurb = "The most widely supported checkpoint. Needs 20-30 steps, so expect " +
                "a minute or two per image.",
            strengths = listOf("Compatible", "512px", "Small"),
        ),
        CuratedModel(
            id = "sdxl-turbo",
            title = "SDXL Turbo",
            repoId = "OlegSkutte/sdxl-turbo-GGUF",
            preferredFileHint = "q8_0",
            kind = ModelKind.IMAGE,
            approxSizeBytes = 3909L * MB,
            minTier = DeviceTier.FLAGSHIP,
            blurb = "Much better 1024px output, at roughly four gigabytes. Only worth " +
                "it on a 12 GB device.",
            strengths = listOf("1024px", "Best quality"),
        ),
    )

    /**
     * Pipelines you assemble rather than download.
     *
     * Z-Image and Qwen-Image publish the diffusion transformer, the text
     * encoder and the VAE as three separate files, often in three different
     * repositories. Left to the browser that is a research exercise; here it
     * is one tap, and the app queues all three.
     */
    val pipelines: List<CuratedPipeline> = listOf(
        CuratedPipeline(
            id = "z-image-turbo",
            title = "Z-Image Turbo",
            minTier = DeviceTier.FLAGSHIP,
            blurb = "A 6B model that makes a 1024px image in 8 steps. The closest " +
                "thing to a modern image model that a phone can actually hold — but " +
                "it is three files and about 6 GB in total, so it needs a phone with " +
                "real memory free.",
            strengths = listOf("8 steps", "1024px", "Modern"),
            parts = listOf(
                CuratedModel(
                    id = "z-image-turbo-dit",
                    title = "Z-Image Turbo transformer",
                    repoId = "leejet/Z-Image-Turbo-GGUF",
                    preferredFileHint = "Q3_K",
                    kind = ModelKind.IMAGE,
                    approxSizeBytes = 3140 * MB,
                    minTier = DeviceTier.FLAGSHIP,
                    blurb = "The diffusion transformer itself.",
                    strengths = emptyList(),
                ),
                CuratedModel(
                    id = "z-image-vae",
                    title = "Z-Image VAE",
                    repoId = "Tongyi-MAI/Z-Image-Turbo",
                    preferredFileHint = "vae/",
                    kind = ModelKind.IMAGE_COMPONENT,
                    approxSizeBytes = 170 * MB,
                    minTier = DeviceTier.FLAGSHIP,
                    blurb = "Turns the model's latents into pixels.",
                    strengths = emptyList(),
                    component = ImageComponent.VAE,
                ),
                CuratedModel(
                    id = "qwen3-4b-encoder",
                    title = "Qwen3 4B Instruct",
                    repoId = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
                    preferredFileHint = "Q4_K_M",
                    kind = ModelKind.TEXT,
                    approxSizeBytes = 2500 * MB,
                    minTier = DeviceTier.FLAGSHIP,
                    blurb = "Reads the prompt. It is an ordinary chat model, so it " +
                        "doubles as one in the Chat tab at no extra cost.",
                    strengths = emptyList(),
                    component = ImageComponent.LLM,
                ),
            ),
        ),
    )

    fun forTier(tier: DeviceTier): List<CuratedModel> =
        (text + embedding + image).filter { it.minTier <= tier }

    fun recommendedFor(tier: DeviceTier): CuratedModel? =
        text.filter { it.minTier <= tier }.maxByOrNull { it.approxSizeBytes }
}
