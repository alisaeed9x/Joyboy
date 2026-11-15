package com.tttt.subtitler

import android.app.Activity
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
 * (v1001) 📞 صفحة الدردشة للمكالمات (واتساب-ستايل) + شاشة «الأصوات المحفوظة»:
 *  • الدردشة للعرض بس: فقاعات من CallChat.bubbles — الخام مبيتغيّرش.
 *  • دوسة الفقاعة بتفتح تعديل أول جملة فيها (LectureUi.editSegDialog).
 *  • ▶ على كل فقاعة بيشغّل التسجيل من لحظتها في مشغّل الموسيقى المصغّر (MusicEngine.play + startMs) — من غير ما تسيب الشاشة.
 *  • التنظيم/التلخيص لسه في قايمة الجمل (LectureUi.openList) — مفيش نسخة تانية من التبويبات هنا.
 *  • الأصوات المحفوظة: عرض/إعادة تسمية/حذف بس — مفيش مسجّل جديد هنا (مفيش مسجّل صوت جاهز في البرنامج؛ الحفظ من شاشة المكالمة نفسها).
 */
class CallChatUi(private val act: Activity, private val ui: Ui, private val th: Theme) {

    private val main = Handler(Looper.getMainLooper())
    private val amber = 0xFFF59E0B.toInt()
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK

    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = ui.dp(top) }
    private fun vcol() = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private fun hrow() = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }

    private fun glassBg(r: Int): android.graphics.drawable.Drawable =
        if (MtGlass.on()) MtGlass.pill(th, MtGlass.fill(th), ui.dp(r).toFloat(), act.resources.displayMetrics.density) else ui.box(th.surface, th.border, 12)

    private fun bubbleBg(me: Boolean, gender: String = ""): android.graphics.drawable.Drawable {
        val gc = genderColor(gender)
        if (!me && gc == 0) return glassBg(16)
        val tint = if (gc != 0) Glass.alpha(gc, 0x26) else Glass.alpha(th.primary, 0x33)
        return if (MtGlass.on()) MtGlass.pill(th, tint, ui.dp(16).toFloat(), act.resources.displayMetrics.density) else ui.box(tint, th.border, 16)
    }

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        ui.text(label, 12.5f, if (primary) onPrimary else th.text, true).apply {
            gravity = Gravity.CENTER; maxLines = 1
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            background = if (primary) Glass.primaryFill(th, ui.dp(16).toFloat()) else glassBg(16)
            isClickable = true; Glass.pressable(this); setOnClickListener { onClick() }
        }

    // (v1036) لون لكل جنس: ذكر أزرق، أنثى وردي — بيغلب لون الحرف لما الجنس يتعرف
    private val maleCol = 0xFF42A5F5.toInt()
    private val femaleCol = 0xFFEC407A.toInt()
    private fun genderColor(g: String): Int = when (g) { "male" -> maleCol; "female" -> femaleCol; else -> 0 }

    private fun spkColor(spk: String): Int = when (spk) {
        "A" -> th.primary
        "B" -> amber
        "C" -> 0xFFAB47BC.toInt()
        "D" -> th.primary
        "S" -> amber
        else -> th.muted
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

    // ===================== 📞 صفحة الدردشة =====================
    fun show(d: Lecture.Doc, startSec: Double = 0.0) {
        var alive = true
        var lastSig = ""
        var renderAllFn: () -> Unit = {}
        var firstRender = true
        var bubbles: List<CallChat.Bubble> = emptyList()
        var bubbleViews = ArrayList<View>()
        val titleV = ui.text(Studio.displayTitle(d).ifBlank { "مكالمة" }, 15f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val metaV = ui.text("", 12f, th.muted)
        val toneBar = hrow()
        val body = vcol()
        val bodyScroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(body) }

        fun headerLine(): String {
            val parties = synchronized(d) { d.parties.toList() }
            val parts = ArrayList<String>()
            if (parties.isNotEmpty()) parts.add(parties.mapIndexed { i, nm -> nm + (Lecture.genderMark(d, ('A' + i).toString()).let { m -> if (m.isEmpty()) "" else " $m" }) }.joinToString(" · "))
            if (d.durSec > 0) parts.add(Lecture.fmtTime(d.durSec))
            parts.add(statusOf(d))
            if (d.lang.isNotBlank()) parts.add("🌐 " + d.lang)
            return parts.joinToString(" · ")
        }

        fun refreshHeader() {
            metaV.text = headerLine()
        }

        // (v1002) خط زمني للنبرة: شريط ملوّن من Seg.tone — الدوسة على جزء بتقفز للفقاعة اللي فيها اللحظة.
        fun jumpToBubble(sec: Double) {
            try {
                val segs = Lecture.displaySegs(d)
                val segIdx = segs.indexOfFirst { it.start >= sec }
                if (segIdx < 0) return
                val bi = bubbles.indexOfLast { it.firstSegIdx <= segIdx }
                if (bi < 0) return
                val v = bubbleViews.getOrNull(bi) ?: return
                bodyScroll.post { bodyScroll.scrollTo(0, v.top) }
            } catch (_: Throwable) {}
        }

        fun paintTone() {
            toneBar.removeAllViews()
            val segs = StudioIntel.toneSegments(d)
            if (segs.isEmpty()) return
            val total = d.durSec.coerceAtLeast(1.0)
            for (t in segs) {
                val w = ((t.end - t.start) / total).toFloat().coerceAtLeast(0.02f)
                val v = View(act).apply {
                    setBackgroundColor(StudioIntel.toneColor(t.tone))
                    isClickable = true
                    setOnClickListener { jumpToBubble(t.start) }
                }
                toneBar.addView(v, LinearLayout.LayoutParams(0, -1, w).apply { marginEnd = 1 })
            }
        }

        fun isAtBottom(): Boolean {
            return try {
                val range = body.height - bodyScroll.height
                range <= 0 || bodyScroll.scrollY >= range - ui.dp(24)
            } catch (_: Throwable) {
                true
            }
        }

        // (v1001) ▶ على الفقاعة: شغّل التسجيل من لحظة الفقاعة في المشغّل المصغّر (من غير ما تسيب الدردشة).
        // لينك من غير ملف محلي (uri فاضي) مفيهوش زرار تشغيل أصلًا.
        // (v1030) ▶ بيشغّل الرسالة دي بس: بيقف لوحده عند آخرها، والرسالة اللي بعدها بزرار ▶ بتاعها.
        var stopWatch: Runnable? = null
        fun cancelWatch() { stopWatch?.let { main.removeCallbacks(it) }; stopWatch = null }
        fun playAt(sec: Double, endSec: Double = 0.0) {
            if (d.uri.isBlank()) {
                Notice.show(act, "مفيش ملف صوت محلي للمكالمة دي", 2400L)
                return
            }
            try {
                cancelWatch()
                val ms = (sec * 1000.0).toInt().coerceAtLeast(0)
                val title = Studio.displayTitle(d).ifBlank { "مكالمة" }
                val track = Links.trackOf(d.uri, title)
                MusicEngine.play(act, listOf(track), 0, false, ms)
                Notice.show(act, "▶ بيشغّل الرسالة دي بس (من " + Lecture.fmtTime(sec) + ")", 1800L)
                if (endSec > sec + 0.2) {
                    val endMs = (endSec * 1000.0).toInt()
                    val myUri = track.uri
                    val giveUpAt = System.currentTimeMillis() + ((endSec - sec) * 1000.0).toLong() + 20000L
                    val w = object : Runnable {
                        override fun run() {
                            if (stopWatch !== this) return
                            try {
                                // لو المشغّل بقى على حاجة تانية (المستخدم شغّل أغنية/تسجيل غيره) نفك المراقبة من غير ما نقفل حاجة
                                if (!alive || MusicEngine.current?.uri != myUri || System.currentTimeMillis() > giveUpAt) { stopWatch = null; return }
                                if (MusicEngine.ready && MusicEngine.posMs >= endMs) {
                                    MusicEngine.pause()
                                    stopWatch = null
                                    return
                                }
                            } catch (_: Throwable) { stopWatch = null; return }
                            main.postDelayed(this, 80L)
                        }
                    }
                    stopWatch = w
                    main.postDelayed(w, 250L)
                }
            } catch (e: Throwable) {
                LogStore.err("CallChatUi:play", e)
                Notice.show(act, "⚠ مقدرتش أشغّل الصوت", 2400L)
            }
        }

        // (v1036) 🔄 «بدّل الأطراف من هنا»: A↔B من الفقاعة دي لآخر المكالمة
        fun swapFromDialog(sec: Double) {
            if (Lecture.isRunning(d.id)) { Notice.show(act, "استنى التفريغ يخلص الأول", 2400L); return }
            GAlert(act).setTitle("🔄 بدّل الأطراف من هنا")
                .setMessage("هيتبدّل «أ» و«ب» في كل الكلام من " + Lecture.fmtTime(sec) + " لآخر المكالمة (اللي قبله ما بيتغيّرش). التنظيم والتلخيص الحاليين هيتمسحوا عشان بيتبنوا على الأطراف القديمة.")
                .setPositiveButton("بدّل") { _, _ ->
                    val n = Lecture.swapFrom(act, d, sec)
                    Notice.show(act, if (n > 0) "🔄 اتبدّلت " + n + " جملة — الملخص/التنظيم اتمسحوا" else "مفيش حاجة اتبدّلت", 3000L)
                    lastSig = ""; renderAllFn(); refreshHeader()
                }.setNegativeButton("إلغاء", null).show()
        }

        // (v1036) فقاعة «جزء ناقص» مكانه الزمني — الدوسة = إعادة محاولة الأجزاء الناقصة بس
        fun failedRow(b: CallChat.Bubble): View {
            val box = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(16) }
            box.addView(ui.text(b.name, 12.5f, amber, true))
            box.addView(ui.text(b.text, 12.5f, th.muted), lp(-1, -2, 4))
            box.isClickable = true; Glass.pressable(box)
            box.setOnClickListener {
                if (Lecture.isRunning(d.id)) { Notice.show(act, "التفريغ شغّال دلوقتي", 2000L); return@setOnClickListener }
                Lecture.start(act, d)
                Notice.show(act, "🔁 بعيد الأجزاء الناقصة…", 2400L)
                lastSig = ""; renderAllFn(); refreshHeader()
            }
            return box
        }

        fun bubbleRow(b: CallChat.Bubble): View {
            if (b.failedChunk >= 0) return failedRow(b)
            val me = CallChat.isMe(d, b.spk)
            val box = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = bubbleBg(me, b.gender) }
            val top = hrow()
            val gcol = genderColor(b.gender)
            top.addView(ui.text(b.name + (Lecture.genderMark(d, b.spk).let { m -> if (m.isEmpty()) "" else " $m" }), 12.5f, if (gcol != 0) gcol else spkColor(b.spk), true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            if ((b.spk == "A" || b.spk == "B") && !Lecture.isRunning(d.id)) {
                top.addView(ui.text("🔄", 13f, th.muted, true).apply {
                    setPadding(ui.dp(10), ui.dp(2), ui.dp(2), ui.dp(2)); isClickable = true; Glass.pressable(this)
                    setOnClickListener { swapFromDialog(b.start) }
                })
            }
            if (d.uri.isNotBlank()) {
                top.addView(ui.text("▶", 14f, th.primary, true).apply {
                    setPadding(ui.dp(10), ui.dp(2), ui.dp(2), ui.dp(2)); isClickable = true; Glass.pressable(this)
                    setOnClickListener { playAt(b.start, b.end) }
                })
            }
            box.addView(top)
            // (v1003) الإخفاء بيتطبق على العرض بس — الفقاعة نفسها (CallChat.Bubble) مبتتغيّرش.
            val parties = synchronized(d) { d.parties.toList() }
            val shownText = if (StudioPrivacy.isRedactOn(act)) StudioPrivacy.redact(b.text, parties) else b.text
            box.addView(ui.text(shownText, 14f, th.text).apply { setLineSpacing(0f, 1.3f); setTextIsSelectable(true) }, lp(-1, -2, 4))
            val meta = hrow()
            meta.addView(ui.text(Lecture.fmtTime(b.start), 11f, th.muted, true))
            if (b.tone.isNotBlank()) {
                meta.addView(ui.text(CallChat.toneEmoji(b.tone) + " " + b.tone, 11f, th.muted).apply { setPadding(ui.dp(8), 0, 0, 0) })
            }
            box.addView(meta, lp(-1, -2, 4))
            // فقاعة «أنا» على اليمين والتانية على الشمال — في الـRTL: START = يمين، END = شمال.
            val w75 = (act.resources.displayMetrics.widthPixels * 0.75).toInt()
            val outer = vcol()
            outer.addView(box, LinearLayout.LayoutParams(w75, -2).apply { gravity = if (me) Gravity.START else Gravity.END })
            outer.isClickable = true
            Glass.pressable(outer)
            outer.setOnClickListener {
                val seg = try {
                    Lecture.displaySegs(d).getOrNull(b.firstSegIdx)
                } catch (_: Throwable) {
                    null
                }
                if (seg == null) return@setOnClickListener
                LectureUi(act, ui, th).editSegDialog(d, seg) { lastSig = ""; renderAllFn(); refreshHeader() }
            }
            // (G8) ضغطة طويلة على الفقاعة = علامة على لحظة أول جملة فيها (نفس نافذة 0.6ث بتاعة sentRow).
            outer.setOnLongClickListener {
                val seg = try {
                    Lecture.displaySegs(d).getOrNull(b.firstSegIdx)
                } catch (_: Throwable) {
                    null
                }
                if (seg == null) return@setOnLongClickListener false
                val added = try {
                    StudioIntel.toggleBookmark(act, d, seg)
                } catch (e: Throwable) {
                    LogStore.err("CallChatUi:bookmark", e)
                    false
                }
                Notice.show(act, if (added) "🔖 اتحفظت العلامة عند " + Lecture.fmtTime(seg.start) else "🔖 اتشالت العلامة", 2200L)
                true
            }
            return outer
        }

        fun renderAll() {
            val wasBottom = if (firstRender) false else isAtBottom()
            val sy = bodyScroll.scrollY
            firstRender = false
            body.removeAllViews()
            bubbleViews = ArrayList()
            bubbles = CallChat.bubbles(d)
            if (bubbles.isEmpty()) {
                val r = Lecture.runOf(d.id)
                body.addView(ui.text(if (r != null && r.running) "⏳ لسه ما وصلش أول جزء…" else "مفيش تفريغ لسه.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12)) })
            } else {
                for (b in bubbles) {
                    val v = bubbleRow(b)
                    bubbleViews.add(v)
                    body.addView(v, lp(-1, -2, 6))
                }
            }
            bodyScroll.post {
                if (!alive) return@post
                try {
                    if (wasBottom) bodyScroll.scrollTo(0, body.height) else bodyScroll.scrollTo(0, sy)
                } catch (_: Throwable) {}
            }
            paintTone()
        }

        val content = vcol().apply { layoutParams = lp(-1, -1) }
        content.addView(titleV)
        content.addView(metaV, lp(-1, -2, 2))
        content.addView(toneBar, LinearLayout.LayoutParams(-1, ui.dp(10)).apply { topMargin = ui.dp(6) })
        content.addView(bodyScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })

        val acts = hrow().apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        acts.addView(pill("🗣 إعادة تسمية متحدث") {
            LectureUi(act, ui, th).renameSpeakerDialog(d) { lastSig = ""; renderAll(); refreshHeader() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        acts.addView(pill("🔀 دمج متحدثين") {
            LectureUi(act, ui, th).mergeSpeakersDialog(d) { lastSig = ""; renderAll(); refreshHeader() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        acts.addView(pill("📝 عرض كقائمة جمل") {
            LectureUi(act, ui, th).openList(d)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        // (v1030) 🔁 إعادة تفريغ + 🗑 حذف التفريغ من شاشة المكالمة نفسها (نفس منطق StudioUi.restartDoc / confirmDelete)
        var ddRef: android.app.Dialog? = null
        fun busy(): Boolean = Lecture.isRunning(d.id) || StudioComplete.isRunning(d.id)
        val acts2 = hrow().apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) } }
        acts2.addView(pill("🔁 إعادة تفريغ") {
            GAlert(act).setTitle("🔁 إعادة التفريغ من الأول")
                .setMessage("«" + Studio.displayTitle(d).take(50) + "» — هيمسح التفريغ الحالي والتنظيم والتلخيص ويفرّغ كل الأجزاء من جديد.")
                .setPositiveButton("امسح وفرّغ من الأول") { _, _ ->
                    Notice.show(act, "⏳ بجهّز إعادة التفريغ…", 2200L)
                    Thread {
                        try {
                            StudioComplete.stop(d.id)
                            Lecture.stop(d.id)
                            var waited = 0
                            while (busy() && waited < 60) { Thread.sleep(500L); waited++ }
                            if (busy()) {
                                main.post { Notice.show(act, "التفريغ لسه بيقفل — جرّب تاني بعد ثواني", 3000L) }
                            } else {
                                Lecture.resetTranscript(act, d)
                                Lecture.start(act, d)
                                main.post { Notice.show(act, "🔁 التفريغ بدأ من الأول", 2600L); lastSig = ""; renderAll(); refreshHeader() }
                            }
                        } catch (e: Throwable) {
                            LogStore.err("CallChatUi:restart", e)
                            main.post { Notice.show(act, "⚠ مقدرتش أعيد التفريغ", 2600L) }
                        }
                    }.apply { isDaemon = true }.start()
                }
                .setNegativeButton("إلغاء", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        acts2.addView(pill("🗑 حذف التفريغ") {
            GAlert(act).setTitle("🗑 حذف التفريغ")
                .setMessage("«" + Studio.displayTitle(d).take(50) + "» — هيمسح التفريغ والترجمة والملخصات والعلامات نهائيًا.")
                .setPositiveButton("حذف") { _, _ ->
                    Thread {
                        try {
                            StudioComplete.stop(d.id)
                            Lecture.stop(d.id)
                            Lecture.deleteFull(act, d.id)
                            main.post { Notice.show(act, "🗑 اتحذف التفريغ", 2200L); try { ddRef?.dismiss() } catch (_: Throwable) {} }
                        } catch (e: Throwable) {
                            LogStore.err("CallChatUi:delete", e)
                            main.post { Notice.show(act, "⚠ مقدرتش أحذف التفريغ", 2600L) }
                        }
                    }.apply { isDaemon = true }.start()
                }
                .setNegativeButton("إلغاء", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        // (v1036) ⚧ جنس الأطراف: تلقائي من التفريغ، وتعديل يدوي بيغلب التلقائي
        acts2.addView(pill("⚧ الجنس") {
            val letters = synchronized(d) {
                (d.segs.map { it.spk }.filter { it.length == 1 && it[0] in 'A'..'E' } + d.parties.indices.map { ('A' + it).toString() }).toSet().sorted()
            }
            if (letters.isEmpty()) { Notice.show(act, "مفيش أطراف لسه — فرّغ المكالمة الأول", 2400L); return@pill }
            val labels = letters.map { l -> CallChat.speakerName(d, l) + "  " + Lecture.genderMark(d, l).ifEmpty { "؟" } }.toTypedArray()
            GAlert(act).setTitle("⚧ جنس الأطراف").setItems(labels) { _, w ->
                val l = letters[w]
                GAlert(act).setTitle(CallChat.speakerName(d, l)).setItems(arrayOf("🤖 تلقائي (من التفريغ الجاي)", "♂ ذكر", "♀ أنثى")) { _, g ->
                    Lecture.setPartyGender(act, d, l[0] - 'A', when (g) { 1 -> "m"; 2 -> "f"; else -> "" })
                    Notice.show(act, "اتحفظ — هيتطبّق على أي ترجمة جاية (الترجمات اللي اتعملت قبل كده ما بتتغيّرش)", 3200L)
                    lastSig = ""; renderAllFn(); refreshHeader()
                }.setNegativeButton("إلغاء", null).show()
            }.setNegativeButton("إغلاق", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))

        var tick: Runnable? = null
        val dd = ui.sheet(act, "📞 " + Studio.displayTitle(d).take(28), listOf<View>(content), fixedH = true, onClose = { alive = false; cancelWatch(); tick?.let { main.removeCallbacks(it) } }, footer = listOf<View>(acts, acts2))
        ddRef = dd
        renderAllFn = { renderAll() }
        refreshHeader()
        renderAll()
        dd.show()

        // (v1000) قفز للحظة المطلوبة (من بحث السجل): الفقاعة اللي فيها أول جملة بتبدأ عند startSec أو بعدها.
        if (startSec > 0.0) {
            bodyScroll.post {
                try {
                    val segs = Lecture.displaySegs(d)
                    val segIdx = segs.indexOfFirst { it.start >= startSec }
                    if (segIdx >= 0) {
                        val bi = bubbles.indexOfLast { it.firstSegIdx <= segIdx }
                        if (bi >= 0) {
                            val v = bubbleViews.getOrNull(bi)
                            if (v != null) bodyScroll.scrollTo(0, v.top)
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        val t = object : Runnable {
            override fun run() {
                if (!alive) return
                try {
                    refreshHeader()
                    val r = Lecture.runOf(d.id)
                    val sig = d.done.size.toString() + "/" + d.failed.size + "/" + d.segs.size + "/" + d.langCode + "/" + (r?.running ?: false)
                    if (sig != lastSig) {
                        lastSig = sig
                        renderAll()
                    }
                } catch (_: Throwable) {}
                main.postDelayed(this, 1000L)
            }
        }
        tick = t
        main.postDelayed(t, 1000L)
    }

    // ===================== 🎤 الأصوات المحفوظة =====================
    fun showVoices() {
        val list = vcol()
        // (v1036 — المرحلة 4) ▶ سماع البصمة المحفوظة: ملف مؤقت في الكاش + MediaPlayer لوحده (من غير ما نلمس مشغّل الموسيقى)
        var player: android.media.MediaPlayer? = null
        fun stopPlayer() { try { player?.release() } catch (_: Throwable) {}; player = null }
        fun playVoice(v: CallKit.VoiceEntry) {
            try {
                stopPlayer()
                val bytes = CallKit.voice(act, v.key)
                if (bytes == null) { Notice.show(act, "⚠ ملف البصمة مش موجود", 2400L); return }
                val f = java.io.File(act.cacheDir, "voice_preview.wav")
                f.writeBytes(bytes)
                val mp = android.media.MediaPlayer()
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener { try { it.release() } catch (_: Throwable) {}; if (player === it) player = null }
                mp.prepare()
                mp.start()
                player = mp
                Notice.show(act, "▶ بيشغّل بصمة «" + v.name + "»", 1600L)
            } catch (e: Throwable) {
                LogStore.err("CallChatUi:voicePlay", e)
                Notice.show(act, "⚠ مقدرتش أشغّل البصمة", 2400L)
            }
        }
        fun render() {
            list.removeAllViews()
            val voices = try {
                CallKit.listVoices(act)
            } catch (e: Throwable) {
                LogStore.err("CallChatUi:voices", e)
                emptyList()
            }
            if (voices.isEmpty()) {
                list.addView(ui.text("مفيش بصمات محفوظة لسه — دوس ➕ فوق واختار مكالمة.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)) })
                return
            }
            for (v in voices) {
                val row = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = glassBg(16) }
                val top = hrow()
                val t = vcol()
                t.addView(ui.text(v.name.ifBlank { "بصمة" }, 14f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val date = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(java.util.Date(v.ts))
                t.addView(ui.text("🎤 بصمة محفوظة · " + date, 11.5f, th.muted).apply { maxLines = 1 })
                top.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(ui.text("▶", 16f, th.primary, true).apply {
                    setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6)); isClickable = true; Glass.pressable(this)
                    setOnClickListener { playVoice(v) }
                })
                top.addView(ui.text("✎", 16f, th.muted).apply {
                    setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6)); isClickable = true; Glass.pressable(this)
                    setOnClickListener { renameVoiceDialog(v) { render() } }
                })
                top.addView(ui.text("🗑", 16f, th.muted).apply {
                    setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6)); isClickable = true; Glass.pressable(this)
                    setOnClickListener { deleteVoiceDialog(v) { render() } }
                })
                row.addView(top)
                list.addView(row, lp(-1, -2, 8))
            }
        }
        val content = vcol().apply { layoutParams = lp(-1, -1) }
        content.addView(ui.text("البصمة بتتحفظ باسم الشخص — لو غيّرت اسم جهة الاتصال في التليفون، البصمة القديمة بتتّم ومبتتربطش بالاسم الجديد (المفتاح مربوط بالاسم وقت الحفظ).", 11.5f, th.muted), lp(-1, -2))
        // (v1036) ➕ بصمة جديدة: بيفتح قايمة المكالمات (فيها «🎤 احفظ بصمة» لكل مكالمة) — بعد الحفظ ارجع افتح الشاشة دي تاني عشان تتحدّث
        content.addView(pill("➕ حفظ بصمة جديدة من مكالمة") {
            try { LectureUi(act, ui, th).showCalls() } catch (e: Throwable) { LogStore.err("CallChatUi:addVoice", e) }
        }, lp(-1, -2, 6))
        content.addView(ui.text("بعد ما تحفظ بصمة ارجع افتح «الأصوات المحفوظة» تاني عشان تظهر هنا.", 11.5f, th.muted), lp(-1, -2, 4))
        content.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        val dd = ui.sheet(act, "🎤 الأصوات المحفوظة", listOf<View>(content), fixedH = true, onClose = { stopPlayer() })
        dd.show()
        render()
    }

    // إعادة التسمية بتغيّر الاسم الظاهر في الفهرس بس — المفتاح بيفضل مربوط بالاسم القديم (CallKit.renameVoice).
    private fun renameVoiceDialog(v: CallKit.VoiceEntry, onDone: () -> Unit) {
        val nameEt = ui.input("الاسم الجديد", v.name)
        GAlert(act).setTitle("✎ إعادة تسمية البصمة").setMessage("الاسم الظاهر بس اللي بيتغيّر — المفتاح بيفضل مربوط بالاسم القديم، فلو غيّرت اسم جهة الاتصال البصمة ممكن تتّم.").setView(nameEt)
            .setPositiveButton("حفظ") { _, _ ->
                val nm = nameEt.text.toString().trim()
                if (nm.isEmpty()) {
                    Notice.show(act, "اكتب الاسم الجديد الأول", 2400L)
                    return@setPositiveButton
                }
                CallKit.renameVoice(act, v.key, nm)
                Notice.show(act, "✅ بقى اسمها «" + nm + "»", 2200L)
                onDone()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun deleteVoiceDialog(v: CallKit.VoiceEntry, onDone: () -> Unit) {
        GAlert(act).setTitle("🗑 حذف البصمة").setMessage("«${v.name}» — هيتمسح المقطع المرجعي نهائيًا.")
            .setPositiveButton("حذف") { _, _ ->
                CallKit.deleteVoice(act, v.key)
                Notice.show(act, "🗑 اتمسحت بصمة «" + v.name + "»", 2200L)
                onDone()
            }.setNegativeButton("إلغاء", null).show()
    }
}
