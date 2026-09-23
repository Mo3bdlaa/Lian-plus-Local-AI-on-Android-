package com.lian.plus.hub

import com.lian.plus.core.model.DiffusionArch
import com.lian.plus.core.model.DiffusionArchDetector
import com.lian.plus.core.model.GgufRole
import com.lian.plus.core.model.GgufRoleDetector
import com.lian.plus.core.model.ImageComponent
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
        // Any extension: a Hub repository splits safetensors the same way,
        // and offering part one of three as a download is the bug this
        // pattern exists to prevent.
        private val SPLIT_REGEX = Regex(
            """-(\d{5})-of-(\d{5})\.(gguf|safetensors|sft)$""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** Files an engine can load: GGUF for text, either format for diffusion. */
private fun isWeightFile(path: String): Boolean =
    path.endsWith(".gguf", ignoreCase = true) ||
        path.endsWith(".safetensors", ignoreCase = true) ||
        path.endsWith(".sft", ignoreCase = true)

/** A page of browse results plus the cursor that continues it. */
data class HubPage(
    val models: List<HfModelSummary>,
    val nextCursor: String?,
)

/** The filter state behind the browse screen. */
data class HubQuery(
    val text: String = "",
    val task: HuggingFaceApi.Task? = null,
    val author: String = "",
    val sort: HuggingFaceApi.SortOrder = HuggingFaceApi.SortOrder.TRENDING,
    val limit: Int = 30,
)

/**
 * One downloadable thing in a repository.
 *
 * A split model is several files that are useless apart, so the unit the user
 * picks is the set, not a file. Grouping them here is what stops the app
 * downloading shard 1 of 9 and then reporting that llama.cpp cannot read it.
 */
data class HfAsset(
    val repoId: String,
    val displayName: String,
    val files: List<HfFile>,
    val totalBytes: Long,
    val quant: Quant,
    val role: GgufRole,
    /** Set when this is a companion file rather than something to load. */
    val component: ImageComponent? = null,
    /** Set when this is a diffusion checkpoint, saying what else it needs. */
    val arch: DiffusionArch? = null,
    /** Overrides the role's generic explanation when there is a better one. */
    val note: String? = null,
) {
    val isSplit: Boolean get() = files.size > 1
    val primary: HfFile get() = files.first()

    /** A pipeline part this checkpoint needs the user to fetch as well. */
    val requires: Set<ImageComponent> get() = arch?.required.orEmpty()
}

data class HfRepoDetail(
    val summary: HfModelSummary,
    val assets: List<HfAsset>,
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

    /**
     * Browses the Hub.
     *
     * Everything the website's own filter sidebar offers that is meaningful
     * here: what the model does, who published it, how to order the results,
     * and free text. Results are cursor-paginated, so the caller can keep
     * asking for more rather than being capped at one page.
     */
    suspend fun browse(query: HubQuery, cursor: String? = null): HubPage =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("$BASE/api/models?limit=").append(query.limit)
                // Every engine in this app reads GGUF; anything else would be
                // listed only to fail at download time.
                append("&filter=gguf")
                append("&sort=").append(query.sort.param)
                append("&direction=-1")
                query.task?.let { append("&pipeline_tag=").append(it.tag) }
                if (query.author.isNotBlank()) append("&author=").append(encode(query.author))
                if (query.text.isNotBlank()) append("&search=").append(encode(query.text))
                cursor?.let { append("&cursor=").append(encode(it)) }
            }
            val (body, next) = getWithCursor(url)
            HubPage(
                models = json.decodeFromString<List<HfModelSummary>>(body),
                nextCursor = next,
            )
        }

    /** Kept for callers that only need a single page of text models. */
    suspend fun searchModels(
        query: String,
        limit: Int = 30,
        sort: SortOrder = SortOrder.DOWNLOADS,
    ): List<HfModelSummary> =
        browse(HubQuery(text = query, limit = limit, sort = sort)).models

    /** Lists the files in a repository, with real (post-LFS) sizes. */
    suspend fun repoFiles(repoId: String): HfRepoDetail = withContext(Dispatchers.IO) {
        val summary = json.decodeFromString<HfModelSummary>(get("$BASE/api/models/$repoId"))
        val entries = json.decodeFromString<List<HfTreeEntry>>(
            get("$BASE/api/models/$repoId/tree/main?recursive=true"),
        )
        val files = entries
            .filter { it.type == "file" }
            .map { HfFile(repoId, it.path, it.realSize, Quant.fromFileName(it.path)) }

        // `.safetensors` is included because the VAE and text encoder of a
        // Qwen-Image or Z-Image repository are published in that format, and
        // the diffusion engine reads it directly. Filtering to GGUF meant the
        // two thirds of those pipelines that are not the transformer were
        // invisible in the browser.
        val weights = files.filter { isWeightFile(it.path) }

        // Shards of one model collapse into a single asset carrying every part;
        // everything else is an asset of one.
        val (shards, singles) = weights.partition { it.isSplitShard }
        val splitAssets = shards
            .groupBy { GgufRoleDetector.splitBaseName(it.fileName) ?: it.fileName }
            .map { (base, parts) ->
                val ordered = parts.sortedBy { it.fileName }
                val expected = GgufRoleDetector.shardTotal(ordered.first().fileName)
                HfAsset(
                    repoId = repoId,
                    displayName = base,
                    files = ordered,
                    totalBytes = ordered.sumOf { it.sizeBytes },
                    quant = Quant.fromFileName(base),
                    // An incomplete set on the Hub itself is still unusable, so
                    // say so rather than letting it look like a normal model.
                    role = if (expected != null && ordered.size < expected) {
                        GgufRole.SHARD
                    } else if (ordered.first().path.endsWith(".gguf", ignoreCase = true)) {
                        GgufRole.STANDALONE
                    } else {
                        // llama.cpp opens a split GGUF through its first shard.
                        // A split safetensors set has no such convention: the
                        // diffusion engine takes one file, so this cannot be
                        // used however many parts are downloaded.
                        GgufRole.SHARD
                    },
                    note = if (!ordered.first().path.endsWith(".gguf", ignoreCase = true)) {
                        "Split across ${ordered.size} safetensors files. The engine " +
                            "loads a single file, so look for a GGUF or single-file " +
                            "version of this instead."
                    } else {
                        null
                    },
                )
            }

        val singleAssets = singles.map { file ->
            // The folder carries as much signal as the name: `vae/…` and
            // `text_encoders/…` are how these repositories are laid out, and
            // the file names alone are not consistent enough to go on.
            val component = DiffusionArchDetector.componentOf(file.path)
            HfAsset(
                repoId = repoId,
                displayName = file.path,
                files = listOf(file),
                totalBytes = file.sizeBytes,
                quant = file.quant,
                role = if (component != null) {
                    GgufRole.DIFFUSION_COMPONENT
                } else {
                    GgufRoleDetector.roleFromName(file.fileName)
                },
                component = component,
                arch = if (component == null) {
                    DiffusionArchDetector.fromName(file.path)
                        .takeIf { it != DiffusionArch.UNKNOWN }
                } else {
                    null
                },
            )
        }

        HfRepoDetail(
            summary = summary,
            // Loadable models first, then the companions, each by size.
            assets = (splitAssets + singleAssets).sortedWith(
                compareByDescending<HfAsset> { it.role.isLoadable }.thenBy { it.totalBytes },
            ),
            otherFiles = files.filterNot { isWeightFile(it.path) },
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

    /** Performs a GET and extracts the `cursor=` value from the Link header. */
    private fun getWithCursor(url: String): Pair<String, String?> {
        val req = Request.Builder().url(url).apply {
            header("User-Agent", USER_AGENT)
            tokenProvider()?.let { header("Authorization", "Bearer $it") }
        }.build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${body.take(200)}")
            }
            val next = resp.header("link")
                ?.split(',')
                ?.firstOrNull { it.contains("rel=\"next\"") }
                ?.substringAfter("cursor=")
                ?.substringBefore('>')
                ?.substringBefore('&')
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            return body to next
        }
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

    enum class SortOrder(val param: String, val label: String) {
        TRENDING("trendingScore", "Trending"),
        DOWNLOADS("downloads", "Most downloaded"),
        LIKES("likes", "Most liked"),
        RECENT("lastModified", "Recently updated"),
    }

    /** The Hub's pipeline tags, limited to the ones this app can actually run. */
    enum class Task(val tag: String, val label: String) {
        TEXT("text-generation", "Text"),
        IMAGE("text-to-image", "Image"),
        EMBEDDING("sentence-similarity", "Embedding"),
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
