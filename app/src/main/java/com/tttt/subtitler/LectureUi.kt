package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** (v991) شريط تقدم مدوّر — بيتملّي من اليمين في الـRTL */
private class LecBar(ctx: Context) : View(ctx) {
    var track: Int = 0
    var fill: Int = 0
    private var frac = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    fun set(f: Float) { val t = f.coerceIn(0f, 1f); if (Math.abs(t - frac) > 0.001f) { frac = t; invalidate() } }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        if (w <= 0f || h <= 0f) return
        val save = c.save()
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) c.scale(-1f, 1f, w / 2f, h / 2f)
        p.color = track; c.drawRoundRect(0f, 0f, w, h, r, r, p)
        if (frac > 0f) { p.color = fill; c.drawRoundRect(0f, 0f, Math.max(h, w * frac), h, r, r, p) }
        c.restoreToCount(save)
    }
}

/**
 * (v991) 🎓 تفريغ المحاضرات — شاشة من «المزيد»:
 *  1) اختيار المصدر: 🎬 فيديو/صوت (مكتبة الفيديوهات · ملف) · 🎙 التسجيلات (كل صوتيات الجهاز) · 🔗 لينك.
 *  2) شاشة المحاضرة: تقدم التفريغ (إيقاف/استكمال/إعادة الناقص) + تبويبات 📝 التفريغ · 🗂 تنظيم · ✂ تلخيص + سويتش 🌐 الاستعانة بالنت + نسخ/مشاركة.
 *  3) سجل محاضراتك (محفوظة، بتكمّل من حيث وقفت).
 * المنطق كله في Lecture.kt — الملف ده واجهة بس.
 */
class LectureUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    companion object {
        const val REQ = 7310
        private var pending: ((Uri) -> Unit)? = null
        /** من MainActivity.onActivityResult */
        fun onResult(act: Activity, c: Int, d: Intent?) {
            val cb = pending; pending = null
            if (c != Activity.RESULT_OK) return
            val u = d?.data ?: return
            try { act.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            cb?.invoke(u)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val amber = 0xFFF59E0B.toInt()
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK

    // ===================== مكوّنات صغيرة =====================
    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = ui.dp(top) }
    private fun vcol() = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private fun hrow() = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }

    private fun glassBg(r: Int): android.graphics.drawable.Drawable =
        if (MtGlass.on()) MtGlass.pill(th, MtGlass.fill(th), ui.dp(r).toFloat(), act.resources.displayMetrics.density) else ui.box(th.surface, th.border, 12)

    private fun pill(label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView =
        ui.text(label, 12.5f, if (primary) onPrimary else if (danger) th.danger else th.text, true).apply {
            gravity = Gravity.CENTER; maxLines = 1
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            background = if (primary) Glass.primaryFill(th, ui.dp(16).toFloat()) else glassBg(16)
            isClickable = true; Glass.pressable(this); setOnClickListener { onClick() }
        }

    /** صف خيارات متبدّلة (مفرد الاختيار): [(قيمة, اسم)] */
    private fun segRow(opts: List<Pair<String, String>>, cur: () -> String, onPick: (String) -> Unit): View {
        val row = hrow()
        val views = ArrayList<Pair<String, TextView>>()
        fun paint() = views.forEach { (k, v) ->
            val on = k == cur()
            v.setTextColor(if (on) onPrimary else th.text)
            v.background = if (on) Glass.primaryFill(th, ui.dp(14).toFloat()) else glassBg(14)
        }
        for ((k, label) in opts) {
            val v = ui.text(label, 12f, th.text, true).apply {
                gravity = Gravity.CENTER; maxLines = 1; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); isClickable = true; Glass.pressable(this)
                setOnClickListener { onPick(k); paint() }
            }
            views.add(k to v); row.addView(v, lp(-2, -2).apply { marginEnd = ui.dp(6) })
        }
        paint()
        return HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(row) }
    }

    private fun card(emoji: String, title: String, sub: String, onClick: () -> Unit): View = hrow().apply {
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); background = glassBg(18)
        addView(ui.text(emoji, 26f).apply { gravity = Gravity.CENTER }, lp(ui.dp(40), -2))
        val t = vcol()
        t.addView(ui.text(title, 14.5f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (sub.isNotBlank()) t.addView(ui.text(sub, 11.5f, th.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        addView(ui.text("‹", 20f, th.muted, true))
        isClickable = true; Glass.pressable(this); setOnClickListener { onClick() }
    }

    private fun fmtDur(sec: Double): String = Lecture.fmtTime(sec)

    private fun copy(text: String) {
        try {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("lecture", text))
            Notice.show(act, "📋 اتنسخ", 1800L)
        } catch (e: Throwable) { LogStore.err("LectureUi:copy", e) }
    }

    private fun share(title: String, text: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
            act.startActivity(Intent.createChooser(i, "مشاركة"))
        } catch (e: Throwable) { LogStore.err("LectureUi:share", e); Notice.show(act, "⚠ المشاركة فشلت", 2400L) }
    }

    private fun nameOf(u: Uri): String = try {
        act.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: (u.lastPathSegment ?: "محاضرة")
    } catch (_: Throwable) { u.lastPathSegment ?: "محاضرة" }

    // ===================== الشاشة الأولى: المصدر + السجل =====================
    private var dlg: Dialog? = null
    private var shown = false
    private var hist: List<Lecture.Doc> = emptyList()
    private val histCol = vcol()
    private var tick: Runnable? = null

    fun show() {
        val col = vcol()
        col.layoutParams = lp(-1, -1)
        col.addView(ui.text("فرّغ أي محاضرة لنص دقيق، وبعدها نظّمها أو لخّصها.", 12.5f, th.muted), lp(-1, -2))
        col.addView(card("🎬", "فيديو أو محاضرة صوتية", "فيديو من المكتبة · ملف صوت · أي ملف من الجهاز") { chooseMedia() }, lp(-1, -2, 10))
        col.addView(card("🎙", "التسجيلات", "كل الريكوردز الموجودة على الجهاز") { openRecordings(false) }, lp(-1, -2, 8))
        col.addView(card("🔗", "من لينك", "يوتيوب · فيسبوك · أي لينك فيه صوت أو فيديو") { askLink() }, lp(-1, -2, 8))
        col.addView(ui.text("📚 محاضراتك", 13f, th.muted, true).apply { setPadding(ui.dp(4), ui.dp(16), ui.dp(4), ui.dp(2)) }, lp(-1, -2))
        val sc = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(histCol) }
        col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(4) })
        shown = true
        val d = ui.sheet(act, "🎓 تفريغ المحاضرات", listOf<View>(col), fixedH = true, onClose = { shown = false; tick?.let { main.removeCallbacks(it) } })
        dlg = d
        d.show()
        loadHistory()
        val t = object : Runnable { override fun run() { if (!shown) return; if (hist.any { Lecture.isRunning(it.id) }) renderHistory(); main.postDelayed(this, 1200L) } }
        tick = t; main.postDelayed(t, 1200L)
    }

    private fun loadHistory() {
        Thread {
            val l = try { Lecture.list(act) } catch (e: Throwable) { LogStore.err("LectureUi:list", e); emptyList() }
            main.post { hist = l; if (shown) renderHistory() }
        }.apply { isDaemon = true }.start()
    }

    private fun statusOf(d: Lecture.Doc): String {
        val r = Lecture.runOf(d.id)
        return when {
            r != null && r.running -> "⏳ بيفرّغ… ${d.pct}%"
            d.complete -> "✅ مكتمل"
            d.failed.isNotEmpty() -> "⚠ ${d.failed.size} جزء ناقص"
            d.nChunks == 0 -> "لسه ما بدأش"
            else -> "⏸ واقف عند ${d.pct}%"
        }
    }

    private fun renderHistory() {
        histCol.removeAllViews()
        if (hist.isEmpty()) { histCol.addView(ui.text("مفيش محاضرات لسه — اختار مصدر من فوق.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)) }); return }
        for (d in hist) {
            val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(16) }
            val top = hrow()
            val t = vcol()
            t.addView(ui.text(d.title.ifBlank { "محاضرة" }, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val meta = listOf(statusOf(d), if (d.durSec > 0) fmtDur(d.durSec) else "", if (d.words > 0) "${d.words} كلمة" else "").filter { it.isNotBlank() }.joinToString(" · ")
            t.addView(ui.text(meta, 11.5f, th.muted).apply { maxLines = 1 })
            top.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(ui.text("🗑", 16f, th.muted).apply {
                setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6)); isClickable = true; Glass.pressable(this)
                setOnClickListener { confirmDelete(d) }
            })
            row.addView(top)
            if (!d.complete && d.nChunks > 0) {
                val bar = LecBar(act).apply { track = Glass.alpha(th.text, 0x22); fill = th.primary; set(d.pct / 100f) }
                row.addView(bar, lp(-1, ui.dp(5), 8))
            }
            row.isClickable = true; Glass.pressable(row); row.setOnClickListener { openDoc(d, false) }
            histCol.addView(row, lp(-1, -2, 8))
        }
    }

    private fun confirmDelete(d: Lecture.Doc) {
        GAlert(act).setTitle("🗑 حذف المحاضرة").setMessage("«${d.title.take(50)}» — هيتمسح التفريغ والتنظيم والتلخيص.")
            .setPositiveButton("حذف") { _, _ -> Lecture.delete(act, d.id); hist = hist.filter { it.id != d.id }; renderHistory() }
            .setNegativeButton("إلغاء", null).show()
    }

    // ===================== المصادر =====================
    private fun chooseMedia() {
        val labels = arrayOf("🎬 فيديو من المكتبة", "🎵 محاضرة صوتية (من صوتيات الجهاز)", "📂 ملف من الجهاز (أي فيديو أو صوت)")
        GAlert(act).setTitle("اختار المحاضرة").setItems(labels) { _, w ->
            when (w) {
                0 -> VideoPicker.show(act, "اختار الفيديو", { v -> begin("video", v.title, v.uri, "") }, { pickFile() })
                1 -> openRecordings(true)
                else -> pickFile()
            }
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun pickFile() {
        if (act !is MainActivity) { Notice.show(act, "اختيار الملف متاح من الصفحة الرئيسية — أو اختار من المكتبة/التسجيلات", 3200L); return }
        pending = { u ->
            val mime = try { act.contentResolver.getType(u) ?: "" } catch (_: Throwable) { "" }
            begin(if (mime.startsWith("audio")) "audio" else "video", nameOf(u).substringBeforeLast('.'), u.toString(), "")
        }
        try { act.startActivityForResult(filePicker("*/*", "اختار فيديو أو صوت", true), REQ) }
        catch (e: Throwable) { LogStore.err("LectureUi:pick", e); Notice.show(act, "⚠ مش قادر أفتح اختيار الملف", 2600L) }
    }

    private fun askLink() {
        val et = ui.input("الصق لينك يوتيوب / فيسبوك / ملف…", "")
        try {
            val clip = (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (clip.startsWith("http", true)) et.setText(clip)
        } catch (_: Throwable) {}
        GAlert(act).setTitle("🔗 لينك المحاضرة").setMessage("هيجيب الصوت من اللينك ويفرّغه. فيديوهات الصفحات المقفولة (محتاجة تسجيل دخول) ممكن ما تشتغلش.").setView(et)
            .setPositiveButton("فرّغ") { _, _ ->
                val u = et.text.toString().trim()
                if (!u.startsWith("http", true)) { Notice.show(act, "اللينك لازم يبدأ بـ http", 2400L); return@setPositiveButton }
                val host = try { Uri.parse(u).host ?: "" } catch (_: Throwable) { "" }
                begin("link", if (host.isNotBlank()) "لينك: $host" else "محاضرة", "", u)
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun begin(kind: String, title: String, uri: String, link: String) {
        val d = Lecture.newDoc(act, kind, title, uri, link)
        if (hist.none { it.id == d.id }) hist = listOf(d) + hist
        main.post { openDoc(d, !d.complete) }
    }

    // ===================== التسجيلات =====================
    private fun openRecordings(allAudio: Boolean) {
        if (!Lecture.hasAudioPermission(act)) {
            Notice.show(act, "محتاج إذن الصوتيات الأول — اسمح بيه وبعدين ادوس تاني", 3600L)
            try { act.requestPermissions(arrayOf(Lecture.audioPermission()), 12) } catch (_: Throwable) {}
            return
        }
        var showAll = allAudio
        var q = ""
        var all: List<Lecture.Rec> = emptyList()
        var scanning = true
        val list = vcol()
        val info = ui.text("⏳ بدوّر على الصوتيات…", 12f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        lateinit var d: Dialog
        fun render() {
            list.removeAllViews()
            val src = (if (showAll) all else all.filter { it.isRec }).filter { q.isBlank() || it.name.contains(q, true) }
            info.text = when {
                scanning -> "⏳ بدوّر على الصوتيات…"
                src.isEmpty() -> if (showAll) "مفيش ملفات صوت" else "مفيش تسجيلات متعرّف عليها — جرّب «كل الصوتيات»"
                else -> "${src.size} ملف"
            }
            for (r in src.take(200)) {
                val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
                row.addView(ui.text(r.name.substringBeforeLast('.'), 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                val date = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(java.util.Date(r.dateSec * 1000L))
                val folder = r.path.trim('/').substringAfterLast('/').ifBlank { r.path.trim('/').substringBeforeLast('/').substringAfterLast('/') }
                row.addView(ui.text(listOf(fmtDur(r.durMs / 1000.0), date, folder).filter { it.isNotBlank() }.joinToString(" · "), 11.5f, th.muted).apply { maxLines = 1 })
                row.isClickable = true; Glass.pressable(row)
                row.setOnClickListener { d.dismiss(); begin(if (r.isRec) "rec" else "audio", r.name.substringBeforeLast('.'), r.uri.toString(), "") }
                list.addView(row, lp(-1, -2, 6))
            }
        }
        val search = ui.input("🔍 دوّر في الأسماء", "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { q = s?.toString()?.trim() ?: ""; render() }
        })
        val content = vcol().apply { layoutParams = lp(-1, -1) }
        content.addView(segRow(listOf("rec" to "🎙 التسجيلات", "all" to "🎵 كل الصوتيات"), { if (showAll) "all" else "rec" }, { showAll = it == "all"; render() }), lp(-1, -2))
        content.addView(search, lp(-1, -2, 4))
        content.addView(info)
        content.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        d = ui.sheet(act, "🎙 اختار تسجيل", listOf<View>(content), fixedH = true)
        d.show()
        Thread {
            val l = try { Lecture.scanAudio(act) } catch (e: Throwable) { LogStore.err("LectureUi:scan", e); emptyList() }
            main.post { all = l; scanning = false; if (!showAll && l.none { it.isRec }) showAll = true; render() }
        }.apply { isDaemon = true }.start()
        render()
    }

    // ===================== شاشة المحاضرة =====================
    private fun openDoc(d: Lecture.Doc, autoStart: Boolean) {
        var tab = "tr"                       // tr · org · sum
        var stamps = false
        var busy = false
        var lastSig = ""
        var alive = true
        if (autoStart && !Lecture.isRunning(d.id)) Lecture.start(act, d)

        val title = ui.text(d.title.ifBlank { "محاضرة" }, 15f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val status = ui.text("", 12f, th.muted)
        val bar = LecBar(act).apply { track = Glass.alpha(th.text, 0x22); fill = th.primary }
        val ctl = hrow()
        val tabs = hrow()
        val opts = vcol()
        val body = vcol()
        val bodyScroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(body) }

        fun curText(): String = when (tab) {
            "org" -> Lecture.outlineText(d.title, d.organized)
            "sum" -> Lecture.outlineText("", d.summary)
            else -> Lecture.transcriptText(d, stamps)
        }

        fun paintCtl() {
            ctl.removeAllViews()
            val r = Lecture.runOf(d.id)
            if (r != null && r.running) ctl.addView(pill("⏹ إيقاف", danger = true) { Lecture.stop(d.id) }, LinearLayout.LayoutParams(0, -2, 1f))
            else if (!d.complete) ctl.addView(pill(if (d.failed.isNotEmpty()) "🔁 أعد الناقص وكمّل" else if (d.nChunks == 0) "▶ ابدأ التفريغ" else "▶ كمّل التفريغ", primary = true) {
                Lecture.start(act, d); lastSig = ""; paintCtl()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            ctl.visibility = if (ctl.childCount == 0) View.GONE else View.VISIBLE
        }

        fun renderOutline(raw: String, emptyMsg: String, again: String, onGen: () -> Unit, webUsed: Boolean) {
            body.removeAllViews()
            if (busy) { body.addView(ui.text("⏳ بيشتغل… (ممكن ياخد نص دقيقة)", 13f, th.muted).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12)) }); return }
            if (raw.isBlank()) {
                body.addView(ui.text(emptyMsg, 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)) })
                body.addView(pill(again, primary = true) { onGen() }, lp(-1, -2, 4)); return
            }
            val o = Lecture.parseOutline(raw)
            if (o.title.isNotBlank()) body.addView(ui.text(o.title, 16f, th.text, true).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(6)) })
            if (webUsed) body.addView(ui.text("🌐 النقط اللي عليها 🌐 جت من النت مش من كلام المحاضر", 11.5f, amber).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(6)) })
            for (s in o.secs) {
                val c = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(16) }
                if (s.head.isNotBlank()) c.addView(ui.text(s.head, 15f, th.primary, true).apply { setTextIsSelectable(true) })
                for (p in s.pts) {
                    val tv = ui.text((if (p.bullet) "• " else "") + (if (p.web) "🌐 " else "") + p.text, 13.5f, if (p.web) amber else th.text).apply {
                        setLineSpacing(0f, 1.25f); setTextIsSelectable(true)
                    }
                    c.addView(tv, lp(-1, -2, 5))
                }
                body.addView(c, lp(-1, -2, 8))
            }
            body.addView(pill(again) { onGen() }, lp(-1, -2, 10))
        }

        var generateFn: (String) -> Unit = {}

        fun renderAll() {
            body.removeAllViews()
            opts.removeAllViews()
            when (tab) {
                "tr" -> {
                    opts.addView(ui.switchRow("⏱ توقيت قبل كل جملة", stamps) { stamps = it; lastSig = ""; renderAll() })
                    val txt = Lecture.transcriptText(d, stamps)
                    if (txt.isBlank()) {
                        val r = Lecture.runOf(d.id)
                        body.addView(ui.text(if (r != null && r.running) "⏳ لسه ما وصلش أول جزء…" else "مفيش تفريغ لسه.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12)) })
                    } else body.addView(ui.text(txt, 14f, th.text).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.35f); setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
                }
                "org" -> {
                    opts.addView(ui.switchRow("🌐 استعن بالنت (تصحيح مصطلحات وإكمال تعريفات)", Cfg.bool("lec_web", false)) { Cfg.put("lec_web", if (it) "1" else "0") })
                    opts.addView(segRow(listOf("ar" to "اكتب بالعربي", "same" to "بلغة المحاضرة"), { Cfg.str("lec_out", "ar") }, { Cfg.put("lec_out", it) }), lp(-1, -2, 4))
                    renderOutline(d.organized, "نظّم المحاضرة: عناوين رئيسية وتحت كل عنوان نقط قليلة.", if (d.organized.isBlank()) "🗂 نظّم المحاضرة" else "↻ إعادة التنظيم", { generateFn("org") }, d.organizedWeb)
                }
                else -> {
                    opts.addView(ui.switchRow("🌐 استعن بالنت (تصحيح مصطلحات وإكمال تعريفات)", Cfg.bool("lec_web", false)) { Cfg.put("lec_web", if (it) "1" else "0") })
                    opts.addView(segRow(listOf("short" to "مختصر", "mid" to "متوسط"), { Cfg.str("lec_sumlen", "mid") }, { Cfg.put("lec_sumlen", it) }), lp(-1, -2, 4))
                    opts.addView(segRow(listOf("ar" to "اكتب بالعربي", "same" to "بلغة المحاضرة"), { Cfg.str("lec_out", "ar") }, { Cfg.put("lec_out", it) }), lp(-1, -2, 4))
                    renderOutline(d.summary, "لخّص المحاضرة: ملخص قصير + أهم النقط.", if (d.summary.isBlank()) "✂ لخّص المحاضرة" else "↻ إعادة التلخيص", { generateFn("sum") }, d.summaryWeb)
                }
            }
        }

        generateFn = { kind ->
            val web = Cfg.bool("lec_web", false)
            if (busy) Unit
            else if (d.segs.isEmpty()) Notice.show(act, "فرّغ المحاضرة الأول", 2400L)
            else {
                busy = true; renderAll()
                Thread {
                    var err = ""
                    try {
                        if (kind == "org") Lecture.organize(act, d, web) else Lecture.summarize(act, d, Cfg.str("lec_sumlen", "mid") == "short", web)
                    } catch (e: Throwable) { LogStore.err("LectureUi:gen", e); err = e.message ?: e.javaClass.simpleName }
                    main.post {
                        busy = false
                        if (err.isNotEmpty()) Notice.show(act, "⚠ " + err.take(120), 3600L)
                        else if (web && !(if (kind == "org") d.organizedWeb else d.summaryWeb)) Notice.show(act, "البحث على النت مارجعش نتيجة — اتعمل من كلام المحاضر بس", 3200L)
                        if (alive) renderAll()
                    }
                }.apply { isDaemon = true }.start()
            }
        }

        fun paintTabs() {
            tabs.removeAllViews()
            for ((k, label) in listOf("tr" to "📝 التفريغ", "org" to "🗂 تنظيم", "sum" to "✂ تلخيص")) {
                val on = tab == k
                tabs.addView(pill(label, primary = on) { tab = k; paintTabs(); lastSig = ""; renderAll() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
            }
        }

        fun refreshStatus() {
            val r = Lecture.runOf(d.id)
            status.text = when {
                r != null && r.running -> r.status + (if (d.failed.isNotEmpty()) " · ⚠ ${d.failed.size} ناقص" else "")
                r != null && r.status.isNotBlank() && !d.complete -> r.status
                d.complete -> "✅ التفريغ مكتمل — ${d.words} كلمة · ${fmtDur(d.durSec)}"
                d.nChunks == 0 -> "جاهز للتفريغ"
                else -> "⏸ واقف عند ${d.pct}%" + (if (d.failed.isNotEmpty()) " · ⚠ ${d.failed.size} جزء ناقص" else "")
            }
            bar.set(d.pct / 100f)
            bar.visibility = if (d.nChunks == 0 || d.complete) View.GONE else View.VISIBLE
        }

        val content = vcol().apply { layoutParams = lp(-1, -1) }
        content.addView(title)
        content.addView(status, lp(-1, -2, 2))
        content.addView(bar, lp(-1, ui.dp(5), 6))
        content.addView(ctl, lp(-1, -2, 8))
        content.addView(tabs, lp(-1, -2, 8))
        content.addView(opts, lp(-1, -2, 6))
        content.addView(bodyScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        val acts = hrow()
        acts.addView(pill("📋 نسخ") { val t = curText(); if (t.isBlank()) Notice.show(act, "مفيش حاجة تتنسخ", 1800L) else copy(t) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        acts.addView(pill("📤 مشاركة") { val t = curText(); if (t.isBlank()) Notice.show(act, "مفيش حاجة تتشارك", 1800L) else share(d.title, t) }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(acts, lp(-1, -2, 8))

        val dd = ui.sheet(act, "🎓 " + d.title.take(28), listOf<View>(content), fixedH = true, onClose = { alive = false; loadHistory() })
        paintCtl(); paintTabs(); refreshStatus(); renderAll()
        dd.show()

        // تحديث حي: الحالة كل ثانية، والتفريغ لما عدد الجمل يتغيّر (وهو بيشتغل)
        val ticker = object : Runnable {
            override fun run() {
                if (!alive) return
                try {
                    refreshStatus()
                    val r = Lecture.runOf(d.id)
                    val sig = d.done.size.toString() + "/" + d.failed.size + "/" + (r?.running ?: false)
                    if (sig != lastSig) {
                        lastSig = sig; paintCtl()
                        if (tab == "tr" && !busy) renderAll()
                    }
                } catch (_: Throwable) {}
                main.postDelayed(this, 1000L)
            }
        }
        main.postDelayed(ticker, 1000L)
    }
}
