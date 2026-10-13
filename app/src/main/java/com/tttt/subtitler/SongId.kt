package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** نتيجة التعرف على أغنية */
class SongMatch(val title: String, val artist: String, val album: String, val conf: Int = 100)

/**
 * (v192) التعرف على أغنية مجهولة بالبصمة عن طريق خدمة AudD (api.audd.io): بياخد مقطع ~12ث من الأغنية، يبعته للخدمة، ويرجع الاسم والفنان.
 * محتاج توكن من audd.io (بتحطه مرة واحدة). المقطع بيتبعت لسيرفراتهم؛ والخدمة ليها حد مجاني وبعده بتتحاسب.
 */
object SongId {
    private const val ENDPOINT = "https://api.audd.io/"
    private const val CLIP_SEC = 12.0

    fun token(): String = Cfg.str("audd_token", "").trim()
    fun saveToken(t: String) = Cfg.put("audd_token", t.trim())

    /** الاسم المحفوظ يدويًا لأغنية (من نتيجة التعرف) — "العنوان\u0001الفنان" */
    fun override(id: Long): Pair<String, String>? {
        val v = Cfg.str("mname_$id", "")
        if (v.isBlank()) return null
        val p = v.split('\u0001'); return p[0] to p.getOrElse(1) { "" }
    }
    fun saveOverride(id: Long, m: SongMatch) = Cfg.put("mname_$id", m.title + "\u0001" + m.artist)
    fun clearOverride(id: Long) = Cfg.put("mname_$id", "")

    // ===== (v251) حالة التعرّف على الأغنية: اتعرفت قبل كده؟ / اليوزر أكّد الاسم؟ =====
    /** "1" = اتعرفت (تلقائي أو يدوي) · "t" = جيميناي سمعها ومش متأكد (ما نعيدش تلقائي) · "" = لسه ما اتدوّرش عليها */
    fun known(id: Long) = Cfg.str("mknown_$id", "") == "1"
    fun searched(id: Long) = Cfg.str("mknown_$id", "").isNotEmpty()
    fun markKnown(id: Long) = Cfg.put("mknown_$id", "1")
    fun markTried(id: Long) { if (Cfg.str("mknown_$id", "") != "1") Cfg.put("mknown_$id", "t") }
    /** ✓ = اليوزر قال الاسم صح (مقفول: مفيش بحث تاني) */
    fun verified(id: Long) = Cfg.str("mverd_$id", "") == "ok"
    fun setVerified(id: Long, v: Boolean) = Cfg.put("mverd_$id", if (v) "ok" else "")
    /** إعادة تعرّف / ✗ : انسى إنها اتعرفت واتأكدت */
    fun resetKnown(id: Long) { Cfg.put("mknown_$id", ""); Cfg.put("mverd_$id", "") }
    fun autoOn() = Cfg.str("song_auto_id", "1") != "0"
    fun setAuto(on: Boolean) = Cfg.put("song_auto_id", if (on) "1" else "0")
    const val UNSURE = "جيميناي مش متأكد"

    /**
     * (v193) التعرف التلقائي: لو فيه توكن AudD بنجرّبه الأول؛ ولو مفيش توكن (أو ما اتعرفتش، أو اليوزر قال إن الاسم غلط)
     * بنبعت المقطع لجيميناي يقول اسم الأغنية والفنان. [avoid] = أسماء اتقال إنها غلط ("العنوان — الفنان").
     */
    fun auto(ctx: Context, t: Track, startSec: Double, avoid: List<String>, onDone: (SongMatch?, String) -> Unit) {
        if (token().isEmpty() || avoid.isNotEmpty()) { gemini(ctx, t, startSec, avoid, onDone); return }
        recognize(ctx, t, startSec) { m, msg -> if (m != null) onDone(m, msg) else gemini(ctx, t, startSec, avoid) { m2, msg2 -> onDone(m2, if (m2 == null) msg2.ifBlank { msg } else "") } }
    }

    /** (v267) جيميناي بيسمع جزء كبير (~3 دقايق) من المسار بدل 25 ثانية — كلمات أكتر = اسم وفنان أدق */
    private const val BIG_SEC = 180.0
    private const val MIN_CONF = 55

    /** حجم المقطع الكبير حوالين startSec (بيتحط startSec في نصه) */
    private fun bigWindow(total: Double, startSec: Double): Pair<Double, Double> {
        val len = minOf(BIG_SEC, total.coerceAtLeast(1.0))
        val a = (startSec - len / 2).coerceIn(0.0, (total - len).coerceAtLeast(0.0))
        return a to minOf(a + len, total.coerceAtLeast(a + 1))
    }

    fun gemini(ctx: Context, t: Track, startSec: Double, avoid: List<String>, onDone: (SongMatch?, String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty()) { onDone(null, "مفيش توكن AudD ولا مفتاح Gemini — ضيف واحد منهم"); return@Thread }
                val w = loadWindow(app, t, startSec)
                if (w == null) { onDone(null, "المقطع ده صامت — شغّل الأغنية لحد جزء فيه صوت وجرّب تاني"); return@Thread }
                ask(keys, w.bytes, w.durSec, t.title, t.artist, avoid, false, 4, onDone)
            } catch (e: Throwable) { LogStore.err("songid-gemini", e); onDone(null, "فشل: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }

    /**
     * (v267) البرومبت القوي: كلمات + صوت + معرفة ← نوع المسار (أغنية/قرآن/نشيد/كلام) ← الاسم والفنان/القارئ، وبحث جوجل للتأكد.
     * [captured] = الصوت ملتقط من تطبيق تاني (مفيش اسم ملف نعتمد عليه).
     */
    private fun prompt(durSec: Double, hintTitle: String, hintArtist: String, avoid: List<String>, captured: Boolean, search: Boolean, multi: Boolean = false): String = buildString {
        val mins = "%.1f".format(java.util.Locale.US, durSec / 60.0)
        append("You are the world's best audio identification expert: popular music of every language and era (Arabic — Egyptian, Gulf, Levantine, Maghreb, classical tarab — plus English, Turkish, Persian, Indian, Korean…), Quran recitation (all qurra and riwayat), anasheed, soundtracks, podcasts, lectures and any other audio.\n\n")
        append("INPUT: a LARGE excerpt (~$mins minutes) of ONE audio track" + (if (captured) " captured from another app's playback (ignore ads, voiceovers and sound effects that are not part of the track)" else "") + ". Because it is long, you can hear many lyric lines / verses and the voice repeatedly — use all of it, not just the first seconds.\n\n")
        append("STEP 1 — LISTEN. Internally transcribe the most distinctive sung/spoken lines you can clearly hear (the hook/chorus and 2-3 other lines, in the original language). Put them in \"heard\" (max 300 characters, no invention).\n")
        append("STEP 2 — CLASSIFY: kind = song | quran | nasheed | speech | soundtrack | other.\n")
        append("STEP 3 — IDENTIFY by kind:\n")
        append("• song: \"title\" = the official released title of THIS song (original language/script — Arabic songs in Arabic, English in English). \"artist\" = the PERFORMER (lead singer / band), not the composer, lyricist, label, uploader or channel. If it is a cover, remix, live or duet version, name the performer you actually hear. Do not confuse songs that share a chorus or lyrics; match the specific melody, arrangement and voice.\n")
        append("• quran: \"artist\" = the reciter's full name in Arabic (identify the voice, style and riwaya; e.g. مشاري راشد العفاسي، عبد الباسط عبد الصمد، ماهر المعيقلي…). \"title\" = the surah name in Arabic with the verse range you hear, e.g. «سورة الكهف (١–١٠)». Decide the surah from the verses actually recited.\n")
        append("• nasheed: \"artist\" = the munshid / group, \"title\" = the nasheed's title.\n")
        append("• speech (lecture, sermon, podcast, audiobook): \"artist\" = the speaker, \"title\" = the lecture/episode/chapter title or its clear topic.\n")
        append("• soundtrack / other: best official title + composer or performer.\n")
        if (search) append("STEP 4 — VERIFY with Google Search: search the distinctive lines you heard (quoted, in the original language) together with your candidate title/artist, and confirm that the title and the performer really match each other. If the search contradicts your first guess, trust the search plus what you hear.\n")
        append("\nHINTS (weak!): ")
        if (!captured && (hintTitle.isNotBlank() || hintArtist.isNotBlank())) append("the device file is named \"$hintTitle\" with artist tag \"$hintArtist\". File names and tags are OFTEN WRONG, generic (\"Track 01\", \"audio_2024\"), or the uploader's name — use them only to break a tie, never override what you hear. ")
        else append("none. ")
        if (avoid.isNotEmpty()) append("\nThese answers were already reported WRONG by the user — NEVER return them again (nor a trivial spelling variant of them): " + avoid.joinToString("; ") + ". Find the real answer instead.")
        append("\n\nRULES:\n- Never invent. If you are below ~70% sure of a field, leave THAT field as \"\" (empty is better than wrong). Knowing the title but not the performer (or the reverse) is fine — fill only what you are sure of.\n")
        append("- Do not output a website, channel, uploader, or generic words like \"Unknown\" / \"Track\".\n")
        append("- \"confidence\" = your honest 0-100 certainty that title AND artist are both correct.\n")
        if (multi) append("- The user will choose from your list, so give up to 4 DISTINCT candidates (different songs / different performers), best first, each a real existing track with its own honest confidence. Never pad the list with guesses you do not believe in; one or two good candidates is fine.\n")
        else append("- Give exactly ONE candidate (the best), or an empty list if you are not sure.\n")
        append("\nOUTPUT: one JSON object only, no markdown, no commentary:\n")
        append("{\"kind\":\"\",\"heard\":\"\",\"candidates\":[{\"title\":\"\",\"artist\":\"\",\"album\":\"\",\"confidence\":0}]}")
    }

    private fun parseJson(txt: String): JSONObject? {
        val a = txt.indexOf('{'); val b = txt.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(txt.substring(a, b + 1)) } catch (_: Throwable) { null }
    }

    /** يسأل جيميناي (بحث جوجل الأول، وبدونه لو الموديل/المفتاح رفض) على كذا مفتاح. على خيط خلفية. بيرجّع (المرشحين بترتيب الأرجحية، رسالة الخطأ) */
    private fun askList(keys: List<String>, wav: ByteArray, durSec: Double, hintTitle: String, hintArtist: String, avoid: List<String>, captured: Boolean, maxKeys: Int, multi: Boolean): Pair<List<SongMatch>, String> {
        val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
        Api.readTimeoutOverride.set(180000)
        var res: JSONObject? = null; var err = ""
        try {
            for (k in 0 until keys.size.coerceAtMost(maxKeys)) {
                val key = keys[k % keys.size]
                for (useSearch in booleanArrayOf(true, false)) {
                    try {
                        val r = Api.generate(model, key, prompt(durSec, hintTitle, hintArtist, avoid, captured, useSearch, multi), wav, 4096, 0.1, true, useSearch)
                        val j = parseJson(r.text)
                        if (j != null) { res = j; break }
                        err = "رد غير مفهوم"
                    } catch (e: ApiErr) {
                        err = e.message ?: ""
                        if (e.code == 429) break   // الكوتة على المفتاح ده: نجرّب المفتاح اللي بعده مباشرة
                    } catch (e: Throwable) { err = e.message ?: "" }
                }
                if (res != null) break
            }
        } finally { Api.readTimeoutOverride.remove() }
        val j = res ?: return emptyList<SongMatch>() to "جيميناي ما ردّش: $err"
        val out = ArrayList<SongMatch>()
        fun add(o: JSONObject) {
            val title = o.optString("title").trim(); var artist = o.optString("artist").trim()
            val conf = if (o.has("confidence")) o.optInt("confidence", 100) else 100
            if (artist.equals("unknown", true) || artist == "غير معروف") artist = ""
            if (title.isEmpty() || title.equals("unknown", true) || title == "غير معروف") return
            if (out.none { it.title.equals(title, true) && it.artist.equals(artist, true) }) out.add(SongMatch(title, artist, o.optString("album").trim(), conf))
        }
        val arr = j.optJSONArray("candidates")
        if (arr != null) { for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) } } else add(j)   // لو الموديل رجّع الشكل القديم (title/artist فوق)
        return out to ""
    }

    private fun ask(keys: List<String>, wav: ByteArray, durSec: Double, hintTitle: String, hintArtist: String, avoid: List<String>, captured: Boolean, maxKeys: Int, onDone: (SongMatch?, String) -> Unit) {
        val (list, err) = askList(keys, wav, durSec, hintTitle, hintArtist, avoid, captured, maxKeys, false)
        if (err.isNotEmpty()) { onDone(null, err); return }
        val best = list.firstOrNull()
        if (best == null || best.conf < MIN_CONF) onDone(null, UNSURE + " من الأغنية/المقطع ده" + (if (best != null) " (ثقته ${best.conf}%)" else "") + " — جرّب مقطع تاني من وسط المسار")
        else onDone(best, "")
    }

    /** مقطع كبير حوالين startSec، وبيجرّب أول المسار لو طلع صامت. null لو مفيش صوت */
    private fun loadWindow(app: Context, t: Track, startSec: Double): WavChunk? {
        val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
        try {
            val total = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
            var win = bigWindow(total, startSec)
            var w = try { src.wav(win.first, win.second) } catch (_: Throwable) { null }
            if ((w == null || w.silent) && win.first > 1.0) { win = bigWindow(total, 0.0); w = try { src.wav(win.first, win.second) } catch (_: Throwable) { null } }
            return if (w == null || w.silent) null else w
        } finally { try { src.close() } catch (_: Throwable) {} }
    }

    /**
     * (v269) خيارات للاختيار بإيدك: لحد 4 مرشحين (أغنية/قارئ/نشيد…) بترتيب الأرجحية. لو فيه توكن AudD ومفيش avoid، نتيجته بتتحط الأولى.
     * [avoid] = أسماء اتعرضت قبل كده («العنوان — الفنان») فمش هتتكرر. على خيط خلفية.
     */
    fun options(ctx: Context, t: Track, startSec: Double, avoid: List<String>, onDone: (List<SongMatch>, String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty() && token().isEmpty()) { onDone(emptyList(), "مفيش توكن AudD ولا مفتاح Gemini — ضيف واحد منهم"); return@Thread }
                val out = ArrayList<SongMatch>()
                if (token().isNotEmpty() && avoid.isEmpty()) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    recognize(app, t, startSec) { m, _ -> if (m != null && m.title.isNotBlank()) out.add(SongMatch(m.title, m.artist, m.album, 95)); latch.countDown() }
                    latch.await(50, java.util.concurrent.TimeUnit.SECONDS)
                }
                var err = ""
                if (keys.isNotEmpty()) {
                    val w = loadWindow(app, t, startSec)
                    if (w == null) err = "المقطع ده صامت — شغّل الأغنية لحد جزء فيه صوت وجرّب تاني"
                    else {
                        val (list, e) = askList(keys, w.bytes, w.durSec, t.title, t.artist, avoid, false, 4, true)
                        err = e
                        for (m in list) if (m.conf >= 20 && out.none { it.title.equals(m.title, true) && it.artist.equals(m.artist, true) }) out.add(m)
                    }
                }
                val bad = avoid.map { it.trim().lowercase() }
                val res = out.filter { m -> val a = (m.title + " — " + m.artist).trim().lowercase(); val b = m.title.trim().lowercase(); a !in bad && b !in bad }
                onDone(res, if (res.isEmpty()) err.ifBlank { UNSURE + " — مفيش خيارات جديدة، جرّب مقطع تاني أو اكتب الاسم بإيدك" } else "")
            } catch (e: Throwable) { LogStore.err("songid-options", e); onDone(emptyList(), "فشل: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }

    /** على خيط خلفية. startSec = من أنهي ثانية ناخد المقطع. onDone(نتيجة أو null، رسالة خطأ/عدم تطابق) */
    fun recognize(ctx: Context, t: Track, startSec: Double, onDone: (SongMatch?, String) -> Unit) {
        val app = ctx.applicationContext
        val tk = token()
        if (tk.isEmpty()) { onDone(null, "محتاج توكن AudD"); return }
        Thread {
            try {
                val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
                val total = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
                val a = startSec.coerceIn(0.0, (total - CLIP_SEC).coerceAtLeast(0.0))
                val w = try { src.wav(a, minOf(a + CLIP_SEC, total.coerceAtLeast(a + 1))) } catch (_: Throwable) { null }
                try { src.close() } catch (_: Throwable) {}
                if (w == null || w.silent) { onDone(null, "المقطع ده صامت — شغّل الأغنية لحد جزء فيه صوت وجرّب تاني"); return@Thread }
                val pk = AudioEnc.pack(w.bytes)
                val ext = if (pk.mime.contains("wav")) "wav" else "m4a"
                val body = multipart(tk, pk.bytes, "clip.$ext", pk.mime)
                val c = URL(ENDPOINT).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 45000
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$B")
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    parse(txt, code, onDone)
                } finally { c.disconnect() }
            } catch (e: Throwable) { LogStore.err("songid", e); onDone(null, "فشل الاتصال: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }

    private const val B = "----SubtitlerBoundary7MA4YWxk"
    private fun multipart(token: String, data: ByteArray, name: String, mime: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun s(x: String) = o.write(x.toByteArray(Charsets.UTF_8))
        s("--$B\r\nContent-Disposition: form-data; name=\"api_token\"\r\n\r\n$token\r\n")
        s("--$B\r\nContent-Disposition: form-data; name=\"return\"\r\n\r\napple_music,spotify\r\n")
        s("--$B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $mime\r\n\r\n")
        o.write(data); s("\r\n--$B--\r\n")
        return o.toByteArray()
    }

    private fun parse(txt: String, code: Int, onDone: (SongMatch?, String) -> Unit) {
        val j = try { JSONObject(txt) } catch (_: Throwable) { null }
        if (j == null) { onDone(null, "رد غير مفهوم من الخدمة (كود $code)"); return }
        if (j.optString("status") != "success") {
            val er = j.optJSONObject("error")
            val m = er?.optString("error_message").orEmpty()
            onDone(null, if (m.isNotBlank()) "الخدمة رفضت الطلب: $m" else "الخدمة رفضت الطلب (كود $code)"); return
        }
        val r = j.optJSONObject("result")
        if (r == null) { onDone(null, "ما اتعرفتش — جرّب مقطع تاني من وسط الأغنية"); return }
        onDone(SongMatch(r.optString("title"), r.optString("artist"), r.optString("album")), "")
    }

    // ===== (v197) التعرف من مقطع WAV جاهز (صوت الجهاز الملتقط من تطبيق تاني) =====
    fun autoClip(wav: ByteArray, avoid: List<String>, onDone: (SongMatch?, String) -> Unit) {
        if (token().isEmpty() || avoid.isNotEmpty()) { geminiClip(wav, avoid, onDone); return }
        recognizeClip(wav) { m, msg -> if (m != null) onDone(m, "") else geminiClip(wav, avoid) { m2, msg2 -> onDone(m2, if (m2 == null) msg2.ifBlank { msg } else "") } }
    }

    fun recognizeClip(wav: ByteArray, onDone: (SongMatch?, String) -> Unit) {
        val tk = token()
        if (tk.isEmpty()) { onDone(null, "محتاج توكن AudD"); return }
        Thread {
            try {
                val pk = AudioEnc.pack(wav)
                val ext = if (pk.mime.contains("wav")) "wav" else "m4a"
                val body = multipart(tk, pk.bytes, "clip.$ext", pk.mime)
                val c = URL(ENDPOINT).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 45000
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$B")
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    parse(txt, code, onDone)
                } finally { c.disconnect() }
            } catch (e: Throwable) { LogStore.err("songid-clip", e); onDone(null, "فشل الاتصال: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }

    fun geminiClip(wav: ByteArray, avoid: List<String>, onDone: (SongMatch?, String) -> Unit) {
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty()) { onDone(null, "مفيش توكن AudD ولا مفتاح Gemini"); return@Thread }
                val dur = ((wav.size - 44).coerceAtLeast(0) / 2) / 16000.0   // WAV 16kHz mono PCM16
                ask(keys, wav, dur, "", "", avoid, true, 3, onDone)
            } catch (e: Throwable) { LogStore.err("songid-geminiclip", e); onDone(null, "فشل: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }
}
