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

    private fun bubbleBg(me: Boolean): android.graphics.drawable.Drawable {
        if (!me) return glassBg(16)
        val tint = Glass.alpha(th.primary, 0x33)
        return if (MtGlass.on()) MtGlass.pill(th, tint, ui.dp(16).toFloat(), act.resources.displayMetrics.density) else ui.box(tint, th.border, 16)
    }

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        ui.text(label, 12.5f, if (primary) onPrimary else th.text, true).apply {
            gravity = Gravity.CENTER; maxLines = 1
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            background = if (primary) Glass.primaryFill(th, ui.dp(16).toFloat()) else glassBg(16)
            isClickable = true; Glass.pressable(this); setOnClickListener { onClick() }
        }

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
        var firstRender = true
        var bubbles: List<CallChat.Bubble> = emptyList()
        var bubbleViews = ArrayList<View>()
        val titleV = ui.text(Studio.displayTitle(d).ifBlank { "مكالمة" }, 15f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val metaV = ui.text("", 12f, th.muted)
        val body = vcol()
        val bodyScroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(body) }

        fun headerLine(): String {
            val parties = synchronized(d) { d.parties.toList() }
            val parts = ArrayList<String>()
            if (parties.isNotEmpty()) parts.add(parties.joinToString(" · "))
            if (d.durSec > 0) parts.add(Lecture.fmtTime(d.durSec))
            parts.add(statusOf(d))
            if (d.lang.isNotBlank()) parts.add("🌐 " + d.lang)
            return parts.joinToString(" · ")
        }

        fun refreshHeader() {
            metaV.text = headerLine()
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
        fun playAt(sec: Double) {
            if (d.uri.isBlank()) {
                Notice.show(act, "مفيش ملف صوت محلي للمكالمة دي", 2400L)
                return
            }
            try {
                val ms = (sec * 1000.0).toInt().coerceAtLeast(0)
                val title = Studio.displayTitle(d).ifBlank { "مكالمة" }
                MusicEngine.play(act, listOf(Links.trackOf(d.uri, title)), 0, false, ms)
                Notice.show(act, "▶ بيشغّل من " + Lecture.fmtTime(sec), 1800L)
            } catch (e: Throwable) {
                LogStore.err("CallChatUi:play", e)
                Notice.show(act, "⚠ مقدرتش أشغّل الصوت", 2400L)
            }
        }

        fun bubbleRow(b: CallChat.Bubble): View {
            val me = CallChat.isMe(d, b.spk)
            val box = vcol().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = bubbleBg(me) }
            val top = hrow()
            top.addView(ui.text(b.name, 12.5f, spkColor(b.spk), true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            if (d.uri.isNotBlank()) {
                top.addView(ui.text("▶", 14f, th.primary, true).apply {
                    setPadding(ui.dp(10), ui.dp(2), ui.dp(2), ui.dp(2)); isClickable = true; Glass.pressable(this)
                    setOnClickListener { playAt(b.start) }
                })
            }
            box.addView(top)
            box.addView(ui.text(b.text, 14f, th.text).apply { setLineSpacing(0f, 1.3f); setTextIsSelectable(true) }, lp(-1, -2, 4))
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
                LectureUi(act, ui, th).editSegDialog(d, seg) { lastSig = ""; renderAll(); refreshHeader() }
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
        }

        val content = vcol().apply { layoutParams = lp(-1, -1) }
        content.addView(titleV)
        content.addView(metaV, lp(-1, -2, 2))
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

        var tick: Runnable? = null
        val dd = ui.sheet(act, "📞 " + Studio.displayTitle(d).take(28), listOf<View>(content), fixedH = true, onClose = { alive = false; tick?.let { main.removeCallbacks(it) } }, footer = listOf<View>(acts))
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
        fun render() {
            list.removeAllViews()
            val voices = try {
                CallKit.listVoices(act)
            } catch (e: Throwable) {
                LogStore.err("CallChatUi:voices", e)
                emptyList()
            }
            if (voices.isEmpty()) {
                list.addView(ui.text("مفيش بصمات محفوظة لسه — افتح أي مكالمة ← 🎤 احفظ بصمة.", 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)) })
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
        content.addView(ui.text("لحفظ بصمة جديدة: افتح أي مكالمة ← 🎤 احفظ بصمة.", 11.5f, th.muted), lp(-1, -2, 4))
        content.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        val dd = ui.sheet(act, "🎤 الأصوات المحفوظة", listOf<View>(content), fixedH = true)
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
