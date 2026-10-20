package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v461/v462) شاشة التلفزيون على تقسيمة يوتيوب TV الرسمية:
 * شريط علوي (الشعار · بحث · ساعة) + ريل تنقّل جانبي + محتوى بصفوف أفقية
 * (كمّل مشاهدة · المفضلة · أحدث الفيديوهات) بكروت فيديو 16:9 بتكبر مع التركيز.
 * بحث موحّد (مكتبة + أخيرة + يوتيوب) بصوت الريموت أو الكتابة من الموبايل (TypeServer)،
 * قفل PIN برقم سري على أقسام، تخصيص إظهار الصفوف، دفء ليلي، ووضع سكون.
 */
class TvActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var th: Theme
    private lateinit var content: LinearLayout
    private lateinit var saver: FrameLayout
    private lateinit var saverClock: android.widget.TextView
    private lateinit var clockTv: android.widget.TextView
    private lateinit var warmView: View
    private val h = Handler(Looper.getMainLooper())
    private var idleSec = 0
    private var favs: LinkedHashSet<String> = LinkedHashSet()
    private var lastScan: List<VideoItem> = emptyList()
    private var firstRail: View? = null

    private fun favLoad() { favs = LinkedHashSet(Cfg.str("tv_favs", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }) }
    private fun favSave() { Cfg.put("tv_favs", favs.joinToString(",")) }
    private fun favToggle(id: String, title: String) {
        if (!favs.remove(id)) { favs.add(id); Notice.show(this, "⭐ اتضافت للمفضلة: $title", 1600L) } else Notice.show(this, "⭐ اتشالت من المفضلة", 1400L)
        favSave(); showHome()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this); favLoad()
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        TypeServer.start(); TvWatchNext.sync(this)
        val rootF = FrameLayout(this).apply { setBackgroundColor(th.bg) }
        val rootCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        rootCol.addView(topBar(), LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        body.addView(rail(), LinearLayout.LayoutParams(ui.dp(92), -1))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(30))
        }
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        rootCol.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        rootF.addView(rootCol, FrameLayout.LayoutParams(-1, -1))
        warmView = View(this).apply { setBackgroundColor(0x2EFF8C1A); isClickable = false; isFocusable = false; visibility = if (Cfg.str("tv_warm", "0") == "1") View.VISIBLE else View.GONE }
        rootF.addView(warmView, FrameLayout.LayoutParams(-1, -1))
        saver = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        saverClock = ui.text("", 54f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val saverCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        saverCol.addView(saverClock, LinearLayout.LayoutParams(-1, -2))
        saverCol.addView(ui.text("📺 Subtitler — دوس أي زرار عشان تكمل", 15f, 0xFF9AA0A6.toInt()).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        saver.addView(saverCol, FrameLayout.LayoutParams(-1, -1))
        rootF.addView(saver, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootF)
        showHome()
        h.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date())
            if (::clockTv.isInitialized) clockTv.text = "🕐 $t"
            if (saver.visibility == View.VISIBLE) saverClock.text = t else { idleSec++; if (idleSec >= 180) showSaver() }
            h.postDelayed(this, 1000)
        }
    }

    private fun showSaver() { saver.visibility = View.VISIBLE; idleSec = 0 }
    private fun wake() { idleSec = 0; if (::saver.isInitialized && saver.visibility == View.VISIBLE) saver.visibility = View.GONE }
    override fun onUserInteraction() { super.onUserInteraction(); wake() }
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean { wake(); return super.dispatchKeyEvent(ev) }
    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }

    // ===== الشريط العلوي والريل (تقسيمة يوتيوب TV) =====
    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(8))
        }
        bar.addView(ui.text("📺 Subtitler", 22f, th.primary, true))
        val searchChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(ui.dp(16), ui.dp(9), ui.dp(16), ui.dp(9)); background = ui.box(th.surface, th.border, 22)
            setOnClickListener { showSearch() }
            setOnFocusChangeListener { v, f -> v.background = ui.box(if (f) th.card else th.surface, if (f) th.primary else th.border, 22) }
        }
        searchChip.addView(ui.text("🔍  ابحث في الفيديوهات ويوتيوب…", 13.5f, th.muted))
        bar.addView(searchChip, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(18), 0, ui.dp(18), 0) })
        clockTv = ui.text("", 15f, th.text, true)
        bar.addView(clockTv)
        return bar
    }

    private fun railBtn(icon: String, label: String, first: Boolean = false, onClick: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            setPadding(ui.dp(4), ui.dp(9), ui.dp(4), ui.dp(9)); background = ui.box(th.bg, Color.TRANSPARENT, 12)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f -> v.background = ui.box(if (f) th.card else th.bg, if (f) th.primary else Color.TRANSPARENT, 12) }
        }
        c.addView(ui.text(icon, 19f, th.text).apply { gravity = Gravity.CENTER })
        c.addView(ui.text(label, 10.5f, th.muted, true).apply { gravity = Gravity.CENTER })
        if (first) firstRail = c
        c.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(6), ui.dp(3), ui.dp(6), ui.dp(3)) }
        return c
    }

    private fun rail(): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, ui.dp(8), 0, ui.dp(8)); setBackgroundColor(th.card)
        }
        r.addView(railBtn("🏠", "الرئيسية", true) { showHome() })
        r.addView(railBtn("🔍", "بحث") { showSearch() })
        r.addView(railBtn("▶", "يوتيوب") { openMain("youtube") })
        r.addView(railBtn("🎬", "الفيديوهات") { openMain("videos") })
        r.addView(railBtn("🎵", "الموسيقى") { openMain("music") })
        r.addView(railBtn("📂", "الملفات") { guardPin { startActivity(Intent(this, FileManagerActivity::class.java)) } })
        r.addView(railBtn("📋", "المهام") { openMain("tasks") })
        r.addView(railBtn("🎛", "الصفوف") { rowsDialog() })
        r.addView(railBtn("🔒", "قفل TV") { pinMenu() })
        r.addView(railBtn("🌡", "دفء ليلي") {
            val on = Cfg.str("tv_warm", "0") != "1"
            Cfg.put("tv_warm", if (on) "1" else "0"); warmView.visibility = if (on) View.VISIBLE else View.GONE
            Notice.show(this, if (on) "🌡 الدفء الليلي اشتغل" else "🌡 الدفء الليلي اتقفل", 1400L)
        })
        r.addView(railBtn("⚙️", "الإعدادات") { guardPin { openMain("settings") } })
        r.addView(railBtn("🔁", "الواجهة") {
            val nx = DeviceUi.cycleNext(this); Cfg.put("ui_mode", nx)
            Notice.show(this, "🖥 الواجهة الجاية: " + DeviceUi.label(nx), 1500L)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
        })
        r.addView(railBtn("🌙", "نوم") { showSaver() })
        r.addView(railBtn("📱", "الموبايل") { openMain("videos") })
        val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(r) }
        return sc
    }

    // ===== الكروت والصفوف =====
    private fun card(wDp: Int, hDp: Int, fill: (LinearLayout) -> Unit, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9)); background = ui.box(th.surface, th.border, 14)
        }
        c.setOnFocusChangeListener { v, f ->
            v.background = ui.box(if (f) th.card else th.surface, if (f) th.primary else th.border, 14)
            v.animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).setDuration(130).start()
            v.elevation = if (f) ui.dp(8).toFloat() else 0f
        }
        c.setOnClickListener { onClick() }
        if (onLong != null) c.setOnLongClickListener { onLong(); true }
        fill(c)
        c.layoutParams = LinearLayout.LayoutParams(ui.dp(wDp), ui.dp(hDp)).apply { setMargins(ui.dp(7), ui.dp(6), ui.dp(7), ui.dp(6)) }
        return c
    }

    private fun rowInto(parent: LinearLayout, title: String, cards: List<View>) {
        if (cards.isEmpty()) return
        parent.addView(ui.text(title, 17f, th.primary, true).apply { setPadding(ui.dp(6), ui.dp(14), 0, ui.dp(2)) })
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        cards.forEach { inner.addView(it) }
        parent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(inner) }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun recents(): List<Recent> = Recents.parse(try { File(filesDir, "recent.json").readText() } catch (_: Exception) { "" })

    private fun recentCard(r: Recent): View = card(252, 120, { c ->
        c.addView(ui.text(r.title, 14.5f, th.text, true).apply { maxLines = 2 })
        c.addView(ui.text("⏸ وقف عند " + PlayerLogic.clock((r.posSec * 1000).toLong()) + " · تغطية " + r.percent + "%" + if (favs.contains(r.id)) " · ⭐" else "", 11.5f, th.muted))
        val track = FrameLayout(this).apply { background = ui.box(th.border, th.border, 4) }
        track.addView(View(this).apply { setBackgroundColor(th.primary) }, FrameLayout.LayoutParams(ui.dp((232 * r.percent.coerceIn(0, 100) / 100).coerceAtLeast(0)), ui.dp(5)))
        c.addView(track, LinearLayout.LayoutParams(-1, ui.dp(5)).apply { topMargin = ui.dp(8) })
    }, { playRecent(r) }, { favToggle(r.id, r.title) })

    private fun videoCard(v: VideoItem): View {
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.border) }
        val c = card(232, 172, { cc ->
            cc.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(106)))
            cc.addView(ui.text(v.title + if (favs.contains(v.videoId)) " ⭐" else "", 12.5f, th.text, true).apply { maxLines = 2; setPadding(0, ui.dp(5), 0, 0) })
            cc.addView(ui.text("📁 " + v.folderName + if (v.durMs > 0) " · " + PlayerLogic.clock(v.durMs) else "", 10.5f, th.muted))
        }, { playFile(v.uri, v.landscape) }, { favToggle(v.videoId, v.title) })
        Thumbs.load(this, v) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } }
        return c
    }

    private fun showHome() {
        content.removeAllViews()
        val hidden = Cfg.str("tv_rows_hide", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val recs = recents()
        if ("cont" !in hidden) rowInto(content, "▶ كمّل مشاهدة", recs.take(15).map { recentCard(it) })
        val favRecs = recs.filter { favs.contains(it.id) }
        if ("fav" !in hidden && favRecs.isNotEmpty()) rowInto(content, "⭐ المفضلة", favRecs.map { recentCard(it) })
        if ("latest" !in hidden) {
            val lt = LinearLayout(content.context).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            content.addView(lt)
            if (!VideoScan.hasPermission(this)) {
                lt.addView(ui.text("🎬 أحدث الفيديوهات", 17f, th.primary, true).apply { setPadding(ui.dp(6), ui.dp(14), 0, ui.dp(2)) })
                lt.addView(ui.text("محتاج إذن الوصول للفيديوهات — افتح «الفيديوهات» مرة واحدة وادّي الإذن", 12.5f, th.muted).apply { setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) })
            } else Thread {
                val items = try { VideoScan.scan(this).sortedByDescending { it.dateMs }.take(24) } catch (_: Exception) { emptyList() }
                lastScan = items
                runOnUiThread { if (!isDestroyed) rowInto(lt, "🎬 أحدث الفيديوهات", items.map { videoCard(it) }) }
            }.start()
        }
        firstRail?.let { f -> content.post { try { f.requestFocus() } catch (_: Exception) {} } }
    }

    // ===== بحث موحّد: نص · صوت · كتابة من الموبايل =====
    private fun showSearch(prefill: String = "") {
        content.removeAllViews()
        content.addView(ui.text("🔍 بحث موحّد — مكتبتك وآخر المشاهدة ويوتيوب", 18f, th.primary, true).apply { setPadding(ui.dp(6), ui.dp(10), 0, ui.dp(6)) })
        val et = EditText(this).apply {
            setText(prefill); setSingleLine(); textSize = 16f; setTextColor(th.text); setHintTextColor(th.muted)
            hint = "اكتب اسم فيديو أو حلقة…"; setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.box(th.surface, th.border, 14)
        }
        content.addView(et, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun run(q: String) {
            results.removeAllViews()
            val ql = q.trim().lowercase()
            if (ql.isEmpty()) return
            val recHits = recents().filter { it.title.lowercase().contains(ql) }
            val libHits = lastScan.filter { it.title.lowercase().contains(ql) || it.folderName.lowercase().contains(ql) }
            rowInto(results, "📺 من آخر المشاهدة", recHits.take(12).map { recentCard(it) })
            rowInto(results, "🎬 من المكتبة", libHits.take(18).map { videoCard(it) })
            if (recHits.isEmpty() && libHits.isEmpty()) results.addView(ui.text("مفيش نتائج محلية لـ «$q»", 13f, th.muted).apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(4)) })
            rowInto(results, "▶ على يوتيوب", listOf(card(252, 92, { c ->
                c.addView(ui.text("ابحث عن «$q» في يوتيوب البرنامج", 14f, th.text, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -1))
            }, { openMain("youtube") })))
        }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        fun sbtn(t: String, f: () -> Unit): View {
            val b = LinearLayout(this).apply {
                isFocusable = true; isClickable = true; gravity = Gravity.CENTER
                setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); background = ui.box(th.surface, th.border, 18)
                setOnClickListener { f() }; addView(ui.text(t, 13.5f, th.text, true))
            }
            b.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) }
            return b
        }
        btns.addView(sbtn("🔍 ابحث") { run(et.text.toString()) })
        btns.addView(sbtn("🎤 بحث صوتي") { startVoice { q -> et.setText(q); run(q) } })
        btns.addView(sbtn("📱 اكتب من الموبايل") { phoneTypeDialog { q -> et.setText(q); run(q) } })
        content.addView(btns)
        content.addView(results)
        if (prefill.isNotEmpty()) run(prefill)
        content.post { try { et.requestFocus() } catch (_: Exception) {} }
    }

    private var voiceCb: ((String) -> Unit)? = null
    private fun startVoice(cb: (String) -> Unit) {
        voiceCb = cb
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "قول اسم الفيديو…")
            }
            startActivityForResult(i, 77)
        } catch (e: Exception) { LogStore.err("Tv:voice", e); Notice.show(this, "⚠ البحث الصوتي مش متاح على الجهاز ده", 2200L) }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 77 && res == RESULT_OK) {
            val q = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            voiceCb?.invoke(q); voiceCb = null
        }
    }

    private fun phoneTypeDialog(apply: (String) -> Unit) {
        val url = TypeServer.url()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)) }
        col.addView(ui.text("امسح الكود بكاميرا موبايلك (أو افتح العنوان في المتصفح — لازم نفس الواي فاي) واكتب هناك:", 13f, th.text))
        if (url.isNotEmpty()) {
            val qr = QrCode.bitmap(url, ui.dp(190))
            if (qr != null) {
                val iv = ImageView(this).apply { setImageBitmap(qr); setBackgroundColor(Color.WHITE); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
                col.addView(iv, LinearLayout.LayoutParams(ui.dp(190), ui.dp(190)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(8) })
            }
        }
        col.addView(ui.text(if (url.isEmpty()) "⚠ مش متصل بواي فاي" else url, 17f, th.primary, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(10)) })
        val got = ui.text(if (TypeServer.lastText.isEmpty()) "لسه ماوصلش حاجة…" else "وصل: " + TypeServer.lastText, 13f, th.muted)
        col.addView(got)
        val dlg = GAlert(this).setTitle("📱 اكتب من الموبايل").setView(col)
            .setPositiveButton("استخدم اللي وصل") { _, _ -> if (TypeServer.lastText.isNotEmpty()) apply(TypeServer.lastText) }
            .setNegativeButton("إغلاق", null).create()
        dlg.show()
        val poll = object : Runnable { override fun run() { if (!dlg.isShowing) return; got.text = if (TypeServer.lastText.isEmpty()) "لسه ماوصلش حاجة…" else "وصل: " + TypeServer.lastText; h.postDelayed(this, 1200) } }
        h.postDelayed(poll, 1200)
    }

    // ===== تخصيص الصفوف وقفل PIN =====
    private fun rowsDialog() {
        val ids = listOf("cont" to "▶ كمّل مشاهدة", "fav" to "⭐ المفضلة", "latest" to "🎬 أحدث الفيديوهات")
        val hidden = Cfg.str("tv_rows_hide", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        val labels = ids.map { it.second }.toTypedArray()
        val checked = BooleanArray(ids.size) { ids[it].first !in hidden }
        GAlert(this).setTitle("🎛 صفوف الشاشة الرئيسية")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> if (isChecked) hidden.remove(ids[which].first) else hidden.add(ids[which].first) }
            .setPositiveButton("تمام") { _, _ -> Cfg.put("tv_rows_hide", hidden.joinToString(",")); showHome() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun sha(s: String): String = try {
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (_: Exception) { s }

    private fun guardPin(next: () -> Unit) {
        if (Cfg.str("tv_pin", "").isEmpty()) { next(); return }
        numpad("🔒 اكتب الرقم السري") { pin -> if (sha(pin) == Cfg.str("tv_pin", "")) next() else Notice.show(this, "⚠ الرقم غلط", 1800L) }
    }

    private fun pinMenu() {
        if (Cfg.str("tv_pin", "").isEmpty()) {
            numpad("🔒 اختار رقم سري جديد (4 أرقام أو أكتر)") { p1 ->
                numpad("🔒 اكتبه تاني للتأكيد") { p2 ->
                    if (p1 == p2 && p1.length >= 4) { Cfg.put("tv_pin", sha(p1)); Notice.show(this, "🔒 القفل اتفعّل", 1800L) }
                    else Notice.show(this, "⚠ الرقمين مش زي بعض أو قصيرين", 2200L)
                }
            }
        } else {
            GAlert(this).setTitle("🔒 قفل TV").setMessage("القفل مفعّل على «الملفات» و«الإعدادات».")
                .setPositiveButton("إلغاء القفل") { _, _ -> guardPin { Cfg.put("tv_pin", ""); Notice.show(this, "🔓 القفل اتشال", 1600L) } }
                .setNegativeButton("سيبه", null).show()
        }
    }

    private fun numpad(title: String, done: (String) -> Unit) {
        var pin = ""
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(10)) }
        val dots = ui.text("○ ○ ○ ○", 22f, th.primary, true).apply { gravity = Gravity.CENTER }
        col.addView(dots)
        fun paintDots() { dots.text = if (pin.isEmpty()) "○ ○ ○ ○" else pin.map { "●" }.joinToString(" ") }
        lateinit var dlg: android.app.Dialog
        fun press(d: String) {
            when (d) {
                "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                "✓" -> { if (pin.isNotEmpty()) { dlg.dismiss(); done(pin) } ; return }
                else -> if (pin.length < 8) pin += d
            }
            paintDots()
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        for (r in 0 until 4) {
            val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            for (k in 0 until 3) {
                val key = keys[r * 3 + k]
                val b = ui.text(key, 19f, th.text, true).apply {
                    gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                    background = ui.box(th.surface, th.border, 12)
                    setOnClickListener { press(key) }
                    setOnFocusChangeListener { v, f -> v.background = ui.box(if (f) th.card else th.surface, if (f) th.primary else th.border, 12) }
                }
                rowL.addView(b, LinearLayout.LayoutParams(ui.dp(64), ui.dp(52)).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            }
            col.addView(rowL)
        }
        dlg = GAlert(this).setTitle(title).setView(col).setNegativeButton("إلغاء", null).create()
        dlg.show()
    }

    // ===== تشغيل وفتح الأقسام =====
    private fun openMain(tab: String?) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("force_phone", true)
            putExtra("from_tv", true)   // (v467) عشان رجوع Main من الزيارة دي يرجّع لواجهة التلفزيون
            if (tab != null) putExtra("tab", tab)
        })
    }

    private fun playFile(uriStr: String, landscape: Boolean) {
        try {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                data = Uri.parse(uriStr); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("landscape", landscape); putExtra("fresh", false); putExtra("nosub", false); putExtra("ask", false)
            })
        } catch (e: Exception) { LogStore.err("Tv:play", e); Notice.show(this, "⚠ ما قدرتش أشغّل الملف ده", 2200L) }
    }

    private fun playRecent(r: Recent) {
        if (r.uri.isNotEmpty()) playFile(r.uri, true)
        else if (r.url.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("force_phone", true))
    }
}
