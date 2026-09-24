package com.lian.plus

import com.lian.plus.image.ImageModelProfiles
import com.lian.plus.image.NativeResolution
import com.lian.plus.image.Sampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The figures asserted here are the ones in stable-diffusion.cpp's own docs for
 * each family. They are model properties, so getting them wrong is not a matter
 * of taste: a turbo model at twenty steps and scale 7 comes out scorched and
 * takes five times as long.
 */
class ImageModelProfileTest {

    @Test
    fun `Z-Image Turbo takes its distilled settings`() {
        val p = ImageModelProfiles.forModel("Z-Image", "z_image_turbo-Q3_K.gguf")
        assertEquals(8, p.steps)
        assertEquals(1.0f, p.cfgScale, 0.001f)
        assertEquals(1024, p.nativeSize)
        assertEquals(Sampler.EULER, p.sampler)
    }

    @Test
    fun `the undistilled Z-Image is not treated as the turbo one`() {
        // Both report the same family string, so only the file name separates
        // them — and the difference is 8 steps at scale 1 versus 20 at scale 5.
        val p = ImageModelProfiles.forModel("Z-Image", "z_image-Q4_0.gguf")
        assertEquals(20, p.steps)
        assertEquals(5.0f, p.cfgScale, 0.001f)
    }

    @Test
    fun `Qwen-Image wants a low guidance scale`() {
        val p = ImageModelProfiles.forModel("Qwen Image", "qwen_image_2.1-Q4_K.gguf")
        assertEquals(2.5f, p.cfgScale, 0.001f)
        assertEquals(1024, p.nativeSize)
    }

    @Test
    fun `Flux leaves classifier-free guidance off`() {
        // Flux is guided through its distilled-guidance input, which the engine
        // already defaults; CFG on top only doubles the work per step.
        assertEquals(1.0f, ImageModelProfiles.forModel("Flux", "flux1-dev.gguf").cfgScale, 0.001f)
        assertEquals(4, ImageModelProfiles.forModel("Flux", "flux1-schnell-Q4.gguf").steps)
    }

    @Test
    fun `SD-Turbo is 512px and four steps despite reporting as SD 2`() {
        val p = ImageModelProfiles.forModel("SD 2.x", "sd_turbo.q8_0.gguf")
        assertEquals(512, p.nativeSize)
        assertEquals(4, p.steps)
        assertEquals(1.0f, p.cfgScale, 0.001f)
    }

    @Test
    fun `plain SD 2 still gets the full step count`() {
        val p = ImageModelProfiles.forModel("SD 2.x", "v2-1_768-ema.gguf")
        assertEquals(20, p.steps)
        assertEquals(7.0f, p.cfgScale, 0.001f)
    }

    @Test
    fun `SD 1_5 stays at 512`() {
        assertEquals(512, ImageModelProfiles.forModel("SD 1.x", "v1-5.gguf").nativeSize)
    }

    @Test
    fun `an unrecognised family defaults to 1024, not 512`() {
        // Every family added since SDXL is a 1024px model. Defaulting the
        // unknown case to 512 quietly halved the output of all of them.
        assertEquals(1024, ImageModelProfiles.forModel("Krea2", "krea2.gguf").nativeSize)
        assertEquals(1024, NativeResolution.forVersion("Ideogram 4"))
        assertEquals(1024, NativeResolution.forVersion(null))
    }

    @Test
    fun `the device ceiling still wins`() {
        // A 1024px model on a phone that can only manage 512 is capped, not
        // refused — the model's native size is an upper bound, not a demand.
        assertEquals(512, NativeResolution.cap("Z-Image", deviceMax = 512))
        assertEquals(1024, NativeResolution.cap("Z-Image", deviceMax = 1024))
    }

    @Test
    fun `every profile is sane`() {
        listOf("Z-Image", "Qwen Image", "Flux", "SD3.x", "SDXL", "SD 2.x", "SD 1.x", "SDXS (512-DS)")
            .forEach { version ->
                val p = ImageModelProfiles.forModel(version)
                assertTrue("$version steps", p.steps in 1..50)
                assertTrue("$version cfg", p.cfgScale in 1.0f..12.0f)
                assertTrue("$version size", p.nativeSize in 256..2048)
                assertTrue("$version note", p.note.isNotBlank())
            }
    }
}
