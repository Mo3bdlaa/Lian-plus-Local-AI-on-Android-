#pragma once

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#define LIAN_LOG_TAG "LianNative"
#define LIANI(...) __android_log_print(ANDROID_LOG_INFO,  LIAN_LOG_TAG, __VA_ARGS__)
#define LIANW(...) __android_log_print(ANDROID_LOG_WARN,  LIAN_LOG_TAG, __VA_ARGS__)
#define LIANE(...) __android_log_print(ANDROID_LOG_ERROR, LIAN_LOG_TAG, __VA_ARGS__)

namespace lian {

// Copies a Java string into a std::string. Returns "" for null.
std::string to_string(JNIEnv *env, jstring s);

// Builds a Java string from UTF-8 bytes. Invalid sequences are replaced so a
// malformed token can never take down the VM through NewStringUTF.
jstring to_jstring(JNIEnv *env, const std::string &s);

jstring to_jstring(JNIEnv *env, const char *s);

// Throws java.lang.IllegalStateException with the given message.
void throw_ise(JNIEnv *env, const std::string &msg);

std::vector<int32_t> to_int_vector(JNIEnv *env, jintArray arr);

jintArray to_jint_array(JNIEnv *env, const std::vector<int32_t> &v);

jfloatArray to_jfloat_array(JNIEnv *env, const std::vector<float> &v);

// Replaces malformed UTF-8 sequences with U+FFFD in place.
std::string sanitize_utf8(const std::string &in);

// Number of bytes still missing for `s` to end on a complete UTF-8 sequence.
// Used to hold back the tail of a multi-byte character while streaming tokens.
size_t incomplete_utf8_tail(const std::string &s);

} // namespace lian
