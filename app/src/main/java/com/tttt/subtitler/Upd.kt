package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** (v1259) مركز التحديث الجديد: فحص الريليز عند كل فتح، تنزيل جوه البرنامج بشريط تقدّم وقابل للاستكمال، تثبيت مباشر لو الملف منزّل، وبادج أحمر. */
object Upd {
    private const val API = "https://api.github.com/repos/alisaeed9x/Joyboy/releases/latest"

    class AppInfo(val name: String, val tag: String, val url: String, val size: Long, val notes: String, val page: String)

    @Volatile var app: AppInfo? = null
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile private var lastCheck = 0L
    @Volatile private var checking = false
    @Volatile private var promptedTag = ""

    val count: Int get() = (if (app != null) 1 else 0)
    fun autoOn(): Boolean = Cfg.bool("upd_auto", false)

    private fun main() = Handler(Looper.getMainLooper())
    private fun changed() { main().post { try { onChange?.invoke() } catch (_: Throwable) {} } }

    private fun instCode(c: Context): Long = try { val pi = c.packageManager.getPackageInfo(c.packageName, 0); if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong() } catch (_: Throwable) { 0L }

    @Suppress("DEPRECATION")
    private fun archCode(c: Context, f: File): Long? = try {
        val pi = c.packageManager.getPackageArchiveInfo(f.path, 0)
        if (pi == null || pi.packageName != c.packageName) null else if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (_: Throwable) { null }

    private fun dir(c: Context): File = File(c.cacheDir, "upd").apply { mkdirs() }
    private fun appFile(c: Context, ap: AppInfo): File = File(dir(c), "app-" + ap.tag.filter { it.isLetterOrDigit() || it == '.' || it == '-' } + ".apk")
    private fun appReady(c: Context, f: File): Boolean { val v = if (f.isFile) archCode(c, f) else null; return v != null && v > instCode(c) }
    private fun appVerify(c: Context, f: File): String? { val v = archCode(c, f); return if (v == null) "الملف مش APK صالح لـ Joyboy" else if (v <= instCode(c)) "الملف مش أحدث من اللي عندك" else null }

    private fun verName(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }

    private fun netOk(c: Context): Boolean {
        if (!GhCi.wifiOnly()) return true
        return try { !(c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered } catch (_: Throwable) { true }
    }

    /** ملفات التحديث القديمة (اللي بقت هي المثبّتة أو أقدم) بتتمسح */
    private fun cleanOld(c: Context) {
        try {
            val inst = instCode(c)
            for (f in dir(c).listFiles() ?: emptyArray()) {
                if (!f.name.startsWith("app-")) continue
                if (f.name.endsWith(".part") || Regex("\\.p\\d$").containsMatchIn(f.name)) continue
                val v = archCode(c, f)
                if (v == null || v <= inst) { f.delete(); File(f.path + ".part").delete() }
            }
        } catch (_: Throwable) {}
    }

    private fun fetchAll(a: Context) {
        try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "Joyboy")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val name = j.optString("name"); val tag = j.optString("tag_name")
            var apk = ""; var size = 0L
            val assets = j.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) { val o = assets.getJSONObject(i); if (o.optString("name").endsWith(".apk")) { apk = o.optString("browser_download_url"); size = o.optLong("size"); break } }
            val ver = verName(a)
            val remote = UpdateSheet.verParts(name)
            if (apk.isNotBlank() && remote.isNotEmpty() && ver.isNotBlank() && UpdateSheet.isNewer(remote, UpdateSheet.verParts(ver))) {
                val notes = UpdateSheet.cumulative(ver).ifBlank { UpdateSheet.clean(j.optString("body")) }.ifBlank { "مفيش ملاحظات للنسخة دي." }
                app = AppInfo(name, tag, apk, size, notes, j.optString("html_url", "https://github.com/alisaeed9x/Joyboy/releases/latest"))
            } else app = null
        } catch (e: Throwable) { LogStore.err("Upd:app", e) }
    }

    /** بيتنادى من Main.onResume: فحص عند كل فتح (مع فاصل 5 دقايق) وبعدها يقرر يسأل/ينزّل */
    fun onOpen(a: Activity, force: Boolean = false) {
        if (DeviceUi.isTv(a)) return
        val now = System.currentTimeMillis()
        if (checking || (!force && now - lastCheck < 300_000L)) return
        checking = true; lastCheck = now
        Thread {
            try { cleanOld(a); fetchAll(a) } catch (e: Throwable) { LogStore.err("Upd:check", e) }
            checking = false
            changed()
            main().post { try { decide(a) } catch (e: Throwable) { LogStore.err("Upd:decide", e) } }
        }.apply { isDaemon = true; start() }
    }

    private fun decide(a: Activity) {
        val ap = app ?: return
        if (a.isFinishing || a.isDestroyed || promptedTag == ap.tag) return
        val f = appFile(a, ap)
        if (appReady(a, f)) { promptedTag = ap.tag; installPrompt(a, ap, f); return }
        if (Cfg.str("upd_skip") == ap.tag) return
        promptedTag = ap.tag
        if (autoOn() && netOk(a)) startApp(a) else offerPrompt(a, ap)
    }

    private fun offerPrompt(a: Activity, ap: AppInfo) {
        val th = Themes.current(); val ui = Ui(a, th)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) }
        col.addView(TvUpdate.flowView(a, "v" + verName(a), shortVer(ap.name)))
        if (ap.size > 0) col.addView(ui.text("حجم التحديث: " + mb(ap.size), 13f, th.muted))
        col.addView(ui.text("إيه اللي اتغيّر:", 15f, th.text, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        col.addView(Notes.render(a, ui, th, ap.notes), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        GAlert(a).setTitle("🔔 فيه تحديث جديد").setView(col)
            .setNegativeButton("مش دلوقتي") { _, _ -> Cfg.put("upd_skip", ap.tag) }
            .setPositiveButton("تحديث") { _, _ -> startApp(a) }.show()
    }

    private fun installPrompt(a: Activity, ap: AppInfo, f: File) {
        try {
            val th = Themes.current(); val ui = Ui(a, th)
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) }
            col.addView(TvUpdate.flowView(a, "v" + verName(a), shortVer(ap.name)))
            col.addView(ui.text("التحديث منزّل وجاهز، مش هيتنزّل تاني.", 13f, th.muted))
            GAlert(a).setTitle("⬆ فيه تحديث، تثبيت؟").setView(col)
                .setNegativeButton("لاحقًا", null)
                .setPositiveButton("تثبيت") { _, _ -> TvUpdate.install(a, f) }.show()
        } catch (e: Throwable) { LogStore.err("Upd:installPrompt", e) }
    }

    /** زرار «تحديث»: لو الملف منزّل بيثبّت على طول، غير كده بينزّل بشريط التقدّم وبعدها يسأل «تثبيت؟» */
    fun startApp(a: Activity) {
        val ap = app ?: return
        val f = appFile(a, ap)
        if (appReady(a, f)) { TvUpdate.install(a, f); return }
        downloadUi(a, "app-" + ap.tag, ap.url, f, ap.size, "⬇ تحديث Joyboy", "v" + verName(a), shortVer(ap.name), { appVerify(a, it) }) { file -> installPrompt(a, ap, file) }
    }

    /** زرار تحديث/تثبيت بلاجن: نفس تجربة التنزيل. لو الملف منزّل بنفس النسخة بيثبّته مباشرة */

    // ───────── التنزيل القابل للاستكمال ─────────
    class Job(val url: String, val dest: File, val verify: (File) -> String?) {
        @Volatile var done = 0L; @Volatile var total = 0L; @Volatile var err: String? = null
        @Volatile var running = false; @Volatile var finished = false; @Volatile var stop = false
        @Volatile var startMs = 0L; @Volatile var startBytes = 0L
        @Volatile var onDone: ((File) -> Unit)? = null
        fun part(): File = File(dest.path + ".part")
    }
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private const val SEGS = 4

    /** (v1268) تنزيل بـ4 اتصالات متوازية (Range) في ملفات أجزاء بتتكمّل لوحدها، ثم بتتجمّع. false = السيرفر مش بيدعم Range فنكمّل بالطريقة العادية */
    private fun pullParallel(j: Job): Boolean {
        val probe = URL(j.url).openConnection() as HttpURLConnection
        var total = -1L
        try {
            probe.connectTimeout = 15000; probe.readTimeout = 30000; probe.instanceFollowRedirects = true
            probe.setRequestProperty("User-Agent", "Joyboy"); probe.setRequestProperty("Range", "bytes=0-0")
            if (probe.responseCode == 206) total = probe.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
        } finally { try { probe.disconnect() } catch (_: Throwable) {} }
        if (total < 2L * 1048576L) return false
        j.total = total
        val sz = (total + SEGS - 1) / SEGS
        val files = (0 until SEGS).map { File(j.dest.path + ".p" + it) }
        j.dest.parentFile?.mkdirs()
        val lens = LongArray(SEGS) { i -> minOf(sz, total - i * sz) }
        var have0 = 0L
        for (i in 0 until SEGS) { val f = files[i]; if (f.isFile && f.length() > lens[i]) f.delete(); if (f.isFile) have0 += f.length() }
        j.done = have0; j.startBytes = have0; j.startMs = System.currentTimeMillis()
        val errs = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val ths = (0 until SEGS).map { i ->
            Thread {
                try {
                    val f = files[i]; var have = if (f.isFile) f.length() else 0L
                    if (have >= lens[i]) return@Thread
                    val c = URL(j.url).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                        c.setRequestProperty("User-Agent", "Joyboy")
                        val st = i * sz + have; val en = i * sz + lens[i] - 1
                        c.setRequestProperty("Range", "bytes=$st-$en")
                        if (c.responseCode != 206) throw IOException("التنزيل فشل (HTTP " + c.responseCode + ")")
                        c.inputStream.use { ins ->
                            FileOutputStream(f, true).use { out ->
                                val b = ByteArray(131072)
                                while (have < lens[i]) {
                                    if (j.stop) return@Thread
                                    val n = ins.read(b)
                                    if (n < 0) break
                                    out.write(b, 0, n); have += n
                                    synchronized(j) { j.done += n }
                                }
                            }
                        }
                    } finally { try { c.disconnect() } catch (_: Throwable) {} }
                } catch (e: Throwable) { errs.add(e) }
            }.apply { isDaemon = true; start() }
        }
        for (t in ths) t.join()
        if (j.stop) throw IOException("⏸ التنزيل واقف (الجزء المنزّل محفوظ)")
        if (errs.isNotEmpty()) throw IOException(errs[0].message ?: "التنزيل فشل")
        for (i in 0 until SEGS) if (files[i].length() != lens[i]) throw IOException("التنزيل اتقطع (الجزء المنزّل محفوظ)")
        val part = j.part()
        FileOutputStream(part, false).use { out ->
            val b = ByteArray(262144)
            for (f in files) f.inputStream().use { ins -> while (true) { val n = ins.read(b); if (n < 0) break; out.write(b, 0, n) } }
        }
        for (f in files) f.delete()
        val bad = j.verify(part)
        if (bad != null) { part.delete(); throw IOException(bad + " (اتمسح، دوس «تنزيل من الأول»)") }
        if (j.dest.exists()) j.dest.delete()
        if (!part.renameTo(j.dest)) throw IOException("مقدرتش أحفظ الملف")
        return true
    }

    private fun pull(j: Job) {
        val part = j.part(); part.parentFile?.mkdirs()
        var have = if (part.isFile) part.length() else 0L
        if (have == 0L) { try { if (pullParallel(j)) return } catch (e: IOException) { throw e } catch (_: Throwable) {} }
        val con = URL(j.url).openConnection() as HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 30000; con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", "Joyboy")
        if (have > 0L) con.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = con.responseCode
            var append = false
            if (code == 206) {
                append = true
                j.total = con.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: (have + (con.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L))
            } else if (code == 200) {
                have = 0L
                val cl = (con.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L)
                if (cl > 0L) j.total = cl
            } else if (code == 416) {
                part.delete()
                throw IOException("الجزء المنزّل قبل كده مش صالح، دوس «تنزيل من الأول»")
            } else throw IOException("التنزيل فشل (HTTP $code)")
            j.done = have; j.startBytes = have; j.startMs = System.currentTimeMillis()
            con.inputStream.use { ins ->
                FileOutputStream(part, append).use { out ->
                    val b = ByteArray(65536)
                    while (true) {
                        if (j.stop) break
                        val n = ins.read(b)
                        if (n < 0) break
                        out.write(b, 0, n); j.done += n
                    }
                }
            }
        } finally { try { con.disconnect() } catch (_: Throwable) {} }
        if (j.stop) throw IOException("⏸ التنزيل واقف (الجزء المنزّل محفوظ)")
        if (j.total > 0L && part.length() != j.total) throw IOException("التنزيل اتقطع عند " + mb(part.length()) + " من " + mb(j.total) + " (الجزء المنزّل محفوظ)")
        val bad = j.verify(part)
        if (bad != null) { part.delete(); throw IOException(bad + " (اتمسح، دوس «تنزيل من الأول»)") }
        if (j.dest.exists()) j.dest.delete()
        if (!part.renameTo(j.dest)) throw IOException("مقدرتش أحفظ الملف")
    }

    @Volatile var lastResult: String? = null
    internal fun svcState(): LongArray? {
        val r = jobs.values.filter { it.running }
        if (r.isEmpty()) return null
        return longArrayOf(r.sumOf { it.done }, r.sumOf { it.total })
    }
    private var appCtx: Context? = null

    private fun startJob(j: Job) {
        if (j.running) return
        j.running = true; j.stop = false; j.err = null; j.finished = false; lastResult = null
        try { appCtx?.let { UpdDlService.start(it) } } catch (_: Throwable) {}
        Thread {
            try {
                pull(j)
                j.finished = true
            } catch (e: Throwable) { j.err = e.message ?: e.javaClass.simpleName }
            lastResult = if (j.finished) "ok" else if (j.stop) "stop" else (j.err ?: "فشل")
            j.running = false
            if (j.finished) main().post { try { j.onDone?.invoke(j.dest) } catch (x: Throwable) { LogStore.err("Upd:onDone", x) } }
        }.apply { isDaemon = true; start() }
    }

    fun shortVer(n: String): String = Regex("\\d+(?:\\.\\d+)+").find(n)?.value?.let { "v" + it } ?: n
    fun mb(n: Long): String = if (n >= 1048576L) String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0) else (n / 1024L).toString() + " KB"
    private fun clock(s: Long): String = String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)

    fun downloadUi(a: Activity, key: String, url: String, dest: File, totalHint: Long, title: String, curName: String, newName: String, verify: (File) -> String?, onDone: (File) -> Unit) {
        appCtx = a.applicationContext
        val j = jobs.getOrPut(key) { Job(url, dest, verify) }
        j.onDone = onDone
        if (j.total <= 0L) j.total = totalHint
        if (j.finished && dest.isFile) { onDone(dest); return }
        val th = Themes.current(); val ui = Ui(a, th); val hh = main()
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        col.addView(TvUpdate.flowView(a, curName, newName))
        val st = ui.text("", 15f, th.text, true)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            try { progressTintList = android.content.res.ColorStateList.valueOf(th.primary) } catch (_: Throwable) {}
        }
        val info = ui.text("", 12.5f, th.muted)
        val btns = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(st, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        col.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(12)).apply { topMargin = ui.dp(8) })
        col.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        col.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        var shown = true
        var lastMode = ""
        lateinit var dlg: Dialog
        fun paint() {
            val mode = if (j.finished) "done" else if (j.running) "run" else if (j.err != null) "err" else "idle"
            if (mode != lastMode) {
                lastMode = mode; btns.removeAllViews()
                if (mode == "run") btns.addView(ui.button("⏸ إيقاف (الجزء المنزّل يتحفظ)") { j.stop = true })
                if (mode == "err" || mode == "idle") {
                    if (j.part().isFile && j.part().length() > 0L) btns.addView(ui.button("▶ استكمال", true) { startJob(j) })
                    else btns.addView(ui.button("⬇ ابدأ التنزيل", true) { startJob(j) })
                    btns.addView(ui.button("🔄 تنزيل من الأول") { j.part().delete(); for (k in 0 until SEGS) File(j.dest.path + ".p" + k).delete(); j.done = 0L; startJob(j) })
                }
            }
            val total = j.total; val done = j.done
            if (mode == "done") { try { dlg.dismiss() } catch (_: Throwable) {}; return }
            if (mode == "run") {
                val pct = if (total > 0L) (done * 100 / total).toInt() else 0
                st.text = "⬇ بينزّل… " + (if (total > 0L) "$pct%" else "")
                bar.isIndeterminate = total <= 0L; bar.progress = if (total > 0L) (done * 1000 / total).toInt() else 0
                val el = (System.currentTimeMillis() - j.startMs).coerceAtLeast(1L)
                val sp = (done - j.startBytes) * 1000L / el
                val left = if (sp > 0L && total > done) (total - done) / sp else -1L
                fun seg(l: String, v: String) = "\u200F" + l + ": \u2066" + v + "\u2069"
                val parts = ArrayList<String>()
                parts.add(seg("الحجم", if (total > 0L) mb(done) + " / " + mb(total) else mb(done)))
                if (sp > 0L) parts.add(seg("السرعة", mb(sp) + "/ث"))
                if (left >= 0L) parts.add(seg("المتبقي", clock(left)))
                info.text = parts.joinToString("  \u200F-  ")
            } else {
                val have = if (j.part().isFile) j.part().length() else 0L
                st.text = j.err ?: "جاهز للتنزيل"
                bar.isIndeterminate = false; bar.progress = if (total > 0L) (have * 1000 / total).toInt() else 0
                info.text = if (have > 0L) "منزّل: \u2066" + mb(have) + (if (total > 0L) " / " + mb(total) else "") + "\u2069 - هيكمّل من نفس النقطة" else "مفيش جزء منزّل، هيبدأ من الأول"
            }
        }
        val tick = object : Runnable { override fun run() { if (!shown) return; try { paint() } catch (_: Throwable) {}; hh.postDelayed(this, 400L) } }
        dlg = GAlert(a).setTitle(title).setView(col).setCancelable(false)
            .setNegativeButton("إخفاء") { _, _ -> shown = false }.show()
        try { dlg.setOnDismissListener { shown = false } } catch (_: Throwable) {}
        if (!j.running) startJob(j)
        paint(); hh.postDelayed(tick, 400L)
    }

    /** كتلة مركز التحديث (بتتحط في الشكلين): مفتاح «تحديث تلقائي» + حالة التحديث + زراير تحديث البرنامج/البلاجن */
    fun centerViews(a: Activity, ui: Ui, th: Theme): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        if (DeviceUi.isTv(a)) return col
        col.addView(ui.switchRow("🔄 تحديث تلقائي (ينزّل لوحده ويسأل تثبّت؟)", autoOn()) { v -> Cfg.put("upd_auto", if (v) "1" else "0") })
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(box)
        fun render() {
            box.removeAllViews()
            val ap = app
            if (ap == null) box.addView(ui.text(if (checking) "⏳ بيفحص…" else "✅ مفيش تحديثات جديدة", 13f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) })
            if (ap != null) {
                val ready = appReady(a, appFile(a, ap))
                box.addView(ui.button((if (ready) "✅ تثبيت التحديث (منزّل): " else "⬆ تحديث Joyboy: ") + ap.name + (if (!ready && ap.size > 0) "  (" + mb(ap.size) + ")" else ""), true) { startApp(a) })
            }
        }
        render()
        return col
    }
}
