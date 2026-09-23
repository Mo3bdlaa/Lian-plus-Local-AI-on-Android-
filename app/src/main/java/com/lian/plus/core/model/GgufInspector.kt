package com.lian.plus.core.model

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads the metadata block at the head of a GGUF file.
 *
 * This runs *before* the model is handed to llama.cpp so the UI can say what a
 * file actually is — architecture, parameter count, trained context, chat
 * template — without paying to load several gigabytes of weights first. Only
 * the key/value section is read; tensor data is never touched.
 */
object GgufInspector {

    private const val TAG = "LianGguf"
    private const val MAGIC = 0x46554747 // "GGUF" little-endian

    /** Enough to carry every family marker; the rest is noise. */
    private const val MAX_TENSOR_NAMES = 400L

    // ggml_type values used for the whole-file "file type" key.
    private val FILE_TYPE_NAMES = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0",
        8 to "Q5_0", 9 to "Q5_1", 10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M",
        13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S",
        17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS",
        23 to "IQ3_XXS", 25 to "IQ4_NL", 26 to "IQ3_S", 29 to "IQ4_XS",
    )

    data class Info(
        val version: Int,
        val tensorCount: Long,
        val architecture: String?,
        val name: String?,
        val quantLabel: String?,
        val contextLength: Int?,
        val embeddingLength: Int?,
        val blockCount: Int?,
        val headCount: Int?,
        val headCountKv: Int?,
        val ropeFreqBase: Float?,
        val chatTemplate: String?,
        val vocabSize: Int?,
        /** `split.count` — present only on the shards of a split model. */
        val splitCount: Int?,
        val metadata: Map<String, String>,
        /**
         * The first few tensor names, which for a diffusion checkpoint are the
         * only reliable way to tell the family: the Z-Image GGUFs carry no
         * key/value metadata whatsoever, and the engine itself identifies them
         * from these names.
         */
        val tensorNames: List<String> = emptyList(),
    ) {
        /**
         * Bytes of KV cache for [contextSize] tokens at [bytesPerElement] per
         * element. Grouped-query attention makes this far smaller than a naive
         * `n_embd` estimate, which is why `head_count_kv` matters here.
         */
        fun kvCacheBytes(contextSize: Int, bytesPerElement: Int = 2): Long? {
            val layers = blockCount ?: return null
            val embd = embeddingLength ?: return null
            val heads = headCount ?: return null
            val kvHeads = headCountKv ?: heads
            if (heads <= 0) return null
            val headDim = embd / heads
            return 2L * layers * kvHeads * headDim * contextSize * bytesPerElement
        }
    }

    fun inspect(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            // The metadata block is small but variable; 32 MB is well past any
            // real model's header and keeps a bad file from mapping the lot.
            val mapSize = minOf(raf.length(), 32L * 1024 * 1024)
            val buf = raf.channel
                .map(FileChannel.MapMode.READ_ONLY, 0, mapSize)
                .order(ByteOrder.LITTLE_ENDIAN)
            parse(buf)
        }
    }.onFailure { Log.w(TAG, "cannot read ${file.name}: ${it.message}") }.getOrNull()

    private fun parse(buf: ByteBuffer): Info? {
        if (buf.remaining() < 24) return null
        if (buf.int != MAGIC) return null

        val version = buf.int
        if (version !in 1..3) {
            Log.w(TAG, "unsupported GGUF version $version")
            return null
        }
        val tensorCount = buf.long
        val kvCount = buf.long
        if (kvCount < 0 || kvCount > 100_000) return null

        val kv = LinkedHashMap<String, Any>(kvCount.toInt().coerceAtMost(4096))
        for (i in 0 until kvCount) {
            val key = readString(buf) ?: break
            val value = readValue(buf, buf.int) ?: break
            kv[key] = value
        }

        val arch = kv["general.architecture"] as? String

        fun intFor(vararg suffixes: String): Int? {
            for (s in suffixes) {
                val direct = kv["$arch.$s"] ?: kv[s]
                if (direct != null) return (direct as? Number)?.toInt()
            }
            return null
        }

        val fileType = (kv["general.file_type"] as? Number)?.toInt()

        // Long strings (the chat template, tokenizer arrays) are not worth
        // holding for display — keep the short scalars only.
        val displayMeta = kv.entries
            .filter { it.value !is List<*> }
            .associate { (k, v) -> k to v.toString() }
            .filterValues { it.length <= 200 }

        // The tensor section follows the key/value block directly, so the
        // names cost one more pass over a few kilobytes. Only a prefix is
        // read: the markers that identify a family are all near the front, and
        // a 300-tensor model would otherwise add nothing but noise.
        val tensorNames = readTensorNames(buf, version, minOf(tensorCount, MAX_TENSOR_NAMES))

        return Info(
            version = version,
            tensorCount = tensorCount,
            architecture = arch,
            name = kv["general.name"] as? String,
            quantLabel = fileType?.let { FILE_TYPE_NAMES[it] ?: "type $it" },
            contextLength = intFor("context_length"),
            embeddingLength = intFor("embedding_length"),
            blockCount = intFor("block_count"),
            headCount = intFor("attention.head_count"),
            headCountKv = intFor("attention.head_count_kv"),
            ropeFreqBase = (kv["$arch.rope.freq_base"] as? Number)?.toFloat(),
            chatTemplate = kv["tokenizer.chat_template"] as? String,
            vocabSize = (kv["tokenizer.ggml.tokens"] as? List<*>)?.size
                ?: intFor("vocab_size"),
            splitCount = (kv["split.count"] as? Number)?.toInt(),
            metadata = displayMeta,
            tensorNames = tensorNames,
        )
    }

    /**
     * Walks the tensor directory, collecting names and skipping the rest.
     *
     * Each entry is: name, dimension count, that many dimensions, a type, and
     * an offset. Nothing here touches tensor *data* — the offsets point past
     * the header into the part of the file we never map.
     */
    private fun readTensorNames(buf: ByteBuffer, version: Int, count: Long): List<String> {
        val names = ArrayList<String>(count.toInt().coerceAtMost(512))
        for (i in 0 until count) {
            val name = readString(buf) ?: break
            if (buf.remaining() < 4) break
            val nDims = buf.int
            if (nDims < 0 || nDims > 8) break
            // GGUF v1 wrote dimensions as uint32; v2 onwards uses uint64.
            val dimBytes = if (version == 1) 4 * nDims else 8 * nDims
            if (buf.remaining() < dimBytes + 12) break
            buf.position(buf.position() + dimBytes)
            buf.int          // ggml type
            buf.long         // offset into the data section
            names += name
        }
        return names
    }

    private fun readString(buf: ByteBuffer): String? {
        if (buf.remaining() < 8) return null
        val len = buf.long
        if (len < 0 || len > buf.remaining()) return null
        val bytes = ByteArray(len.toInt())
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** GGUF value types, per the on-disk enum. */
    private fun readValue(buf: ByteBuffer, type: Int): Any? = when (type) {
        0 -> buf.get().toInt() and 0xFF          // uint8
        1 -> buf.get().toInt()                   // int8
        2 -> buf.short.toInt() and 0xFFFF        // uint16
        3 -> buf.short.toInt()                   // int16
        4 -> buf.int.toLong() and 0xFFFFFFFFL    // uint32
        5 -> buf.int                             // int32
        6 -> buf.float                           // float32
        7 -> buf.get().toInt() != 0              // bool
        8 -> readString(buf)                     // string
        9 -> readArray(buf)                      // array
        10 -> buf.long                           // uint64
        11 -> buf.long                           // int64
        12 -> buf.double                         // float64
        else -> null
    }

    private fun readArray(buf: ByteBuffer): List<Any>? {
        if (buf.remaining() < 12) return null
        val elemType = buf.int
        val count = buf.long
        if (count < 0 || count > 10_000_000) return null

        // Token vocabularies run to hundreds of thousands of strings; we only
        // need the count, so skip the payload instead of materialising it.
        if (elemType == 8 && count > 2048) {
            var skipped = 0L
            while (skipped < count) {
                if (buf.remaining() < 8) return null
                val len = buf.long
                if (len < 0 || len > buf.remaining()) return null
                buf.position(buf.position() + len.toInt())
                skipped++
            }
            return List(count.toInt()) { "" }
        }

        val out = ArrayList<Any>(count.toInt().coerceAtMost(4096))
        for (i in 0 until count) {
            out += readValue(buf, elemType) ?: return out
        }
        return out
    }
}
