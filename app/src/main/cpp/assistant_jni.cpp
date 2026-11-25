// Bridge between the launcher's assistant process and llama.cpp. One model and one request at a time.
// Runs only in the ":assistant" process, so the launcher itself never holds a model in memory.
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <string>
#include <unistd.h>
#include <chrono>

static double now_ms() { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

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
Java_app_korvy_launcher_assistant_Native_init(JNIEnv *env, jobject, jstring libDir) {
    llama_log_set(log_cb, nullptr);
    ggml_backend_load_all_from_path(jstr(env, libDir).c_str());
    llama_backend_init();
}

/** Loads a GGUF model. Returns 0 on success. */
extern "C" JNIEXPORT jint JNICALL
Java_app_korvy_launcher_assistant_Native_load(JNIEnv *env, jobject, jstring path, jint nCtx, jint threads, jint threadsBatch) {
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
    cp.n_threads_batch = threadsBatch;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("context init failed"); free_all(); return 2; }
    g_tmpls = common_chat_templates_init(g_model, "");
    LOGI("model loaded, n_ctx=%d threads=%d batch threads=%d", nCtx, threads, threadsBatch);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_app_korvy_launcher_assistant_Native_stop(JNIEnv *, jobject) { g_stop = true; }

extern "C" JNIEXPORT void JNICALL
Java_app_korvy_launcher_assistant_Native_unload(JNIEnv *, jobject) { free_all(); }

/**
 * Generates a reply to a chat (parallel arrays of roles and contents). [grammar] (GBNF) constrains the output,
 * e.g. to the assistant's JSON action format; empty means free text. Each new piece of text is passed to
 * listener.onToken(String) -> boolean (false stops). Returns the whole reply, or null on error.
 */
/** The chat (parallel role/content arrays) as tokens, through the model's chat template. Empty on error. */
static std::vector<llama_token> render(JNIEnv *env, jobjectArray roles, jobjectArray contents, bool addGenerationPrompt) {
    common_chat_templates_inputs in;
    const jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        m.role = jstr(env, (jstring) env->GetObjectArrayElement(roles, i));
        m.content = jstr(env, (jstring) env->GetObjectArrayElement(contents, i));
        in.messages.push_back(m);
    }
    in.add_generation_prompt = addGenerationPrompt;
    in.use_jinja = true;
    in.enable_thinking = false; // short, direct answers; no hidden reasoning on a phone
    common_chat_params cp;
    try {
        cp = common_chat_templates_apply(g_tmpls.get(), in);
    } catch (const std::exception &e) {
        LOGE("chat template failed: %s", e.what());
        return {};
    }
    // Tokenize part by part: special tokens are parsed in template text but never in user-typed text.
    std::vector<llama_token> tokens;
    bool first = true;
    for (const auto &part : cp.prompt.parts) {
        auto t = common_tokenize(g_ctx, part.text, first, !part.is_input);
        tokens.insert(tokens.end(), t.begin(), t.end());
        first = false;
    }
    return tokens;
}

/**
 * Puts [tokens] in the KV cache, reusing the cached prefix (always re-reading at least the last token so there are
 * fresh logits to sample from). Returns how many tokens were read, or -1 on error.
 */
static int feed(const std::vector<llama_token> &tokens) {
    size_t keep = 0;
    while (keep < g_cached.size() && keep < tokens.size() && g_cached[keep] == tokens[keep]) keep++;
    if (keep >= tokens.size()) keep = tokens.size() - 1;
    auto *mem = llama_get_memory(g_ctx);
    if (keep == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) { llama_memory_clear(mem, false); keep = 0; }
    const double t0 = now_ms();
    g_cached.assign(tokens.begin(), tokens.begin() + keep);
    common_batch batch(g_ctx);
    for (int i = (int) keep; i < (int) tokens.size(); i += BATCH) {
        batch.clear();
        const int end = std::min((int) tokens.size(), i + BATCH);
        for (int j = i; j < end; j++) batch.add(tokens[j], j, 0, j == (int) tokens.size() - 1);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGE("prompt decode failed"); g_cached.clear(); llama_memory_clear(mem, false); return -1;
        }
    }
    g_cached = tokens;
    const int read = (int) (tokens.size() - keep);
    const double ms = now_ms() - t0;
    LOGI("timing: prompt %d tokens, reused %d, read %d in %.0f ms (%.1f t/s)", (int) tokens.size(), (int) keep, read, ms, read * 1000.0 / (ms + 0.01));
    return read;
}

/**
 * Reads the fixed start of every request (system prompt and examples) ahead of the first question, so it's answered
 * sooner. The work is kept in [statePath] (about 20 MB for a 1.5B model) and restored when the model process starts
 * again, instead of being read again. Returns the number of tokens read (0 = all restored or cached), -1 on error.
 */
extern "C" JNIEXPORT jint JNICALL
Java_app_korvy_launcher_assistant_Native_prefill(JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents, jstring jstate) {
    if (!g_ctx || !g_model) return -1;
    const std::string state = jstr(env, jstate);
    if (g_cached.empty() && !state.empty() && access(state.c_str(), R_OK) == 0) {
        std::vector<llama_token> saved(g_n_ctx);
        size_t n = 0;
        const double t0 = now_ms();
        if (llama_state_seq_load_file(g_ctx, state.c_str(), 0, saved.data(), saved.size(), &n) > 0) {
            saved.resize(n); g_cached = saved;
            LOGI("timing: restored %d prompt tokens from disk in %.0f ms", (int) n, now_ms() - t0);
        } else {
            LOGE("saved prompt state unusable; reading again");
            llama_memory_clear(llama_get_memory(g_ctx), false); g_cached.clear(); unlink(state.c_str());
        }
    }
    auto tokens = render(env, roles, contents, false);
    if (tokens.empty()) return -1;
    if (g_cached.size() >= tokens.size() && std::equal(tokens.begin(), tokens.end(), g_cached.begin())) return 0; // already there
    const int read = feed(tokens);
    if (read > 0 && !state.empty()) {
        const double t0 = now_ms();
        if (llama_state_seq_save_file(g_ctx, state.c_str(), 0, tokens.data(), tokens.size()) == 0) LOGE("could not save prompt state");
        else LOGI("timing: saved %d prompt tokens in %.0f ms", (int) tokens.size(), now_ms() - t0);
    }
    return read;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_korvy_launcher_assistant_Native_complete(
        JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents, jstring jgrammar,
        jint maxTokens, jfloat temp, jobject listener) {
    if (!g_ctx || !g_model) return nullptr;
    g_stop = false;

    auto tokens = render(env, roles, contents, true);
    if (tokens.empty()) return nullptr;
    if ((int) tokens.size() + maxTokens >= g_n_ctx) {
        LOGE("prompt too long: %d tokens", (int) tokens.size());
        return nullptr;
    }
    const double t_start = now_ms();
    if (feed(tokens) < 0) return nullptr;
    common_batch batch(g_ctx);
    bool first_piece = true;

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
    double t_sample = 0, t_decode = 0, t_cb = 0; int n_gen = 0;
    const double t_gen = now_ms();
    for (int i = 0; i < maxTokens && !g_stop; i++) {
        double t0 = now_ms();
        const llama_token tok = common_sampler_sample(smpl, g_ctx, -1);
        common_sampler_accept(smpl, tok, true);
        t_sample += now_ms() - t0; n_gen++;
        if (llama_vocab_is_eog(vocab, tok)) break;
        pending += common_token_to_piece(g_ctx, tok);
        if (valid_utf8(pending)) {
            const double tc = now_ms();
            out += pending;
            if (first_piece && out.find("\"arg\"") != std::string::npos) { first_piece = false; LOGI("timing: first answer text %.0f ms after the request", now_ms() - t_start); }
            jstring piece = env->NewStringUTF(pending.c_str());
            const bool more = env->CallBooleanMethod(listener, onToken, piece);
            env->DeleteLocalRef(piece);
            pending.clear();
            t_cb += now_ms() - tc;
            if (!more) break;
        }
        const double td = now_ms();
        batch.clear();
        batch.add(tok, pos++, 0, true);
        const int rc_dec = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        t_decode += now_ms() - td;
        if (rc_dec != 0) { LOGE("decode failed"); g_cached.clear(); llama_memory_clear(llama_get_memory(g_ctx), false); break; }
        g_cached.push_back(tok);
    }
    common_sampler_free(smpl);
    LOGI("timing: %d generated in %.0f ms: sampling %.0f, decode %.0f (%.1f t/s), callback %.0f",
         n_gen, now_ms() - t_gen, t_sample, t_decode, n_gen * 1000.0 / (t_decode + 0.01), t_cb);
    return env->NewStringUTF(out.c_str());
}
