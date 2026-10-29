package com.tttt.subtitler

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger

/**
 * (v529) ترجمة «ملفات الترجمة الجاهزة» (سوفت سب): ملف جنب الفيديو / ملف تختاره / مسار جوه الفيديو.
 * المحرك: جوجل (من غير مفتاح، فصحى) أو جيميناي (بمفاتيحك، باللهجة والأسلوب المختارين في الإعدادات).
 * مستقل عن محرك الصوت: بياخد جمل (وقت + نص) ويرجّع نص مترجم لكل جملة.
 */
object SubFileTr {
    class Cue(val start: Double, val end: Double, val text: String)
    class Out(val texts: List<String>, val failed: Int, val error: String)

    // ===== قراءة الملفات =====
    fun decode(b: ByteArray): String {
        if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xFE) return String(b, Charsets.UTF_16LE)
        if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xFF) == 0xFF) return String(b, Charsets.UTF_16BE)
        val u = String(b, Charsets.UTF_8)
        if (!u.contains('\uFFFD')) return u
        return try { String(b, Charset.forName("windows-1252")) } catch (_: Throwable) { u }
    }

    private val TS = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})")
    private val TAGS = Regex("<[^>]*>|\\{[^}]*\\}")

    private fun sec(h: String, m: String, s: String, ms: String): Double =
        (if (h.isEmpty()) 0 else h.toInt()) * 3600.0 + m.toInt() * 60 + s.toInt() + ms.padEnd(3, '0').take(3).toInt() / 1000.0

    private fun assTime(s: String): Double = try {
        val p = s.trim().split(":")
        if (p.size != 3) -1.0 else p[0].toInt() * 3600.0 + p[1].toInt() * 60 + p[2].toDouble()
    } catch (_: Throwable) { -1.0 }

    /** SRT / WebVTT / ASS / SSA → جمل مرتبة بالوقت */
    fun parse(raw: String): List<Cue> {
        val text = raw.removePrefix("\uFEFF").replace("\r", "")
        val out = ArrayList<Cue>()
        val isAss = text.contains("[Events]") || text.lineSequence().any { it.startsWith("Dialogue:") }
        if (isAss) {
            var fmt = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
            for (ln in text.lines()) {
                val l = ln.trim()
                if (l.startsWith("Format:") && l.contains("Start") && l.contains("Text")) { fmt = l.substringAfter(":").split(",").map { it.trim() }; continue }
                if (!l.startsWith("Dialogue:")) continue
                val f = l.substringAfter(":").trimStart().split(",", limit = fmt.size)
                val si = fmt.indexOf("Start"); val ei = fmt.indexOf("End"); val ti = fmt.indexOf("Text")
                if (si < 0 || ei < 0 || ti < 0 || f.size <= ti) continue
                val body = f[ti]
                if (body.contains("\\p1")) continue   // رسم (مش كلام)
                val a = assTime(f[si]); val b = assTime(f[ei])
                val t = body.replace(Regex("\\{[^}]*\\}"), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
                if (t.isNotEmpty() && a >= 0 && b >= a) out.add(Cue(a, b, t))
            }
        } else {
            for (blk in text.split(Regex("\n\\s*\n"))) {
                val lines = blk.trim().lines()
                val i = lines.indexOfFirst { TS.containsMatchIn(it) }
                if (i < 0) continue
                val g = TS.find(lines[i])!!.groupValues
                val body = lines.drop(i + 1).joinToString("\n").replace(TAGS, "").trim()
                if (body.isEmpty()) continue
                try { out.add(Cue(sec(g[1], g[2], g[3], g[4]), sec(g[5], g[6], g[7], g[8]), body)) } catch (_: Throwable) {}
            }
        }
        return out.sortedBy { it.start }
    }

    /** نسبة الجمل اللي أغلبها حروف عربية */
    fun mostlyArabic(cues: List<Cue>): Boolean {
        if (cues.isEmpty()) return false
        val ar = cues.count { c ->
            val letters = c.text.count { it.isLetter() }
            letters > 0 && c.text.count { it.code in 0x0600..0x06FF } * 100 / letters >= 60
        }
        return ar * 100 / cues.size >= 60
    }

    /**
     * مسار ترجمة جوه فيديو محلي عن طريق MediaExtractor (بينجح مع mp4/3gpp وبعض الـ mkv).
     * بيرجّع (اسم المسار، الجمل) أو null لو مفيش/مش مدعوم — ساعتها المشغّل بيترجم لحظيًا أثناء التشغيل.
     */
    /** (v552) مسارات الترجمة النصية جوه الفيديو من غير ما نقراها: (رقم المسار في MediaExtractor، اسم بالعربي) */
    fun embeddedTracks(ctx: Context, uri: Uri): List<Pair<Int, String>> {
        var ex: MediaExtractor? = null
        val out = ArrayList<Pair<Int, String>>()
        try {
            ex = MediaExtractor()
            if (uri.scheme == "file") ex.setDataSource(uri.path ?: return out) else ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!(mime.startsWith("text/") || mime.contains("subrip") || mime.contains("ssa") || mime.contains("vtt") || mime.contains("ttml"))) continue
                val lang = try { f.getString(MediaFormat.KEY_LANGUAGE) ?: "" } catch (_: Throwable) { "" }
                val nm = if (lang.isBlank() || lang == "und") "" else try { java.util.Locale(lang).getDisplayLanguage(java.util.Locale("ar")).ifBlank { lang } } catch (_: Throwable) { lang }
                out.add(i to (nm.ifBlank { "لغة غير معروفة" } + " (مسار " + (out.size + 1) + ")"))
            }
        } catch (_: Throwable) {
        } finally { try { ex?.release() } catch (_: Throwable) {} }
        return out
    }

    /** مسار واحد بالرقم (من embeddedTracks) */
    fun readEmbeddedTrack(ctx: Context, uri: Uri, idx: Int): Pair<String, List<Cue>>? {
        var ex: MediaExtractor? = null
        try {
            ex = MediaExtractor()
            if (uri.scheme == "file") ex.setDataSource(uri.path ?: return null) else ex.setDataSource(ctx, uri, null)
            if (idx !in 0 until ex.trackCount) return null
            val f = ex.getTrackFormat(idx)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: return null
            val cues = readTrack(ex, idx, mime)
            return if (cues.isEmpty()) null else ("مسار " + (idx + 1)) to cues
        } catch (_: Throwable) { return null } finally { try { ex?.release() } catch (_: Throwable) {} }
    }

    fun readEmbedded(ctx: Context, uri: Uri): Pair<String, List<Cue>>? {
        var ex: MediaExtractor? = null
        try {
            ex = MediaExtractor()
            if (uri.scheme == "file") ex.setDataSource(uri.path ?: return null) else ex.setDataSource(ctx, uri, null)
            var best: Pair<String, List<Cue>>? = null
            var bestPrefers = -1
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                val ok = mime.startsWith("text/") || mime.contains("subrip") || mime.contains("ssa") || mime.contains("vtt") || mime.contains("ttml")
                if (!ok) continue
                val lang = try { f.getString(MediaFormat.KEY_LANGUAGE) ?: "" } catch (_: Throwable) { "" }
                val cues = readTrack(ex, i, mime)
                if (cues.isEmpty()) continue
                // الأفضلية: إنجليزي ثم أي لغة مش عربي ثم أي حاجة
                val pref = when { lang.startsWith("en") -> 2; !lang.startsWith("ar") -> 1; else -> 0 }
                if (pref > bestPrefers) { bestPrefers = pref; best = (if (lang.isBlank() || lang == "und") "مسار ${i + 1}" else lang) to cues }
            }
            return best
        } catch (_: Throwable) {
            return null
        } finally { try { ex?.release() } catch (_: Throwable) {} }
    }

    private fun readTrack(ex: MediaExtractor, idx: Int, mime: String): List<Cue> {
        val times = ArrayList<Double>(); val texts = ArrayList<String>()
        try {
            ex.selectTrack(idx)
            val buf = java.nio.ByteBuffer.allocate(256 * 1024)
            var guard = 0
            while (guard++ < 40000) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = ex.sampleTime
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                var s = if (mime.contains("3gpp") && bytes.size >= 2) {
                    val len = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    String(bytes, 2, minOf(len, bytes.size - 2), Charsets.UTF_8)
                } else String(bytes, Charsets.UTF_8)
                // لو الجملة جاية بتايم كود SRT جواها
                val m = TS.find(s)
                if (m != null) s = s.substring(m.range.last + 1)
                s = s.replace(TAGS, "").trim()
                times.add(t / 1_000_000.0); texts.add(s)
                ex.advance()
            }
            try { ex.unselectTrack(idx) } catch (_: Throwable) {}
        } catch (_: Throwable) { return emptyList() }
        val out = ArrayList<Cue>()
        for (k in texts.indices) {
            if (texts[k].isEmpty()) continue
            val st = times[k]
            val nextT = if (k + 1 < times.size) times[k + 1] else st + 4.0
            val dur = (1.0 + texts[k].length * 0.07).coerceIn(2.0, 7.0)
            val en = if (k + 1 < texts.size && texts[k + 1].isEmpty()) nextT else minOf(nextT - 0.02, st + dur)
            if (en > st) out.add(Cue(st, en, texts[k]))
        }
        return out
    }

    // ===== الترجمة =====
    private fun oneLine(t: String) = t.replace(Regex("\\s*\n\\s*"), " ").trim()

    private fun gtCall(texts: List<String>, tl: String = "ar"): List<String>? {
        val body = "client=gtx&sl=auto&tl=" + tl + "&dt=t&q=" + java.net.URLEncoder.encode(texts.joinToString("\n"), "UTF-8")
        var last: Exception? = null
        for (t in 1..4) {
            try {
                val c = java.net.URL("https://translate.googleapis.com/translate_a/single").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 25000
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code == 429 || code >= 500) { c.disconnect(); Thread.sleep(3000L * t); continue }
                if (code != 200) { c.disconnect(); throw java.io.IOException("HTTP $code") }
                val txt = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }; c.disconnect()
                try { NetUse.api(body.length.toLong() + 200, txt) } catch (_: Throwable) {}
                val segs = JSONArray(txt).optJSONArray(0) ?: return null
                val sb = StringBuilder()
                for (k in 0 until segs.length()) sb.append(segs.optJSONArray(k)?.optString(0, "") ?: "")
                val parts = sb.toString().split("\n")
                return if (parts.size == texts.size) parts.map { it.trim() } else null
            } catch (e: Exception) { last = e; try { Thread.sleep(1500L * t) } catch (_: InterruptedException) {} }
        }
        if (last != null) throw last
        return null
    }

    /** جملة واحدة بجوجل (للترجمة اللحظية) */
    fun googleOne(t: String): String? = gtCall(listOf(oneLine(t)))?.firstOrNull()

    private fun gemKeys(): List<String> = (Cfg.allMainKeys() + Cfg.keys("viskeys")).distinct().filter { it.length > 10 }
    private fun gemModel(): String = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }

    private fun gemPrompt(lang: String, style: String, ctx: String): String =
        "أنت مترجم أفلام ومسلسلات محترف. ترجم سطور الترجمة التالية (أي لغة أصلية) إلى العربية بلهجة «$lang» وأسلوب «$style».\n" +
        "- حافظ على المعنى والنبرة والفروق بين المتكلمين، واجعل كل سطر قصيرًا ومناسبًا للقراءة على الشاشة.\n" +
        "- كل سطر له ترجمة واحدة بنفس رقم idx: ممنوع الدمج أو الحذف أو إعادة الترتيب.\n" +
        "- لو السطر وصف صوت أو موسيقى مثل [music] أو ♪ ترجمه بإيجاز بين أقواس أو اتركه كما هو.\n" +
        "- السطور السابقة للسياق فقط، لا تترجمها." +
        (if (ctx.isBlank()) "" else "\nالسياق السابق:\n$ctx") +
        "\nأخرج JSON فقط بالشكل: {\"items\":[{\"idx\":0,\"translated\":\"...\"}]}\nالسطور:\n"

    private fun gemBatch(items: List<Pair<Int, String>>, ctx: String, lang: String, style: String, keys: List<String>, first: Int): Map<Int, String> {
        val arr = JSONArray()
        for ((i, t) in items) arr.put(JSONObject().put("idx", i).put("text", oneLine(t)))
        val prompt = gemPrompt(lang, style, ctx) + arr.toString()
        var lastErr: Exception? = null
        for (attempt in 0 until 4) {
            val key = keys[(first + attempt) % keys.size]
            try {
                val r = Api.generate(gemModel(), key, prompt, null, 8192, 0.2, true, false)
                val js = Parse.json(r.text) ?: throw Exception("رد جيميناي مش JSON")
                val a = js.optJSONArray("items") ?: throw Exception("رد جيميناي من غير items")
                val m = HashMap<Int, String>()
                for (q in 0 until a.length()) { val o = a.optJSONObject(q) ?: continue; val ix = o.optInt("idx", -1); val tr = o.optString("translated").trim(); if (ix >= 0 && tr.isNotEmpty()) m[ix] = tr }
                if (m.isNotEmpty()) return m
                throw Exception("رد جيميناي فاضي")
            } catch (e: Exception) {
                lastErr = e
                val code = (e as? ApiErr)?.code ?: 0
                try { Thread.sleep(if (code == 429) 4000L * (attempt + 1) else 1500L * (attempt + 1)) } catch (_: InterruptedException) {}
            }
        }
        throw lastErr ?: Exception("فشل جيميناي")
    }

    /** جملة واحدة بجيميناي (للترجمة اللحظية) */
    fun geminiOne(t: String, ctx: String): String? {
        val keys = gemKeys()
        if (keys.isEmpty()) return null
        val lang = Cfg.str("lang", "فصحى").ifBlank { "فصحى" }; val style = Cfg.str("style", "حرفي").ifBlank { "حرفي" }
        return gemBatch(listOf(0 to t), ctx, lang, style, keys, (System.currentTimeMillis() / 1000L % keys.size).toInt())[0]
    }

    /**
     * ترجمة كل الجمل. eng = "google" | "gemini". report بيتنادى بنص التقدم. cancel() لو true بيوقف.
     * اللي يفشل بيتسيب فاضي في النتيجة (والمشغّل بيحط مكانه النص الأصلي) وعدّاده في failed.
     */
    fun translate(cues: List<Cue>, eng: String, report: (String) -> Unit, cancel: () -> Boolean = { false }): Out {
        val n = cues.size
        val res = arrayOfNulls<String>(n)
        var failed = 0
        if (eng == "gemini") {
            val keys = gemKeys()
            if (keys.isEmpty()) return Out(List(n) { "" }, n, "مفيش مفتاح جيميناي — ضيفه من الإعدادات أو اختار جوجل")
            val lang = Cfg.str("lang", "فصحى").ifBlank { "فصحى" }; val style = Cfg.str("style", "حرفي").ifBlank { "حرفي" }
            if (keys.size >= 2 && n > 0) return translateMulti(cues, keys, lang, style, report, cancel)   // (v638) الملف كامل لمفتاحين + تنقيح بالباقي
            val size = 40
            val batches = (0 until n).chunked(size)
            val next = AtomicInteger(0); val done = AtomicInteger(0); val bad = AtomicInteger(0)
            val par = minOf(4, keys.size).coerceAtLeast(1)
            val firstErr = arrayOfNulls<String>(1)
            val workers = (0 until par).map { w ->
                Thread {
                    while (!cancel()) {
                        val bi = next.getAndIncrement()
                        if (bi >= batches.size) break
                        val b = batches[bi]
                        val ctx = if (b[0] == 0) "" else ((maxOf(0, b[0] - 3) until b[0]).joinToString("\n") { oneLine(cues[it].text) })
                        try {
                            val m = gemBatch(b.map { (it - b[0]) to cues[it].text }, ctx, lang, style, keys, bi + w)
                            for ((k, tr) in m) { val gi = b[0] + k; if (gi in b) res[gi] = tr }
                        } catch (e: Exception) { if (firstErr[0] == null) firstErr[0] = (e.message ?: e.javaClass.simpleName).take(100); bad.addAndGet(b.size) }
                        val d = done.incrementAndGet()
                        report("✨ جيميناي " + d + "/" + batches.size)
                    }
                }.apply { isDaemon = true }
            }
            workers.forEach { it.start() }; workers.forEach { try { it.join() } catch (_: InterruptedException) {} }
            failed = res.count { it.isNullOrBlank() }
            return Out(res.map { it ?: "" }, failed, if (failed == n) (firstErr[0] ?: "فشل") else "")
        }
        // جوجل: 30 جملة في الطلب (جملة بسطر واحد)
        val batches = (0 until n).chunked(30)
        var errMsg = ""; var failedB = 0
        for ((bi, b) in batches.withIndex()) {
            if (cancel()) break
            if (bi % 3 == 0) report("🌐 جوجل " + (bi + 1) + "/" + batches.size)
            val src = b.map { oneLine(cues[it].text) }
            try {
                val r = gtCall(src)
                if (r != null) { for ((q, gi) in b.withIndex()) res[gi] = r[q] }
                else for ((q, gi) in b.withIndex()) { try { gtCall(listOf(src[q]))?.firstOrNull()?.let { res[gi] = it } } catch (_: Exception) {} }   // عدد الأسطر اختلف: جملة جملة
            } catch (e: Exception) {
                failedB++; errMsg = (e.message ?: e.javaClass.simpleName).take(80)
                if (failedB >= 4) break
            }
        }
        failed = res.count { it.isNullOrBlank() }
        return Out(res.map { it ?: "" }, failed, if (failed == n) (if (errMsg.isEmpty()) "جوجل وقف أو النت فاصل" else errMsg) else "")
    }

    // ===================== (v638) خط الترجمة المتعدد: ترجمة كاملة بمفتاحين ← تنقيح بالباقي =====================
    private const val MULTI_CHUNK = 200       // جمل في الطلب الواحد (الملف كله بيتبعت معاهم كسياق)
    private const val MULTI_CTX_ALL = 700     // لحد الرقم ده من الجمل: الملف كله سياق · أكتر: نافذة حواليهم

    /** (مفاتيح الترجمة، مفاتيح التنقيح) حسب عدد المفاتيح: 2 ← 1+1 · 3 ← 2+1 · 4+ ← 2+2 */
    fun multiPlan(nKeys: Int): Pair<Int, Int> = when {
        nKeys <= 1 -> Pair(nKeys.coerceAtLeast(0), 0)
        nKeys == 2 -> Pair(1, 1)
        nKeys == 3 -> Pair(2, 1)
        else -> Pair(2, 2)
    }

    private fun ctxBlock(cues: List<Cue>, from: Int, to: Int): String {
        val n = cues.size
        val a = if (n <= MULTI_CTX_ALL) 0 else maxOf(0, from - 250)
        val b = if (n <= MULTI_CTX_ALL) n else minOf(n, to + 250)
        val sb = StringBuilder()
        for (i in a until b) sb.append('#').append(i + 1).append(' ').append(oneLine(cues[i].text)).append('\n')
        return sb.toString()
    }

    private fun fullTrPrompt(lang: String, style: String, ctx: String, items: JSONArray): String =
        "أنت مترجم أفلام ومسلسلات محترف. أمامك ملف الترجمة الأصلي لفيديو واحد (بترتيبه، وأرقام الأسطر #N) — اقرأه كله أولًا وافهم القصة: مين بيتكلم مع مين، العلاقات بينهم، مين ذكر ومين أنثى، والمصطلحات المتكررة.\n" +
        "ثم ترجم «السطور المطلوبة» فقط إلى العربية بلهجة «$lang» وأسلوب «$style».\n" +
        "- ترجم بالمعنى الكامل للمشهد مش سطر سطر: السطر الغامض يتحدد بما قبله وبعده في الملف كله.\n" +
        "- 🔴 الضمائر والأجناس: حدّد كل (هو/هي/أنت/هم/هذا) يعود على مين من السياق، واكتب الضمير والفعل والصفة الصح (مذكر/مؤنث، مفرد/جمع، رسمي/غير رسمي).\n" +
        "- ثبّت ترجمة الأسماء والألقاب وكلمات القرابة والمصطلحات في الملف كله، وحافظ على نبرة كل شخصية من أول الملف لآخره.\n" +
        "- كل سطر له ترجمة واحدة بنفس idx: ممنوع الدمج أو الحذف أو إعادة الترتيب، والسطر قصير ومناسب للقراءة على الشاشة.\n" +
        "- لو السطر وصف صوت أو موسيقى مثل [music] أو ♪ ترجمه بإيجاز بين أقواس.\n" +
        "- الملف الكامل للفهم فقط: لا تترجم ولا تُرجع منه إلا السطور المطلوبة.\n" +
        "═══ ملف الترجمة الكامل ═══\n" + ctx +
        "═══ السطور المطلوبة (idx من 0 إلى " + (items.length() - 1) + "، و«ref» = رقمه #N في الملف) ═══\n" + items.toString() +
        "\nأخرج JSON فقط وبدون أي نص خارجه: {\"items\":[{\"idx\":0,\"translated\":\"...\"}]} — " + items.length() + " عنصر بالضبط، كل واحد بترجمة عربية غير فاضية."

    private fun refinePrompt(lang: String, style: String, ctx: String, items: JSONArray, cands: Int): String =
        "أنت منقّح ترجمة أفلام ومسلسلات محترف (لهجة «$lang» وأسلوب «$style»). لكل سطر معاك النص الأصلي" +
        (if (cands >= 2) " ومعاه ترجمتين مرشّحتين (candidate_1 و candidate_2) من مترجمين مختلفين" else " ومعاه ترجمة مرشّحة (candidate_1)") +
        ". المطلوب نسخة نهائية واحدة جاهزة للعرض لكل سطر:\n" +
        "- 🔴 صحّح الضمائر والأجناس والمخاطَب (هو/هي/أنت/هم، مذكر/مؤنث، مفرد/جمع) على حسب من يتكلم ومع مين في الملف كله.\n" +
        "- ثبّت الأسماء والألقاب والمصطلحات في الملف كله، وصحّح أي ترجمة حرفية أو معنى اتغيّر أو سطر اتحذف منه جزء.\n" +
        (if (cands >= 2) "- اختار الأدق من المرشحتين أو ادمج الأفضل منهما أو أعد الصياغة لو الاتنين مش مظبوطين.\n" else "") +
        "- اللهجة «$lang» والأسلوب «$style» بشكل ثابت من أول الملف لآخره، والسطر قصير ومناسب للقراءة على الشاشة.\n" +
        "- كل سطر بنفس idx: ممنوع الدمج أو الحذف أو إعادة الترتيب. الملف الكامل للفهم فقط.\n" +
        "═══ الملف الأصلي الكامل ═══\n" + ctx +
        "═══ السطور للتنقيح (idx من 0 إلى " + (items.length() - 1) + ") ═══\n" + items.toString() +
        "\nأخرج JSON فقط وبدون أي نص خارجه: {\"items\":[{\"idx\":0,\"translated\":\"...\"}]} — " + items.length() + " عنصر بالضبط، كل واحد بالترجمة النهائية بعد التنقيح."

    /** طلب JSON واحد بمحاولات: بيبدأ بمفتاح primary وبيلف على باقي المفاتيح لو فشل */
    private fun callItems(prompt: String, keys: List<String>, primary: Int, temp: Double): Map<Int, String> {
        var lastErr: Exception? = null
        for (attempt in 0 until 4) {
            val key = keys[(primary + attempt) % keys.size]
            try {
                val r = Api.generate(gemModel(), key, prompt, null, 8192, temp, true, false)
                val js = Parse.json(r.text) ?: throw Exception("رد جيميناي مش JSON")
                val a = js.optJSONArray("items") ?: throw Exception("رد جيميناي من غير items")
                val m = HashMap<Int, String>()
                for (q in 0 until a.length()) { val o = a.optJSONObject(q) ?: continue; val ix = o.optInt("idx", -1); val tr = o.optString("translated").trim(); if (ix >= 0 && tr.isNotEmpty()) m[ix] = tr }
                if (m.isNotEmpty()) return m
                throw Exception("رد جيميناي فاضي")
            } catch (e: Exception) {
                lastErr = e
                val code = (e as? ApiErr)?.code ?: 0
                try { Thread.sleep(if (code == 429) 4000L * (attempt + 1) else 1500L * (attempt + 1)) } catch (_: InterruptedException) {}
            }
        }
        throw lastErr ?: Exception("فشل جيميناي")
    }

    /**
     * (v638) ترجمة ملف السوفت سب بجيميناي بعدة مفاتيح:
     * ١) مفتاحين (أو واحد) كل واحد بيترجم الملف كامل لوحده بنفس لهجة/أسلوب الإعدادات (الملف كله سياق في كل طلب)،
     * ٢) باقي المفاتيح (لحد 2) بتاخد الأصل + الترجمتين وتنقّحهم (ضمائر/أجناس/ثبات مصطلحات/اختيار الأدق) والنتيجة النهائية هي المنقّحة،
     * وأي سطر التنقيح فشل فيه بيرجع للترجمة الأولى ثم التانية ثم الأصل.
     */
    fun translateMulti(cues: List<Cue>, keys: List<String>, lang: String, style: String, report: (String) -> Unit, cancel: () -> Boolean = { false }): Out {
        val n = cues.size
        val (nt, nr) = multiPlan(keys.size)
        val trKeys = keys.take(nt); val rfKeys = keys.drop(nt).take(nr)
        val chunks = (0 until n).chunked(MULTI_CHUNK)
        val firstErr = arrayOfNulls<String>(1)

        // ===== المرحلة ١: ترجمة كاملة بكل مفتاح ترجمة (بالتوازي) =====
        val passes = Array(nt) { arrayOfNulls<String>(n) }
        val doneA = AtomicInteger(0); val totalA = chunks.size * nt
        val ws = (0 until nt).map { w ->
            Thread {
                for (b in chunks) {
                    if (cancel()) break
                    val items = JSONArray()
                    for (i in b) items.put(JSONObject().put("idx", i - b[0]).put("ref", i + 1).put("text", oneLine(cues[i].text)))
                    try {
                        val m = callItems(fullTrPrompt(lang, style, ctxBlock(cues, b.first(), b.last() + 1), items), keys, keys.indexOf(trKeys[w]).coerceAtLeast(0), 0.3)
                        for ((k, tr) in m) { val gi = b[0] + k; if (gi in b) passes[w][gi] = tr }
                    } catch (e: Exception) { if (firstErr[0] == null) firstErr[0] = (e.message ?: e.javaClass.simpleName).take(100) }
                    report("✨ ترجمة كاملة " + doneA.incrementAndGet() + "/" + totalA + (if (nt > 1) " (" + nt + " مفاتيح بالتوازي)" else ""))
                }
            }.apply { isDaemon = true }
        }
        ws.forEach { it.start() }; ws.forEach { try { it.join() } catch (_: InterruptedException) {} }
        val base = List(n) { i -> passes.map { it[i] }.firstOrNull { !it.isNullOrBlank() } ?: "" }
        if (base.all { it.isBlank() }) return Out(List(n) { "" }, n, firstErr[0] ?: if (cancel()) "اتوقفت" else "فشل")
        if (cancel() || rfKeys.isEmpty()) {
            val failed0 = base.count { it.isBlank() }
            return Out(base, failed0, "")
        }

        // ===== المرحلة ٢: تنقيح بالأصل + الترجمتين على المفاتيح الباقية (كل مفتاح بياخد مقاطع) =====
        val refined = arrayOfNulls<String>(n)
        val nextB = AtomicInteger(0); val doneB = AtomicInteger(0)
        val rws = rfKeys.indices.map { w ->
            Thread {
                while (!cancel()) {
                    val bi = nextB.getAndIncrement()
                    if (bi >= chunks.size) break
                    val b = chunks[bi]
                    val items = JSONArray()
                    for (i in b) {
                        val o = JSONObject().put("idx", i - b[0]).put("ref", i + 1).put("original", oneLine(cues[i].text))
                        val c1 = passes[0][i]; val c2 = if (nt > 1) passes[1][i] else null
                        if (!c1.isNullOrBlank()) o.put("candidate_1", oneLine(c1))
                        if (!c2.isNullOrBlank()) o.put("candidate_2", oneLine(c2))
                        items.put(o)
                    }
                    try {
                        val m = callItems(refinePrompt(lang, style, ctxBlock(cues, b.first(), b.last() + 1), items, nt), keys, keys.indexOf(rfKeys[w]).coerceAtLeast(0), 0.2)
                        for ((k, tr) in m) { val gi = b[0] + k; if (gi in b) refined[gi] = tr }
                    } catch (e: Exception) { if (firstErr[0] == null) firstErr[0] = (e.message ?: e.javaClass.simpleName).take(100) }
                    report("🧹 تنقيح وتظبيط الضمائر " + doneB.incrementAndGet() + "/" + chunks.size + (if (rfKeys.size > 1) " (" + rfKeys.size + " مفاتيح بالتوازي)" else ""))
                }
            }.apply { isDaemon = true }
        }
        rws.forEach { it.start() }; rws.forEach { try { it.join() } catch (_: InterruptedException) {} }
        val fin = List(n) { i -> refined[i]?.takeIf { it.isNotBlank() } ?: base[i] }
        return Out(fin, fin.count { it.isBlank() }, "")
    }
}
