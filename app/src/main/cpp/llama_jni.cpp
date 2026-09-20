// JNI bridge to llama.cpp.
//
// Everything the Kotlin side needs is funnelled through a small number of
// entry points; the interesting parts are:
//   * a per-context record of which tokens are already in the KV cache, so a
//     follow-up turn only has to decode the new suffix (prefix caching),
//   * UTF-8 aware streaming, holding back the tail of a multi-byte character
//     until the bytes that complete it arrive,
//   * cooperative cancellation that works both from the callback's return
//     value and from another thread.

#include "jni_util.h"

#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

using namespace lian;

namespace {

struct lian_ctx {
    llama_model *       model = nullptr;
    llama_context *     ctx   = nullptr;
    const llama_vocab * vocab = nullptr;

    // Tokens currently held in the KV cache for sequence 0.
    std::vector<llama_token> kv;

    std::atomic<bool> cancel{false};
    std::mutex        busy;
};

lian_ctx * as_ctx(jlong h) { return reinterpret_cast<lian_ctx *>(h); }
llama_model * as_model(jlong h) { return reinterpret_cast<llama_model *>(h); }

std::atomic<bool> g_backend_ready{false};

void log_cb(ggml_log_level level, const char *text, void *) {
    if (text == nullptr) return;
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    __android_log_write(prio, LIAN_LOG_TAG, text);
}

std::string piece_for(const llama_vocab *vocab, llama_token tok, bool special) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, special);
    if (n >= 0) return std::string(buf, static_cast<size_t>(n));
    std::vector<char> big(static_cast<size_t>(-n));
    n = llama_token_to_piece(vocab, tok, big.data(), static_cast<int32_t>(big.size()), 0, special);
    if (n < 0) return {};
    return std::string(big.data(), static_cast<size_t>(n));
}

ggml_type cache_type(jint v) {
    switch (v) {
        case 1:  return GGML_TYPE_Q8_0;
        case 2:  return GGML_TYPE_Q4_0;
        default: return GGML_TYPE_F16;
    }
}

// Decodes `tokens` starting at position `pos0` into sequence 0, in chunks no
// larger than the context's logical batch size. Only the very last token of the
// whole run is asked for logits. Returns false on decode failure/cancellation.
bool decode_tokens(lian_ctx *c,
                   const std::vector<llama_token> &tokens,
                   int pos0,
                   bool logits_for_last,
                   JNIEnv *env,
                   jobject callback,
                   jmethodID on_prefill) {
    if (tokens.empty()) return true;

    const int n_batch = static_cast<int>(llama_n_batch(c->ctx));
    const int total   = static_cast<int>(tokens.size());

    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    bool ok = true;

    for (int i = 0; i < total && ok; i += n_batch) {
        const int n = std::min(n_batch, total - i);
        batch.n_tokens = n;
        for (int k = 0; k < n; ++k) {
            batch.token[k]     = tokens[i + k];
            batch.pos[k]       = pos0 + i + k;
            batch.n_seq_id[k]  = 1;
            batch.seq_id[k][0] = 0;
            batch.logits[k]    = 0;
        }
        const bool is_last_chunk = (i + n >= total);
        if (is_last_chunk && logits_for_last) batch.logits[n - 1] = 1;

        if (c->cancel.load()) { ok = false; break; }

        const int rc = llama_decode(c->ctx, batch);
        if (rc != 0) {
            LIANE("llama_decode failed with %d at pos %d", rc, pos0 + i);
            ok = false;
            break;
        }
        if (callback != nullptr && on_prefill != nullptr) {
            env->CallVoidMethod(callback, on_prefill, i + n, total);
            if (env->ExceptionCheck()) { env->ExceptionClear(); ok = false; break; }
        }
    }

    llama_batch_free(batch);
    return ok;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_lian_plus_llm_LlamaNative_backendInit(JNIEnv *, jobject) {
    if (g_backend_ready.exchange(true)) return;
    llama_log_set(log_cb, nullptr);
    llama_backend_init();
}

JNIEXPORT jstring JNICALL
Java_com_lian_plus_llm_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return to_jstring(env, llama_print_system_info());
}

/**
 * Enumerates the compute devices ggml found, one per line:
 *   name|description|type|freeBytes|totalBytes
 *
 * The app needs this before it can offer GPU offload honestly: a build with
 * the Vulkan backend compiled in still means nothing if the phone's driver
 * refuses to enumerate a device, and the only way to know is to ask.
 */
JNIEXPORT jstring JNICALL
Java_com_lian_plus_llm_LlamaNative_backendDevices(JNIEnv *env, jobject) {
    std::string out;
    const size_t n = ggml_backend_dev_count();
    for (size_t i = 0; i < n; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;

        size_t free_bytes = 0;
        size_t total_bytes = 0;
        ggml_backend_dev_memory(dev, &free_bytes, &total_bytes);

        const char *name = ggml_backend_dev_name(dev);
        const char *desc = ggml_backend_dev_description(dev);

        const char *type = "cpu";
        switch (ggml_backend_dev_type(dev)) {
            case GGML_BACKEND_DEVICE_TYPE_GPU:   type = "gpu";   break;
            case GGML_BACKEND_DEVICE_TYPE_IGPU:  type = "igpu";  break;
            case GGML_BACKEND_DEVICE_TYPE_ACCEL: type = "accel"; break;
            default: break;
        }

        out += name ? name : "?";
        out += '|';
        out += desc ? desc : "";
        out += '|';
        out += type;
        out += '|';
        out += std::to_string(free_bytes);
        out += '|';
        out += std::to_string(total_bytes);
        out += '\n';
    }
    return to_jstring(env, out);
}

/** True when this library was compiled with the Vulkan backend at all. */
JNIEXPORT jboolean JNICALL
Java_com_lian_plus_llm_LlamaNative_hasVulkanSupport(JNIEnv *, jobject) {
#ifdef LIAN_HAS_VULKAN
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/**
 * Time a square matmul on one backend device and report GFLOP/s.
 *
 * The point is not a benchmark score for its own sake: it is the one honest
 * way to answer "is this phone's GPU worth using for this?". Vendor names and
 * driver version strings say nothing useful - two devices with the same Adreno
 * generation differ by a factor of three depending on the ROM - and a driver
 * that enumerates a compute device may still be slower than the CPU it shares
 * a memory bus with.
 *
 * ggml_mul_mat with F16 weights against an F32 activation is the operation
 * inference actually spends its time in, so the number transfers.
 *
 * Returns GFLOP/s, or -1 when the device cannot run the graph at all - which
 * is itself a result worth storing, since it means the GPU must not be
 * offered however good it looks on paper.
 */
JNIEXPORT jdouble JNICALL
Java_com_lian_plus_llm_LlamaNative_benchmarkMatmul(
        JNIEnv *, jobject, jint device_index, jint dim, jint threads, jint budget_ms) {

    if (device_index < 0 || (size_t) device_index >= ggml_backend_dev_count()) return -1.0;

    const int64_t n = std::max(64, (int) dim);

    ggml_backend_dev_t dev = ggml_backend_dev_get((size_t) device_index);
    if (dev == nullptr) return -1.0;

    ggml_backend_t backend = ggml_backend_dev_init(dev, nullptr);
    if (backend == nullptr) return -1.0;

    if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU && threads > 0) {
        ggml_backend_cpu_set_n_threads(backend, threads);
    }

    double gflops = -1.0;

    ggml_init_params params = {
        /*.mem_size   =*/ ggml_tensor_overhead() * 8 + ggml_graph_overhead(),
        /*.mem_buffer =*/ nullptr,
        /*.no_alloc   =*/ true,
    };
    ggml_context * ctx = ggml_init(params);
    if (ctx == nullptr) {
        ggml_backend_free(backend);
        return -1.0;
    }

    ggml_tensor * a = ggml_new_tensor_2d(ctx, GGML_TYPE_F16, n, n);
    ggml_tensor * b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, n, n);
    ggml_tensor * c = ggml_mul_mat(ctx, a, b);

    ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors(ctx, backend);
    if (buf != nullptr) {
        // Small non-zero values: denormals and NaNs can take slow paths, and a
        // buffer of zeros invites a driver to skip work altogether.
        std::vector<ggml_fp16_t> a_host((size_t) (n * n), ggml_fp32_to_fp16(0.0125f));
        std::vector<float>       b_host((size_t) (n * n), 0.25f);
        ggml_backend_tensor_set(a, a_host.data(), 0, ggml_nbytes(a));
        ggml_backend_tensor_set(b, b_host.data(), 0, ggml_nbytes(b));

        ggml_cgraph * graph = ggml_new_graph(ctx);
        ggml_build_forward_expand(graph, c);

        // One untimed pass: the first dispatch pays for shader compilation and
        // buffer residency, which is startup cost, not throughput.
        const bool warm_ok =
            ggml_backend_graph_compute(backend, graph) == GGML_STATUS_SUCCESS;
        ggml_backend_synchronize(backend);

        if (warm_ok) {
            const int    budget = budget_ms > 0 ? budget_ms : 250;
            const double flops_per_pass = 2.0 * (double) n * (double) n * (double) n;

            const auto started = std::chrono::steady_clock::now();
            int passes = 0;
            bool ok = true;
            while (ok) {
                ok = ggml_backend_graph_compute(backend, graph) == GGML_STATUS_SUCCESS;
                ++passes;
                ggml_backend_synchronize(backend);
                const auto elapsed = std::chrono::steady_clock::now() - started;
                if (std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count()
                        >= budget) {
                    break;
                }
            }
            ggml_backend_synchronize(backend);
            const double seconds = std::chrono::duration<double>(
                std::chrono::steady_clock::now() - started).count();

            if (ok && seconds > 0.0) {
                gflops = (flops_per_pass * passes) / seconds / 1e9;
            }
        }

        ggml_backend_buffer_free(buf);
    }

    ggml_free(ctx);
    ggml_backend_free(backend);
    return gflops;
}

JNIEXPORT jlong JNICALL
Java_com_lian_plus_llm_LlamaNative_loadModel(JNIEnv *env, jobject, jstring jpath,
                                             jint n_gpu_layers, jboolean use_mmap,
                                             jboolean use_mlock, jobject progress) {
    const std::string path = to_string(env, jpath);

    struct prog_state {
        JNIEnv *  env;
        jobject   obj;
        jmethodID method;
    };
    prog_state ps{env, progress, nullptr};
    if (progress != nullptr) {
        jclass cls = env->GetObjectClass(progress);
        ps.method = env->GetMethodID(cls, "onProgress", "(F)Z");
        env->DeleteLocalRef(cls);
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    // mmap keeps the weights out of the app's dirty RSS, which is what lets a
    // 4 GB model coexist with Android's per-app memory limits. mlock pins them
    // so the kernel cannot evict pages under pressure - only worth it when the
    // caller has already checked there is headroom.
    if (use_mmap == JNI_TRUE) {
        mp.load_mode = use_mlock == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP;
    } else {
        mp.load_mode = use_mlock == JNI_TRUE ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE;
    }
    if (ps.method != nullptr) {
        mp.progress_callback_user_data = &ps;
        mp.progress_callback = [](float p, void *ud) -> bool {
            auto *s = static_cast<prog_state *>(ud);
            const jboolean keep = s->env->CallBooleanMethod(s->obj, s->method, p);
            if (s->env->ExceptionCheck()) { s->env->ExceptionClear(); return false; }
            return keep == JNI_TRUE;
        };
    }

    llama_model *m = llama_model_load_from_file(path.c_str(), mp);
    if (m == nullptr) {
        LIANE("failed to load model: %s", path.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT void JNICALL
Java_com_lian_plus_llm_LlamaNative_freeModel(JNIEnv *, jobject, jlong h) {
    if (h != 0) llama_model_free(as_model(h));
}

JNIEXPORT jstring JNICALL
Java_com_lian_plus_llm_LlamaNative_modelDesc(JNIEnv *env, jobject, jlong h) {
    char buf[512] = {0};
    if (h != 0) llama_model_desc(as_model(h), buf, sizeof(buf));
    return to_jstring(env, buf);
}

JNIEXPORT jlong JNICALL
Java_com_lian_plus_llm_LlamaNative_modelParamCount(JNIEnv *, jobject, jlong h) {
    return h == 0 ? 0 : static_cast<jlong>(llama_model_n_params(as_model(h)));
}

JNIEXPORT jlong JNICALL
Java_com_lian_plus_llm_LlamaNative_modelSizeBytes(JNIEnv *, jobject, jlong h) {
    return h == 0 ? 0 : static_cast<jlong>(llama_model_size(as_model(h)));
}

JNIEXPORT jint JNICALL
Java_com_lian_plus_llm_LlamaNative_modelNCtxTrain(JNIEnv *, jobject, jlong h) {
    return h == 0 ? 0 : llama_model_n_ctx_train(as_model(h));
}

JNIEXPORT jint JNICALL
Java_com_lian_plus_llm_LlamaNative_modelNEmbd(JNIEnv *, jobject, jlong h) {
    return h == 0 ? 0 : llama_model_n_embd(as_model(h));
}

JNIEXPORT jstring JNICALL
Java_com_lian_plus_llm_LlamaNative_modelMeta(JNIEnv *env, jobject, jlong h, jstring jkey) {
    if (h == 0) return nullptr;
    const std::string key = to_string(env, jkey);
    char buf[1024] = {0};
    const int n = llama_model_meta_val_str(as_model(h), key.c_str(), buf, sizeof(buf));
    if (n < 0) return nullptr;
    return to_jstring(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_lian_plus_llm_LlamaNative_modelChatTemplate(JNIEnv *env, jobject, jlong h) {
    if (h == 0) return nullptr;
    const char *tmpl = llama_model_chat_template(as_model(h), nullptr);
    if (tmpl == nullptr) return nullptr;
    return to_jstring(env, tmpl);
}

JNIEXPORT jlong JNICALL
Java_com_lian_plus_llm_LlamaNative_createContext(JNIEnv *env, jobject, jlong model_h,
                                                 jint n_ctx, jint n_batch, jint n_ubatch,
                                                 jint n_threads, jint flash_attn,
                                                 jboolean embeddings, jint pooling,
                                                 jint type_k, jint type_v) {
    if (model_h == 0) { throw_ise(env, "model handle is null"); return 0; }
    llama_model *model = as_model(model_h);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(n_ctx);
    cp.n_batch         = static_cast<uint32_t>(n_batch);
    cp.n_ubatch        = static_cast<uint32_t>(n_ubatch);
    cp.n_seq_max       = 1;
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;
    cp.embeddings      = embeddings == JNI_TRUE;
    cp.type_k          = cache_type(type_k);
    cp.type_v          = cache_type(type_v);
    cp.flash_attn_type = flash_attn < 0 ? LLAMA_FLASH_ATTN_TYPE_AUTO
                       : (flash_attn > 0 ? LLAMA_FLASH_ATTN_TYPE_ENABLED
                                         : LLAMA_FLASH_ATTN_TYPE_DISABLED);
    if (embeddings == JNI_TRUE) {
        cp.pooling_type = pooling < 0 ? LLAMA_POOLING_TYPE_UNSPECIFIED
                                      : static_cast<enum llama_pooling_type>(pooling);
    }

    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LIANE("llama_init_from_model failed (n_ctx=%d)", n_ctx);
        return 0;
    }

    auto *c = new lian_ctx();
    c->model = model;
    c->ctx   = ctx;
    c->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_com_lian_plus_llm_LlamaNative_freeContext(JNIEnv *, jobject, jlong h) {
    if (h == 0) return;
    lian_ctx *c = as_ctx(h);
    c->cancel.store(true);
    {
        std::lock_guard<std::mutex> lock(c->busy);
        llama_free(c->ctx);
        c->ctx = nullptr;
    }
    delete c;
}

JNIEXPORT jint JNICALL
Java_com_lian_plus_llm_LlamaNative_contextSize(JNIEnv *, jobject, jlong h) {
    return h == 0 ? 0 : static_cast<jint>(llama_n_ctx(as_ctx(h)->ctx));
}

JNIEXPORT jint JNICALL
Java_com_lian_plus_llm_LlamaNative_cachedTokenCount(JNIEnv *, jobject, jlong h) {
    return h == 0 ? 0 : static_cast<jint>(as_ctx(h)->kv.size());
}

JNIEXPORT void JNICALL
Java_com_lian_plus_llm_LlamaNative_resetContext(JNIEnv *, jobject, jlong h) {
    if (h == 0) return;
    lian_ctx *c = as_ctx(h);
    std::lock_guard<std::mutex> lock(c->busy);
    llama_memory_clear(llama_get_memory(c->ctx), true);
    c->kv.clear();
}

JNIEXPORT void JNICALL
Java_com_lian_plus_llm_LlamaNative_requestCancel(JNIEnv *, jobject, jlong h) {
    if (h != 0) as_ctx(h)->cancel.store(true);
}

JNIEXPORT jintArray JNICALL
Java_com_lian_plus_llm_LlamaNative_tokenize(JNIEnv *env, jobject, jlong h, jstring jtext,
                                            jboolean add_special, jboolean parse_special) {
    if (h == 0) return to_jint_array(env, {});
    lian_ctx *c = as_ctx(h);
    const std::string text = to_string(env, jtext);

    int cap = static_cast<int>(text.size()) + 16;
    std::vector<llama_token> out(static_cast<size_t>(cap));
    int n = llama_tokenize(c->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                           out.data(), cap, add_special == JNI_TRUE, parse_special == JNI_TRUE);
    if (n < 0) {
        out.resize(static_cast<size_t>(-n));
        n = llama_tokenize(c->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                           out.data(), static_cast<int32_t>(out.size()),
                           add_special == JNI_TRUE, parse_special == JNI_TRUE);
        if (n < 0) return to_jint_array(env, {});
    }
    out.resize(static_cast<size_t>(n));
    return to_jint_array(env, std::vector<int32_t>(out.begin(), out.end()));
}

JNIEXPORT jstring JNICALL
Java_com_lian_plus_llm_LlamaNative_detokenize(JNIEnv *env, jobject, jlong h,
                                              jintArray jtokens, jboolean special) {
    if (h == 0) return to_jstring(env, "");
    lian_ctx *c = as_ctx(h);
    const std::vector<int32_t> toks = to_int_vector(env, jtokens);
    std::string out;
    for (int32_t t : toks) out += piece_for(c->vocab, t, special == JNI_TRUE);
    return to_jstring(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_lian_plus_llm_LlamaNative_applyChatTemplate(JNIEnv *env, jobject, jlong model_h,
                                                     jstring jtmpl, jobjectArray jroles,
                                                     jobjectArray jcontents,
                                                     jboolean add_assistant) {
    const jsize n = env->GetArrayLength(jroles);

    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        auto r = reinterpret_cast<jstring>(env->GetObjectArrayElement(jroles, i));
        auto c = reinterpret_cast<jstring>(env->GetObjectArrayElement(jcontents, i));
        roles[i]    = to_string(env, r);
        contents[i] = to_string(env, c);
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
        msgs[i].role    = roles[i].c_str();
        msgs[i].content = contents[i].c_str();
        total += roles[i].size() + contents[i].size();
    }

    std::string tmpl_owned;
    const char *tmpl = nullptr;
    if (jtmpl != nullptr) {
        tmpl_owned = to_string(env, jtmpl);
        if (!tmpl_owned.empty()) tmpl = tmpl_owned.c_str();
    }
    if (tmpl == nullptr && model_h != 0) {
        tmpl = llama_model_chat_template(as_model(model_h), nullptr);
    }
    if (tmpl == nullptr) return nullptr; // caller falls back to its own formatter

    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                            add_assistant == JNI_TRUE,
                                            buf.data(), static_cast<int32_t>(buf.size()));
    if (len > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(len) + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                        add_assistant == JNI_TRUE,
                                        buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len < 0) return nullptr;
    return to_jstring(env, std::string(buf.data(), static_cast<size_t>(len)));
}

JNIEXPORT jint JNICALL
Java_com_lian_plus_llm_LlamaNative_generate(JNIEnv *env, jobject, jlong h,
                                            jintArray jprompt, jint max_tokens,
                                            jfloat temperature, jint top_k, jfloat top_p,
                                            jfloat min_p, jfloat repeat_penalty,
                                            jint repeat_last_n, jfloat freq_penalty,
                                            jfloat presence_penalty, jint seed,
                                            jstring jgrammar, jintArray jstop_tokens,
                                            jobject callback) {
    if (h == 0) { throw_ise(env, "context handle is null"); return -1; }
    lian_ctx *c = as_ctx(h);

    std::unique_lock<std::mutex> lock(c->busy, std::try_to_lock);
    if (!lock.owns_lock()) { throw_ise(env, "context is already generating"); return -1; }
    c->cancel.store(false);

    jclass cb_cls       = env->GetObjectClass(callback);
    jmethodID on_token  = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;I)Z");
    jmethodID on_prefill= env->GetMethodID(cb_cls, "onPrefill", "(II)V");
    env->DeleteLocalRef(cb_cls);
    if (on_token == nullptr) { throw_ise(env, "callback is missing onToken"); return -1; }

    const std::vector<int32_t> prompt_i = to_int_vector(env, jprompt);
    std::vector<llama_token> prompt(prompt_i.begin(), prompt_i.end());
    if (prompt.empty()) { throw_ise(env, "prompt is empty"); return -1; }

    const int n_ctx = static_cast<int>(llama_n_ctx(c->ctx));
    if (static_cast<int>(prompt.size()) >= n_ctx) {
        throw_ise(env, "prompt (" + std::to_string(prompt.size()) +
                       " tokens) does not fit in the context window (" +
                       std::to_string(n_ctx) + ")");
        return -1;
    }

    // Reuse whatever prefix is already in the KV cache and drop the rest.
    size_t common = 0;
    while (common < c->kv.size() && common < prompt.size() && c->kv[common] == prompt[common]) {
        ++common;
    }
    // At least one token has to be decoded for fresh logits.
    if (common == prompt.size() && common > 0) --common;

    llama_memory_t mem = llama_get_memory(c->ctx);
    if (common < c->kv.size()) {
        llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1);
        c->kv.resize(common);
    }

    const std::vector<llama_token> to_decode(prompt.begin() + static_cast<long>(common),
                                             prompt.end());
    if (!decode_tokens(c, to_decode, static_cast<int>(common), true, env, callback, on_prefill)) {
        c->kv.clear();
        llama_memory_clear(mem, true);
        if (c->cancel.load()) return 0;
        throw_ise(env, "prompt evaluation failed");
        return -1;
    }
    c->kv = prompt;

    // ---- sampler chain -------------------------------------------------
    llama_sampler_chain_params scp = llama_sampler_chain_default_params();
    scp.no_perf = true;
    llama_sampler *chain = llama_sampler_chain_init(scp);

    const std::string grammar = jgrammar == nullptr ? std::string() : to_string(env, jgrammar);
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(c->vocab, grammar.c_str(), "root");
        if (g != nullptr) llama_sampler_chain_add(chain, g);
        else LIANW("grammar failed to compile - ignoring it");
    }

    if (repeat_penalty != 1.0f || freq_penalty != 0.0f || presence_penalty != 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(
            llama_vocab_n_tokens(c->vocab), repeat_last_n,
            repeat_penalty, freq_penalty, presence_penalty));
    }

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (top_k > 0)              llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
        if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        if (min_p > 0.0f)           llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(
            seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
    }

    // Let the penalty samplers see the prompt.
    for (llama_token t : prompt) llama_sampler_accept(chain, t);

    const std::vector<int32_t> stop_tokens = to_int_vector(env, jstop_tokens);

    // ---- decode loop ---------------------------------------------------
    std::string pending;   // bytes not yet handed to Kotlin (partial UTF-8)
    int n_generated = 0;
    int n_past = static_cast<int>(prompt.size());

    llama_batch batch = llama_batch_init(1, 0, 1);

    while (n_generated < max_tokens && n_past < n_ctx) {
        if (c->cancel.load()) break;

        const llama_token id = llama_sampler_sample(chain, c->ctx, -1);

        if (llama_vocab_is_eog(c->vocab, id)) break;
        bool hit_stop = false;
        for (int32_t s : stop_tokens) if (s == id) { hit_stop = true; break; }
        if (hit_stop) break;

        pending += piece_for(c->vocab, id, false);
        const size_t hold = incomplete_utf8_tail(pending);
        if (pending.size() > hold) {
            const std::string emit = pending.substr(0, pending.size() - hold);
            pending = pending.substr(pending.size() - hold);

            jstring js = to_jstring(env, emit);
            const jboolean keep = env->CallBooleanMethod(callback, on_token, js, id);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            if (keep != JNI_TRUE) break;
        }

        ++n_generated;

        batch.n_tokens    = 1;
        batch.token[0]    = id;
        batch.pos[0]      = n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0]= 0;
        batch.logits[0]   = 1;

        if (llama_decode(c->ctx, batch) != 0) {
            LIANE("llama_decode failed during generation at pos %d", n_past);
            break;
        }
        // Recorded only now: if the decode had failed, the token would be in
        // our mirror but not in the cache, and the next call's prefix match
        // would reuse state that does not exist.
        c->kv.push_back(id);
        ++n_past;
    }

    // Flush whatever is left, even if it is a broken sequence - sanitize_utf8
    // turns it into U+FFFD rather than dropping the user's last character.
    if (!pending.empty()) {
        jstring js = to_jstring(env, pending);
        env->CallBooleanMethod(callback, on_token, js, -1);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    llama_batch_free(batch);
    llama_sampler_free(chain);
    return n_generated;
}

JNIEXPORT jfloatArray JNICALL
Java_com_lian_plus_llm_LlamaNative_embed(JNIEnv *env, jobject, jlong h, jintArray jtokens) {
    if (h == 0) return to_jfloat_array(env, {});
    lian_ctx *c = as_ctx(h);
    std::lock_guard<std::mutex> lock(c->busy);

    const std::vector<int32_t> toks_i = to_int_vector(env, jtokens);
    if (toks_i.empty()) return to_jfloat_array(env, {});
    const std::vector<llama_token> toks(toks_i.begin(), toks_i.end());

    llama_memory_clear(llama_get_memory(c->ctx), true);
    c->kv.clear();

    if (!decode_tokens(c, toks, 0, true, env, nullptr, nullptr)) {
        return to_jfloat_array(env, {});
    }

    const int n_embd = llama_model_n_embd(c->model);
    const float *src = llama_get_embeddings_seq(c->ctx, 0);
    if (src == nullptr) src = llama_get_embeddings_ith(c->ctx, -1);
    if (src == nullptr) return to_jfloat_array(env, {});

    // L2-normalise so callers can use a plain dot product as cosine similarity.
    std::vector<float> out(src, src + n_embd);
    double norm = 0.0;
    for (float v : out) norm += static_cast<double>(v) * v;
    norm = norm > 0.0 ? 1.0 / std::sqrt(norm) : 0.0;
    for (float &v : out) v = static_cast<float>(v * norm);

    llama_memory_clear(llama_get_memory(c->ctx), true);
    return to_jfloat_array(env, out);
}

} // extern "C"
