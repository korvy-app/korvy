// Bridge between the launcher's assistant process and llama.cpp. One model and one request at a time.
// Runs only in the ":assistant" process, so the launcher itself never holds a model in memory.
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <string>
#include <unistd.h>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

#define TAG "assistant-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static common_chat_templates_ptr g_tmpls;
static std::atomic<bool> g_stop{false};
// Tokens currently in the KV cache (prompt + reply of the last request). A new request that starts the same way
// (system prompt, examples, earlier turns) reuses them instead of reading everything again.
static std::vector<llama_token> g_cached;
static int g_n_ctx = 4096;
constexpr int BATCH = 512;

static void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

static bool valid_utf8(const std::string &s) {
    const auto *b = (const unsigned char *) s.c_str();
    while (*b) {
        int n = (*b & 0x80) == 0 ? 1 : (*b & 0xE0) == 0xC0 ? 2 : (*b & 0xF0) == 0xE0 ? 3 : (*b & 0xF8) == 0xF0 ? 4 : 0;
        if (!n) return false;
        b++;
        for (int i = 1; i < n; i++, b++) if ((*b & 0xC0) != 0x80) return false;
    }
    return true;
}

static void free_all() {
    g_cached.clear();
    g_tmpls.reset();
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hiddenoutlier_launcher_assistant_Native_init(JNIEnv *env, jobject, jstring libDir) {
    llama_log_set(log_cb, nullptr);
    ggml_backend_load_all_from_path(jstr(env, libDir).c_str());
    llama_backend_init();
}

/** Loads a GGUF model. Returns 0 on success. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hiddenoutlier_launcher_assistant_Native_load(JNIEnv *env, jobject, jstring path, jint nCtx, jint threads) {
    free_all();
    auto mp = llama_model_default_params();
    g_model = llama_model_load_from_file(jstr(env, path).c_str(), mp);
    if (!g_model) { LOGE("model load failed"); return 1; }
    g_n_ctx = nCtx;
    auto cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = BATCH;
    cp.n_ubatch = BATCH;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("context init failed"); free_all(); return 2; }
    g_tmpls = common_chat_templates_init(g_model, "");
    LOGI("model loaded, n_ctx=%d threads=%d", nCtx, threads);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hiddenoutlier_launcher_assistant_Native_stop(JNIEnv *, jobject) { g_stop = true; }

extern "C" JNIEXPORT void JNICALL
Java_com_hiddenoutlier_launcher_assistant_Native_unload(JNIEnv *, jobject) { free_all(); }

/**
 * Generates a reply to a chat (parallel arrays of roles and contents). [grammar] (GBNF) constrains the output,
 * e.g. to the assistant's JSON action format; empty means free text. Each new piece of text is passed to
 * listener.onToken(String) -> boolean (false stops). Returns the whole reply, or null on error.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hiddenoutlier_launcher_assistant_Native_complete(
        JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents, jstring jgrammar,
        jint maxTokens, jfloat temp, jobject listener) {
    if (!g_ctx || !g_model) return nullptr;
    g_stop = false;

    common_chat_templates_inputs in;
    const jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        m.role = jstr(env, (jstring) env->GetObjectArrayElement(roles, i));
        m.content = jstr(env, (jstring) env->GetObjectArrayElement(contents, i));
        in.messages.push_back(m);
    }
    in.add_generation_prompt = true;
    in.use_jinja = true;
    in.enable_thinking = false; // short, direct answers; no hidden reasoning on a phone
    common_chat_params cp;
    try {
        cp = common_chat_templates_apply(g_tmpls.get(), in);
    } catch (const std::exception &e) {
        LOGE("chat template failed: %s", e.what());
        return nullptr;
    }

    // Tokenize part by part: special tokens are parsed in template text but never in user-typed text.
    std::vector<llama_token> tokens;
    bool first = true;
    for (const auto &part : cp.prompt.parts) {
        auto t = common_tokenize(g_ctx, part.text, first, !part.is_input);
        tokens.insert(tokens.end(), t.begin(), t.end());
        first = false;
    }
    if ((int) tokens.size() + maxTokens >= g_n_ctx) {
        LOGE("prompt too long: %d tokens", (int) tokens.size());
        return nullptr;
    }

    // Reuse the cached prefix; always re-read at least the last token so there are fresh logits to sample from.
    size_t keep = 0;
    while (keep < g_cached.size() && keep < tokens.size() && g_cached[keep] == tokens[keep]) keep++;
    if (keep >= tokens.size()) keep = tokens.size() - 1;
    auto *mem = llama_get_memory(g_ctx);
    if (keep == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) { llama_memory_clear(mem, false); keep = 0; }
    LOGI("prompt %d tokens, reused %d from cache", (int) tokens.size(), (int) keep);
    g_cached.assign(tokens.begin(), tokens.begin() + keep);
    common_batch batch(g_ctx);
    for (int i = (int) keep; i < (int) tokens.size(); i += BATCH) {
        batch.clear();
        const int end = std::min((int) tokens.size(), i + BATCH);
        for (int j = i; j < end; j++) batch.add(tokens[j], j, 0, j == (int) tokens.size() - 1);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { LOGE("prompt decode failed"); g_cached.clear(); llama_memory_clear(mem, false); return nullptr; }
    }
    g_cached = tokens;

    common_params_sampling sp;
    sp.temp = temp;
    const std::string grammar = jstr(env, jgrammar);
    if (!grammar.empty()) sp.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, grammar);
    common_sampler *smpl = common_sampler_init(g_model, sp);
    if (!smpl) { LOGE("sampler init failed (bad grammar?)"); return nullptr; }

    jclass cls = env->GetObjectClass(listener);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    std::string out, pending;
    llama_pos pos = (llama_pos) tokens.size();
    for (int i = 0; i < maxTokens && !g_stop; i++) {
        const llama_token tok = common_sampler_sample(smpl, g_ctx, -1);
        common_sampler_accept(smpl, tok, true);
        if (llama_vocab_is_eog(vocab, tok)) break;
        pending += common_token_to_piece(g_ctx, tok);
        if (valid_utf8(pending)) {
            out += pending;
            jstring piece = env->NewStringUTF(pending.c_str());
            const bool more = env->CallBooleanMethod(listener, onToken, piece);
            env->DeleteLocalRef(piece);
            pending.clear();
            if (!more) break;
        }
        batch.clear();
        batch.add(tok, pos++, 0, true);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { LOGE("decode failed"); g_cached.clear(); llama_memory_clear(llama_get_memory(g_ctx), false); break; }
        g_cached.push_back(tok);
    }
    common_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}
