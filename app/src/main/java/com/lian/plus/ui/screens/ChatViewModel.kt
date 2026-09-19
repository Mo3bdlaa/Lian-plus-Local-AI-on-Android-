package com.lian.plus.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lian.plus.core.LianRuntime
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

data class ChatUiState(
    val chatId: Long? = null,
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

    private var turnJob: Job? = null

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

    private suspend fun runTurn(chatId: Long) {
        val settings = runtime.settingsStore.settings.first()
        runtime.syncToolsWithSettings(settings)

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
