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

    /** What still has to be downloaded, for the UI to name. */
    val missingLabel: String
        get() = missing.joinToString(" and ") { it.label }

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
     * Components from the same repository win: a user who has two VAEs almost
     * certainly wants the one that shipped beside this checkpoint, and picking
     * the other produces images that are subtly wrong rather than an error.
     */
    fun resolve(primary: InstalledModel, installed: List<InstalledModel>): ImagePipeline {
        val arch = primary.diffusionArch
            ?: DiffusionArchDetector.fromName(primary.fileName)

        val wanted = arch.required + arch.optional
        val candidates = installed.filter { it.id != primary.id && it.component != null }

        val parts = wanted.mapNotNull { component ->
            val matches = candidates.filter { it.component == component }
            val chosen = matches.firstOrNull { it.repoId != null && it.repoId == primary.repoId }
                ?: matches.firstOrNull()
            chosen?.let { component to it }
        }.toMap()

        return ImagePipeline(primary, arch, parts)
    }
}
