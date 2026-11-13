package com.tttt.subtitler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * (v696) جلب ملف الترجمة أونلاين من غير API key: بيدوّر باسم الفيديو/الفولدر في كذا موقع مع بعض (OpenSubtitles · Podnapisi · SubtitleCat)،
 * بيرتّب النتائج (اللغة · العنوان · الحلقة · النسخة)، بيحمّل، بيفك الضغط (ZIP / GZ)، ولو الملف باك فيه كل الحلقات بيختار حلقة الفيديو
 * وبيخزّن الباك كله في كاش جهاز (الحلقات اللي بعدها بتتلاقى من غير نت).
 * كل المواقع best-effort: لو موقع قفل أو غيّر شكله بيتخطّى والباقي يكمّل.
 */
object SubOnline {
    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val SUB_EXT = setOf("srt", "ass", "ssa", "vtt")

    class Hit(
        val provider: String, val id: String, val name: String, val lang: String, val dl: String,
        val downloads: Int, val season: Int, val episode: Int, val pack: Boolean, val year: Int = -1
    ) {
        var score = 0.0
        fun label(): String = "[$provider] " + name.take(70)
    }

    class Entry(val name: String, val bytes: ByteArray)
    class Got(val fileName: String, val text: String, val cues: List<SubFileTr.Cue>, val lang: String, val source: String, val packSize: Int)
    class Net(val code: Int, val body: ByteArray)

    /** جلسة بحث: النتايج مترتبة + مؤشر «النتيجة اللي بعدها» */
    class Session(val info: SubName.Info, val langs: List<String>, val hits: List<Hit>) {
        @Volatile var next = 0
        @Volatile var last: Got? = null
        fun remaining(): Int = hits.size - next
    }

    private fun log(m: String) { try { LogStore.add("🌍 ترجمة أونلاين: $m") } catch (_: Throwable) {} }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ===== HTTP =====
    private fun readLimited(st: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val n = st.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("الملف كبير زيادة")
        }
        return out.toByteArray()
    }

    /** GET مع متابعة التحويلات يدويًا (http↔https) */
    fun http(url0: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 12_000_000, timeoutMs: Int = 15000): Net {
        var url = url0
        for (hop in 0..6) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs + 10000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept-Language", "en,ar;q=0.8")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return Net(code, ByteArray(0))
                    url = URL(URL(url), loc).toString()
                } else {
                    val st = if (code < 400) c.inputStream else c.errorStream
                    val body = if (st == null) ByteArray(0) else st.use { readLimited(it, maxBytes) }
                    return Net(code, body)
                }
            } finally { try { c.disconnect() } catch (_: Throwable) {} }
        }
        return Net(-2, ByteArray(0))
    }

    // ===== فك الضغط + ترميز =====
    private fun looksSubtitle(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 65536), Charsets.ISO_8859_1)
        return head.contains("-->") || head.contains("[Events]") || head.contains("Dialogue:") || head.startsWith("WEBVTT")
    }

    @Volatile var lastUnpackError = ""

    /** بايتات (ZIP / GZ / نص) ← ملفات ترجمة. RAR و7z مش مدعومين */
    fun unpack(data: ByteArray, nameHint: String, depth: Int = 0): List<Entry> {
        if (data.size < 4) return emptyList()
        val b0 = data[0].toInt() and 0xFF; val b1 = data[1].toInt() and 0xFF
        val out = ArrayList<Entry>()
        if (b0 == 0x50 && b1 == 0x4B) {   // ZIP
            try {
                ZipInputStream(ByteArrayInputStream(data)).use { z ->
                    var e = z.nextEntry
                    var n = 0
                    while (e != null && n < 800) {
                        if (!e.isDirectory) {
                            val nm = e.name.substringAfterLast('/').substringAfterLast('\\')
                            val ext = nm.substringAfterLast('.', "").lowercase()
                            val nested = (ext == "zip" || ext == "gz") && depth < 2
                            if (ext in SUB_EXT || nested) {
                                val bytes = readLimited(z, 6_000_000)
                                if (ext in SUB_EXT) { if (looksSubtitle(bytes)) out.add(Entry(nm, bytes)) } else out.addAll(unpack(bytes, nm, depth + 1))
                                n++
                            }
                        }
                        z.closeEntry(); e = z.nextEntry
                    }
                }
            } catch (e: Throwable) { log("فك ZIP فشل: " + (e.message ?: "").take(60)) }
            if (out.isEmpty()) lastUnpackError = "مفيش ملفات ترجمة جوه الـZIP"
            return out
        }
        if (b0 == 0x1F && b1 == 0x8B) {   // GZIP
            return try {
                val bytes = GZIPInputStream(ByteArrayInputStream(data)).use { readLimited(it, 6_000_000) }
                unpack(bytes, nameHint.removeSuffix(".gz").ifBlank { "sub.srt" }, depth + 1)
            } catch (e: Throwable) { log("فك GZ فشل: " + (e.message ?: "").take(60)); emptyList() }
        }
        if ((b0 == 0x52 && b1 == 0x61) || (b0 == 0x37 && b1 == 0x7A)) {   // RAR / 7z
            lastUnpackError = "الملف RAR أو 7z والتطبيق بيفك ZIP و SRT بس — نزّل نسخة ZIP أو SRT"
            return emptyList()
        }
        if (looksSubtitle(data)) {
            var nm = nameHint.ifBlank { "sub.srt" }
            if (nm.substringAfterLast('.', "").lowercase() !in SUB_EXT) nm += ".srt"
            return listOf(Entry(nm, data))
        }
        lastUnpackError = "التحميل ده مش ملف ترجمة (ممكن صفحة ويب)"
        return emptyList()
    }

    private fun arRatio(t: String): Double {
        var l = 0; var a = 0
        for (ch in t) { if (ch.isLetter()) { l++; if (ch.code in 0x0600..0x06FF) a++ } }
        return if (l == 0) 0.0 else a.toDouble() / l
    }

    /** ترميز ذكي: UTF-8 لو سليم، وإلا windows-1256 (عربي قديم) أو 1252 حسب اللغة المتوقعة */
    fun decodeSmart(b: ByteArray, langHint: String): String {
        if (b.size >= 2) {
            val x0 = b[0].toInt() and 0xFF; val x1 = b[1].toInt() and 0xFF
            if ((x0 == 0xFF && x1 == 0xFE) || (x0 == 0xFE && x1 == 0xFF)) return SubFileTr.decode(b)
        }
        if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0xEF && (b[1].toInt() and 0xFF) == 0xBB && (b[2].toInt() and 0xFF) == 0xBF) return String(b, 3, b.size - 3, Charsets.UTF_8)
        val strict = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
        } catch (_: Throwable) { null }
        if (strict != null) return strict
        val ar = try { String(b, Charset.forName("windows-1256")) } catch (_: Throwable) { null }
        val lat = try { String(b, Charset.forName("windows-1252")) } catch (_: Throwable) { String(b, Charsets.ISO_8859_1) }
        if (ar != null && (langHint == "ar" || arRatio(ar) > 0.5)) return ar
        return lat
    }

    // ===== مزوّدين =====
    private val L3 = mapOf("ar" to "ara", "en" to "eng", "fr" to "fre", "tr" to "tur", "es" to "spa", "de" to "ger", "it" to "ita", "ru" to "rus", "pt" to "por", "ja" to "jpn", "ko" to "kor", "fa" to "per", "hi" to "hin", "zh" to "chi")
    private val PACK_WORDS = Regex("(?i)(complete|season|pack|all ?episodes|batch|الموسم|كامل)")

    private fun qList(info: SubName.Info): List<String> {
        val l = LinkedHashSet<String>()
        if (info.weakEp && info.titleAlt.isNotBlank()) l.add(info.titleAlt)
        if (info.title.isNotBlank()) l.add(info.title)
        if (l.isEmpty() && info.raw.isNotBlank()) l.add(info.raw)
        return l.toList()
    }
    private fun strongSE(info: SubName.Info) = info.episode >= 0 && info.season >= 0 && !info.weakEp

    /** OpenSubtitles (الـREST القديم من غير مفتاح) */
    private fun osAll(info: SubName.Info, langs: List<String>): List<Hit> {
        val out = ArrayList<Hit>()
        val lg = langs.mapNotNull { L3[it] }.joinToString(",")
        for (q in qList(info).take(2)) {
            val parts = ArrayList<String>()
            if (strongSE(info)) parts.add("episode-" + info.episode)
            parts.add("query-" + enc(q.lowercase()))
            if (strongSE(info)) parts.add("season-" + info.season)
            if (lg.isNotEmpty()) parts.add("sublanguageid-$lg")
            val r = http("https://rest.opensubtitles.org/search/" + parts.joinToString("/"), mapOf("X-User-Agent" to "TemporaryUserAgent", "Accept" to "application/json"))
            if (r.code != 200) { log("OpenSubtitles رجّع ${r.code}"); continue }
            val arr = try { JSONArray(String(r.body, Charsets.UTF_8)) } catch (_: Throwable) { continue }
            for (i in 0 until minOf(arr.length(), 100)) {
                val o = arr.optJSONObject(i) ?: continue
                val link = o.optString("SubDownloadLink")
                if (link.isBlank()) continue
                if (o.optString("SubFormat", "srt").lowercase() !in SUB_EXT) continue
                val kind = o.optString("MovieKind")
                val se = o.optString("SeriesSeason").toIntOrNull() ?: -1
                val ep = o.optString("SeriesEpisode").toIntOrNull() ?: -1
                val nm = o.optString("SubFileName").ifBlank { o.optString("MovieReleaseName") }.ifBlank { o.optString("MovieName") }
                val isEp = kind == "episode" && ep > 0
                out.add(Hit("OpenSubtitles", o.optString("IDSubtitleFile").ifBlank { link }, nm, o.optString("ISO639").lowercase(), link,
                    o.optString("SubDownloadsCnt").toIntOrNull() ?: 0, if (isEp && se > 0) se else -1, if (isEp) ep else -1, false, o.optString("MovieYear").toIntOrNull() ?: -1))
            }
            if (out.size >= 5) break
        }
        return out
    }

    /** Podnapisi (JSON لو رجّع، وإلا استخراج من الصفحة) */
    private fun pnAll(info: SubName.Info, langs: List<String>): List<Hit> {
        val out = ArrayList<Hit>()
        for (q in qList(info).take(2)) {
            val p = StringBuilder("keywords=" + enc(q))
            for (l in langs) p.append("&language=").append(l)
            if (strongSE(info)) p.append("&seasons=").append(info.season).append("&episodes=").append(info.episode).append("&movie_type=tv-series")
            val r = http("https://www.podnapisi.net/subtitles/search/advanced?$p", mapOf("Accept" to "application/json"))
            if (r.code != 200) { log("Podnapisi رجّع ${r.code}"); continue }
            val txt = String(r.body, Charsets.UTF_8).trim()
            if (txt.startsWith("{")) {
                var data: JSONArray? = null
                try { data = JSONObject(txt).optJSONArray("data") } catch (_: Throwable) {}
                if (data == null) continue
                for (i in 0 until minOf(data.length(), 60)) {
                    val o = data.optJSONObject(i) ?: continue
                    val id = o.optString("id"); if (id.isBlank()) continue
                    val rel = o.optJSONArray("releases")?.optString(0).orEmpty()
                    val mv = o.optJSONObject("movie")
                    val nm = rel.ifBlank { o.optString("title") }.ifBlank { mv?.optString("title").orEmpty() }.ifBlank { q }
                    val pn = SubName.parse(nm)
                    val se = mv?.optInt("season", -1)?.takeIf { it > 0 } ?: pn.season
                    val ep = mv?.optInt("episode", -1)?.takeIf { it > 0 } ?: pn.episode
                    val dl = o.optJSONObject("stats")?.optInt("downloads", 0) ?: o.optInt("downloads", 0)
                    out.add(Hit("Podnapisi", id, nm, o.optString("language").lowercase(), "https://www.podnapisi.net/subtitles/$id/download", dl, se, ep, ep < 0 && PACK_WORDS.containsMatchIn(nm), o.optInt("year", -1)))
                }
            } else {
                val seen = HashSet<String>()
                for (m in Regex("/subtitles/([A-Za-z0-9]{3,14})/download").findAll(txt)) {
                    val id = m.groupValues[1]
                    if (seen.add(id) && seen.size <= 20) out.add(Hit("Podnapisi", id, q, "", "https://www.podnapisi.net/subtitles/$id/download", 0, -1, -1, false))
                }
            }
            if (out.size >= 5) break
        }
        return out
    }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]*>"), " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()

    /** SubtitleCat: صفحة البحث ← صفحات الملفات (اللينك الفعلي بيتحدد وقت التحميل حسب اللغة) */
    private fun scAll(info: SubName.Info, langs: List<String>): List<Hit> {
        val out = ArrayList<Hit>()
        val qs = ArrayList<String>()
        val base = qList(info).firstOrNull() ?: return out
        if (strongSE(info)) qs.add(base + " " + String.format(java.util.Locale.US, "S%02dE%02d", info.season, info.episode))
        qs.add(base)
        val seen = HashSet<String>()
        for (q in qs.take(2)) {
            val r = http("https://www.subtitlecat.com/index.php?search=" + enc(q))
            if (r.code != 200) { log("SubtitleCat رجّع ${r.code}"); continue }
            val html = String(r.body, Charsets.UTF_8)
            for (m in Regex("<a[^>]+href=\"(/?subs/\\d+/[^\"]+?\\.html)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
                val href = m.groupValues[1].trimStart('/')
                if (!seen.add(href) || seen.size > 14) continue
                val nm = stripTags(m.groupValues[2]).ifBlank { href.substringAfterLast('/').removeSuffix(".html") }
                val pn = SubName.parse(nm)
                out.add(Hit("SubtitleCat", href, nm, "", "https://www.subtitlecat.com/$href", 0, pn.season, pn.episode, pn.episode < 0 && PACK_WORDS.containsMatchIn(nm), pn.year))
            }
            if (out.size >= 6) break
        }
        return out
    }

    private fun scResolve(h: Hit, langs: List<String>): String? {
        val r = http(h.dl)
        if (r.code != 200) return null
        val html = String(r.body, Charsets.UTF_8)
        val links = Regex("href=\"([^\"]+?-([a-z]{2,3})\\.srt)\"", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] to it.groupValues[2].lowercase() }.toList()
        for (l in langs) {
            links.firstOrNull { it.second == l }?.let { return URL(URL(h.dl), it.first).toString() }
        }
        return links.firstOrNull()?.let { URL(URL(h.dl), it.first).toString() }
    }

    // ===== ترتيب النتايج =====
    fun score(h: Hit, info: SubName.Info, langs: List<String>): Double {
        var s = 0.0
        val li = if (h.lang.isEmpty()) -1 else langs.indexOf(h.lang)
        s += when {
            li == 0 -> 40.0
            li > 0 -> 40.0 - 15.0 * li
            h.lang.isEmpty() -> 15.0
            else -> -50.0
        }
        val q = if (info.weakEp && info.titleAlt.isNotBlank()) info.titleAlt else info.title
        s += 45.0 * maxOf(SubName.similarity(q, h.name), SubName.similarity(info.title, h.name))
        if (info.episode >= 0) {
            val seOk = info.season < 0 || h.season < 0 || h.season == info.season
            if (h.episode >= 0) s += if (h.episode == info.episode && seOk) 45.0 else -70.0
            else if (h.pack) s += 12.0 else s -= 6.0
        } else if (h.episode >= 0) s -= 25.0
        if (info.year > 0 && h.year > 0) s += if (info.year == h.year) 10.0 else -12.0
        s += 15.0 * SubName.releaseSim(info.raw, h.name)
        s += 3.0 * Math.log10((h.downloads + 1).toDouble())
        return s
    }

    /** بحث في كل المواقع بالتوازي ← نتايج مترتبة. فاضية = ولا موقع رجّع حاجة */
    fun search(info: SubName.Info, langs: List<String>, report: (String) -> Unit): List<Hit> {
        report("🔎 بدوّر على «" + info.label() + "»…")
        val all = java.util.concurrent.CopyOnWriteArrayList<Hit>()
        val jobs = ArrayList<Thread>()
        fun job(nm: String, f: () -> List<Hit>) {
            jobs.add(Thread {
                try { val l = f(); all.addAll(l); log("$nm: ${l.size} نتيجة") } catch (e: Throwable) { log("$nm فشل: " + (e.message ?: e.javaClass.simpleName).take(70)) }
            }.also { it.isDaemon = true; it.start() })
        }
        job("OpenSubtitles") { osAll(info, langs) }
        job("Podnapisi") { pnAll(info, langs) }
        job("SubtitleCat") { scAll(info, langs) }
        for (t in jobs) { try { t.join(35000L) } catch (_: InterruptedException) {} }
        val seen = HashSet<String>()
        val list = all.filter { seen.add(it.provider + "|" + it.id) }
        for (h in list) h.score = score(h, info, langs)
        val sorted = list.sortedByDescending { it.score }
        // نتايج بحلقة غلط (-70) من غير أي فايدة بتتشال
        return sorted.filter { it.score > -20.0 }.take(30)
    }

    // ===== تحميل + اختيار =====
    fun fetch(ctx: Context?, hit: Hit, info: SubName.Info, langs: List<String>, report: (String) -> Unit): Got? {
        report("⬇ بنزّل: " + hit.label())
        var url = hit.dl
        if (hit.provider == "SubtitleCat") { url = scResolve(hit, langs) ?: run { log("SubtitleCat: مفيش لينك SRT في الصفحة"); return null } }
        val r = http(url, if (hit.provider == "OpenSubtitles") mapOf("X-User-Agent" to "TemporaryUserAgent") else emptyMap())
        if (r.code != 200 || r.body.isEmpty()) { log("التحميل رجّع ${r.code}"); return null }
        return ingest(ctx, r.body, hit.name, info, langs, hit.lang, hit.provider + " · " + hit.name.take(60), report)
    }

    private class Dec(val e: Entry, val text: String, val cues: List<SubFileTr.Cue>, val lang: String, val se: Int, val ep: Int)

    private fun langRank(l: String, langs: List<String>): Int { val i = langs.indexOf(l); return if (i < 0) 99 else i }

    /**
     * ملف محمّل (ترجمة / ZIP / GZ) ← الترجمة الصح لفيديو info. الباك (أكتر من ملف) بيتخزّن كله في الكاش، والحلقة المطلوبة بتتختار.
     * null = مفيش ترجمة صالحة أو الحلقة مش موجودة في الباك (السبب في report).
     */
    fun ingest(ctx: Context?, data: ByteArray, nameHint: String, info: SubName.Info, langs: List<String>, langHint: String, source: String, report: (String) -> Unit): Got? {
        lastUnpackError = ""
        val entries = unpack(data, nameHint)
        if (entries.isEmpty()) { report("⚠ " + lastUnpackError.ifBlank { "مفيش ملف ترجمة جوه التحميل" }); return null }
        val decs = ArrayList<Dec>()
        for (e in entries) {
            val lg0 = SubName.langOfName(e.name).ifEmpty { langHint }
            val txt = decodeSmart(e.bytes, lg0)
            val cues = try { SubFileTr.parse(txt) } catch (_: Throwable) { emptyList() }
            if (cues.size < 5) continue
            val lg = if (SubFileTr.mostlyArabic(cues)) "ar" else lg0.ifEmpty { "en" }
            val p = SubName.parse(e.name)
            decs.add(Dec(e, txt, cues, lg, p.season, p.episode))
        }
        if (decs.isEmpty()) { report("⚠ الملف مش ترجمة صالحة"); return null }
        if (ctx != null && (decs.size > 1 || info.episode >= 0)) cachePut(ctx, info, decs.map { Triple(it.e.name, it.text, Triple(it.lang, it.se, it.ep)) })
        var pool: List<Dec> = decs
        if (info.episode >= 0 && decs.size > 1) {
            val idx = SubName.matchEpisodes(decs.map { it.e.name }, info.season, info.episode)
            if (idx.isEmpty()) { report("⚠ الباك (${decs.size} ملف) مفيهوش الحلقة ${info.episode}"); return null }
            pool = idx.map { decs[it] }
        } else if (info.episode >= 0 && decs.size == 1) {
            val d = decs[0]
            if (d.ep >= 0 && d.ep != info.episode && !info.weakEp) { report("⚠ الملف ده لحلقة ${d.ep} مش ${info.episode}"); return null }
        }
        val best = pool.sortedWith(compareBy<Dec>({ langRank(it.lang, langs) }, { -it.cues.size })).first()
        return Got(best.e.name, best.text, best.cues, best.lang, source, decs.size)
    }

    // ===== كاش الباكات (أوفلاين) =====
    private fun cacheRoot(ctx: Context) = File(ctx.filesDir, "subpacks").also { it.mkdirs() }
    private fun cacheDir(ctx: Context, info: SubName.Info, create: Boolean): File? {
        val d = File(cacheRoot(ctx), SubName.slug(info.title.ifBlank { info.raw }))
        if (create) d.mkdirs()
        return if (d.isDirectory) d else null
    }
    private val CNAME = Regex("^([a-z]{0,3})_s(-?\\d+)_e(-?\\d+)_(\\d+)\\.(srt|ass|ssa|vtt)$")

    /** (اسم، نص، (لغة، موسم، حلقة)) */
    private fun cachePut(ctx: Context, info: SubName.Info, items: List<Triple<String, String, Triple<String, Int, Int>>>) {
        try {
            val d = cacheDir(ctx, info, true) ?: return
            var n = 0
            for ((nm, txt, meta) in items) {
                val ext = nm.substringAfterLast('.', "srt").lowercase().let { if (it in SUB_EXT) it else "srt" }
                val ep = if (meta.third >= 0) meta.third else if (items.size == 1) info.episode else -1
                val se = if (meta.second >= 0) meta.second else info.season
                val f = File(d, meta.first.take(3).ifEmpty { "xx" } + "_s" + se + "_e" + ep + "_" + n + "." + ext)
                if (!f.exists()) f.writeText(txt, Charsets.UTF_8)
                n++
            }
            d.setLastModified(System.currentTimeMillis())
            val dirs = cacheRoot(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() } ?: return
            if (dirs.size > 60) for (x in dirs.take(dirs.size - 60)) { try { x.deleteRecursively() } catch (_: Throwable) {} }
        } catch (e: Throwable) { log("الكاش فشل: " + (e.message ?: "").take(60)) }
    }

    /** ترجمة محفوظة من باك سابق (من غير نت). null = مفيش */
    fun cacheFind(ctx: Context, info: SubName.Info, langs: List<String>): Got? {
        try {
            val d = cacheDir(ctx, info, false) ?: return null
            val files = d.listFiles()?.filter { it.isFile } ?: return null
            class C(val f: File, val lang: String, val se: Int, val ep: Int)
            val cs = files.mapNotNull { f ->
                val m = CNAME.find(f.name) ?: return@mapNotNull null
                C(f, m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.filter { c ->
                if (info.episode >= 0) c.ep == info.episode && (info.season < 0 || c.se < 0 || c.se == info.season) else c.ep < 0
            }
            val best = cs.sortedWith(compareBy<C>({ langRank(it.lang, langs) }, { it.f.name })).firstOrNull() ?: return null
            val txt = best.f.readText(Charsets.UTF_8)
            val cues = SubFileTr.parse(txt)
            if (cues.size < 5) return null
            return Got(best.f.name, txt, cues, best.lang.ifEmpty { if (SubFileTr.mostlyArabic(cues)) "ar" else "en" }, "كاش الجهاز", cs.size)
        } catch (e: Throwable) { log("قراءة الكاش فشلت: " + (e.message ?: "").take(60)); return null }
    }

    /** جلسة بحث كاملة + أول ترجمة تنزل. null في got = مفيش (والجلسة فيها نتايج تتجرّب بـ next) */
    fun fetchNext(ctx: Context?, s: Session, report: (String) -> Unit, maxTries: Int = 5): Got? {
        var tries = 0
        while (s.next < s.hits.size && tries < maxTries) {
            val h = s.hits[s.next++]
            tries++
            val g = try { fetch(ctx, h, s.info, s.langs, report) } catch (e: Throwable) { log("فشل تحميل ${h.label()}: " + (e.message ?: e.javaClass.simpleName).take(70)); null }
            if (g != null) { s.last = g; return g }
        }
        return null
    }

    /** اللغات المفضّلة من الإعدادات (الافتراضي عربي ثم إنجليزي) */
    fun prefLangs(): List<String> {
        val l = try { Cfg.str("subnet_langs", "ar,en") } catch (_: Throwable) { "ar,en" }
        val x = l.split(",", " ", "،").map { it.trim().lowercase() }.filter { it.length in 2..3 }
        return if (x.isEmpty()) listOf("ar", "en") else x
    }
}
