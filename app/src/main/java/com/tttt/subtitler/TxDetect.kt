package com.tttt.subtitler

import android.content.Context

/**
 * (v1000 — المرحلة 2، §3.2) اكتشاف نوع المحتوى قبل التفريغ.
 * طبقتين، بالترتيب: إشارات مجانية (من غير طلب شبكة) ← طلب خفيف لجيميناي على أول 90 ثانية.
 * محافظ عمدًا: الإشارات المجانية بتقترح نوع بس لو الإشارة قوية، والطلب الخفيف بيتقبل بس لو ثقته ≥ 0.7.
 * الفشل صامت (null) ومبيوقفش التفريغ أبدًا — Lecture.start() بيتعامل مع null على إنه «مش عارفين».
 * مفيش مفتاح تبديل للاكتشاف: شغال افتراضيًا حسب البرنامج.
 * بيتنده من Lecture.detectType() بس — من جوه Lecture.start()، قبل خيوط التفريغ.
 */
object TxDetect {

    data class Result(val type: String, val conf: Double, val reason: String)

    private fun keys(): List<String> = (Cfg.keys("keys") + Cfg.keys("backup") + Cfg.keys("extra")).distinct()

    /**
     * إشارات مجانية (من غير جيميناي): اسم/مسار الملف، نوع المصدر، عنوان يوتيوب، المدة.
     * بيرجّع اقتراح بس لو الإشارة قوية — وإلا null (والمتصل يجرّب الطلب الخفيف).
     * kind: video · audio · rec · link — نفس قيم Lecture.Doc.kind.
     * title: عنوان العرض (لليوتيوب: العنوان المتحل من openSource؛ للملفات: اسم الملف).
     * path: uri أو link — بيتفحص فيه مجلد/اسم الملف.
     */
    fun freeSignals(kind: String, title: String, path: String, durSec: Double): Result? {
        val name = title.trim()
        val p = path.trim()
        // تسجيل مكالمة: اسم أو مسار شبه «Recordings/Voice Recorder/Call» — نفس نمط Lecture.REC_HINT.
        if (Lecture.REC_HINT.containsMatchIn(p) || Lecture.REC_HINT.containsMatchIn(name)) {
            return Result("call", 0.85, "اسم/مسار الملف شبه تسجيل مكالمة")
        }
        if (kind == "rec") {
            return Result("call", 0.8, "المصدر تسجيل من الجهاز")
        }
        val t = name.lowercase()
        if (t.contains("lecture") || name.contains("محاضرة") || name.contains("محاضره")) {
            return Result("lecture", 0.8, "العنوان فيه «Lecture/محاضرة»")
        }
        if (t.contains("meeting") || name.contains("اجتماع")) {
            return Result("meeting", 0.8, "العنوان فيه «Meeting/اجتماع»")
        }
        if (name.contains("مسلسل") || name.contains("فيلم") || t.contains("episode")) {
            return Result("show", 0.75, "العنوان فيه «مسلسل/فيلم/episode»")
        }
        return null
    }

    // برومبت الطلب الخفيف: نوع واحد + ثقة + سبب — JSON فقط، من غير أي كلام تاني.
    private fun detectPrompt(): String = buildString {
        append("صنّف المقطع الصوتي ده لنوع واحد بس من الأنواع دي: lecture (محاضرة/شرح)، call (مكالمة بين طرفين)، subs (حوار فيلم/مسلسل)، show (مسلسل/فيلم)، meeting (اجتماع)، audio (صوت عادي مش مصنّف).\n")
        append("رجّع JSON فقط بدون شرح ولا markdown، بالشكل ده بالظبط:\n")
        append("{\"type\":\"call\",\"confidence\":0.85,\"reason\":\"صوتين بيتناوبوا الكلام بأسلوب مكالمة\"}\n")
        append("confidence من 0.0 لـ 1.0 — حط رقم قليل (أقل من 0.7) لو مش متأكد. reason جملة قصيرة واحدة بالعربي.")
    }

    /**
     * الطلب الخفيف: جيميناي على مقطع صوت قصير (أول 60–90 ثانية).
     * نفس نمط Lecture.titleFromText: مفاتيح Cfg (بحد أقصى مفتاحين)، موديل التفريغ lecModel()،
     * Api.generate بحرارة 0.1 و JSON، وفشل صامت (null) مع تسجيل في LogStore.
     * مبرميش استثناء أبدًا.
     */
    fun detectLight(ctx: Context, wavBytes: ByteArray, mime: String = "audio/wav"): Result? {
        val ks = keys()
        if (ks.isEmpty()) return null
        val mdl = Lecture.lecModel()
        val prompt = detectPrompt()
        for (a in 0 until minOf(ks.size, 2)) {
            val key = ks[a % ks.size]
            try {
                val r = Api.generate(mdl, key, prompt, wavBytes, 256, 0.1, true, false)
                val j = Lecture.parseJson(r.text) ?: continue
                val type = j.optString("type").trim().lowercase()
                if (type !in listOf("lecture", "call", "subs", "show", "meeting", "audio")) continue
                val conf = j.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
                val reason = j.optString("reason").trim().take(140)
                return Result(type, conf, reason)
            } catch (e: NoKey) {
                return null
            } catch (e: Throwable) {
                LogStore.err("TxDetect:light", e)
            }
        }
        return null
    }
}
