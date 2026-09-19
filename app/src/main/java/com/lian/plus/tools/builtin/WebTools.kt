package com.lian.plus.tools.builtin

import com.lian.plus.hub.HuggingFaceApi
import com.lian.plus.tools.Tool
import com.lian.plus.tools.ToolParameter
import com.lian.plus.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where [WebSearchTool] sends queries. */
data class SearchBackend(
    val kind: Kind,
    /** Base URL of a SearXNG instance, when [kind] is SEARXNG. */
    val searxngUrl: String = "",
) {
    enum class Kind { DUCKDUCKGO, SEARXNG }
}

/**
 * Web search.
 *
 * Two backends, both keyless. DuckDuckGo's HTML endpoint is the default because
 * it needs no setup; it is scraped, so it can break without warning, which is
 * why a self-hosted SearXNG instance (proper JSON API) is offered as the stable
 * alternative.
 *
 * This is the one built-in tool that sends the user's words off the device, so
 * it is off by default and the UI says as much.
 */
class WebSearchTool(
    private val client: OkHttpClient = HuggingFaceApi.defaultClient(),
    private val backendProvider: () -> SearchBackend = { SearchBackend(SearchBackend.Kind.DUCKDUCKGO) },
) : Tool {

    override val name = "web_search"
    override val description =
        "Searches the web and returns the top results with short snippets. Use it for " +
            "anything recent, or any fact you are not sure about."
    override val parameters = listOf(
        ToolParameter("query", "string", "What to search for."),
        ToolParameter("count", "integer", "How many results, 1-8. Default 5.", required = false),
    )
    override val requiresNetwork = true
    override val isSensitive = true

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val query = args.str("query")
            ?: return@withContext ToolResult("No query given.", isError = true)
        val count = (args.num("count")?.toInt() ?: 5).coerceIn(1, 8)

        val backend = backendProvider()
        val results = runCatching {
            when (backend.kind) {
                SearchBackend.Kind.SEARXNG -> searxng(backend.searxngUrl, query, count)
                SearchBackend.Kind.DUCKDUCKGO -> duckDuckGo(query, count)
            }
        }.getOrElse {
            return@withContext ToolResult("Search failed: ${it.message}", isError = true)
        }

        if (results.isEmpty()) {
            return@withContext ToolResult("No results for \"$query\".")
        }

        val body = results.mapIndexed { i, r ->
            "[${i + 1}] ${r.title}\n${r.url}\n${r.snippet}"
        }.joinToString("\n\n")

        ToolResult(
            content = "Search results for \"$query\":\n\n$body",
            displaySummary = "Searched the web for \"$query\" (${results.size} results)",
        )
    }

    private data class Hit(val title: String, val url: String, val snippet: String)

    private fun searxng(baseUrl: String, query: String, count: Int): List<Hit> {
        require(baseUrl.startsWith("http")) { "SearXNG URL is not set" }
        val url = baseUrl.trimEnd('/') +
            "/search?format=json&q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val body = get(url)
        val arr = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return emptyList()
        return arr.take(count).map { el ->
            val o = el.jsonObject
            Hit(
                title = o["title"]?.jsonPrimitive?.content.orEmpty(),
                url = o["url"]?.jsonPrimitive?.content.orEmpty(),
                snippet = o["content"]?.jsonPrimitive?.content.orEmpty().take(400),
            )
        }
    }

    private fun duckDuckGo(query: String, count: Int): List<Hit> {
        val url = "https://html.duckduckgo.com/html/?q=" +
            java.net.URLEncoder.encode(query, "UTF-8")
        val html = get(url)

        val results = mutableListOf<Hit>()
        for (m in RESULT_LINK.findAll(html)) {
            if (results.size >= count) break
            val href = decodeRedirect(m.groupValues[1])
            val title = stripTags(m.groupValues[2])
            if (title.isBlank() || href.isBlank()) continue

            val snippet = SNIPPET
                .find(html, m.range.last)
                ?.takeIf { it.range.first - m.range.last < 2000 }
                ?.let { stripTags(it.groupValues[1]) }
                .orEmpty()

            results += Hit(title, href, snippet.take(400))
        }
        return results
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    /** DuckDuckGo wraps outbound links in `/l/?uddg=<encoded>`. */
    private fun decodeRedirect(href: String): String {
        val marker = "uddg="
        val at = href.indexOf(marker)
        if (at < 0) return href
        val encoded = href.substring(at + marker.length).substringBefore('&')
        return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
    }

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"
        val RESULT_LINK = Regex(
            """<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val SNIPPET = Regex(
            """<a[^>]+class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }

    private fun stripTags(html: String): String = html
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Fetches one page and hands the model its readable text. */
class WebFetchTool(
    private val client: OkHttpClient = HuggingFaceApi.defaultClient(),
) : Tool {
    override val name = "fetch_url"
    override val description =
        "Downloads a web page and returns its readable text. Use it to read a result " +
            "that web_search returned."
    override val parameters = listOf(
        ToolParameter("url", "string", "Full URL, including https://"),
        ToolParameter(
            "max_chars", "integer",
            "Maximum characters to return, up to 20000. Default 6000.", required = false,
        ),
    )
    override val requiresNetwork = true
    override val isSensitive = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val url = args.str("url") ?: return@withContext ToolResult("No URL given.", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult("Only http and https URLs are allowed.", isError = true)
        }
        val limit = (args.num("max_chars")?.toInt() ?: 6000).coerceIn(500, 20_000)

        runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", "LianPlus/0.1")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ToolResult("HTTP ${resp.code} from $url", isError = true)
                }
                val contentType = resp.header("content-type").orEmpty()
                val raw = resp.body?.string().orEmpty()
                val text = if (contentType.contains("html", ignoreCase = true)) {
                    readableText(raw)
                } else {
                    raw
                }
                val clipped = text.take(limit)
                ToolResult(
                    content = "Contents of $url:\n\n$clipped" +
                        if (text.length > limit) "\n\n[truncated at $limit characters]" else "",
                    displaySummary = "Fetched $url",
                )
            }
        }.getOrElse { ToolResult("Could not fetch $url: ${it.message}", isError = true) }
    }

    /** Drops scripts, styles and markup — enough to make a page readable. */
    private fun readableText(html: String): String = html
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        .replace(Regex("(?is)<nav[^>]*>.*?</nav>"), " ")
        .replace(Regex("(?is)<footer[^>]*>.*?</footer>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .replace(Regex("[ \t]{2,}"), " ")
        .trim()
}
