package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.io.File

/**
 * (Tube) نسخة يوتيوب المستقلة: التطبيق كله هو واجهة يوتيوب الأصلية (YtProUi على NewPipeExtractor)
 * + المشغّل الداخلي في وضع «Tube» المقفول على المشاهدة (من غير ترجمة — راجع Player.tube).
 * التشغيل من غير إعلانات لأن الروابط بتتاخد مباشرة من يوتيوب (استخراج) مش من صفحة الويب.
 * الإعدادات الخاصة بيوتيوب (الجودة/السجل/المسح) جوه الواجهة نفسها «⚙ إعدادات يوتيوب»،
 * وشريط العنوان فوق فيه مفتاح الثيم والداكن/الفاتح بس.
 */
class TubeActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var th: Theme
    private var yt: YtProUi? = null
    private lateinit var recentFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.init(this); CrashLog.install(this); YtFast.startup()
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        recentFile = File(filesDir, "recent.json")

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        // شريط عنوان نحيف: اسم التطبيق + ثيم (الثيمات والداكن من نفس نظام Subtitler)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), 0, ui.dp(8), 0) }
        bar.addView(ui.text("▶ Tube", 17f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ui.button("🎨") { themeSheet() }, LinearLayout.LayoutParams(-2, ui.dp(48)))
        page.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(48)))

        val host = FrameLayout(this)
        val y = YtProUi(this, ui, th,
            { Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }).associateBy { it.id } },
            { id, name, _ -> playYt(id, name) },
            { u -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) {} },
            { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, 47) },
            { n -> playYt(n.id, n.title) },
            { n, a -> download(n, a) },
            { YtHistoryUi.show(this, ui, th, { id, t -> playYt(id, t) }, { id, t, _ -> playYt(id, t) }) },
            onPlayAt = { id, name, _, ms, pk -> playYt(id, name, ms, pk) })
        yt = y
        host.addView(y.root, FrameLayout.LayoutParams(-1, -1))
        host.addView(y.busyView, FrameLayout.LayoutParams(-1, -1))
        page.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        handleLink(intent)
    }

    /** لينكات يوتيوب من برامج تانية (VIEW/SEND) بتتفتح هنا على طول */
    private fun handleLink(i: Intent?) {
        if (i == null) return
        val raw = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.data?.toString()
            else -> null
        } ?: return
        val id = YtExtract.videoId(raw) ?: return
        yt?.openVideo(id)
    }

    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); if (i != null) { intent = i; handleLink(i) } }

    /** تشغيل فيديو يوتيوب في المشغّل الداخلي — دايمًا وضع Tube (من غير ترجمة) */
    fun playYt(id: String, title: String, startMs: Long = 0L, handoff: YtPick? = null) {
        if (handoff != null) {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("handoff", true)
                putExtra("url", handoff.url); putExtra("aurl", handoff.audio ?: ""); putExtra("ref", "https://www.youtube.com/"); putExtra("ua", handoff.ua)
                if (handoff.qlist.isNotEmpty()) putExtra("qlist", handoff.qlist)
                putExtra("title", title.ifBlank { handoff.title }); putExtra("ytid", id)
                putExtra("tube", true); putExtra("nosub", true); putExtra("autotr", false); if (startMs > 0L) putExtra("startms", startMs)
            })
            return
        }
        if (id.length != 11) { playYtLegacy(id, title, startMs); return }
        // (v351) المشغّل بيفتح فورًا وبيجيب رابط الفيديو جواه
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("ytpend", true); putExtra("ytid", id); putExtra("title", title)
            putExtra("ref", "https://www.youtube.com/")
            putExtra("tube", true); putExtra("nosub", true); putExtra("autotr", false); if (startMs > 0L) putExtra("startms", startMs)
        })
    }

    /** معرفات مش قياسية: بنجيب الرابط بره الأول (YtExtract وبعدين NewPipe) */
    private fun playYtLegacy(id: String, title: String, startMs: Long) {
        Notice.show(this, "⏳ بجيب الفيديو…", 1500L)
        Thread {
            val best = try { YtExtract.fetchPick(id, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null }
            val np = if (best == null) try { YtNp.init(); YtNp.pick(id, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null } else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    best != null -> startActivity(Intent(this, PlayerActivity::class.java).apply {
                        putExtra("url", best.url); putExtra("aurl", best.audio ?: ""); putExtra("qlist", best.optsJson()); putExtra("ref", "https://www.youtube.com/"); putExtra("ua", best.ua)
                        putExtra("title", title.ifBlank { best.title }); putExtra("ytid", id)
                        putExtra("tube", true); putExtra("nosub", true); putExtra("autotr", false); if (startMs > 0L) putExtra("startms", startMs)
                    })
                    np != null -> startActivity(Intent(this, PlayerActivity::class.java).apply {
                        putExtra("url", np.url); putExtra("aurl", np.audio ?: ""); putExtra("ref", "https://www.youtube.com/"); putExtra("ua", np.ua)
                        if (np.qlist.isNotEmpty()) putExtra("qlist", np.qlist)
                        putExtra("title", title.ifBlank { np.title }); putExtra("ytid", id)
                        putExtra("tube", true); putExtra("nosub", true); putExtra("autotr", false); if (startMs > 0L) putExtra("startms", startMs)
                    })
                    else -> GAlert(this).setTitle("⚠ ما قدرتش أجيب الفيديو")
                        .setMessage("يوتيوب ما رضيش يدّي لينك للفيديو ده من التطبيق.\n\n" + YtExtract.lastWhy.ifBlank { "السبب مش معروف" })
                        .setPositiveButton("🔁 جرّب تاني") { _, _ -> playYtLegacy(id, title, startMs) }
                        .setNeutralButton("🌐 افتحه بره") { _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YtHistory.watchUrl(id)))) } catch (_: Exception) {} }
                        .setNegativeButton("إغلاق", null).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** تحميل مباشر (من غير مدير مهام): خيط خلفي + إشعارات بداية/نهاية، وبيتسجّل في «تحميلاتي» جوه يوتيوب */
    private fun download(n: NpItem, audioOnly: Boolean) {
        val app = applicationContext
        val nm = n.title.ifBlank { n.id }
        Notice.show(this, "⬇ بدأ التحميل: " + nm.take(40), 2500L)
        Thread {
            val msg = try {
                YtNp.init()
                val p = (if (audioOnly) YtNp.pickAudio(n.id) else YtNp.pick(n.id, Cfg.int("yt_maxh", 0))) ?: throw java.io.IOException("ما قدرتش أجيب رابط التحميل")
                val hs = mapOf("User-Agent" to p.ua, "Referer" to "https://www.youtube.com/")
                val job = if (audioOnly) Downloader.Job(nm, p.url, null, false, 1, hs, true)
                          else Downloader.Job(nm, p.url, p.audio, p.url.contains(".m3u8", true), p.h, hs)
                val res = Downloader.run(app, job) { _, _ -> }
                if (!res.first) throw java.io.IOException(res.second)
                try { YtStore.addFront(YtStore.DL, NpItem(n.id, n.title, n.channel, n.channelUrl, n.thumb, n.dur, -1L, "⬇ " + (if (audioOnly) "صوت" else "فيديو") + " · " + res.second, 0, res.second), 300) } catch (_: Throwable) {}
                "✅ خلص التحميل: " + nm.take(40)
            } catch (e: Exception) { "⚠ التحميل فشل: " + (e.message ?: "") }
            runOnUiThread { if (!isFinishing && !isDestroyed) Notice.show(this, msg, 3500L) }
        }.apply { isDaemon = true }.start()
    }

    /** الثيم + داكن/فاتح (نفس ثيمات Subtitler — الافتراضي كريمي دافئ من غير أخضر) */
    private fun themeSheet() {
        var themeId = Cfg.str("theme", "mx")
        val chips = ui.chips(Themes.all.map { it.name }, { Themes.byId(themeId).name }) { n ->
            themeId = Themes.all.first { it.name == n }.id
            Cfg.p.edit().putString("theme", themeId).apply(); recreate()
        }
        val dark = ui.switchRow("🌙 الوضع الداكن", !Themes.byId(themeId).isLight) { d ->
            if (d == !Themes.byId(Cfg.str("theme", "mx")).isLight) return@switchRow
            Cfg.p.edit().putString("theme", Themes.partner(Cfg.str("theme", "mx"))).apply(); recreate()
        }
        ui.sheet(this, "🎨 الثيم", listOf(chips, dark)).show()
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 47 && c == RESULT_OK) d?.data?.let { yt?.importUri(it) }   // ملف اشتراكات يوتيوب
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (yt?.back() == true) return
        super.onBackPressed()
    }

    override fun onResume() { super.onResume(); yt?.refresh() }
    override fun onPause() { yt?.onHide(); super.onPause() }
}
