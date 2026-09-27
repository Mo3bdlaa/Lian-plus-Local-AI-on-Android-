package com.lian.plus

import com.lian.plus.core.model.DiffusionArch
import com.lian.plus.core.model.DiffusionArchDetector
import com.lian.plus.core.model.GgufInspector
import com.lian.plus.core.model.ImageComponent
import com.lian.plus.core.model.ImagePipelineResolver
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.core.model.Quant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GIB = 1024L * 1024 * 1024

class ImagePipelineTest {

    private fun info(arch: String? = null, tensors: List<String> = emptyList()) =
        GgufInspector.Info(
            version = 3,
            tensorCount = tensors.size.toLong(),
            architecture = arch,
            name = null,
            quantLabel = null,
            contextLength = null,
            embeddingLength = null,
            blockCount = null,
            headCount = null,
            headCountKv = null,
            ropeFreqBase = null,
            chatTemplate = null,
            vocabSize = null,
            splitCount = null,
            metadata = emptyMap(),
            tensorNames = tensors,
        )

    private fun model(
        id: String,
        name: String,
        kind: ModelKind,
        sizeBytes: Long = GIB,
        repo: String? = "repo/one",
        component: ImageComponent? = null,
        arch: DiffusionArch? = null,
        llmArch: String? = null,
        width: Int? = null,
    ) = InstalledModel(
        id = id,
        displayName = id,
        kind = kind,
        filePath = "/models/$name",
        sizeBytes = sizeBytes,
        repoId = repo,
        fileName = name,
        quant = Quant.UNKNOWN,
        architecture = llmArch,
        parameterCount = null,
        contextTrained = null,
        embeddingDim = width,
        chatTemplate = null,
        component = component,
        diffusionArch = arch,
    )

    // ---- detection -------------------------------------------------------

    @Test
    fun `Qwen-Image is recognised from its architecture key`() {
        // What the published GGUF actually carries: three metadata keys, of
        // which the architecture is the only useful one.
        val arch = DiffusionArchDetector.detect(
            info(arch = "qwen_image21"),
            "qwen-image-2.1-UC-Q4_0.gguf",
        )
        assertEquals(DiffusionArch.QWEN_IMAGE, arch)
        assertEquals(setOf(ImageComponent.LLM, ImageComponent.VAE), arch.required)
    }

    @Test
    fun `Z-Image is recognised from tensor names alone`() {
        // These files carry zero key/value pairs, so the names are all there is.
        val arch = DiffusionArchDetector.detect(
            info(tensors = listOf("cap_embedder.0.weight", "context_refiner.0.attention.out.weight")),
            "z_image_turbo-Q3_K.gguf",
        )
        assertEquals(DiffusionArch.Z_IMAGE, arch)
    }

    @Test
    fun `a complete checkpoint is not mistaken for a pipeline`() {
        val arch = DiffusionArchDetector.detect(
            info(tensors = listOf("first_stage_model.encoder.conv_in.weight", "model.diffusion_model.x")),
            "sd-turbo.q8_0.gguf",
        )
        assertEquals(DiffusionArch.FULL_CHECKPOINT, arch)
        assertFalse(arch.needsAssembly)
        assertFalse(arch.isDiffusionOnly)
    }

    @Test
    fun `an unreadable file falls back to the name`() {
        assertEquals(DiffusionArch.FLUX, DiffusionArchDetector.detect(null, "flux1-dev-Q4_K.gguf"))
        assertEquals(DiffusionArch.UNKNOWN, DiffusionArchDetector.detect(null, "something.gguf"))
    }

    @Test
    fun `companion files are identified by folder as well as name`() {
        assertEquals(
            ImageComponent.VAE,
            DiffusionArchDetector.componentOf("vae/qwen_image_2.1_vae_bf16.safetensors"),
        )
        assertEquals(
            ImageComponent.LLM,
            DiffusionArchDetector.componentOf("text_encoders/qwen3vl_8b_int8_convrot.safetensors"),
        )
        assertEquals(ImageComponent.T5XXL, DiffusionArchDetector.componentOf("t5xxl_fp8.safetensors"))
        assertEquals(ImageComponent.CLIP_L, DiffusionArchDetector.componentOf("clip_l.safetensors"))
        // The Qwen-Image transformer contains "qwen" and is emphatically not
        // the text encoder: misreading it puts the checkpoint in the encoder
        // slot and leaves the pipeline with no transformer at all.
        assertNull(DiffusionArchDetector.componentOf("qwen-image-2.1-UC-Q4_0.gguf"))
        assertNull(DiffusionArchDetector.componentOf("z_image_turbo-Q3_K.gguf"))
    }

    @Test
    fun `a stock Qwen chat model can serve as an image text encoder`() {
        // Not a guess: the Z-Image example loads exactly this file as --llm.
        assertEquals(
            ImageComponent.LLM,
            DiffusionArchDetector.componentOf("Qwen3-4B-Instruct-2507-Q4_K_M.gguf"),
        )
    }

    // ---- resolution ------------------------------------------------------

    @Test
    fun `a Qwen-Image checkpoint on its own reports what is missing`() {
        val primary = model("qwen", "qwen-image.gguf", ModelKind.IMAGE, 4 * GIB,
            arch = DiffusionArch.QWEN_IMAGE)
        val pipeline = ImagePipelineResolver.resolve(primary, listOf(primary))

        assertFalse(pipeline.isComplete)
        assertEquals(listOf(ImageComponent.VAE, ImageComponent.LLM), pipeline.missing)
        assertTrue(pipeline.missingLabel.contains("VAE"))
        assertTrue(pipeline.missingLabel.contains("text encoder"))
    }

    @Test
    fun `the pipeline is complete once both companions are installed`() {
        val primary = model("qwen", "qwen-image.gguf", ModelKind.IMAGE, 4 * GIB,
            arch = DiffusionArch.QWEN_IMAGE)
        val vae = model("vae", "vae.safetensors", ModelKind.IMAGE_COMPONENT, GIB / 2,
            component = ImageComponent.VAE)
        val llm = model("llm", "qwen3vl.safetensors", ModelKind.IMAGE_COMPONENT, 5 * GIB,
            component = ImageComponent.LLM)

        val pipeline = ImagePipelineResolver.resolve(primary, listOf(primary, vae, llm))

        assertTrue(pipeline.isComplete)
        // The size question is about the set, not the transformer alone.
        assertEquals(4 * GIB + GIB / 2 + 5 * GIB, pipeline.totalBytes)
        assertEquals("/models/qwen3vl.safetensors", pipeline.pathOf(ImageComponent.LLM))
    }

    @Test
    fun `a companion from the same repository is preferred`() {
        val primary = model("qwen", "qwen-image.gguf", ModelKind.IMAGE, 4 * GIB,
            repo = "mine/qwen", arch = DiffusionArch.QWEN_IMAGE)
        val strangerVae = model("vae-other", "vae.safetensors", ModelKind.IMAGE_COMPONENT,
            repo = "someone/else", component = ImageComponent.VAE)
        val ownVae = model("vae-own", "own_vae.safetensors", ModelKind.IMAGE_COMPONENT,
            repo = "mine/qwen", component = ImageComponent.VAE)
        val llm = model("llm", "qwen3vl.safetensors", ModelKind.IMAGE_COMPONENT,
            repo = "mine/qwen", component = ImageComponent.LLM)

        val pipeline = ImagePipelineResolver.resolve(
            primary,
            listOf(primary, strangerVae, ownVae, llm),
        )
        assertEquals("/models/own_vae.safetensors", pipeline.pathOf(ImageComponent.VAE))
    }

    // ---- encoder compatibility ------------------------------------------

    private val zImage = model("zi", "z_image_turbo-Q3_K.gguf", ModelKind.IMAGE, 3 * GIB,
        repo = "leejet/Z-Image-Turbo-GGUF", arch = DiffusionArch.Z_IMAGE)
    private val zVae = model("zvae", "vae_diffusion_pytorch_model.safetensors",
        ModelKind.IMAGE_COMPONENT, GIB / 6, repo = "Tongyi-MAI/Z-Image-Turbo",
        component = ImageComponent.VAE)

    // What a phone with the curated chat picks installed actually holds.
    private val qwen25small = model("q15", "qwen2.5-1.5b-instruct-q4_k_m.gguf", ModelKind.TEXT,
        GIB, repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF", component = ImageComponent.LLM,
        llmArch = "qwen2", width = 1536)
    private val qwen3_4b = model("q3", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", ModelKind.TEXT,
        5 * GIB / 2, repo = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
        component = ImageComponent.LLM, llmArch = "qwen3", width = 2560)

    @Test
    fun `Z-Image never takes a Qwen chat model of the wrong width`() {
        // The 1.5B model sorts first and shares no repository with either
        // side, so a resolver that only ranked would pick it - and Z-Image's
        // cap_embedder reads 2560-wide features, not 1536.
        val pipeline = ImagePipelineResolver.resolve(
            zImage,
            listOf(zImage, qwen25small, zVae, qwen3_4b),
        )
        assertTrue(pipeline.isComplete)
        assertEquals(qwen3_4b.filePath, pipeline.pathOf(ImageComponent.LLM))
    }

    @Test
    fun `with only incompatible encoders installed the pipeline says what it needs`() {
        val pipeline = ImagePipelineResolver.resolve(zImage, listOf(zImage, qwen25small, zVae))
        assertFalse(pipeline.isComplete)
        assertEquals(listOf(ImageComponent.LLM), pipeline.missing)
        // Named precisely, because "a text encoder" is no help to someone who
        // already has one that does not fit.
        assertTrue(pipeline.missingLabel, pipeline.missingLabel.contains("Qwen3 4B"))
    }

    @Test
    fun `a VAE from a repository named for the family is found across repositories`() {
        // The curated Z-Image transformer and its VAE come from different
        // repositories; the family in the repository name is what ties them.
        val sdxlVae = model("xl", "sdxl_vae.safetensors", ModelKind.IMAGE_COMPONENT,
            GIB / 3, repo = "madebyollin/sdxl-vae-fp16-fix", component = ImageComponent.VAE)
        val pipeline = ImagePipelineResolver.resolve(
            zImage,
            listOf(zImage, sdxlVae, zVae, qwen3_4b),
        )
        assertEquals(zVae.filePath, pipeline.pathOf(ImageComponent.VAE))
    }

    @Test
    fun `a headerless encoder is accepted only from where it belongs`() {
        val qwenImage = model("qi", "qwen_image_2.1-Q4_K.gguf", ModelKind.IMAGE, 4 * GIB,
            repo = "abenzerps/Qwen-Image-2.1-Uncensored-GGUF", arch = DiffusionArch.QWEN_IMAGE)
        val sameRepoEncoder = model("te", "text_encoders_qwen3vl_8b.safetensors",
            ModelKind.IMAGE_COMPONENT, 9 * GIB, repo = "abenzerps/Qwen-Image-2.1-Uncensored-GGUF",
            component = ImageComponent.LLM)
        val strayEncoder = model("stray", "qwen_text_encoder.safetensors",
            ModelKind.IMAGE_COMPONENT, 9 * GIB, repo = "someone/unrelated",
            component = ImageComponent.LLM)

        assertEquals(
            sameRepoEncoder.filePath,
            ImagePipelineResolver.resolve(qwenImage, listOf(qwenImage, strayEncoder, sameRepoEncoder))
                .pathOf(ImageComponent.LLM),
        )
        // With nothing but the stray one, it is not guessed at.
        assertEquals(
            "",
            ImagePipelineResolver.resolve(qwenImage, listOf(qwenImage, strayEncoder))
                .pathOf(ImageComponent.LLM),
        )
    }

    @Test
    fun `Qwen-Image asks for a vision-language encoder`() {
        assertEquals(true, DiffusionArch.QWEN_IMAGE.acceptsEncoder("qwen2vl", 3584))
        assertEquals(true, DiffusionArch.QWEN_IMAGE.acceptsEncoder("qwen3vl", 4096))
        assertEquals(false, DiffusionArch.QWEN_IMAGE.acceptsEncoder("qwen3", 2560))
        assertEquals(null, DiffusionArch.QWEN_IMAGE.acceptsEncoder(null, null))
    }

    @Test
    fun `a full checkpoint needs nothing and is loaded as a checkpoint`() {
        val primary = model("sd", "sd-turbo.gguf", ModelKind.IMAGE, 2 * GIB,
            arch = DiffusionArch.FULL_CHECKPOINT)
        val pipeline = ImagePipelineResolver.resolve(primary, listOf(primary))

        assertTrue(pipeline.isComplete)
        assertFalse(pipeline.arch.isDiffusionOnly)
        assertEquals(2 * GIB, pipeline.totalBytes)
    }

    @Test
    fun `an optional component is used when present but never blocks`() {
        val primary = model("sd", "sd-turbo.gguf", ModelKind.IMAGE, 2 * GIB,
            arch = DiffusionArch.FULL_CHECKPOINT)
        val vae = model("vae", "vae.safetensors", ModelKind.IMAGE_COMPONENT, GIB / 4,
            component = ImageComponent.VAE)

        val alone = ImagePipelineResolver.resolve(primary, listOf(primary))
        assertTrue(alone.isComplete)

        val withVae = ImagePipelineResolver.resolve(primary, listOf(primary, vae))
        assertTrue(withVae.isComplete)
        assertEquals("/models/vae.safetensors", withVae.pathOf(ImageComponent.VAE))
    }
}
