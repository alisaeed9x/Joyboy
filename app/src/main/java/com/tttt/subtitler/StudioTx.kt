package com.tttt.subtitler

import org.json.JSONObject

/**
 * (v1012) مصدر التفريغ في الاستوديو:
 *  • «🔒 مصدر ثابت»: كل تفريغ الاستوديو بـ Gemini Flash Lite Latest بس — مفيش سلسلة موديلات ولا «موديل أدق» ولا تبديل صامت
 *    (الإعداد studio_tx_fixed، الافتراضي مفعّل). لو اتقفل بيرجع السلوك القديم (سلسلة وارد / lec_model).
 *  • «📴 تفريغ أوفلاين»: سويتش (studio_tx_offline، الافتراضي مقفول). مفعّل ← الكلام غير العربي (إنجليزي/غيره) بيتفرّغ على الجهاز بـ Whisper
 *    (Offline.transcribe الموجود — مفيش محرك جديد). العربي دايمًا أونلاين بـ Flash Lite: لو Whisper طلّع نص عربي في أي جزء الجزء ده
 *    بيتعاد أونلاين والتشغيلة كلها بتكمّل أونلاين. أي فشل أوفلاين لجزء ← الجزء ده بيتفرّغ أونلاين بنفس Flash Lite.
 */
object StudioTx {
    const val FIXED_KEY = "studio_tx_fixed"
    const val OFFLINE_KEY = "studio_tx_offline"

    fun fixedOn(): Boolean = Cfg.bool(FIXED_KEY, true)
    /** الموديل المتثبّت للتفريغ، أو "" لو القفل مقفول */
    fun pinned(): String = if (fixedOn()) StudioComplete.MODEL else ""
    fun offlineOn(): Boolean = Cfg.bool(OFFLINE_KEY, false)

    /** الأوفلاين مفعّل وجاهز فعلًا (المكتبة + الموديل نازل)؟ */
    fun offlineReady(): Boolean = offlineOn() && try { WhisperLib.loaded && Offline.engine().ready() } catch (_: Throwable) { false }

    /** سطر حالة للواجهة تحت السويتش */
    fun offlineNote(): String = when {
        !offlineOn() -> "مقفول — كل التفريغ أونلاين بـ Flash Lite."
        !WhisperLib.loaded -> "⛔ الأوفلاين مش مدعوم على جهازك (محتاج معالج 64-bit) — التفريغ أونلاين."
        !(try { Offline.engine().ready() } catch (_: Throwable) { false }) -> "⚠ موديل Whisper مش نازل — نزّله من الإعدادات ← 🎙 التفريغ والصوت، ولحد ما ينزل التفريغ أونلاين."
        else -> "✅ الإنجليزي واللغات غير العربية بتتفرّغ على الجهاز (Whisper " + Offline.model().id + ")، والعربي أونلاين."
    }

    /** نص عربي؟ (٣٠٪ فأكتر من الحروف عربية) */
    internal fun isArabic(text: String): Boolean {
        var ar = 0; var all = 0
        for (c in text) {
            if (!c.isLetter()) continue
            all++
            if (c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F') ar++
        }
        return all > 0 && ar * 100 >= all * 30
    }

    private class OfflineFail : RuntimeException("offline-fallback")

    /**
     * جزء واحد أوفلاين. true = اتفرّغ واتحفظ · null = كمّل أونلاين (عربي / فشل / نتيجة فاضية على كلام).
     * بيحفظ بنفس شكل doChunk (إزالة جمل الجزء ثم إضافة + recordLang).
     */
    internal fun offlineChunk(run: Lecture.Run, wav: WavChunk, idx: Int, st: Double, en: Double): Boolean? {
        val d = run.doc
        if (!run.offline) return null
        val sess = run.offSess ?: Offline.Session().also { run.offSess = it }
        val res = try {
            Offline.transcribe(wav.bytes, en - st, Lecture.termsFromPrev(d, idx), { m -> LogStore.add("📴 [استوديو] $m") },
                { throw OfflineFail() }, { }, sess)
        } catch (_: OfflineFail) { return null } catch (e: Throwable) { LogStore.err("StudioTx:offline", e); return null }
        if (Offline.ranOffline.get() != true) return null
        val j = try { JSONObject(res.text) } catch (_: Throwable) { return null }
        val arr = j.optJSONArray("subtitles") ?: return null
        val dur = en - st
        val got = ArrayList<Lecture.Seg>()
        val all = StringBuilder()
        val code = Lecture.normLangCode(j.optString("detected_source_language"))
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val t = x.optString("original").trim()
            if (t.isEmpty()) continue
            all.append(t).append(' ')
            val s0 = x.optDouble("start", 0.0).coerceIn(0.0, dur)
            val e0 = x.optDouble("end", s0).coerceIn(s0, dur)
            got.add(Lecture.Seg(st + s0, st + e0, t, idx, "", code, "", Lecture.confOf(if (x.optBoolean("low_confidence")) 0.3 else -1.0)))
        }
        if (code == "ar" || isArabic(all.toString())) {
            if (run.offline) LogStore.add("📴 [استوديو] الكلام عربي — التفريغ العربي أونلاين بـ Flash Lite، والتشغيلة هتكمّل أونلاين")
            run.offline = false
            return null
        }
        if (got.isEmpty()) return if (wav.activeSec >= 5.0) null else true
        synchronized(d) {
            d.segs.removeAll { it.chunk == idx }
            d.segs.addAll(got)
            val i = Offline.langIds.indexOf(code)
            Lecture.recordLang(d, code, if (i > 0) Offline.langLabels[i] else "")
        }
        return true
    }
}
