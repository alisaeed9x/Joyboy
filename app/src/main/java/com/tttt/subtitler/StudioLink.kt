package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * (v999 — ملاحظة 4) رابط ترجمة الاستوديو بالمشغّلين.
 * المشكلة: الاستوديو بيحفظ تحت lec:<id>، والمشغّل بيحفظ تحت هوية الفيديو (f:الاسم:الحجم / yt:<id> / u:...)
 * فترجمة الاستوديو مكانتش بتظهر لما الفيديو يتفتح من المشغّل العادي.
 * الحل هنا في مكان واحد (مش نسخة رابعة من حساب الهوية):
 *  • نفس المفتاح للمحلي ويوتيوب: translateToSrt بتحفظ نسخة تانية تحت مفتاح المشغّل.
 *  • خريطة للينكات العامة: المشغّل عند الفتح بيدوّر على Doc مطابق (uri/link/صفحة/yt id) وينسخ ملف lec: تحت مفتاح الفيديو.
 *  • الاختيار المتذكّر لكل فيديو: Cfg sub_choice:<vid> = engine | studio | sidecar (نفس نمط sub_offset_ms:<vid>).
 *  • علامة: Cfg studio_for:<vid> = id الدوكيومنت اللي نسخته شغّالة دلوقتي تحت مفتاح الفيديو.
 * كل الدوال آمنة من خيط خلفي. نسخ الملفات بايت-لبايت من غير إعادة قراءة/كتابة JSON.
 */
object StudioLink {
    const val CHOICE_ENGINE = "engine"
    const val CHOICE_STUDIO = "studio"
    const val CHOICE_SIDECAR = "sidecar"

    fun choiceKey(vid: String): String = "sub_choice:$vid"
    fun studioKey(vid: String): String = "studio_for:$vid"

    fun choice(ctx: Context, vid: String): String = try {
        Cfg.str(choiceKey(vid), "")
    } catch (_: Throwable) {
        ""
    }

    fun remember(ctx: Context, vid: String, c: String) {
        try {
            Cfg.put(choiceKey(vid), c)
        } catch (_: Throwable) {
        }
    }

    fun studioFor(ctx: Context, vid: String): String = try {
        Cfg.str(studioKey(vid), "")
    } catch (_: Throwable) {
        ""
    }

    fun markStudio(ctx: Context, vid: String, docId: String) {
        try {
            Cfg.put(studioKey(vid), docId)
        } catch (_: Throwable) {
        }
    }

    fun clearStudioMark(ctx: Context, vid: String) {
        try {
            Cfg.put(studioKey(vid), "")
        } catch (_: Throwable) {
        }
    }

    // هوية الملف المحلي — نفس صيغة Player.videoId والمحلي في MtTranslate.localVid:
    // f:الاسم:الحجم من ContentResolver، وfile:// من File، غير كده f:<uri> زي ما هو.
    fun vidForLocal(ctx: Context, uriStr: String): String? {
        if (uriStr.isBlank()) return null
        try {
            val u = Uri.parse(uriStr)
            if (u.scheme == "content") {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return "f:" + c.getString(0) + ":" + c.getLong(1)
                }
            }
            if (u.scheme == "file") {
                val f = File(u.path ?: "")
                if (f.isFile) return "f:" + f.name + ":" + f.length()
            }
        } catch (_: Throwable) {
        }
        return "f:$uriStr"
    }

    // هوية المشغّل من الدوكيومنت — محلي أو يوتيوب بس. اللينك العام بيرجّع null
    // (هويته مش دالة صرفة في اللينك: محتاجة WebIdent/idOverride اللي موجودة وقت التشغيل بس).
    fun vidForDoc(ctx: Context, d: Lecture.Doc): String? {
        if (d.uri.isNotBlank()) return vidForLocal(ctx, d.uri)
        if (d.link.isBlank()) return null
        val yt = Links.ytId(d.link) ?: return null
        return "yt:$yt"
    }

    fun progressDir(ctx: Context): File = File(ctx.filesDir, "progress")

    fun loadSaved(ctx: Context, id: String): Saved? = try {
        TrLib.loadSaved(ctx, id)
    } catch (_: Throwable) {
        null
    }

    fun hasSubs(ctx: Context, id: String): Boolean {
        val n = loadSaved(ctx, id)?.subs?.size ?: 0
        return n > 0
    }

    fun studioSubs(ctx: Context, d: Lecture.Doc): List<Sub> {
        val s = loadSaved(ctx, Studio.lecId(d)) ?: return emptyList()
        return s.subs
    }

    // نسخة ترجمة الاستوديو تحت مفتاح المشغّل (نفس ملف lec: بيتفضل للمكتبة).
    // false = مفيش ملف lec: أصلًا أو النسخ فشل — المتصل يكمّل من غيرها.
    fun copyStudioToVid(ctx: Context, vid: String, d: Lecture.Doc): Boolean {
        val dir = progressDir(ctx)
        val src = File(dir, Store.keyFor(Studio.lecId(d)) + ".json")
        if (!src.exists()) return false
        val dst = File(dir, Store.keyFor(vid) + ".json")
        return try {
            dir.mkdirs()
            src.copyTo(dst, true)
            markStudio(ctx, vid, d.id)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // خبّي ترجمة المحرك الحالية قبل ما الاستوديو يكتب فوقها — عشان اختيار «ترجمة الفيديو» بعدين يرجّعها.
    fun stashEngine(ctx: Context, vid: String): Boolean {
        val dir = progressDir(ctx)
        val src = File(dir, Store.keyFor(vid) + ".json")
        if (!src.exists()) return false
        val dst = File(dir, Store.keyFor(vid) + ".eng.json")
        return try {
            src.copyTo(dst, true)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun hasEngineStash(ctx: Context, vid: String): Boolean {
        val f = File(progressDir(ctx), Store.keyFor(vid) + ".eng.json")
        return f.exists()
    }

    // وصف المصدر زي ما بيظهر في المشغّل: 🎙 Studio · التاريخ · اللغة · النوع.
    fun label(ctx: Context, d: Lecture.Doc): String {
        val parts = ArrayList<String>()
        parts.add("🎙 Studio")
        val date = try {
            if (d.created > 0) SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(d.created)) else ""
        } catch (_: Throwable) {
            ""
        }
        if (date.isNotBlank()) parts.add(date)
        if (d.lang.isNotBlank()) parts.add(d.lang)
        val typeLabel = try {
            Studio.typeOf(d).label
        } catch (_: Throwable) {
            ""
        }
        if (typeLabel.isNotBlank()) parts.add(typeLabel)
        return parts.joinToString(" · ")
    }

    // ملف SRT عند الطلب من ترجمة الاستوديو المحفوظة (للمشاركة/التصدير) — بيتكتب في الكاش بس،
    // ومفيش مجلد SRT دائم جديد: المشغّل بيقرا ملف Store مش SRT.
    fun exportSrt(ctx: Context, d: Lecture.Doc): File? {
        val txt = try {
            TrLib.srtText(ctx, Studio.lecId(d))
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            val f = File(ctx.cacheDir, TrLib.safeName(Studio.displayTitle(d)) + ".srt")
            f.writeText(txt, Charsets.UTF_8)
            f
        } catch (_: Throwable) {
            null
        }
    }

    // ===================== مطابقة الدوكيومنت بالفيديو الشغّال =====================
    // ترتيب المطابقة: علامة studio_for (سريعة، من غير مسح) ← مسح خفيف لملفات lectures/*.json
    // (عناوين بس من غير تفريغ segs، والأحدث أولًا، وبيقف على أول مطابقة).
    // uri: نص الـUri المحلي · link: اللينك الأصلي لو اتبعت للمشغّل · ref: صفحة/Referer الفيديو.
    fun findDoc(ctx: Context, vid: String, uriStr: String?, linkStr: String?, ref: String?): Lecture.Doc? {
        val marked = studioFor(ctx, vid)
        if (marked.isNotBlank()) {
            val d = try {
                Lecture.load(ctx, marked)
            } catch (_: Throwable) {
                null
            }
            if (d != null) return d
        }
        val dir = File(ctx.filesDir, "lectures")
        val files = try {
            dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }
        } catch (_: Throwable) {
            return null
        }
        for (f in files) {
            val head = readHeader(f) ?: continue
            if (!matches(head, vid, uriStr, linkStr, ref)) continue
            val full = try {
                Lecture.load(ctx, head.id)
            } catch (_: Throwable) {
                null
            }
            if (full != null) return full
        }
        return null
    }

    // عناوين الدوكيومنت بس (id/uri/link/title/created/lang/type) — من غير segs.
    private fun readHeader(f: File): Lecture.Doc? {
        return try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val id = o.optString("id")
            if (id.isBlank()) return null
            val d = Lecture.Doc(id)
            d.title = o.optString("title")
            d.kind = o.optString("kind", "video")
            d.uri = o.optString("uri")
            d.link = o.optString("link")
            d.created = o.optLong("created", 0L)
            d.lang = o.optString("lang")
            d.type = o.optString("ty")
            d.typeAuto = o.optBoolean("tyA", false)
            d.typeConf = o.optDouble("tyC", 0.0)
            d.autoTitle = o.optString("aTi")
            d.langCode = o.optString("lCd")
            d.durSec = o.optDouble("dur", 0.0)
            d
        } catch (_: Throwable) {
            null
        }
    }

    private fun matches(d: Lecture.Doc, vid: String, uriStr: String?, linkStr: String?, ref: String?): Boolean {
        if (d.uri.isNotBlank() && uriStr != null && d.uri == uriStr) return true
        if (d.link.isBlank()) return false
        if (linkStr != null && d.link == linkStr) return true
        if (ref != null && ref.isNotBlank() && d.link == ref) return true
        if (ref != null && ref.isNotBlank()) {
            try {
                if (WebIdent.specific(ref) && WebIdent.pageKey(d.link) == WebIdent.pageKey(ref)) return true
            } catch (_: Throwable) {
            }
        }
        if (vid.startsWith("yt:")) {
            val yt = try {
                Links.ytId(d.link)
            } catch (_: Throwable) {
                null
            }
            if (yt != null && vid == "yt:$yt") return true
        }
        return false
    }
}
