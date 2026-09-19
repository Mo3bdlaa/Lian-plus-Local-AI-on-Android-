package com.lian.plus.core.model

/** What an installed file is used for. */
enum class ModelKind {
    /** Causal LM used for chat and completions. */
    TEXT,
    /** Embedding model used by the retrieval pipeline. */
    EMBEDDING,
    /** Diffusion checkpoint used by the image engine. */
    IMAGE,
    /** Companion file for a diffusion checkpoint (VAE, TAESD, text encoder). */
    IMAGE_COMPONENT,
}

/** Which component of an image pipeline a companion file provides. */
enum class ImageComponent { VAE, TAESD, CLIP_L, CLIP_G, T5XXL, DIFFUSION, UPSCALER }

/**
 * Quantisation, ordered from smallest to largest. The ordering is what the
 * download picker uses to choose the best variant that fits the device budget.
 */
enum class Quant(val tag: String, val bitsPerWeight: Double, val quality: Int) {
    IQ1_S("IQ1_S", 1.6, 1),
    IQ2_XXS("IQ2_XXS", 2.1, 2),
    IQ2_M("IQ2_M", 2.7, 3),
    Q2_K("Q2_K", 2.6, 3),
    IQ3_XXS("IQ3_XXS", 3.1, 4),
    Q3_K_S("Q3_K_S", 3.4, 4),
    Q3_K_M("Q3_K_M", 3.9, 5),
    IQ4_XS("IQ4_XS", 4.3, 6),
    Q4_0("Q4_0", 4.5, 6),
    Q4_K_S("Q4_K_S", 4.6, 7),
    Q4_K_M("Q4_K_M", 4.9, 8),
    Q5_K_S("Q5_K_S", 5.5, 8),
    Q5_K_M("Q5_K_M", 5.7, 9),
    Q6_K("Q6_K", 6.6, 10),
    Q8_0("Q8_0", 8.5, 11),
    F16("F16", 16.0, 12),
    F32("F32", 32.0, 12),
    UNKNOWN("?", 5.0, 5);

    companion object {
        /** Reads the quantisation out of a GGUF filename, e.g. `...-Q4_K_M.gguf`. */
        fun fromFileName(name: String): Quant {
            val upper = name.uppercase()
            // Longest tag first so Q4_K_M is not matched as Q4_K_S's prefix.
            return entries
                .filter { it != UNKNOWN }
                .sortedByDescending { it.tag.length }
                .firstOrNull { upper.contains(it.tag) }
                ?: UNKNOWN
        }
    }
}

/** A model file that lives on the device. */
data class InstalledModel(
    val id: String,
    val displayName: String,
    val kind: ModelKind,
    val filePath: String,
    val sizeBytes: Long,
    val repoId: String?,
    val fileName: String,
    val quant: Quant,
    val architecture: String?,
    val parameterCount: Long?,
    val contextTrained: Int?,
    val embeddingDim: Int?,
    val chatTemplate: String?,
    val component: ImageComponent? = null,
    val addedAtMillis: Long = System.currentTimeMillis(),
) {
    val sizeLabel: String get() = formatBytes(sizeBytes)
    val parameterLabel: String? get() = parameterCount?.let { formatParams(it) }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

fun formatParams(count: Long): String = when {
    count >= 1_000_000_000 -> "%.1fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.0fM".format(count / 1_000_000.0)
    else -> count.toString()
}
