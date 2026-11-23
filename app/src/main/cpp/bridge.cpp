#include <jni.h>
#include <string>
#ifndef PRIME_AI_UNSUPPORTED
#include "runtime.hpp"
#include <memory>
static std::unique_ptr<prime::Runtime> runtime;
#endif
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT jstring JNICALL
Java_cz_primevoice_translation_GgufNative_probe(JNIEnv *env, jobject) {
#ifdef PRIME_AI_UNSUPPORTED
    fail(env, "Lokální AI vyžaduje 64bitový Android"); return nullptr;
#else
    return env->NewStringUTF("llama.cpp 2d8a325 • CPU • GGUF Qwen2.5 / Gemma3");
#endif
}
extern "C" JNIEXPORT void JNICALL
Java_cz_primevoice_translation_GgufNative_load(JNIEnv *env, jobject, jstring file, jint threads) {
#ifndef PRIME_AI_UNSUPPORTED
    const char *path = env->GetStringUTFChars(file, nullptr);
    try { runtime.reset(); runtime = std::make_unique<prime::Runtime>(path, threads); }
    catch (const std::exception &e) { fail(env, e.what()); }
    env->ReleaseStringUTFChars(file, path);
#else
    fail(env, "Nepodporovana architektura AI");
#endif
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cz_primevoice_translation_GgufNative_generate(JNIEnv *env, jobject, jbyteArray input, jint timeout) {
#ifndef PRIME_AI_UNSUPPORTED
    try {
        if (!runtime) throw std::runtime_error("Model neni nacten");
        std::string prompt(env->GetArrayLength(input), '\0');
        env->GetByteArrayRegion(input, 0, prompt.size(), reinterpret_cast<jbyte *>(prompt.data()));
        auto result = runtime->translate(prompt, timeout);
        auto output = env->NewByteArray(result.size());
        env->SetByteArrayRegion(output, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return output;
    } catch (const std::exception &e) { fail(env, e.what()); }
#else
    fail(env, "Nepodporovana architektura AI");
#endif
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_cz_primevoice_translation_GgufNative_close(JNIEnv *, jobject) {
#ifndef PRIME_AI_UNSUPPORTED
    runtime.reset();
#endif
}
