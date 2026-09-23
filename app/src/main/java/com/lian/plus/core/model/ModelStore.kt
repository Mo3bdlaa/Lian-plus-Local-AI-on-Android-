package com.lian.plus.core.model

import android.content.Context
import android.util.Log
import com.lian.plus.data.db.AppDatabase
import com.lian.plus.data.db.ModelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The registry of model files on the device.
 *
 * Files live in the app's own `files/models` directory, so they are removed
 * when the app is uninstalled and need no storage permission. The database row
 * carries the metadata we read once at import time, so listing models never has
 * to re-parse a multi-gigabyte file.
 */
class ModelStore(private val context: Context) {

    private val dao = AppDatabase.get(context).models()

    val modelsDir: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }

    fun dirFor(kind: ModelKind): File =
        File(modelsDir, kind.name.lowercase()).apply { mkdirs() }

    fun observeAll(): Flow<List<InstalledModel>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    fun observe(kind: ModelKind): Flow<List<InstalledModel>> =
        dao.observeByKind(kind.name).map { list -> list.map { it.toModel() } }

    suspend fun byId(id: String): InstalledModel? =
        withContext(Dispatchers.IO) { dao.byId(id)?.toModel() }

    /** Everything installed, for resolving a pipeline against what is present. */
    suspend fun all(): List<InstalledModel> =
        withContext(Dispatchers.IO) { dao.all().map { it.toModel() } }

    /** The checkpoint plus whichever companion files it needs and we have. */
    suspend fun pipelineFor(model: InstalledModel): ImagePipeline =
        ImagePipelineResolver.resolve(model, all())

    suspend fun markUsed(id: String) = withContext(Dispatchers.IO) { dao.markUsed(id) }

    /**
     * Registers a downloaded file, reading its GGUF header for metadata.
     *
     * The id is derived from the repo and filename so re-importing the same
     * file updates the row instead of creating a duplicate.
     */
    suspend fun register(
        file: File,
        kind: ModelKind,
        repoId: String?,
        displayName: String? = null,
        component: ImageComponent? = null,
        /** The path inside the repository, when it differs from the file name. */
        relativeName: String? = null,
    ): InstalledModel = withContext(Dispatchers.IO) {
        val info = if (file.name.endsWith(".gguf", ignoreCase = true)) {
            GgufInspector.inspect(file)
        } else null

        val id = buildString {
            append(repoId?.replace('/', '_') ?: "local")
            append('_')
            append(file.name.substringBeforeLast('.'))
        }.take(180)

        // The header has the last word. A file named like a model but carrying
        // a clip architecture and no layers is a vision projector, and calling
        // it a model only defers the failure to load time.
        val role = GgufRoleDetector.roleFromMetadata(info, file.name)

        // A companion file is recognised by name and by the folder it came
        // from, which repositories are consistent about: `vae/`,
        // `text_encoders/`. Left to the caller this was only ever set for
        // files downloaded through the picker, so a sideloaded VAE looked like
        // a checkpoint that would not load.
        val part = component ?: DiffusionArchDetector.componentOf(relativeName ?: file.name)
        val effectiveKind = when {
            part == null -> kind
            // A Qwen chat model doubles as the text encoder for Qwen-Image and
            // Z-Image. Recording that it can serve as one must not stop it
            // being a chat model, or the user loses it from the chat picker
            // and pays for the same weights twice.
            part == ImageComponent.LLM && kind == ModelKind.TEXT -> ModelKind.TEXT
            kind == ModelKind.IMAGE -> ModelKind.IMAGE_COMPONENT
            else -> kind
        }

        val model = InstalledModel(
            id = id,
            displayName = displayName
                ?: info?.name
                ?: file.name.removeSuffix(".gguf").replace('-', ' '),
            kind = effectiveKind,
            filePath = file.absolutePath,
            sizeBytes = file.length(),
            repoId = repoId,
            fileName = file.name,
            quant = Quant.fromFileName(file.name).takeIf { it != Quant.UNKNOWN }
                ?: info?.quantLabel?.let { Quant.fromFileName(it) }
                ?: Quant.UNKNOWN,
            architecture = info?.architecture,
            parameterCount = estimateParameters(file.length(), info),
            contextTrained = info?.contextLength,
            embeddingDim = info?.embeddingLength,
            chatTemplate = info?.chatTemplate,
            role = role,
            component = part,
            diffusionArch = if (effectiveKind == ModelKind.IMAGE) {
                DiffusionArchDetector.detect(info, file.name)
            } else {
                null
            },
        )
        dao.upsert(model.toEntity())
        Log.i(TAG, "registered ${model.displayName} (${model.sizeLabel})")
        model
    }

    /** Removes the row and, when [deleteFile] is set, the weights themselves. */
    suspend fun remove(id: String, deleteFile: Boolean = true) = withContext(Dispatchers.IO) {
        val row = dao.byId(id)
        dao.delete(id)
        if (deleteFile && row != null) {
            val f = File(row.filePath)
            if (f.exists() && f.absolutePath.startsWith(modelsDir.absolutePath)) f.delete()
        }
    }

    /**
     * Reconciles the database with what is actually on disk.
     *
     * Files can vanish (the user clearing app storage, a failed download being
     * cleaned up), and a row pointing at nothing produces a confusing "loading
     * failed" much later. Any `.gguf` found on disk but missing from the
     * database is adopted, which is also how sideloaded files get picked up.
     */
    suspend fun sync() = withContext(Dispatchers.IO) {
        for (row in dao.all()) {
            if (!File(row.filePath).exists()) {
                Log.w(TAG, "dropping ${row.displayName}: file is gone")
                dao.delete(row.id)
            }
        }
        val known = dao.all().map { it.filePath }.toSet()
        for (kind in ModelKind.entries) {
            val dir = dirFor(kind)
            dir.listFiles()?.forEach { f ->
                if (f.isFile && isWeightFile(f.name) && f.absolutePath !in known) {
                    runCatching { register(f, kind, repoId = null) }
                        .onFailure { Log.w(TAG, "could not adopt ${f.name}: ${it.message}") }
                }
            }
        }
    }

    /**
     * Files the engines can read. `.safetensors` matters for image pipelines:
     * the VAE and text encoder of a Qwen-Image or Z-Image repository are
     * published in that format, not as GGUF, and the diffusion engine loads
     * them directly.
     */
    fun isWeightFile(name: String): Boolean =
        name.endsWith(".gguf", true) ||
            name.endsWith(".safetensors", true) ||
            name.endsWith(".sft", true)

    /**
     * True once every shard of a split model sits beside [first].
     *
     * llama.cpp opens a split model through its first shard and finds the rest
     * by name, so registering before they have all arrived produces a model
     * that fails at load with nothing to explain why.
     */
    fun splitSetComplete(first: File): Boolean {
        val total = GgufRoleDetector.shardTotal(first.name) ?: return true
        val base = GgufRoleDetector.splitBaseName(first.name) ?: return true
        val ext = GgufRoleDetector.shardExtension(first.name) ?: "gguf"
        val dir = first.parentFile ?: return false
        return (1..total).all { index ->
            File(dir, "%s-%05d-of-%05d.%s".format(base, index, total, ext)).exists()
        }
    }

    /** Total bytes taken by installed weights. */
    suspend fun diskUsage(): Long = withContext(Dispatchers.IO) {
        dao.all().sumOf { File(it.filePath).let { f -> if (f.exists()) f.length() else 0L } }
    }

    /**
     * Parameter count, derived from file size and bits per weight when the
     * GGUF metadata does not state it outright.
     */
    private fun estimateParameters(sizeBytes: Long, info: GgufInspector.Info?): Long? {
        val bpw = info?.quantLabel?.let { Quant.fromFileName(it) }?.bitsPerWeight ?: return null
        if (bpw <= 0) return null
        return ((sizeBytes * 8) / bpw).toLong()
    }

    private fun ModelEntity.toModel() = InstalledModel(
        id = id,
        displayName = displayName,
        kind = runCatching { ModelKind.valueOf(kind) }.getOrDefault(ModelKind.TEXT),
        filePath = filePath,
        sizeBytes = sizeBytes,
        repoId = repoId,
        fileName = fileName,
        quant = runCatching { Quant.valueOf(quant) }.getOrDefault(Quant.UNKNOWN),
        architecture = architecture,
        parameterCount = parameterCount,
        contextTrained = contextTrained,
        embeddingDim = embeddingDim,
        chatTemplate = chatTemplate,
        role = runCatching { GgufRole.valueOf(role) }.getOrDefault(GgufRole.STANDALONE),
        component = component?.let { runCatching { ImageComponent.valueOf(it) }.getOrNull() },
        diffusionArch = diffusionArch?.let {
            runCatching { DiffusionArch.valueOf(it) }.getOrNull()
        },
        addedAtMillis = addedAt,
    )

    private fun InstalledModel.toEntity() = ModelEntity(
        id = id,
        displayName = displayName,
        kind = kind.name,
        filePath = filePath,
        sizeBytes = sizeBytes,
        repoId = repoId,
        fileName = fileName,
        quant = quant.name,
        architecture = architecture,
        parameterCount = parameterCount,
        contextTrained = contextTrained,
        embeddingDim = embeddingDim,
        chatTemplate = chatTemplate,
        role = role.name,
        component = component?.name,
        diffusionArch = diffusionArch?.name,
        addedAt = addedAtMillis,
    )

    private companion object {
        const val TAG = "LianModels"
    }
}
