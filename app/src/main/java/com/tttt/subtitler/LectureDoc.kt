package com.tttt.subtitler

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * (v993) تصدير مذكرة المذاكرة — نفس منطق وضع المذاكرة في برنامج الترجمة (HTML):
 *  • خام محسّن (Word): تنظيم/ألوان/خريطة ذهنية بدون أي إضافة ولا صور.
 *  • محسّن + مُضاف (Word): + [[ADD]] أحمر بخط تحته + صناديق تحذير طبي [[MEDWARN]] + مصطلحات (*) بحاشية + صور Wikipedia + لقطات من الفيديو الأصلي.
 *  • صفحة HTML واحدة مستقلة (نفس تصميم الـHTML) · TXT · JSON.
 * المنطق (تحليل Gemini) في Lecture.analyze — هنا البناء والتصدير بس.
 */
object LectureDoc {
    class Img(val bytes: ByteArray, val ext: String, val title: String)
    class Frame(val bytes: ByteArray, val label: String, val ts: Double)
    class Media(val images: Map<String, Img>, val frames: Map<Int, Frame>, val audioOnly: Boolean = false)
    class V(val ts: Double, val what: String, val source: String, val term: String, val callouts: List<String>)

    /** الإشارات البصرية لقسم (visuals) + التوافق مع frameTimestamp القديم. حد أقصى 3 ومن غير تكرار قريب */
    private fun visualsOf(s: JSONObject): List<V> {
        val out = ArrayList<V>()
        for (v in objs(arr(s, "visuals"))) {
            val ts = v.optDouble("ts", -1.0)
            val src = if (v.optString("source").equals("wiki", true)) "wiki" else "frame"
            out.add(V(ts, v.optString("what").ifBlank { v.optString("phrase") }, src, v.optString("wikiTerm").trim(), strs(arr(v, "callouts"))))
        }
        if (out.isEmpty() && s.has("frameTimestamp") && !s.isNull("frameTimestamp") && s.optDouble("frameTimestamp", -1.0) >= 0.0)
            out.add(V(s.optDouble("frameTimestamp", 0.0), s.optString("frameLabel"), "frame", s.optString("imageSearchTerm").trim(), strs(arr(s, "frameCallouts"))))
        val res = ArrayList<V>()
        for (v in out) if (res.none { Math.abs(it.ts - v.ts) < 5.0 && it.source == v.source }) res.add(v)
        return res.take(3)
    }
    private fun frameKey(idx: Int, j: Int) = idx * 100 + j
    private fun framesOf(media: Media, idx: Int): List<Frame> = (0 until 3).mapNotNull { media.frames[frameKey(idx, it)] }

    /** مصطلحات صور Wikipedia للقسم: الصورة العامة + أي إشارة بصرية مصدرها wiki أو لقطتها مش متاحة */
    private fun termsOf(s: JSONObject, idx: Int, frames: Map<Int, Frame>?, audioOnly: Boolean): List<String> {
        val t = ArrayList<String>()
        s.optString("imageSearchTerm").trim().takeIf { it.isNotEmpty() }?.let { t.add(it) }
        visualsOf(s).forEachIndexed { j, v ->
            if (v.term.isNotEmpty() && (v.source == "wiki" || audioOnly || frames == null || frames[frameKey(idx, j)] == null)) t.add(v.term)
        }
        return t.distinct().take(4)
    }
    class Built(val name: String, val mime: String, val uri: Uri)

    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    // ===================== أدوات =====================
    private fun esc(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private val BAD_XML = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
    private fun xml(s: String?): String = BAD_XML.replace((s ?: ""), "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun arr(o: JSONObject, k: String): JSONArray = o.optJSONArray(k) ?: JSONArray()
    private fun objs(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun strs(a: JSONArray): List<String> = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    private fun obj(o: JSONObject, k: String): JSONObject? = o.optJSONObject(k)
    private fun fmt(sec: Double) = Lecture.fmtTime(sec)

    // ===================== علامات [[ADD]] [[TERM]] [[MEDWARN]] (نفس الـHTML) =====================
    private sealed class Tok {
        class Plain(val t: String) : Tok()
        class Add(val t: String) : Tok()
        class Term(val word: String, val def: String) : Tok()
    }
    private val MARK = Regex("\\[\\[ADD\\]\\]([\\s\\S]*?)\\[\\[/ADD\\]\\]|\\[\\[TERM:([^\\]]*)\\]\\]([\\s\\S]*?)\\[\\[/TERM\\]\\]")
    private val MEDW = Regex("\\[\\[MEDWARN\\]\\]([\\s\\S]*?)\\[\\[/MEDWARN\\]\\]")
    private val ADDRX = Regex("\\[\\[ADD\\]\\][\\s\\S]*?\\[\\[/ADD\\]\\]")
    private val TERMRX = Regex("\\[\\[TERM:[^\\]]*\\]\\]([\\s\\S]*?)\\[\\[/TERM\\]\\]")
    private val MEDADD = Regex("\\[\\[MEDWARN\\]\\]\\[\\[ADD\\]\\]([\\s\\S]*?)\\[\\[/ADD\\]\\]\\[\\[/MEDWARN\\]\\]")
    private val STRAY = Regex("\\[\\[/?(?:ADD|MEDWARN|TERM[^\\]]*)\\]\\]")

    private fun tokens(text: String, strip: Boolean): List<Tok> {
        var c = MEDW.replace(text) { it.groupValues[1] }
        if (strip) c = ADDRX.replace(c, "").replace(Regex("\\s{2,}"), " ").trim()
        val out = ArrayList<Tok>(); var last = 0
        for (m in MARK.findAll(c)) {
            if (m.range.first > last) out.add(Tok.Plain(STRAY.replace(c.substring(last, m.range.first), "")))
            if (m.groups[1] != null) out.add(Tok.Add(STRAY.replace(TERMRX.replace(m.groupValues[1]) { it.groupValues[1] }, "")))
            else out.add(Tok.Term(m.groupValues[3], m.groupValues[2].trim()))
            last = m.range.last + 1
        }
        if (last < c.length) out.add(Tok.Plain(STRAY.replace(c.substring(last), "")))
        return out
    }

    private fun medWarnings(sec: JSONObject): List<String> {
        val all = ArrayList<String>()
        fun scan(t: String) { for (m in MEDADD.findAll(t)) { val v = m.groupValues[1].trim(); if (v.isNotEmpty()) all.add(v) } }
        strs(arr(sec, "bullets")).forEach { scan(it) }
        sec.optString("detail").takeIf { it.isNotBlank() }?.let { scan(it) }
        return all
    }

    private val PALETTE = listOf("2E86C1" to "EAF2FA", "C0392B" to "FBEAEA", "27AE60" to "EAF7EF", "8E44AD" to "F3EAFA",
        "D68910" to "FDF3E3", "16A085" to "E5F7F3", "D53E8C" to "FCEAF3", "5D6D7E" to "ECF0F1")
    private val MM_FILL = listOf("E8F4FD", "FDEEE8", "EAF7EA", "F5EEFB", "FEF6DC", "E8F7F5", "FBE9F0", "EFF3E6")
    private val MM_LINE = listOf("3498DB", "E67E22", "27AE60", "9B59B6", "F1C40F", "16A085", "E91E8C", "7CB342")

    // ===================== صور Wikipedia =====================
    private fun http(url: String, max: Int = 6_000_000): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "JoyboyStudy/1.0 (lecture notes; Android)")
        try { if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }.takeIf { it.size <= max } } finally { c.disconnect() }
    } catch (_: Throwable) { null }

    private fun mediaDir(ctx: Context) = File(ctx.filesDir, "lectures/media").also { it.mkdirs() }
    private fun extOf(b: ByteArray): String = when {
        b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b.size > 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "gif"
        else -> "jpg"
    }

    /** أقرب مقالة Wikipedia للمصطلح ← صورتها الرئيسية (نفس الـHTML). null لو مفيش. */
    fun wikiImage(ctx: Context, term: String): Img? {
        val t = term.trim(); if (t.isEmpty()) return null
        val key = Integer.toHexString(t.lowercase().hashCode())
        val fb = File(mediaDir(ctx), "w_$key.bin"); val ft = File(mediaDir(ctx), "w_$key.txt")
        try { if (fb.exists() && ft.exists()) return Img(fb.readBytes(), extOf(fb.readBytes()), ft.readText()) } catch (_: Throwable) {}
        try {
            val q = URLEncoder.encode(t, "UTF-8")
            val s = http("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&format=json&srlimit=1")?.toString(Charsets.UTF_8) ?: return null
            val title = JSONObject(s).optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title") ?: return null
            if (title.isBlank()) return null
            val q2 = URLEncoder.encode(title, "UTF-8")
            val p = http("https://en.wikipedia.org/w/api.php?action=query&titles=$q2&prop=pageimages&format=json&pithumbsize=700")?.toString(Charsets.UTF_8) ?: return null
            val pages = JSONObject(p).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val k0 = pages.keys().asSequence().firstOrNull() ?: return null
            val thumb = pages.optJSONObject(k0)?.optJSONObject("thumbnail")?.optString("source") ?: return null
            if (thumb.isBlank()) return null
            val bytes = http(thumb) ?: return null
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
            if (bo.outWidth <= 0) return null
            try { fb.writeBytes(bytes); ft.writeText(title) } catch (_: Throwable) {}
            return Img(bytes, extOf(bytes), title)
        } catch (e: Throwable) { LogStore.err("LectureDoc:wiki", e); return null }
    }

    fun fetchImages(ctx: Context, an: JSONObject, frames: Map<Int, Frame>, audioOnly: Boolean, onProg: (Int, Int, String) -> Unit): Map<String, Img> {
        val terms = objs(arr(an, "sections")).withIndex().flatMap { (i, sec) -> termsOf(sec, i, frames, audioOnly) }.distinct()
        val out = LinkedHashMap<String, Img>()
        terms.forEachIndexed { i, t -> wikiImage(ctx, t)?.let { out[t] = it }; onProg(i + 1, terms.size, t) }
        return out
    }

    // ===================== لقطات من الفيديو الأصلي =====================
    private fun openRetriever(ctx: Context, d: Lecture.Doc): Pair<MediaMetadataRetriever, Boolean>? {
        if (d.kind == "audio" || d.kind == "rec") return null
        val r = MediaMetadataRetriever()
        try {
            if (d.link.isBlank()) { r.setDataSource(ctx, Uri.parse(d.uri)); return r to false }
            val yt = Links.ytId(d.link)
            if (yt != null) {
                val p = MtYt.pick(yt, 720) ?: run { r.release(); return null }
                r.setDataSource(p.url, hashMapOf("User-Agent" to p.ua, "Referer" to "https://www.youtube.com/")); return r to true
            }
            if (Sniff.isDirect(d.link)) {
                val h = HashMap<String, String>(); if (d.ua.isNotBlank()) h["User-Agent"] = d.ua; if (d.ref.isNotBlank()) h["Referer"] = d.ref
                r.setDataSource(d.link, h); return r to true
            }
            val rs = Lecture.resolveLink(ctx, d.link)
            r.setDataSource(rs.url, HashMap(rs.hdr)); return r to true
        } catch (e: Throwable) {
            LogStore.err("LectureDoc:retriever", e)
            try { r.release() } catch (_: Throwable) {}
            return null
        }
    }

    /** يرسم الأسهم/التسميات فوق اللقطة (نفس توزيع الـHTML تقريبًا) */
    private fun drawCallouts(src: Bitmap, callouts: List<String>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(out); val w = out.width.toFloat(); val h = out.height.toFloat()
        val pos = listOf(0.14f to 0.16f, 0.84f to 0.16f, 0.14f to 0.5f, 0.84f to 0.5f, 0.14f to 0.84f, 0.84f to 0.84f)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD400.toInt(); textSize = w / 64f; isFakeBoldText = true }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD400.toInt(); strokeWidth = w / 420f; style = Paint.Style.STROKE }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD400.toInt(); style = Paint.Style.FILL }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD1141414.toInt(); style = Paint.Style.FILL }
        callouts.take(6).forEachIndexed { i, label ->
            val (fx, fy) = pos[i % pos.size]
            val px = fx * w; val py = fy * h; val cx = w / 2f; val cy = h / 2f
            val tipX = cx + (px - cx) * 0.35f; val tipY = cy + (py - cy) * 0.35f
            cv.drawLine(px, py, tipX, tipY, line); cv.drawCircle(tipX, tipY, w / 250f, fill)
            val tw = txt.measureText(label); val pad = w / 100f; val bh = txt.textSize * 1.6f
            val boxX = if (fx < 0.5f) px - tw - 3 * pad else px - pad
            val l = boxX - pad; val r = l + tw + 2.5f * pad
            cv.drawRect(l, py - bh / 2, r, py + bh / 2, bg)
            cv.drawRect(l, py - bh / 2, r, py + bh / 2, line)
            val fm = txt.fontMetrics
            cv.drawText(label, boxX + pad / 2, py - (fm.ascent + fm.descent) / 2f, txt)
        }
        return out
    }

    fun captureFrames(ctx: Context, d: Lecture.Doc, an: JSONObject, onProg: (Int, Int, String) -> Unit): Map<Int, Frame> {
        val targets = ArrayList<Triple<Int, Int, V>>()
        objs(arr(an, "sections")).forEachIndexed { i, sec -> visualsOf(sec).forEachIndexed { j, v -> if (v.source == "frame" && v.ts >= 0.0) targets.add(Triple(i, j, v)) } }
        val todo = targets.take(40)
        if (todo.isEmpty()) return emptyMap()
        val (r, remote) = openRetriever(ctx, d) ?: return emptyMap()
        val out = LinkedHashMap<Int, Frame>()
        try {
            todo.forEachIndexed { n, (i, j, v) ->
                // المحاضر بيشاور وهو بيتكلم: بناخد الفريم بعد الجملة بثانية عشان الشاشة تكون استقرت على اللي بيشاور عليه
                val ts = if (d.durSec > 1.0) (v.ts + 1.0).coerceAtMost(d.durSec - 0.5) else v.ts
                try {
                    val opt = if (remote) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
                    var bmp = r.getFrameAtTime((ts * 1_000_000.0).toLong(), opt)
                    if (bmp != null) {
                        if (bmp.width > 1280) { val nh = (bmp.height * 1280f / bmp.width).toInt(); bmp = Bitmap.createScaledBitmap(bmp, 1280, nh, true) }
                        val fin = if (v.callouts.isNotEmpty()) drawCallouts(bmp, v.callouts) else bmp
                        val bo = ByteArrayOutputStream(); fin.compress(Bitmap.CompressFormat.JPEG, 88, bo)
                        out[frameKey(i, j)] = Frame(bo.toByteArray(), v.what, v.ts)
                    }
                } catch (e: Throwable) { LogStore.err("LectureDoc:frame", e) }
                onProg(n + 1, todo.size, v.what.take(40))
            }
        } finally { try { r.release() } catch (_: Throwable) {} }
        return out
    }

    // ===================== TXT / JSON =====================
    fun txt(d: Lecture.Doc): String {
        val sb = StringBuilder()
        sb.append(d.title.ifBlank { "محاضرة" }).append('\n')
        if (d.lang.isNotBlank()) sb.append("اللغة: ").append(d.lang).append('\n')
        if (d.durSec > 0) sb.append("المدة: ").append(fmt(d.durSec)).append('\n')
        sb.append("\n").append(Lecture.transcriptText(d, true))
        return sb.toString()
    }

    fun json(d: Lecture.Doc): String {
        val a = JSONArray()
        synchronized(d) {
            for (s in d.segs.sortedBy { it.start }) a.put(JSONObject().put("start", s.start).put("end", s.end).put("text", s.text)
                .put("speaker", when (s.spk) { "S" -> "student"; "D" -> "lecturer"; else -> "" }).put("lang", d.lang))
        }
        return JSONObject().put("title", d.title).put("lang", d.lang).put("duration", d.durSec).put("segments", a).toString(2)
    }

    // ===================== صفحة HTML =====================
    private fun htmlRuns(text: String, strip: Boolean, terms: MutableList<Pair<String, String>>?): String {
        val sb = StringBuilder()
        for (t in tokens(text, strip)) when (t) {
            is Tok.Plain -> sb.append(esc(t.t))
            is Tok.Add -> sb.append("<span class=\"underline-note\">").append(esc(t.t)).append("</span>")
            is Tok.Term -> {
                sb.append("<span class=\"term\">").append(esc(t.word)).append("<span class=\"star\">(*)</span></span>")
                if (t.def.isNotEmpty() && terms != null && terms.none { it.first == t.word }) terms.add(t.word to t.def)
            }
        }
        return sb.toString()
    }

    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)

    fun buildHtml(d: Lecture.Doc, an: JSONObject, media: Media?, extras: Boolean): ByteArray {
        val secs = objs(arr(an, "sections"))
        val toc = StringBuilder(); val body = StringBuilder()
        val divider = "<div class=\"divider\"><div class=\"diamond\"></div></div>"

        val mm = obj(an, "mindMap"); val br = if (mm != null) objs(arr(mm, "branches")) else emptyList()
        if (mm != null && br.isNotEmpty()) {
            body.append(divider).append("<div class=\"section\" id=\"s-mindmap\"><h2 class=\"section-title\">🧠 خريطة ذهنية للمحاضرة</h2>")
            body.append("<p style=\"text-align:center;font-weight:800;color:var(--teal-dark);\">⬢ ").append(esc(mm.optString("center"))).append("</p><div class=\"mindmap\">")
            for (b in br) {
                body.append("<div class=\"box\"><h4>").append(esc(b.optString("label"))).append("</h4><ul>")
                for (p in strs(arr(b, "points"))) body.append("<li>").append(esc(p)).append("</li>")
                body.append("</ul></div>")
            }
            body.append("</div></div>")
            toc.append("<li><a href=\"#s-mindmap\">خريطة ذهنية للمحاضرة</a></li>")
        }

        secs.forEachIndexed { idx, s ->
            val terms = ArrayList<Pair<String, String>>()
            val bullets = StringBuilder()
            for (b in strs(arr(s, "bullets"))) bullets.append("<li>").append(htmlRuns(b, !extras, terms)).append("</li>")
            val detail = s.optString("detail")
            val detailHtml = if (detail.isNotBlank()) "<div class=\"extra\"><span class=\"lbl\">📝 شرح إضافي من المحاضرة:</span>" + htmlRuns(detail, !extras, terms) + "</div>" else ""
            val warns = if (extras) medWarnings(s) else emptyList()
            val warnHtml = if (warns.isNotEmpty()) "<div class=\"medbox\"><span class=\"lbl\">⚠️ معلومة طبية حساسة مُضافة — راجعها في مصدر طبي موثوق قبل الاعتماد عليها سريرياً</span>" + warns.joinToString("<br>") { esc(it) } + "</div>" else ""
            val imgs = StringBuilder()
            if (extras && media != null) {
                for (f in framesOf(media, idx)) {
                    imgs.append("<figure><img src=\"data:image/jpeg;base64,").append(b64(f.bytes)).append("\" alt=\"").append(esc(s.optString("heading"))).append("\">")
                    imgs.append("<figcaption>لقطة من المحاضرة").append(if (f.label.isNotBlank()) " — " + esc(f.label) else "").append(" (").append(fmt(f.ts)).append(")</figcaption></figure>")
                }
                for (im in termsOf(s, idx, media.frames, media.audioOnly).mapNotNull { media.images[it] }) {
                    val mime = when (im.ext) { "png" -> "image/png"; "gif" -> "image/gif"; else -> "image/jpeg" }
                    imgs.append("<figure><img src=\"data:").append(mime).append(";base64,").append(b64(im.bytes)).append("\" alt=\"").append(esc(s.optString("heading"))).append("\">")
                    imgs.append("<figcaption class=\"src\">المصدر: Wikipedia — ").append(esc(im.title)).append("</figcaption></figure>")
                }
            }
            val fn = if (terms.isNotEmpty()) "<div class=\"footnotes\"><span class=\"fn-title\">📌 حاشية: تعريف المصطلحات العلمية المُعلَّمة بـ (*) في هذا القسم</span><ol>" +
                terms.joinToString("") { "<li><span class=\"fn-star\">*</span> <b>" + esc(it.first) + ":</b> " + esc(it.second) + "</li>" } + "</ol></div>" else ""
            body.append(divider).append("<div class=\"section\" id=\"s").append(idx).append("\"><h2 class=\"section-title\"><span class=\"num\">").append(idx + 1).append("</span>")
                .append(esc(s.optString("heading"))).append("</h2><ol class=\"points\">").append(bullets).append("</ol>").append(detailHtml).append(warnHtml).append(imgs).append(fn).append("</div>")
            toc.append("<li><a href=\"#s").append(idx).append("\">").append(esc(s.optString("heading"))).append("</a></li>")
        }

        val cmps = objs(arr(an, "comparisons"))
        if (cmps.isNotEmpty()) {
            body.append(divider).append("<div class=\"section\" id=\"s-cmp\"><h2 class=\"section-title\">⚖️ مقارنات</h2>")
            for (c in cmps) {
                body.append("<p style=\"font-weight:800;color:var(--teal-dark);\">").append(esc(c.optString("title"))).append("</p><table class=\"cmp\"><tr>")
                for (h in strs(arr(c, "columns"))) body.append("<th>").append(esc(h)).append("</th>")
                body.append("</tr>")
                val rows = arr(c, "rows")
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONArray(i) ?: continue
                    body.append("<tr>"); for (j in 0 until row.length()) body.append("<td>").append(esc(row.optString(j))).append("</td>"); body.append("</tr>")
                }
                body.append("</table>")
            }
            body.append("</div>"); toc.append("<li><a href=\"#s-cmp\">مقارنات</a></li>")
        }

        val review = obj(an, "review")
        val defs = if (review != null) objs(arr(review, "definitions")) else emptyList()
        if (defs.isNotEmpty()) {
            body.append(divider).append("<div class=\"section\" id=\"s-def\"><h2 class=\"section-title\">📖 مصطلحات وتعريفات</h2><table class=\"def\"><tr><th>المصطلح</th><th>التعريف</th></tr>")
            for (x in defs) body.append("<tr><td class=\"term-cell\">").append(esc(x.optString("term"))).append("</td><td>").append(htmlRuns(x.optString("meaning"), !extras, null)).append("</td></tr>")
            body.append("</table></div>"); toc.append("<li><a href=\"#s-def\">مصطلحات وتعريفات</a></li>")
        }

        val qna = objs(arr(an, "qna"))
        if (qna.isNotEmpty()) {
            body.append(divider).append("<div class=\"section\" id=\"s-qna\"><h2 class=\"section-title\">❓ أسئلة ونقاشات (طالب - دكتور)</h2>")
            qna.forEachIndexed { i, q ->
                body.append("<div class=\"qa\"><div class=\"q\">س").append(i + 1).append(": ").append(esc(q.optString("question"))).append("</div><div class=\"a\">ج: ")
                    .append(htmlRuns(q.optString("answer"), !extras, null)).append("</div></div>")
            }
            body.append("</div>"); toc.append("<li><a href=\"#s-qna\">أسئلة ونقاشات</a></li>")
        }

        val kps = if (review != null) strs(arr(review, "keyPoints")) else emptyList()
        if (kps.isNotEmpty()) {
            body.append(divider).append("<div class=\"section\" id=\"s-kp\"><h2 class=\"section-title\">📌 نقاط المراجعة الأساسية</h2><ol class=\"points\">")
            for (k in kps) body.append("<li>").append(esc(k)).append("</li>")
            body.append("</ol></div>"); toc.append("<li><a href=\"#s-kp\">نقاط المراجعة الأساسية</a></li>")
        }

        val quiz = if (review != null) objs(arr(review, "quickQuiz")) else emptyList()
        if (quiz.isNotEmpty()) {
            body.append(divider).append("<div class=\"section\" id=\"s-quiz\"><h2 class=\"section-title\">🧪 أسئلة مراجعة سريعة</h2>")
            quiz.forEachIndexed { i, q ->
                body.append("<div class=\"qa\"><div class=\"q\">").append(i + 1).append(". ").append(esc(q.optString("question"))).append("</div><div class=\"a\">الإجابة: ")
                    .append(esc(q.optString("answer"))).append("</div></div>")
            }
            body.append("</div>"); toc.append("<li><a href=\"#s-quiz\">أسئلة مراجعة سريعة</a></li>")
        }

        val notice = if (extras)
            "<div class=\"notice\"><b>ملحوظة:</b> أي نص يظهر <span class=\"underline-note\">بهذا الشكل الأحمر تحته خط</span> هو معلومة موثوقة مُضافة من خارج كلام المحاضر مباشرةً — كل ما عدا ذلك منقول من المحاضرة نفسها.</div>" +
            "<div class=\"notice warn\"><b>⚠️ تحذير طبي:</b> أي معلومة مُضافة تخص جرعة دواء أو تشخيص أو خطوة علاج، هتلاقيها في صندوق تحذير منفصل تحت القسم المرتبط — راجعها في مصدر طبي موثوق قبل أي استخدام سريري فعلي.</div>"
        else "<div class=\"notice\">هذه النسخة تحتوي فقط على المعلومات المستخرجة من المحاضرة نفسها، بدون أي إضافة أو صورة من مصدر خارجي.</div>"

        val title = an.optString("title").ifBlank { d.title.ifBlank { "مذكرة المحاضرة" } }
        val sub = an.optString("subtitle")
        val html = StringBuilder()
        html.append("<!DOCTYPE html>\n<html lang=\"ar\" dir=\"rtl\">\n<head>\n<meta charset=\"UTF-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n<title>")
            .append(esc(title)).append("</title>\n<style>").append(LectureCss.HTML).append("</style>\n</head>\n<body>\n<div class=\"sheet\">\n<div class=\"cover\"><h1>")
            .append(esc(title)).append("</h1>").append(if (sub.isNotBlank()) "<p class=\"sub\">" + esc(sub) + "</p>" else "")
            .append("<span class=\"badge\">🎓 مذكرة مذاكرة — ")
            .append(if (extras) "محسّنة + معلومات إضافية موثوقة وصور" else "تفريغ منظّم من المحاضرة (بدون إضافات)").append("</span></div>")
            .append(notice).append("<div class=\"toc\"><h3>📑 فهرس المحاضرة</h3><ol>").append(toc).append("</ol></div>")
            .append(body).append("<div class=\"end-mark\">❖ ❖ ❖</div>\n</div>\n</body>\n</html>")
        return html.toString().toByteArray(Charsets.UTF_8)
    }

    // ===================== ملف Word (.docx) — OOXML يدوي =====================
    private class Dx {
        val body = StringBuilder()
        val media = ArrayList<Pair<String, ByteArray>>()   // اسم الملف داخل word/media ← بايتات
        val rels = StringBuilder()
        var nextRid = 10
        var nextDoc = 1
        val numIds = ArrayList<Int>()
    }

    private fun rPr(b: Boolean, i: Boolean, sz: Int, color: String?, u: Boolean): String = buildString {
        append("<w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>")
        if (b) append("<w:b/><w:bCs/>"); if (i) append("<w:i/><w:iCs/>")
        if (color != null) append("<w:color w:val=\"").append(color).append("\"/>")
        append("<w:sz w:val=\"").append(sz).append("\"/><w:szCs w:val=\"").append(sz).append("\"/>")
        if (u) append("<w:u w:val=\"single\"/>")
        append("<w:rtl/></w:rPr>")
    }

    private fun run(t: String, b: Boolean = false, i: Boolean = false, sz: Int = 22, color: String? = null, u: Boolean = false): String =
        "<w:r>" + rPr(b, i, sz, color, u) + "<w:t xml:space=\"preserve\">" + xml(t) + "</w:t></w:r>"

    private fun para(runs: String, jc: String? = null, style: String? = null, numId: Int = 0, shd: String? = null, bdr: String? = null,
                     before: Int = 0, after: Int = 100, keepNext: Boolean = false): String = buildString {
        append("<w:p><w:pPr>")
        if (style != null) append("<w:pStyle w:val=\"").append(style).append("\"/>")
        if (keepNext) append("<w:keepNext/>")
        if (numId > 0) append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"").append(numId).append("\"/></w:numPr>")
        if (bdr != null) append("<w:pBdr>").append(bdr).append("</w:pBdr>")
        if (shd != null) append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(shd).append("\"/>")
        append("<w:bidi/><w:spacing w:before=\"").append(before).append("\" w:after=\"").append(after).append("\"/>")
        if (jc != null) append("<w:jc w:val=\"").append(jc).append("\"/>")
        append("</w:pPr>").append(runs).append("</w:p>")
    }

    private fun bd(side: String, style: String, sz: Int, color: String, space: Int = 4) = "<w:$side w:val=\"$style\" w:sz=\"$sz\" w:space=\"$space\" w:color=\"$color\"/>"

    private fun tokRuns(text: String, strip: Boolean, terms: MutableList<Pair<String, String>>?, sz: Int, color: String?, italic: Boolean = false): String {
        val sb = StringBuilder()
        for (t in tokens(text, strip)) when (t) {
            is Tok.Plain -> sb.append(run(t.t, sz = sz, color = color, i = italic))
            is Tok.Add -> sb.append(run(t.t, sz = sz, color = "C0392B", u = true, i = italic))
            is Tok.Term -> {
                sb.append(run(t.word, b = true, sz = sz, color = color, i = italic)).append(run(" (*)", b = true, sz = sz, color = "C0392B"))
                if (t.def.isNotEmpty() && terms != null && terms.none { it.first == t.word }) terms.add(t.word to t.def)
            }
        }
        return sb.toString()
    }

    private fun divider(): String =
        para(run("", sz = 2), jc = "center", bdr = bd("bottom", "double", 18, "D4AC0D"), before = 260, after = 0) +
        para(run("◆ ◆ ◆", b = true, sz = 20, color = "D4AC0D"), jc = "center", before = 40, after = 260)

    private fun cell(w: Int, content: String, fill: String? = null, line: String = "BBBBBB"): String =
        "<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/><w:tcBorders>" + bd("top", "single", 6, line, 0) + bd("left", "single", 6, line, 0) + bd("bottom", "single", 6, line, 0) + bd("right", "single", 6, line, 0) +
        "</w:tcBorders>" + (if (fill != null) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$fill\"/>" else "") +
        "<w:tcMar><w:top w:w=\"100\" w:type=\"dxa\"/><w:left w:w=\"120\" w:type=\"dxa\"/><w:bottom w:w=\"100\" w:type=\"dxa\"/><w:right w:w=\"120\" w:type=\"dxa\"/></w:tcMar></w:tcPr>" +
        (if (content.isEmpty()) "<w:p/>" else content) + "</w:tc>"

    private fun table(widths: List<Int>, rows: List<List<String>>): String = buildString {
        append("<w:tbl><w:tblPr><w:bidiVisual/><w:tblW w:w=\"").append(widths.sum()).append("\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>")
        for (w in widths) append("<w:gridCol w:w=\"").append(w).append("\"/>")
        append("</w:tblGrid>")
        for (r in rows) append("<w:tr><w:trPr><w:cantSplit/></w:trPr>").append(r.joinToString("")).append("</w:tr>")
        append("</w:tbl>")
    }

    private fun picture(x: Dx, bytes: ByteArray, ext: String): String {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
        if (bo.outWidth <= 0 || bo.outHeight <= 0) return ""
        val maxW = 5_100_000.0; val maxH = 4_300_000.0
        var cx = maxW; var cy = maxW * bo.outHeight / bo.outWidth
        if (cy > maxH) { cy = maxH; cx = maxH * bo.outWidth / bo.outHeight }
        val name = "img" + (x.media.size + 1) + "." + ext
        x.media.add(name to bytes)
        val rid = "rId" + (x.nextRid++)
        x.rels.append("<Relationship Id=\"").append(rid).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/").append(name).append("\"/>")
        val id = x.nextDoc++
        return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"${cx.toLong()}\" cy=\"${cy.toLong()}\"/><wp:docPr id=\"$id\" name=\"$name\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${cx.toLong()}\" cy=\"${cy.toLong()}\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
    }

    private fun mindMapBlocks(mm: JSONObject?): String {
        if (mm == null) return ""
        val br = objs(arr(mm, "branches")); if (br.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append(para(run("🧠 خريطة ذهنية للمحاضرة", b = true, color = "3498DB", sz = 32), style = "Heading1", after = 200))
        sb.append(para(run("⬢ " + mm.optString("center"), b = true, sz = 30, color = "FFFFFF"), jc = "center", shd = "2C3E50",
            bdr = bd("top", "single", 12, "F5D023") + bd("bottom", "single", 12, "F5D023"), after = 300))
        val perRow = if (br.size <= 4) br.size else if (br.size % 3 == 0) 3 else if (br.size % 2 == 0) 2 else 3
        val colW = 9000 / maxOf(1, perRow)
        var i = 0
        while (i < br.size) {
            val row = br.subList(i, minOf(br.size, i + perRow))
            val cells = ArrayList<String>()
            row.forEachIndexed { ci, b ->
                val k = (i + ci) % MM_FILL.size
                val inner = StringBuilder(para(run(b.optString("label"), b = true, sz = 22, color = MM_LINE[k]), jc = "center", after = 100))
                for (p in strs(arr(b, "points"))) inner.append(para(run(p, sz = 18), numId = 2, after = 40))
                cells.add(cell(colW, inner.toString(), MM_FILL[k], MM_LINE[k]))
            }
            while (cells.size < perRow) cells.add(cell(colW, ""))
            sb.append(table(List(perRow) { colW }, listOf(cells)))
            sb.append(para("", after = 150))
            i += perRow
        }
        return sb.toString()
    }

    private fun docxBody(x: Dx, d: Lecture.Doc, an: JSONObject, media: Media?, extras: Boolean) {
        val b = x.body
        val title = an.optString("title").ifBlank { d.title.ifBlank { "مذكرة المحاضرة" } }
        b.append(para(run(title, b = true, sz = 44, color = "1B2631"), style = "Title", jc = "center", after = 200))
        an.optString("subtitle").takeIf { it.isNotBlank() }?.let { b.append(para(run(it, i = true, sz = 24, color = "666666"), jc = "center", after = 300)) }
        b.append(para(run(if (extras) "🎓 مذكرة مذاكرة — محسّنة + معلومات إضافية موثوقة وصور" else "🎓 مذكرة مذاكرة — تفريغ منظّم من المحاضرة (بدون إضافات)", sz = 18, color = "999999"),
            jc = "center", bdr = bd("bottom", "single", 6, "F5D023", 1), after = 300))
        if (extras) {
            b.append(para(run("ملحوظة: ", b = true, sz = 18) + run("أي نص يظهر ", sz = 18) + run("بهذا الشكل الأحمر تحته خط", sz = 18, color = "C0392B", u = true) +
                run(" هو معلومة موثوقة مُضافة من خارج كلام المحاضر مباشرةً — كل ما عدا ذلك منقول من المحاضرة نفسها.", sz = 18), jc = "center", shd = "FBEAEA", after = 150))
            b.append(para(run("⚠️ تحذير طبي: ", b = true, sz = 18, color = "922B21") +
                run("أي معلومة مُضافة تخص جرعة دواء أو تشخيص أو خطوة علاج، هتلاقيها في صندوق تحذير منفصل تحت القسم المرتبط — راجعها في مصدر طبي موثوق قبل أي استخدام سريري فعلي.", sz = 18, color = "922B21"),
                jc = "center", shd = "FDECEA", bdr = bd("top", "single", 8, "C0392B") + bd("bottom", "single", 8, "C0392B"), after = 500))
        } else {
            b.append(para(run("هذه النسخة تحتوي فقط على المعلومات المستخرجة من المحاضرة نفسها، بدون أي إضافة أو صورة من مصدر خارجي.", sz = 18, color = "444444"), jc = "center", shd = "EAF2FA", after = 500))
        }
        b.append(mindMapBlocks(obj(an, "mindMap")))
        b.append(divider())
        b.append(para(run("📚 شرح المحاضرة بالتفصيل", b = true, color = "2E86C1", sz = 32), style = "Heading1", after = 200))

        val secs = objs(arr(an, "sections"))
        secs.forEachIndexed { idx, s ->
            val pal = PALETTE[idx % PALETTE.size]
            if (idx > 0) b.append(divider())
            b.append(para(run("${idx + 1}. ${s.optString("heading")}", b = true, color = pal.first, sz = 32), style = "Heading1", jc = "center", shd = pal.second,
                bdr = bd("bottom", "double", 10, pal.first) + bd("right", "single", 24, pal.first), before = if (idx == 0) 200 else 0, after = 150, keepNext = true))
            val terms = ArrayList<Pair<String, String>>()
            val numId = x.numIds[idx]
            for (bl in strs(arr(s, "bullets"))) b.append(para(tokRuns(bl, !extras, terms, 22, null), numId = numId, after = 80))
            val detail = s.optString("detail")
            if (detail.isNotBlank()) b.append(para(tokRuns(detail, !extras, terms, 20, "555555", italic = true), shd = "F7F3E3", bdr = bd("left", "single", 12, pal.first), before = 150, after = 150))
            if (extras) {
                val warns = medWarnings(s)
                if (warns.isNotEmpty()) {
                    val line = bd("top", "single", 12, "C0392B") + bd("left", "single", 12, "C0392B") + bd("right", "single", 12, "C0392B")
                    b.append(para(run("⚠️ معلومة طبية حساسة مُضافة — راجعها في مصدر طبي موثوق قبل الاعتماد عليها سريرياً", b = true, sz = 19, color = "922B21"), shd = "FDECEA", bdr = line, before = 100, after = 0))
                    warns.forEachIndexed { i, w ->
                        val last = i == warns.size - 1
                        b.append(para(run(w, sz = 19, color = "922B21"), numId = 2, shd = "FDECEA",
                            bdr = bd("left", "single", 12, "C0392B") + (if (last) bd("bottom", "single", 12, "C0392B") else "") + bd("right", "single", 12, "C0392B"), after = if (last) 100 else 50))
                    }
                }
                if (media != null) for (f in framesOf(media, idx)) {
                    val pic = picture(x, f.bytes, "jpg")
                    if (pic.isNotEmpty()) {
                        b.append(para(pic, jc = "center", before = 100, after = 40))
                        b.append(para(run("لقطة من المحاضرة" + (if (f.label.isNotBlank()) " — " + f.label else "") + " (" + fmt(f.ts) + ")", i = true, sz = 16, color = "999999"), jc = "center", after = 150))
                    }
                }
                if (media != null) for (im in termsOf(s, idx, media.frames, media.audioOnly).mapNotNull { media.images[it] }) {
                    val pic = picture(x, im.bytes, im.ext)
                    if (pic.isNotEmpty()) {
                        b.append(para(pic, jc = "center", before = 100, after = 40))
                        b.append(para(run("المصدر: Wikipedia — " + im.title, i = true, sz = 16, color = "999999"), jc = "center", after = 150))
                    }
                }
            }
            if (terms.isNotEmpty()) {
                var first = true
                for ((w, def) in terms) {
                    b.append(para(run("* ", b = true, sz = 16, color = "C0392B") + run("$w: ", b = true, sz = 16, color = "555555") + run(def, sz = 16, color = "555555"),
                        bdr = if (first) bd("top", "single", 6, "444444", 6) else null, before = if (first) 100 else 0, after = 30))
                    first = false
                }
            }
        }

        val cmps = objs(arr(an, "comparisons"))
        if (cmps.isNotEmpty()) {
            b.append(divider())
            b.append(para(run("⚖️ مقارنات", b = true, color = "D68910", sz = 32), style = "Heading1", after = 200))
            for (c in cmps) {
                b.append(para(run(c.optString("title"), b = true, sz = 26), style = "Heading2", after = 100, keepNext = true))
                val cols = strs(arr(c, "columns")); val n = maxOf(1, cols.size); val w = 9000 / n
                val rows = ArrayList<List<String>>()
                rows.add(cols.map { cell(w, para(run(it, b = true, sz = 20, color = "FFFFFF"), jc = "center", after = 0), "2E86C1", "2E86C1") })
                val rr = arr(c, "rows")
                for (i in 0 until rr.length()) {
                    val r = rr.optJSONArray(i) ?: continue
                    rows.add((0 until n).map { j -> cell(w, para(run(r.optString(j), sz = 20), jc = "center", after = 0), if (i % 2 == 1) "F4F8FB" else null) })
                }
                b.append(table(List(n) { w }, rows)); b.append(para("", after = 200))
            }
        }

        val qna = objs(arr(an, "qna"))
        if (qna.isNotEmpty()) {
            b.append(divider())
            b.append(para(run("❓ أسئلة ونقاشات (طالب - دكتور)", b = true, color = "8E44AD", sz = 32), style = "Heading1", after = 200))
            qna.forEachIndexed { i, q ->
                b.append(para(run("س${i + 1}: ${q.optString("question")}", b = true, sz = 22), shd = "F3EAFA", bdr = bd("right", "single", 18, "8E44AD"), before = 150, after = 60, keepNext = true))
                b.append(para(run("ج: ", sz = 21, color = "4B3B82") + tokRuns(q.optString("answer"), !extras, null, 21, null), after = 150))
            }
        }

        val review = obj(an, "review")
        val defs = if (review != null) objs(arr(review, "definitions")) else emptyList()
        if (defs.isNotEmpty()) {
            b.append(divider())
            b.append(para(run("📖 مصطلحات وتعريفات", b = true, color = "16A085", sz = 32), style = "Heading1", after = 200))
            val rows = ArrayList<List<String>>()
            rows.add(listOf(cell(3000, para(run("المصطلح", b = true, sz = 20), after = 0), "D8F3E8"), cell(6000, para(run("التعريف", b = true, sz = 20), after = 0), "D8F3E8")))
            defs.forEachIndexed { i, x2 ->
                val f = if (i % 2 == 1) "F5FBF8" else null
                rows.add(listOf(cell(3000, para(run(x2.optString("term"), b = true, sz = 20, color = "16A085"), after = 0), f), cell(6000, para(tokRuns(x2.optString("meaning"), !extras, null, 20, null), after = 0), f)))
            }
            b.append(table(listOf(3000, 6000), rows)); b.append(para("", after = 200))
        }

        val kps = if (review != null) strs(arr(review, "keyPoints")) else emptyList()
        if (kps.isNotEmpty()) {
            b.append(divider())
            b.append(para(run("📌 نقاط المراجعة الأساسية", b = true, color = "D68910", sz = 32), style = "Heading1", after = 200))
            kps.forEachIndexed { i, k -> b.append(para(run(k, sz = 22, color = PALETTE[i % PALETTE.size].first), numId = 2, after = 80)) }
        }

        val quiz = if (review != null) objs(arr(review, "quickQuiz")) else emptyList()
        if (quiz.isNotEmpty()) {
            b.append(divider())
            b.append(para(run("🧪 أسئلة مراجعة سريعة", b = true, color = "D4AC0D", sz = 32), style = "Heading1", after = 200))
            quiz.forEachIndexed { i, q ->
                b.append(para(run("${i + 1}. ${q.optString("question")}", b = true, sz = 22), before = 120, after = 40, keepNext = true))
                b.append(para(run("الإجابة: ${q.optString("answer")}", sz = 20, color = "777777"), after = 100))
            }
        }
    }

    private const val NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""

    private fun stylesXml(): String = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:bidi/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"44\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"26\"/></w:rPr></w:style>" +
        "</w:styles>"

    private fun numberingXml(sections: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        append("<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>")
        append("<w:abstractNum w:abstractNumId=\"2\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"540\" w:hanging=\"270\"/></w:pPr></w:lvl></w:abstractNum>")
        append("<w:num w:numId=\"2\"><w:abstractNumId w:val=\"2\"/></w:num>")
        for (i in 0 until sections) append("<w:num w:numId=\"").append(10 + i).append("\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>")
        append("</w:numbering>")
    }

    fun buildDocx(d: Lecture.Doc, an: JSONObject, media: Media?, extras: Boolean): ByteArray {
        val secs = objs(arr(an, "sections")).size
        val x = Dx(); for (i in 0 until secs) x.numIds.add(10 + i)
        docxBody(x, d, an, media, extras)
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $NS><w:body>" + x.body +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/><w:bidi/></w:sectPr></w:body></w:document>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>" + x.rels + "</Relationships>"
        val types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"gif\" ContentType=\"image/gif\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/></Types>"
        val root = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            put("[Content_Types].xml", types.toByteArray(Charsets.UTF_8))
            put("_rels/.rels", root.toByteArray(Charsets.UTF_8))
            put("word/document.xml", doc.toByteArray(Charsets.UTF_8))
            put("word/_rels/document.xml.rels", rels.toByteArray(Charsets.UTF_8))
            put("word/styles.xml", stylesXml().toByteArray(Charsets.UTF_8))
            put("word/numbering.xml", numberingXml(secs).toByteArray(Charsets.UTF_8))
            for ((n, b) in x.media) put("word/media/$n", b)
        }
        return bo.toByteArray()
    }

    // ===================== حفظ ومشاركة =====================
    private fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "محاضرة" }

    fun save(ctx: Context, name: String, mime: String, bytes: ByteArray): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Joyboy/Study"); put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("مقدرتش أكتب في التنزيلات")
            (ctx.contentResolver.openOutputStream(uri) ?: throw IOException("مقدرتش أفتح الملف للكتابة")).use { it.write(bytes) }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, done, null, null)
            return uri
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, "Study").apply { mkdirs() }
        val f = File(dir, name); f.writeBytes(bytes)
        return androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fmprov", f)
    }

    fun open(act: Activity, b: Built) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(b.uri, b.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) { share(act, b) }
    }

    fun share(act: Activity, b: Built) {
        try {
            act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(b.mime).putExtra(Intent.EXTRA_STREAM, b.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "مشاركة"))
        } catch (e: Throwable) { LogStore.err("LectureDoc:share", e) }
    }

    // ===================== المُنسّق =====================
    private val memo = HashMap<String, Media>()

    /** kind: docx_raw · docx_plus · html · txt · json — بيتنده من خيط خلفي. onStatus بيتنده من نفس الخيط (الواجهة تبعتها للـmain). */
    fun build(ctx: Context, d: Lecture.Doc, kind: String, onStatus: (String) -> Unit): Built {
        val base = safeName(d.title)
        if (kind == "txt") return Built("$base - تفريغ.txt", "text/plain", save(ctx, "$base - تفريغ.txt", "text/plain", txt(d).toByteArray(Charsets.UTF_8)))
        if (kind == "json") return Built("$base - تفريغ.json", "application/json", save(ctx, "$base - تفريغ.json", "application/json", json(d).toByteArray(Charsets.UTF_8)))
        if (d.segs.isEmpty()) throw IOException("فرّغ المحاضرة الأول")
        onStatus("🧠 بحلل المحاضرة وببني الهيكل التعليمي…")
        val an = Lecture.analyze(ctx, d)
        val extras = kind != "docx_raw"
        var media: Media? = null
        if (extras) {
            val key = d.id + ":" + d.analysisN
            media = memo[key] ?: run {
                val audioOnly = d.kind == "audio" || d.kind == "rec"
                onStatus("🎬 بلقط صور من الفيديو الأصلي عند إشارات المحاضر…")
                val frames = try { captureFrames(ctx, d, an) { i, n, h -> onStatus("🎬 لقطات: $i/$n — $h") } } catch (e: Throwable) { LogStore.err("LectureDoc:frames", e); emptyMap() }
                onStatus("🖼 بجيب صور توضيحية من Wikipedia…")
                val imgs = fetchImages(ctx, an, frames, audioOnly) { i, n, t -> onStatus("🖼 صور: $i/$n — $t") }
                Media(imgs, frames, audioOnly).also { memo[key] = it }
            }
        }
        onStatus("📄 ببني الملف…")
        return when (kind) {
            "html" -> { val n = "$base - مذكرة.html"; Built(n, "text/html", save(ctx, n, "text/html", buildHtml(d, an, media, true))) }
            "docx_raw" -> { val n = "$base - مذكرة (خام محسّن).docx"; Built(n, MIME_DOCX, save(ctx, n, MIME_DOCX, buildDocx(d, an, null, false))) }
            else -> { val n = "$base - مذكرة (محسّن + مُضاف).docx"; Built(n, MIME_DOCX, save(ctx, n, MIME_DOCX, buildDocx(d, an, media, true))) }
        }
    }
}
