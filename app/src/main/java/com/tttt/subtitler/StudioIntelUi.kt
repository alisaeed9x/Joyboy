package com.tttt.subtitler

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * (v1002 — المرحلة 4) واجهة الذكاء الإضافي للاستوديو — كل الشاشات بتتفتح من «🧠 ذكاء إضافي»:
 * اسأل التسجيل · فصول · ترجمة جنب بعض · استخراج · وعود ومهام · علامات · ثقة منخفضة ·
 * محضر الاجتماع · قاموس الشخصيات · كورسات/Anki · تفريغ تلقائي للمكالمات.
 * القفز للحظة: مكالمة ← صفحة الدردشة، غيرها ← شاشة التفريغ (LectureUi.open).
 */
class StudioIntelUi(private val act: Activity, private val ui: Ui, private val th: Theme) {

    private val main = Handler(Looper.getMainLooper())
    private val amber = 0xFFF59E0B.toInt()
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK

    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = ui.dp(top) }
    private fun vcol() = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private fun hrow() = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }

    private fun glassBg(r: Int): android.graphics.drawable.Drawable =
        if (MtGlass.on()) MtGlass.pill(th, MtGlass.fill(th), ui.dp(r).toFloat(), act.resources.displayMetrics.density) else ui.box(th.surface, th.border, 12)

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        ui.text(label, 12.5f, if (primary) onPrimary else th.text, true).apply {
            gravity = Gravity.CENTER; maxLines = 1
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            background = if (primary) Glass.primaryFill(th, ui.dp(16).toFloat()) else glassBg(16)
            isClickable = true; Glass.pressable(this); setOnClickListener { onClick() }
        }

    private fun jumpTo(d: Lecture.Doc, sec: Double) {
        try {
            if (Studio.typeOf(d).code == "call") CallChatUi(act, ui, th).show(d, sec)
            else LectureUi(act, ui, th).open(d, sec)
        } catch (e: Throwable) {
            LogStore.err("StudioIntelUi:jump", e)
        }
    }

    private fun openSheet(title: String, content: View): android.app.Dialog {
        val d = ui.sheet(act, title, listOf<View>(content), fixedH = true)
        d.show()
        return d
    }

    // ===================== المركز: «🧠 ذكاء إضافي» =====================
    fun showHub(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val type = Studio.typeOf(d).code
        col.addView(ui.text("أدوات ذكية على التفريغ ده — من غير ما الصوت يتفرّغ من جديد.", 12f, th.muted), lp(-1, -2))
        col.addView(pill("❓ اسأل التسجيل", primary = true) { askSheet(d) }, lp(-1, -2, 10))
        if (type == "lecture" || type == "subs" || type == "show" || type == "meeting" || type == "audio") {
            col.addView(pill("📑 الفصول التلقائية") { chaptersSheet(d) }, lp(-1, -2, 8))
        }
        col.addView(pill("🔀 ترجمة جنب بعض (أصل | ترجمة)") { sideBySideSheet(d) }, lp(-1, -2, 8))
        col.addView(pill("🔍 عناصر مهمة — أرقام · مبالغ · مواعيد") { extractSheet(d) }, lp(-1, -2, 8))
        col.addView(pill("✅ وعود ومهام") { promisesSheet(d) }, lp(-1, -2, 8))
        col.addView(pill("🔖 العلامات") { bookmarksSheet(d) }, lp(-1, -2, 8))
        col.addView(pill("⚠ جمل الثقة المنخفضة") { lowConfSheet(d) }, lp(-1, -2, 8))
        if (type == "meeting") {
            col.addView(pill("🤝 محضر الاجتماع") { showMinutes(d) }, lp(-1, -2, 8))
        }
        if (type == "show") {
            col.addView(pill("🎬 قاموس أسماء الشخصيات") { glossarySheet(d) }, lp(-1, -2, 8))
        }
        col.addView(pill("📚 كورس — اربط المحاضرة دي بمادة") { courseSheet(d) }, lp(-1, -2, 8))
        if (type == "lecture") {
            col.addView(pill("🃏 تصدير أسئلة Anki (CSV)") { exportAnki(d) }, lp(-1, -2, 8))
        }
        // (G10) التقدير قبل البدء بس — دوكيومنت خلص تفريغه (nChunks > 0) مبيظهرلوش تقدير.
        val est = if (d.nChunks == 0) StudioIntel.estimateShort(d) else ""
        if (est.isNotBlank()) {
            val warn = StudioIntel.estimate(d).warn
            col.addView(ui.text("💰 " + est + (if (warn.isNotBlank()) "\n" + warn else ""), 12f, if (warn.isNotBlank()) amber else th.muted), lp(-1, -2, 10))
        }
        openSheet("🧠 ذكاء إضافي", col)
    }

    // ===================== 1) اسأل التسجيل =====================
    fun askSheet(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val qEt = ui.input("اسأل… (مثال: هو قال إيه عن الميعاد؟)", "")
        val ansV = ui.text("", 13.5f, th.text).apply { setLineSpacing(0f, 1.3f); setTextIsSelectable(true) }
        val srcV = ui.text("من نص التفريغ — الإجابة من الكلام المفرّغ بس، من غير اختراع. (البحث في أول 8 أجزاء من التفريغ)", 11.5f, th.muted)
        val jumpBtn = pill("▶ اسمع اللحظة دي") {}
        jumpBtn.visibility = View.GONE
        col.addView(qEt)
        col.addView(pill("❓ اسأل", primary = true) {
            val q = qEt.text.toString().trim()
            if (q.isBlank()) {
                Notice.show(act, "اكتب سؤال الأول", 2200L)
                return@pill
            }
            ansV.text = "⏳ بدوّر في التفريغ…"
            jumpBtn.visibility = View.GONE
            Thread {
                val res = try {
                    StudioIntel.askSync(act, d, q)
                } catch (e: Throwable) {
                    LogStore.err("StudioIntelUi:ask", e)
                    StudioIntel.AskResult("", null, "فشل السؤال")
                }
                main.post {
                    if (!res.ok) {
                        ansV.text = "⚠ " + res.error
                        return@post
                    }
                    ansV.text = res.answer
                    // (G12) التغطية بتتقال صراحة: البحث بيدوّر في أول N جزء بس (8 كحد أقصى).
                    if (res.parts > 0) srcV.text = "الإجابة من أول " + res.parts + " جزء من التفريغ — من الكلام المفرّغ بس، من غير اختراع."
                    val st = res.start
                    if (st != null) {
                        jumpBtn.visibility = View.VISIBLE
                        jumpBtn.setOnClickListener { jumpTo(d, st) }
                    }
                }
            }.apply { isDaemon = true }.start()
        }, lp(-1, -2, 8))
        col.addView(srcV, lp(-1, -2, 8))
        col.addView(ansV, lp(-1, -2, 8))
        col.addView(jumpBtn, lp(-1, -2, 8))
        openSheet("❓ اسأل التسجيل", col)
    }

    // ===================== 2) فصول تلقائية =====================
    fun chaptersSheet(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val list = vcol()
        val status = ui.text("", 12f, th.muted)
        col.addView(ui.text("عناوين بتوقيتها زي فصول يوتيوب — الدوسة بتقفز للحظة.", 12f, th.muted))
        col.addView(status, lp(-1, -2, 6))

        fun render(items: List<StudioIntel.Chapter>) {
            list.removeAllViews()
            if (items.isEmpty()) {
                list.addView(ui.text("مفيش فصول لسه — دوس «توليد الفصول».", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)) })
                return
            }
            for (c in items) {
                val row = hrow().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
                row.addView(ui.text(Lecture.fmtTime(c.start), 12.5f, th.primary, true))
                row.addView(ui.text(c.title, 13.5f, th.text, true).apply { setPadding(ui.dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                row.isClickable = true
                Glass.pressable(row)
                row.setOnClickListener { jumpTo(d, c.start) }
                list.addView(row, lp(-1, -2, 6))
            }
        }
        render(StudioIntel.chapters(act, d))
        col.addView(pill("📑 توليد الفصول", primary = true) {
            status.text = "⏳ بيتولّد… (طلب نص لكل جزء من التفريغ)"
            Thread {
                val items = try {
                    StudioIntel.chaptersSync(act, d)
                } catch (e: Throwable) {
                    LogStore.err("StudioIntelUi:ch", e)
                    emptyList()
                }
                main.post {
                    if (items.isEmpty()) status.text = "⚠ مقدرتش أولّد فصول — جرّب تاني أو اتأكد إن التفريغ مكتمل"
                    else status.text = "✅ " + items.size + " فصل"
                    render(items)
                }
            }.apply { isDaemon = true }.start()
        }, lp(-1, -2, 8))
        col.addView(pill("📋 نسخ الفصول كنص") {
            val txt = StudioIntel.chaptersText(act, d)
            if (txt.isBlank()) {
                Notice.show(act, "مفيش فصول لسه", 2200L)
                return@pill
            }
            try {
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("فصول", txt))
                Notice.show(act, "✅ اتنسخت — الصقها مع الوصف أو الـSRT", 2600L)
            } catch (e: Throwable) {
                LogStore.err("StudioIntelUi:chCopy", e)
            }
        }, lp(-1, -2, 8))
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        openSheet("📑 الفصول التلقائية", col)
    }

    // ===================== 3) ترجمة جنب بعض =====================
    fun sideBySideSheet(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val status = ui.text("اختار لغة الترجمة — العرض أصل | ترجمة، مش ملف SRT.", 12f, th.muted)
        val list = vcol()
        col.addView(status)
        val langs = arrayOf("English", "العربية", "Français", "Deutsch", "Español", "Türkçe", "한국어")
        col.addView(pill("🔀 ترجم جنب بعض", primary = true) {
            GAlert(act).setTitle("لغة الترجمة").setItems(langs) { _, w ->
                val lang = langs[w]
                status.text = "⏳ بترجم لـ $lang… (نفس محرك الترجمة الحالي)"
                Thread {
                    var pairs: List<Pair<String, String>> = emptyList()
                    var failed = 0
                    var err = ""
                    try {
                        val segs = Studio.displaySegs(d)
                        val cues = segs.map { SubFileTr.Cue(it.start, it.end, it.text) }
                        val out = SubFileTr.translate(cues, TrWizard.method(), { m -> main.post { status.text = "⏳ " + m } }, { false }, lang, null, d.langCode.ifBlank { null })
                        failed = out.failed
                        err = out.error
                        pairs = segs.mapIndexed { i, s -> s.text to (out.texts.getOrNull(i) ?: "") }
                    } catch (e: Throwable) {
                        LogStore.err("StudioIntelUi:sbs", e)
                        err = (e.message ?: e.javaClass.simpleName).take(100)
                    }
                    main.post {
                        list.removeAllViews()
                        if (pairs.isEmpty()) {
                            status.text = "⚠ فشلت الترجمة" + (if (err.isNotBlank()) ": $err" else "")
                            return@post
                        }
                        status.text = "✅ " + pairs.size + " جملة" + (if (failed > 0) " — $failed جملة فضلت بالأصل" else "")
                        for ((orig, tr) in pairs) {
                            val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
                            row.addView(ui.text(orig, 13.5f, th.text).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) })
                            row.addView(ui.text(if (tr.isBlank()) "(مفيش ترجمة)" else tr, 13.5f, th.muted).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }, lp(-1, -2, 4))
                            list.addView(row, lp(-1, -2, 6))
                        }
                    }
                }.apply { isDaemon = true }.start()
            }.setNegativeButton("إلغاء", null).show()
        }, lp(-1, -2, 8))
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        openSheet("🔀 ترجمة جنب بعض", col)
    }

    // ===================== 4) استخراج عناصر مهمة =====================
    fun extractSheet(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val list = vcol()
        val items = StudioIntel.extract(d)
        col.addView(ui.text("أرقام ومبالغ ومواعيد وأسماء من نص التفريغ (بحث نصي — ممكن يبقى فيه نتايج غلط).", 12f, th.muted))
        if (items.isEmpty()) {
            list.addView(ui.text("مفيش عناصر مهمة اتلقطت في التفريغ ده.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)) })
        }
        for (it in items) {
            val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
            val top = hrow()
            val dot = TextView(act).apply { text = "●"; setTextColor(StudioIntel.extractKindColor(it.kind)); textSize = 12f }
            top.addView(dot)
            top.addView(ui.text(StudioIntel.extractKindLabel(it.kind), 12f, th.muted, true).apply { setPadding(ui.dp(6), 0, 0, 0) })
            top.addView(ui.text("⏱ " + Lecture.fmtTime(it.start), 12f, th.muted).apply { setPadding(ui.dp(10), 0, 0, 0) })
            row.addView(top)
            row.addView(ui.text(it.text, 14f, th.text, true).apply { setTextIsSelectable(true) }, lp(-1, -2, 4))
            val acts = hrow()
            acts.addView(pill("▶ اسمع") { jumpTo(d, it.start) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
            // (G3) «أضف للتقويم» للمواعيد بس — المبالغ ليها أفعالها الخاصة ومبتتحطش في التقويم.
            // وقت الحدث بيتفسّر من نص الموعد نفسه (StudioIntel.parseDateMillis) — لو مش مفهوم بيتساب للمستخدم.
            if (it.kind == "date") {
                acts.addView(pill("📅 أضف للتقويم") {
                    try {
                        act.startActivity(StudioIntel.calendarIntent(it.text, "من تفريغ: " + Studio.displayTitle(d), it.text))
                    } catch (e: Throwable) {
                        LogStore.err("StudioIntelUi:cal", e)
                        Notice.show(act, "⚠ مفيش تطبيق تقويم", 2400L)
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
            }
            if (it.kind == "phone") {
                acts.addView(pill("👤 أضف كجهة اتصال") {
                    try {
                        act.startActivity(StudioIntel.contactIntent(it.text))
                    } catch (e: Throwable) {
                        LogStore.err("StudioIntelUi:contact", e)
                        Notice.show(act, "⚠ مقدرتش أفتح جهات الاتصال", 2400L)
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            row.addView(acts, lp(-1, -2, 8))
            list.addView(row, lp(-1, -2, 6))
        }
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        openSheet("🔍 عناصر مهمة", col)
    }

    // ===================== 5) وعود ومهام =====================
    fun promisesSheet(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val list = vcol()
        val status = ui.text("", 12f, th.muted)
        col.addView(ui.text("مين قال إنه هيعمل إيه وإمتى — من نص التفريغ.", 12f, th.muted))
        col.addView(status, lp(-1, -2, 6))

        fun render(items: List<StudioIntel.Promise>) {
            list.removeAllViews()
            if (items.isEmpty()) {
                list.addView(ui.text("مفيش وعود اتلقطت — دوس «استخراج الوعود».", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)) })
                return
            }
            for (p in items) {
                val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
                val who = if (p.who.isNotBlank()) p.who else "؟"
                row.addView(ui.text("👤 $who" + (if (p.whenText.isNotBlank()) " · 📅 " + p.whenText else ""), 12.5f, th.primary, true))
                row.addView(ui.text(p.what, 13.5f, th.text).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }, lp(-1, -2, 4))
                row.addView(pill("📅 تذكير (حدث في التقويم)") {
                    try {
                        act.startActivity(StudioIntel.calendarIntent(p.what, "وعد من " + who + (if (p.whenText.isNotBlank()) " — " + p.whenText else "") + " — من تفريغ: " + Studio.displayTitle(d), p.whenText))
                    } catch (e: Throwable) {
                        LogStore.err("StudioIntelUi:rem", e)
                        Notice.show(act, "⚠ مفيش تطبيق تقويم", 2400L)
                    }
                }, lp(-1, -2, 8))
                list.addView(row, lp(-1, -2, 6))
            }
        }
        render(StudioIntel.promises(act, d))
        col.addView(pill("✅ استخراج الوعود", primary = true) {
            status.text = "⏳ بستخرج… (طلب نص واحد)"
            Thread {
                val items = try {
                    StudioIntel.promisesSync(act, d)
                } catch (e: Throwable) {
                    LogStore.err("StudioIntelUi:pr", e)
                    emptyList()
                }
                main.post {
                    if (items.isEmpty()) status.text = "⚠ مفيش وعود اتلقطت"
                    else status.text = "✅ " + items.size + " وعد/مهمة"
                    render(items)
                }
            }.apply { isDaemon = true }.start()
        }, lp(-1, -2, 8))
        col.addView(ui.text("التذكير بيتحفظ كحدث في تطبيق التقويم بتاعك (مفيش منبّه جوّا التطبيق) — ولو الموعد مكتوب («بكرة»، «الأحد»…) بيتحط وقته في الحدث، ولو مش مفهوم بتختار التاريخ والوقت بنفسك في شاشة التقويم.", 11.5f, th.muted), lp(-1, -2, 6))
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        openSheet("✅ وعود ومهام", col)
    }

    // ===================== 13) علامات =====================
    fun bookmarksSheet(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val list = vcol()

        fun render() {
            list.removeAllViews()
            val items = StudioIntel.bookmarks(act, d)
            if (items.isEmpty()) {
                list.addView(ui.text("مفيش علامات لسه — اضغط ضغطة طويلة على أي جملة في شاشة التفريغ.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)) })
                return
            }
            for (b in items) {
                val row = hrow().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
                row.addView(ui.text("🔖 " + Lecture.fmtTime(b.start), 12.5f, th.primary, true))
                row.addView(ui.text(b.label, 13f, th.text).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                row.isClickable = true
                Glass.pressable(row)
                row.setOnClickListener { jumpTo(d, b.start) }
                list.addView(row, lp(-1, -2, 6))
            }
        }
        render()
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        openSheet("🔖 العلامات", col)
    }

    // ===================== 12) ثقة منخفضة =====================
    fun lowConfSheet(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val list = vcol()
        val items = StudioIntel.lowConfSegs(d)
        col.addView(ui.text("جمل الموديل نفسه مش متأكد من دقّتها — «أعد تفريغ المقطع» بيعيد الجزء كله (300ث وارد / 60ث عادي).", 12f, th.muted))
        if (items.isEmpty()) {
            list.addView(ui.text("مفيش جمل ثقة منخفضة — أو التفريغ ده قديم من قبل ما الموديل يرجّع ثقة.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)) })
        }
        for (s in items) {
            val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
            row.addView(ui.text("⏱ " + Lecture.fmtTime(s.start) + " → " + Lecture.fmtTime(s.end) + " · ثقة " + (s.conf * 100).toInt() + "٪", 12f, amber, true))
            row.addView(ui.text(s.text, 13.5f, th.text).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }, lp(-1, -2, 4))
            row.addView(pill("🔁 أعد تفريغ المقطع ده") {
                val ok = StudioIntel.retranscribeSeg(act, d, s)
                Notice.show(act, if (ok) "⏳ بيعيد تفريغ الجزء…" else "⚠ التفريغ شغال دلوقتي — استنى يخلص", 2600L)
            }, lp(-1, -2, 8))
            list.addView(row, lp(-1, -2, 6))
        }
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        openSheet("⚠ ثقة منخفضة", col)
    }

    // ===================== 8) محضر الاجتماع =====================
    fun showMinutes(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val m = StudioIntel.minutes(act, d)
        col.addView(ui.text(Studio.displayTitle(d).ifBlank { "اجتماع" }, 15f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        col.addView(ui.text(if (d.durSec > 0) Lecture.fmtTime(d.durSec) else "", 12f, th.muted), lp(-1, -2, 4))
        col.addView(ui.text("📌 القرارات", 14f, th.primary, true), lp(-1, -2, 10))
        if (m.decisions.isEmpty()) col.addView(ui.text("مفيش قرارات واضحة في التنظيم — دوس «نظّم التفريغ» من شاشة التفريغ الأول.", 12.5f, th.muted), lp(-1, -2, 4))
        for (t in m.decisions) col.addView(ui.text("• $t", 13.5f, th.text).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }, lp(-1, -2, 4))
        col.addView(ui.text("✅ المهام", 14f, th.primary, true), lp(-1, -2, 10))
        if (m.tasks.isEmpty()) col.addView(ui.text("مفيش مهام واضحة في التنظيم.", 12.5f, th.muted), lp(-1, -2, 4))
        for (t in m.tasks) col.addView(ui.text("• $t", 13.5f, th.text).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }, lp(-1, -2, 4))
        val pr = StudioIntel.promises(act, d)
        col.addView(ui.text("🗣 وعود اتقالت في الاجتماع", 14f, th.primary, true), lp(-1, -2, 10))
        val prStatus = ui.text("", 12f, th.muted)
        col.addView(prStatus, lp(-1, -2, 4))
        val prBox = vcol()
        col.addView(prBox, lp(-1, -2, 4))

        fun renderPromises(items: List<StudioIntel.Promise>) {
            prBox.removeAllViews()
            if (items.isEmpty()) {
                prBox.addView(ui.text("مفيش وعود اتلقطت في الاجتماع ده.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6)) })
                return
            }
            for (p in items) prBox.addView(ui.text("• " + (if (p.who.isNotBlank()) p.who + ": " else "") + p.what + (if (p.whenText.isNotBlank()) " (" + p.whenText + ")" else ""), 13.5f, th.text).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }, lp(-1, -2, 4))
        }
        renderPromises(pr)
        // (G6) لو مفيش وعود محفوظة، الاستخراج بيشتغل في الخلفية وبيعرض لما يخلص — من غير ما يعلّق الشاشة.
        if (pr.isEmpty()) {
            prStatus.text = "⏳ جارٍ استخراج الوعود…"
            Thread {
                val items = try {
                    StudioIntel.promisesSync(act, d)
                } catch (e: Throwable) {
                    LogStore.err("StudioIntelUi:minutesPr", e)
                    emptyList()
                }
                main.post {
                    prStatus.text = ""
                    renderPromises(items)
                }
            }.apply { isDaemon = true }.start()
        }
        col.addView(pill("✅ استخراج الوعود والمهام") { promisesSheet(d) }, lp(-1, -2, 10))
        col.addView(pill("📖 فتح التفريغ الكامل") {
            LectureUi(act, ui, th).openList(d)
        }, lp(-1, -2, 8))
        openSheet("🤝 محضر الاجتماع", col)
    }

    // ===================== 9) قاموس شخصيات المسلسل =====================
    fun glossarySheet(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val list = vcol()
        val series = StudioIntel.seriesKey(d)
        col.addView(ui.text("السلسلة: $series", 12.5f, th.text, true))
        col.addView(ui.text("الأسماء هنا بتتثبّت كتابتها في أي حلقة جديدة من نفس السلسلة — بتتضاف لبرومبت التفريغ.", 12f, th.muted), lp(-1, -2, 4))

        fun render() {
            list.removeAllViews()
            val names = StudioIntel.glossary(act, d)
            if (names.isEmpty()) {
                list.addView(ui.text("مفيش أسماء لسه — ضيف أسماء الشخصيات تحت.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)) })
                return
            }
            for (nm in names) {
                val row = hrow().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
                row.addView(ui.text(nm, 13.5f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(pill("🗑") {
                    val cur = StudioIntel.glossary(act, d).filter { it != nm }
                    StudioIntel.setGlossary(act, d, cur)
                    render()
                }, LinearLayout.LayoutParams(-2, -2))
                list.addView(row, lp(-1, -2, 6))
            }
        }
        render()
        val nameEt = ui.input("اسم شخصية (زي ما بيتقال في الحوار)", "")
        col.addView(nameEt, lp(-1, -2, 8))
        col.addView(pill("➕ أضف الاسم", primary = true) {
            val nm = nameEt.text.toString().trim()
            if (nm.isBlank()) return@pill
            val cur = StudioIntel.glossary(act, d).toMutableList()
            if (!cur.contains(nm)) cur.add(nm)
            StudioIntel.setGlossary(act, d, cur)
            nameEt.setText("")
            render()
            Notice.show(act, "✅ اتضاف — هيتثبّت في الحلقات الجديدة", 2200L)
        }, lp(-1, -2, 8))
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        openSheet("🎬 قاموس الشخصيات", col)
    }

    // ===================== 10) كورسات / Anki =====================
    fun courseSheet(d: Lecture.Doc) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val cur = StudioIntel.courseOf(act, d.id)
        // (G11) الشيت بيتقفل وبيتفتح تاني بعد الربط/الشيل عشان سطر «المحاضرة دي في كورس: …» يتحدّث.
        lateinit var sheetDlg: android.app.Dialog
        col.addView(ui.text(if (cur.isNotBlank()) "المحاضرة دي في كورس: $cur" else "المحاضرة دي مش في كورس لسه.", 12.5f, th.text))
        val nameEt = ui.input("اسم المادة / الكورس", cur)
        col.addView(nameEt, lp(-1, -2, 8))
        col.addView(pill("📚 اربط بالكورس ده", primary = true) {
            val nm = nameEt.text.toString().trim()
            if (nm.isBlank()) return@pill
            StudioIntel.assignCourse(act, nm, d.id)
            Notice.show(act, "✅ اتربطت بكورس «$nm»", 2400L)
            sheetDlg.dismiss()
            courseSheet(d)
        }, lp(-1, -2, 8))
        if (cur.isNotBlank()) {
            col.addView(pill("🗑 شيل من الكورس") {
                StudioIntel.removeFromCourse(act, cur, d.id)
                Notice.show(act, "اتشالت من الكورس", 2200L)
                sheetDlg.dismiss()
                courseSheet(d)
            }, lp(-1, -2, 8))
            col.addView(pill("📖 افتح الكورس") { showCourse(cur) }, lp(-1, -2, 8))
        }
        col.addView(pill("📚 كل الكورسات") { showCourses() }, lp(-1, -2, 8))
        sheetDlg = openSheet("📚 كورس", col)
    }

    fun showCourses() {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val list = vcol()
        val courses = StudioIntel.listCourses(act)
        if (courses.isEmpty()) {
            list.addView(ui.text("مفيش كورسات لسه — افتح أي محاضرة ← «🧠 ذكاء إضافي» ← «📚 كورس».", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)) })
        }
        for (c in courses) {
            val row = hrow().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
            row.addView(ui.text("📚 " + c.name, 13.5f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.text(c.docIds.size.toString() + " محاضرة", 12f, th.muted))
            row.isClickable = true
            Glass.pressable(row)
            row.setOnClickListener { showCourse(c.name) }
            list.addView(row, lp(-1, -2, 6))
        }
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        openSheet("📚 الكورسات", col)
    }

    fun showCourse(name: String) {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val course = StudioIntel.listCourses(act).firstOrNull { it.name == name } ?: return
        col.addView(ui.text("📚 $name", 15f, th.text, true))
        val docs = try {
            val all = Lecture.list(act)
            course.docIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
        } catch (_: Throwable) {
            emptyList()
        }
        if (docs.isEmpty()) col.addView(ui.text("مفيش محاضرات في الكورس ده.", 12.5f, th.muted), lp(-1, -2, 6))
        for (doc in docs) {
            val row = hrow().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(14) }
            row.addView(ui.text(Studio.displayTitle(doc).ifBlank { "محاضرة" }, 13.5f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            row.isClickable = true
            Glass.pressable(row)
            row.setOnClickListener { LectureUi(act, ui, th).open(doc) }
            col.addView(row, lp(-1, -2, 6))
        }
        val terms = StudioIntel.courseGlossary(act, course)
        col.addView(ui.text("📖 القاموس التراكمي (" + terms.size + " مصطلح)", 14f, th.primary, true), lp(-1, -2, 10))
        if (terms.isEmpty()) col.addView(ui.text("لسه مفيش مصطلحات — اعمل «نظّم التفريغ» في كل محاضرة الأول.", 12.5f, th.muted), lp(-1, -2, 4))
        for (t in terms.take(60)) col.addView(ui.text("• $t", 13f, th.text).apply { setTextIsSelectable(true) }, lp(-1, -2, 3))
        col.addView(pill("🃏 تصدير أسئلة Anki لكل محاضرة") {
            exportAnkiForCourse(docs, name)
        }, lp(-1, -2, 10))
        openSheet("📚 $name", col)
    }

    private fun exportAnki(d: Lecture.Doc) {
        Notice.show(act, "⏳ بجهّز أسئلة Anki…", 2000L)
        Thread {
            val f = try {
                StudioIntel.ankiCsv(act, d)
            } catch (e: Throwable) {
                LogStore.err("StudioIntelUi:anki", e)
                null
            }
            main.post {
                if (f == null) Notice.show(act, "⚠ مقدرتش أولّد أسئلة — اتأكد إن فيه تفريغ أو تنظيم", 3000L)
                else shareCsv(f)
            }
        }.apply { isDaemon = true }.start()
    }

    // (G2) ملف CSV واحد للكورس كله (كل المحاضرات في ملف واحد باسم الكورس) — مش ملف لكل محاضرة.
    private fun exportAnkiForCourse(docs: List<Lecture.Doc>, courseName: String) {
        Notice.show(act, "⏳ بجهّز أسئلة Anki للكورس…", 2000L)
        Thread {
            val f = try {
                StudioIntel.ankiCsvForCourse(act, docs, courseName)
            } catch (e: Throwable) {
                LogStore.err("StudioIntelUi:ankiCourse", e)
                null
            }
            main.post {
                if (f == null) Notice.show(act, "⚠ مقدرتش أولّد أسئلة لأي محاضرة", 3000L)
                else {
                    Notice.show(act, "✅ اتعمل ملف CSV واحد للكورس", 3000L)
                    shareCsv(f)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun shareCsv(f: java.io.File) {
        try {
            val u = FmUtil.uriFor(act, f)
            val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(Intent.createChooser(send, "مشاركة أسئلة Anki"))
        } catch (e: Throwable) {
            LogStore.err("StudioIntelUi:shareCsv", e)
            Notice.show(act, "✅ الملف جاهز: " + f.absolutePath, 4000L)
        }
    }

    // ===================== 7) تفريغ تلقائي للمكالمات =====================
    fun showAutoCall() {
        val col = vcol().apply { layoutParams = lp(-1, -1) }
        val status = ui.text("", 12f, th.muted)
        col.addView(ui.text("لما يتفعّل: كل مرة الاستوديو يتفتح، أي تسجيل مكالمة جديد بيتفرّغ لوحده — على الواي-فاي والشاحن بس — وبإشعار «N مكالمات جاهزة».", 12f, th.muted))
        col.addView(ui.switchRow("🔁 تفريغ تلقائي للمكالمات الجديدة", StudioIntel.isAutoCallOn()) { v ->
            StudioIntel.setAutoCallOn(v)
            status.text = if (v) "✅ اتفعّل — هيتفحص لما الاستوديو يتفتح" else "اتقفل"
        }, lp(-1, -2, 10))
        col.addView(pill("🔍 افحص دلوقتي", primary = true) {
            status.text = "⏳ بيفحص التسجيلات…"
            Thread {
                val msg = try {
                    StudioIntel.checkAutoCalls(act)
                } catch (e: Throwable) {
                    LogStore.err("StudioIntelUi:autoCall", e)
                    "⚠ فشل الفحص"
                }
                main.post {
                    status.text = if (msg.isBlank()) "✅ مفيش مكالمات جديدة" else msg
                }
            }.apply { isDaemon = true }.start()
        }, lp(-1, -2, 8))
        // (v1003) قائمة الاستثناء — تسجيلات ماتتفرّغش تلقائي أبدًا.
        col.addView(pill("🚫 قائمة الاستثناء") {
            try {
                StudioPrivacyUi(act, ui, th).showExclusions()
            } catch (e: Throwable) {
                LogStore.err("StudioIntelUi:excl", e)
            }
        }, lp(-1, -2, 8))
        col.addView(status, lp(-1, -2, 8))
        col.addView(ui.text("ملحوظة: مفيش مراقبة في الخلفية طول الوقت — الفحص بيشتغل لما تفتح الاستوديو أو تدوس «افحص دلوقتي».", 11.5f, th.muted), lp(-1, -2, 4))
        openSheet("🔁 تفريغ تلقائي للمكالمات", col)
    }
}
