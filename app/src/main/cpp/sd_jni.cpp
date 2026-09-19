// JNI bridge to stable-diffusion.cpp.
//
// This library is loaded only by the :imagegen process (see ImageGenService).
// Keeping it out of the main process means the huge transient allocations a
// diffusion run makes are reclaimed by the OS the moment that process is
// killed, and it removes any chance of its statically linked ggml meeting the
// copy inside lian_llm.

#include "jni_util.h"

#include "stable-diffusion.h"

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

using namespace lian;

namespace {

struct lian_sd {
    sd_ctx_t *  ctx = nullptr;
    std::string model_path;
    std::mutex  busy;
};

lian_sd * as_sd(jlong h) { return reinterpret_cast<lian_sd *>(h); }

// The progress callback is global in stable-diffusion.cpp, so the Java side of
// it has to be too. Only one generation runs at a time inside this process,
// which the per-context mutex enforces.
struct progress_target {
    JavaVM *  vm     = nullptr;
    jobject   obj    = nullptr;  // global ref
    jmethodID method = nullptr;
};
progress_target g_progress;
std::mutex g_progress_mu;

void sd_log(enum sd_log_level_t level, const char *text, void *) {
    if (text == nullptr) return;
    int prio = ANDROID_LOG_INFO;
    if (level == SD_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == SD_LOG_WARN) prio = ANDROID_LOG_WARN;
    else if (level == SD_LOG_DEBUG) prio = ANDROID_LOG_DEBUG;
    __android_log_write(prio, LIAN_LOG_TAG, text);
}

void sd_progress(int step, int steps, float time, void *) {
    std::lock_guard<std::mutex> lock(g_progress_mu);
    if (g_progress.vm == nullptr || g_progress.obj == nullptr) return;
    JNIEnv *env = nullptr;
    if (g_progress.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return;
    env->CallVoidMethod(g_progress.obj, g_progress.method, step, steps, time);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

enum sample_method_t to_sample_method(jint v) {
    if (v < 0 || v >= SAMPLE_METHOD_COUNT) return EULER_A_SAMPLE_METHOD;
    return static_cast<enum sample_method_t>(v);
}

enum scheduler_t to_scheduler(jint v) {
    if (v < 0 || v >= SCHEDULER_COUNT) return DISCRETE_SCHEDULER;
    return static_cast<enum scheduler_t>(v);
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_lian_plus_image_SdNative_systemInfo(JNIEnv *env, jobject) {
    return to_jstring(env, sd_get_system_info());
}

JNIEXPORT jint JNICALL
Java_com_lian_plus_image_SdNative_physicalCores(JNIEnv *, jobject) {
    return sd_get_num_physical_cores();
}

JNIEXPORT jlong JNICALL
Java_com_lian_plus_image_SdNative_loadContext(JNIEnv *env, jobject,
                                              jstring jmodel, jstring jvae, jstring jtaesd,
                                              jstring jclipL, jstring jclipG, jstring jt5,
                                              jstring jdiffusion, jint nThreads, jint wtype,
                                              jboolean flashAttn, jboolean convDirect,
                                              jboolean mmap) {
    sd_set_log_callback(sd_log, nullptr);
    sd_set_progress_callback(sd_progress, nullptr);

    const std::string model     = to_string(env, jmodel);
    const std::string vae       = to_string(env, jvae);
    const std::string taesd     = to_string(env, jtaesd);
    const std::string clip_l    = to_string(env, jclipL);
    const std::string clip_g    = to_string(env, jclipG);
    const std::string t5        = to_string(env, jt5);
    const std::string diffusion = to_string(env, jdiffusion);

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    auto opt = [](const std::string &s) { return s.empty() ? nullptr : s.c_str(); };
    p.model_path            = opt(model);
    p.vae_path              = opt(vae);
    p.taesd_path            = opt(taesd);
    p.clip_l_path           = opt(clip_l);
    p.clip_g_path           = opt(clip_g);
    p.t5xxl_path            = opt(t5);
    p.diffusion_model_path  = opt(diffusion);
    p.n_threads             = nThreads;
    p.wtype                 = wtype < 0 ? SD_TYPE_COUNT : static_cast<enum sd_type_t>(wtype);
    p.enable_mmap           = mmap == JNI_TRUE;
    p.flash_attn            = flashAttn == JNI_TRUE;
    p.diffusion_flash_attn  = flashAttn == JNI_TRUE;
    // Direct convolution trades a little speed for a much smaller peak working
    // set, which is the difference between finishing and being OOM-killed on a
    // phone.
    p.diffusion_conv_direct = convDirect == JNI_TRUE;
    p.vae_conv_direct       = convDirect == JNI_TRUE;

    sd_ctx_t *ctx = new_sd_ctx(&p);
    if (ctx == nullptr) {
        LIANE("new_sd_ctx failed for %s", model.c_str());
        return 0;
    }
    auto *s = new lian_sd();
    s->ctx = ctx;
    s->model_path = model;
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_lian_plus_image_SdNative_freeContext(JNIEnv *, jobject, jlong h) {
    if (h == 0) return;
    lian_sd *s = as_sd(h);
    {
        std::lock_guard<std::mutex> lock(s->busy);
        free_sd_ctx(s->ctx);
        s->ctx = nullptr;
    }
    delete s;
}

JNIEXPORT jstring JNICALL
Java_com_lian_plus_image_SdNative_modelVersion(JNIEnv *env, jobject, jlong h) {
    if (h == 0) return to_jstring(env, "");
    return to_jstring(env, sd_get_model_version_name(as_sd(h)->ctx));
}

JNIEXPORT void JNICALL
Java_com_lian_plus_image_SdNative_cancel(JNIEnv *, jobject, jlong h) {
    if (h == 0) return;
    // Deliberately not taking `busy`: the point is to interrupt the thread
    // that currently holds it.
    sd_cancel_generation(as_sd(h)->ctx, SD_CANCEL_ALL);
}

// Returns an int[] laid out as [width, height, channels, pixel bytes...] for
// the first generated image, or null on failure.
JNIEXPORT jbyteArray JNICALL
Java_com_lian_plus_image_SdNative_txt2img(JNIEnv *env, jobject, jlong h,
                                          jstring jprompt, jstring jnegative,
                                          jint width, jint height, jint steps,
                                          jfloat cfg, jlong seed, jint sampleMethod,
                                          jint scheduler, jint clipSkip,
                                          jbyteArray jinitRgb, jint initW, jint initH,
                                          jfloat strength, jobject progress,
                                          jintArray jdims) {
    if (h == 0) { throw_ise(env, "sd context is null"); return nullptr; }
    lian_sd *s = as_sd(h);

    std::unique_lock<std::mutex> lock(s->busy, std::try_to_lock);
    if (!lock.owns_lock()) { throw_ise(env, "a generation is already running"); return nullptr; }

    // Publish the progress callback for the duration of this call.
    {
        std::lock_guard<std::mutex> g(g_progress_mu);
        if (progress != nullptr) {
            env->GetJavaVM(&g_progress.vm);
            g_progress.obj = env->NewGlobalRef(progress);
            jclass cls = env->GetObjectClass(progress);
            g_progress.method = env->GetMethodID(cls, "onStep", "(IIF)V");
            env->DeleteLocalRef(cls);
        }
    }

    const std::string prompt   = to_string(env, jprompt);
    const std::string negative = to_string(env, jnegative);

    sd_img_gen_params_t g;
    sd_img_gen_params_init(&g);
    g.prompt          = prompt.c_str();
    g.negative_prompt = negative.c_str();
    g.width           = width;
    g.height          = height;
    g.clip_skip       = clipSkip;
    g.seed            = seed;
    g.batch_count     = 1;
    g.sample_params.sample_steps         = steps;
    g.sample_params.guidance.txt_cfg     = cfg;
    g.sample_params.sample_method        = to_sample_method(sampleMethod);
    g.sample_params.scheduler            = to_scheduler(scheduler);
    // Tiled VAE decoding keeps the decode step's peak allocation bounded; at
    // 512-1024 px on a phone this is what stops the OOM killer firing.
    g.vae_tiling_params.enabled        = true;
    g.vae_tiling_params.tile_size_x    = 32;
    g.vae_tiling_params.tile_size_y    = 32;
    g.vae_tiling_params.target_overlap = 0.5f;

    // Optional img2img input (RGB, 3 bytes per pixel).
    std::vector<uint8_t> init_pixels;
    if (jinitRgb != nullptr && initW > 0 && initH > 0) {
        const jsize n = env->GetArrayLength(jinitRgb);
        init_pixels.resize(static_cast<size_t>(n));
        env->GetByteArrayRegion(jinitRgb, 0, n, reinterpret_cast<jbyte *>(init_pixels.data()));
        g.init_image.width   = static_cast<uint32_t>(initW);
        g.init_image.height  = static_cast<uint32_t>(initH);
        g.init_image.channel = 3;
        g.init_image.data    = init_pixels.data();
        g.strength           = strength;
    }

    sd_image_t *images = nullptr;
    int n_images = 0;
    const bool ok = generate_image(s->ctx, &g, &images, &n_images);

    {
        std::lock_guard<std::mutex> gl(g_progress_mu);
        if (g_progress.obj != nullptr) {
            env->DeleteGlobalRef(g_progress.obj);
            g_progress.obj = nullptr;
            g_progress.method = nullptr;
        }
    }
    // Clear any cancellation latch so the context stays usable afterwards.
    sd_cancel_generation(s->ctx, SD_CANCEL_RESET);

    if (!ok || images == nullptr || n_images <= 0) {
        LIANE("generate_image failed");
        if (images != nullptr) free(images);
        return nullptr;
    }

    const sd_image_t &img = images[0];
    const size_t bytes = static_cast<size_t>(img.width) * img.height * img.channel;

    jbyteArray out = env->NewByteArray(static_cast<jsize>(bytes));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(bytes),
                                reinterpret_cast<const jbyte *>(img.data));
    }
    if (jdims != nullptr && env->GetArrayLength(jdims) >= 3) {
        jint dims[3] = {static_cast<jint>(img.width),
                        static_cast<jint>(img.height),
                        static_cast<jint>(img.channel)};
        env->SetIntArrayRegion(jdims, 0, 3, dims);
    }

    for (int i = 0; i < n_images; ++i) free(images[i].data);
    free(images);
    return out;
}

} // extern "C"
