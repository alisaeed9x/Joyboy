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

    // (v999 ملاحظة 2) اسم ولون المتحدث في قايمة الجمل — A/B/C للترجمة، D/S للمحاضرات.
    // (v1000) أي اسم حر بعد إعادة التسمية (مثلاً «أحمد») بيظهر زي ما هو — مش فاضي.
    private fun spkLabel(spk: String): String = when (spk) {
        "A" -> "🗣 أ"
        "B" -> "🗣 ب"
        "C" -> "🗣 ج"
        "D" -> "🎓 المحاضر"
        "S" -> "🙋 طالب"
        else -> if (spk.isBlank()) "" else "🗣 " + spk
    }

    private fun spkColor(spk: String): Int = when (spk) {
        "A" -> th.primary
        "B" -> amber
        "C" -> 0xFFAB47BC.toInt()
        "D" -> th.primary
        "S" -> amber
        else -> th.muted
    }

    // (v999) توقيت مكتوب: «1:12» أو «01:12» أو «1:02:30» أو ثواني «72» — بيرجّع ثواني أو null.
    private fun parseSegTime(s: String): Double? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (t.contains(':')) {
            val parts = t.split(':')
            if (parts.size == 2) {
                val m = parts[0].toIntOrNull() ?: return null
                val sec = parts[1].toDoubleOrNull() ?: return null
                return m * 60.0 + sec
            }
            if (parts.size == 3) {
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].toIntOrNull() ?: return null
                val sec = parts[2].toDoubleOrNull() ?: return null
                return h * 3600.0 + m * 60.0 + sec
            }
            return null
        }
        return t.toDoubleOrNull()
    }

    // (v999 ملاحظة 2) صف جملة واحدة في قايمة الجمل الحيّة: ⏱ بداية → نهاية + المتحدث + النص. دوسة بتفتح التعديل.
    private fun sentRow(d: Lecture.Doc, s: Lecture.Seg, onEdit: (Lecture.Seg) -> Unit): View {
        val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
        val top = hrow()
        top.addView(ui.text("⏱ " + Lecture.fmtTime(s.start) + " → " + Lecture.fmtTime(s.end), 12f, th.muted, true))
        val label = spkLabel(s.spk)
        if (label.isNotBlank()) top.addView(ui.text(label, 12f, spkColor(s.spk), true).apply { setPadding(ui.dp(10), 0, 0, 0) })
        if (s.lang.isNotBlank() && s.lang != d.langCode) top.addView(ui.text(s.lang, 11f, th.muted).apply { setPadding(ui.dp(10), 0, 0, 0) })
        row.addView(top)
        row.addView(ui.text(s.text, 14f, th.text).apply { setLineSpacing(0f, 1.3f); setTextIsSelectable(true) }, lp(-1, -2, 4))
        row.isClickable = true
        Glass.pressable(row)
        row.setOnClickListener { onEdit(s) }
        return row
    }

    // (v999 ملاحظة 2) تعديل جملة: نص + بداية + نهاية — بيتحفظ من غير إعادة تفريغ.
    // (v1001) internal عشان صفحة الدردشة (CallChatUi) تستعمله على أول جملة في الفقاعة.
    internal fun editSegDialog(d: Lecture.Doc, s: Lecture.Seg, onSaved: () -> Unit) {
        val textEt = ui.input("نص الجملة", s.text, 3)
        val startEt = ui.input("البداية (MM:SS أو ثواني)", Lecture.fmtTime(s.start))
        val endEt = ui.input("النهاية (MM:SS أو ثواني)", Lecture.fmtTime(s.end))
        val box = vcol()
        box.addView(textEt, lp(-1, -2))
        box.addView(startEt, lp(-1, -2, 8))
        box.addView(endEt, lp(-1, -2, 8))
        GAlert(act).setTitle("✎ تعديل الجملة").setMessage("التعديل بيتحفظ في التفريغ من غير إعادة تفريغ. التوقيتات بتتقلم داخل مدة الفيديو.").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val ns = parseSegTime(startEt.text.toString())
                val ne = parseSegTime(endEt.text.toString())
                if (ns == null || ne == null) {
                    Notice.show(act, "⚠ التوقيت لازم يبقى MM:SS أو ثواني", 2600L)
                    return@setPositiveButton
                }
                val ok = Lecture.editSeg(act, d, s.chunk, s.start, textEt.text.toString(), ns, ne)
                if (ok) onSaved() else Notice.show(act, "⚠ مقدرتش ألاقي الجملة", 2600L)
            }.setNegativeButton("إلغاء", null).show()
    }

    // (v1000) المتحدثون الموجودون فعلًا في التفريغ — من قيم spk المميزة في الجمل.
    private fun speakersOf(d: Lecture.Doc): List<String> = synchronized(d) {
        d.segs.map { it.spk }.filter { it.isNotBlank() }.distinct().sorted()
    }

    // (v1000) «إعادة تسمية متحدث»: اختار متحدث موجود + اسم جديد ← Lecture.renameSpeaker.
    // بيرفض لو التفريغ شغال (القلب نفسه بيرفض كمان — الرفض هنا عشان المستخدم ميدوسش على الفاضي).
    // بعد النجاح: إعادة رسم الشاشة + تنبيه إن التنظيم والتلخيص اتمسحوا.
    // (v1001) internal عشان صفحة الدردشة (CallChatUi) تستعمله من الفوتر.
    internal fun renameSpeakerDialog(d: Lecture.Doc, onDone: () -> Unit) {
        if (Lecture.isRunning(d.id)) {
            Notice.show(act, "التفريغ شغال — استنى يخلص وبعدين غيّر أسماء المتحدثين", 3000L)
            return
        }
        val spks = speakersOf(d)
        if (spks.isEmpty()) {
            Notice.show(act, "مفيش متحدثين متسجلين في التفريغ ده", 2600L)
            return
        }
        val labels = spks.map { spkLabel(it).ifBlank { it } }.toTypedArray()
        GAlert(act).setTitle("🗣 إعادة تسمية متحدث").setMessage("اختار المتحدث، وبعدها اكتب الاسم الجديد. التنظيم والتلخيص هيتمسحوا ويتعملوا من جديد بالأسماء الجديدة.").setItems(labels) { _, w ->
            val old = spks[w]
            val oldName = spkLabel(old).ifBlank { old }
            val nameEt = ui.input("الاسم الجديد", "")
            GAlert(act).setTitle("🗣 «" + oldName + "» ← اسم جديد").setMessage("كل الجمل اللي قالها «" + oldName + "» هتبقى بالاسم الجديد.").setView(nameEt)
                .setPositiveButton("حفظ") { _, _ ->
                    val newName = nameEt.text.toString().trim()
                    if (newName.isEmpty()) {
                        Notice.show(act, "اكتب الاسم الجديد الأول", 2400L)
                        return@setPositiveButton
                    }
                    if (Lecture.isRunning(d.id)) {
                        Notice.show(act, "التفريغ بدأ يشتغل — استنى يخلص", 3000L)
                        return@setPositiveButton
                    }
                    val ok = Lecture.renameSpeaker(act, d, old, newName)
                    if (ok) {
                        Notice.show(act, "✅ بقى اسمه «" + newName + "» — التنظيم والتلخيص اتمسحوا، اعملهم من جديد", 3600L)
                        onDone()
                    } else {
                        Notice.show(act, "⚠ مفيش متحدث بالاسم ده أو التفريغ شغال", 3000L)
                    }
                }.setNegativeButton("إلغاء", null).show()
        }.setNegativeButton("إلغاء", null).show()
    }

    // (v1000) «دمج متحدثين»: اختار المصدر وبعدها الهدف ← Lecture.mergeSpeakers.
    // نفس حارس التشغيل ونفس التنبيه بعد النجاح زي إعادة التسمية.
    // (v1001) internal عشان صفحة الدردشة (CallChatUi) تستعمله من الفوتر.
    internal fun mergeSpeakersDialog(d: Lecture.Doc, onDone: () -> Unit) {
        if (Lecture.isRunning(d.id)) {
            Notice.show(act, "التفريغ شغال — استنى يخلص وبعدين ادمج المتحدثين", 3000L)
            return
        }
        val spks = speakersOf(d)
        if (spks.size < 2) {
            Notice.show(act, "لازم يبقى فيه متحدثين على الأقل عشان تدمج", 2600L)
            return
        }
        val labels = spks.map { spkLabel(it).ifBlank { it } }.toTypedArray()
        GAlert(act).setTitle("🔀 دمج متحدثين").setMessage("اختار المتحدث اللي هيتدمج (المصدر) — جمله كلها هتبقى باسم المتحدث التاني. التنظيم والتلخيص هيتمسحوا.").setItems(labels) { _, w ->
            val from = spks[w]
            val fromName = spkLabel(from).ifBlank { from }
            val rest = spks.filter { it != from }
            val restLabels = rest.map { spkLabel(it).ifBlank { it } }.toTypedArray()
            GAlert(act).setTitle("🔀 «" + fromName + "» ← يبقى مين؟").setMessage("كل جمل «" + fromName + "» هتبقى باسم المتحدث اللي تختاره.").setItems(restLabels) { _, w2 ->
                val to = rest[w2]
                val toName = spkLabel(to).ifBlank { to }
                if (Lecture.isRunning(d.id)) {
                    Notice.show(act, "التفريغ بدأ يشتغل — استنى يخلص", 3000L)
                    return@setItems
                }
                val ok = Lecture.mergeSpeakers(act, d, from, to)
                if (ok) {
                    Notice.show(act, "✅ «" + fromName + "» بقى «" + toName + "» — التنظيم والتلخيص اتمسحوا، اعملهم من جديد", 3600L)
                    onDone()
                } else {
                    Notice.show(act, "⚠ مفيش متحدث بالاسم ده أو التفريغ شغال", 3000L)
                }
            }.setNegativeButton("إلغاء", null).show()
        }.setNegativeButton("إلغاء", null).show()
    }

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
        col.addView(card("📞", "المكالمات المسجلة", "أسماء من جهات الاتصال · بصمة صوت · فرّغ المكالمة") { openCalls() }, lp(-1, -2, 8))   // (v994)
        col.addView(card("🕘", "فيديوهات شفتها قبل كده", "سجل يوتيوب · المتصفح · فيديوهات الجهاز — اختار منها وابدأ") { openWatched() }, lp(-1, -2, 8))
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
            t.addView(ui.text(Studio.displayTitle(d).ifBlank { "محاضرة" }, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val meta = listOf(statusOf(d), if (d.durSec > 0) fmtDur(d.durSec) else "", if (d.words > 0) "${d.words} كلمة" else "", if (d.lang.isNotBlank()) "🌐 " + d.lang else "").filter { it.isNotBlank() }.joinToString(" · ")
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
            row.isClickable = true; Glass.pressable(row); row.setOnClickListener { open(d) }
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

    // (v1001) type: نوع المحتوى — لو مش فاضي بيتثبّت عبر Studio.setType بعد newDoc (زي StudioUi.begin).
    // parties: أطراف المكالمة — بتتثبّت بس على دوكيومنت جديد لسه نوعه فاضي (d.type.isBlank() قبل setType)؛
    // دوكيومنت مستأنف ليه نوع أصلًا (مثلًا اتفرّغ قبل كده كمحاضرة) مبيتلمسش — لا نوعه ولا أطرافه ولا تفريغه.
    private fun begin(kind: String, title: String, uri: String, link: String, type: String = "", parties: List<String> = emptyList()) {
        val d = Lecture.newDoc(act, kind, title, uri, link)
        val fresh = d.type.isBlank()
        if (type.isNotBlank()) {
            try {
                Studio.setType(act, d, type, false)
            } catch (e: Throwable) {
                LogStore.err("LectureUi:setType", e)
            }
        }
        if (fresh && parties.isNotEmpty()) {
            synchronized(d) {
                d.parties.clear()
                d.parties.addAll(parties)
            }
            Lecture.save(act, d)
        }
        if (hist.none { it.id == d.id }) hist = listOf(d) + hist
        main.post { openDoc(d, !d.complete) }
    }

    // ===================== فيديوهات شفتها قبل كده (السجل العام) =====================
    private fun openWatched() {
        var src = "youtube"
        val list = vcol()
        lateinit var d: Dialog
        fun render() {
            list.removeAllViews()
            val items = PubHistory.list("v", src)
            if (items.isEmpty()) { list.addView(ui.text("مفيش حاجة في السجل ده لسه.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)) }); return }
            for (x in items.take(150)) {
                val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
                row.addView(ui.text(x.title, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                row.addView(ui.text(java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm", java.util.Locale.US).format(java.util.Date(x.ts)), 11.5f, th.muted).apply { maxLines = 1 })
                row.isClickable = true; Glass.pressable(row)
                row.setOnClickListener {
                    d.dismiss()
                    when (src) {
                        "youtube" -> begin("link", x.title, "", YtHistory.watchUrl(x.ref))
                        "local" -> begin("video", x.title, x.ref, "")
                        else -> begin("link", x.title, "", x.ref)
                    }
                }
                list.addView(row, lp(-1, -2, 6))
            }
        }
        val content = vcol().apply { layoutParams = lp(-1, -1) }
        content.addView(segRow(listOf("youtube" to "▶ يوتيوب", "browser" to "🌐 المتصفح", "local" to "📁 الجهاز"), { src }, { src = it; render() }), lp(-1, -2))
        content.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        d = ui.sheet(act, "🕘 اختار فيديو من السجل", listOf<View>(content), fixedH = true)
        d.show()
        render()
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

    // ===================== (v994) 📞 المكالمات المسجلة — أسماء من الجهات + بصمة الصوت (CallKit) =====================
    /** مدخل من «المزيد» */
    fun showCalls() = openCalls()

    /**
     * (v995) فتح شاشة تفريغ محفوظة من «الاستوديو» — نفس شاشة openDoc الخاصة، من غير بدء تلقائي
     * (التفريغ لو شغال بيكمّل لوحده، ولو واقف المستخدم بيدوس «كمّل» من جوّا الشاشة).
     * (v1000) startSec: قفز للحظة دي بعد الفتح — من نتائج البحث في سجل الاستوديو.
     */
    fun open(d: Lecture.Doc, startSec: Double = 0.0) {
        // (v1001) المكالمات بتتفتح في صفحة الدردشة — القايمة لسه متاحة من جوّاها عبر openList.
        if (Studio.typeOf(d).code == "call") {
            try {
                CallChatUi(act, ui, th).show(d, startSec)
            } catch (e: Throwable) {
                LogStore.err("LectureUi:chat", e)
                openDoc(d, false, startSec)
            }
        } else openDoc(d, false, startSec)
    }

    /** قايمة الجمل + تبويبات التنظيم/التلخيص مباشرة (من غير تحويلة الدردشة) — للمكالمات من جوّا صفحة الدردشة. */
    fun openList(d: Lecture.Doc) { openDoc(d, false) }

    private class CallRow(val rec: Lecture.Rec, val who: CallKit.Who)

    private fun callTitle(r: CallRow): String = when {
        r.who.name.isNotBlank() -> "👤 " + r.who.name
        r.who.number.isNotBlank() -> "📞 " + r.who.number
        else -> r.rec.name.substringBeforeLast('.')
    }

    private fun callKey(r: CallRow): String = CallKit.contactKey(r.who.name.ifBlank { r.who.number.ifBlank { r.rec.name.substringBeforeLast('.') } })

    private fun openCalls() {
        if (!Lecture.hasAudioPermission(act)) {
            Notice.show(act, "محتاج إذن الصوتيات الأول — اسمح بيه وبعدين ادوس تاني", 3600L)
            try { act.requestPermissions(arrayOf(Lecture.audioPermission()), 12) } catch (_: Throwable) {}
            return
        }
        var showAll = false
        var scanning = true
        var q = ""
        var rows: List<CallRow> = emptyList()
        val list = vcol()
        val info = ui.text("⏳ بدوّر على التسجيلات…", 12f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        lateinit var d: Dialog

        fun render() {
            list.removeAllViews()
            val src = (if (showAll) rows else rows.filter { it.rec.isRec }).filter { q.isBlank() || callTitle(it).contains(q, true) || it.rec.name.contains(q, true) }
            info.text = when {
                scanning -> "⏳ بدوّر على التسجيلات…"
                src.isEmpty() -> if (showAll) "مفيش ملفات صوت" else "مفيش تسجيلات متعرّف عليها — جرّب «كل الصوتيات»"
                else -> "${src.size} ملف"
            }
            for (r in src.take(200)) {
                val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
                row.addView(ui.text(callTitle(r), 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                val date = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(java.util.Date(r.rec.dateSec * 1000L))
                val voice = if (CallKit.hasVoice(act, callKey(r))) "🎤 بصمة محفوظة" else ""
                val sub = listOf(fmtDur(r.rec.durMs / 1000.0), date, if (r.who.name.isNotBlank()) r.who.number else "", voice).filter { it.isNotBlank() }.joinToString(" · ")
                row.addView(ui.text(sub, 11.5f, th.muted).apply { maxLines = 1 })
                row.isClickable = true; Glass.pressable(row)
                row.setOnClickListener { callActions(r) { d.dismiss() } }
                list.addView(row, lp(-1, -2, 6))
            }
        }

        val search = ui.input("🔍 دوّر باسم أو رقم", "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { q = s?.toString()?.trim() ?: ""; render() }
        })
        val content = vcol().apply { layoutParams = lp(-1, -1) }
        content.addView(ui.text("اختار مكالمة: فرّغها، أو احفظ مقطع مرجعي لصوت الشخص (بصمة الصوت) عشان التفريغ يعرف مين بيتكلم.", 12f, th.muted), lp(-1, -2))
        if (!CallKit.hasContactsPerm(act)) {
            content.addView(pill("👥 اسمح بجهات الاتصال عشان تظهر أسماء الأشخاص") {
                try { act.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 15) } catch (_: Throwable) {}
                Notice.show(act, "اسمح وبعدين اقفل الشاشة وافتحها تاني", 3200L)
            }, lp(-1, -2, 6))
        }
        content.addView(segRow(listOf("rec" to "🎙 التسجيلات", "all" to "🎵 كل الصوتيات"), { if (showAll) "all" else "rec" }, { showAll = it == "all"; render() }), lp(-1, -2, 6))
        content.addView(search, lp(-1, -2, 4))
        content.addView(info)
        content.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        d = ui.sheet(act, "📞 المكالمات المسجلة", listOf<View>(content), fixedH = true)
        d.show()
        Thread {
            val out = ArrayList<CallRow>()
            try {
                for (r in Lecture.scanAudio(act)) out.add(CallRow(r, if (out.size < 300) CallKit.resolve(act, r.name) else CallKit.parseFileName(r.name)))
            } catch (e: Throwable) { LogStore.err("LectureUi:calls", e) }
            main.post { rows = out; scanning = false; if (!showAll && out.none { it.rec.isRec }) showAll = true; render() }
        }.apply { isDaemon = true }.start()
        render()
    }

    private fun callActions(r: CallRow, closeList: () -> Unit) {
        val labels = arrayOf("🎓 فرّغ المكالمة دي", "🎤 احفظ بصمة صوت الشخص ده من المكالمة", "🎤 احفظ بصمة صوتي أنا من المكالمة")
        GAlert(act).setTitle(callTitle(r)).setItems(labels) { _, w ->
            when (w) {
                0 -> {
                    closeList()
                    val other = r.who.name.ifBlank { "طرف تاني" }
                    val parties = if (CallKit.hasVoice(act, "me")) listOf("أنا", other) else listOf(other)
                    begin("rec", r.who.name.ifBlank { r.rec.name.substringBeforeLast('.') }, r.rec.uri.toString(), "", "call", parties)
                }
                1 -> saveVoiceDialog(r, false)
                else -> saveVoiceDialog(r, true)
            }
        }.setNegativeButton("إغلاق", null).show()
    }

    /** يقتطع ~7 ثواني من المكالمة (من الثانية اللي يحددها المستخدم) ويحفظها كبصمة صوت عبر CallKit */
    private fun saveVoiceDialog(r: CallRow, mine: Boolean) {
        val nameEt = ui.input("اسم الشخص", if (mine) "أنا" else r.who.name.ifBlank { r.who.number })
        val startEt = ui.input("من ثانية كام؟ (مثلاً 0)", "0")
        val box = vcol()
        box.addView(nameEt, lp(-1, -2))
        box.addView(startEt, lp(-1, -2, 8))
        GAlert(act).setTitle(if (mine) "🎤 بصمة صوتي" else "🎤 بصمة صوت الشخص").setMessage("هيقتطع 7 ثواني من المكالمة من الثانية اللي تحددها. اختار جزء الشخص ده بس هو اللي بيتكلم فيه وصوته واضح (من غير ما حد يقاطعه).").setView(box)
            .setPositiveButton("احفظ") { _, _ ->
                val nm = nameEt.text.toString().trim().ifBlank { if (mine) "أنا" else "شخص" }
                val st0 = startEt.text.toString().trim().toDoubleOrNull() ?: 0.0
                val key = if (mine) "me" else CallKit.contactKey(nm)
                Notice.show(act, "⏳ بقتطع المقطع…", 1800L)
                Thread {
                    var msg: String
                    val src = try { AudioSources.make(act, r.rec.uri, null, emptyMap(), 1) { LogStore.add("🎤 $it") } } catch (e: Throwable) { LogStore.err("LectureUi:voiceSrc", e); null }
                    if (src == null) msg = "⚠ مقدرتش أفتح التسجيل"
                    else try {
                        val dur = src.durationSec()
                        val st = st0.coerceIn(0.0, Math.max(0.0, dur - 1.0))
                        val c = src.wav(st, Math.min(dur, st + 7.0))
                        msg = if (c == null || c.silent || c.bytes.size < 4000) "⚠ المقطع ده صامت أو قصير — جرّب ثانية تانية"
                        else { CallKit.saveVoice(act, key, nm, c.bytes); "✅ اتحفظت بصمة صوت: $nm" }
                    } catch (e: Throwable) { LogStore.err("LectureUi:voice", e); msg = "⚠ فشل اقتطاع المقطع" }
                    finally { try { src.close() } catch (_: Throwable) {} }
                    main.post { Notice.show(act, msg, 3200L) }
                }.apply { isDaemon = true }.start()
            }.setNegativeButton("إلغاء", null).show()
    }

    // ===================== شاشة المحاضرة =====================
    private fun openDoc(d: Lecture.Doc, autoStart: Boolean, startSec: Double = 0.0) {
        var tab = "tr"                       // tr · org · sum
        // (v999 ملاحظة 2) التوقيت ظاهر افتراضيًا في أنواع الترجمة (subs/show) — ومقفول افتراضيًا للمحاضرات.
        var stamps = Studio.typeOf(d).code == "subs" || Studio.typeOf(d).code == "show"
        // (v1000) قايمة الجمل الحيّة بقت لكل الأنواع — مش subs/show بس: كل نوع بقى فيه
        // صفوف بتوقيتات ومتحدثين ودوسة للتعديل (نفس sentRow/editSegDialog).
        val isSentList = true
        var busy = false
        var lastSig = ""
        var alive = true
        var building = false
        var buildMsg = ""
        if (autoStart && !Lecture.isRunning(d.id)) Lecture.start(act, d)

        // (v1000) العنوان التلقائي بيظهر هنا كمان — نفس قاعدة Studio.displayTitle
        // (تلقائي بس لو العنوان الحقيقي عام)؛ قبل كده كان d.title بس فكان التلقائي مختفي جوه الشاشة.
        val title = ui.text(Studio.displayTitle(d).ifBlank { "محاضرة" }, 15f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
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
            if (webUsed) body.addView(ui.text("🌐 النقط اللي عليها 🌐 جت من النت مش من الكلام الأصلي", 11.5f, amber).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(6)) })
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
                    if (isSentList) {
                        // (v999 ملاحظة 2) قايمة جمل حيّة لأنواع الترجمة: كل جملة صف بتوقيتها ومتحدثها،
                        // بتكبر مع كل جزء بيخلص، ودوسة على أي جملة بتفتح تعديلها.
                        val rows = Lecture.displaySegs(d)
                        if (rows.isEmpty()) {
                            val r = Lecture.runOf(d.id)
                            body.addView(ui.text(if (r != null && r.running) "⏳ لسه ما وصلش أول جزء…" else "مفيش تفريغ لسه.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12)) })
                        } else {
                            for (s in rows) {
                                body.addView(sentRow(d, s) { seg -> editSegDialog(d, seg) { lastSig = ""; renderAll() } }, lp(-1, -2, 6))
                            }
                            val cs = if (d.chunkSec > 0) d.chunkSec else Lecture.CHUNK_SEC
                            for (c in d.failed.sorted()) {
                                body.addView(ui.text("⚠ [جزء ناقص من " + Lecture.fmtTime(c * cs) + " إلى " + Lecture.fmtTime(minOf(d.durSec, (c + 1) * cs)) + "]", 13f, amber).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) }, lp(-1, -2, 6))
                            }
                        }
                    } else {
                        val txt = Lecture.transcriptText(d, stamps)
                        if (txt.isBlank()) {
                            val r = Lecture.runOf(d.id)
                            body.addView(ui.text(if (r != null && r.running) "⏳ لسه ما وصلش أول جزء…" else "مفيش تفريغ لسه.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12)) })
                        } else body.addView(ui.text(txt, 14f, th.text).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.35f); setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
                    }
                }
                "org" -> {
                    opts.addView(ui.switchRow("🌐 استعن بالنت (تصحيح مصطلحات وإكمال تعريفات)", Cfg.bool("lec_web", false)) { Cfg.put("lec_web", if (it) "1" else "0") })
                    opts.addView(segRow(listOf("ar" to "اكتب بالعربي", "same" to "بلغة التفريغ"), { Cfg.str("lec_out", "ar") }, { Cfg.put("lec_out", it) }), lp(-1, -2, 4))
                    // (v1000) لابلات محايدة للنوع: «المحاضرة» كانت غلط للمكالمات والاجتماعات والمسلسلات.
                    renderOutline(d.organized, "نظّم التفريغ: عناوين رئيسية وتحت كل عنوان نقط قليلة.", if (d.organized.isBlank()) "🗂 نظّم التفريغ" else "↻ إعادة التنظيم", { generateFn("org") }, d.organizedWeb)
                }
                else -> {
                    opts.addView(ui.switchRow("🌐 استعن بالنت (تصحيح مصطلحات وإكمال تعريفات)", Cfg.bool("lec_web", false)) { Cfg.put("lec_web", if (it) "1" else "0") })
                    opts.addView(segRow(listOf("short" to "مختصر", "mid" to "متوسط"), { Cfg.str("lec_sumlen", "mid") }, { Cfg.put("lec_sumlen", it) }), lp(-1, -2, 4))
                    opts.addView(segRow(listOf("ar" to "اكتب بالعربي", "same" to "بلغة التفريغ"), { Cfg.str("lec_out", "ar") }, { Cfg.put("lec_out", it) }), lp(-1, -2, 4))
                    renderOutline(d.summary, "لخّص التفريغ: ملخص قصير + أهم النقط.", if (d.summary.isBlank()) "✂ لخّص التفريغ" else "↻ إعادة التلخيص", { generateFn("sum") }, d.summaryWeb)
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
            // (v999) دقايق اتفرّغت من مدة الفيديو + اللغة المكتشفة — في سطر الحالة.
            val mins = if (d.nChunks > 0 && d.durSec > 0) " · اتفرّغ " + Lecture.fmtMinutes(Lecture.doneMinutes(d)) + " من " + Lecture.fmtMinutes(d.durSec / 60.0) + " دقيقة" else ""
            val lang = if (d.lang.isNotBlank()) " · 🌐 " + d.lang + (if (d.langCode.isNotBlank()) " (" + d.langCode + ")" else "") else ""
            status.text = when {
                building -> buildMsg
                r != null && r.running -> r.status + (if (d.failed.isNotEmpty()) " · ⚠ ${d.failed.size} ناقص" else "") + mins + lang
                r != null && r.status.isNotBlank() && !d.complete -> r.status + mins + lang
                d.complete -> "✅ التفريغ مكتمل — ${d.words} كلمة · ${fmtDur(d.durSec)}" + lang
                d.nChunks == 0 -> "جاهز للتفريغ"
                else -> "⏸ واقف عند ${d.pct}%" + (if (d.failed.isNotEmpty()) " · ⚠ ${d.failed.size} جزء ناقص" else "") + mins + lang
            }
            bar.set(d.pct / 100f)
            bar.visibility = if (d.nChunks == 0 || d.complete) View.GONE else View.VISIBLE
        }

        fun buildFile(kind: String) {
            if (building) return
            if (d.segs.isEmpty()) { Notice.show(act, "فرّغ المحاضرة الأول", 2400L); return }
            building = true; buildMsg = "⏳ بجهّز المذكرة…"; refreshStatus()
            Thread {
                var err = ""; var out: LectureDoc.Built? = null
                try { out = LectureDoc.build(act, d, kind) { m -> buildMsg = m } } catch (e: Throwable) { LogStore.err("LectureUi:build", e); err = e.message ?: e.javaClass.simpleName }
                main.post {
                    building = false; buildMsg = ""; if (alive) refreshStatus()
                    val b = out
                    if (b == null) Notice.show(act, "⚠ " + err.take(120), 3600L)
                    else GAlert(act).setTitle("✅ الملف جاهز").setMessage("اتحفظ في التنزيلات ← Joyboy/Study\n" + b.name)
                        .setPositiveButton("فتح") { _, _ -> LectureDoc.open(act, b) }
                        .setNeutralButton("مشاركة") { _, _ -> LectureDoc.share(act, b) }
                        .setNegativeButton("إغلاق", null).show()
                }
            }.apply { isDaemon = true }.start()
        }

        fun chooseBuild() {
            val labels = arrayOf(
                "📘 Word — محسّن + مُضاف (إضافات موثوقة بالأحمر + صور + لقطات من الفيديو)",
                "📄 Word — خام محسّن (تنظيم وألوان وخريطة ذهنية، بدون إضافات)",
                "🌐 صفحة HTML (شغالة أوفلاين، تصميم المذكرة)",
                "📝 تفريغ TXT", "{ } تفريغ JSON")
            val kinds = arrayOf("docx_plus", "docx_raw", "html", "txt", "json")
            GAlert(act).setTitle("📘 مذكرة المذاكرة").setItems(labels) { _, w -> buildFile(kinds[w]) }.setNegativeButton("إغلاق", null).show()
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
        // (v1000) إدارة المتحدثين — لكل الأنواع: إعادة تسمية / دمج، بحارس التشغيل جوه الديالوجات.
        val spkRow = hrow()
        spkRow.addView(pill("🗣 إعادة تسمية متحدث") { renameSpeakerDialog(d) { lastSig = ""; renderAll(); refreshStatus() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        spkRow.addView(pill("🔀 دمج متحدثين") { mergeSpeakersDialog(d) { lastSig = ""; renderAll(); refreshStatus() } }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(spkRow, lp(-1, -2, 6))
        // (v999 ملاحظة 3) المذكرة للمحاضرات بس — أنواع الترجمة (subs/show/…) والاجتماعات والمكالمات مبتظهرش عندهم
        if (Studio.typeOf(d).code == "lecture") {
            content.addView(pill("📘 مذكرة المذاكرة (Word / HTML)", primary = true) { chooseBuild() }, lp(-1, -2, 6))
        }

        // (v1001) عنوان الشيت حسب النوع — القايمة لسه متاحة للمكالمات من الدردشة فمتنفعش 🎓.
        val sheetEmoji = if (Studio.typeOf(d).code == "call") "📞 " else "🎓 "
        val dd = ui.sheet(act, sheetEmoji + Studio.displayTitle(d).take(28), listOf<View>(content), fixedH = true, onClose = { alive = false; loadHistory() })
        paintCtl(); paintTabs(); refreshStatus(); renderAll()
        dd.show()

        // (v1000) قفز للحظة المطلوبة (من بحث السجل): أول جملة بتبدأ عند startSec أو بعدها —
        // الصفوف في body بنفس ترتيب Lecture.displaySegs فالاندكس هو اندكس الصف.
        if (startSec > 0.0) {
            bodyScroll.post {
                try {
                    val rows = Lecture.displaySegs(d)
                    val idx = rows.indexOfFirst { it.start >= startSec }
                    if (idx >= 0) {
                        val v = body.getChildAt(idx)
                        if (v != null) bodyScroll.scrollTo(0, v.top)
                    }
                } catch (_: Throwable) {}
            }
        }

        // تحديث حي: الحالة كل ثانية، والتفريغ لما عدد الجمل يتغيّر (وهو بيشتغل)
        val ticker = object : Runnable {
            override fun run() {
                if (!alive) return
                try {
                    refreshStatus()
                    val r = Lecture.runOf(d.id)
                    // (v999) الإشارة بتتغيّر كمان لما عدد الجمل أو اللغة يتغيّروا — عشان القايمة الحيّة تكبر مع كل جزء.
                    val sig = d.done.size.toString() + "/" + d.failed.size + "/" + d.segs.size + "/" + d.langCode + "/" + (r?.running ?: false)
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
