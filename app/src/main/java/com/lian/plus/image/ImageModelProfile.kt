package com.lian.plus.image

/**
 * The settings a checkpoint actually wants.
 *
 * These are properties of the model, not of the phone: a distilled turbo model
 * is trained to converge in a handful of steps at a guidance scale of 1, and
 * running it at the twenty steps and scale 7 that SD 1.5 needs wastes minutes
 * and produces a worse picture. Getting them wrong is not a subtle loss — a
 * turbo model at cfg 7 comes out scorched, and a full model at four steps comes
 * out as noise.
 *
 * Every figure below comes from the reference invocation in
 * stable-diffusion.cpp's own docs for that family, not from taste.
 */
data class ImageModelProfile(
    /** The resolution the model was trained at. */
    val nativeSize: Int,
    val steps: Int,
    /**
     * Classifier-free guidance. 1.0 means no CFG at all, which halves the work
     * per step — distilled models are trained for exactly that.
     */
    val cfgScale: Float,
    val sampler: Sampler,
    val note: String,
)

object ImageModelProfiles {

    private val DEFAULT = ImageModelProfile(
        nativeSize = 1024,
        steps = 20,
        cfgScale = 7.0f,
        sampler = Sampler.EULER_A,
        note = "Standard diffusion settings.",
    )

    /**
     * The profile for a loaded model.
     *
     * [version] is what the engine reports, which names the family. It is not
     * always enough: "Z-Image" covers both the base model (20 steps, cfg 5)
     * and the Turbo distill (8 steps, cfg 1), and only the file name tells
     * them apart — so the name is consulted for the distinctions the family
     * does not carry.
     */
    fun forModel(version: String?, fileName: String? = null): ImageModelProfile {
        val v = version?.lowercase().orEmpty()
        val n = fileName?.lowercase().orEmpty()
        val turbo = n.contains("turbo") || n.contains("lightning") ||
            n.contains("lcm") || n.contains("hyper")

        return when {
            v.contains("z-image") || v.contains("z image") -> if (turbo) {
                ImageModelProfile(
                    nativeSize = 1024,
                    steps = 8,
                    cfgScale = 1.0f,
                    sampler = Sampler.EULER,
                    note = "Z-Image Turbo converges in 8 steps with no guidance.",
                )
            } else {
                ImageModelProfile(
                    nativeSize = 1024,
                    steps = 20,
                    cfgScale = 5.0f,
                    sampler = Sampler.EULER,
                    note = "Z-Image, the undistilled model: 20 steps at scale 5.",
                )
            }

            v.contains("qwen image") -> ImageModelProfile(
                nativeSize = 1024,
                steps = 20,
                cfgScale = 2.5f,
                sampler = Sampler.EULER,
                note = "Qwen-Image renders text well and wants a low guidance scale.",
            )

            v.contains("flux") -> ImageModelProfile(
                nativeSize = 1024,
                steps = if (n.contains("schnell")) 4 else 20,
                // Flux takes its guidance through the distilled-guidance input,
                // which the engine already defaults correctly; classifier-free
                // guidance on top of it only doubles the work.
                cfgScale = 1.0f,
                sampler = Sampler.EULER,
                note = "Flux uses distilled guidance, so CFG stays at 1.",
            )

            v.contains("sd3") -> ImageModelProfile(
                nativeSize = 1024,
                steps = 20,
                cfgScale = 4.5f,
                sampler = Sampler.EULER,
                note = "Stable Diffusion 3 at its trained resolution.",
            )

            v.contains("sdxs") -> ImageModelProfile(
                nativeSize = 512,
                steps = 1,
                cfgScale = 1.0f,
                sampler = Sampler.EULER,
                note = "SDXS is a one-step model.",
            )

            v.contains("sdxl") -> if (turbo) {
                ImageModelProfile(1024, 4, 1.0f, Sampler.EULER_A, "SDXL Turbo: 1-4 steps, no guidance.")
            } else {
                ImageModelProfile(1024, 25, 7.0f, Sampler.DPMPP2M, "SDXL at 1024px.")
            }

            v.contains("sd 2") || v.contains("sd2") -> if (turbo) {
                // SD-Turbo is built on SD 2.1 and reports as such, but it is a
                // 512px one-to-four-step model and treating it like SD 2.1
                // asks it for twenty steps it does not need.
                ImageModelProfile(512, 4, 1.0f, Sampler.EULER_A, "SD-Turbo: 1-4 steps, no guidance.")
            } else {
                ImageModelProfile(512, 20, 7.0f, Sampler.EULER_A, "SD 2.x at 512px.")
            }

            v.contains("sd 1") || v.contains("sd1") || v.contains("pix2pix") ->
                ImageModelProfile(512, 20, 7.0f, Sampler.EULER_A, "SD 1.x at 512px.")

            // Everything newer than SDXL is a 1024px model. Defaulting the
            // unknown case to 512, as this used to, quietly halved the output
            // of every family added since.
            else -> DEFAULT
        }
    }
}

/**
 * The resolution a checkpoint was actually trained at.
 *
 * Diffusion models degrade badly above their training resolution and the cost
 * grows with the square of the side, so a 768px request against a 512-native
 * checkpoint is slower *and* worse. SD-Turbo is 512 despite being built on
 * SD 2.1, which is why the family name alone is not enough.
 */
object NativeResolution {

    fun forVersion(version: String?, fileName: String? = null): Int =
        ImageModelProfiles.forModel(version, fileName).nativeSize

    /** The largest side worth offering: the lower of the model's and the device's. */
    fun cap(version: String?, deviceMax: Int, fileName: String? = null): Int =
        minOf(forVersion(version, fileName), deviceMax)
}
