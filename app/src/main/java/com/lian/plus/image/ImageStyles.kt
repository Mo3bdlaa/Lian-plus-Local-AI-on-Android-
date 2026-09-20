package com.lian.plus.image

/**
 * Prompt presets.
 *
 * These are plain text appended to what the user typed — a "style" in a
 * diffusion UI is nothing more than a set of words the model was trained to
 * associate with a look, so keeping them visible as text is both honest and
 * editable.
 */
enum class ImageStyle(
    val label: String,
    val promptSuffix: String,
    val negativeSuffix: String,
) {
    NONE("None", "", ""),
    ANIME(
        "Anime",
        "anime style, cel shaded, vibrant colours, clean line art",
        "photorealistic, 3d render",
    ),
    CINEMATIC(
        "Cinematic",
        "cinematic lighting, dramatic composition, film still, shallow depth of field",
        "flat lighting, snapshot",
    ),
    PHOTO(
        "Photographic",
        "photorealistic, 35mm photograph, natural lighting, highly detailed",
        "illustration, cartoon, painting",
    );

    fun apply(prompt: String): String =
        if (promptSuffix.isEmpty()) prompt else "$prompt, $promptSuffix"

    fun applyNegative(negative: String): String = when {
        negativeSuffix.isEmpty() -> negative
        negative.isBlank() -> negativeSuffix
        else -> "$negative, $negativeSuffix"
    }
}

/**
 * Output shapes.
 *
 * Diffusion models work in latent space at a factor of 8, and the UNet is
 * happiest when both sides are multiples of 64 — so the dimensions are snapped
 * rather than taken literally from the ratio.
 */
enum class AspectRatio(val label: String, val wRatio: Int, val hRatio: Int) {
    SQUARE("1:1", 1, 1),
    LANDSCAPE("16:9", 16, 9),
    PORTRAIT("9:16", 9, 16),
    CLASSIC("4:3", 4, 3);

    /** Dimensions whose long edge is [baseSize], both snapped to 64. */
    fun dimensions(baseSize: Int): Pair<Int, Int> {
        val longEdge = baseSize
        val shortEdge = (longEdge.toLong() * minOf(wRatio, hRatio) / maxOf(wRatio, hRatio)).toInt()
        val (w, h) = if (wRatio >= hRatio) longEdge to shortEdge else shortEdge to longEdge
        return snap(w) to snap(h)
    }

    private fun snap(value: Int): Int = ((value + 32) / 64 * 64).coerceAtLeast(256)
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

    fun forVersion(version: String?): Int {
        val v = version?.lowercase().orEmpty()
        return when {
            v.contains("sdxl") || v.contains("xl") -> 1024
            v.contains("sd3") || v.contains("flux") -> 1024
            v.contains("sd 2") || v.contains("sd2") -> 512
            v.contains("sd 1") || v.contains("sd1") -> 512
            else -> 512
        }
    }

    /** The largest side worth offering: the lower of the model's and the device's. */
    fun cap(version: String?, deviceMax: Int): Int =
        minOf(forVersion(version), deviceMax)
}
