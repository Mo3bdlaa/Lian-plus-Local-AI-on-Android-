package com.lian.plus.image

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.lian.plus.core.model.InstalledModel
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

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val bound: Boolean = false,
        val loadedModelId: String? = null,
        val modelVersion: String? = null,
        val engineAvailable: Boolean = true,
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
                        // The worker process was killed - most likely by the OOM
                        // killer during a large generation. Forget the handle so
                        // the next request rebinds and reloads.
                        Log.w(TAG, "image process disconnected")
                        service = null
                        _state.value = State(bound = false)
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

    /** Loads [model], optionally with a separate VAE or TAESD file. */
    suspend fun load(
        model: InstalledModel,
        vae: InstalledModel? = null,
        taesd: InstalledModel? = null,
        threads: Int,
        flashAttention: Boolean = true,
        convDirect: Boolean = true,
    ): Result<String> {
        val svc = connect()
            ?: return Result.failure(IllegalStateException("could not start the image process"))
        // loadModel reads gigabytes off disk and returns only when it is done;
        // on the main thread that is an ANR, not a slow call.
        return withContext(Dispatchers.IO) {
            runCatching {
                val ok = svc.loadModel(
                    model.filePath,
                    vae?.filePath.orEmpty(),
                    taesd?.filePath.orEmpty(),
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
