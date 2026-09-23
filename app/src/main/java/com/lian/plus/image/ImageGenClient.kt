package com.lian.plus.image

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.lian.plus.core.model.ImageComponent
import com.lian.plus.core.model.ImagePipeline
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** Samplers exposed in the UI, in `sample_method_t` order. */
enum class Sampler(val nativeValue: Int, val label: String) {
    EULER(0, "Euler"),
    EULER_A(1, "Euler Ancestral"),
    HEUN(2, "Heun"),
    DPM2(3, "DPM2"),
    DPMPP2S_A(4, "DPM++ 2S a"),
    DPMPP2M(5, "DPM++ 2M"),
    LCM(9, "LCM"),
    DDIM(10, "DDIM Trailing"),
}

data class ImageRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 4,
    val cfgScale: Float = 1.5f,
    val seed: Long = -1,
    val sampler: Sampler = Sampler.EULER_A,
    val scheduler: Int = 0,
    val initImagePath: String? = null,
    val strength: Float = 0.6f,
)

sealed interface ImageEvent {
    data class Step(val step: Int, val totalSteps: Int, val secondsForStep: Float) : ImageEvent {
        val fraction: Float get() = if (totalSteps > 0) step.toFloat() / totalSteps else 0f
    }

    data class Done(
        val file: File,
        val width: Int,
        val height: Int,
        val elapsedMillis: Long,
    ) : ImageEvent

    data class Failed(val message: String) : ImageEvent
}

/**
 * Talks to [ImageGenService] across the process boundary.
 *
 * The binding is kept alive for as long as a model is loaded, because loading a
 * checkpoint takes tens of seconds; [releaseProcess] tears it down explicitly
 * when the user is finished, which is also what reclaims the worker's memory.
 */
class ImageGenClient(private val context: Context) {

    private var service: IImageGenService? = null
    private var connection: ServiceConnection? = null

    /** Read-only here: used to find the companion files a checkpoint needs. */
    private val models by lazy { ModelStore(context) }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val bound: Boolean = false,
        val loadedModelId: String? = null,
        val modelVersion: String? = null,
        val engineAvailable: Boolean = true,
        /** Set when the worker process died rather than being unloaded. */
        val crashMessage: String? = null,
    )

    private suspend fun connect(): IImageGenService? {
        service?.let { return it }
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val svc = IImageGenService.Stub.asInterface(binder)
                        service = svc
                        _state.value = _state.value.copy(
                            bound = true,
                            engineAvailable = runCatching { svc.isEngineAvailable }.getOrDefault(false),
                        )
                        if (cont.isActive) cont.resume(svc)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        // The worker process died. Saying "no model loaded"
                        // makes it look like the app unloaded on purpose, which
                        // is the opposite of useful when the real cause is the
                        // system reclaiming memory.
                        val wasLoaded = _state.value.loadedModelId
                        Log.w(TAG, "image process died (model was $wasLoaded)")
                        service = null
                        _state.value = State(
                            bound = false,
                            crashMessage = if (wasLoaded != null) {
                                "The image engine was stopped by the system, most likely " +
                                    "for memory. Close other apps and load the model again; " +
                                    "a smaller output size also helps."
                            } else null,
                        )
                    }
                }
                connection = conn
                val intent = Intent(context, ImageGenService::class.java)
                val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                if (!ok && cont.isActive) cont.resume(null)
                cont.invokeOnCancellation { runCatching { context.unbindService(conn) } }
            }
        }
    }

    suspend fun engineInfo(): String = withContext(Dispatchers.IO) {
        runCatching { connect()?.engineInfo() }.getOrNull() ?: "image engine unavailable"
    }

    /**
     * Loads [model] together with whatever companion files it needs.
     *
     * The pipeline is resolved here rather than at each call site so that
     * every path into the engine — the Images screen, the chat composer, the
     * HTTP server — gets the same answer, including the same refusal when a
     * piece is missing.
     */
    suspend fun load(
        model: InstalledModel,
        threads: Int,
        flashAttention: Boolean = true,
        convDirect: Boolean = true,
    ): Result<String> {
        val pipeline = withContext(Dispatchers.IO) { models.pipelineFor(model) }
        return load(pipeline, threads, flashAttention, convDirect)
    }

    suspend fun load(
        pipeline: ImagePipeline,
        threads: Int,
        flashAttention: Boolean = true,
        convDirect: Boolean = true,
    ): Result<String> {
        // Refusing here, by name, beats letting the engine fail on two thirds
        // of a model and reporting whatever it says on the way down.
        if (!pipeline.isComplete) {
            return Result.failure(
                IllegalStateException(
                    "${pipeline.primary.displayName} is a ${pipeline.arch.label} " +
                        "pipeline and still needs its ${pipeline.missingLabel}. " +
                        "Both usually sit in the same repository as the checkpoint.",
                ),
            )
        }

        val svc = connect()
            ?: return Result.failure(IllegalStateException("could not start the image process"))
        // loadModel reads gigabytes off disk and returns only when it is done;
        // on the main thread that is an ANR, not a slow call.
        return withContext(Dispatchers.IO) {
            runCatching {
                val model = pipeline.primary
                // A bare transformer handed over as a checkpoint is exactly the
                // failure this whole path exists to avoid, so which slot it
                // goes in follows from the detected family, not from a guess.
                val asCheckpoint = !pipeline.arch.isDiffusionOnly
                val ok = svc.loadModel(
                    if (asCheckpoint) model.filePath else "",
                    if (asCheckpoint) "" else model.filePath,
                    pipeline.pathOf(ImageComponent.VAE),
                    pipeline.pathOf(ImageComponent.TAESD),
                    pipeline.pathOf(ImageComponent.CLIP_L),
                    pipeline.pathOf(ImageComponent.CLIP_G),
                    pipeline.pathOf(ImageComponent.T5XXL),
                    pipeline.pathOf(ImageComponent.LLM),
                    threads,
                    flashAttention,
                    convDirect,
                )
                if (!ok) error("the engine could not load ${model.fileName}")
                val version = runCatching { svc.modelVersion() }.getOrDefault("")
                _state.value = _state.value.copy(loadedModelId = model.id, modelVersion = version)
                version
            }
        }
    }

    fun generate(request: ImageRequest, outputFile: File): Flow<ImageEvent> = callbackFlow {
        val svc = connect()
        if (svc == null) {
            trySend(ImageEvent.Failed("The image process is not available.")); close(); return@callbackFlow
        }

        val callback = object : IImageGenCallback.Stub() {
            override fun onStep(step: Int, totalSteps: Int, secondsForStep: Float) {
                trySend(ImageEvent.Step(step, totalSteps, secondsForStep))
            }

            override fun onComplete(filePath: String, width: Int, height: Int, elapsedMillis: Long) {
                trySend(ImageEvent.Done(File(filePath), width, height, elapsedMillis))
                close()
            }

            override fun onError(message: String) {
                trySend(ImageEvent.Failed(message))
                close()
            }
        }

        runCatching {
            svc.generate(
                request.prompt,
                request.negativePrompt,
                request.width,
                request.height,
                request.steps,
                request.cfgScale,
                request.seed,
                request.sampler.nativeValue,
                request.scheduler,
                request.initImagePath.orEmpty(),
                request.strength,
                outputFile.absolutePath,
                callback,
            )
        }.onFailure {
            trySend(ImageEvent.Failed(it.message ?: "could not reach the image process"))
            close()
        }

        awaitClose { runCatching { svc.cancel() } }
    }.flowOn(Dispatchers.IO)

    fun cancel() {
        runCatching { service?.cancel() }
    }

    fun clearCrashMessage() {
        _state.value = _state.value.copy(crashMessage = null)
    }

    /** Unloads the model and lets the worker process exit, freeing its memory. */
    fun releaseProcess() {
        runCatching { service?.unload() }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        _state.value = State()
    }

    private companion object {
        const val TAG = "LianImageClient"
    }
}
