package com.lian.plus.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lian.plus.core.LianRuntime
import com.lian.plus.data.db.GeneratedImageEntity
import com.lian.plus.image.ImageEvent
import java.io.File
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.core.TurnEvent
import com.lian.plus.core.TurnOptions
import com.lian.plus.data.db.ChatEntity
import com.lian.plus.data.db.MessageEntity
import com.lian.plus.llm.ChatMessage
import com.lian.plus.llm.ChatTurn
import com.lian.plus.tools.builtin.GenerateImageTool
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the composer will do when Send is tapped. */
enum class ComposerMode { TEXT, IMAGE }

data class ChatUiState(
    val chatId: Long? = null,
    val mode: ComposerMode = ComposerMode.TEXT,
    /** Progress of an image being generated into this conversation. */
    val imageStep: Int = 0,
    val imageTotalSteps: Int = 0,
    val imageSeconds: Int = 0,
    /** What the residency manager is doing, phrased for the user. */
    val residencyNote: String? = null,
    val generating: Boolean = false,
    /** The reply being streamed, before it is committed to the database. */
    val streamingText: String = "",
    val prefill: Pair<Int, Int>? = null,
    val activeTool: String? = null,
    val toolTrail: List<String> = emptyList(),
    val retrievedFrom: List<String> = emptyList(),
    val contextNote: String? = null,
    val error: String? = null,
    val modelName: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime = LianRuntime.get(app)
    private val chatDao = runtime.database.chats()
    private val messageDao = runtime.database.messages()

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    val chats: StateFlow<List<ChatEntity>> = chatDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // _ui changes on every streamed token, so the chat id is isolated first -
    // without distinctUntilChanged, flatMapLatest would tear down and rebuild
    // the database query once per token.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> = _ui
        .map { it.chatId }
        .distinctUntilChanged()
        .flatMapLatest { id -> id?.let { messageDao.observeForChat(it) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val loadedModel = runtime.llm.loaded

    /** Text models on the device, for the in-place picker. */
    val installedModels: StateFlow<List<InstalledModel>> =
        runtime.modelStore.observe(ModelKind.TEXT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val modelLoadState = runtime.modelLoadState

    /** Loads [model] without leaving the chat. */
    fun loadModel(model: InstalledModel) {
        viewModelScope.launch {
            runtime.loadTextModel(model).onFailure {
                _ui.value = _ui.value.copy(error = it.message)
            }
        }
    }

    private var turnJob: Job? = null

    val residencyState = runtime.residency.state

    fun setMode(mode: ComposerMode) {
        _ui.value = _ui.value.copy(mode = mode)
    }

    init {
        viewModelScope.launch {
            val existing = chatDao.observeAll().first()
            _ui.value = _ui.value.copy(
                chatId = existing.firstOrNull()?.id ?: createChat(),
                modelName = runtime.llm.loaded.value?.model?.displayName,
            )
        }
    }

    fun selectChat(id: Long) {
        _ui.value = _ui.value.copy(chatId = id, streamingText = "", error = null)
    }

    fun newChat() {
        viewModelScope.launch { _ui.value = _ui.value.copy(chatId = createChat()) }
    }

    fun deleteChat(id: Long) {
        viewModelScope.launch {
            chatDao.delete(id)
            if (_ui.value.chatId == id) {
                val remaining = chatDao.observeAll().first()
                _ui.value = _ui.value.copy(chatId = remaining.firstOrNull()?.id ?: createChat())
            }
        }
    }

    private suspend fun createChat(): Long = chatDao.insert(
        ChatEntity(
            title = "New chat",
            systemPrompt = null,
            modelId = runtime.llm.loaded.value?.model?.id,
        ),
    )

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _ui.value.generating) return

        if (_ui.value.mode == ComposerMode.IMAGE) {
            generateImage(trimmed)
            return
        }

        turnJob = viewModelScope.launch {
            val chatId = _ui.value.chatId ?: createChat().also {
                _ui.value = _ui.value.copy(chatId = it)
            }

            messageDao.insert(
                MessageEntity(chatId = chatId, role = ChatTurn.USER, content = trimmed),
            )
            // The first user message names the conversation; a full title would
            // cost a whole extra generation for very little.
            val chat = chatDao.byId(chatId)
            if (chat != null && chat.title == "New chat") {
                chatDao.rename(chatId, trimmed.take(48).let {
                    if (trimmed.length > 48) "$it…" else it
                })
            }
            chatDao.touch(chatId)

            runTurn(chatId)
        }
    }

    /** Re-runs the last turn, dropping the previous assistant reply. */
    fun regenerate() {
        if (_ui.value.generating) return
        turnJob = viewModelScope.launch {
            val chatId = _ui.value.chatId ?: return@launch
            val all = messageDao.forChat(chatId)
            val lastAssistant = all.lastOrNull { it.role == ChatTurn.ASSISTANT } ?: return@launch
            messageDao.deleteFrom(chatId, lastAssistant.id)
            runTurn(chatId)
        }
    }

    /**
     * Generates an image into the conversation.
     *
     * The prompt becomes a user message and the picture an assistant reply, so
     * the thread holds the whole iteration — which is the point: a prompt is
     * almost never right first time, and previously every attempt was thrown
     * away the moment the image appeared.
     */
    fun generateImage(prompt: String, seed: Long = -1) {
        if (_ui.value.generating) return
        turnJob = viewModelScope.launch {
            val chatId = _ui.value.chatId ?: createChat().also {
                _ui.value = _ui.value.copy(chatId = it)
            }
            messageDao.insert(
                MessageEntity(chatId = chatId, role = ChatTurn.USER, content = prompt),
            )
            chatDao.byId(chatId)?.takeIf { it.title == "New chat" }?.let {
                chatDao.rename(chatId, prompt.take(48))
            }
            chatDao.touch(chatId)

            val model = runtime.modelStore.observe(ModelKind.IMAGE).first()
                .firstOrNull { it.id == runtime.currentSettings.activeImageModelId }
                ?: runtime.modelStore.observe(ModelKind.IMAGE).first().firstOrNull()

            if (model == null) {
                failTurn(chatId, "No image model is installed. Open Models to download one.")
                return@launch
            }

            _ui.value = _ui.value.copy(
                generating = true,
                imageStep = 0,
                imageTotalSteps = runtime.currentSettings.imageSteps,
                imageSeconds = 0,
                error = null,
            )

            // Load on intent. The residency manager reports what it had to
            // release, so a pause is explained rather than unexplained.
            val ensured = runtime.residency.ensure(model)
            ensured.onFailure {
                failTurn(chatId, it.message ?: "Could not load ${model.displayName}")
                return@launch
            }
            ensured.getOrNull()?.let { evicted ->
                _ui.value = _ui.value.copy(
                    residencyNote = "Released ${evicted.displayName} to make room for " +
                        model.displayName,
                )
            }

            val ticker = launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    _ui.value = _ui.value.copy(imageSeconds = _ui.value.imageSeconds + 1)
                }
            }

            val target = File(runtime.imagesDir, "chat_${System.currentTimeMillis()}.png")
            val request = runtime.defaultImageRequest().copy(prompt = prompt, seed = seed)

            runtime.imageClient.generate(request, target).collect { event ->
                when (event) {
                    is ImageEvent.Step -> _ui.value = _ui.value.copy(
                        imageStep = event.step,
                        imageTotalSteps = event.totalSteps,
                    )

                    is ImageEvent.Done -> {
                        val imageId = runtime.database.images().insert(
                            GeneratedImageEntity(
                                filePath = event.file.absolutePath,
                                prompt = prompt,
                                negativePrompt = request.negativePrompt.ifBlank { null },
                                width = event.width,
                                height = event.height,
                                steps = request.steps,
                                cfgScale = request.cfgScale,
                                seed = request.seed,
                                sampler = request.sampler.label,
                                modelId = model.id,
                                durationMillis = event.elapsedMillis,
                            ),
                        )
                        messageDao.insert(
                            MessageEntity(
                                chatId = chatId,
                                role = ChatTurn.ASSISTANT,
                                content = "",
                                imagePath = event.file.absolutePath,
                                generatedImageId = imageId,
                                statsLine = "${event.width}x${event.height} · " +
                                    "${request.steps} steps · ${event.elapsedMillis / 1000}s",
                            ),
                        )
                        chatDao.touch(chatId)
                    }

                    is ImageEvent.Failed -> failTurn(chatId, event.message)
                }
            }

            ticker.cancel()
            _ui.value = _ui.value.copy(generating = false, imageStep = 0, imageSeconds = 0)
        }
    }

    /** Regenerates an image message with a fresh seed, appended to the thread. */
    fun regenerateImage(message: MessageEntity) {
        viewModelScope.launch {
            val source = message.generatedImageId?.let { runtime.database.images().byId(it) }
            generateImage(source?.prompt ?: return@launch, seed = -1)
        }
    }

    /** Loads an image message's prompt back into the composer for editing. */
    suspend fun promptOf(message: MessageEntity): String? =
        message.generatedImageId?.let { runtime.database.images().byId(it)?.prompt }

    private suspend fun failTurn(chatId: Long, message: String) {
        messageDao.insert(
            MessageEntity(
                chatId = chatId,
                role = ChatTurn.ASSISTANT,
                content = message,
                isError = true,
            ),
        )
        _ui.value = _ui.value.copy(generating = false, error = message)
    }

    /** Copies a generated image into the device gallery. */
    fun saveToGallery(context: android.content.Context, file: File) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file.name)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lian+")
                    }
                    val uri = context.contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                    ) ?: error("the gallery rejected the file")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("could not open the destination")
                    true
                }.getOrElse { false }
            }
            _ui.value = _ui.value.copy(
                residencyNote = if (ok) "Saved to Pictures/Lian+" else "Could not save to the gallery",
            )
        }
    }

    fun share(context: android.content.Context, file: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.files", file,
            )
            context.startActivity(
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share image",
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            _ui.value = _ui.value.copy(residencyNote = "Could not share: ${it.message}")
        }
    }

    fun clearResidencyNote() {
        _ui.value = _ui.value.copy(residencyNote = null)
    }

    private suspend fun runTurn(chatId: Long) {
        val settings = runtime.settingsStore.settings.first()
        runtime.syncToolsWithSettings(settings)

        // Load the text model on first use rather than at startup: opening the
        // app to generate a picture should not pay for a language model.
        if (!runtime.llm.isLoaded) {
            val model = settings.activeTextModelId?.let { runtime.modelStore.byId(it) }
                ?: runtime.modelStore.observe(ModelKind.TEXT).first().firstOrNull()
            if (model == null) {
                failTurn(chatId, "No text model is installed. Open Models to download one.")
                return
            }
            _ui.value = _ui.value.copy(
                generating = true,
                residencyNote = "Loading ${model.displayName}…",
            )
            val ensured = runtime.residency.ensure(model)
            ensured.onFailure {
                failTurn(chatId, it.message ?: "Could not load ${model.displayName}")
                return
            }
            _ui.value = _ui.value.copy(
                residencyNote = ensured.getOrNull()
                    ?.let { "Released ${it.displayName} to make room for ${model.displayName}" },
            )
        }

        val history = messageDao.forChat(chatId).map {
            ChatMessage(it.id, it.role, it.content, it.tokens, it.isSummary)
        }

        _ui.value = _ui.value.copy(
            generating = true,
            streamingText = "",
            error = null,
            toolTrail = emptyList(),
            retrievedFrom = emptyList(),
            contextNote = null,
            modelName = runtime.llm.loaded.value?.model?.displayName,
        )

        val options = TurnOptions(
            systemPrompt = chatDao.byId(chatId)?.systemPrompt ?: settings.systemPrompt,
            sampling = settings.sampling(),
            useTools = settings.toolsEnabled,
            useRetrieval = settings.ragEnabled,
        )

        val buffer = StringBuilder()
        var imagePath: String? = null
        var statsLine: String? = null

        runtime.orchestrator.run(history, options).collect { event ->
            when (event) {
                TurnEvent.Thinking -> Unit

                is TurnEvent.Retrieved -> _ui.value = _ui.value.copy(
                    retrievedFrom = event.passages.map { it.documentTitle }.distinct(),
                )

                is TurnEvent.ContextPlanned -> _ui.value = _ui.value.copy(
                    contextNote = buildString {
                        append("${event.plan.promptTokens} prompt tokens")
                        if (event.plan.summarisedMessages > 0) {
                            append(" · ${event.plan.summarisedMessages} older messages summarised")
                        }
                        if (event.plan.droppedMessages > 0) {
                            append(" · ${event.plan.droppedMessages} dropped")
                        }
                    },
                )

                is TurnEvent.Prefill -> _ui.value =
                    _ui.value.copy(prefill = event.done to event.total)

                is TurnEvent.Delta -> {
                    buffer.append(event.text)
                    _ui.value = _ui.value.copy(streamingText = buffer.toString(), prefill = null)
                }

                is TurnEvent.ToolStarted -> _ui.value =
                    _ui.value.copy(activeTool = event.call.name)

                is TurnEvent.ToolFinished -> {
                    // A generated image comes back as a path; pull it out so the
                    // message can show the picture instead of the marker.
                    val marker = GenerateImageTool.IMAGE_MARKER
                    if (event.result.content.startsWith(marker)) {
                        imagePath = event.result.content
                            .removePrefix(marker)
                            .substringBefore('\n')
                            .trim()
                    }
                    _ui.value = _ui.value.copy(
                        activeTool = null,
                        toolTrail = _ui.value.toolTrail +
                            (event.result.displaySummary ?: "Ran ${event.call.name}"),
                    )
                }

                is TurnEvent.Completed -> {
                    val text = event.text.ifBlank { buffer.toString() }
                    statsLine = event.stats?.let { s ->
                        // A real generation is a better measurement than any
                        // synthetic probe, so it refines the device estimates.
                        runtime.recordGenerationSpeed(s.tokensPerSecond, s.generatedTokens)
                        "%.1f tok/s · %d tokens · %.0f tok/s prompt".format(
                            s.tokensPerSecond, s.generatedTokens, s.prefillTokensPerSecond,
                        )
                    }
                    messageDao.insert(
                        MessageEntity(
                            chatId = chatId,
                            role = ChatTurn.ASSISTANT,
                            content = text,
                            tokens = event.stats?.generatedTokens ?: -1,
                            imagePath = imagePath,
                            statsLine = statsLine,
                        ),
                    )
                    chatDao.touch(chatId)
                }

                is TurnEvent.Failed -> {
                    _ui.value = _ui.value.copy(error = event.message)
                    messageDao.insert(
                        MessageEntity(
                            chatId = chatId,
                            role = ChatTurn.ASSISTANT,
                            content = event.message,
                            isError = true,
                        ),
                    )
                }
            }
        }

        _ui.value = _ui.value.copy(
            generating = false,
            streamingText = "",
            prefill = null,
            activeTool = null,
        )
    }

    fun stop() {
        runtime.llm.cancel()
        turnJob?.cancel()
        _ui.value = _ui.value.copy(generating = false, streamingText = "")
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun observeChats(): Flow<List<ChatEntity>> = chatDao.observeAll()
}
