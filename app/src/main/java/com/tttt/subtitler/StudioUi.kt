package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
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

/**
 * (v995 — المرحلة 1) 🎙 الاستوديو — مكان واحد للتفريغ والمذاكرة والترجمة والمكالمات.
 * شاشة كروت (ui.sheet) بنفس أسلوب LectureUi.show():
 *  تفريغ محاضرة · تفريغ تسجيل صوتي · تفريغ مكالمة · ترجمة فيديو · تلقائي ✨
 *  + المكالمات المسجلة + سجل التفريغات.
 * شاشة «🎓 تفريغ المحاضرات» الحالية عايشة جوّا الاستوديو (كارت المحاضرة بيفتحها زي ما هي)،
 * وشاشة «📞 المكالمات المسجلة» كمان (LectureUi.showCalls).
 * تدفق الفيديو: مصدر (محلي/يوتيوب/متصفح) ← «تفريغ» ولا «مشاهدة»؟ ← تفريغ (الصوت بس،
 * الفيديو مبيشتغلش) مع اختيار النوع يدوي ← بعد التفريغ: «📝 ترجمة لـSRT» من شاشة التفريغ.
 * الاكتشاف التلقائي للنوع مش هنا — ده شغل المرحلة 2 (TxDetect.kt)، والكارت بتاعه بيقول كده.
 * مفيش هنا أي استكمال/استئناف لترجمات lec: — Studio.canResume(id) = false ليها،
 * والترجمة بتتعمل مرة واحدة من التفريغ الجاهز وبس.
 * المنطق كله في Studio.kt وLecture.kt — الملف ده واجهة بس.
 */
class StudioUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    companion object {
        const val REQ = 7311
        private var pending: ((Uri) -> Unit)? = null
        /** من MainActivity.onActivityResult — نفس نمط LectureUi.REQ */
        fun onResult(act: Activity, c: Int, d: Intent?) {
            val cb = pending
            pending = null
            if (c != Activity.RESULT_OK) return
            val u = d?.data ?: return
            try {
                act.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}
            cb?.invoke(u)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK

    // ===================== مكوّنات صغيرة (نفس أسلوب LectureUi.kt) =====================
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
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
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
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                isClickable = true
                Glass.pressable(this)
                setOnClickListener { onPick(k); paint() }
            }
            views.add(k to v)
            row.addView(v, lp(-2, -2).apply { marginEnd = ui.dp(6) })
        }
        paint()
        return HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(row) }
    }

    private fun card(emoji: String, title: String, sub: String, onClick: () -> Unit): View = hrow().apply {
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        background = glassBg(18)
        addView(ui.text(emoji, 26f).apply { gravity = Gravity.CENTER }, lp(ui.dp(40), -2))
        val t = vcol()
        t.addView(ui.text(title, 14.5f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (sub.isNotBlank()) t.addView(ui.text(sub, 11.5f, th.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        addView(ui.text("‹", 20f, th.muted, true))
        isClickable = true
        Glass.pressable(this)
        setOnClickListener { onClick() }
    }

    private fun fmtDur(sec: Double): String = Lecture.fmtTime(sec)

    private fun nameOf(u: Uri): String = try {
        act.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: (u.lastPathSegment ?: "محاضرة")
    } catch (_: Throwable) { u.lastPathSegment ?: "محاضرة" }

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

    // ===================== الشاشة الرئيسية =====================
    fun show() {
        val cards = vcol()
        cards.addView(card("🎓", "تفريغ محاضرة", "تفريغ حرفي + تنظيم + تلخيص") {
            try {
                LectureUi(act, ui, th).show()
            } catch (e: Throwable) { LogStore.err("StudioUi:lecture", e) }
        }, lp(-1, -2, 10))
        cards.addView(card("🎙", "تفريغ تسجيل صوتي", "ريكوردز وصوتيات الجهاز") {
            try {
                openRecordings("audio", true)
            } catch (e: Throwable) { LogStore.err("StudioUi:audio", e) }
        }, lp(-1, -2, 8))
        cards.addView(card("📞", "تفريغ مكالمة", "مكالمة مسجلة — النوع بيتحدد وانت بتفرّغ") {
            try {
                openRecordings("call", false)
            } catch (e: Throwable) { LogStore.err("StudioUi:call", e) }
        }, lp(-1, -2, 8))
        cards.addView(card("💬", "ترجمة فيديو", "فيديو ← ملف SRT في مكتبة الترجمات") {
            try {
                startVideoFlow("subs")
            } catch (e: Throwable) { LogStore.err("StudioUi:subs", e) }
        }, lp(-1, -2, 8))
        cards.addView(card("✨", "تلقائي", "اختار فيديو أو صوت — النوع يدوي دلوقتي، والاكتشاف التلقائي جاي في المرحلة 2") {
            try {
                autoChoice()
            } catch (e: Throwable) { LogStore.err("StudioUi:auto", e) }
        }, lp(-1, -2, 8))
        cards.addView(card("📞", "المكالمات المسجلة", "أسماء من جهات الاتصال · بصمة صوت") {
            try {
                LectureUi(act, ui, th).showCalls()
            } catch (e: Throwable) { LogStore.err("StudioUi:calls", e) }
        }, lp(-1, -2, 8))
        cards.addView(card("📚", "سجل التفريغات", "كل التفريغات — فتح · ترجمة لـSRT · تغيير النوع") {
            try {
                showHistory()
            } catch (e: Throwable) { LogStore.err("StudioUi:hist", e) }
        }, lp(-1, -2, 8))
        val col = vcol()
        col.layoutParams = lp(-1, -1)
        col.addView(ui.text("مكان واحد للتفريغ والمذاكرة والترجمة والمكالمات.", 12.5f, th.muted), lp(-1, -2))
        val sc = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(cards) }
        col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(4) })
        val d = ui.sheet(act, "🎙 الاستوديو", listOf<View>(col), fixedH = true)
        d.show()
    }

    /** كارت «تلقائي ✨»: المصدر الأول، وبعدها النوع يدوي — الاكتشاف التلقائي شغل المرحلة 2 */
    private fun autoChoice() {
        val labels = arrayOf("🎬 فيديو", "🎙 تسجيل صوتي")
        GAlert(act).setTitle("✨ تلقائي").setMessage("الاكتشاف التلقائي للنوع لسه في المرحلة 2 — دلوقتي اختار المصدر وبعدها اختار النوع يدوي.").setItems(labels) { _, w ->
            if (w == 0) {
                startVideoFlow("")
            } else {
                openRecordings("", true)
            }
        }.setNegativeButton("إلغاء", null).show()
    }

    // ===================== تدفق الفيديو: مصدر ← تفريغ/مشاهدة ← نوع يدوي ← تفريغ =====================
    private fun startVideoFlow(suggested: String) {
        val labels = arrayOf("📁 محلي — من مكتبة الفيديوهات", "▶ يوتيوب — من لينك", "🕘 من سجل المشاهدة (يوتيوب · متصفح · جهاز)")
        GAlert(act).setTitle("اختار مصدر الفيديو").setItems(labels) { _, w ->
            when (w) {
                0 -> VideoPicker.show(act, "اختار الفيديو", { v -> askWatchOrTranscribe("video", v.title, v.uri, "", suggested) }, { pickFile(suggested) })
                1 -> askLink(suggested)
                else -> openWatched(suggested, "youtube")
            }
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun askLink(suggested: String) {
        val et = ui.input("الصق لينك يوتيوب / فيسبوك / ملف…", "")
        try {
            val clip = (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (clip.startsWith("http", true)) et.setText(clip)
        } catch (_: Throwable) {}
        GAlert(act).setTitle("🔗 لينك الفيديو").setMessage("هيجيب الصوت من اللينك ويفرّغه من غير ما الفيديو يشتغل — أو شاهده في المشغّل. فيديوهات الصفحات المقفولة (محتاجة تسجيل دخول) ممكن ما تشتغلش.").setView(et)
            .setPositiveButton("كمّل") { _, _ ->
                val u = et.text.toString().trim()
                if (!u.startsWith("http", true)) {
                    Notice.show(act, "اللينك لازم يبدأ بـ http", 2400L)
                    return@setPositiveButton
                }
                val host = try { Uri.parse(u).host ?: "" } catch (_: Throwable) { "" }
                val t = if (host.isNotBlank()) "لينك: $host" else "فيديو"
                askWatchOrTranscribe("link", t, "", u, suggested)
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun pickFile(suggested: String) {
        if (act !is MainActivity) {
            Notice.show(act, "اختيار الملف متاح من الصفحة الرئيسية — أو اختار من المكتبة/التسجيلات", 3200L)
            return
        }
        pending = { u ->
            val mime = try { act.contentResolver.getType(u) ?: "" } catch (_: Throwable) { "" }
            val kind = if (mime.startsWith("audio")) "audio" else "video"
            val title = nameOf(u).substringBeforeLast('.')
            if (kind == "audio") {
                pickType(suggested) { t -> begin(kind, title, u.toString(), "", t) }
            } else {
                askWatchOrTranscribe(kind, title, u.toString(), "", suggested)
            }
        }
        try {
            act.startActivityForResult(filePicker("*/*", "اختار فيديو أو صوت", true), REQ)
        } catch (e: Throwable) {
            LogStore.err("StudioUi:pick", e)
            Notice.show(act, "⚠ مش قادر أفتح اختيار الملف", 2600L)
        }
    }

    /** سجل المشاهدة العام (يوتيوب/متصفح/جهاز) — نفس نمط LectureUi.openWatched، بس الاختيار بيروح لسؤال «تفريغ ولا مشاهدة» */
    private fun openWatched(suggested: String, firstSrc: String = "youtube") {
        var src = firstSrc
        val list = vcol()
        lateinit var d: Dialog
        fun render() {
            list.removeAllViews()
            val items = PubHistory.list("v", src)
            if (items.isEmpty()) {
                list.addView(ui.text("مفيش حاجة في السجل ده لسه.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)) })
                return
            }
            for (x in items.take(150)) {
                val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
                row.addView(ui.text(x.title, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                row.addView(ui.text(java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm", java.util.Locale.US).format(java.util.Date(x.ts)), 11.5f, th.muted).apply { maxLines = 1 })
                row.isClickable = true
                Glass.pressable(row)
                row.setOnClickListener {
                    d.dismiss()
                    when (src) {
                        "youtube" -> askWatchOrTranscribe("link", x.title, "", YtHistory.watchUrl(x.ref), suggested)
                        "local" -> askWatchOrTranscribe("video", x.title, x.ref, "", suggested)
                        else -> askWatchOrTranscribe("link", x.title, "", x.ref, suggested)
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

    /** التسجيلات والصوتيات — نفس نمط LectureUi.openRecordings، بس الاختيار بيروح لاختيار النوع وبعدها التفريغ */
    private fun openRecordings(presetType: String, allAudio: Boolean) {
        if (!Lecture.hasAudioPermission(act)) {
            Notice.show(act, "محتاج إذن الصوتيات الأول — اسمح بيه وبعدين ادوس تاني", 3600L)
            try {
                act.requestPermissions(arrayOf(Lecture.audioPermission()), 12)
            } catch (_: Throwable) {}
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
                row.isClickable = true
                Glass.pressable(row)
                row.setOnClickListener {
                    d.dismiss()
                    pickType(presetType) { t -> begin(if (r.isRec) "rec" else "audio", r.name.substringBeforeLast('.'), r.uri.toString(), "", t) }
                }
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
        content.addView(ui.text("اختار تسجيل — بعدها اختار نوع المحتوى (محاضرة/مكالمة/…) وبعدها يبدأ التفريغ.", 12f, th.muted), lp(-1, -2))
        content.addView(segRow(listOf("rec" to "🎙 التسجيلات", "all" to "🎵 كل الصوتيات"), { if (showAll) "all" else "rec" }, { showAll = it == "all"; render() }), lp(-1, -2, 6))
        content.addView(search, lp(-1, -2, 4))
        content.addView(info)
        content.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        d = ui.sheet(act, if (presetType == "call") "📞 اختار مكالمة" else "🎙 اختار تسجيل", listOf<View>(content), fixedH = true)
        d.show()
        Thread {
            val l = try { Lecture.scanAudio(act) } catch (e: Throwable) { LogStore.err("StudioUi:scan", e); emptyList() }
            main.post {
                all = l
                scanning = false
                if (!showAll && l.none { it.isRec }) showAll = true
                render()
            }
        }.apply { isDaemon = true }.start()
        render()
    }

    /** «تفريغ» ولا «مشاهدة»؟ — التفريغ بيسحب الصوت بس من غير ما الفيديو يشتغل */
    private fun askWatchOrTranscribe(kind: String, title: String, uri: String, link: String, suggested: String) {
        val labels = arrayOf("🎓 فرّغ — من غير ما الفيديو يشتغل", "▶ شاهده في المشغّل")
        GAlert(act).setTitle(if (title.isBlank()) "فيديو" else title).setMessage("تفريغ ولا مشاهدة؟ التفريغ بيسحب الصوت بس من غير ما الفيديو يشتغل.").setItems(labels) { _, w ->
            if (w == 0) {
                pickType(suggested) { t -> begin(kind, title, uri, link, t) }
            } else {
                watchVideo(title, uri, link)
            }
        }.setNegativeButton("إلغاء", null).show()
    }

    /** اختيار النوع يدوي — الاكتشاف التلقائي جاي في المرحلة 2 */
    private fun pickType(suggested: String, onPick: (String) -> Unit) {
        val types = Studio.TxType.all
        val labels = types.map { it.emoji + " " + it.label }.toTypedArray()
        val msg = if (suggested.isNotBlank()) {
            "المقترح: " + Studio.TxType.fromCode(suggested).label + " — الاكتشاف التلقائي للنوع جاي في المرحلة 2، دلوقتي اختار يدوي."
        } else {
            "الاكتشاف التلقائي للنوع جاي في المرحلة 2 — دلوقتي اختار يدوي."
        }
        GAlert(act).setTitle("اختار نوع المحتوى").setMessage(msg).setItems(labels) { _, w ->
            onPick(types[w].code)
        }.setNegativeButton("إلغاء", null).show()
    }

    /** يبدأ التفريغ: نوع يدوي (لو متحدد) ← Lecture.start (الصوت بس، الفيديو مبيشتغلش) ← شاشة التفريغ */
    private fun begin(kind: String, title: String, uri: String, link: String, type: String) {
        val d = try {
            Lecture.newDoc(act, kind, title, uri, link)
        } catch (e: Throwable) {
            LogStore.err("StudioUi:newDoc", e)
            Notice.show(act, "⚠ مقدرتش أبدأ التفريغ", 2600L)
            return
        }
        if (type.isNotBlank()) {
            try {
                Studio.setType(act, d, type, false)
            } catch (e: Throwable) {
                LogStore.err("StudioUi:setType", e)
            }
        }
        try {
            Lecture.start(act, d)
        } catch (e: Throwable) {
            LogStore.err("StudioUi:start", e)
            Notice.show(act, "⚠ مقدرتش أبدأ التفريغ", 2600L)
            return
        }
        Notice.show(act, "⏳ التفريغ بدأ — تقدر تتابعه من سجل التفريغات", 2800L)
        try {
            LectureUi(act, ui, th).open(d)
        } catch (e: Throwable) {
            LogStore.err("StudioUi:open", e)
        }
    }

    /** المشاهدة — المسار العادي للمشغّل، مش مسار التفريغ */
    private fun watchVideo(title: String, uri: String, link: String) {
        try {
            if (link.isNotBlank()) {
                val yt = Links.ytId(link)
                if (yt != null) {
                    if (!MeeTubeLink.playYt(act, yt, 0L)) {
                        MeeTubeLink.openUrl(act, YtHistory.watchUrl(yt))
                    }
                    return
                }
                val pi = Intent(act, PlayerActivity::class.java).pipTask(act).putExtra("url", link).putExtra("title", title).putExtra("nosub", true).putExtra("landscape", false).putExtra("fresh", false)
                if (!MtPlay.fastOpen(act, pi)) {
                    act.startActivity(pi)
                }
                return
            }
            if (uri.isNotBlank()) {
                val pi = Intent(act, PlayerActivity::class.java).pipTask(act).apply {
                    data = Uri.parse(uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("landscape", true)
                    putExtra("fresh", false)
                    putExtra("nosub", true)
                }
                if (!MtPlay.fastOpen(act, pi)) {
                    act.startActivity(pi)
                }
                return
            }
            Notice.show(act, "⚠ مفيش فيديو يتفتح", 2200L)
        } catch (e: Throwable) {
            LogStore.err("StudioUi:watch", e)
            Notice.show(act, "⚠ مقدرتش أفتح الفيديو", 2600L)
        }
    }

    // ===================== سجل التفريغات =====================
    private var histShown = false
    private var hist: List<Lecture.Doc> = emptyList()
    private val histCol = vcol()
    private var histTick: Runnable? = null

    fun showHistory() {
        val col = vcol()
        col.layoutParams = lp(-1, -1)
        col.addView(ui.text("كل التفريغات — دوس على أي واحدة عشان تفتحها أو تترجمها لـSRT أو تغيّر نوعها.", 12.5f, th.muted), lp(-1, -2))
        col.addView(pill("↻ تحديث") { loadHistory() }, lp(-1, -2, 8))
        val sc = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(histCol) }
        col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(4) })
        histShown = true
        val d = ui.sheet(act, "📚 سجل التفريغات", listOf<View>(col), fixedH = true, onClose = { histShown = false; histTick?.let { main.removeCallbacks(it) } })
        d.show()
        loadHistory()
        val t = object : Runnable {
            override fun run() {
                if (!histShown) return
                if (hist.any { Lecture.isRunning(it.id) }) renderHistory()
                main.postDelayed(this, 1200L)
            }
        }
        histTick = t
        main.postDelayed(t, 1200L)
    }

    private fun loadHistory() {
        Thread {
            val l = try { Lecture.list(act) } catch (e: Throwable) { LogStore.err("StudioUi:list", e); emptyList() }
            main.post {
                hist = l
                if (histShown) renderHistory()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun renderHistory() {
        histCol.removeAllViews()
        if (hist.isEmpty()) {
            histCol.addView(ui.text("مفيش تفريغات لسه — اختار مصدر من شاشة الاستوديو.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)) })
            return
        }
        for (d in hist) {
            val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(16) }
            val top = hrow()
            val t = vcol()
            t.addView(ui.text(Studio.displayTitle(d).ifBlank { "محاضرة" }, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val tt = Studio.typeOf(d)
            val meta = listOf(tt.emoji + " " + tt.label, statusOf(d), if (d.durSec > 0) fmtDur(d.durSec) else "", if (d.words > 0) "${d.words} كلمة" else "").filter { it.isNotBlank() }.joinToString(" · ")
            t.addView(ui.text(meta, 11.5f, th.muted).apply { maxLines = 1 })
            top.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(ui.text("🗑", 16f, th.muted).apply {
                setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6))
                isClickable = true
                Glass.pressable(this)
                setOnClickListener { confirmDelete(d) }
            })
            row.addView(top)
            row.isClickable = true
            Glass.pressable(row)
            row.setOnClickListener { openDocSheet(d) }
            histCol.addView(row, lp(-1, -2, 8))
        }
    }

    private fun confirmDelete(d: Lecture.Doc) {
        GAlert(act).setTitle("🗑 حذف التفريغ").setMessage("«${Studio.displayTitle(d).take(50)}» — هيتمسح التفريغ والتنظيم والتلخيص.")
            .setPositiveButton("حذف") { _, _ ->
                Lecture.delete(act, d.id)
                hist = hist.filter { it.id != d.id }
                renderHistory()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    // ===================== شاشة التفريغ: النوع ✎ + العنوان التلقائي + ترجمة لـSRT =====================
    private fun openDocSheet(d: Lecture.Doc) {
        val titleV = ui.text("المحاضرة: " + Studio.displayTitle(d).ifBlank { "محاضرة" }, 15f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val typeChip = pill("النوع: " + Studio.typeOf(d).emoji + " " + Studio.typeOf(d).label + " ✎") {}
        val typeNote = ui.text("تغيير النوع بيعيد التنظيم والعرض من نفس التفريغ — الصوت مبيتفرّغش من جديد.", 11.5f, th.muted)
        val metaV = ui.text("", 12f, th.muted)
        val srtStatus = ui.text("", 12.5f, th.muted)
        fun paintType() {
            val t = Studio.typeOf(d)
            typeChip.text = "النوع: " + t.emoji + " " + t.label + " ✎"
        }
        fun paintMeta() {
            val t = Studio.typeOf(d)
            val meta = listOf(statusOf(d), if (d.durSec > 0) fmtDur(d.durSec) else "", if (d.words > 0) "${d.words} كلمة" else "", if (d.segs.isNotEmpty()) "${d.segs.size} جملة" else "", "الناتج: " + t.outputShape).filter { it.isNotBlank() }.joinToString(" · ")
            metaV.text = meta
        }
        typeChip.setOnClickListener {
            pickType(Studio.typeOf(d).code) { code ->
                var ok = true
                try {
                    Studio.setType(act, d, code, false)
                } catch (e: Throwable) {
                    LogStore.err("StudioUi:setType", e)
                    ok = false
                }
                if (!ok) {
                    Notice.show(act, "⚠ مقدرتش أغيّر النوع", 2600L)
                } else {
                    paintType()
                    paintMeta()
                    renderHistory()
                    Notice.show(act, "✅ النوع بقى «" + Studio.typeOf(d).label + "» — من غير إعادة تفريغ", 2800L)
                }
            }
        }
        paintType()
        paintMeta()
        lateinit var dd: Dialog
        val content = vcol().apply { layoutParams = lp(-1, -1) }
        content.addView(titleV)
        content.addView(typeChip, lp(-1, -2, 8))
        content.addView(typeNote, lp(-1, -2, 4))
        content.addView(metaV, lp(-1, -2, 6))
        content.addView(srtStatus, lp(-1, -2, 6))
        content.addView(pill("📖 فتح التفريغ", primary = true) {
            try {
                LectureUi(act, ui, th).open(d)
            } catch (e: Throwable) { LogStore.err("StudioUi:openDoc", e) }
        }, lp(-1, -2, 8))
        content.addView(pill("📝 ترجمة لـSRT") { runSrt(d, srtStatus) }, lp(-1, -2, 8))
        content.addView(pill("📚 فتح مكتبة الترجمات") {
            try {
                TrLibUi(act, ui, th).show()
            } catch (e: Throwable) { LogStore.err("StudioUi:trlib", e) }
        }, lp(-1, -2, 8))
        content.addView(ui.text("الترجمة بتظهر في 📚 مكتبة الترجمات — ولو المصدر لينك، من غير ملف SRT جنب الفيديو (التفريغ نفسه مفيهوش ملف محلي).", 11.5f, th.muted), lp(-1, -2, 4))
        content.addView(pill("🗑 حذف التفريغ", danger = true) {
            GAlert(act).setTitle("🗑 حذف التفريغ").setMessage("«${Studio.displayTitle(d).take(50)}» — هيتمسح التفريغ والتنظيم والتلخيص.")
                .setPositiveButton("حذف") { _, _ ->
                    Lecture.delete(act, d.id)
                    hist = hist.filter { it.id != d.id }
                    renderHistory()
                    try {
                        dd.dismiss()
                    } catch (_: Throwable) {}
                }
                .setNegativeButton("إلغاء", null).show()
        }, lp(-1, -2, 10))
        dd = ui.sheet(act, "🎙 " + Studio.displayTitle(d).take(28), listOf<View>(content), fixedH = true)
        dd.show()
    }

    /** ترجمة التفريغ الجاهز لـSRT بمحرك الترجمة الحالي — على خيط خلفي، والنتيجة في مكتبة الترجمات */
    private fun runSrt(d: Lecture.Doc, statusV: TextView) {
        val n = synchronized(d) { d.segs.size }
        if (n == 0) {
            Notice.show(act, "مفيش تفريغ لسه — فرّغ الأول", 2600L)
            return
        }
        statusV.text = "⏳ بترجم…"
        Thread {
            var res: Studio.SrtResult? = null
            try {
                res = Studio.translateToSrt(act, d, report = { m -> main.post { statusV.text = "⏳ " + m } })
            } catch (e: Throwable) {
                LogStore.err("StudioUi:srt", e)
            }
            val r = res
            main.post {
                if (r == null) {
                    statusV.text = "⚠ فشلت الترجمة"
                    Notice.show(act, "⚠ فشلت الترجمة", 3000L)
                } else if (!r.ok) {
                    statusV.text = "⚠ " + r.error.take(100)
                    Notice.show(act, "⚠ " + r.error.take(100), 3600L)
                } else {
                    val noFile = r.srtPath == null
                    val failedNote = if (r.failed > 0) " · " + r.failed + " سطر فشل واتساب بنصه الأصلي" else ""
                    statusV.text = "✅ اتعملت الترجمة (" + r.subs + " سطر" + failedNote + ")"
                    if (noFile) {
                        Notice.show(act, "✅ الترجمة ظهرت في 📚 مكتبة الترجمات — من غير ملف SRT جنب الفيديو لأن المصدر لينك", 4200L)
                    } else {
                        Notice.show(act, "✅ اتعمل ملف SRT وظهر في 📚 مكتبة الترجمات", 3200L)
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
