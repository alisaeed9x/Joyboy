// (v253) جسر JNI بسيط فوق whisper.cpp — التفريغ على الجهاز من غير نت ولا مفتاح
#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <string>
#include "whisper.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "subwhisper", __VA_ARGS__)

static std::string clean(const char *s) {
    std::string o = s ? s : "";
    for (auto &c : o) if (c == '\n' || c == '\t' || c == '\r') c = ' ';
    return o;
}

// (v415) تقدم التفريغ الحقيقي: نسبة من 0 لـ 100 بتتحدّث من whisper.cpp نفسه (كل نافذة + كل جملة جديدة)
static volatile int g_prog = 0;
static volatile long g_total_cs = 1;
static void on_progress(struct whisper_context *, struct whisper_state *, int p, void *) { if (p > g_prog) g_prog = p; }
static void on_segment(struct whisper_context *ctx, struct whisper_state *, int, void *) {
    int n = whisper_full_n_segments(ctx);
    if (n > 0 && g_total_cs > 0) {
        long p = (long) whisper_full_get_segment_t1(ctx, n - 1) * 100 / g_total_cs;
        if (p > 99) p = 99;
        if (p > g_prog) g_prog = (int) p;
    }
}

extern "C" {

JNIEXPORT jint JNICALL Java_com_tttt_subtitler_WhisperLib_nProgress(JNIEnv *, jclass) { return g_prog; }

JNIEXPORT jlong JNICALL Java_com_tttt_subtitler_WhisperLib_nInit(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;   // (v394) أسرع على المعالج وبياخد رام أقل
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL Java_com_tttt_subtitler_WhisperLib_nFree(JNIEnv *, jclass, jlong h) {
    if (h) whisper_free(reinterpret_cast<whisper_context *>(h));
}

// الرد: UTF-8 bytes. سطر أول "L\t<لغة>" وبعده لكل جزء "t0ms\tt1ms\tno_speech\tavg_logprob\tالنص". لو فشل: "ERR\t<كود>"
JNIEXPORT jbyteArray JNICALL Java_com_tttt_subtitler_WhisperLib_nRun(JNIEnv *env, jclass, jlong h, jfloatArray audio,
                                                                      jint threads, jstring lang, jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(h);
    std::string out;
    if (!ctx) {
        out = "ERR\tno-context";
    } else {
        const char *lg = env->GetStringUTFChars(lang, nullptr);
        const char *pr = env->GetStringUTFChars(prompt, nullptr);
        std::string langS = (lg && *lg) ? lg : "auto";
        std::string promptS = pr ? pr : "";
        env->ReleaseStringUTFChars(lang, lg);
        env->ReleaseStringUTFChars(prompt, pr);

        jsize n = env->GetArrayLength(audio);
        jfloat *pcm = env->GetFloatArrayElements(audio, nullptr);

        whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        wp.n_threads = threads;
        wp.language = langS.c_str();
        wp.translate = false;
        wp.no_context = true;
        // (v394) سرعة + دقة: greedy بعينة واحدة/اتنين عند الفشل، وعتبات الهلوسة والتكرار صريحة
        wp.greedy.best_of = 2;
        wp.temperature_inc = 0.2f;
        wp.suppress_blank = true;
        wp.no_speech_thold = 0.6f;
        wp.logprob_thold = -1.0f;
        wp.entropy_thold = 2.4f;
        wp.token_timestamps = false;
        wp.print_progress = false;
        wp.print_realtime = false;
        wp.print_special = false;
        wp.print_timestamps = false;
        if (!promptS.empty()) wp.initial_prompt = promptS.c_str();

        g_prog = 0; g_total_cs = (long) (n / 160); if (g_total_cs < 1) g_total_cs = 1;
        wp.progress_callback = on_progress; wp.progress_callback_user_data = nullptr;
        wp.new_segment_callback = on_segment; wp.new_segment_callback_user_data = nullptr;
        int rc = whisper_full(ctx, wp, pcm, (int) n);
        g_prog = 100;
        env->ReleaseFloatArrayElements(audio, pcm, JNI_ABORT);

        if (rc != 0) {
            out = "ERR\t" + std::to_string(rc);
        } else {
            out = std::string("L\t") + whisper_lang_str(whisper_full_lang_id(ctx)) + "\n";
            int ns = whisper_full_n_segments(ctx);
            const whisper_token eot = whisper_token_eot(ctx);
            for (int i = 0; i < ns; i++) {
                double sum = 0; int cnt = 0;
                int nt = whisper_full_n_tokens(ctx, i);
                for (int j = 0; j < nt; j++) {
                    whisper_token_data d = whisper_full_get_token_data(ctx, i, j);
                    if (d.id < eot) { sum += d.plog; cnt++; }
                }
                double lp = cnt ? sum / cnt : 0.0;
                out += std::to_string((long long) whisper_full_get_segment_t0(ctx, i) * 10) + "\t" +
                       std::to_string((long long) whisper_full_get_segment_t1(ctx, i) * 10) + "\t" +
                       std::to_string(whisper_full_get_segment_no_speech_prob(ctx, i)) + "\t" +
                       std::to_string(lp) + "\t" + clean(whisper_full_get_segment_text(ctx, i)) + "\n";
            }
        }
    }
    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}

}
