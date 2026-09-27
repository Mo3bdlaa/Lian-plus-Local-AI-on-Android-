package com.lian.plus.core.model

/**
 * A diffusion checkpoint together with the companion files it needs.
 *
 * For SD 1.5 or SDXL this is a pipeline of one: everything is in the
 * checkpoint. For Qwen-Image, Z-Image or Flux the GGUF on the Hub is the
 * diffusion transformer alone, and the text encoder and VAE are separate
 * downloads — often in the same repository, often several gigabytes each.
 * Resolving the set up front is what lets the app say "this needs a text
 * encoder you do not have" instead of handing the engine two thirds of a model
 * and reporting whatever it says on the way down.
 */
data class ImagePipeline(
    val primary: InstalledModel,
    val arch: DiffusionArch,
    val parts: Map<ImageComponent, InstalledModel>,
) {
    /** Required components that are not installed. Empty means ready to load. */
    val missing: List<ImageComponent>
        get() = arch.required.filter { it !in parts }.sortedBy { it.ordinal }

    val isComplete: Boolean get() = missing.isEmpty()

    /** Everything that has to be resident at once, weights only. */
    val totalBytes: Long
        get() = primary.sizeBytes + parts.values.sumOf { it.sizeBytes }

    /**
     * What still has to be downloaded, for the UI to name. The encoder is
     * named precisely when the family needs a particular one - "a text
     * encoder" is no help to someone with three Qwen models already installed,
     * none of which fit.
     */
    val missingLabel: String
        get() = missing.joinToString(" and ") { part ->
            val hint = arch.encoderHint
            if (part == ImageComponent.LLM && hint != null) "text encoder ($hint)" else part.label
        }

    val partLabel: String
        get() = if (parts.isEmpty()) {
            primary.sizeLabel
        } else {
            "${parts.size + 1} files · ${formatBytes(totalBytes)}"
        }

    fun pathOf(component: ImageComponent): String = parts[component]?.filePath.orEmpty()
}

object ImagePipelineResolver {

    /**
     * Builds the pipeline for [primary] out of what is installed.
     *
     * The ranking, strongest first: shipped in the same repository; shipped in
     * a repository named for the same family (the Z-Image transformer and its
     * VAE live in different repositories, but both say "Z-Image"); anything
     * else of the right kind. A user with two VAEs almost certainly wants the
     * one that came with this checkpoint — the other produces images that are
     * subtly wrong rather than an error, which is the harder failure to spot.
     *
     * A text encoder is held to more than a ranking. Every Qwen chat model on
     * the phone is a candidate by name, and most of them are the wrong width
     * for any given transformer; one that its own header rules out is never
     * chosen, however it ranks.
     */
    fun resolve(primary: InstalledModel, installed: List<InstalledModel>): ImagePipeline {
        val arch = primary.diffusionArch
            ?: DiffusionArchDetector.fromName(primary.fileName)

        val wanted = arch.required + arch.optional
        val candidates = installed.filter { it.id != primary.id && it.component != null }

        fun sameRepo(m: InstalledModel) = m.repoId != null && m.repoId == primary.repoId
        fun sameFamily(m: InstalledModel) =
            m.repoId != null && DiffusionArchDetector.fromName(m.repoId) == arch

        val parts = wanted.mapNotNull { component ->
            val matches = candidates
                .filter { it.component == component }
                .filter { candidate ->
                    if (component != ImageComponent.LLM) return@filter true
                    // The header decides when there is one. Without one, only
                    // where the file came from is evidence enough.
                    arch.acceptsEncoder(candidate.architecture, candidate.embeddingDim)
                        ?: (sameRepo(candidate) || sameFamily(candidate))
                }
            val chosen = matches.firstOrNull(::sameRepo)
                ?: matches.firstOrNull(::sameFamily)
                ?: matches.firstOrNull()
            chosen?.let { component to it }
        }.toMap()

        return ImagePipeline(primary, arch, parts)
    }
}
