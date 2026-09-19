package com.lian.plus.hub

import com.lian.plus.core.model.Quant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class HfModelSummary(
    val id: String,
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
    val createdAt: String? = null,
    val private: Boolean = false,
    val gated: Boolean = false,
) {
    val owner: String get() = id.substringBefore('/', "")
    val name: String get() = id.substringAfter('/')
}

@Serializable
private data class HfLfs(val size: Long = 0)

@Serializable
private data class HfTreeEntry(
    val type: String = "file",
    val path: String = "",
    val size: Long = 0,
    val lfs: HfLfs? = null,
) {
    /** For LFS-backed files `size` is the pointer size, not the real one. */
    val realSize: Long get() = lfs?.size ?: size
}

/** One downloadable file inside a repository. */
data class HfFile(
    val repoId: String,
    val path: String,
    val sizeBytes: Long,
    val quant: Quant,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val downloadUrl: String get() = "https://huggingface.co/$repoId/resolve/main/$path"

    /** Multi-part GGUF files (`...-00001-of-00003.gguf`) need every shard. */
    val isSplitShard: Boolean get() = SPLIT_REGEX.containsMatchIn(fileName)
    val isFirstShard: Boolean
        get() = SPLIT_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull() == 1

    companion object {
        private val SPLIT_REGEX = Regex("""-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)
    }
}

data class HfRepoDetail(
    val summary: HfModelSummary,
    val ggufFiles: List<HfFile>,
    val otherFiles: List<HfFile>,
)

/**
 * Read-only client for the Hugging Face Hub.
 *
 * Only public endpoints are used. A token is optional and, when present, is
 * sent solely as an Authorization header to huggingface.co so gated repos the
 * user already has access to become visible.
 */
class HuggingFaceApi(
    private val client: OkHttpClient = defaultClient(),
    private val tokenProvider: () -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Searches repositories that publish GGUF weights. */
    suspend fun searchModels(
        query: String,
        limit: Int = 30,
        sort: SortOrder = SortOrder.DOWNLOADS,
    ): List<HfModelSummary> = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$BASE/api/models?filter=gguf&limit=$limit")
            append("&sort=${sort.param}&direction=-1")
            if (query.isNotBlank()) append("&search=").append(encode(query))
        }
        json.decodeFromString<List<HfModelSummary>>(get(url))
    }

    /** Searches repositories holding diffusion checkpoints usable by the image engine. */
    suspend fun searchImageModels(query: String, limit: Int = 30): List<HfModelSummary> =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("$BASE/api/models?limit=$limit&sort=downloads&direction=-1")
                append("&filter=gguf")
                append("&search=").append(encode(query.ifBlank { "stable diffusion" }))
            }
            json.decodeFromString<List<HfModelSummary>>(get(url))
        }

    /** Lists the files in a repository, with real (post-LFS) sizes. */
    suspend fun repoFiles(repoId: String): HfRepoDetail = withContext(Dispatchers.IO) {
        val summary = json.decodeFromString<HfModelSummary>(get("$BASE/api/models/$repoId"))
        val entries = json.decodeFromString<List<HfTreeEntry>>(
            get("$BASE/api/models/$repoId/tree/main?recursive=true"),
        )
        val files = entries
            .filter { it.type == "file" }
            .map { HfFile(repoId, it.path, it.realSize, Quant.fromFileName(it.path)) }

        HfRepoDetail(
            summary = summary,
            ggufFiles = files
                .filter { it.path.endsWith(".gguf", ignoreCase = true) }
                // A split model is represented by its first shard only; the
                // downloader pulls the rest.
                .filter { !it.isSplitShard || it.isFirstShard }
                .sortedBy { it.sizeBytes },
            otherFiles = files.filterNot { it.path.endsWith(".gguf", ignoreCase = true) },
        )
    }

    /** Fetches a repository's README, used for the model card view. */
    suspend fun readme(repoId: String): String? = withContext(Dispatchers.IO) {
        runCatching { get("$BASE/$repoId/raw/main/README.md") }.getOrNull()
    }

    /** Content-Length for a file, following redirects. Null when unavailable. */
    suspend fun contentLength(url: String): Long? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).head().apply {
            tokenProvider()?.let { header("Authorization", "Bearer $it") }
        }.build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                // HF reports the real size of an LFS object in this header even
                // when the body would be a pointer file.
                resp.header("x-linked-size")?.toLongOrNull()
                    ?: resp.header("content-length")?.toLongOrNull()
            }
        }.getOrNull()
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).apply {
            header("User-Agent", USER_AGENT)
            // The token never leaves huggingface.co: every URL here is built
            // from BASE, never from a server-supplied redirect target.
            tokenProvider()?.let { header("Authorization", "Bearer $it") }
        }.build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} from ${resp.request.url.encodedPath}: ${body.take(200)}")
            }
            return body
        }
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    enum class SortOrder(val param: String) {
        DOWNLOADS("downloads"),
        LIKES("likes"),
        RECENT("lastModified"),
    }

    companion object {
        const val BASE = "https://huggingface.co"
        const val USER_AGENT = "LianPlus/0.1 (Android; local inference)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Weight files are huge; a write/read stall should not kill an
            // otherwise healthy multi-gigabyte transfer.
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
