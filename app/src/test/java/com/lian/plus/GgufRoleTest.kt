package com.lian.plus

import com.lian.plus.core.model.GgufRole
import com.lian.plus.core.model.GgufRoleDetector
import com.lian.plus.image.AspectRatio
import com.lian.plus.image.NativeResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufRoleTest {

    @Test
    fun `a vision projector is not a model`() {
        // The exact file that produced "llama.cpp could not load this file".
        val role = GgufRoleDetector.roleFromName("mmproj-F32.gguf")
        assertEquals(GgufRole.VISION_PROJECTOR, role)
        assertFalse(role.isLoadable)
        assertTrue(role.explanation.isNotBlank())
    }

    @Test
    fun `draft and adapter files are recognised`() {
        assertEquals(GgufRole.DRAFT, GgufRoleDetector.roleFromName("mtp-RVN.gguf"))
        assertEquals(
            GgufRole.ADAPTER,
            GgufRoleDetector.roleFromName("some-lora-adapter.gguf"),
        )
    }

    @Test
    fun `an ordinary quantised model is loadable`() {
        val role = GgufRoleDetector.roleFromName("Qwen2.5-3B-Instruct-Q4_K_M.gguf")
        assertEquals(GgufRole.STANDALONE, role)
        assertTrue(role.isLoadable)
    }

    @Test
    fun `shards are detected and grouped by base name`() {
        val name = "big-model-Q4_K_M-00003-of-00009.gguf"
        assertEquals(GgufRole.SHARD, GgufRoleDetector.roleFromName(name))
        assertEquals("big-model-Q4_K_M", GgufRoleDetector.splitBaseName(name))
        assertEquals(9, GgufRoleDetector.shardTotal(name))
    }

    @Test
    fun `a non-split name has no shard information`() {
        assertNull(GgufRoleDetector.splitBaseName("model-Q4_K_M.gguf"))
        assertNull(GgufRoleDetector.shardTotal("model-Q4_K_M.gguf"))
    }

    @Test
    fun `diffusion pipeline parts are not standalone`() {
        assertEquals(
            GgufRole.DIFFUSION_COMPONENT,
            GgufRoleDetector.roleFromName("t5xxl_fp16.gguf"),
        )
        assertFalse(GgufRoleDetector.roleFromName("vae.gguf").isLoadable)
    }
}

class ImageSizingTest {

    @Test
    fun `sd turbo is treated as a 512 model despite being sd 2 x`() {
        assertEquals(512, NativeResolution.forVersion("SD 2.x"))
    }

    @Test
    fun `sdxl keeps its larger native size`() {
        assertEquals(1024, NativeResolution.forVersion("SDXL"))
    }

    @Test
    fun `the cap is the lower of model and device`() {
        // A 12 GB phone would allow 1024, but the checkpoint would not benefit.
        assertEquals(512, NativeResolution.cap("SD 2.x", deviceMax = 1024))
        // And a small phone still limits a large checkpoint.
        assertEquals(512, NativeResolution.cap("SDXL", deviceMax = 512))
    }

    @Test
    fun `aspect ratios snap both sides to multiples of 64`() {
        AspectRatio.entries.forEach { ratio ->
            val (w, h) = ratio.dimensions(512)
            assertEquals("$ratio width", 0, w % 64)
            assertEquals("$ratio height", 0, h % 64)
        }
    }

    @Test
    fun `the long edge follows the requested ratio`() {
        val (w, h) = AspectRatio.LANDSCAPE.dimensions(512)
        assertTrue("landscape should be wider than tall", w > h)
        val (pw, ph) = AspectRatio.PORTRAIT.dimensions(512)
        assertTrue("portrait should be taller than wide", ph > pw)
        val (sw, sh) = AspectRatio.SQUARE.dimensions(512)
        assertEquals(sw, sh)
    }
}
