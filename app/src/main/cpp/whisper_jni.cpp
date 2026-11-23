// Speech to text with whisper.cpp, in the ":assistant" process. Shares ggml (and its runtime-picked CPU
// backend) with llama.cpp, so Native.init must have run first.
#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <android/log.h>
#include "whisper.h"

static whisper_context * g_wctx = nullptr;
static std::string g_wpath;
static std::mutex g_wmu;
static std::atomic<bool> g_wabort{false};

static std::string wstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

static void wlog(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, "whisper", "%s", text);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hiddenoutlier_launcher_assistant_Native_whisperLoad(JNIEnv * env, jobject, jstring jpath) {
    std::lock_guard<std::mutex> lock(g_wmu);
    std::string path = wstr(env, jpath);
    if (g_wctx && g_wpath == path) return 0;
    if (g_wctx) { whisper_free(g_wctx); g_wctx = nullptr; g_wpath.clear(); }
    whisper_log_set(wlog, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    g_wctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (!g_wctx) return -1;
    g_wpath = path;
    return 0;
}

/** 16 kHz mono float samples → text. [lang] is "en", "auto", … [prompt] biases spelling (app and contact names). */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hiddenoutlier_launcher_assistant_Native_whisperTranscribe(JNIEnv * env, jobject, jfloatArray jpcm, jstring jlang, jint threads, jstring jprompt) {
    std::lock_guard<std::mutex> lock(g_wmu);
    if (!g_wctx) return nullptr;
    g_wabort = false;
    std::string lang = wstr(env, jlang), prompt = wstr(env, jprompt);
    jsize n = env->GetArrayLength(jpcm);
    jfloat * pcm = env->GetFloatArrayElements(jpcm, nullptr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.language = lang.empty() ? "en" : lang.c_str();
    wp.translate = false;
    wp.no_timestamps = true;
    wp.no_context = true;
    wp.single_segment = false;
    wp.suppress_blank = true;
    wp.print_progress = false; wp.print_realtime = false; wp.print_special = false; wp.print_timestamps = false;
    if (!prompt.empty()) wp.initial_prompt = prompt.c_str();
    wp.abort_callback = [](void *) { return g_wabort.load(); };

    int rc = whisper_full(g_wctx, wp, pcm, n);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    if (rc != 0) return nullptr;
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(g_wctx); i++) text += whisper_full_get_segment_text(g_wctx, i);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_hiddenoutlier_launcher_assistant_Native_whisperStop(JNIEnv *, jobject) { g_wabort = true; }

extern "C" JNIEXPORT void JNICALL
Java_com_hiddenoutlier_launcher_assistant_Native_whisperUnload(JNIEnv *, jobject) {
    g_wabort = true;
    std::lock_guard<std::mutex> lock(g_wmu);
    if (g_wctx) { whisper_free(g_wctx); g_wctx = nullptr; g_wpath.clear(); }
}
