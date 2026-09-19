package com.lian.plus.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lian.plus.llm.ChatFormat
import com.lian.plus.llm.SamplingParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "lian_settings")

data class AppSettings(
    val activeTextModelId: String? = null,
    val activeEmbeddingModelId: String? = null,
    val activeImageModelId: String? = null,

    val contextSize: Int = 4096,
    val threads: Int = 4,
    val batchSize: Int = 512,
    val kvCacheType: Int = 0,
    val flashAttention: Int = -1,
    val useMlock: Boolean = false,
    val chatFormatId: String = ChatFormat.AUTO.id,

    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,

    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,

    val toolsEnabled: Boolean = true,
    val webSearchEnabled: Boolean = false,
    val searxngUrl: String = "",
    val ragEnabled: Boolean = true,

    val serverEnabled: Boolean = false,
    val serverPort: Int = 8080,
    val serverLanExposed: Boolean = false,
    val serverApiKey: String = "",

    val imageSteps: Int = 4,
    val imageCfg: Float = 1.5f,
    val imageSize: Int = 512,
    val imageSampler: Int = 1,

    val huggingFaceToken: String = "",
    val keepScreenOnWhileGenerating: Boolean = true,
) {
    val chatFormat: ChatFormat
        get() = ChatFormat.entries.firstOrNull { it.id == chatFormatId } ?: ChatFormat.AUTO

    fun sampling(): SamplingParams = SamplingParams(
        maxTokens = maxTokens,
        temperature = temperature,
        topK = topK,
        topP = topP,
        minP = minP,
        repeatPenalty = repeatPenalty,
    )

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Lian, a helpful assistant running entirely on this phone. " +
                "Be direct and concise. If you do not know something, say so rather " +
                "than inventing an answer."
    }
}

/** Preferences, backed by DataStore. */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs.putString(Keys.textModel, next.activeTextModelId)
            prefs.putString(Keys.embedModel, next.activeEmbeddingModelId)
            prefs.putString(Keys.imageModel, next.activeImageModelId)
            prefs[Keys.contextSize] = next.contextSize
            prefs[Keys.threads] = next.threads
            prefs[Keys.batchSize] = next.batchSize
            prefs[Keys.kvCacheType] = next.kvCacheType
            prefs[Keys.flashAttention] = next.flashAttention
            prefs[Keys.useMlock] = next.useMlock
            prefs[Keys.chatFormat] = next.chatFormatId
            prefs[Keys.temperature] = next.temperature
            prefs[Keys.topK] = next.topK
            prefs[Keys.topP] = next.topP
            prefs[Keys.minP] = next.minP
            prefs[Keys.repeatPenalty] = next.repeatPenalty
            prefs[Keys.maxTokens] = next.maxTokens
            prefs[Keys.systemPrompt] = next.systemPrompt
            prefs[Keys.toolsEnabled] = next.toolsEnabled
            prefs[Keys.webSearch] = next.webSearchEnabled
            prefs[Keys.searxng] = next.searxngUrl
            prefs[Keys.rag] = next.ragEnabled
            prefs[Keys.serverEnabled] = next.serverEnabled
            prefs[Keys.serverPort] = next.serverPort
            prefs[Keys.serverLan] = next.serverLanExposed
            prefs[Keys.serverKey] = next.serverApiKey
            prefs[Keys.imageSteps] = next.imageSteps
            prefs[Keys.imageCfg] = next.imageCfg
            prefs[Keys.imageSize] = next.imageSize
            prefs[Keys.imageSampler] = next.imageSampler
            prefs[Keys.hfToken] = next.huggingFaceToken
            prefs[Keys.keepScreenOn] = next.keepScreenOnWhileGenerating
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.putString(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value == null) remove(key) else set(key, value)
    }

    private fun Preferences.toSettings() = AppSettings(
        activeTextModelId = this[Keys.textModel],
        activeEmbeddingModelId = this[Keys.embedModel],
        activeImageModelId = this[Keys.imageModel],
        contextSize = this[Keys.contextSize] ?: 4096,
        threads = this[Keys.threads] ?: 4,
        batchSize = this[Keys.batchSize] ?: 512,
        kvCacheType = this[Keys.kvCacheType] ?: 0,
        flashAttention = this[Keys.flashAttention] ?: -1,
        useMlock = this[Keys.useMlock] ?: false,
        chatFormatId = this[Keys.chatFormat] ?: ChatFormat.AUTO.id,
        temperature = this[Keys.temperature] ?: 0.7f,
        topK = this[Keys.topK] ?: 40,
        topP = this[Keys.topP] ?: 0.95f,
        minP = this[Keys.minP] ?: 0.05f,
        repeatPenalty = this[Keys.repeatPenalty] ?: 1.1f,
        maxTokens = this[Keys.maxTokens] ?: 1024,
        systemPrompt = this[Keys.systemPrompt] ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
        toolsEnabled = this[Keys.toolsEnabled] ?: true,
        webSearchEnabled = this[Keys.webSearch] ?: false,
        searxngUrl = this[Keys.searxng] ?: "",
        ragEnabled = this[Keys.rag] ?: true,
        serverEnabled = this[Keys.serverEnabled] ?: false,
        serverPort = this[Keys.serverPort] ?: 8080,
        serverLanExposed = this[Keys.serverLan] ?: false,
        serverApiKey = this[Keys.serverKey] ?: "",
        imageSteps = this[Keys.imageSteps] ?: 4,
        imageCfg = this[Keys.imageCfg] ?: 1.5f,
        imageSize = this[Keys.imageSize] ?: 512,
        imageSampler = this[Keys.imageSampler] ?: 1,
        huggingFaceToken = this[Keys.hfToken] ?: "",
        keepScreenOnWhileGenerating = this[Keys.keepScreenOn] ?: true,
    )

    private object Keys {
        val textModel = stringPreferencesKey("active_text_model")
        val embedModel = stringPreferencesKey("active_embed_model")
        val imageModel = stringPreferencesKey("active_image_model")
        val contextSize = intPreferencesKey("context_size")
        val threads = intPreferencesKey("threads")
        val batchSize = intPreferencesKey("batch_size")
        val kvCacheType = intPreferencesKey("kv_cache_type")
        val flashAttention = intPreferencesKey("flash_attention")
        val useMlock = booleanPreferencesKey("use_mlock")
        val chatFormat = stringPreferencesKey("chat_format")
        val temperature = floatPreferencesKey("temperature")
        val topK = intPreferencesKey("top_k")
        val topP = floatPreferencesKey("top_p")
        val minP = floatPreferencesKey("min_p")
        val repeatPenalty = floatPreferencesKey("repeat_penalty")
        val maxTokens = intPreferencesKey("max_tokens")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val toolsEnabled = booleanPreferencesKey("tools_enabled")
        val webSearch = booleanPreferencesKey("web_search")
        val searxng = stringPreferencesKey("searxng_url")
        val rag = booleanPreferencesKey("rag_enabled")
        val serverEnabled = booleanPreferencesKey("server_enabled")
        val serverPort = intPreferencesKey("server_port")
        val serverLan = booleanPreferencesKey("server_lan")
        val serverKey = stringPreferencesKey("server_api_key")
        val imageSteps = intPreferencesKey("image_steps")
        val imageCfg = floatPreferencesKey("image_cfg")
        val imageSize = intPreferencesKey("image_size")
        val imageSampler = intPreferencesKey("image_sampler")
        val hfToken = stringPreferencesKey("hf_token")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
    }
}
