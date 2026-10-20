package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** نتيجة التعرف على أغنية */
class SongMatch(val title: String, val artist: String, val album: String, val conf: Int = 100, val tLat: String = "", val aLat: String = "", val votes: Int = 1, val albLat: String = "")

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
    fun saveOverride(id: Long, m: SongMatch) {
        Cfg.put("mname_$id", m.title + "\u0001" + m.artist); Cfg.put("malbum_$id", m.album.trim())
        // (v298) الصيغة التانية للاسم (إنجليزي لو الأصلي عربي): بتتحفظ جنب الاسم عشان الكلمات تدوّر بكل لغة لوحدها
        Cfg.put("mlat_$id", if (m.tLat.isBlank() && m.aLat.isBlank()) "" else m.tLat + "\u0001" + m.aLat)
        Cfg.put("malbumlat_$id", m.albLat.trim())   // (v331) اسم الألبوم بالصيغة التانية (إنجليزي لو الأصلي عربي) — بيتدوّر بيه على الكلمات
    }
    /** (v331) الألبوم بالصيغة التانية — "" لو مش معروف */
    fun albumLat(id: Long): String = Cfg.str("malbumlat_$id", "")
    /** (v300) الصيغة الإنجليزية بس (من غير ما نلمس الاسم) — لو اتجابت بعد حفظ الاسم */
    fun saveAlt(id: Long, t: String, a: String) { Cfg.put("mlat_$id", if (t.isBlank() && a.isBlank()) "" else t.trim() + "\u0001" + a.trim()) }
    /** (v306) مفاتيح الطلبات النصية الصغيرة (ألبوم/تصحيح/صيغة إنجليزي): مفتاح الصور/الوضع البصري الأول (لوحده)، وبعده الأساسية — فمفاتيح السماع الأساسية بتفضل فاضية للمقاطع */
    fun textKeys(): List<String> = (Cfg.keys("viskeys") + Cfg.allMainKeys()).distinct()
    fun hasArabic(s: String) = Regex("[\\u0600-\\u06FF]").containsMatchIn(s)

    /**
     * (v300) اسم الأغنية والمغني بحروف إنجليزية (الكتابة الرسمية في Spotify/YouTube Music لو معروفة، وإلا نقل حروف) —
     * عشان LRCLIB والبوستر يلاقوها. طلب نصي صغير لجيميناي (من غير صوت). على خيط خلفية؛ بيرجّع ("","") لو فشل.
     */
    fun latinize(title: String, artist: String, onDone: (String, String) -> Unit) {
        Thread {
            var lt = ""; var la = ""
            try {
                val keys = textKeys()
                val p = "The song title and the singer name below are written in Arabic. Write the SAME song and the SAME singer in English letters exactly the way they are officially written on Spotify / Apple Music / YouTube Music if you know that spelling; otherwise use a standard transliteration (do NOT translate the meaning, and do NOT change who the singer is). " +
                    "If a field is empty or already in English letters, return it as it is.\nTitle: \"$title\"\nSinger: \"$artist\"\n" +
                    "Output one JSON object only, no markdown: {\"title_latin\":\"\",\"artist_latin\":\"\"}"
                for (k in keys.take(3)) {
                    try {
                        val r = Api.generate(Models.DEFAULT, k, p, null, 600, 0.0, true, false)
                        val j = parseJson(r.text)
                        if (j != null) {
                            lt = j.optString("title_latin").trim(); la = j.optString("artist_latin").trim()
                            if (lt.isNotEmpty() || la.isNotEmpty()) break
                        }
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) { LogStore.err("songid-latin", e) }
            onDone(lt, la)
        }.apply { isDaemon = true }.start()
    }

    /**
     * (v305) تصحيح/إكمال المعلومات بطلب نصي لجيميناي: الحقول اللي اليوزر قال إنها صح (مش معلّمة غلط) هي الحقيقة المرجعية،
     * والحقول المعلّمة غلط (أو الألبوم الفاضي) بتتدوّر من جديد على أساسها. بيرجّع الاسم والمغني والألبوم + سنة + الصيغة الإنجليزي.
     * onDone(null, سبب) لو فشل أو ما اتأكدش.
     */
    fun repair(m: SongMatch, wrongTitle: Boolean, wrongArtist: Boolean, wrongAlbum: Boolean, fileName: String, durSec: Double, onDone: (SongMatch?, String) -> Unit) {
        Thread {
            var out: SongMatch? = null; var msg = ""
            try {
                val keys = textKeys()
                if (keys.isEmpty()) { onDone(null, "مفيش مفتاح جيميناي"); return@Thread }
                fun fld(label: String, v: String, wrong: Boolean) =
                    if (v.isBlank()) "$label: (unknown — find it)\n" else if (wrong) "$label: \"$v\"  ← WRONG, reported by the user; never return this (or a trivial spelling variant) again\n" else "$label: \"$v\"  ← CORRECT (verified by the user — treat as ground truth)\n"
                val p = buildString {
                    append("You are a music metadata expert (Arabic — Egyptian, Gulf, Levantine, Maghreb, classical tarab — and international music). The metadata of ONE song file is partly wrong. ")
                    append("Fields marked CORRECT are ground truth: build on them. Replace the fields marked WRONG or unknown with the real values of the SAME recording.\n\n")
                    append(fld("Song title", m.title, wrongTitle)); append(fld("Performer (lead singer / band)", m.artist, wrongArtist)); append(fld("Album", m.album, wrongAlbum))
                    if (fileName.isNotBlank()) append("File name (weak hint, often wrong): \"$fileName\"\n")
                    if (durSec > 0) append("Track duration: about ${Math.round(durSec)} seconds\n")
                    append("\nRULES:\n- Use Google Search to verify. The album is the ORIGINAL official release the song first appeared on (studio album / EP / official single title), NOT a compilation, greatest-hits, playlist or YouTube channel. If the song was only released as a single, the single's title is the album.\n")
                    append("- Written in the original language/script (Arabic songs in Arabic). Performer = the singer of THIS recording, not the composer or lyricist; many classic songs are re-sung by other singers, so keep the CORRECT performer given above.\n")
                    append("- Never invent: if below ~70% sure of a field, return \"\" for it.\n")
                    append("- *_latin = the same value written in English letters exactly as on Spotify / Apple Music if known, otherwise a standard transliteration.\n")
                    append("OUTPUT: one JSON object only, no markdown: {\"title\":\"\",\"artist\":\"\",\"album\":\"\",\"year\":\"\",\"title_latin\":\"\",\"artist_latin\":\"\",\"album_latin\":\"\",\"confidence\":0}")
                }
                var j: JSONObject? = null
                loop@ for (k in keys.take(3)) {
                    for (useSearch in booleanArrayOf(true, false)) {
                        try {
                            val r = Api.generate(Models.DEFAULT, k, p, null, 1500, 0.0, true, useSearch)
                            j = parseJson(r.text); if (j != null) break@loop
                        } catch (e: ApiErr) { msg = e.message ?: ""; if (e.code == 429) continue@loop }
                        catch (e: Throwable) { msg = e.message ?: "" }
                    }
                }
                if (j == null) { onDone(null, "جيميناي ما ردّش" + (if (msg.isNotBlank()) ": $msg" else "")); return@Thread }
                val jj: JSONObject = j
                fun g(k: String) = jj.optString(k).trim()
                var title = if (wrongTitle) g("title") else m.title
                var artist = if (wrongArtist) g("artist") else m.artist
                var album = if (wrongAlbum || m.album.isBlank()) g("album").ifBlank { if (wrongAlbum) "" else m.album } else m.album
                val conf = jj.optInt("confidence", 100)
                if ((wrongTitle && title.isEmpty()) || (wrongArtist && artist.isEmpty())) { onDone(null, "جيميناي مش متأكد من القيمة الصح"); return@Thread }
                if (conf in 1..49 && (wrongTitle || wrongArtist)) { onDone(null, "جيميناي مش متأكد (ثقته $conf%)"); return@Thread }
                if (title.isEmpty()) title = m.title
                out = SongMatch(title, artist, album, if (conf <= 0) 100 else conf, g("title_latin").ifBlank { m.tLat }, g("artist_latin").ifBlank { m.aLat }, 1, g("album_latin").ifBlank { m.albLat })
            } catch (e: Throwable) { LogStore.err("songid-repair", e); msg = e.message ?: "" }
            onDone(out, if (out == null) msg.ifBlank { "فشل" } else "")
        }.apply { isDaemon = true }.start()
    }

    /** (v298) الاسم/الفنان بالحروف اللاتينية (أو اللغة التانية) المحفوظين مع التعرّف — null لو مفيش */
    fun alt(id: Long): Pair<String, String>? {
        val v = Cfg.str("mlat_$id", ""); if (v.isBlank()) return null
        val p = v.split('\u0001'); val a = p[0].trim(); val b = p.getOrElse(1) { "" }.trim()
        return if (a.isEmpty() && b.isEmpty()) null else a to b
    }
    /** (v294) الألبوم المحفوظ مع الاسم (من التعرّف أو من اليوزر) — "" لو مش معروف */
    fun albumOf(id: Long): String = Cfg.str("malbum_$id", "")

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
    fun resetKnown(id: Long) { Cfg.put("mknown_$id", ""); Cfg.put("mverd_$id", ""); FastId.forgetPart(id) }
    fun autoOn() = Cfg.str("song_auto_id", "1") != "0"
    const val UNSURE = "جيميناي مش متأكد"
    /** (v270) اسم الملف/التاج مفيد؟ لو العنوان عام (Track 01 / AUD-2024 / أرقام بس) أو مفيش فنان نعتبره مش مفيد */
    /** (v294) الاسم/المغني باين إنه موقع أو لينك (www · .com · موقع …) مش اسم أغنية ولا مغني */
    fun looksLikeSite(s: String): Boolean {
        val x = s.lowercase()
        if (Regex("(www\\.|https?:|@[a-z0-9_.]{3,}|\\b[a-z0-9-]+\\.(com|net|org|info|biz|me|tv|co|io|fm|ws|cc|ru|xyz|mobi|app|site|online|top|vip|club|pro|ink|link|stream|download|eg|sa|ae|ir|ly|to|ml|tk)\\b)").containsMatchIn(x)) return true
        return Regex("(^|\\s)(موقع|تحميل|download|mp3|ألبوماتي|البوماتي|albumaty|rnbxclusive|mp3quran)(\\s|$)").containsMatchIn(x)
    }
    /** (v314) اسم الملف بعد شيل اسم الموقع/اللينك/الكلام الزيادة («Song - www.site.com [128kbps]» ← «Song») — تلميح ضعيف للتعرّف مش أكتر */
    fun cleanHint(raw: String): String {
        var x = raw.replace('_', ' ')
        x = x.replace(Regex("(?i)(https?://\\S+|www\\.\\S+|\\b[a-z0-9-]+\\.(com|net|org|info|biz|me|tv|co|io|fm|ws|cc|ru|xyz|mobi|app|site|online|top|vip|club|pro|ink|link|stream|download|eg|sa|ae|ir|ly|to|ml|tk)\\b)"), " ")
        x = x.replace(Regex("(?i)[\\[(]?\\b(\\d{2,3}\\s?kbps|mp3|m4a|hd|official audio|lyrics|تحميل|download)\\b[\\])]?"), " ")
        x = x.replace(Regex("[\\[\\]()]+"), " ").replace(Regex("\\s*[-–—|]\\s*$"), "").replace(Regex("^\\s*[-–—|]\\s*"), "").replace(Regex("\\s+"), " ").trim()
        return if (looksLikeSite(x) || x.length < 2 || x.all { it.isDigit() || it == ' ' || it == '-' || it == '.' }) "" else x
    }
    fun tagsLookGood(title: String, artist: String): Boolean {
        val t = title.replace('_', ' ').trim().lowercase()
        if (looksLikeSite(title) || looksLikeSite(artist)) return false
        if (artist.trim().lowercase().let { it == "<unknown>" || it == "unknown" || it == "غير معروف" }) return false
        if (artist.isBlank() || t.length < 2) return false
        if (artist.trim().lowercase() == title.trim().lowercase()) return false   // (v314) بعض المواقع بتكتب نفس الاسم في العنوان والفنان
        if (t.all { it.isDigit() || it == ' ' || it == '-' || it == '.' }) return false
        return !Regex("^(track|audio|aud|vid|video|record(ing)?|new recording|voice|whatsapp|untitled|unknown|مقطع|تسجيل)[ \\-]*\\d*").containsMatchIn(t)
    }

    /**
     * (v193) التعرف التلقائي: لو فيه توكن AudD بنجرّبه الأول؛ ولو مفيش توكن (أو ما اتعرفتش، أو اليوزر قال إن الاسم غلط)
     * بنبعت المقطع لجيميناي يقول اسم الأغنية والفنان. [avoid] = أسماء اتقال إنها غلط ("العنوان — الفنان").
     */
    fun auto(ctx: Context, t: Track, startSec: Double, avoid: List<String>, onDone: (SongMatch?, String) -> Unit) {
        if (token().isEmpty() || avoid.isNotEmpty()) { gemini(ctx, t, startSec, avoid, onDone); return }
        recognize(ctx, t, startSec) { m, msg -> if (m != null) onDone(m, msg) else gemini(ctx, t, startSec, avoid) { m2, msg2 -> onDone(m2, if (m2 == null) msg2.ifBlank { msg } else "") } }
    }

    /** (v267) جيميناي بيسمع جزء كبير (~3 دقايق) من المسار بدل 25 ثانية — كلمات أكتر = اسم وفنان أدق */
    private const val BIG_SEC = 30.0   // (v295) أي مقطع يتبعت لمفتاح ما يزيدش عن 30 ثانية
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
        append("INPUT: a " + (if (durSec >= 90) "LARGE excerpt (~$mins minutes)" else "short excerpt (~${durSec.toInt()} seconds)") + " of ONE audio track" + (if (captured) " captured from another app's playback (ignore ads, voiceovers and sound effects that are not part of the track)" else "") + ". " + (if (durSec >= 90) "Because it is long, you can hear many lyric lines / verses and the voice repeatedly — use all of it, not just the first seconds." else "It is only a part of the song (other parts are heard separately by other listeners): use every sung line, the melody, the arrangement and the voice to identify it.") + "\n\n")
        append("STEP 1 — LISTEN. Internally transcribe the most distinctive sung/spoken lines you can clearly hear (the hook/chorus and 2-3 other lines, in the original language). Put them in \"heard\" (max 300 characters, no invention).\n")
        append("STEP 2 — CLASSIFY: kind = song | quran | nasheed | speech | soundtrack | other.\n")
        append("STEP 3 — IDENTIFY by kind:\n")
        append("• song: \"title\" = the official released title of THIS song (original language/script — Arabic songs in Arabic, English in English). \"artist\" = the PERFORMER (lead singer / band), not the composer, lyricist, label, uploader or channel. If it is a cover, remix, live or duet version, name the performer you actually hear. Many songs — especially classic Arabic ones — have been re-sung by dozens of different singers: decide the performer ONLY from the voice, the arrangement and the recording quality you actually hear, never default to the original or most famous singer and never to the name in the file. Do not confuse songs that share a chorus or lyrics; match the specific melody, arrangement and voice.\n")
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
        append("- \"album\" = the ORIGINAL official album/EP (or the single's title) this song was first released on — NOT a compilation, greatest-hits or playlist. The album is used to find the cover art, so fill it whenever you are reasonably sure (≥70%); otherwise leave it \"\".\n")
        append("- \"confidence\" = your honest 0-100 certainty that title AND artist are both correct.\n")
        if (multi) append("- The user will choose from your list, so give up to 4 DISTINCT candidates (different songs / different performers), best first, each a real existing track with its own honest confidence. Never pad the list with guesses you do not believe in; one or two good candidates is fine.\n")
        else append("- Give exactly ONE candidate (the best), or an empty list if you are not sure.\n")
        append("\nOUTPUT: one JSON object only, no markdown, no commentary:\n")
        append("- \"title_latin\" / \"artist_latin\" = the same title / performer written in English letters (standard transliteration or the official English spelling), so answers written in different scripts can be matched.\n")
        append("{\"kind\":\"\",\"heard\":\"\",\"candidates\":[{\"title\":\"\",\"artist\":\"\",\"title_latin\":\"\",\"artist_latin\":\"\",\"album\":\"\",\"confidence\":0}]}")
    }

    private fun parseJson(txt: String): JSONObject? {
        val a = txt.indexOf('{'); val b = txt.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(txt.substring(a, b + 1)) } catch (_: Throwable) { null }
    }

    /** يسأل جيميناي (بحث جوجل الأول، وبدونه لو الموديل/المفتاح رفض) على كذا مفتاح. على خيط خلفية. بيرجّع (المرشحين بترتيب الأرجحية، رسالة الخطأ) */
    private fun askList(keys: List<String>, wav: ByteArray, durSec: Double, hintTitle: String, hintArtist: String, avoid: List<String>, captured: Boolean, maxKeys: Int, multi: Boolean): Pair<List<SongMatch>, String> {
        val model = Models.DEFAULT   // (v295) دايمًا gemini-flash-lite-latest للتعرّف
        Api.readTimeoutOverride.set(180000)
        var res: JSONObject? = null; var err = ""
        try {
            for (k in 0 until keys.size.coerceAtMost(maxKeys)) {
                val key = keys[k % keys.size]
                for (useSearch in booleanArrayOf(false)) {   // (v295) من غير بحث جوجل: هو سبب الـ429
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
            if (out.none { it.title.equals(title, true) && it.artist.equals(artist, true) }) out.add(SongMatch(title, artist, o.optString("album").trim(), conf, o.optString("title_latin").trim(), o.optString("artist_latin").trim()))
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


    // ===== (v270) تعرّف أوتوماتيك: الـ3 دقايق بتتقسم على المفاتيح بالتوازي، ولو مقطعين اتفقوا = الأغنية الصح =====
    private fun nk(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun sameTitle(a: SongMatch, b: SongMatch): Boolean {
        val x = nk(a.tLat.ifBlank { a.title }); val y = nk(b.tLat.ifBlank { b.title })
        if (x.isNotEmpty() && x == y) return true
        if (nk(a.title) == nk(b.title) && nk(a.title).isNotEmpty()) return true
        // (v306) عربي عند واحد وإنجليزي عند التاني: نقارن الصيغة اللاتينية بتاعة كل واحد بالأصل التاني
        if (a.tLat.isNotBlank() && nk(a.tLat) == nk(b.title) && nk(b.title).isNotEmpty()) return true
        if (b.tLat.isNotBlank() && nk(b.tLat) == nk(a.title) && nk(a.title).isNotEmpty()) return true
        return ArNorm.sim(a.tLat.ifBlank { a.title }, b.tLat.ifBlank { b.title }) >= 0.85 || ArNorm.sim(a.title, b.title) >= 0.85
    }
    private fun sameArtist(a: SongMatch, b: SongMatch): Boolean {
        if (a.artist.isBlank() || b.artist.isBlank()) return true   // فنان ناقص مايمنعش الاتفاق على الاسم
        val x = nk(a.aLat.ifBlank { a.artist }); val y = nk(b.aLat.ifBlank { b.artist })
        if (x == y || (x.length >= 4 && y.length >= 4 && (x.contains(y) || y.contains(x)))) return true
        return ArNorm.sim(a.aLat.ifBlank { a.artist }, b.aLat.ifBlank { b.artist }) >= 0.7 || ArNorm.sim(a.artist, b.artist) >= 0.7
    }

    /**
     * بيقسم النافذة الكبيرة (~3د) على عدد المفاتيح (كل مفتاح ياخد مقطع ~3د÷العدد) ويبعتهم في نفس الوقت.
     * بعدها بيجمّع الإجابات: نفس اسم الأغنية + نفس المغني (حتى لو واحد عربي والتاني إنجليزي) = اتفاق.
     * onDone(الأصح لو اتنين اتفقوا أو واحد واثق جدًا وإلا null، كل الخيارات مرتبة، رسالة خطأ). على خيط خلفية.
     */
    fun smart(ctx: Context, t: Track, startSec: Double, avoid: List<String>, onDone: (SongMatch?, List<SongMatch>, String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty()) { onDone(null, emptyList(), "مفيش مفتاح Gemini — ضيف واحد"); return@Thread }
                val n = keys.size.coerceIn(1, 6)
                val src = YtAudio.src(app, t)
                val slices = ArrayList<WavChunk>()
                try {
                    val total = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
                    // (v295: كل مقطع 30ث بالظبط أو أقل) (v293) كل مفتاح ياخد مقطع مختلف موزّع على الأغنية كلها بدل ما يسمعوا 3 دقايق ورا بعض:
                    // مفتاح واحد = ~60ث حوالين مكان التشغيل · 6 مفاتيح = 6 مقاطع × 30ث من أماكن مختلفة (مقدمة · مقاطع · كورس)
                    val len = BIG_SEC.coerceAtMost(total.coerceAtLeast(1.0))
                    fun one(a: Double): WavChunk? {
                        val a0 = a.coerceIn(0.0, (total - len).coerceAtLeast(0.0)); val b0 = minOf(a0 + len, total.coerceAtLeast(a0 + 1))
                        if (b0 - a0 < 4.0) return null
                        val w = try { src.wav(a0, b0) } catch (_: Throwable) { null }
                        return if (w != null && !w.silent) w else null
                    }
                    fun spread(): List<WavChunk> {
                        if (n == 1 || total <= len * n) return emptyList()
                        val from = total * 0.06; val span = (total * 0.94 - len - from).coerceAtLeast(0.0)
                        return (0 until n).mapNotNull { i -> one(from + span * i / (n - 1)) }
                    }
                    fun contiguous(w0: Pair<Double, Double>): List<WavChunk> {
                        // أغنية قصيرة: مقاطع متجاورة مختلفة، كل واحد ≤30ث (عدد المقاطع = اللي الأغنية تسمح بيه، بحد أقصى عدد المفاتيح)
                        val out = ArrayList<WavChunk>()
                        val cnt = ((total / len).toInt()).coerceIn(1, n)
                        for (i in 0 until cnt) {
                            val a = (w0.first + i * len).coerceAtMost((total - 4.0).coerceAtLeast(0.0)); val b = minOf(a + len, total)
                            if (b - a < 4.0) continue
                            val w = try { src.wav(a, b) } catch (_: Throwable) { null }
                            if (w != null && !w.silent) out.add(w)
                        }
                        return out
                    }
                    if (n == 1) { one(startSec - len / 2)?.let { slices.add(it) } }
                    else slices.addAll(spread().ifEmpty { contiguous(bigWindow(total, startSec)) })
                    if (slices.isEmpty()) slices.addAll(contiguous(bigWindow(total, 0.0)))   // كله صامت/قصير: الطريقة القديمة من أول المسار
                } finally { try { src.close() } catch (_: Throwable) {} }
                if (slices.isEmpty()) { onDone(null, emptyList(), "المقطع ده صامت — شغّل الأغنية لحد جزء فيه صوت وجرّب تاني"); return@Thread }

                val answers = java.util.Collections.synchronizedList(ArrayList<SongMatch>())
                val errs = java.util.Collections.synchronizedList(ArrayList<String>())
                val latch = java.util.concurrent.CountDownLatch(slices.size)
                slices.forEachIndexed { i, w ->
                    Thread {
                        try {
                            val rot = keys.drop(i % keys.size) + keys.take(i % keys.size)   // كل مقطع يبدأ بمفتاح مختلف وبعدها يجرّب اللي بعده لو وقع
                            val (list, e) = askList(rot, w.bytes, w.durSec, t.title, t.artist, avoid, false, 2, false)
                            if (e.isNotEmpty()) errs.add(e)
                            list.firstOrNull { it.conf >= 40 }?.let { answers.add(it) }
                        } catch (e: Throwable) { LogStore.err("songid-smart", e) } finally { latch.countDown() }
                    }.apply { isDaemon = true }.start()
                }
                latch.await(200, java.util.concurrent.TimeUnit.SECONDS)

                val bad = avoid.map { it.trim().lowercase() }
                val good = ArrayList(answers).filter { m -> val a = (m.title + " — " + m.artist).trim().lowercase(); a !in bad && m.title.trim().lowercase() !in bad }
                // تجميع الاتفاقات
                val groups = ArrayList<ArrayList<SongMatch>>()
                for (m in good) { val g = groups.firstOrNull { sameTitle(it[0], m) && sameArtist(it[0], m) }; if (g != null) g.add(m) else groups.add(arrayListOf(m)) }
                val reps = groups.map { g ->
                    val b = g.maxByOrNull { it.conf }!!
                    // لو أحسن إجابة مالهاش ألبوم وغيرها له، ناخده
                    val alb = b.album.ifBlank { g.firstOrNull { it.album.isNotBlank() }?.album ?: "" }
                    SongMatch(b.title, b.artist.ifBlank { g.firstOrNull { it.artist.isNotBlank() }?.artist ?: "" }, alb, b.conf, b.tLat, b.aLat, g.size)
                }.sortedWith(compareByDescending<SongMatch> { it.votes }.thenByDescending { it.conf })
                val top = reps.firstOrNull()
                val sure = top != null && (top.votes >= 2 || (slices.size == 1 && good.size == 1 && top.conf >= 85))   // لازم مقطعين يتفقوا (إلا لو مفتاح واحد بس وواثق جدًا) — الغلط أسوأ من عدم التغيير
                if (reps.isEmpty()) onDone(null, emptyList(), errs.firstOrNull()?.let { "جيميناي ما ردّش: $it" } ?: (UNSURE + " — جرّب مقطع تاني أو اكتب الاسم بإيدك"))
                else onDone(if (sure) top else null, reps, "")
            } catch (e: Throwable) { LogStore.err("songid-smart", e); onDone(null, emptyList(), "فشل: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }

    /** مقطع كبير حوالين startSec، وبيجرّب أول المسار لو طلع صامت. null لو مفيش صوت */
    private fun loadWindow(app: Context, t: Track, startSec: Double): WavChunk? {
        val src = YtAudio.src(app, t)
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
                val src = YtAudio.src(app, t)
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
