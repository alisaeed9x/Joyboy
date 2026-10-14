// (v307) جسر JNI لـ ONNX Runtime — لفصل الصوت عن الموسيقى (MDX-Net) في StemCore.kt
// المكتبة نفسها (libonnxruntime.so) جاية مع sherpa-onnx AAR وبنحمّلها بـ dlopen وقت التشغيل،
// فمفيش ربط وقت البناء غير الهيدر (بيتنزّل في CMakeLists) ومفيش نسختين من onnxruntime في الـ APK.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>
#include "onnxruntime_c_api.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "substem", __VA_ARGS__)

static const OrtApi *g_api = nullptr;
static OrtEnv *g_env = nullptr;
static std::mutex g_mu;
static thread_local std::string g_err;

static bool ck(OrtStatus *s) {
    if (!s) return true;
    g_err = g_api->GetErrorMessage(s);
    g_api->ReleaseStatus(s);
    return false;
}

static bool initApi() {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_api && g_env) return true;
    void *h = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) { const char *e = dlerror(); g_err = std::string("dlopen: ") + (e ? e : "?"); return false; }
    typedef const OrtApiBase *(*GetBaseFn)();
    GetBaseFn gb = reinterpret_cast<GetBaseFn>(dlsym(h, "OrtGetApiBase"));
    if (!gb) { g_err = "OrtGetApiBase مش موجودة في المكتبة"; return false; }
    const OrtApiBase *b = gb();
    g_api = b->GetApi(ORT_API_VERSION);
    if (!g_api) { g_err = std::string("نسخة onnxruntime قديمة: ") + b->GetVersionString(); return false; }
    if (!ck(g_api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "substem", &g_env))) { g_env = nullptr; return false; }
    LOGI("onnxruntime %s", b->GetVersionString());
    return true;
}

struct Sess {
    OrtSessionOptions *opts = nullptr;
    OrtSession *session = nullptr;
    OrtMemoryInfo *mem = nullptr;
    OrtValue *in = nullptr;
    std::string inName, outName;
    std::vector<float> inBuf;
    size_t outCount = 0;
};

static void freeSess(Sess *s) {
    if (!s) return;
    if (g_api) {
        if (s->in) g_api->ReleaseValue(s->in);
        if (s->mem) g_api->ReleaseMemoryInfo(s->mem);
        if (s->session) g_api->ReleaseSession(s->session);
        if (s->opts) g_api->ReleaseSessionOptions(s->opts);
    }
    delete s;
}

static jstring mkStr(JNIEnv *env, const std::string &s) { return env->NewStringUTF(s.c_str()); }

extern "C" {

JNIEXPORT jlong JNICALL Java_com_tttt_subtitler_StemOrt_nOpen(JNIEnv *env, jclass, jstring path, jint threads) {
    g_err.clear();
    if (!initApi()) return 0;
    const char *p = env->GetStringUTFChars(path, nullptr);
    std::string model = p ? p : "";
    env->ReleaseStringUTFChars(path, p);
    Sess *s = new (std::nothrow) Sess();
    if (!s) { g_err = "الرام مش كفاية"; return 0; }
    bool ok = ck(g_api->CreateSessionOptions(&s->opts));
    if (ok) ok = ck(g_api->SetIntraOpNumThreads(s->opts, threads > 0 ? threads : 2));
    if (ok) ok = ck(g_api->SetInterOpNumThreads(s->opts, 1));
    if (ok) ok = ck(g_api->SetSessionExecutionMode(s->opts, ORT_SEQUENTIAL));
    if (ok) ok = ck(g_api->SetSessionGraphOptimizationLevel(s->opts, ORT_ENABLE_ALL));
    if (ok) ok = ck(g_api->AddSessionConfigEntry(s->opts, "session.intra_op.allow_spinning", "0"));
    if (ok) ok = ck(g_api->CreateSession(g_env, model.c_str(), s->opts, &s->session));
    if (ok) {
        size_t nIn = 0, nOut = 0;
        ok = ck(g_api->SessionGetInputCount(s->session, &nIn)) && ck(g_api->SessionGetOutputCount(s->session, &nOut));
        if (ok && (nIn < 1 || nOut < 1)) { g_err = "الموديل من غير مدخل/مخرج"; ok = false; }
    }
    if (ok) {
        OrtAllocator *al = nullptr;
        ok = ck(g_api->GetAllocatorWithDefaultOptions(&al));
        char *nm = nullptr;
        if (ok) ok = ck(g_api->SessionGetInputName(s->session, 0, al, &nm));
        if (ok && nm) { s->inName = nm; (void) ck(g_api->AllocatorFree(al, nm)); nm = nullptr; }
        if (ok) ok = ck(g_api->SessionGetOutputName(s->session, 0, al, &nm));
        if (ok && nm) { s->outName = nm; (void) ck(g_api->AllocatorFree(al, nm)); }
    }
    if (ok) ok = ck(g_api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &s->mem));
    if (!ok) { freeSess(s); return 0; }
    return reinterpret_cast<jlong>(s);
}

// أبعاد المدخل الأول (-1 = بعد ديناميكي). null لو فشل
JNIEXPORT jintArray JNICALL Java_com_tttt_subtitler_StemOrt_nShape(JNIEnv *env, jclass, jlong h) {
    Sess *s = reinterpret_cast<Sess *>(h);
    if (!s || !s->session) return nullptr;
    OrtTypeInfo *ti = nullptr;
    if (!ck(g_api->SessionGetInputTypeInfo(s->session, 0, &ti))) return nullptr;
    const OrtTensorTypeAndShapeInfo *tsi = nullptr;
    bool ok = ck(g_api->CastTypeInfoToTensorInfo(ti, &tsi));
    if (ok && !tsi) { g_err = "المدخل مش Tensor"; ok = false; }
    size_t n = 0;
    std::vector<int64_t> d;
    if (ok) ok = ck(g_api->GetDimensionsCount(tsi, &n));
    if (ok) { d.assign(n, 0); ok = ck(g_api->GetDimensions(tsi, d.data(), n)); }
    g_api->ReleaseTypeInfo(ti);
    if (!ok) return nullptr;
    jintArray arr = env->NewIntArray((jsize) n);
    if (!arr) return nullptr;
    std::vector<jint> v(n);
    for (size_t i = 0; i < n; i++) v[i] = (jint) d[i];
    if (n > 0) env->SetIntArrayRegion(arr, 0, (jsize) n, v.data());
    return arr;
}

// بيحجز مدخل [1,c,f,t] مرة واحدة (بيرجّع 0 = تمام)
JNIEXPORT jint JNICALL Java_com_tttt_subtitler_StemOrt_nPrepare(JNIEnv *, jclass, jlong h, jint c, jint f, jint t) {
    Sess *s = reinterpret_cast<Sess *>(h);
    if (!s || !s->session) { g_err = "الموديل مش مفتوح"; return 1; }
    size_t n = (size_t) c * (size_t) f * (size_t) t;
    try { s->inBuf.assign(n, 0.0f); } catch (const std::bad_alloc &) { g_err = "الرام مش كفاية"; return 2; }
    s->outCount = n;
    if (s->in) { g_api->ReleaseValue(s->in); s->in = nullptr; }
    int64_t dims[4] = {1, c, f, t};
    if (!ck(g_api->CreateTensorWithDataAsOrtValue(s->mem, s->inBuf.data(), n * sizeof(float), dims, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &s->in))) return 3;
    return 0;
}

// input/output: float[c*f*t]. 0 = تمام
JNIEXPORT jint JNICALL Java_com_tttt_subtitler_StemOrt_nRun(JNIEnv *env, jclass, jlong h, jfloatArray input, jfloatArray output) {
    Sess *s = reinterpret_cast<Sess *>(h);
    g_err.clear();
    if (!s || !s->session || !s->in) { g_err = "الموديل مش جاهز"; return 1; }
    if ((size_t) env->GetArrayLength(input) != s->inBuf.size() || (size_t) env->GetArrayLength(output) != s->outCount) { g_err = "حجم المصفوفة غلط"; return 2; }
    env->GetFloatArrayRegion(input, 0, (jsize) s->inBuf.size(), s->inBuf.data());
    const char *inN[1] = {s->inName.c_str()};
    const char *outN[1] = {s->outName.c_str()};
    const OrtValue *ins[1] = {s->in};
    OrtValue *outs[1] = {nullptr};
    if (!ck(g_api->Run(s->session, nullptr, inN, ins, 1, outN, 1, outs))) return 3;
    int rc = 0;
    float *data = nullptr;
    OrtTensorTypeAndShapeInfo *info = nullptr;
    size_t cnt = 0;
    if (!ck(g_api->GetTensorMutableData(outs[0], reinterpret_cast<void **>(&data)))) rc = 4;
    else if (!ck(g_api->GetTensorTypeAndShape(outs[0], &info))) rc = 5;
    else {
        bool ok = ck(g_api->GetTensorShapeElementCount(info, &cnt));
        g_api->ReleaseTensorTypeAndShapeInfo(info);
        if (!ok) rc = 6;
        else if (cnt != s->outCount) { g_err = "حجم مخرج الموديل مختلف عن المتوقع"; rc = 7; }
    }
    if (rc == 0) env->SetFloatArrayRegion(output, 0, (jsize) cnt, data);
    g_api->ReleaseValue(outs[0]);
    return rc;
}

JNIEXPORT void JNICALL Java_com_tttt_subtitler_StemOrt_nClose(JNIEnv *, jclass, jlong h) {
    freeSess(reinterpret_cast<Sess *>(h));
}

JNIEXPORT jstring JNICALL Java_com_tttt_subtitler_StemOrt_nErr(JNIEnv *env, jclass) { return mkStr(env, g_err); }

JNIEXPORT jstring JNICALL Java_com_tttt_subtitler_StemOrt_nVersion(JNIEnv *env, jclass) {
    if (!initApi()) return mkStr(env, "?");
    typedef const OrtApiBase *(*GetBaseFn)();
    GetBaseFn gb = reinterpret_cast<GetBaseFn>(dlsym(RTLD_DEFAULT, "OrtGetApiBase"));
    return mkStr(env, gb ? gb()->GetVersionString() : "?");
}

}  // extern "C"
