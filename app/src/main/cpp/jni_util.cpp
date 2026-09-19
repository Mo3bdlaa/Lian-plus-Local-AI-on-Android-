#include "jni_util.h"

namespace lian {

std::string to_string(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    const jsize len = env->GetStringUTFLength(s);
    std::string out(chars, static_cast<size_t>(len));
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring to_jstring(JNIEnv *env, const std::string &s) {
    const std::string clean = sanitize_utf8(s);
    return env->NewStringUTF(clean.c_str());
}

jstring to_jstring(JNIEnv *env, const char *s) {
    return to_jstring(env, std::string(s == nullptr ? "" : s));
}

void throw_ise(JNIEnv *env, const std::string &msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, msg.c_str());
}

std::vector<int32_t> to_int_vector(JNIEnv *env, jintArray arr) {
    if (arr == nullptr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::vector<int32_t> out(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(arr, 0, n, reinterpret_cast<jint *>(out.data()));
    return out;
}

jintArray to_jint_array(JNIEnv *env, const std::vector<int32_t> &v) {
    jintArray arr = env->NewIntArray(static_cast<jsize>(v.size()));
    if (arr == nullptr) return nullptr;
    if (!v.empty()) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(v.size()),
                               reinterpret_cast<const jint *>(v.data()));
    }
    return arr;
}

jfloatArray to_jfloat_array(JNIEnv *env, const std::vector<float> &v) {
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(v.size()));
    if (arr == nullptr) return nullptr;
    if (!v.empty()) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
    }
    return arr;
}

// Length of the UTF-8 sequence that starts with `b`, or 0 if `b` is not a
// valid leading byte.
static size_t utf8_seq_len(unsigned char b) {
    if (b < 0x80) return 1;
    if ((b & 0xE0) == 0xC0) return 2;
    if ((b & 0xF0) == 0xE0) return 3;
    if ((b & 0xF8) == 0xF0) return 4;
    return 0;
}

std::string sanitize_utf8(const std::string &in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        const auto b = static_cast<unsigned char>(in[i]);
        const size_t need = utf8_seq_len(b);
        bool ok = need > 0 && i + need <= in.size();
        if (ok) {
            for (size_t k = 1; k < need; ++k) {
                if ((static_cast<unsigned char>(in[i + k]) & 0xC0) != 0x80) { ok = false; break; }
            }
        }
        if (ok) {
            out.append(in, i, need);
            i += need;
        } else {
            out.append("\xEF\xBF\xBD"); // U+FFFD
            i += 1;
        }
    }
    return out;
}

size_t incomplete_utf8_tail(const std::string &s) {
    // Walk back at most 3 bytes looking for a leading byte whose sequence runs
    // past the end of the buffer.
    const size_t max_look = s.size() < 4 ? s.size() : 4;
    for (size_t back = 1; back <= max_look; ++back) {
        const auto b = static_cast<unsigned char>(s[s.size() - back]);
        const size_t need = utf8_seq_len(b);
        if (need == 0) continue;           // continuation byte, keep walking back
        return need > back ? back : 0;     // sequence incomplete -> hold `back` bytes
    }
    return 0;
}

} // namespace lian
