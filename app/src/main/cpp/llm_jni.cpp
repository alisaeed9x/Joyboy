// (v696) جسر JNI لـ llama.cpp — ترجمة Hy-MT2 على الجهاز (من غير نت ولا مفتاح). نفس مكتبة whisper (subwhisper) عشان ggml واحدة.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "subllm", __VA_ARGS__)

struct LlmH {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
};

static bool g_backend = false;

static std::string err(const std::string &s) { return "ERR\t" + s; }

extern "C" {

// المسار ممكن يبقى /proc/self/fd/N (ملف اتختار من الموبايل) — mmap بيشتغل عليه عادي
JNIEXPORT jlong JNICALL Java_com_tttt_subtitler_LlmLib_nInit(JNIEnv *env, jclass, jstring path, jint nCtx) {
    if (!g_backend) { llama_backend_init(); g_backend = true; }
    const char *p = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;   // mmap: الموديل مش بيتنسخ في الرام كله + شغّال على /proc/self/fd/N
    llama_model *m = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!m) return 0;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx > 256 ? (uint32_t) nCtx : 1024;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.no_perf = true;
    llama_context *c = llama_init_from_model(m, cp);
    if (!c) { llama_model_free(m); return 0; }
    auto *h = new LlmH();
    h->model = m; h->ctx = c; h->vocab = llama_model_get_vocab(m); h->n_ctx = (int) llama_n_ctx(c);
    return reinterpret_cast<jlong>(h);
}

// (v747) البيلد ده فيه kernel STQ1_0 (موديل 1.25-bit)؟
JNIEXPORT jboolean JNICALL Java_com_tttt_subtitler_LlmLib_nStq(JNIEnv *, jclass) {
#ifdef JOY_STQ
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL Java_com_tttt_subtitler_LlmLib_nFree(JNIEnv *, jclass, jlong hh) {
    auto *h = reinterpret_cast<LlmH *>(hh);
    if (!h) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

// [user] = نص الطلب كامل (التعليمة + النص). بيتلف في قالب hunyuan-dense (من غير system، زي ما Tencent موصية).
// الرد: UTF-8 للترجمة بس، أو "ERR\t<سبب>". مستدعي واحد في المرة (القفل في Kotlin).
JNIEXPORT jbyteArray JNICALL Java_com_tttt_subtitler_LlmLib_nTranslate(JNIEnv *env, jclass, jlong hh, jstring user, jint threads, jint maxNew) {
    auto *h = reinterpret_cast<LlmH *>(hh);
    std::string out;
    if (!h) {
        out = err("no-context");
    } else {
        const char *u = env->GetStringUTFChars(user, nullptr);
        std::string userS = u ? u : "";
        env->ReleaseStringUTFChars(user, u);

        llama_chat_message msg = {"user", userS.c_str()};
        std::vector<char> buf(userS.size() * 2 + 256);
        int n = llama_chat_apply_template("hunyuan-dense", &msg, 1, true, buf.data(), (int32_t) buf.size());
        if (n > (int) buf.size()) { buf.resize(n + 16); n = llama_chat_apply_template("hunyuan-dense", &msg, 1, true, buf.data(), (int32_t) buf.size()); }
        if (n < 0) {
            out = err("chat-template");
        } else {
            std::string prompt(buf.data(), (size_t) n);
            std::vector<llama_token> toks(prompt.size() + 16);
            int nt = llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), true, true);
            if (nt < 0) { toks.resize((size_t) -nt); nt = llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), true, true); }
            if (nt <= 0) {
                out = err("tokenize");
            } else if (nt >= h->n_ctx - 8) {
                out = err("too-long");
            } else {
                toks.resize((size_t) nt);
                int budget = std::min((int) maxNew, h->n_ctx - nt - 4);
                llama_set_n_threads(h->ctx, threads, threads);
                llama_memory_clear(llama_get_memory(h->ctx), true);

                llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
                llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(h->vocab), 64, 1.05f, 0.0f, 0.0f));
                llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20));
                llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.6f, 1));
                llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
                llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

                bool fail = false;
                // البرومبت على دفعات n_batch
                for (int i = 0; i < nt && !fail; i += 512) {
                    int c = std::min(512, nt - i);
                    llama_batch b = llama_batch_get_one(toks.data() + i, c);
                    if (llama_decode(h->ctx, b) != 0) fail = true;
                }
                if (fail) {
                    out = err("decode-prompt");
                } else {
                    for (int k = 0; k < budget; k++) {
                        llama_token t = llama_sampler_sample(smpl, h->ctx, -1);
                        if (llama_vocab_is_eog(h->vocab, t)) break;
                        char pc[256];
                        int pl = llama_token_to_piece(h->vocab, t, pc, (int32_t) sizeof(pc), 0, false);
                        if (pl > 0) out.append(pc, (size_t) pl);
                        llama_batch b = llama_batch_get_one(&t, 1);
                        if (llama_decode(h->ctx, b) != 0) { fail = true; break; }
                    }
                    if (fail && out.empty()) out = err("decode");
                }
                llama_sampler_free(smpl);
            }
        }
    }
    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}

// (v744) طريقة SubMaster: رسالة system + user بقالب الموديل نفسه (HY-MT1.5)، وفك حتمي greedy (temp 0 · top-k 1 · من غير repeat penalty).
// الرد: UTF-8 للناتج بس، أو "ERR\t<سبب>". مستدعي واحد في المرة (القفل في Kotlin).
JNIEXPORT jbyteArray JNICALL Java_com_tttt_subtitler_LlmLib_nChat(JNIEnv *env, jclass, jlong hh, jstring sys, jstring user, jint threads, jint maxNew) {
    auto *h = reinterpret_cast<LlmH *>(hh);
    std::string out;
    if (!h) {
        out = err("no-context");
    } else {
        const char *su = sys ? env->GetStringUTFChars(sys, nullptr) : nullptr;
        std::string sysS = su ? su : "";
        if (su) env->ReleaseStringUTFChars(sys, su);
        const char *u = env->GetStringUTFChars(user, nullptr);
        std::string userS = u ? u : "";
        env->ReleaseStringUTFChars(user, u);

        std::vector<llama_chat_message> msgs;
        if (!sysS.empty()) msgs.push_back({"system", sysS.c_str()});
        msgs.push_back({"user", userS.c_str()});
        const char *tmpl = "hunyuan-dense";
        std::vector<char> buf((sysS.size() + userS.size()) * 2 + 512);
        int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
        if (n > (int) buf.size()) { buf.resize(n + 16); n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size()); }
        if (n < 0) {
            out = err("chat-template");
        } else {
            std::string prompt(buf.data(), (size_t) n);
            std::vector<llama_token> toks(prompt.size() + 16);
            int nt = llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), true, true);
            if (nt < 0) { toks.resize((size_t) -nt); nt = llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), true, true); }
            if (nt <= 0) {
                out = err("tokenize");
            } else if (nt >= h->n_ctx - 8) {
                out = err("too-long");
            } else {
                toks.resize((size_t) nt);
                int budget = std::min((int) maxNew, h->n_ctx - nt - 4);
                llama_set_n_threads(h->ctx, threads, threads);
                llama_memory_clear(llama_get_memory(h->ctx), true);
                llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
                llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
                bool fail = false;
                for (int i = 0; i < nt && !fail; i += 512) {
                    int c = std::min(512, nt - i);
                    llama_batch b = llama_batch_get_one(toks.data() + i, c);
                    if (llama_decode(h->ctx, b) != 0) fail = true;
                }
                if (fail) {
                    out = err("decode-prompt");
                } else {
                    for (int k = 0; k < budget; k++) {
                        llama_token t = llama_sampler_sample(smpl, h->ctx, -1);
                        if (llama_vocab_is_eog(h->vocab, t)) break;
                        char pc[256];
                        int pl = llama_token_to_piece(h->vocab, t, pc, (int32_t) sizeof(pc), 0, false);
                        if (pl > 0) out.append(pc, (size_t) pl);
                        llama_batch b = llama_batch_get_one(&t, 1);
                        if (llama_decode(h->ctx, b) != 0) { fail = true; break; }
                    }
                    if (fail && out.empty()) out = err("decode");
                }
                llama_sampler_free(smpl);
            }
        }
    }
    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}

}
