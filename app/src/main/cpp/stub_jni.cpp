// Built in place of the real engines when the vendored sources are unavailable.
// Every entry point is absent, so Kotlin's UnsatisfiedLinkError probe in
// LlamaNative/SdNative reports the engine as unavailable and the UI explains why.
#include <jni.h>
#include <android/log.h>

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    __android_log_print(ANDROID_LOG_WARN, "LianNative",
                        "%s was built as a stub - engine unavailable", LIAN_STUB_LIB);
    return JNI_VERSION_1_6;
}
