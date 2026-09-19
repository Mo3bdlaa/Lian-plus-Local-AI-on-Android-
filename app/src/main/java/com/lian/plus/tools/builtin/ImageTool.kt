package com.lian.plus.tools.builtin

import com.lian.plus.image.ImageEvent
import com.lian.plus.image.ImageGenClient
import com.lian.plus.image.ImageRequest
import com.lian.plus.image.Sampler
import com.lian.plus.tools.Tool
import com.lian.plus.tools.ToolParameter
import com.lian.plus.tools.ToolResult
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Lets the chat model generate a picture.
 *
 * The image is written to disk and the tool returns its path; the chat screen
 * recognises the path and renders the picture inline rather than showing the
 * model a base64 blob it has no way to interpret.
 */
class GenerateImageTool(
    private val client: ImageGenClient,
    private val outputDir: File,
    private val defaults: () -> ImageRequest,
) : Tool {
    override val name = "generate_image"
    override val description =
        "Creates an image from a description and shows it to the user. Describe the " +
            "subject, style and composition in the prompt."
    override val parameters = listOf(
        ToolParameter("prompt", "string", "What the image should show."),
        ToolParameter(
            "negative_prompt", "string",
            "What to keep out of the image.", required = false,
        ),
        ToolParameter(
            "size", "integer",
            "Square size in pixels: 512, 768 or 1024. Default 512.", required = false,
        ),
    )
    override val isSensitive = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val prompt = args.str("prompt")
            ?: return ToolResult("No prompt given.", isError = true)
        if (client.state.value.loadedModelId == null) {
            return ToolResult(
                "No image model is loaded. Tell the user to pick one on the Images screen.",
                isError = true,
            )
        }

        val size = (args.num("size")?.toInt() ?: defaults().width).let {
            when {
                it >= 1024 -> 1024
                it >= 768 -> 768
                else -> 512
            }
        }
        val base = defaults()
        val request = base.copy(
            prompt = prompt,
            negativePrompt = args.str("negative_prompt") ?: base.negativePrompt,
            width = size,
            height = size,
            sampler = base.sampler.takeIf { it != Sampler.EULER } ?: Sampler.EULER_A,
        )

        outputDir.mkdirs()
        val target = File(outputDir, "chat_${System.currentTimeMillis()}.png")

        val outcome = client.generate(request, target)
            .mapNotNull { it as? ImageEvent.Done ?: it as? ImageEvent.Failed }
            .firstOrNull()

        return when (outcome) {
            is ImageEvent.Done -> ToolResult(
                content = "$IMAGE_MARKER${outcome.file.absolutePath}\n" +
                    "Generated a ${outcome.width}x${outcome.height} image in " +
                    "${outcome.elapsedMillis / 1000}s. It is already shown to the user; " +
                    "just describe it briefly, do not repeat the path.",
                displaySummary = "Generated an image",
            )
            is ImageEvent.Failed -> ToolResult(outcome.message, isError = true)
            else -> ToolResult("Image generation produced no result.", isError = true)
        }
    }

    companion object {
        /** Prefix the chat screen looks for to render the result inline. */
        const val IMAGE_MARKER = "[[image:]]"
    }
}
