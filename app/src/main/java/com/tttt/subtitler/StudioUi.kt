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
 * الفيديو مبيشتغلش) مع اختيار النوع يدوي أو تلقائي ← بعد التفريغ: «📝 ترجمة لـSRT» من شاشة التفريغ.
 * (v1000 — المرحلة 2) الاكتشاف التلقائي للنوع بقى شغال: Lecture.start() بينده TxDetect قبل
 * التفريغ لما النوع يبقى فاضي، واختيار «✨ تلقائي» في pickType بيسيب النوع فاضي عشان الاكتشاف يشتغل.
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
        cards.addView(card("✨", "تلقائي", "اختار فيديو أو صوت — النوع بيتحدد تلقائي وانت بتفرّغ، وتقدر تغيّره يدوي بعدين") {
            try {
                autoChoice()
            } catch (e: Throwable) { LogStore.err("StudioUi:auto", e) }
        }, lp(-1, -2, 8))
        cards.addView(card("📞", "المكالمات المسجلة", "أسماء من جهات الاتصال · بصمة صوت") {
            try {
                LectureUi(act, ui, th).showCalls()
            } catch (e: Throwable) { LogStore.err("StudioUi:calls", e) }
        }, lp(-1, -2, 8))
        cards.addView(card("🎤", "الأصوات المحفوظة", "بصمات صوت جهات الاتصال — إعادة تسمية · حذف") {
            try {
                CallChatUi(act, ui, th).showVoices()
            } catch (e: Throwable) { LogStore.err("StudioUi:voices", e) }
        }, lp(-1, -2, 8))
        cards.addView(card("📚", "سجل التفريغات", "كل التفريغات — فتح · ترجمة لـSRT · تغيير النوع") {
            try {
                showHistory()
            } catch (e: Throwable) { LogStore.err("StudioUi:hist", e) }
        }, lp(-1, -2, 8))
        cards.addView(card("📚", "مكتبة الترجمات", "ترجمات الفيديو المحفوظة — فتح · استكمال · تصدير") {
            try {
                TrLibUi(act, ui, th).show()
            } catch (e: Throwable) { LogStore.err("StudioUi:trlib", e) }
        }, lp(-1, -2, 8))
        val col = vcol()
        col.layoutParams = lp(-1, -1)
        col.addView(ui.text("مكان واحد للتفريغ والمذاكرة والترجمة والمكالمات.", 12.5f, th.muted), lp(-1, -2))
        val sc = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(cards) }
        col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(4) })
        val d = ui.sheet(act, "🎙 الاستوديو", listOf<View>(col), fixedH = true)
        d.show()
    }

    /** كارت «تلقائي ✨»: المصدر الأول، وبعدها النوع تلقائي أو يدوي — الاكتشاف التلقائي شغال من المرحلة 2 */
    private fun autoChoice() {
        val labels = arrayOf("🎬 فيديو", "🎙 تسجيل صوتي")
        GAlert(act).setTitle("✨ تلقائي").setMessage("اختار المصدر — وبعدها اختار النوع يدوي، أو سيبه «✨ تلقائي» والبرنامج يحدده أول ما التفريغ يبدأ.").setItems(labels) { _, w ->
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
        // (v1000) زرار «فرّغ» واحد بس: التفريغ الافتراضي بقى وارد، فالاختيار المنفصل «عن طريق وارد» بقى زايد.
        val labels = arrayOf("🎓 فرّغ — من غير ما الفيديو يشتغل", "▶ شاهده في المشغّل")
        GAlert(act).setTitle(if (title.isBlank()) "فيديو" else title).setMessage("تفريغ ولا مشاهدة؟ التفريغ بيسحب الصوت بس من غير ما الفيديو يشتغل.").setItems(labels) { _, w ->
            if (w == 0) {
                pickType(suggested) { t -> begin(kind, title, uri, link, t) }
            } else {
                watchVideo(title, uri, link)
            }
        }.setNegativeButton("إلغاء", null).show()
    }

    /** اختيار النوع: يدوي، أو «✨ تلقائي» — بيسيب النوع فاضي (onPick("")) فالاكتشاف
     * التلقائي (TxDetect عبر Lecture.start) يحدده أول ما التفريغ يبدأ. begin() مبيعملش
     * setType أصلًا لما النوع فاضي. autoDoc: لما البكّر يتفتح من شريحة نوع متحدد تلقائي —
     * الرسالة بتسمّي النوع المكتشف وثقته عشان المستخدم يأكّد أو يغيّر.
     */
    private fun pickType(suggested: String, autoDoc: Lecture.Doc? = null, onPick: (String) -> Unit) {
        val types = Studio.TxType.all
        val labels = ArrayList<String>()
        labels.add("✨ تلقائي — البرنامج يحدد النوع لوحده")
        labels.addAll(types.map { it.emoji + " " + it.label })
        val msg = if (autoDoc != null && autoDoc.typeAuto) {
            "النوع اتحدد تلقائي: «" + Studio.typeOf(autoDoc).label + "» (ثقة " + (autoDoc.typeConf * 100).toInt() + "٪) — أكّد عليه أو اختار نوع تاني يدوي، أو سيبه تلقائي زي ما هو."
        } else if (suggested.isNotBlank()) {
            "المقترح: " + Studio.TxType.fromCode(suggested).label + " — أو سيبه تلقائي والبرنامج يحدد النوع أول ما التفريغ يبدأ."
        } else {
            "اختار نوع يدوي، أو سيبه تلقائي والبرنامج يحدد النوع أول ما التفريغ يبدأ."
        }
        GAlert(act).setTitle("اختار نوع المحتوى").setMessage(msg).setItems(labels.toTypedArray()) { _, w ->
            if (w == 0) onPick("") else onPick(types[w - 1].code)
        }.setNegativeButton("إلغاء", null).show()
    }

    /** يبدأ التفريغ: نوع يدوي (لو متحدد) ← Lecture.start (الصوت بس، الفيديو مبيشتغلش) ← شاشة التفريغ */
    /** (v998) warid=true = تفريغ بطريقة وارد. الصوتيات (audio/rec) بتسأل عن الطريقة هنا، لأنها مبتعدّيش على سؤال «تفريغ ولا مشاهدة» */
    private fun begin(kind: String, title: String, uri: String, link: String, type: String, warid: Boolean = false) {
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
        if (warid) {
            beginWarid(d)
            return
        }
        // (v1000) مفيش سؤال عن الطريقة هنا خلاص: التفريغ الافتراضي بقى وارد، فالخيارين كانوا هيودّوا لنفس المكان.
        // تحويل دوكيومنت عادي قديم لوارد صراحةً لسه موجود عبر beginWarid() (بموافقة على مسح القديم).
        startNormal(d)
    }

    /** التفريغ العادي: Lecture.start ← شاشة التفريغ */
    private fun startNormal(d: Lecture.Doc) {
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

    /** (v998) تفريغ وارد: لو فيه تفريغ عادي قديم هيتمسح الأول (أجزاء ٦٠ ثانية مبتكملش على أجزاء وارد) — بموافقة المستخدم */
    private fun beginWarid(d: Lecture.Doc) {
        if (Lecture.isRunning(d.id)) {
            Notice.show(act, "التفريغ شغال أصلًا — استنى يخلص", 2600L)
            try {
                LectureUi(act, ui, th).open(d)
            } catch (e: Throwable) {
                LogStore.err("StudioUi:open", e)
            }
            return
        }
        val hasOld = synchronized(d) { (d.segs.isNotEmpty() || d.done.isNotEmpty() || d.failed.isNotEmpty()) && !d.warid }
        if (hasOld) {
            GAlert(act).setTitle("🌐 التفريغ عن طريق وارد").setMessage("المصدر ده متفريغ قبل كده بالطريقة العادية — التفريغ القديم هيتمسح ويتفرّغ من جديد بطريقة وارد (أجزاء ٥ أو ١٠ دقايق).").setPositiveButton("امسح وفرّغ بوارد") { _, _ -> startWaridNow(d) }.setNegativeButton("إلغاء", null).show()
            return
        }
        startWaridNow(d)
    }

    /** (v998) تشغيل وارد الفعلي بعد الموافقة */
    private fun startWaridNow(d: Lecture.Doc) {
        try {
            WaridTx.startWarid(act, d)
        } catch (e: Throwable) {
            LogStore.err("StudioUi:waridStart", e)
            Notice.show(act, "⚠ مقدرتش أبدأ التفريغ بوارد", 2600L)
            return
        }
        Notice.show(act, "⏳ التفريغ بوارد بدأ — تقدر تتابعه من سجل التفريغات", 2800L)
        try {
            LectureUi(act, ui, th).open(d)
        } catch (e: Throwable) {
            LogStore.err("StudioUi:open", e)
        }
    }

    /** المشاهدة — المسار العادي للمشغّل، مش مسار التفريغ.
     * (v999 ملاحظة 4) nosub=false: ترجمة الاستوديو لازم تعرض لوحدها لما الفيديو يتفتح —
     * nosub=true كانت بتخفي الترجمة (ccOn=false) لحد ما المستخدم يدوس «ترجمة».
     * (v1000) startSec: قفز للحظة دي — يوتيوب عبر MeeTubeLink.playYt(startMs)، والمحلي/اللينك
     * عبر extra «startms» اللي Player.kt بيقراه (getLongExtra("startms") وبيعمل seekTo).
     */
    private fun watchVideo(title: String, uri: String, link: String, startSec: Double = 0.0) {
        val startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L)
        try {
            if (link.isNotBlank()) {
                val yt = Links.ytId(link)
                if (yt != null) {
                    if (!MeeTubeLink.playYt(act, yt, startMs)) {
                        MeeTubeLink.openUrl(act, YtHistory.watchUrl(yt))
                    }
                    return
                }
                val pi = Intent(act, PlayerActivity::class.java).pipTask(act).putExtra("url", link).putExtra("title", title).putExtra("nosub", false).putExtra("landscape", false).putExtra("fresh", false)
                if (startMs > 0L) pi.putExtra("startms", startMs)
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
                    putExtra("nosub", false)
                    if (startMs > 0L) putExtra("startms", startMs)
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

    /** (v999 ملاحظة 3) شغّل الفيديو بالترجمة: لازم تكون فيه ترجمة محفوظة (lec:) — لو مفيش بنوجّه لزرار «📝 ترجمة لـSRT» بدل ما الفيديو يفتح من غير ترجمة */
    private fun playWithTranslation(d: Lecture.Doc, startSec: Double = 0.0) {
        val hasSubs = try {
            StudioLink.hasSubs(act, Studio.lecId(d))
        } catch (_: Throwable) {
            false
        }
        if (!hasSubs) {
            Notice.show(act, "لسه مفيش ترجمة محفوظة — دوس «📝 ترجمة لـSRT» الأول", 3200L)
            return
        }
        try {
            val vid = StudioLink.vidForDoc(act, d)
            if (vid != null) StudioLink.copyStudioToVid(act, vid, d)
        } catch (e: Throwable) {
            LogStore.err("StudioUi:playLink", e)
        }
        watchVideo(Studio.displayTitle(d), d.uri, d.link, startSec)
    }

    /** (v999 ملاحظة 3) تحميل/مشاركة الترجمة: SRT من ملف الاستوديو المحفوظ (StudioLink.exportSrt) أو VTT بتحويل تافه من نفس نص الـSRT */
    private fun shareTranslation(d: Lecture.Doc) {
        val srt = try {
            TrLib.srtText(act, Studio.lecId(d))
        } catch (_: Throwable) {
            null
        }
        if (srt == null) {
            Notice.show(act, "مفيش ترجمة محفوظة لسه — دوس «📝 ترجمة لـSRT» الأول", 3200L)
            return
        }
        val labels = arrayOf("📥 SRT", "📥 VTT")
        GAlert(act).setTitle("تحميل / مشاركة الترجمة").setItems(labels) { _, w ->
            try {
                val f = if (w == 0) StudioLink.exportSrt(act, d) else srtToVttFile(srt, d)
                if (f == null) {
                    Notice.show(act, "⚠ مقدرتش أجهّز الملف", 2600L)
                    return@setItems
                }
                val u = FmUtil.uriFor(act, f)
                val mime = if (w == 0) "application/x-subrip" else "text/vtt"
                val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                act.startActivity(Intent.createChooser(send, "مشاركة الترجمة"))
            } catch (e: Throwable) {
                LogStore.err("StudioUi:shareTr", e)
                Notice.show(act, "⚠ مقدرتش أشارك الترجمة", 2600L)
            }
        }.setNegativeButton("إلغاء", null).show()
    }

    /** (v999 ملاحظة 3) SRT ← VTT: هيدر WEBVTT + الفاصلة العشرية في التوقيتات بتبقى نقطة — مفيش كاتب VTT جديد، ده تحويل سطور من نص الـSRT الجاهز */
    private fun srtToVttFile(srt: String, d: Lecture.Doc): java.io.File? {
        val vtt = "WEBVTT\n\n" + srt.replace(Regex("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})"), "$1.$2")
        return try {
            val f = java.io.File(act.cacheDir, TrLib.safeName(Studio.displayTitle(d)) + ".vtt")
            f.writeText(vtt, Charsets.UTF_8)
            f
        } catch (_: Throwable) {
            null
        }
    }

    // ===================== سجل التفريغات =====================
    private var histShown = false
    private var hist: List<Lecture.Doc> = emptyList()
    private val histCol = vcol()
    private var histTick: Runnable? = null
    // (v1000) بحث شامل في السجل: عنوان + نص كل الجمل — نفس نمط TrLibUi (debounce 260ms،
    // خيط خلفي، token للإلغاء، حد أدنى حرفين). النتائج: (عدد الجمل المطابقة، بداية أول مطابقة).
    private var histQ = ""
    private var histSearchTok = 0
    private var histHits: Map<String, Pair<Int, Double?>> = emptyMap()

    fun showHistory() {
        val col = vcol()
        col.layoutParams = lp(-1, -1)
        col.addView(ui.text("كل التفريغات — دوس على أي واحدة عشان تفتحها أو تترجمها لـSRT أو تغيّر نوعها.", 12.5f, th.muted), lp(-1, -2))
        val search = ui.input("🔍 دوّر في العناوين ونص التفريغ", "")
        search.addTextChangedListener(object : TextWatcher {
            private var pending: Runnable? = null
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { main.removeCallbacks(it) }
                val r = Runnable { histQ = s?.toString()?.trim() ?: ""; runHistSearch(histQ) }
                pending = r
                main.postDelayed(r, 260L)
            }
        })
        col.addView(search, lp(-1, -2, 8))
        col.addView(pill("↻ تحديث") { loadHistory() }, lp(-1, -2, 8))
        val sc = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(histCol) }
        col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(4) })
        histShown = true
        val d = ui.sheet(act, "📚 سجل التفريغات", listOf<View>(col), fixedH = true, onClose = { histShown = false; histSearchTok++; histTick?.let { main.removeCallbacks(it) } })
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
                if (histShown) {
                    if (histQ.length >= 2) runHistSearch(histQ) else renderHistory()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // (v1000) بحث المحتوى: مفيش index — مسح كامل للدوكيومنتات اللي في الذاكرة (hist جاي من
    // Lecture.list، وكل دوكيومنت فيه segs كاملة)، فالبحث على خيط خلفي وبيتلغي لو اتكتب حرف جديد.
    // Lecture.countMatches نقية ومن غير I/O؛ مطابقة العنوان بتتضاف هنا (countMatches بتغطي الجمل بس).
    private fun runHistSearch(q: String) {
        val tok = ++histSearchTok
        if (q.length < 2) {
            histHits = emptyMap()
            if (histShown) renderHistory()
            return
        }
        val snap = hist
        Thread {
            val m = HashMap<String, Pair<Int, Double?>>()
            for (d in snap) {
                if (tok != histSearchTok) return@Thread
                val titleHit = Studio.displayTitle(d).contains(q, true) || d.title.contains(q, true)
                val hit = Lecture.countMatches(d, q)
                if (titleHit || hit.first > 0) m[d.id] = hit
            }
            main.post { if (tok == histSearchTok && histShown) { histHits = m; renderHistory() } }
        }.apply { isDaemon = true }.start()
    }

    private fun renderHistory() {
        histCol.removeAllViews()
        val searching = histQ.length >= 2
        val docs = if (searching) hist.filter { histHits.containsKey(it.id) } else hist
        if (docs.isEmpty()) {
            histCol.addView(ui.text(if (searching) "مفيش نتيجة لـ«" + histQ + "» — جرّب كلمة تانية." else "مفيش تفريغات لسه — اختار مصدر من شاشة الاستوديو.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)) })
            return
        }
        for (d in docs) {
            val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(16) }
            val top = hrow()
            val t = vcol()
            t.addView(ui.text(Studio.displayTitle(d).ifBlank { "محاضرة" }, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val tt = Studio.typeOf(d)
            val typePart = tt.emoji + " " + tt.label + (if (d.typeAuto) " ✨ تلقائي (" + (d.typeConf * 100).toInt() + "٪)" else "")
            val meta = listOf(typePart, statusOf(d), if (d.durSec > 0) fmtDur(d.durSec) else "", if (d.words > 0) "${d.words} كلمة" else "", if (d.lang.isNotBlank()) "🌐 " + d.lang else "").filter { it.isNotBlank() }.joinToString(" · ")
            t.addView(ui.text(meta, 11.5f, th.muted).apply { maxLines = 1 })
            if (searching) {
                val hit = histHits[d.id]
                val hitLine = if (hit != null && hit.first > 0) {
                    "🔍 " + hit.first + " جملة · أول نتيجة " + fmtDur(hit.second ?: 0.0)
                } else {
                    "🔍 العنوان مطابق"
                }
                t.addView(ui.text(hitLine, 11.5f, th.primary, true).apply { maxLines = 1 })
            }
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
            row.setOnClickListener {
                // (v1000) في وضع البحث: الدوسة بتفتح الدوكيومنت عند أول نتيجة — مش شيت الدوكيومنت.
                if (searching) {
                    try {
                        LectureUi(act, ui, th).open(d, histHits[d.id]?.second ?: 0.0)
                    } catch (e: Throwable) { LogStore.err("StudioUi:openHit", e) }
                } else {
                    openDocSheet(d)
                }
            }
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

    // (v1000) نص شريحة النوع: المتحدد تلقائي بيبان عليه «✨ تلقائي (ثقة ٪)» — واليدوي بيبان عليه «✎».
    private fun typeChipText(d: Lecture.Doc): String {
        val t = Studio.typeOf(d)
        return if (d.typeAuto) {
            "النوع: " + t.emoji + " " + t.label + " ✨ تلقائي (" + (d.typeConf * 100).toInt() + "٪)"
        } else {
            "النوع: " + t.emoji + " " + t.label + " ✎"
        }
    }

    // ===================== شاشة التفريغ: النوع ✎ + العنوان التلقائي + ترجمة لـSRT =====================
    private fun openDocSheet(d: Lecture.Doc) {
        // (v1001) بادئة العنوان حسب النوع — «المحاضرة: » كانت غلط للمكالمات والأنواع التانية.
        val titlePrefix = when (Studio.typeOf(d).code) {
            "call" -> "المكالمة: "
            "lecture" -> "المحاضرة: "
            else -> Studio.typeOf(d).label + ": "
        }
        val titleV = ui.text(titlePrefix + Studio.displayTitle(d).ifBlank { Studio.typeOf(d).label }, 15f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val typeChip = pill(typeChipText(d)) {}
        val typeNote = ui.text("تغيير النوع بيعيد التنظيم والعرض من نفس التفريغ — الصوت مبيتفرّغش من جديد.", 11.5f, th.muted)
        val metaV = ui.text("", 12f, th.muted)
        val srtStatus = ui.text("", 12.5f, th.muted)
        fun paintType() {
            typeChip.text = typeChipText(d)
        }
        fun paintMeta() {
            val t = Studio.typeOf(d)
            val meta = listOf(statusOf(d), if (d.durSec > 0) fmtDur(d.durSec) else "", if (d.words > 0) "${d.words} كلمة" else "", if (d.lang.isNotBlank()) "🌐 " + d.lang + (if (d.langCode.isNotBlank()) " (" + d.langCode + ")" else "") else "", if (d.segs.isNotEmpty()) "${d.segs.size} جملة" else "", "الناتج: " + t.outputShape).filter { it.isNotBlank() }.joinToString(" · ")
            metaV.text = meta
        }
        typeChip.setOnClickListener {
            // (v1000) البكّر بيتفتح ومعاه الدوكيومنت نفسه عشان الرسالة تسمّي النوع المكتشف وثقته.
            // «✨ تلقائي» من هنا معناها «سيبه زي ما هو» — مفيش setType بكود فاضي (كان هيقلب النوع محاضرة).
            pickType(Studio.typeOf(d).code, d) { code ->
                if (code.isBlank()) {
                    Notice.show(act, "النوع هيفضل تلقائي زي ما هو", 2400L)
                    return@pickType
                }
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
        // (v999 ملاحظة 3) زرار التشغيل بالترجمة أول الأزرار — خطوتين بس من آخر التفريغ للمشاهدة بالترجمة (الصوتيات ملهاش فيديو فمبتظهرش)
        val hasVideo = (d.uri.isNotBlank() || d.link.isNotBlank()) && d.kind != "audio" && d.kind != "rec"
        if (hasVideo) {
            content.addView(pill("▶ شغّل الفيديو بالترجمة", primary = true) { playWithTranslation(d) }, lp(-1, -2, 8))
        }
        content.addView(pill("📖 فتح التفريغ") {
            try {
                LectureUi(act, ui, th).open(d)
            } catch (e: Throwable) { LogStore.err("StudioUi:openDoc", e) }
        }, lp(-1, -2, 8))
        // (v1001) المكالمات ليها صفحة دردشة — من هنا أو من قايمة السجل (LectureUi.open بتحوّل ليها).
        if (Studio.typeOf(d).code == "call") {
            content.addView(pill("💬 عرض كدردشة") {
                try {
                    CallChatUi(act, ui, th).show(d)
                } catch (e: Throwable) { LogStore.err("StudioUi:chat", e) }
            }, lp(-1, -2, 8))
        }
        content.addView(pill("📥 تحميل / مشاركة الترجمة") { shareTranslation(d) }, lp(-1, -2, 8))
        content.addView(pill("📝 ترجمة لـSRT") { runSrt(d, srtStatus) }, lp(-1, -2, 8))
        content.addView(pill("🌐 الترجمة عن طريق وارد") { runWaridTranslate(d, srtStatus) }, lp(-1, -2, 8))
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
        var sheetOpen = true
        val tick = object : Runnable {
            override fun run() {
                if (!sheetOpen) return
                paintMeta()
                main.postDelayed(this, 1200L)
            }
        }
        dd = ui.sheet(act, "🎙 " + Studio.displayTitle(d).take(28), listOf<View>(content), fixedH = true, onClose = { sheetOpen = false; main.removeCallbacks(tick) })
        dd.show()
        main.postDelayed(tick, 1200L)
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
            main.post { showSrtResult(r, statusV) }
        }.apply { isDaemon = true }.start()
    }

    /** نتيجة ترجمة SRT (عادي أو وارد): نفس العرض في الحالتين */
    private fun showSrtResult(r: Studio.SrtResult?, statusV: TextView) {
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

    /** (v998) «الترجمة عن طريق وارد»: تفريغ وارد الأول لو الدوكيومنت مش متفريغ بوارد، وبعدها ترجمة لـSRT */
    private fun runWaridTranslate(d: Lecture.Doc, statusV: TextView) {
        if (Lecture.isRunning(d.id)) {
            Notice.show(act, "التفريغ لسه شغال — استنى يخلص وبعدها دوس تاني", 3000L)
            return
        }
        val needTx = synchronized(d) { !d.warid || d.segs.isEmpty() }
        val hasOld = synchronized(d) { d.segs.isNotEmpty() && !d.warid }
        if (needTx && hasOld) {
            GAlert(act).setTitle("🌐 الترجمة عن طريق وارد").setMessage("التفريغ الحالي بالطريقة العادية هيتمسح ويتفرّغ من جديد بطريقة وارد (أجزاء ٥ أو ١٠ دقايق)، وبعدها تتعمل الترجمة.").setPositiveButton("فرّغ بوارد وترجم") { _, _ -> runWaridTranslateNow(d, statusV, true) }.setNegativeButton("إلغاء", null).show()
            return
        }
        runWaridTranslateNow(d, statusV, needTx)
    }

    /** (v998) تنفيذ الترجمة بوارد على خيط خلفي: تفريغ وارد (لو لازم) ← انتظار اكتماله ← ترجمة لـSRT */
    private fun runWaridTranslateNow(d: Lecture.Doc, statusV: TextView, doTx: Boolean) {
        statusV.text = if (doTx) "⏳ بفرّغ بوارد…" else "⏳ بترجم بوارد…"
        Thread {
            var res: Studio.SrtResult? = null
            var fail = ""
            var partialNote = ""
            try {
                if (doTx) {
                    WaridTx.startWarid(act, d)
                    while (Lecture.isRunning(d.id)) {
                        val st = Lecture.runOf(d.id)?.status ?: ""
                        main.post { if (st.isNotBlank()) statusV.text = "⏳ " + st }
                        try {
                            Thread.sleep(1000L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    val segs = synchronized(d) { d.segs.size }
                    val err = Lecture.runOf(d.id)?.error ?: ""
                    if (segs == 0) {
                        fail = if (err.isNotBlank()) err.take(100) else "فشل التفريغ بوارد"
                    } else {
                        // (v1000) التفريغ خلص ناقص (في أجزاء فشلت): الترجمة هتتعمل من اللي اتفرّغ،
                        // بس لازم يبان في الحالة النهائية إنها من تفريغ ناقص — مش ترجمة كاملة.
                        val nFailed = synchronized(d) { d.failed.size }
                        if (nFailed > 0) partialNote = "⚠ الترجمة اتعملت من تفريغ ناقص (" + nFailed + " جزء)"
                    }
                }
                if (fail.isEmpty()) {
                    res = WaridTx.translateWaridDoc(act, d, report = { m -> main.post { statusV.text = "⏳ " + m } })
                }
            } catch (e: Throwable) {
                LogStore.err("StudioUi:waridTr", e)
                if (fail.isEmpty()) fail = (e.message ?: e.javaClass.simpleName).take(100)
            }
            val r = res
            val f = fail
            val pn = partialNote
            main.post {
                if (f.isNotEmpty()) {
                    statusV.text = "⚠ " + f
                    Notice.show(act, "⚠ " + f, 3600L)
                } else {
                    showSrtResult(r, statusV)
                    if (pn.isNotBlank() && r?.ok == true) statusV.text = statusV.text.toString() + " · " + pn
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
