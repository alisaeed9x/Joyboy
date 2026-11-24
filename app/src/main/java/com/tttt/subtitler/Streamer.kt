package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/** (v1254) ستريمر: صفحة مصادر (سورسات) تتصفّح قوايم وحلقات وتشغّلها في مشغّل Joyboy وتحمّلها بمدير التحميل. من غير مكتبات إضافية ومن غير إعلانات. */
class SShow(val title: String, val url: String, val thumb: String)
class SEp(val title: String, val url: String)
class SStream(val label: String, val url: String, val ref: String)

interface StreamSource {
    val id: String
    val name: String
    fun search(q: String, page: Int): List<SShow>
    fun episodes(s: SShow): List<SEp>
    fun streams(e: SEp): List<SStream>
}

object StreamerNet {
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    fun get(url: String, ref: String = ""): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 25000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        if (ref.isNotEmpty()) c.setRequestProperty("Referer", ref)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP " + code)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }
    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    fun abs(base: String, u: String): String {
        val x = u.trim().replace("&amp;", "&")
        if (x.startsWith("http://") || x.startsWith("https://")) return x
        if (x.startsWith("//")) return "https:" + x
        return try { java.net.URI(base).resolve(x).toString() } catch (_: Throwable) { x }
    }
    private val MEDIA = Regex("""https?:\\?/\\?/[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?""")
    private val IFRAME = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    /** بيدوّر في الصفحة على لينكات m3u8/mp4 (ومرّة واحدة جوه iframe) */
    fun detect(html: String, page: String, depth: Int = 0): List<SStream> {
        val out = ArrayList<SStream>(); val seen = HashSet<String>()
        for (m in MEDIA.findAll(html.replace("\\/", "/"))) {
            val u = m.value
            if (seen.add(u)) out.add(SStream(if (u.contains(".m3u8")) "HLS" else "MP4", u, page))
        }
        if (out.isEmpty() && depth == 0) {
            for (m in IFRAME.findAll(html).take(3)) {
                val fr = abs(page, m.groupValues[1])
                try { out.addAll(detect(get(fr, page), fr, 1)) } catch (_: Throwable) {}
                if (out.isNotEmpty()) break
            }
        }
        return out
    }
}

/** مصدر مدمج قانوني: Internet Archive (أفلام/كرتون/أنمي عام). بيثبت إن الدورة كلها شغّالة من غير سورس خارجي */
class ArchiveSource : StreamSource {
    override val id = "archive"
    override val name = "Internet Archive"
    override fun search(q: String, page: Int): List<SShow> {
        val qq = if (q.isBlank()) "subject:(anime OR animation OR cartoon)" else "(" + q + ")"
        val u = "https://archive.org/advancedsearch.php?q=" + StreamerNet.enc(qq + " AND mediatype:(movies)") + "&fl%5B%5D=identifier&fl%5B%5D=title&rows=30&page=" + page + "&output=json&sort%5B%5D=downloads+desc"
        val docs = JSONObject(StreamerNet.get(u)).getJSONObject("response").getJSONArray("docs")
        val out = ArrayList<SShow>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i); val idf = d.optString("identifier")
            if (idf.isEmpty()) continue
            out.add(SShow(d.optString("title", idf), idf, "https://archive.org/services/img/" + idf))
        }
        return out
    }
    override fun episodes(s: SShow): List<SEp> {
        val files = JSONObject(StreamerNet.get("https://archive.org/metadata/" + s.url)).optJSONArray("files") ?: return emptyList()
        val out = ArrayList<SEp>()
        for (i in 0 until files.length()) {
            val n = files.getJSONObject(i).optString("name")
            val l = n.lowercase()
            if (l.endsWith(".mp4") || l.endsWith(".m4v") || l.endsWith(".mkv") || l.endsWith(".webm"))
                out.add(SEp(n.substringBeforeLast('.'), "https://archive.org/download/" + s.url + "/" + java.net.URLEncoder.encode(n, "UTF-8").replace("+", "%20")))
        }
        return out
    }
    override fun streams(e: SEp): List<SStream> = listOf(SStream("ملف", e.url, ""))
}

/** سورس معرَّف بـJSON (الصقه أو حط لينكه): regex بسيط لكل خطوة. بيتخزّن في الجهاز بس */
class RecipeSource(val j: JSONObject) : StreamSource {
    override val id = "r:" + j.optString("name")
    override val name = j.optString("name")
    private val base = j.optString("base")
    private fun named(m: MatchResult, g: String): String = try { ((m.groups as MatchNamedGroupCollection)[g]?.value ?: "") } catch (_: Throwable) { "" }
    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").trim()
    override fun search(q: String, page: Int): List<SShow> {
        val s = j.getJSONObject("search")
        val url = s.getString("url").replace("{q}", StreamerNet.enc(q)).replace("{page}", page.toString())
        val html = StreamerNet.get(StreamerNet.abs(base, url), base)
        val out = ArrayList<SShow>(); val seen = HashSet<String>()
        for (m in Regex(s.getString("item"), setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html)) {
            val u = named(m, "url"); if (u.isEmpty() || !seen.add(u)) continue
            out.add(SShow(clean(named(m, "title")).ifEmpty { u }, StreamerNet.abs(base, u), named(m, "img").let { if (it.isEmpty()) "" else StreamerNet.abs(base, it) }))
        }
        return out
    }
    override fun episodes(s: SShow): List<SEp> {
        val e = j.optJSONObject("episodes") ?: return listOf(SEp(s.title, s.url))
        val html = StreamerNet.get(s.url, base)
        val out = ArrayList<SEp>(); val seen = HashSet<String>()
        for (m in Regex(e.getString("item"), setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html)) {
            val u = named(m, "url"); if (u.isEmpty() || !seen.add(u)) continue
            out.add(SEp(clean(named(m, "title")).ifEmpty { "حلقة " + (out.size + 1) }, StreamerNet.abs(s.url, u)))
        }
        if (e.optBoolean("reverse", false)) out.reverse()
        return out
    }
    override fun streams(e: SEp): List<SStream> {
        val html = StreamerNet.get(e.url, base)
        val st = j.optJSONObject("stream")
        val out = ArrayList<SStream>()
        if (st != null && st.has("item")) {
            for (m in Regex(st.getString("item"), setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html)) {
                val u = named(m, "url").replace("\\/", "/"); if (u.isEmpty()) continue
                out.add(SStream(clean(named(m, "label")).ifEmpty { if (u.contains(".m3u8")) "HLS" else "فيديو" }, StreamerNet.abs(e.url, u), e.url))
            }
        }
        if (out.isEmpty()) out.addAll(StreamerNet.detect(html, e.url))
        return out
    }
}

object StreamerSources {
    private fun prefs(c: Context) = c.getSharedPreferences("streamer", Context.MODE_PRIVATE)
    fun custom(c: Context): List<JSONObject> {
        val a = try { JSONArray(prefs(c).getString("recipes", "[]")) } catch (_: Throwable) { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    fun all(c: Context): List<StreamSource> {
        val l = ArrayList<StreamSource>(); l.add(ArchiveSource())
        for (j in custom(c)) try { l.add(RecipeSource(j)) } catch (_: Throwable) {}
        return l
    }
    private fun save(c: Context, l: List<JSONObject>) { val a = JSONArray(); l.forEach { a.put(it) }; prefs(c).edit().putString("recipes", a.toString()).apply() }
    /** بيرجّع رسالة خطأ أو null لو اتضاف */
    fun add(c: Context, text: String): String? {
        try {
            var t = text.trim()
            if (t.startsWith("http", true)) t = StreamerNet.get(t)
            val j = JSONObject(t)
            if (j.optString("name").isBlank()) return "السورس لازم يبقى فيه name"
            val s = j.optJSONObject("search") ?: return "ناقص search"
            if (s.optString("url").isBlank() || s.optString("item").isBlank()) return "search لازم فيه url وitem"
            Regex(s.getString("item"))
            val l = custom(c).filter { it.optString("name") != j.optString("name") }.toMutableList(); l.add(j); save(c, l)
            return null
        } catch (e: Throwable) { return "مش JSON صالح: " + (e.message ?: e.javaClass.simpleName).take(80) }
    }
    fun remove(c: Context, name: String) { save(c, custom(c).filter { it.optString("name") != name }) }
}

class StreamerUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val stack = ArrayList<() -> Unit>()
    private var dlg: Dialog? = null
    private var srcs: List<StreamSource> = emptyList()
    private var cur = 0
    private var query = ""
    private var results: List<SShow> = emptyList()
    private var busy = ""
    private var ext: List<StreamSource> = emptyList()

    fun show() {
        srcs = StreamerSources.all(act) + ext
        val d = ui.sheet(act, "🎞 ستريمر", listOf<View>(root), solid = true)
        (d as? GDialog)?.onBack = { if (stack.size > 1) { stack.removeAt(stack.size - 1); val top: () -> Unit = stack[stack.size - 1]; top(); true } else false }
        dlg = d
        go { home() }
        d.show()
        runSearch()
        bg {
            val ex = try { PluginBridge.aniyomiSources(act) } catch (_: Throwable) { emptyList() }
            if (ex.isNotEmpty()) onUi { ext = ex; srcs = StreamerSources.all(act) + ext; if (stack.size == 1) home() }
        }
    }

    private fun go(f: () -> Unit) { stack.add(f); f() }
    private fun onUi(f: () -> Unit) { act.runOnUiThread { try { f() } catch (_: Throwable) {} } }
    private fun bg(f: () -> Unit) { Thread { try { f() } catch (_: Throwable) {} }.also { it.isDaemon = true }.start() }
    private fun src(): StreamSource = srcs[cur.coerceIn(0, srcs.size - 1)]

    private fun runSearch() {
        busy = "⏳ بدوّر…"; if (stack.size == 1) home()
        val s = src(); val q = query
        bg {
            val r = try { s.search(q, 1) } catch (e: Throwable) { onUi { busy = "⚠ " + (e.message ?: "فشل").take(80); home() }; return@bg }
            onUi { results = r; busy = if (r.isEmpty()) "مفيش نتايج" else ""; if (stack.size == 1) home() }
        }
    }

    private fun home() {
        root.removeAllViews()
        root.addView(ui.chips(srcs.map { it.name }, { src().name }) { n ->
            cur = srcs.indexOfFirst { it.name == n }.coerceAtLeast(0); results = emptyList(); runSearch()
        })
        val inp = ui.input("🔎 دوّر…", query)
        inp.setOnEditorActionListener { v, _, _ -> query = v.text.toString().trim(); runSearch(); true }
        root.addView(inp)
        root.addView(ui.button("🔎 بحث") { query = inp.text.toString().trim(); runSearch() })
        root.addView(ui.button("➕ إضافة سورس (JSON)") { addDialog() })
        if (ext.isEmpty()) root.addView(ui.button("🧩 بلاجن (لتشغيل إضافات Aniyomi)") { PluginsUi(act, ui, th).show() })
        if (src() is RecipeSource) root.addView(ui.button("🗑 احذف السورس ده") {
            StreamerSources.remove(act, src().name); srcs = StreamerSources.all(act) + ext; cur = 0; results = emptyList(); runSearch()
        })
        if (busy.isNotEmpty()) root.addView(ui.text(busy, 13f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
        for (s in results) root.addView(row(s.title, s.thumb, "") { open(s) })
    }

    private fun row(title: String, thumb: String, sub: String, onTap: () -> Unit): LinearLayout {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); background = ui.box(th.surface, th.border, 10)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) }
            setOnClickListener { onTap() }
        }
        if (thumb.isNotEmpty()) {
            val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.box(th.card, th.border, 8) }
            r.addView(iv, LinearLayout.LayoutParams(ui.dp(56), ui.dp(78)).apply { marginEnd = ui.dp(10) })
            try { com.bumptech.glide.Glide.with(act).load(thumb).centerCrop().into(iv) } catch (_: Throwable) {}
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.text(title, 14f, th.text, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub.isNotEmpty()) col.addView(ui.text(sub, 11f, th.muted))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return r
    }

    private fun open(s: SShow) {
        go {
            root.removeAllViews()
            root.addView(ui.text(s.title, 16f, th.primary, true))
            root.addView(ui.text("⏳ بجيب الحلقات…", 13f, th.muted).apply { tag = "wait" })
            val sc = src()
            bg {
                val eps = try { sc.episodes(s) } catch (e: Throwable) { onUi { root.removeAllViews(); root.addView(ui.text("⚠ " + (e.message ?: "فشل").take(100), 13f, th.danger)) }; return@bg }
                onUi { showEpisodes(s, sc, eps) }
            }
        }
    }

    private fun showEpisodes(s: SShow, sc: StreamSource, eps: List<SEp>) {
        root.removeAllViews()
        root.addView(ui.text(s.title, 16f, th.primary, true))
        root.addView(ui.text(if (eps.isEmpty()) "مفيش حلقات ظاهرة" else eps.size.toString() + " حلقة — دوس للتشغيل، ⬇ للتحميل", 12f, th.muted))
        for (e in eps) {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(10), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(th.surface, th.border, 10)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) }
            }
            val t = ui.text("▶  " + e.title, 14f, th.text).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setOnClickListener { resolve(sc, s, e, false) } }
            r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(ui.text("⬇", 20f, th.primary).apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); setOnClickListener { resolve(sc, s, e, true) } })
            root.addView(r)
        }
    }

    private fun resolve(sc: StreamSource, s: SShow, e: SEp, download: Boolean) {
        Notice.show(act, "⏳ بجيب لينك الفيديو…", 1800L)
        bg {
            val st = try { sc.streams(e) } catch (x: Throwable) { onUi { Notice.show(act, "⚠ " + (x.message ?: "فشل").take(80), 3000L) }; return@bg }
            onUi {
                if (st.isEmpty()) Notice.show(act, "⚠ ملقيتش لينك فيديو للحلقة دي", 3000L)
                else if (st.size == 1) act1(s, e, st[0], download)
                else GAlert(act).setTitle("اختار").setItems(st.map { it.label + " · " + it.url.substringAfter("//").take(40) }.toTypedArray()) { _, i -> act1(s, e, st[i], download) }.setNegativeButton("إغلاق", null).show()
            }
        }
    }

    private fun act1(s: SShow, e: SEp, st: SStream, download: Boolean) {
        val title = s.title + " - " + e.title
        if (download) {
            val ext = if (st.url.contains(".m3u8")) "mp4" else st.url.substringBefore('?').substringAfterLast('.', "mp4").take(4).ifEmpty { "mp4" }
            val name = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80) + "." + ext
            DmTasks.start(act.applicationContext, st.url, name, null, StreamerNet.UA, null, st.ref)
            Notice.show(act, "⬇ اتضاف للتحميل — تابعه من المهام: " + name, 2600L)
        } else {
            act.startActivity(Intent(act, PlayerActivity::class.java).pipTask(act).apply {
                putExtra("url", st.url); putExtra("ref", st.ref); putExtra("ua", StreamerNet.UA); putExtra("title", title)
                putExtra("landscape", true); putExtra("nosub", true)
            })
        }
    }

    private fun addDialog() {
        val inp = ui.input("الصق JSON السورس أو لينك ملف JSON", "", 6)
        val msg = ui.text("", 12f, th.danger)
        var d2: Dialog? = null
        val save = ui.button("حفظ", true) {
            val t = inp.text.toString()
            msg.text = "⏳ …"
            bg {
                val err = StreamerSources.add(act, t)
                onUi {
                    if (err == null) { d2?.dismiss(); srcs = StreamerSources.all(act) + ext; cur = srcs.size - 1; results = emptyList(); runSearch() }
                    else msg.text = "⚠ " + err
                }
            }
        }
        d2 = ui.sheet(act, "➕ سورس جديد", listOf<View>(
            ui.text("مثال: {\"name\":\"موقعي\",\"base\":\"https://site\",\"search\":{\"url\":\"https://site/?s={q}\",\"item\":\"<a href=\\\"(?<url>[^\\\"]+)\\\"[^>]*>(?<title>[^<]+)\"},\"episodes\":{\"item\":\"...\"}}", 11f, th.muted),
            inp, msg, save))
        d2?.show()
    }
}
