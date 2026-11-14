package com.tttt.subtitler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * (v1002 — المرحلة 4) الذكاء الإضافي للاستوديو: اسأل التسجيل · فصول تلقائية · ترجمة جنب بعض ·
 * استخراج عناصر مهمة · وعود ومهام · خط زمني للنبرة · تفريغ تلقائي للمكالمات الجديدة ·
 * محضر اجتماع · قاموس شخصيات للمسلسلات · كورسات/Anki · تقدير التكلفة · ثقة منخفضة · علامات.
 * التخزين الجانبي: ملف intel لكل دوكيومنت (filesDir/lectures/<id>.intel.json) — نموذج Lecture.Doc
 * مبيتغيّرش في المرحلة دي غير Seg.conf (اللي بيتخزن في JSON بتاع الدوكيومنت نفسه).
 * كل طلبات Gemini النصية هنا على نفس مفاتيح keys/backup/extra ونفس model بتاع الترجمة،
 * وبتتنده من خيط خلفي (الواجهة بتناديها جوّا Thread).
 * مفيش محرك ترجمة جديد ولا كاتب SRT جديد — الترجمة الجنب بعض بتستعمل SubFileTr.translate.
 */
object StudioIntel {

    const val AUTO_CALL_KEY = "studio_auto_call_tx"
    const val AUTO_CALL_SEEN_KEY = "studio_auto_call_seen"
    // (v1003 — المرحلة 5) قايمة استثناء التفريغ التلقائي: URIs بصيغة نصية سطر لكل URI، زي قايمة seen
    // بالظبط (بحد 200). المفتاح studio_pin_* مش هنا — الـPIN مفاتيحه في StudioPrivacy.
    const val EXCL_KEY = "studio_auto_call_excl"
    const val LOW_CONF_MAX = 0.6

    // ===================== التخزين الجانبي =====================
    private fun intelFile(ctx: Context, id: String): File =
        File(ctx.filesDir, "lectures" + File.separator + id + ".intel.json")

    private fun loadIntel(ctx: Context, id: String): JSONObject {
        return try {
            val f = intelFile(ctx, id)
            if (!f.exists()) JSONObject() else JSONObject(f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun saveIntel(ctx: Context, id: String, o: JSONObject) {
        try {
            intelFile(ctx, id).writeText(o.toString(), Charsets.UTF_8)
        } catch (e: Throwable) {
            LogStore.err("StudioIntel:save", e)
        }
    }

    class Chapter(val start: Double, val title: String)
    class Bookmark(val start: Double, val label: String)
    class Promise(val who: String, val what: String, val whenText: String)
    class Extract(val kind: String, val text: String, val start: Double, val segIdx: Int)
    class ToneSeg(val start: Double, val end: Double, val tone: String)
    class AskResult(val answer: String, val start: Double?, val error: String, val parts: Int = 0) {
        val ok: Boolean get() = error.isEmpty()
    }
    class Estimate(val nChunks: Int, val chunkSec: Double, val warn: String)

    fun chapters(ctx: Context, d: Lecture.Doc): List<Chapter> {
        val out = ArrayList<Chapter>()
        val arr = loadIntel(ctx, d.id).optJSONArray("ch") ?: return out
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            out.add(Chapter(x.optDouble("t", 0.0), x.optString("ti")))
        }
        return out.sortedBy { it.start }
    }

    fun saveChapters(ctx: Context, d: Lecture.Doc, list: List<Chapter>) {
        val o = loadIntel(ctx, d.id)
        val arr = JSONArray()
        for (c in list) arr.put(JSONObject().put("t", c.start).put("ti", c.title))
        o.put("ch", arr)
        saveIntel(ctx, d.id, o)
    }

    fun chaptersText(ctx: Context, d: Lecture.Doc): String {
        val list = chapters(ctx, d)
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("الفصول:\n")
        for (c in list) sb.append(Lecture.fmtTime(c.start)).append("  ").append(c.title).append("\n")
        return sb.toString()
    }

    fun bookmarks(ctx: Context, d: Lecture.Doc): List<Bookmark> {
        val out = ArrayList<Bookmark>()
        val arr = loadIntel(ctx, d.id).optJSONArray("bm") ?: return out
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            out.add(Bookmark(x.optDouble("s", 0.0), x.optString("t")))
        }
        return out.sortedBy { it.start }
    }

    // ضغطة طويلة على جملة: إضافة/إزالة علامة على لحظتها — بترجّع true لو اتضافت، false لو اتشالت.
    fun toggleBookmark(ctx: Context, d: Lecture.Doc, seg: Lecture.Seg): Boolean {
        val o = loadIntel(ctx, d.id)
        val arr = o.optJSONArray("bm") ?: JSONArray()
        val list = ArrayList<Bookmark>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            list.add(Bookmark(x.optDouble("s", 0.0), x.optString("t")))
        }
        val hit = list.firstOrNull { kotlin.math.abs(it.start - seg.start) < 0.6 }
        if (hit != null) {
            list.remove(hit)
        } else {
            val label = seg.text.take(60)
            list.add(Bookmark(seg.start, label))
        }
        val out = JSONArray()
        for (b in list.sortedBy { it.start }) out.put(JSONObject().put("s", b.start).put("t", b.label))
        o.put("bm", out)
        saveIntel(ctx, d.id, o)
        return hit == null
    }

    fun promises(ctx: Context, d: Lecture.Doc): List<Promise> {
        val out = ArrayList<Promise>()
        val arr = loadIntel(ctx, d.id).optJSONArray("pr") ?: return out
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            out.add(Promise(x.optString("w"), x.optString("x"), x.optString("d")))
        }
        return out
    }

    fun savePromises(ctx: Context, d: Lecture.Doc, list: List<Promise>) {
        val o = loadIntel(ctx, d.id)
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("w", p.who).put("x", p.what).put("d", p.whenText))
        o.put("pr", arr)
        saveIntel(ctx, d.id, o)
    }

    // ===================== طلبات Gemini النصية =====================
    private fun gemKeys(): List<String> =
        (Cfg.keys("keys") + Cfg.keys("backup") + Cfg.keys("extra")).distinct()

    private fun textModel(): String = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }

    private fun askGeminiJson(prompt: String, maxTokens: Int): JSONObject? {
        val ks = gemKeys()
        if (ks.isEmpty()) return null
        val mdl = textModel()
        for (a in 0 until minOf(ks.size, 2)) {
            val key = ks[a % ks.size]
            try {
                val r = Api.generate(mdl, key, prompt, null, maxTokens, 0.1, true, false)
                val j = Lecture.parseJson(r.text)
                if (j != null) return j
            } catch (e: NoKey) {
                return null
            } catch (e: Throwable) {
                LogStore.err("StudioIntel:gem", e)
            }
        }
        return null
    }

    // نص التفريغ بتوقيتات — الإدخال المشترك لكل طلبات النص (اسأل/فصول/وعود/Anki).
    fun stampedText(d: Lecture.Doc, maxChars: Int): String {
        val segs = Studio.displaySegs(d)
        val sb = StringBuilder()
        for (s in segs) {
            val line = "[" + Lecture.fmtTime(s.start) + "] " + s.text + "\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString()
    }

    // نفس تقسيم stampedText بس على أجزاء — كل جزء بتوقيتاته المطلقة، فالقوايم بتتدمج بالترتيب على t.
    fun stampedParts(d: Lecture.Doc, partChars: Int): List<String> {
        val segs = Studio.displaySegs(d)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (s in segs) {
            val line = "[" + Lecture.fmtTime(s.start) + "] " + s.text + "\n"
            if (sb.length + line.length > partChars && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.clear()
            }
            sb.append(line)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    // ===================== 1) اسأل التسجيل =====================
    // الإجابة من نص التفريغ بس — البرومبت بيمنع الاختراع صراحة، والرد بييجي مع توقيت الجملة الداعمة.
    // النص الطويل بيتقسم على أجزاء 9000 حرف (بحد أقصى 8 أجزاء) — أول إجابة مش «لا يوجد» هي اللي بتتعرض.
    // parts = عدد الأجزاء اللي اتغطت بالبحث (بتتقال للمستخدم في الشاشة).
    fun askSync(ctx: Context, d: Lecture.Doc, q: String): AskResult {
        val segs = Studio.displaySegs(d)
        if (segs.isEmpty()) return AskResult("", null, "مفيش تفريغ لسه — فرّغ الأول")
        val ks = gemKeys()
        if (ks.isEmpty()) return AskResult("", null, "مفيش مفتاح Gemini — ضيفه من الإعدادات الأول")
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (s in segs) {
            val line = "[" + Lecture.fmtTime(s.start) + "] " + s.text + "\n"
            if (sb.length + line.length > 9000) {
                chunks.add(sb.toString())
                sb.clear()
            }
            sb.append(line)
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        val totalParts = chunks.size.coerceAtMost(8)
        val mdl = textModel()
        var lastErr = ""
        for ((ci, chunk) in chunks.withIndex()) {
            if (ci >= 8) break
            val prompt = buildString {
                append("جاوب على السؤال التالي من نص التفريغ ده بس — من غير أي إضافة أو اختراع أو معلومات من برّه النص.\n")
                append("لو الإجابة مش موجودة في النص قول «لا يوجد في التفريغ» وماتخترعش.\n")
                append("رجّع JSON فقط بدون شرح ولا markdown:\n")
                append("{\"answer\":\"...\",\"start\":0.0,\"found\":true}\n")
                append("start = بداية الجملة الداعمة للإجابة بالثواني (مثال 75.5)، أو -1 لو مفيش إجابة.\n\n")
                append("السؤال: ").append(q).append("\n\nنص التفريغ:\n").append(chunk)
            }
            for (a in 0 until minOf(ks.size, 2)) {
                val key = ks[a % ks.size]
                try {
                    val r = Api.generate(mdl, key, prompt, null, 2048, 0.1, true, false)
                    val j = Lecture.parseJson(r.text) ?: continue
                    val ans = j.optString("answer").trim()
                    if (ans.isBlank() || ans.contains("لا يوجد في التفريغ")) continue
                    if (!j.optBoolean("found", true)) continue
                    val st = j.optDouble("start", -1.0)
                    val start = if (st >= 0) st else null
                    return AskResult(ans.take(1500), start, "", totalParts)
                } catch (e: NoKey) {
                    return AskResult("", null, "مفيش مفتاح Gemini — ضيفه من الإعدادات الأول", totalParts)
                } catch (e: Throwable) {
                    lastErr = (e.message ?: e.javaClass.simpleName).take(100)
                    LogStore.err("StudioIntel:ask", e)
                }
            }
        }
        if (lastErr.isNotEmpty()) return AskResult("", null, "فشل السؤال: $lastErr", totalParts)
        return AskResult("لا يوجد في التفريغ — السؤال ده إجابته مش مذكورة في النص.", null, "", totalParts)
    }

    // ===================== 2) فصول تلقائية =====================
    // الفصول بتتولد جزء جزء (20 ألف حرف للجزء) عشان التسجيلات الطويلة تتغطى كلها — كل جزء توقيتاته
    // مطلقة، فالقوايم بتتدمج بالترتيب على t، وبحد أقصى 20 فصل إجمالي (أول 20 بالوقت).
    fun chaptersSync(ctx: Context, d: Lecture.Doc): List<Chapter> {
        val parts = stampedParts(d, 20000)
        if (parts.isEmpty()) return emptyList()
        if (parts.size == 1 && parts[0].length < 100) return emptyList()
        val out = ArrayList<Chapter>()
        for (part in parts) {
            val prompt = buildString {
                append("قسّم نص التفريغ التالي لفصول زي فصول يوتيوب — كل فصل عنوان قصير (2–6 كلمات) وتوقيت بدايته بالثواني.\n")
                append("الفصول لازم تغطي المحتوى كله بالترتيب، من غير ما تخترع مواضيع مش موجودة، وبحد أقصى 20 فصل.\n")
                append("رجّع JSON فقط بدون شرح ولا markdown:\n")
                append("{\"chapters\":[{\"t\":0.0,\"title\":\"...\"}]}\n\n")
                append(part)
            }
            val j = askGeminiJson(prompt, 4096) ?: continue
            val arr = j.optJSONArray("chapters") ?: continue
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                val title = x.optString("title").trim()
                if (title.isBlank()) continue
                out.add(Chapter(x.optDouble("t", 0.0).coerceIn(0.0, d.durSec), title.take(80)))
            }
        }
        val sorted = out.sortedBy { it.start }.take(20)
        if (sorted.isNotEmpty()) saveChapters(ctx, d, sorted)
        return sorted
    }

    // ===================== 5) وعود ومهام =====================
    fun promisesSync(ctx: Context, d: Lecture.Doc): List<Promise> {
        val cached = promises(ctx, d)
        if (cached.isNotEmpty()) return cached
        val text = stampedText(d, 20000)
        if (text.length < 100) return emptyList()
        val names = synchronized(d) { d.parties.toList() }
        val prompt = buildString {
            append("استخرج من نص التفريغ التالي كل الوعود والمهام: مين قال إنه هيعمل إيه وإمتى.\n")
            append("اكتب الوعد زي ما اتقال بالظبط من غير تلخيص يقصّه، و«when» زي ما اتقالت (مثال: «بكرة»، «الأحد»، «بعد أسبوع») أو فاضية لو مفيش موعد.\n")
            if (names.isNotEmpty()) append("الأطراف: ").append(names.joinToString("، ")).append(" — انسب كل وعد لصاحبه بالاسم.\n")
            append("رجّع JSON فقط بدون شرح ولا markdown:\n")
            append("{\"items\":[{\"who\":\"...\",\"what\":\"...\",\"when\":\"...\"}]}\n\n")
            append(text)
        }
        val j = askGeminiJson(prompt, 4096) ?: return emptyList()
        val arr = j.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Promise>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val what = x.optString("what").trim()
            if (what.isBlank()) continue
            out.add(Promise(x.optString("who").trim(), what.take(300), x.optString("when").trim()))
        }
        if (out.isNotEmpty()) savePromises(ctx, d, out)
        return out
    }

    // ===================== 4) استخراج عناصر مهمة =====================
    // Regex محلي (من غير طلب شبكة): أرقام تليفون مصرية · مبالغ بعملة · تواريخ/مواعيد · أسماء الأطراف.
    // أسماء الأشخاص من نص حر مابتتستخرجش بالـregex — الأطراف المعروفين بس (من v1001) هم اللي بيتعلّموا.
    fun extract(d: Lecture.Doc): List<Extract> {
        val out = ArrayList<Extract>()
        val phoneRe = Regex("(?:\\+?20|0)?1[0125][0-9]{8}")
        val amountRe = Regex("[0-9][0-9,.]*\\s*(جنيه|ج\\.م|دولار|ريال|درهم|يورو|ألف|مليون|مليار)")
        val dateRe = Regex("[0-9]{1,2}/[0-9]{1,2}(/[0-9]{2,4})?|بكرة|بعد بكرة|الأحد|الاثنين|الثلاثاء|الأربعاء|الخميس|الجمعة|السبت|يناير|فبراير|مارس|أبريل|مايو|يونيو|يوليو|أغسطس|سبتمبر|أكتوبر|نوفمبر|ديسمبر")
        val segs = Studio.displaySegs(d)
        val partyNames = synchronized(d) { d.parties.filter { it.isNotBlank() && it != "أنا" && it != "طرف تاني" } }
        for ((i, s) in segs.withIndex()) {
            for (m in phoneRe.findAll(s.text)) out.add(Extract("phone", m.value, s.start, i))
            for (m in amountRe.findAll(s.text)) out.add(Extract("amount", m.value, s.start, i))
            for (m in dateRe.findAll(s.text)) out.add(Extract("date", m.value, s.start, i))
            for (nm in partyNames) {
                if (s.text.contains(nm)) out.add(Extract("name", nm, s.start, i))
            }
        }
        return out
    }

    fun extractKindLabel(kind: String): String = when (kind) {
        "phone" -> "📞 رقم تليفون"
        "amount" -> "💰 مبلغ"
        "date" -> "📅 تاريخ / موعد"
        "name" -> "👤 اسم"
        else -> kind
    }

    fun extractKindColor(kind: String): Int = when (kind) {
        "phone" -> 0xFF64B5F6.toInt()
        "amount" -> 0xFFFFB300.toInt()
        "date" -> 0xFFAB47BC.toInt()
        "name" -> 0xFF4DB6AC.toInt()
        else -> 0xFF9E9E9E.toInt()
    }

    // بيحوّل نص موعد مستخرج («بكرة»، «بعد بكرة»، «الأحد»، dd/mm، «15 يناير») لوقت بداية حدث بالمللي.
    // القواعد: الساعة الافتراضية 9 الصبح بتوقيت الجهاز؛ يوم الأسبوع = أقرب وقوع جاي (لو النهارده نفس اليوم يبقى الأسبوع الجاي)؛
    // dd/mm من غير سنة = السنة دي، ولو التاريخ فات يبقى السنة الجاية؛ أي نص مش مفهوم بيرجّع null والوقت بيتساب للمستخدم.
    fun parseDateMillis(text: String, now: Long = System.currentTimeMillis()): Long? {
        val t = text.trim()
        if (t.isBlank()) return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 9)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val months = mapOf("يناير" to 0, "فبراير" to 1, "مارس" to 2, "أبريل" to 3, "مايو" to 4, "يونيو" to 5, "يوليو" to 6, "أغسطس" to 7, "سبتمبر" to 8, "أكتوبر" to 9, "نوفمبر" to 10, "ديسمبر" to 11)
        val weekdays = mapOf("الأحد" to Calendar.SUNDAY, "الاثنين" to Calendar.MONDAY, "الثلاثاء" to Calendar.TUESDAY, "الأربعاء" to Calendar.WEDNESDAY, "الخميس" to Calendar.THURSDAY, "الجمعة" to Calendar.FRIDAY, "السبت" to Calendar.SATURDAY)
        if (t.contains("بعد بكرة") || t.contains("بعد بكره")) {
            cal.add(Calendar.DAY_OF_YEAR, 2)
            return cal.timeInMillis
        }
        if (t.contains("بكرة") || t.contains("بكره")) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        if (t.contains("النهاردة") || t == "اليوم") return cal.timeInMillis
        val wd = weekdays[t]
        if (wd != null) {
            var ahead = (wd - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (ahead == 0) ahead = 7
            cal.add(Calendar.DAY_OF_YEAR, ahead)
            return cal.timeInMillis
        }
        val dm = Regex("([0-9]{1,2})/([0-9]{1,2})(?:/([0-9]{2,4}))?").find(t)
        if (dm != null) {
            val day = dm.groupValues[1].toIntOrNull() ?: return null
            val month = dm.groupValues[2].toIntOrNull() ?: return null
            if (day !in 1..31 || month !in 1..12) return null
            val yearTxt = dm.groupValues[3]
            val year = when {
                yearTxt.isBlank() -> cal.get(Calendar.YEAR)
                yearTxt.length <= 2 -> 2000 + (yearTxt.toIntOrNull() ?: return null)
                else -> yearTxt.toIntOrNull() ?: return null
            }
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month - 1)
            cal.set(Calendar.DAY_OF_MONTH, day)
            if (yearTxt.isBlank() && cal.timeInMillis < now) cal.add(Calendar.YEAR, 1)
            return cal.timeInMillis
        }
        for ((name, month) in months) {
            val m = Regex("([0-9]{1,2})\\s*" + name).find(t)
            if (m != null) {
                val day = m.groupValues[1].toIntOrNull() ?: return null
                if (day !in 1..31) return null
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, day)
                if (cal.timeInMillis < now) cal.add(Calendar.YEAR, 1)
                return cal.timeInMillis
            }
            if (t == name) {
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                if (cal.timeInMillis < now) cal.add(Calendar.YEAR, 1)
                return cal.timeInMillis
            }
        }
        return null
    }

    // «أضف للتقويم»: intent إدخال حدث عادي (مفيش سيم تقويم جوّا التطبيق — اتأكدنا بالبحث).
    // لو whenText اتفسّر لتاريخ، وقت البداية (+ ساعة نهاية) بيتحط في الحدث؛ لو مش مفهوم،
    // الوقت بيتساب فاضي والمستخدم بيختاره في شاشة التقويم نفسها.
    fun calendarIntent(title: String, desc: String, whenText: String = ""): Intent {
        val it = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        it.putExtra(CalendarContract.Events.TITLE, title)
        it.putExtra(CalendarContract.Events.DESCRIPTION, desc)
        val at = if (whenText.isNotBlank()) parseDateMillis(whenText) else null
        if (at != null) {
            it.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at)
            it.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at + 3600000L)
        }
        return it
    }

    // «أضف كجهة اتصال»: intent إدخال جهة عادي (مفيش سيم جهات جوّا التطبيق غير قراءة الاسم بالرقم).
    fun contactIntent(phone: String): Intent {
        val it = Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
        it.putExtra(ContactsContract.Intents.Insert.PHONE, phone)
        return it
    }

    // ===================== 6) خط زمني للنبرة =====================
    // من Seg.tone (v1001) — جمل متتالية بنفس النبرة بتتدمج في شريط واحد. الفاضي = «محايد».
    fun toneSegments(d: Lecture.Doc): List<ToneSeg> {
        val segs = Studio.displaySegs(d)
        val out = ArrayList<ToneSeg>()
        var cur: ToneSeg? = null
        for (s in segs) {
            val tone = s.tone.ifBlank { "محايد" }
            val c = cur
            if (c != null && c.tone == tone) {
                cur = ToneSeg(c.start, s.end, tone)
            } else {
                if (c != null) out.add(c)
                cur = ToneSeg(s.start, s.end, tone)
            }
        }
        if (cur != null) out.add(cur)
        return out
    }

    fun toneColor(tone: String): Int = when (tone) {
        "بيضحك", "مبسوط", "فرحان", "بيبتسم" -> 0xFFFFB300.toInt()
        "متضايق", "مستاء", "عصبي", "غاضب", "متوتر", "قلقان", "خايف" -> 0xFFE53935.toInt()
        "حزين", "زعلان", "مكتئب" -> 0xFFAB47BC.toInt()
        "هادي", "محايد", "جاد" -> 0xFF64B5F6.toInt()
        else -> 0xFF9E9E9E.toInt()
    }

    // ===================== 11) تقدير التكلفة/المدة =====================
    // عدد الأجزاء = المدة ÷ حجم الجزء (وارد 300/600 من الإعدادات، عادي 60) — والتحذير من حالة وارد
    // المحفوظة في Cfg (warid_state): يومي كل موديل مقابل سقفه، وكول داون. مفيش تتبّع كوتة جديد هنا.
    fun estimate(d: Lecture.Doc): Estimate {
        val cs = if (d.warid) WaridTx.chunkSec() else Lecture.CHUNK_SEC
        val n = if (d.durSec > 0) kotlin.math.ceil(d.durSec / cs).toInt().coerceAtLeast(1) else 0
        // تحذير الكوتة وارد بس — الدوكيومنتات غير الوارد مالهاش تحذير (مفيش تتبّع كوتة لمفاتيح المسار العادي).
        return Estimate(n, cs, if (d.warid) quotaWarn() else "")
    }

    fun estimateShort(d: Lecture.Doc): String {
        if (d.durSec <= 0) return ""
        val e = estimate(d)
        val mins = (d.durSec / 60.0).toInt().coerceAtLeast(1)
        return "$mins دقيقة ≈ ${e.nChunks} جزء"
    }

    fun quotaWarn(): String {
        return try {
            val raw = Cfg.str(WaridTx.STATE_KEY, "")
            if (raw.isBlank()) return ""
            val root = JSONObject(raw)
            val m = root.optJSONObject("m") ?: return ""
            var ready = 0
            var worst = ""
            var worstLeft = Int.MAX_VALUE
            for (model in WaridTx.CHAIN) {
                val o = m.optJSONObject(model) ?: continue
                val daily = o.optInt("d", 0)
                val cap = if (model.contains("lite")) WaridTx.LITE_DAILY_CAP else WaridTx.FLASH_DAILY_CAP
                val left = cap - daily
                if (left < worstLeft) {
                    worstLeft = left
                    worst = model
                }
                val cool = o.optLong("cool", 0L)
                if (daily < cap && cool <= System.currentTimeMillis()) ready++
            }
            if (ready == 0) return "⚠ كل موديلات وارد مستنفدة أو مهدّاة دلوقتي — التفريغ ممكن يفشل لحد ما الكوتة تتجدد"
            if (worstLeft in 0..5) return "⚠ موديل $worst قرب يخلص (باقي $worstLeft طلب اليوم)"
            ""
        } catch (_: Throwable) {
            ""
        }
    }

    // ===================== 12) ثقة منخفضة =====================
    // conf = -1 يعني الموديل مارجّعش ثقة (دوكيومنتات قديمة أو موديل مابيدعمهاش) — مابتتعلّمش.
    fun lowConfSegs(d: Lecture.Doc): List<Lecture.Seg> =
        Studio.displaySegs(d).filter { it.conf >= 0.0 && it.conf <= LOW_CONF_MAX }

    // (G1) علم لمرة واحدة: إعادة تفريغ جملة بتحط الجزء ده على «الموديل الأقوى» — في الذاكرة بس،
    // وبيتمسح أول ما الجزء يشتغل (سواء نجح أو فشل). المفتاح: id الدوكيومنت + رقم الجزء.
    private val accurateChunks = java.util.Collections.synchronizedSet(HashSet<String>())

    fun flagAccurate(d: Lecture.Doc, chunk: Int) {
        accurateChunks.add(d.id + "#" + chunk)
    }

    fun takeAccurate(d: Lecture.Doc, chunk: Int): Boolean = accurateChunks.remove(d.id + "#" + chunk)

    // إعادة تفريغ المقطع: الجزء كله (300ث وارد / 60ث عادي) بيتعاد — الجملة لوحدها مالهاش طلب صوت مستقل.
    // بيمسح جمل الجزء ويحطه في failed، وبعدها Lecture.start بيكمّل الناقص بس.
    // (G1) الجزء المعاد بيتعلم «أقوى» — المسار العادي بيستعمل LEC_MODEL_ACCURATE، ووارد بيبدأ
    // السلسلة من أول موديل Flash مش lite (شوف takeAccurate في doChunk وtranscribeChunk).
    fun retranscribeSeg(ctx: Context, d: Lecture.Doc, seg: Lecture.Seg): Boolean {
        if (Lecture.isRunning(d.id)) return false
        synchronized(d) {
            d.segs.removeAll { it.chunk == seg.chunk }
            d.done.remove(seg.chunk)
            d.failed.add(seg.chunk)
        }
        flagAccurate(d, seg.chunk)
        Lecture.save(ctx, d)
        Lecture.start(ctx, d)
        return true
    }

    // ===================== 9) قاموس شخصيات المسلسلات =====================
    // مفتاح السلسلة = عنوان الدوكيومنت الظاهر (displayTitle) — ملف واحد لكل السلاسل.
    fun seriesKey(d: Lecture.Doc): String = Studio.displayTitle(d).ifBlank { d.title }.ifBlank { "مسلسل" }

    private fun seriesFile(ctx: Context): File = File(ctx.filesDir, "studio_series.json")

    private fun loadSeries(ctx: Context): JSONObject {
        return try {
            val f = seriesFile(ctx)
            if (!f.exists()) JSONObject() else JSONObject(f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    fun glossary(ctx: Context, d: Lecture.Doc): List<String> {
        val arr = loadSeries(ctx).optJSONArray(seriesKey(d)) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out.filter { it.isNotBlank() }
    }

    fun setGlossary(ctx: Context, d: Lecture.Doc, names: List<String>) {
        val o = loadSeries(ctx)
        o.put(seriesKey(d), JSONArray(names.filter { it.isNotBlank() }.distinct()))
        try {
            seriesFile(ctx).writeText(o.toString(), Charsets.UTF_8)
        } catch (e: Throwable) {
            LogStore.err("StudioIntel:series", e)
        }
    }

    // أسماء القاموس بتتضاف لقايمة terms اللي بتروح لبرومبت التفريغ (عبر Lecture.start → Run.showGlossary).
    fun glossaryTerms(ctx: Context, d: Lecture.Doc): String = glossary(ctx, d).joinToString("، ")

    // ===================== 10) كورسات / Anki =====================
    private fun coursesFile(ctx: Context): File = File(ctx.filesDir, "studio_courses.json")

    class Course(val name: String, val docIds: List<String>)

    fun listCourses(ctx: Context): List<Course> {
        val out = ArrayList<Course>()
        try {
            val f = coursesFile(ctx)
            if (!f.exists()) return out
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                val ids = ArrayList<String>()
                val a = x.optJSONArray("docs") ?: JSONArray()
                for (j in 0 until a.length()) ids.add(a.optString(j))
                out.add(Course(x.optString("n"), ids))
            }
        } catch (e: Throwable) {
            LogStore.err("StudioIntel:courses", e)
        }
        return out
    }

    private fun saveCourses(ctx: Context, list: List<Course>) {
        try {
            val arr = JSONArray()
            for (c in list) arr.put(JSONObject().put("n", c.name).put("docs", JSONArray(c.docIds)))
            coursesFile(ctx).writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Throwable) {
            LogStore.err("StudioIntel:courses", e)
        }
    }

    fun assignCourse(ctx: Context, name: String, docId: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val list = listCourses(ctx).toMutableList()
        val idx = list.indexOfFirst { it.name == clean }
        if (idx >= 0) {
            val ids = list[idx].docIds.toMutableList()
            if (!ids.contains(docId)) ids.add(docId)
            list[idx] = Course(clean, ids)
        } else {
            list.add(Course(clean, listOf(docId)))
        }
        saveCourses(ctx, list)
    }

    fun removeFromCourse(ctx: Context, name: String, docId: String) {
        val list = listCourses(ctx).toMutableList()
        val idx = list.indexOfFirst { it.name == name }
        if (idx < 0) return
        val ids = list[idx].docIds.filter { it != docId }
        if (ids.isEmpty()) list.removeAt(idx) else list[idx] = Course(name, ids)
        saveCourses(ctx, list)
    }

    fun courseOf(ctx: Context, docId: String): String {
        for (c in listCourses(ctx)) if (c.docIds.contains(docId)) return c.name
        return ""
    }

    // القاموس التراكمي للكورس: قسم «أهم المصطلحات» من تنظيم كل محاضرة فيه (v1000 — التنظيم للمحاضرات بس).
    fun courseGlossary(ctx: Context, course: Course): List<String> {
        val out = LinkedHashSet<String>()
        for (id in course.docIds) {
            val doc = try {
                Lecture.list(ctx).firstOrNull { it.id == id }
            } catch (_: Throwable) {
                null
            } ?: continue
            val org = synchronized(doc) { doc.organized }
            if (org.isBlank()) continue
            var inTerms = false
            for (line in org.lines()) {
                val t = line.trim()
                if (t.startsWith("##")) {
                    inTerms = t.contains("مصطلح")
                    continue
                }
                if (inTerms && t.isNotBlank()) out.add(t.removePrefix("•").removePrefix("-").trim().take(120))
            }
        }
        return out.toList()
    }

    // صفوف CSV (من غير هيدر) لمحاضرة واحدة — بيعمل طلب Gemini واحد؛ بيرجّع null لو مفيش بطاقات.
    private fun ankiCsvBody(ctx: Context, d: Lecture.Doc): String? {
        val src = synchronized(d) { d.organized }.ifBlank { stampedText(d, 12000) }
        if (src.length < 100) return null
        val prompt = buildString {
            append("اعمل 15 سؤال مراجعة (Q/A) من النص التالي — أسئلة فهم مش حفظ حرفي، والإجابة جملة واحدة قصيرة من النص.\n")
            append("رجّع JSON فقط بدون شرح ولا markdown:\n")
            append("{\"cards\":[{\"q\":\"...\",\"a\":\"...\"}]}\n\n")
            append(src.take(12000))
        }
        val j = askGeminiJson(prompt, 4096) ?: return null
        val arr = j.optJSONArray("cards") ?: return null
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val q = x.optString("q").trim().replace(";", "،").replace("\n", " ")
            val a = x.optString("a").trim().replace(";", "،").replace("\n", " ")
            if (q.isBlank() || a.isBlank()) continue
            sb.append("\"").append(q).append("\";\"").append(a).append("\"\n")
        }
        if (sb.length < 5) return null
        return sb.toString()
    }

    // بطاقات Anki: سؤال/جواب من التنظيم (أو من التفريغ لو مفيش تنظيم) — CSV بفاصلة منقوطة عشان العربي فيه فواصل.
    fun ankiCsv(ctx: Context, d: Lecture.Doc): File? {
        val body = ankiCsvBody(ctx, d) ?: return null
        return try {
            val dir = File(ctx.filesDir, "studio_anki").also { it.mkdirs() }
            val safe = TrLib.safeName(Studio.displayTitle(d).ifBlank { "lecture" })
            val f = File(dir, safe + ".csv")
            f.writeText("question;answer\n" + body, Charsets.UTF_8)
            f
        } catch (e: Throwable) {
            LogStore.err("StudioIntel:anki", e)
            null
        }
    }

    // (G2) ملف CSV واحد للكورس كله: هيدر مرة واحدة + صفوف كل محاضراته، باسم الكورس.
    fun ankiCsvForCourse(ctx: Context, docs: List<Lecture.Doc>, courseName: String): File? {
        val sb = StringBuilder()
        sb.append("question;answer\n")
        for (doc in docs) {
            val body = try {
                ankiCsvBody(ctx, doc)
            } catch (e: Throwable) {
                LogStore.err("StudioIntel:ankiCourse", e)
                null
            } ?: continue
            sb.append(body)
        }
        if (sb.length < 30) return null
        return try {
            val dir = File(ctx.filesDir, "studio_anki").also { it.mkdirs() }
            val safe = TrLib.safeName(courseName.ifBlank { "course" })
            val f = File(dir, safe + ".csv")
            f.writeText(sb.toString(), Charsets.UTF_8)
            f
        } catch (e: Throwable) {
            LogStore.err("StudioIntel:ankiCourse", e)
            null
        }
    }

    // ===================== 8) محضر الاجتماع =====================
    // المشتق من organize (شكل MEETING في v1000: قرارات + مهام + مسؤول + موعد) + وعود مخزنة.
    // القرارات/المهام بيتقروا من سطور التنظيم اللي فيها الكلمات الدالة — تقريبي، مش تحليل نحوي.
    class Minutes(val decisions: List<String>, val tasks: List<String>, val rawOrganized: String)

    fun minutes(ctx: Context, d: Lecture.Doc): Minutes {
        val org = synchronized(d) { d.organized }
        val decisions = ArrayList<String>()
        val tasks = ArrayList<String>()
        for (line in org.lines()) {
            val t = line.trim().removePrefix("•").removePrefix("-").trim()
            if (t.isBlank()) continue
            if (t.contains("قرار") || t.contains("اتفق") || t.contains("تم الاتفاق")) decisions.add(t)
            if (t.contains("مهمة") || t.contains("مسؤول") || t.contains("هيعمل") || t.contains("المطلوب")) tasks.add(t)
        }
        return Minutes(decisions, tasks, org)
    }

    // ===================== 7) تفريغ تلقائي للمكالمات الجديدة =====================
    // مفيش WorkManager في التطبيق ولا scheduler خلفي للمكالمات — الفحص بيشتغل لما الاستوديو يتفتح
    // (StudioUi.show بينادي checkAutoCalls) أو من زرار «افحص دلوقتي». مقفول افتراضيًا.
    // الشروط: واي-فاي + شاحن بس. الإشعار: NotificationManager عادي زي MusicService.
    fun isAutoCallOn(): Boolean = Cfg.bool(AUTO_CALL_KEY, false)

    fun setAutoCallOn(v: Boolean) {
        Cfg.put(AUTO_CALL_KEY, if (v) "1" else "0")
    }

    fun onWifi(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Throwable) {
            false
        }
    }

    fun onCharger(ctx: Context): Boolean {
        return try {
            val it = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = it?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            plugged != 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun seenUris(ctx: Context): MutableSet<String> {
        val raw = Cfg.str(AUTO_CALL_SEEN_KEY, "")
        return raw.lines().map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
    }

    private fun saveSeen(ctx: Context, seen: Set<String>) {
        Cfg.put(AUTO_CALL_SEEN_KEY, seen.take(200).joinToString("\n"))
    }

    // (v1003 — المرحلة 5) قايمة الاستثناء — نفس نمط seenUris/saveSeen بالظبط (سطور، بحد 200).
    // بتتخزن URIs نصية؛ الواجهة بتحل الاسم بـ CallKit.resolve وقت العرض. مفتاح الاستثناء مش مفتاح
    // بصمة صوت (CallKit.contactKey) — البصمة بتتيتّم لو الاسم اتغيّر، والـURI ثابت.
    fun excludedUris(ctx: Context): MutableSet<String> {
        val raw = Cfg.str(EXCL_KEY, "")
        return raw.lines().map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
    }

    fun saveExcluded(ctx: Context, set: Set<String>) {
        Cfg.put(EXCL_KEY, set.take(200).joinToString("\n"))
    }

    fun isExcluded(ctx: Context, uri: String): Boolean = excludedUris(ctx).contains(uri.trim())

    // بيفحص التسجيلات الجديدة ويفرّغها — لازم يتنده من خيط خلفي. بيرجّع رسالة حالة للعرض.
    fun checkAutoCalls(ctx: Context): String {
        if (!isAutoCallOn()) return ""
        if (!onWifi(ctx)) return "التفريغ التلقائي واقف: محتاج واي-فاي"
        if (!onCharger(ctx)) return "التفريغ التلقائي واقف: محتاج الشاحن"
        val seen = seenUris(ctx)
        val existing = try {
            Lecture.list(ctx).map { it.uri }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
        var started = 0
        try {
            val recs = Lecture.scanAudio(ctx).filter { it.isRec }
            for (r in recs) {
                val uri = r.uri.toString()
                if (isExcluded(ctx, uri)) continue   // (v1003) مستثنى — ومبيتحطش في seen عشان لو اتشال من الاستثناء يتفرّغ في الفحص الجاي
                if (seen.contains(uri) || existing.contains(uri)) continue
                seen.add(uri)
                val who = try {
                    CallKit.resolve(ctx, r.name)
                } catch (_: Throwable) {
                    CallKit.Who("", "")
                }
                val title = who.name.ifBlank { r.name.substringBeforeLast('.') }
                val d = Lecture.newDoc(ctx, "rec", title, uri, "")
                Studio.setType(ctx, d, "call", false)
                val other = who.name.ifBlank { "طرف تاني" }
                synchronized(d) {
                    if (CallKit.hasVoice(ctx, "me")) {
                        d.parties.add("أنا")
                        d.parties.add(other)
                    } else {
                        d.parties.add(other)
                    }
                }
                Lecture.save(ctx, d)
                Lecture.start(ctx, d)
                started++
            }
        } catch (e: Throwable) {
            LogStore.err("StudioIntel:autoCall", e)
            return "⚠ فحص المكالمات الجديدة فشل"
        }
        saveSeen(ctx, seen)
        if (started > 0) notifyReady(ctx, started)
        return if (started > 0) "$started مكالمات جاهزة — بدأ تفريغها" else ""
    }

    private fun notifyReady(ctx: Context, n: Int) {
        try {
            val app = ctx.applicationContext
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = "studio_auto"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(ch, "تفريغ المكالمات التلقائي", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, ch) else Notification.Builder(app)
            val notif = nb.setContentTitle("🎙 الاستوديو").setContentText("$n مكالمات جاهزة — بدأ تفريغها").setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).setAutoCancel(true).build()
            nm.notify(91002, notif)
        } catch (e: Throwable) {
            LogStore.err("StudioIntel:notif", e)
        }
    }
}
