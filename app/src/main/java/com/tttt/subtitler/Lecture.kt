package com.tttt.subtitler

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * (v991) تفريغ المحاضرات — المنطق (من غير واجهة):
 *  • تفريغ نصي دقيق بنفس لغة الكلام (Gemini) على أجزاء 60 ثانية — الحد اللي جيميناي بيسمح بيه — بالتوازي على أكتر من مفتاح.
 *  • كل جزء بيتحفظ أول ما يخلص: لو البرنامج اتقفل أو جزء فشل، «استكمال» بيكمّل الناقص بس (من غير ما يعيد اللي خلص).
 *  • «تنظيم»: عناوين رئيسية وتحت كل عنوان نقط قليلة · «تلخيص»: ملخص قصير + أهم النقط · ودايمًا اختيار «استعن بالنت» (بحث جوجل).
 *  • مصادر: فيديو/ملف صوت من الجهاز · تسجيلات الجهاز (MediaStore) · لينك (يوتيوب/فيسبوك/أي لينك فيه صوت أو فيديو).
 * التخزين: filesDir/lectures/<id>.json — مفيش تعديل على أي حاجة موجودة.
 */
object Lecture {
    const val CHUNK_SEC = 60.0   // جيميناي ما بيقبلش أكتر من كده في الطلب الواحد بشكل موثوق

    // ===================== الموديل =====================
    class Seg(val start: Double, val end: Double, val text: String, val chunk: Int, val spk: String = "")   // spk: D محاضر · S طالب/متحدث تاني · "" غير معروف

    class Doc(val id: String) {
        var title = ""
        var kind = "video"          // video · audio · rec · link
        var uri = ""                // content:// أو file:// للمصادر المحلية
        var link = ""               // اللينك الأصلي (بيتحل من جديد عند الاستكمال لأن روابط الستريم بتنتهي)
        var durSec = 0.0
        var nChunks = 0
        var created = System.currentTimeMillis()
        var lang = ""
        var clean = true            // تجاهل الكلام الإداري/الجانبي
        val done = HashSet<Int>()
        val failed = HashSet<Int>()
        val segs = ArrayList<Seg>()
        var organized = ""          // نص «تنظيم» الجاهز
        var organizedWeb = false
        var summary = ""
        var summaryWeb = false
        var summaryLen = "mid"      // short · mid
        var ua = ""                 // (v993) هيدرز اللينك المتحل — بتتستعمل للقطات الفيديو
        var ref = ""
        var analysis = ""           // (v993) تحليل المذكرة (JSON) — بيتعاد لو التفريغ اتغيّر
        var analysisN = 0
        var analysisHash = ""
        val complete: Boolean get() = nChunks > 0 && done.size >= nChunks && failed.isEmpty()
        val pct: Int get() = if (nChunks <= 0) 0 else (done.size * 100 / nChunks).coerceIn(0, 100)
        val words: Int get() = synchronized(this) { segs.sumOf { s -> s.text.split(' ').count { it.isNotBlank() } } }
    }

    /** حالة تشغيل حيّة (في الذاكرة) — الواجهة بتقراها كل ثانية */
    class Run(val doc: Doc) {
        @Volatile var running = true
        @Volatile var stop = false
        @Volatile var status = "بجهّز الصوت…"
        @Volatile var error = ""
    }
    val runs = ConcurrentHashMap<String, Run>()
    fun runOf(id: String): Run? = runs[id]
    fun isRunning(id: String) = runs[id]?.running == true

    // ===================== التخزين =====================
    private fun dir(ctx: Context) = File(ctx.filesDir, "lectures").also { it.mkdirs() }

    fun idFor(key: String): String =
        "l" + java.lang.Long.toHexString(key.hashCode().toLong() and 0xffffffffL) + java.lang.Long.toHexString(key.length.toLong())

    private fun toJson(d: Doc): JSONObject = synchronized(d) {
        val segs = JSONArray()
        for (s in d.segs) segs.put(JSONObject().put("s", s.start).put("e", s.end).put("t", s.text).put("c", s.chunk).put("k", s.spk))
        JSONObject().put("id", d.id).put("title", d.title).put("kind", d.kind).put("uri", d.uri).put("link", d.link)
            .put("dur", d.durSec).put("n", d.nChunks).put("created", d.created).put("lang", d.lang).put("clean", d.clean)
            .put("done", JSONArray(d.done.toList())).put("failed", JSONArray(d.failed.toList())).put("segs", segs)
            .put("org", d.organized).put("orgWeb", d.organizedWeb).put("sum", d.summary).put("sumWeb", d.summaryWeb).put("sumLen", d.summaryLen)
            .put("ua", d.ua).put("ref", d.ref).put("an", d.analysis).put("anN", d.analysisN).put("anH", d.analysisHash)
    }

    private fun fromJson(o: JSONObject): Doc {
        val d = Doc(o.getString("id"))
        d.title = o.optString("title"); d.kind = o.optString("kind", "video"); d.uri = o.optString("uri"); d.link = o.optString("link")
        d.durSec = o.optDouble("dur", 0.0); d.nChunks = o.optInt("n", 0); d.created = o.optLong("created", 0L)
        d.lang = o.optString("lang"); d.clean = o.optBoolean("clean", true)
        o.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) d.done.add(a.optInt(i)) }
        o.optJSONArray("failed")?.let { a -> for (i in 0 until a.length()) d.failed.add(a.optInt(i)) }
        o.optJSONArray("segs")?.let { a ->
            for (i in 0 until a.length()) { val x = a.optJSONObject(i) ?: continue; d.segs.add(Seg(x.optDouble("s"), x.optDouble("e"), x.optString("t"), x.optInt("c"), x.optString("k"))) }
        }
        d.organized = o.optString("org"); d.organizedWeb = o.optBoolean("orgWeb"); d.summary = o.optString("sum")
        d.summaryWeb = o.optBoolean("sumWeb"); d.summaryLen = o.optString("sumLen", "mid")
        d.ua = o.optString("ua"); d.ref = o.optString("ref"); d.analysis = o.optString("an"); d.analysisN = o.optInt("anN", 0); d.analysisHash = o.optString("anH")
        return d
    }

    @Synchronized fun save(ctx: Context, d: Doc) {
        try {
            val f = File(dir(ctx), d.id + ".json"); val t = File(dir(ctx), d.id + ".tmp")
            t.writeText(toJson(d).toString(), Charsets.UTF_8)
            if (!t.renameTo(f)) { f.writeText(t.readText(Charsets.UTF_8), Charsets.UTF_8); t.delete() }
        } catch (e: Throwable) { LogStore.err("Lecture:save", e) }
    }

    fun load(ctx: Context, id: String): Doc? = try {
        val f = File(dir(ctx), "$id.json"); if (f.exists()) fromJson(JSONObject(f.readText(Charsets.UTF_8))) else null
    } catch (e: Throwable) { LogStore.err("Lecture:load", e); null }

    /** كل المحاضرات المحفوظة، الأحدث أولًا (يتنده من خيط خلفي — بيقرا الملفات) */
    fun list(ctx: Context): List<Doc> {
        val out = ArrayList<Doc>()
        for (f in dir(ctx).listFiles().orEmpty()) {
            if (!f.name.endsWith(".json")) continue
            val live = runs[f.name.removeSuffix(".json")]?.doc
            if (live != null) { out.add(live); continue }
            try { out.add(fromJson(JSONObject(f.readText(Charsets.UTF_8)))) } catch (_: Throwable) {}
        }
        return out.sortedByDescending { it.created }
    }

    fun delete(ctx: Context, id: String) {
        runs[id]?.stop = true
        try { File(dir(ctx), "$id.json").delete() } catch (_: Throwable) {}
        runs.remove(id)
    }

    fun newDoc(ctx: Context, kind: String, title: String, uri: String, link: String): Doc {
        val id = idFor(if (link.isNotBlank()) link else uri)
        val old = runs[id]?.doc ?: load(ctx, id)
        if (old != null) return old   // نفس المصدر ← نكمّل على اللي اتفرّغ قبل كده
        val d = Doc(id); d.kind = kind; d.title = title.ifBlank { "محاضرة" }; d.uri = uri; d.link = link
        save(ctx, d)
        return d
    }

    // ===================== المفاتيح والموديل =====================
    private fun keys(): List<String> = (Cfg.keys("keys") + Cfg.keys("backup") + Cfg.keys("extra")).distinct()
    private fun model(): String = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }

    // ===================== مصادر الصوت =====================
    class Resolved(val url: String, val hdr: Map<String, String>, val title: String)

    /** لينك → رابط ستريم صوت/فيديو (بيتنده من خيط خلفي). يوتيوب: صوت m4a · روابط الملفات · صفحات فيها فيديو (فيسبوك وغيره) */
    fun resolveLink(ctx: Context, link0: String): Resolved {
        val link = link0.trim()
        Links.ytId(link)?.let { id ->
            val p = MtYt.audio(id) ?: throw IOException("يوتيوب: " + MtYt.lastWhy.ifBlank { "مقدرتش أجيب الصوت" })
            return Resolved(p.url, mapOf("User-Agent" to p.ua, "Referer" to "https://www.youtube.com/"), p.title)
        }
        PageScan.resolve(link)?.let { return Resolved(it.url, emptyMap(), it.name) }
        val cv = ClipWatch.resolve(ctx, link) ?: throw IOException("مفيش صوت أو فيديو في اللينك ده")
        val play = cv.playUrl.ifBlank { if (Sniff.isDirect(cv.url)) cv.url else "" }
        if (play.isBlank()) throw IOException("لقيت صفحة بس مقدرتش أطلّع منها ستريم (ممكن تحتاج تسجيل دخول)")
        val hdr = HashMap<String, String>()
        if (cv.ua.isNotBlank()) hdr["User-Agent"] = cv.ua
        if (cv.ref.isNotBlank()) hdr["Referer"] = cv.ref
        return Resolved(play, hdr, cv.title)
    }

    private fun openSource(ctx: Context, d: Doc): AudioSource {
        val log: (String) -> Unit = { LogStore.add("🎓 $it") }
        if (d.link.isNotBlank()) {
            val r = resolveLink(ctx, d.link)
            if (d.title.isBlank() || d.title == "محاضرة" || d.title == d.link) d.title = r.title.ifBlank { d.title }
            d.ua = r.hdr["User-Agent"] ?: ""; d.ref = r.hdr["Referer"] ?: ""
            return AudioSources.make(ctx, null, r.url, r.hdr, 1, log)
        }
        return AudioSources.make(ctx, Uri.parse(d.uri), null, emptyMap(), 1, log)
    }

    // ===================== تسجيلات الجهاز =====================
    class Rec(val uri: Uri, val name: String, val durMs: Long, val size: Long, val dateSec: Long, val path: String, val isRec: Boolean)

    fun audioPermission(): String = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
    fun hasAudioPermission(ctx: Context): Boolean = ctx.checkSelfPermission(audioPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val REC_HINT = Regex("record|rec[_\\- ]|voice|memo|dictaphone|call|ptt|تسجيل|ريكورد|مكالم|صوتيات", RegexOption.IGNORE_CASE)

    /** كل الصوتيات على الجهاز؛ isRec = تسجيل (علامة النظام IS_RECORDING أو اسم/مسار شبه «Recordings/Voice Recorder/Call»). يتنده من خيط خلفي */
    fun scanAudio(ctx: Context): List<Rec> {
        val out = ArrayList<Rec>()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        fun query(withFlag: Boolean) {
            val proj = ArrayList<String>().apply {
                add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.DISPLAY_NAME); add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.SIZE); add(MediaStore.Audio.Media.DATE_MODIFIED)
                add(if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.Audio.Media.DATA)
                if (withFlag) add("is_recording")
            }
            ctx.contentResolver.query(base, proj.toTypedArray(), null, null, MediaStore.Audio.Media.DATE_MODIFIED + " DESC")?.use { c ->
                while (c.moveToNext()) {
                    val dur = c.getLong(2)
                    if (dur in 1..2500L) continue
                    val name = c.getString(1) ?: continue
                    val path = c.getString(5) ?: ""
                    val flag = withFlag && c.getInt(6) == 1
                    out.add(Rec(ContentUris.withAppendedId(base, c.getLong(0)), name, dur, c.getLong(3), c.getLong(4), path, flag || REC_HINT.containsMatchIn(path) || REC_HINT.containsMatchIn(name)))
                }
            }
        }
        try { query(Build.VERSION.SDK_INT >= 31) } catch (_: Throwable) { out.clear(); try { query(false) } catch (e: Throwable) { LogStore.err("Lecture:scan", e) } }
        return out
    }

    // ===================== التفريغ =====================
    private fun transcribePrompt(durSec: Double, clean: Boolean, prev: String): String = buildString {
        append("أنت مفرّغ صوتي أكاديمي محترف بدقة عالية جدًا، متخصص في المحاضرات. اسمع المقطع الصوتي (مدته حوالي ${durSec.toInt()} ثانية) بتركيز كامل وفرّغ الكلام المنطوق نصًا حرفيًا بنفس اللغة المنطوقة فعلًا — من غير أي ترجمة ولا تلخيص ولا إضافة من عندك.\n\n")
        append("قواعد صارمة:\n")
        append("- 🔴 المتحدثين: المحاضر = الصوت الأساسي اللي بيشرح (spk=\"D\"). أي صوت تاني مختلف (طالب بيسأل أو بيقاطع أو بيرد، أو شخص تاني) = spk=\"S\". ميّز بين الأصوات من نبرتها وطبقتها وطريقة كلامها مش من المحتوى بس. لو الجملة الواحدة اتقالت من صوتين، افصلها لعنصرين.\n")
        append("- 🔴 لو طالب قاطع المحاضر أو سأل، سجّل سؤاله كعنصر منفصل spk=\"S\" بتوقيته، ورد المحاضر بعده كعنصر spk=\"D\". متدمجوش مع بعض أبدًا.\n")
        append("- 🔴 لو المتحدث خلط عربي وإنجليزي في نفس الجملة، اكتب كل جزء بلغته بالظبط زي ما اتقال. المصطلحات العلمية والإنجليزي القصير (حتى كلمتين وسط الجملة) تتكتب بحروفها الإنجليزية الصحيحة ومتتشالش ولا تتترجم ولا تتكتب بحروف عربية.\n")
        append("- كل جملة أو وحدة معنى (٣–١٥ ثانية) عنصر منفصل بـ start/end بالثواني نسبةً لبداية المقطع ده، والتوقيت لازم يبقى دقيق.\n")
        if (clean) append("- استبعد الكلام الإداري والجانبي اللي مالوش علاقة بالمادة (طلب سكوت، تنظيم الحضور، الغياب، الوقت، دردشة طلبة مع بعض). لكن أسئلة الطلبة للمحاضر وردوده عليها تتفرّغ دايمًا. ملاحظة قصيرة مرتبطة بالشرح (افتحوا صفحة كذا) سيبها.\n")
        else append("- فرّغ كل كلام مسموع حتى الجانبي.\n")
        append("- تجاهل السكوت والمؤثرات غير الكلامية (خطوات، ضجيج، تنحنح).\n")
        append("- لو الصوت مش واضح اكتب أقرب تفسير علمي منطقي من السياق، ومتخترعش كلام لو مفيش كلام مسموع.\n")
        append("- لو المقطع مفيهوش كلام خالص رجّع segments فاضية.\n")
        if (prev.isNotBlank()) append("\nسياق آخر كلام في المقطع اللي قبله (لتكملة جملة مقطوعة على الحدود وتوحيد كتابة المصطلحات بس، متكررهوش): \"$prev\"\n")
        append("\nرجّع JSON فقط بدون شرح ولا markdown:\n")
        append("{\"lang\":\"اللغة الأساسية بالعربي\",\"segments\":[{\"start\":0.0,\"end\":5.2,\"spk\":\"D\",\"text\":\"...\"}]}")
    }

    private fun spkOf(raw: String): String {
        val r = raw.trim().lowercase()
        return when {
            r.isEmpty() -> ""
            r.startsWith("s") || r.startsWith("ط") || r.startsWith("st") -> "S"
            r.startsWith("d") || r.startsWith("م") || r.startsWith("l") -> "D"
            else -> ""
        }
    }

    private fun parseJson(t: String): JSONObject? {
        val c = t.replace("```json", "").replace("```", "").trim()
        return try { JSONObject(c) } catch (_: Throwable) {
            val a = c.indexOf('{'); val b = c.lastIndexOf('}')
            if (a >= 0 && b > a) try { JSONObject(c.substring(a, b + 1)) } catch (_: Throwable) { null } else null
        }
    }

    /** جزء واحد: بيرجّع true لو نجح (حتى لو الجزء صامت)، false لو فشل نهائيًا بعد المحاولات */
    private fun doChunk(run: Run, keys: List<String>, mdl: String, src: AudioSource, idx: Int, worker: Int, dur: Double): Boolean {
        val d = run.doc
        val st = idx * CHUNK_SEC; val en = minOf(dur, st + CHUNK_SEC)
        if (en - st < 1.0) return true
        val wav = try { src.wav(st, en) } catch (e: Throwable) { LogStore.err("Lecture:wav", e); null } ?: return false
        if (wav.silent) { synchronized(d) { d.segs.removeAll { it.chunk == idx } }; return true }
        val prev = synchronized(d) { d.segs.filter { it.chunk == idx - 1 }.sortedBy { it.start }.takeLast(2).joinToString(" ") { it.text } }.takeLast(300)
        val prompt = transcribePrompt(en - st, d.clean, prev)
        for (a in 0 until 4) {
            if (run.stop) return false
            val key = keys[(idx + worker + a) % keys.size]
            try {
                val r = Api.generate(mdl, key, prompt, wav.bytes, Api.outTokens(en - st), 0.1, true, false)
                val j = parseJson(r.text)
                if (j == null) { if (r.text.isBlank() && r.finish.startsWith("PROMPT_")) { return true }; continue }
                val arr = j.optJSONArray("segments") ?: JSONArray()
                val got = ArrayList<Seg>()
                for (i in 0 until arr.length()) {
                    val x = arr.optJSONObject(i) ?: continue
                    val t = x.optString("text").trim(); if (t.isEmpty()) continue
                    val s0 = (x.optDouble("start", 0.0)).coerceIn(0.0, en - st); val e0 = (x.optDouble("end", s0)).coerceIn(s0, en - st)
                    got.add(Seg(st + s0, st + e0, t, idx, spkOf(x.optString("spk"))))
                }
                synchronized(d) {
                    d.segs.removeAll { it.chunk == idx }; d.segs.addAll(got)
                    val l = j.optString("lang"); if (l.isNotBlank() && d.lang.isBlank()) d.lang = l
                }
                return true
            } catch (e: NoKey) { run.error = e.message ?: "مفيش مفتاح"; run.stop = true; return false }
            catch (e: ApiErr) {
                if (e.code == 429 || e.code >= 500) { try { Thread.sleep(1500L * (a + 1)) } catch (_: InterruptedException) {} }
                else if (e.code != 400 && e.code != 403) { run.error = (e.message ?: "").take(120); return false }
            } catch (e: Throwable) { LogStore.err("Lecture:chunk", e); try { Thread.sleep(800) } catch (_: InterruptedException) {} }
        }
        return false
    }

    /** يبدأ/يكمّل التفريغ في الخلفية. الأجزاء اللي خلصت بتتخطى؛ اللي فشلت بتتعاد. */
    fun start(ctx0: Context, d: Doc): Run? {
        val ctx = ctx0.applicationContext
        if (isRunning(d.id)) return runs[d.id]
        val run = Run(d); runs[d.id] = run
        Thread {
            var src: AudioSource? = null
            try {
                val ks = keys()
                if (ks.isEmpty()) throw IOException("مفيش مفتاح Gemini — ضيفه من الإعدادات الأول")
                val mdl = model()
                run.status = "بجهّز الصوت…"
                val s = openSource(ctx, d); src = s
                val dur = s.durationSec()
                if (dur <= 0.0) throw IOException("مقدرتش أعرف مدة الصوت")
                synchronized(d) { d.durSec = dur; d.nChunks = Math.ceil(dur / CHUNK_SEC).toInt() }
                save(ctx, d)
                val todo = (0 until d.nChunks).filter { it !in d.done }
                if (todo.isEmpty()) { run.status = "التفريغ مكتمل ✅"; return@Thread }
                val next = AtomicInteger(0); val finished = AtomicInteger(0)
                val par = minOf(ks.size, 3, todo.size).coerceAtLeast(1)
                run.status = "بفرّغ…"
                val ths = (0 until par).map { w ->
                    Thread {
                        while (!run.stop) {
                            val k = next.getAndIncrement(); if (k >= todo.size) break
                            val idx = todo[k]
                            val ok = try { doChunk(run, ks, mdl, s, idx, w, dur) } catch (e: Throwable) { LogStore.err("Lecture:worker", e); false }
                            synchronized(d) { if (ok) { d.done.add(idx); d.failed.remove(idx) } else if (!run.stop) d.failed.add(idx) }
                            if (finished.incrementAndGet() % 4 == 0) save(ctx, d)
                            run.status = "بفرّغ… ${d.done.size}/${d.nChunks}"
                        }
                    }.apply { isDaemon = true; start() }
                }
                ths.forEach { try { it.join() } catch (_: InterruptedException) {} }
                run.status = when {
                    run.error.isNotEmpty() -> "اتوقف: " + run.error
                    run.stop -> "اتوقف — تقدر تكمّل"
                    d.failed.isNotEmpty() -> "اكتمل مع ${d.failed.size} جزء ناقص — اضغط «أعد الناقص»"
                    else -> "التفريغ مكتمل ✅"
                }
            } catch (e: Throwable) {
                LogStore.err("Lecture:start", e); run.error = (e.message ?: e.javaClass.simpleName).take(140); run.status = "فشل: " + run.error
            } finally {
                try { src?.close() } catch (_: Throwable) {}
                save(ctx, d); run.running = false
            }
        }.apply { isDaemon = true; start() }
        return run
    }

    fun stop(id: String) { runs[id]?.stop = true }

    // ===================== نص التفريغ =====================
    fun fmtTime(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
    }

    /** نص التفريغ: stamps=true سطر لكل جملة بتوقيتها · false فقرات متصلة. القطع الفاشلة بتظهر كعلامة ⚠ */
    fun transcriptText(d: Doc, stamps: Boolean): String = synchronized(d) {
        class Item(val start: Double, val text: String, val gap: Boolean, val spk: String)
        val items = ArrayList<Item>()
        for (s in d.segs) items.add(Item(s.start, s.text, false, s.spk))
        for (c in d.failed) items.add(Item(c * CHUNK_SEC, "⚠ [جزء ناقص من ${fmtTime(c * CHUNK_SEC)} إلى ${fmtTime(minOf(d.durSec, (c + 1) * CHUNK_SEC))}]", true, ""))
        items.sortBy { it.start }
        val sb = StringBuilder()
        if (stamps) {
            for (i in items) {
                if (i.gap) sb.append(i.text).append('\n')
                else sb.append('[').append(fmtTime(i.start)).append("] ").append(if (i.spk == "S") "🙋 " else "").append(i.text).append('\n')
            }
        } else {
            var para = 0; var last = ""
            for (i in items) {
                if (i.gap) { if (sb.isNotEmpty()) sb.append("\n\n"); sb.append(i.text).append("\n\n"); para = 0; last = ""; continue }
                val changed = i.spk != last && (i.spk == "S" || last == "S")
                if (changed || para > 650) { if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n\n"); para = 0 }
                else if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append(' ')
                if (para == 0 && i.spk == "S") sb.append("🙋 ")
                sb.append(i.text); para += i.text.length + 1; last = i.spk
            }
        }
        sb.toString().trim()
    }

    // ===================== تنظيم / تلخيص (نصوص) =====================
    class TextResult(val text: String, val usedWeb: Boolean)

    private fun gen(prompt: String, web: Boolean, maxTok: Int): TextResult {
        val ks = keys(); if (ks.isEmpty()) throw IOException("مفيش مفتاح Gemini — ضيفه من الإعدادات الأول")
        val mdl = model()
        var last: Throwable? = null
        var useWeb = web
        for (a in 0 until minOf(ks.size, 3) + 2) {
            val key = ks[a % ks.size]
            try {
                val r = Api.generate(mdl, key, prompt, null, maxTok, 0.2, false, useWeb)
                if (r.text.isNotBlank()) return TextResult(r.text.trim(), useWeb)
                last = IOException("رد فاضي (${r.finish})")
            } catch (e: NoKey) { throw e }
            catch (e: ApiErr) {
                last = e
                if (e.code == 400 && useWeb) useWeb = false   // الموديل/المفتاح مش بيدعم البحث ← نكمّل من غيره
                else if (e.code == 429 || e.code >= 500) { try { Thread.sleep(1500L * (a + 1)) } catch (_: InterruptedException) {} }
            } catch (e: Throwable) { last = e }
        }
        throw IOException("فشل الطلب: " + (last?.message ?: "").take(100))
    }

    private const val MAX_CHARS = 300_000

    private fun outLangLine(): String = if (Cfg.str("lec_out", "ar") == "same")
        "اكتب بنفس لغة المحاضرة." else "اكتب بالعربية المصرية الواضحة (سهلة المذاكرة)، وسيب المصطلحات العلمية بلغتها الأصلية بين قوسين أو كما هي."

    private fun webLine(web: Boolean): String = if (web)
        "\n- مسموح تستعين ببحث جوجل لتصحيح مصطلح أو إكمال تعريف ناقص فقط. كل نقطة مصدرها النت لازم تبدأ بالرمز 🌐 وتبقى قصيرة، ومتعدّلش ولا تناقض كلام المحاضر. باقي النقط من كلام المحاضر بس."
    else "\n- كل المحتوى لازم يكون من كلام المحاضر في التفريغ؛ متضيفش معلومات من برّه."

    private fun organizePrompt(text: String, web: Boolean): String =
        "أنت أستاذ جامعي بتنظّم تفريغ محاضرة لطالب بيذاكر منها.\n" +
        "المطلوب: عناوين رئيسية، وتحت كل عنوان نقط قليلة ومركّزة.\n" +
        "القواعد:\n- عدد العناوين الرئيسية من 5 لـ 12 حسب طول المحاضرة — مش أكتر.\n" +
        "- تحت كل عنوان من 3 لـ 6 نقط بس. كل نقطة جملة واحدة واضحة. ممنوع تعمل عشرات النقط.\n" +
        "- رتّب العناوين بتسلسل منطقي، مش بالضرورة بترتيب الكلام العشوائي.\n" +
        "- ${outLangLine()}" + webLine(web) + "\n\n" +
        "الشكل بالظبط (من غير أي كلام تاني قبل أو بعد):\n# عنوان المحاضرة\n## العنوان الرئيسي الأول\n- نقطة\n- نقطة\n## العنوان الرئيسي التاني\n- نقطة\n\n" +
        "نص التفريغ:\n" + text

    private fun summaryPrompt(text: String, short: Boolean, web: Boolean): String =
        "لخّص تفريغ المحاضرة ده لطالب.\n" +
        "القواعد:\n- " + (if (short) "ملخص من 3 لـ 4 جمل بس، وبعده 5 نقط أهم حاجات." else "ملخص فقرة واحدة (6 لـ 8 جمل) بيغطي الفكرة العامة، وبعده من 6 لـ 8 نقط لأهم الأفكار.") + "\n" +
        "- ${outLangLine()}" + webLine(web) + "\n\n" +
        "الشكل بالظبط (من غير أي كلام تاني):\n## الملخص\n<الفقرة>\n## أهم النقط\n- نقطة\n- نقطة\n\n" +
        "نص التفريغ:\n" + text

    private fun fullText(d: Doc): String {
        val t = transcriptText(d, false).replace(Regex("⚠ \\[جزء ناقص[^\\]]*\\]"), "").trim()
        if (t.isBlank()) throw IOException("مفيش تفريغ لسه — فرّغ المحاضرة الأول")
        return t
    }

    private fun parts(t: String): List<String> {
        if (t.length <= MAX_CHARS) return listOf(t)
        val n = Math.ceil(t.length / MAX_CHARS.toDouble()).toInt(); val size = t.length / n + 1
        return (0 until n).map { t.substring(it * size, minOf(t.length, (it + 1) * size)) }
    }

    /** «تنظيم»: عناوين + نقط. يتنده من خيط خلفي ويحفظ النتيجة في الدوكيومنت */
    fun organize(ctx: Context, d: Doc, web: Boolean): String {
        val t = fullText(d)
        var usedWeb = false
        val outs = parts(t).mapIndexed { i, p ->
            val r = gen(organizePrompt(p, web), web, 16000); usedWeb = usedWeb || r.usedWeb
            if (i == 0) r.text else r.text.lines().filterNot { it.startsWith("# ") && !it.startsWith("## ") }.joinToString("\n")
        }
        val res = outs.joinToString("\n")
        synchronized(d) { d.organized = res; d.organizedWeb = usedWeb }
        save(ctx, d); return res
    }

    /** «تلخيص»: فقرة + أهم النقط */
    fun summarize(ctx: Context, d: Doc, short: Boolean, web: Boolean): String {
        val t = fullText(d)
        var usedWeb = false
        val ps = parts(t)
        var res: String
        if (ps.size == 1) { val r = gen(summaryPrompt(ps[0], short, web), web, 6000); usedWeb = r.usedWeb; res = r.text }
        else {
            val mid = ps.joinToString("\n\n") { p -> gen(summaryPrompt(p, false, false), false, 6000).text }
            val r = gen(summaryPrompt(mid, short, web), web, 6000); usedWeb = r.usedWeb; res = r.text
        }
        synchronized(d) { d.summary = res; d.summaryWeb = usedWeb; d.summaryLen = if (short) "short" else "mid" }
        save(ctx, d); return res
    }

    // ===================== تحليل المذكرة (v993) — نفس منطق وضع المذاكرة في الـHTML =====================
    private const val AN_MAX_CHARS = 200_000

    private fun segsHash(d: Doc): String = synchronized(d) { d.segs.size.toString() + "_" + d.segs.sumOf { it.text.length } + "_" + d.segs.count { it.spk == "S" } }

    private fun analysisPrompt(text: String): String = buildString {
        append("أنت أستاذ جامعي خبير ومحرر أكاديمي محترف، متخصص في تحويل تفريغ المحاضرات الخام إلى مادة مذاكرة استثنائية الجودة. المطلوب مش تلخيص سطحي، المطلوب \"شغل فاخر\": مذكرة أكاديمية شاملة، عميقة، منظمة بعناية، تخلّي الطالب يذاكر منها من غير ما يرجع للمحاضرة الأصلية.\n\n")
        append("⚠️⚠️ تحذير حرج: أغلب المحاضرات طبية. أي معلومة طبية غلط ممكن تضر مريض حقيقي. الدقة شرط أساسي غير قابل للتفاوض.\n\n")
        append("═══ المصدر الوحيد للحقيقة ═══\nكل المحتوى الأساسي (bullets, detail, qna, keyPoints, definitions) لازم يتبني على كلام المحاضر الفعلي في النص. النص ده هو مصدر الحقيقة الوحيد.\n")
        append("علامة (طالب) قبل جملة معناها إنها اتقالت من متحدث تاني غير المحاضر (سؤال أو مقاطعة أو رد). الجمل من غير علامة هي كلام المحاضر.\n\n")
        append("═══ نظام تمييز الإضافات (حرج جدًا) ═══\nلو حبيت تضيف معلومة موثوقة من برّه كلام المحاضر (تعريف أوسع، سياق علمي معروف، تصنيف يفيد الفهم، إكمال خطوة ناقصة جوه تسلسل ذكره المحاضر):\n")
        append("1. لازم تبقى دقيقة وموثوقة 100% علميًا — لو في أي شك ولو 1% متضيفهاش خالص.\n2. متتعارضش مع أي حاجة قالها المحاضر ولو ضمنيًا.\n3. الإضافة تسد فجوة صغيرة جوه قسم موجود؛ ممنوع تخترع قسم كامل من عندك.\n")
        append("4. 🔴 حيط النص المضاف بـ [[ADD]] و [[/ADD]] بالظبط: \"...كلام المحاضر [[ADD]]المعلومة المضافة[[/ADD]] وباقي الكلام...\". العلامات إلزامية لأي جزء مضاف مهما صغر، وممنوعة على أي جزء منقول من كلام المحاضر.\n\n")
        append("═══ 🚨 إضافات طبية حساسة ═══\nلو الإضافة بتخص جرعة دواء أو معيار تشخيص محدد أو خطوة علاج مباشر، حيطها كمان بـ [[MEDWARN]]...[[/MEDWARN]] برّه الـ[[ADD]]: [[MEDWARN]][[ADD]]نص الإضافة[[/ADD]][[/MEDWARN]]. ولو مش متأكد 100% اتجاهلها خالص.\n\n")
        append("═══ 🏷️ تمييز المصطلحات العلمية (في bullets و detail) ═══\nكل مصطلح علمي أو تقني مهم يظهر لأول مرة في القسم (مرض، إجراء، مادة، خلية، مفهوم تشريحي/فسيولوجي دقيق) حيطه بـ [[TERM:تعريفه المختصر]]المصطلح[[/TERM]]. التعريف سطر واحد (8–15 كلمة) ودقيق 100%. علّم المصطلح مرة واحدة بس في القسم، من 2 لـ 5 مصطلحات لكل قسم، ومش كل كلمة.\n\n")
        append("═══ معايير الجودة ═══\n")
        append("- اشرح كل مفهوم بعمق يخلّي الطالب يفهم \"ليه\" مش بس \"إيه\"؛ اربط الأفكار، وضّح العلاقات السببية والاستثناءات اللي ذكرها المحاضر.\n")
        append("- رتّب منطقيًا بداية ونهاية لكل قسم، مش نقل حرفي للترتيب العشوائي للكلام.\n")
        append("- أمثلة والحالات السريرية/العملية اللي ذكرها المحاضر اشرحها بالتفصيل جوه القسم المرتبط.\n")
        append("- أي مقارنة ذكرها المحاضر (الفرق بين تقنيتين، مين أفضل وليه) نظّمها في جدول جوه comparisons.\n")
        append("- ابحث في النص كله عن أي سؤال من طالب ورد المحاضر عليه (الجمل اللي عليها علامة (طالب) وكمان أي سؤال متقال في الكلام) وسجّله في qna بالسؤال والإجابة كاملين ومفصّلين.\n")
        append("- لكل قسم رئيسي اقترح مصطلح علمي إنجليزي دقيق (زي اسم مقالة Wikipedia) يصلح للبحث عن صورة توضيحية حقيقية في imageSearchTerm. لو القسم مفيهوش مفهوم بصري (مقدمة/خاتمة) سيبه فاضي.\n")
        append("- 🔴🔴 الإشارات البصرية (visuals): المحاضر كتير بيشاور على حاجة قدامه من غير ما يسميها: \"عندنا هنا كذا\"، \"زي ما إنتو شايفين\"، \"شوفوا الشكل ده\"، \"ده اللي على الشاشة\"، \"الرسمة دي\"، \"هنا في الصورة\"، \"لاحظوا الجزء ده\"، \"as you can see\"، \"this one here\". لكل إشارة زي دي: (1) حدّد من سياق الجمل اللي قبلها وبعدها هو بيشاور على إيه بالظبط (شكل تشريحي؟ جدول؟ مخطط؟ أشعة؟ سلايد؟ جهاز؟) واكتبه في what بجملة واضحة. (2) سجّل ts = ثانية الجملة اللي فيها الإشارة (من الرقم بعد | في علامة الوقت). (3) اختار source: \"frame\" لو الأنسب لقطة من الفيديو نفسه (سلايد/رسمة/حاجة مخصوصة بالمحاضرة)، أو \"wiki\" لو الأنسب صورة مرجعية معروفة (شكل تشريحي، تصنيف، جهاز). (4) wikiTerm دايمًا: مصطلح Wikipedia إنجليزي دقيق للحاجة دي (بيتستعمل كبديل لو اللقطة مش متاحة). (5) callouts: أسماء الأجزاء اللي المحاضر نطقها وبيشاور عليها (تتحط كأسهم على اللقطة)، [] لو مفيش. حط الإشارة في القسم اللي بتخدمه. متخترعش إشارة لو مفيش مؤشر واضح، ومتكررش نفس الإشارة (حد أقصى 3 لكل قسم).\n")
        append("- خريطة ذهنية في mindMap: عقدة مركزية (موضوع المحاضرة) + فروع (فرع لكل قسم) وتحت كل فرع نقط قصيرة جدًا (كلمة أو اتنين).\n\n")
        append("النص الكامل للمحاضرة (كل سطر: [الوقت | الثواني] الكلام):\n").append(text).append("\n\n")
        append("""أرجع JSON فقط بدون أي شرح أو markdown، بالهيكل ده بالظبط:
{
  "title": "عنوان المحاضرة المستنتج",
  "subtitle": "وصف قصير جدًا (سطر واحد)",
  "mindMap": {"center": "الموضوع المركزي (كلمتين-تلاتة)", "branches": [{"label": "اسم الفرع", "points": ["نقطة قصيرة 1", "نقطة قصيرة 2"]}]},
  "sections": [{"heading": "عنوان القسم", "bullets": ["نقطة شرح (ممكن تحتوي [[ADD]]..[[/ADD]] أو [[TERM:..]]..[[/TERM]])"], "detail": "فقرة شرح موسّعة (4-7 جمل) تربط الأفكار وتوضّح العلاقات", "imageSearchTerm": "English Wikipedia term e.g. Pulpotomy", "visuals": [{"ts": 123.4, "phrase": "عندنا هنا الشكل ده", "what": "رسم تشريحي يوضّح الطبقات", "source": "frame", "wikiTerm": "Tooth anatomy", "callouts": ["Enamel", "Dentin"]}]}],
  "comparisons": [{"title": "الفرق بين X وY", "columns": ["المعيار", "الأول", "التاني"], "rows": [["معيار", "قيمة أ", "قيمة ب"]]}],
  "qna": [{"question": "سؤال الطالب كامل", "answer": "إجابة المحاضر كاملة ومفصّلة"}],
  "review": {"keyPoints": ["نقطة مراجعة"], "definitions": [{"term": "مصطلح", "meaning": "تعريف مختصر"}], "quickQuiz": [{"question": "سؤال مراجعة يختبر الفهم", "answer": "إجابة مختصرة"}]}
}

الأعداد: sections من 5 لـ 12 حسب طول المحتوى وعمقه، comparisons [] لو مفيش مقارنة صريحة، qna [] لو مفيش سؤال في النص، review.keyPoints من 8 لـ 15، quickQuiz من 5 لـ 10، mindMap.branches بعدد sections تقريبًا، و visuals [] لو القسم مفيهوش إشارة بصرية. اكتب بالعربية المصرية الواضحة وسيب المصطلحات العلمية بلغتها الأصلية.""")
    }

    /** يحلل التفريغ لهيكل مذكرة (JSON). بيتخزّن في الدوكيومنت ويتعاد بس لو التفريغ اتغيّر. يتنده من خيط خلفي. */
    fun analyze(ctx: Context, d: Doc): JSONObject {
        val h = segsHash(d)
        if (d.analysis.isNotBlank() && d.analysisHash == h) try { return JSONObject(d.analysis) } catch (_: Throwable) {}
        val ks = keys(); if (ks.isEmpty()) throw IOException("مفيش مفتاح Gemini — ضيفه من الإعدادات الأول")
        val lines = synchronized(d) {
            d.segs.sortedBy { it.start }.joinToString("\n") { "[" + fmtTime(it.start) + " | " + it.start.toInt() + "] " + (if (it.spk == "S") "(طالب) " else "") + it.text }
        }
        if (lines.isBlank()) throw IOException("مفيش تفريغ لسه — فرّغ المحاضرة الأول")
        val text = if (lines.length > AN_MAX_CHARS) lines.take(AN_MAX_CHARS) + "\n...[اتقطع باقي النص لطول المحاضرة]" else lines
        val prompt = analysisPrompt(text)
        val mdl = model()
        var last: Throwable? = null
        for (a in 0 until minOf(ks.size, 3) + 2) {
            val key = ks[a % ks.size]
            try {
                val r = Api.generate(mdl, key, prompt, null, 24000, 0.3, true, false)
                val j = parseJson(r.text)
                if (j != null && (j.optJSONArray("sections")?.length() ?: 0) > 0) {
                    synchronized(d) { d.analysis = j.toString(); d.analysisN++; d.analysisHash = h }
                    save(ctx, d); return j
                }
                last = IOException("رد التحليل مش مكتمل (${r.finish})")
            } catch (e: NoKey) { throw e }
            catch (e: ApiErr) {
                last = e
                if (e.code == 429 || e.code >= 500) { try { Thread.sleep(1500L * (a + 1)) } catch (_: InterruptedException) {} }
            } catch (e: Throwable) { last = e }
        }
        throw IOException("فشل التحليل: " + (last?.message ?: "").take(100))
    }

    // ===================== تحليل الناتج للعرض =====================
    class Pt(val text: String, val bullet: Boolean, val web: Boolean)
    class Sec(val head: String, val pts: List<Pt>)
    class Outline(val title: String, val secs: List<Sec>)

    fun parseOutline(raw: String): Outline {
        var title = ""; val secs = ArrayList<Sec>(); var head: String? = null; var pts = ArrayList<Pt>()
        fun flush() { val h = head; if (h != null || pts.isNotEmpty()) secs.add(Sec(h ?: "", pts)); pts = ArrayList(); head = null }
        for (l0 in raw.lines()) {
            val l = l0.trim(); if (l.isEmpty()) continue
            when {
                l.startsWith("## ") -> { flush(); head = l.removePrefix("## ").trim() }
                l.startsWith("# ") -> title = l.removePrefix("# ").trim()
                l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• ") -> { val t = l.substring(2).trim(); pts.add(Pt(t.removePrefix("🌐").trim(), true, t.startsWith("🌐"))) }
                else -> pts.add(Pt(l.removePrefix("🌐").trim(), false, l.startsWith("🌐")))
            }
        }
        flush()
        return Outline(title, secs)
    }

    fun outlineText(title: String, raw: String): String {
        val o = parseOutline(raw); val sb = StringBuilder()
        val t = o.title.ifBlank { title }; if (t.isNotBlank()) sb.append(t).append("\n\n")
        for (s in o.secs) {
            if (s.head.isNotBlank()) sb.append(s.head).append('\n')
            for (p in s.pts) sb.append(if (p.bullet) "• " else "").append(if (p.web) "🌐 " else "").append(p.text).append('\n')
            sb.append('\n')
        }
        return sb.toString().trim()
    }
}
