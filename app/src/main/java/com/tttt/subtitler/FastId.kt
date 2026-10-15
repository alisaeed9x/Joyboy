package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * (v314) تعرّف سريع على الأغنية بـ3 طلبات صغيرة ورا بعض بدل الطلب الكبير القديم (مقاطع 30ث × عدة مفاتيح + انتظار الاتفاق):
 *  1) المغني (الأهم): مقطع ~15ث بس، رد صغير جدًا (JSON من 3 حقول)، وبيتبعت على مفتاحين بالتوازي بمقطعين مختلفين — أول رد واثق يكسب.
 *  2) اسم الأغنية: نفس المقطع + المغني اللي اتعرف بيتحط «حقيقة مؤكدة» عشان الدقة تزيد.
 *  3) الألبوم: طلب نصي من غير صوت (اسم الأغنية + المغني).
 * كل مرحلة بتظهر نتيجتها على الشاشة أول ما توصل ([partial]) والكلمات والغلاف بيبدأوا مرة واحدة بالاسم الصح بعد ما تخلص الـ3.
 * لو فشلت أي مرحلة أساسية (المغني أو الاسم) بنرجّع null والمتصل بيكمّل بالطريقة القديمة (SongId.smart).
 */
object FastId {
    private const val CLIP = 15.0
    private const val MIN_ARTIST = 65     // أقل ثقة نقبلها في المغني
    private const val MIN_TITLE = 55      // أقل ثقة نقبلها في الاسم (والمغني معروف)

    /** الاسم/المغني اللي اتعرفوا لحد دلوقتي وبيتعرضوا مؤقتًا (title to artist) — بيتمسحوا لما العملية تخلص */
    val partial = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, String>>()
    /** نص المرحلة الحالية للعرض (🎤 / 🎵 / 💿) */
    val stage = java.util.concurrent.ConcurrentHashMap<Long, String>()

    fun clear(id: Long) { partial.remove(id); stage.remove(id) }

    /** (v315) أغاني اليوزر سابها (غيّر الأغنية) — العملية بتقف عند أول فاصل بين المراحل بدل ما تكمّل وتحرق طلبات */
    private val gone = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    const val CANCELLED = "__cancelled__"
    fun abandon(id: Long) { if (id >= 0) gone.add(id) }
    fun resume(id: Long) { gone.remove(id) }
    private fun isGone(id: Long) = gone.contains(id)

    /** (v315) نتيجة مرحلة المغني بتتحفظ فورًا: لو اليوزر غيّر الأغنية في النص ورجع لها، بنكمّل من مرحلة الاسم من غير ما نعيد المغني */
    private fun savePart(id: Long, artist: String, lat: String, conf: Int) = Cfg.put("fpart_$id", artist + "\u0001" + lat + "\u0001" + conf)
    private fun loadPart(id: Long): Triple<String, String, Int>? {
        val v = Cfg.str("fpart_$id", ""); if (v.isBlank()) return null
        val p = v.split('\u0001'); if (p.size < 3 || p[0].isBlank()) return null
        return Triple(p[0], p[1], p[2].toIntOrNull() ?: 100)
    }
    fun forgetPart(id: Long) = Cfg.put("fpart_$id", "")

    private fun parseJson(txt: String): JSONObject? {
        val a = txt.indexOf('{'); val b = txt.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(txt.substring(a, b + 1)) } catch (_: Throwable) { null }
    }

    private fun bad(s: String): Boolean {
        val x = s.trim().lowercase()
        return x.isEmpty() || x == "unknown" || x == "غير معروف" || x == "n/a" || SongId.looksLikeSite(s)
    }

    /** مقطع غير صامت بطول ~15ث من نقطة [at]؛ لو صامت بيجرّب نقاط تانية. */
    private fun clips(app: Context, t: Track, startSec: Double): List<WavChunk> {
        val out = ArrayList<WavChunk>()
        val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
        try {
            val total = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
            if (total <= 1.0) return out
            val len = CLIP.coerceAtMost(total)
            fun one(a: Double): WavChunk? {
                val a0 = a.coerceIn(0.0, (total - len).coerceAtLeast(0.0)); val b0 = minOf(a0 + len, total)
                if (b0 - a0 < 4.0) return null
                val w = try { src.wav(a0, b0) } catch (_: Throwable) { null }
                return if (w != null && !w.silent) w else null
            }
            // أول مقطع: حوالين مكان التشغيل/نص الأغنية (غالبًا فيه غناء)، تاني: من ربع الأغنية، وبعدهم احتياطي لو طلعوا صامتين
            val offs = listOf(startSec - len / 2, total * 0.25, total * 0.62, total * 0.08)
            for (o in offs) {
                if (out.size >= 2) break
                if (out.any { Math.abs(it.startSec - o) < len }) continue
                one(o)?.let { out.add(it) }
            }
            if (out.isEmpty()) one(0.0)?.let { out.add(it) }
        } finally { try { src.close() } catch (_: Throwable) {} }
        return out
    }

    /**
     * يبعت نفس البرومبت لعدد من المقاطع/المفاتيح بالتوازي ويرجّع أول رد (JSON) يعدّي [accept]. null لو مفيش.
     * الطلب الأبطأ بيتساب (مش بنستناه) أول ما يجي رد مقبول.
     */
    private fun race(keys: List<String>, clips: List<WavChunk>, prompt: String, maxTok: Int, timeoutMs: Int, stop: () -> Boolean, accept: (JSONObject) -> Boolean): Pair<JSONObject?, String> {
        if (keys.isEmpty() || clips.isEmpty()) return null to "مفيش مفتاح أو مقطع"
        val n = minOf(2, keys.size, clips.size).coerceAtLeast(1)
        val win = AtomicReference<JSONObject?>(null)
        val err = AtomicReference("")
        val latch = CountDownLatch(1)
        val left = AtomicInteger(n)
        val done = AtomicBoolean(false)
        for (i in 0 until n) {
            Thread {
                try {
                    Api.readTimeoutOverride.set(timeoutMs)
                    val rot = keys.drop(i % keys.size) + keys.take(i % keys.size)   // كل مقطع يبدأ بمفتاح مختلف
                    for (k in rot.take(3)) {
                        if (done.get() || stop()) break
                        try {
                            val r = Api.generate(Models.DEFAULT, k, prompt, clips[i % clips.size].bytes, maxTok, 0.0, true, false)
                            val j = parseJson(r.text)
                            if (j != null && accept(j)) { if (win.compareAndSet(null, j)) { done.set(true); latch.countDown() }; break }
                            if (j == null) err.set("رد غير مفهوم")
                        } catch (e: ApiErr) { err.set(e.message ?: ""); if (e.code != 429 && e.code != 503 && e.code != 500) break }
                        catch (e: Throwable) { err.set(e.message ?: ""); break }
                    }
                } catch (e: Throwable) { LogStore.err("fastid-race", e) }
                finally { try { Api.readTimeoutOverride.remove() } catch (_: Throwable) {}; if (left.decrementAndGet() == 0) latch.countDown() }
            }.apply { isDaemon = true }.start()
        }
        latch.await(timeoutMs.toLong() + 5000L, TimeUnit.MILLISECONDS)
        return win.get() to err.get()
    }

    private fun artistPrompt(hintTitle: String): String = buildString {
        append("Listen to this short excerpt of ONE audio track and identify WHO performs it: the lead singer / band / Quran reciter / munshid / speaker heard in THIS recording.\n")
        append("Decide from the voice, the language, the style and the sung/spoken lines. Many songs (especially classic Arabic ones) are re-sung by different singers: name the one you actually hear, not the most famous original.\n")
        append("Do NOT return a website, channel, uploader, label, composer or lyricist. If you are below ~65% sure, return an empty string for artist.\n")
        if (hintTitle.isNotBlank()) append("Weak hint (often wrong): the file is named \"$hintTitle\".\n")
        append("artist = in the singer's original script (Arabic singers in Arabic). artist_latin = the same name in English letters exactly as on Spotify / Apple Music.\n")
        append("OUTPUT one JSON object only, no markdown: {\"artist\":\"\",\"artist_latin\":\"\",\"confidence\":0}")
    }

    private fun titlePrompt(artist: String, hintTitle: String): String = buildString {
        append("The performer of this audio excerpt is already VERIFIED: \"$artist\" (treat it as ground truth). Identify the exact title of the song (or surah / nasheed / lecture) he/she performs in this excerpt.\n")
        append("Use the sung/spoken lines, the melody and the arrangement; do not confuse songs that share a chorus. title = official released title in the original script. title_latin = the same title in English letters as on Spotify / Apple Music.\n")
        append("If you are below ~60% sure of the title, return an empty string.\n")
        if (hintTitle.isNotBlank()) append("Weak hint (often wrong): the file is named \"$hintTitle\".\n")
        append("OUTPUT one JSON object only, no markdown: {\"title\":\"\",\"title_latin\":\"\",\"confidence\":0}")
    }

    private fun albumPrompt(title: String, artist: String): String =
        "Song: \"$title\" — performer: \"$artist\".\nWhat is the ORIGINAL official album / EP this recording was first released on (if it was released only as a single, the single's title)? " +
            "Not a compilation, greatest-hits, playlist or YouTube channel. If you are below ~70% sure, return an empty string.\n" +
            "OUTPUT one JSON object only, no markdown: {\"album\":\"\",\"album_latin\":\"\",\"year\":\"\"}"

    /**
     * على خيط خلفية. [onPartial] بتتنادى (من خيط الخلفية) كل ما مرحلة تخلص عشان الشاشة تتحدّث.
     * onDone(النتيجة الكاملة أو null لو المغني/الاسم ما اتعرفوش، رسالة).
     */
    fun run(ctx: Context, t: Track, startSec: Double, onPartial: () -> Unit, onDone: (SongMatch?, String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty()) { onDone(null, "مفيش مفتاح Gemini"); return@Thread }
                val hint = SongId.cleanHint(t.title)
                var artist: String; var artistLat: String; var artistConf: Int
                val saved = loadPart(t.id)
                var cl: List<WavChunk> = emptyList()
                if (saved != null) {
                    // (v315) المغني اتعرف قبل كده (العملية اتقطعت لما غيّرت الأغنية) — نكمّل من مرحلة الاسم
                    artist = saved.first; artistLat = saved.second; artistConf = saved.third
                    partial[t.id] = (hint to artist)
                    stage[t.id] = "🎵 بدوّر على اسم الأغنية…"; onPartial()
                    if (isGone(t.id)) { onDone(null, CANCELLED); return@Thread }
                    cl = clips(app, t, startSec)
                    if (cl.isEmpty()) { onDone(null, "المقطع ده صامت — شغّل الأغنية لحد جزء فيه صوت وجرّب تاني"); return@Thread }
                } else {
                    stage[t.id] = "🎤 بدوّر على المغني…"; onPartial()
                    cl = clips(app, t, startSec)
                    if (cl.isEmpty()) { onDone(null, "المقطع ده صامت — شغّل الأغنية لحد جزء فيه صوت وجرّب تاني"); return@Thread }
                    if (isGone(t.id)) { onDone(null, CANCELLED); return@Thread }

                    // ===== 1) المغني =====
                    val (ja, ea) = race(keys, cl, artistPrompt(hint), 160, 25000, { isGone(t.id) }) { j ->
                        !bad(j.optString("artist")) && (if (j.has("confidence")) j.optInt("confidence", 100) else 100) >= MIN_ARTIST
                    }
                    if (ja == null) { onDone(null, if (isGone(t.id)) CANCELLED else ea.ifBlank { "المغني ما اتعرفش" }); return@Thread }
                    artist = ja.optString("artist").trim(); artistLat = ja.optString("artist_latin").trim()
                    artistConf = if (ja.has("confidence")) ja.optInt("confidence", 100) else 100
                    savePart(t.id, artist, artistLat, artistConf)
                    partial[t.id] = (hint to artist); stage[t.id] = "🎵 بدوّر على اسم الأغنية…"; onPartial()
                    if (isGone(t.id)) { onDone(null, CANCELLED); return@Thread }
                }

                // ===== 2) اسم الأغنية (والمغني حقيقة مؤكدة) =====
                val (jt, et) = race(keys, cl, titlePrompt(artist, hint), 160, 25000, { isGone(t.id) }) { j ->
                    !bad(j.optString("title")) && (if (j.has("confidence")) j.optInt("confidence", 100) else 100) >= MIN_TITLE
                }
                if (jt == null) { onDone(null, if (isGone(t.id)) CANCELLED else et.ifBlank { "اسم الأغنية ما اتعرفش" }); return@Thread }
                val title = jt.optString("title").trim(); val titleLat = jt.optString("title_latin").trim()
                val titleConf = if (jt.has("confidence")) jt.optInt("confidence", 100) else 100
                partial[t.id] = (title to artist); stage[t.id] = "💿 بدوّر على الألبوم…"; onPartial()
                if (isGone(t.id)) { onDone(null, CANCELLED); return@Thread }   // (v315) اليوزر سابها: مفيش طلب ألبوم

                // ===== 3) الألبوم (نصي من غير صوت، مهلة قصيرة — لو فشل نكمّل من غيره) =====
                var album = ""; var albumLat = ""
                try {
                    Api.readTimeoutOverride.set(10000)
                    for (k in SongId.textKeys().take(2)) {
                        try {
                            val r = Api.generate(Models.DEFAULT, k, albumPrompt(title, artist), null, 200, 0.0, true, false)
                            val j = parseJson(r.text)
                            if (j != null) { album = j.optString("album").trim(); albumLat = j.optString("album_latin").trim(); break }
                        } catch (e: ApiErr) { if (e.code != 429) break } catch (_: Throwable) { break }
                    }
                } finally { try { Api.readTimeoutOverride.remove() } catch (_: Throwable) {} }

                forgetPart(t.id)
                onDone(SongMatch(title, artist, album, minOf(artistConf, titleConf), titleLat, artistLat, 1, albumLat), "")
            } catch (e: Throwable) { LogStore.err("fastid", e); onDone(null, "فشل: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }
}
