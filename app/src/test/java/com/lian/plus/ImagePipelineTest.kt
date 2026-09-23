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
    ) = InstalledModel(
        id = id,
        displayName = id,
        kind = kind,
        filePath = "/models/$name",
        sizeBytes = sizeBytes,
        repoId = repo,
        fileName = name,
        quant = Quant.UNKNOWN,
        architecture = null,
        parameterCount = null,
        contextTrained = null,
        embeddingDim = null,
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
