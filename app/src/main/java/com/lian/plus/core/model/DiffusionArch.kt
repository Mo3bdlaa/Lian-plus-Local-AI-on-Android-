package com.lian.plus.core.model

/**
 * Which diffusion family a checkpoint belongs to, and what else it needs.
 *
 * The older image models — SD 1.5, SDXL — ship as one file holding the UNet,
 * the text encoder and the VAE together, so "download the checkpoint, generate
 * an image" is the whole story. Everything since Flux splits the pipeline
 * across separate files, and the GGUF you find on the Hub is usually the
 * diffusion transformer alone. Downloading it and pointing the engine at it
 * gets you a load failure, because two thirds of the model is missing.
 *
 * This is what lets the app say so before the download rather than after.
 */
enum class DiffusionArch(
    val label: String,
    /** Components without which the pipeline cannot run at all. */
    val required: Set<ImageComponent>,
    /** Components that improve or extend it but are not needed to generate. */
    val optional: Set<ImageComponent> = emptySet(),
) {
    /** UNet, text encoder and VAE in one file: SD 1.x, SD 2.x, SDXL. */
    FULL_CHECKPOINT("Complete checkpoint", emptySet(), setOf(ImageComponent.VAE, ImageComponent.TAESD)),

    /** Qwen-Image / Qwen-Image 2.1 — a Qwen text encoder plus its own VAE. */
    QWEN_IMAGE("Qwen-Image", setOf(ImageComponent.LLM, ImageComponent.VAE)),

    /** Z-Image (Tongyi) — Qwen3 text encoder plus its own VAE. */
    Z_IMAGE("Z-Image", setOf(ImageComponent.LLM, ImageComponent.VAE)),

    FLUX("Flux", setOf(ImageComponent.CLIP_L, ImageComponent.T5XXL, ImageComponent.VAE)),

    /** SD3 runs on the two CLIP encoders; T5 improves prompt following. */
    SD3("Stable Diffusion 3", setOf(ImageComponent.CLIP_L, ImageComponent.CLIP_G, ImageComponent.VAE),
        setOf(ImageComponent.T5XXL)),

    /** Nothing in the file identified it. Treated as self-contained and tried. */
    UNKNOWN("Diffusion model", emptySet(), setOf(ImageComponent.VAE)),
    ;

    /** True when the file is only part of a pipeline the user must assemble. */
    val needsAssembly: Boolean get() = required.isNotEmpty()

    /**
     * Whether the file goes to the engine as a complete checkpoint or as the
     * diffusion transformer of a pipeline. Passing a bare transformer as a
     * checkpoint is the specific mistake that produces an unexplained load
     * failure, so the distinction is carried explicitly rather than guessed at
     * load time.
     */
    val isDiffusionOnly: Boolean get() = needsAssembly
}

/** Human names for the parts of a pipeline, used in the "what's missing" line. */
val ImageComponent.label: String
    get() = when (this) {
        ImageComponent.VAE -> "VAE"
        ImageComponent.TAESD -> "TAESD"
        ImageComponent.CLIP_L -> "CLIP-L text encoder"
        ImageComponent.CLIP_G -> "CLIP-G text encoder"
        ImageComponent.T5XXL -> "T5-XXL text encoder"
        ImageComponent.LLM -> "text encoder"
        ImageComponent.DIFFUSION -> "diffusion transformer"
        ImageComponent.UPSCALER -> "upscaler"
    }

object DiffusionArchDetector {

    /**
     * The authoritative answer, from the file itself.
     *
     * Two signals, in order of trust. Some converters write
     * `general.architecture`; the Z-Image GGUFs published by the
     * stable-diffusion.cpp author carry no metadata at all — zero key/value
     * pairs — so the tensor names are the only thing left to read, and they are
     * in fact what the engine itself matches on.
     */
    fun detect(info: GgufInspector.Info?, fileName: String): DiffusionArch {
        val arch = info?.architecture?.lowercase().orEmpty()
        when {
            arch.startsWith("qwen_image") || arch.startsWith("qwen-image") -> return DiffusionArch.QWEN_IMAGE
            arch.startsWith("z_image") || arch.startsWith("z-image") -> return DiffusionArch.Z_IMAGE
            arch.startsWith("flux") -> return DiffusionArch.FLUX
            arch.startsWith("sd3") -> return DiffusionArch.SD3
        }

        val tensors = info?.tensorNames.orEmpty()
        if (tensors.isNotEmpty()) {
            fun any(prefix: String) = tensors.any { it.startsWith(prefix) }

            // A file carrying the VAE and the conditioner is complete whatever
            // else is in it, so this is checked before the family markers.
            if (any("first_stage_model.") || any("cond_stage_model.")) {
                return DiffusionArch.FULL_CHECKPOINT
            }
            if (any("cap_embedder.") || any("context_refiner.")) return DiffusionArch.Z_IMAGE
            if (any("double_blocks.") && any("single_blocks.")) return DiffusionArch.FLUX
            if (any("joint_blocks.")) return DiffusionArch.SD3
            if (any("transformer_blocks.") && any("img_in")) return DiffusionArch.QWEN_IMAGE
            if (any("model.diffusion_model.")) return DiffusionArch.FULL_CHECKPOINT
        }

        return fromName(fileName)
    }

    /**
     * A guess from the file name, for labelling a repository before anything
     * has been downloaded. Names are a convention, so [detect] overrules this
     * the moment the file is on disk.
     */
    fun fromName(fileName: String): DiffusionArch {
        val n = fileName.lowercase()
        return when {
            n.contains("qwen-image") || n.contains("qwen_image") -> DiffusionArch.QWEN_IMAGE
            n.contains("z-image") || n.contains("z_image") -> DiffusionArch.Z_IMAGE
            n.contains("flux") -> DiffusionArch.FLUX
            n.contains("sd3") || n.contains("stable-diffusion-3") -> DiffusionArch.SD3
            else -> DiffusionArch.UNKNOWN
        }
    }

    /**
     * Which part of a pipeline a companion file is, from its name and the
     * folder it sits in. Repositories are consistent about this in practice —
     * `vae/`, `text_encoders/` — and the name carries the rest.
     */
    fun componentOf(path: String): ImageComponent? {
        val p = path.lowercase()
        val name = p.substringAfterLast('/')

        // The unambiguous markers first, because the folder and these words
        // are decisive whatever else the name contains.
        when {
            name.contains("taesd") -> return ImageComponent.TAESD
            p.startsWith("vae/") || name.contains("vae") || name.contains("_ae.") ||
                name == "ae.safetensors" || name == "ae.gguf" -> return ImageComponent.VAE
            name.contains("clip_l") || name.contains("clip-l") -> return ImageComponent.CLIP_L
            name.contains("clip_g") || name.contains("clip-g") -> return ImageComponent.CLIP_G
            name.contains("t5") || name.contains("umt5") -> return ImageComponent.T5XXL
            p.startsWith("text_encoder") || name.contains("text_encoder") ->
                return ImageComponent.LLM
            name.contains("upscal") || name.contains("esrgan") -> return ImageComponent.UPSCALER
        }

        // A checkpoint that names its own family is the transformer, not a
        // companion — "qwen-image-2.1-UC-Q4_0.gguf" contains "qwen" and is the
        // very thing the text encoder is loaded *for*.
        if (fromName(name) != DiffusionArch.UNKNOWN) return null

        // Otherwise a Qwen language model can serve as the text encoder for
        // Qwen-Image and Z-Image, because that is literally what they use: the
        // Z-Image example loads a stock Qwen3-4B-Instruct GGUF. A model like
        // that stays a chat model as well; being usable as an encoder is an
        // extra role, not a reclassification.
        return if (name.contains("qwen") || name.contains("llm")) ImageComponent.LLM else null
    }
}
