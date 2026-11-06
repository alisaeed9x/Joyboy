package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/** (v682) سلايدر صوت رأسي كبير: فوق = أعلى صوت. بيبعت القيمة وإنت بتسحب (محدود بمعدّل) وعند الرفع */
class VertVolume(ctx: Context, private val onChange: (Int) -> Unit) : View(ctx) {
    private val den = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rc = RectF()
    var value = 50
        private set
    var dragging = false
        private set
    private var lastSent = -1
    private var lastAt = 0L

    fun setValue(v: Int) { val n = v.coerceIn(0, 100); if (n != value) { value = n; invalidate() } }

    private fun top() = 8f * den
    private fun bot() = height - 8f * den
    private fun fromY(y: Float): Int {
        val a = top(); val b = bot()
        if (b <= a) return value
        return (((b - y) / (b - a)) * 100f).toInt().coerceIn(0, 100)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); apply(e.y, true) }
            MotionEvent.ACTION_MOVE -> apply(e.y, false)
            MotionEvent.ACTION_UP -> { apply(e.y, true); dragging = false }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun apply(y: Float, force: Boolean) {
        val n = fromY(y)
        if (n != value) { value = n; invalidate() }
        val now = System.currentTimeMillis()
        if (n != lastSent && (force || now - lastAt > 90L)) { lastSent = n; lastAt = now; onChange(n) }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val tw = w * 0.80f; val x0 = (w - tw) / 2f; val r = tw / 2.4f
        p.style = Paint.Style.FILL; p.color = 0xFF1E2F4D.toInt()
        rc.set(x0, 0f, x0 + tw, h); c.drawRoundRect(rc, r, r, p)
        val fh = h * (value / 100f)
        if (fh > 0f) {
            p.color = if (dragging) 0xFF8FB4FF.toInt() else 0xFF5B8DEF.toInt()
            rc.set(x0, h - fh, x0 + tw, h)
            val rr = minOf(r, fh / 2f)
            c.drawRoundRect(rc, rr, rr, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * den; p.color = 0xFF34507A.toInt()
        rc.set(x0, 0f, x0 + tw, h); c.drawRoundRect(rc, r, r, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER
        p.textSize = 20f * den; p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText(value.toString(), w / 2f, h / 2f + 7f * den, p)
    }
}

/**
 * (v682) صفحة «المشغّل» في الريموت: بتتحكّم في الفيديو الشغّال على التلفزيون من الموبايل (بورتريه دايمًا، ومن غير ما يفتح مشغّل على الموبايل).
 * الحالة (عنوان · عدّاد الجمل · سرعة · ترجمة · تغطية الشريط) بتيجي من LinkSession.mirror (نفس /sync بتاع وضع المتحكم)،
 * والأوامر بتروح PlayerActivity.rAct على التلفزيون عن طريق RemoteClient.player(...).
 */
/** (v787) أجزاء صفحة المشغّل اللي الريموت بيرصّها في صفحته الواحدة (PlayerPad.show بـ embedInto) */
class PadParts(
    val quick: View, val sub: View, val tabs: View, val tabBody: View,
    val tr: View, val transport: View, val bar: View, val times: View,
    val logBtn: View, val repeatRow: View, val info: View, val studio: View,   // (v795) studio = زرار واحد لكل تحكمات الترجمة (سلايدرات)
    val mk: (String, Float, Int, Int, () -> Unit) -> TextView
)

/** (v804) اختيار جودة للتحميل من ريموت الموبايل */
private class DlPick(val label: String, val url: String, val audio: String?, val hls: Boolean, val h: Int, val size: Long, val approx: Boolean)

object PlayerPad {
    /** (v684) آخر مترجم اتختار لترجمة ملف الترجمة (google / gemini) */
    @Volatile private var lastSfEng = "gemini"

    fun show(act: Activity, onLeave: (() -> Unit)? = null, embedInto: ((PadParts) -> Unit)? = null): (() -> Unit)? {
        var outDispose: (() -> Unit)? = null
        try {
            try { RemoteClient.init(act) } catch (_: Throwable) {}
            val dm = act.resources.displayMetrics
            fun dp(v: Int) = (v * dm.density).toInt()
            fun dpf(v: Float) = v * dm.density
            val th = Themes.byId(Cfg.str("theme", "mx"))
            val hh = Handler(Looper.getMainLooper())
            val oldOri = act.requestedOrientation
            if (embedInto == null) { try { act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } catch (_: Throwable) {} }
            val d = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)   // (v782) ورقة بتطلع من تحت فوق الريموت بدل صفحة كاملة
            val root = FrameLayout(act).apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f); setColor(0xFF0F1830.toInt()); setStroke(dp(1), 0xFF2B3C60.toInt()) }
            }

            val cCard = 0xFF14203A.toInt(); val cLine = 0xFF2B3C60.toInt(); val cOn = 0xFF2563EB.toInt(); val cOnLine = 0xFF3B82F6.toInt()
            val cDim = 0xFF8FA0BC.toInt()

            // ── حالة الصفحة (بتتحدّث من render) ──
            var st: JSONObject = JSONObject()
            var durNow = 0L
            var holdUntil = 0L
            var closed = false
            var sheetsOpen = 0
            var started = false
            var paused = false
            var curDialect = ""
            var curStyle = ""
            var curStrength = ""
            // (v709) تأثير «الكوباية»: زرار اللهجة/الأدوات بيتملي لحد ما الأداة تخلص
            val fills = ArrayList<PadFill>()
            var toolKey = ""; var toolPct = 0; var toolSince = 0L
            var doneKey = ""; var doneAt = 0L
            var tapKey = ""; var tapAt = 0L
            var bgNow = 45
            var qList: List<String> = emptyList(); var qCur = 0
            var aList: List<String> = emptyList(); var aCur = 0

            /** (v688) تفتيح لون بنسبة f (0..1) — لتدرّج خفيف بدل لون مسطّح */
            fun lighten(c: Int, f: Float): Int {
                val a = (c ushr 24) and 0xFF; val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                fun m(x: Int) = (x + (255 - x) * f).toInt().coerceIn(0, 255)
                return (a shl 24) or (m(r) shl 16) or (m(g) shl 8) or m(b)
            }
            fun bg(fill: Int, stroke: Int, r: Float) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighten(fill, 0.07f), fill)).apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(r); setStroke(dp(1), stroke)
            }
            fun pill(c: Int, r: Float = 2f) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(r); setColor(c) }
            fun label(t: String, sp: Float, col: Int = Color.WHITE, bold: Boolean = true): TextView = IconTextView(act).apply {
                text = t; textSize = sp; setTextColor(col); gravity = Gravity.CENTER; includeFontPadding = false
                if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            fun btn(t: String, sp: Float = 14f, fill: Int = cCard, stroke: Int = cLine, tap: (TextView) -> Unit): TextView = label(t, sp).apply {
                background = bg(fill, stroke, 14f); setPadding(dp(6), dp(8), dp(6), dp(8)); isClickable = true
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    try { tap(this) } catch (e: Throwable) { LogStore.err("PlayerPad:btn", e) }
                }
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(60L).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    }
                    false
                }
            }
            fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
            fun go(a: String, v: Long = 0L) {
                Thread {
                    try {
                        val r = RemoteClient.player(a, v)
                        val n = r?.optString("note", "") ?: ""   // (v799) رسالة الزرار بتظهر على الموبايل بس (مش على التلفزيون)
                        if (n.isNotEmpty()) try { act.runOnUiThread { if (!act.isFinishing && !act.isDestroyed) Notice.show(act, n, 2200L) } } catch (_: Throwable) {}
                    } catch (e: Throwable) { LogStore.err("PlayerPad:go", e) }
                }.also { it.isDaemon = true }.start()
            }
            /** (v709) يلبّس الزرار طبقة ميه بتطلع من تحت (ClipDrawable) — key = مفتاح الأداة اللي بتملّيه، "*" = أي أداة */
            fun fillable(b: TextView, key: String): TextView {
                val water = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(14f); setColor(0xAA22C55E.toInt()) }
                val clip = android.graphics.drawable.ClipDrawable(water, Gravity.BOTTOM, android.graphics.drawable.ClipDrawable.VERTICAL)
                clip.level = 0
                b.background = android.graphics.drawable.LayerDrawable(arrayOf(b.background, clip))
                fills.add(PadFill(key, b, clip, System.currentTimeMillis()))
                return b
            }
            /** دوسة على أداة: الزرار يبدأ يتملّى فورًا لحد ما التلفزيون يبلّغ إنه شغّال */
            fun tapFill(k: String) { if (k.isEmpty() || k == "dedup") return; tapKey = k; tapAt = System.currentTimeMillis() }
            fun note(t: String) { try { android.widget.Toast.makeText(act, t, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} }
            fun tm(ms: Long): String {
                val s = (ms / 1000L).coerceAtLeast(0L); val h = s / 3600L; val m = (s % 3600L) / 60L; val c = s % 60L
                return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, c) else String.format(Locale.US, "%02d:%02d", m, c)
            }
            fun strList(a: org.json.JSONArray?): List<String> {
                val out = ArrayList<String>()
                if (a != null) for (i in 0 until a.length()) out.add(a.optString(i))
                return out
            }

            /** (v779) رقم الجودة الحالية على الزرار (720p…) بدل أيقونة 🎞؛ لو مفيش جودات بتفضل الأيقونة */
            fun qShort(): String {
                val l = qList.getOrNull(qCur) ?: return "🎞"
                val m = Regex("(\\d{3,4})\\s*[pPiI]?").find(l)
                return if (m != null) m.groupValues[1] + "p" else l.take(7)
            }
            // ── ورقة سفلية عامة: sheet(عنوان) { جسم, إعادة بناء, إغلاق -> ... } ──
            fun sheet(title: String, fill: (LinearLayout, () -> Unit, () -> Unit) -> Unit) {
                val sd = Dialog(act)
                sd.requestWindowFeature(Window.FEATURE_NO_TITLE)
                val panel = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                    setPadding(dp(16), dp(14), dp(16), dp(20))
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1C2A4A.toInt(), 0xFF121C34.toInt())).apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadii = floatArrayOf(dpf(26f), dpf(26f), dpf(26f), dpf(26f), 0f, 0f, 0f, 0f)
                        setStroke(dp(1), cLine)
                    }
                }
                panel.addView(View(act).apply { background = pill(0xFF3A4C73.toInt(), 3f) }, lp(dp(42), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
                panel.addView(label(title, 18f), lp(-1, -2).apply { bottomMargin = dp(2) })
                val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                val scroll = MaxHeightScroll(act, (dm.heightPixels * 0.72f).toInt()).apply { isVerticalScrollBarEnabled = false; addView(body) }
                panel.addView(scroll, lp(-1, -2))
                var redoFn: () -> Unit = {}
                val closeFn: () -> Unit = { try { sd.dismiss() } catch (_: Throwable) {} }
                redoFn = { body.removeAllViews(); fill(body, { redoFn() }, closeFn) }
                redoFn()
                sd.setContentView(panel)
                sd.window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM)
                    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT); setDimAmount(0.55f)
                }
                sheetsOpen += 1
                sd.setOnDismissListener { sheetsOpen = (sheetsOpen - 1).coerceAtLeast(0) }
                sd.show()
            }
            /** صفوف شيبس متساوية العرض (من اليمين) */
            fun chipRows(body: LinearLayout, labels: List<String>, per: Int, isOn: (String) -> Boolean, fk: ((String) -> String?)? = null, tap: (String) -> Unit) {
                var row: LinearLayout? = null
                labels.forEachIndexed { i, l ->
                    if (i % per == 0) {
                        row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                        body.addView(row, lp(-1, -2).apply { topMargin = dp(8) })
                    }
                    val on = isOn(l)
                    val cb = btn(l, 15f, if (on) cOn else cCard, if (on) cOnLine else cLine) { tap(l) }
                    fk?.invoke(l)?.let { fillable(cb, it) }
                    row?.addView(cb,
                        lp(0, dp(46), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                }
                val rem = labels.size % per
                if (rem != 0) for (k in 0 until per - rem) row?.addView(View(act), lp(0, dp(46), 1f))
            }
            fun opt(body: LinearLayout, t: String, tap: () -> Unit) {
                body.addView(btn(t, 15f) { tap() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }, lp(-1, dp(52)).apply { topMargin = dp(8) })
            }
            fun grp(body: LinearLayout, t: String) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(View(act).apply { background = pill(cOnLine) }, lp(dp(4), dp(14)))
                row.addView(label(t, 12.5f, cDim).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, lp(0, -2, 1f).apply { marginStart = dp(8) })
                body.addView(row, lp(-1, -2).apply { topMargin = dp(14) })
            }
            /** (v688) كارت معلومة/حالة جوه الورقة */
            fun infoCard(body: LinearLayout, t: String, col: Int = 0xFFBBD0FF.toInt(), fill: Int = 0xFF1B2B52.toInt(), line: Int = 0xFF3B5A99.toInt()) {
                body.addView(label(t, 12.5f, col, false).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; background = bg(fill, line, 14f); setPadding(dp(12), dp(10), dp(12), dp(10)) }, lp(-1, -2).apply { topMargin = dp(10) })
            }

            var logSrc = 0; var logDiag = true   // (v770) 0 = لوج التلفزيون · 1 = لوج الموبايل
            fun sheetLog() = sheet("📜 اللوج") { body, redo, _ ->
                val raw = if (logSrc == 0) LinkSession.logLines().joinToString("\n") else LogStore.lastLines(LogStore.currentText(), 400)
                val lines = raw.lines().filter { it.isNotBlank() && (!logDiag || it.contains("🔬") || it.contains("Player:tick") || it.contains("⚠") || it.contains("❌")) }.takeLast(150)
                val txt = lines.joinToString("\n")
                val r1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                r1.addView(btn("📺 التلفزيون", 14f, if (logSrc == 0) cOn else cCard, if (logSrc == 0) cOnLine else cLine) { logSrc = 0; redo() }, lp(0, dp(46), 1f).apply { marginEnd = dp(4) })
                r1.addView(btn("📱 الموبايل", 14f, if (logSrc == 1) cOn else cCard, if (logSrc == 1) cOnLine else cLine) { logSrc = 1; redo() }, lp(0, dp(46), 1f).apply { marginStart = dp(4) })
                body.addView(r1, lp(-1, -2).apply { topMargin = dp(10) })
                val r2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                r2.addView(btn("🔬 تشخيص بس", 13f, if (logDiag) cOn else cCard, if (logDiag) cOnLine else cLine) { logDiag = !logDiag; redo() }, lp(0, dp(46), 1f).apply { marginEnd = dp(4) })
                r2.addView(btn("🔄 تحديث", 13f) { redo() }, lp(0, dp(46), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                r2.addView(btn("📋 نسخ", 13f) {
                    try { (act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("log", txt)); note("📋 اتنسخ " + lines.size + " سطر") } catch (_: Throwable) { note("⚠ فشل النسخ") }
                }, lp(0, dp(46), 1f).apply { marginStart = dp(4) })
                body.addView(r2, lp(-1, -2).apply { topMargin = dp(8) })
                if (logSrc == 0 && !LinkSession.connected) infoCard(body, "مفيش اتصال بالتلفزيون دلوقتي — سطور التلفزيون بتوصل وانت متصل بس.")
                body.addView(label(if (txt.isEmpty()) "مفيش سطور لسه" + (if (logSrc == 0) " — شغّل فيديو على التلفزيون وسيبه يشتغل وارجع دوس 🔄" else "") else txt, 11f, 0xFFD6E2FF.toInt(), false).apply {
                    typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true); gravity = Gravity.START; textDirection = View.TEXT_DIRECTION_ANY_RTL
                    background = bg(0xFF0F1830.toInt(), 0xFF2B3F6E.toInt(), 14f); setPadding(dp(10), dp(8), dp(10), dp(8))
                }, lp(-1, -2).apply { topMargin = dp(10) })
            }
            fun sheetSpeed() = sheet("⏩ سرعة التشغيل") { body, _, close ->
                val cur = PosExtrap.speedOf(st.optString("speed", "1x"))
                chipRows(body, PlayerLogic.speeds.map { PlayerLogic.speedLabel(it) }, 3, { l -> Math.abs(PosExtrap.speedOf(l) - cur) < 0.01 }) { l ->
                    go("speedset", (PosExtrap.speedOf(l) * 100.0).toLong()); close()
                }
            }
            fun sheetList(title: String, items: List<String>, cur: Int, cmdName: String) = sheet(title) { body, _, close ->
                items.forEachIndexed { i, t ->
                    val on = i == cur
                    body.addView(btn((if (on) "✓ " else "") + t, 15f, if (on) cOn else cCard, if (on) cOnLine else cLine) { go(cmdName, i.toLong()); close() },
                        lp(-1, dp(48)).apply { topMargin = dp(8) })
                }
            }
            // ── (v684) ترجمة ملف ترجمة: المرفق مع الفيديو، أو ملف من الموبايل ← إعدادات (مترجم/لهجة/أسلوب/شدة) ← زرار «▶ ترجم» ──
            var sfEng = lastSfEng; var sfDia = "فصحى"; var sfSty = "حرفي"; var sfStr = "متوسطة"
            var dualSel = 0            // (v695) وضع العرض الحالي (من حالة التلفزيون)
            var appliedVer = -1        // (v695) آخر نسخة طبّقتها من «النسخ» (للتمييز في القايمة)
            var sfMobText: String? = null   // (v688) نص الملف اللي اخترته من الموبايل — الموبايل بيترجمه بالتوازي مع التلفزيون
            fun sheetSfSettings(src: String, what: String) = sheet("📄 إعدادات ترجمة الملف") { body, redo, close ->
                body.addView(label(what, 13f, cDim, false), lp(-1, -2).apply { topMargin = dp(6) })
                grp(body, "المترجم")
                chipRows(body, kotlin.collections.listOf("🌐 جوجل", "✨ جيميناي"), 2, { l -> l.startsWith("✨") == (sfEng == "gemini") }) { l ->
                    sfEng = if (l.startsWith("✨")) "gemini" else "google"; lastSfEng = sfEng; redo()
                }
                if (sfEng == "gemini") {
                    grp(body, "لهجة الترجمة")
                    chipRows(body, kotlin.collections.listOf("فصحى", "مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني"), 4, { l -> l == sfDia }) { l -> sfDia = l; redo() }
                    grp(body, "الأسلوب")
                    chipRows(body, kotlin.collections.listOf("حرفي", "شعبي", "جرئ", "+18"), 4, { l -> l == sfSty }) { l -> sfSty = l; redo() }
                    grp(body, "الشدة (مع شعبي / جرئ / +18)")
                    chipRows(body, kotlin.collections.listOf("خفيفة", "متوسطة", "شديدة"), 3, { l -> l == sfStr }) { l -> sfStr = l; redo() }
                } else {
                    body.addView(label("ℹ جوجل بيترجم فصحى بس — اللهجة والأسلوب والشدة مع جيميناي", 12.5f, 0xFFFFB74D.toInt(), false), lp(-1, -2).apply { topMargin = dp(10) })
                }
                infoCard(body, "🔀 الترجمة هتشتغل على التلفزيون والموبايل مع بعض — أول واحد يخلّص بيتطبّق والتاني بيقف. لو التلفزيون مفيهوش نت الموبايل بيكمّل لوحده.")
                body.addView(btn("▶ ترجم", 18f, 0xFF4F5BD5.toInt(), 0xFF7C8CF8.toInt()) {
                    if (sfEng == "gemini" && !SubFileTr.hasGemKeys()) {   // (v705) مفيش مفتاح جيميناي: وقّف وقول على الموبايل — من غير ما يبدّل لمترجم تاني
                        try {
                            GAlert(act).setTitle("⚠ جيميناي لا يحتوي على مفاتيح")
                                .setMessage("الترجمة ما بدأتش.")
                                .setPositiveButton("🔑 إضافة مفاتيح") { _, _ ->
                                    act.showKeyGuide { k ->
                                        val cur = Cfg.keys("keys")
                                        if (k in cur || k in Cfg.keys("extra") || k in Cfg.keys("backup")) note("⚠ المفتاح ده موجود قبل كده")
                                        else { Cfg.p.edit().putString("keys", (cur + k).joinToString("\n")).apply(); note("✓ اتحفظ المفتاح — دوس «ترجم» تاني") }
                                    }
                                }
                                .setNeutralButton("🔀 اختيار وضع آخر") { _, _ -> sfEng = "google"; lastSfEng = sfEng; redo() }
                                .setNegativeButton("إلغاء") { _, _ -> close() }
                                .show()
                        } catch (_: Throwable) { note("⚠ جيميناي لا يحتوي على مفاتيح — الترجمة ما بدأتش") }
                        return@btn
                    }
                    val jid = SubDual.newJid()
                    go("sfgo:" + src + ":" + sfEng + ":" + sfDia + ":" + sfSty + ":" + sfStr + ":" + jid)
                    SubDual.start(jid, if (src == "mobile") sfMobText else null, sfEng, sfDia, sfSty, sfStr) { m -> act.runOnUiThread { if (!closed) note(m) } }
                    note("⏳ بدأت الترجمة على التلفزيون والموبايل مع بعض — أول واحد يخلّص بيظهر")
                    close()
                }, lp(-1, dp(56)).apply { topMargin = dp(16) })
            }
            fun openSfSettings(src: String, what: String) {
                sfDia = curDialect.ifBlank { "فصحى" }; sfSty = curStyle.ifBlank { "حرفي" }; sfStr = curStrength.ifBlank { "متوسطة" }; sfEng = lastSfEng
                sheetSfSettings(src, what)
            }
            fun pickSubFromPhone() {
                if (!(LinkSession.mirror.live && LinkSession.mirror.gotFirst)) { note("شغّل فيديو على التلفزيون الأول"); return }
                SubPick.pick(act) { u ->
                    if (u == null || closed) return@pick
                    note("⏳ بقرا الملف…")
                    Thread {
                        val pk = SubPick.read(act, u)
                        if (pk == null) { act.runOnUiThread { note("الملف ده مش ترجمة صالحة (SRT / ASS / VTT) أو كبير زيادة") }; return@Thread }
                        sfMobText = pk.text
                        act.runOnUiThread { note("⏳ ببعت «" + pk.name + "» (" + pk.cues + " جملة) للتلفزيون…") }
                        val err = SubPick.upload(pk.name, pk.text)
                        act.runOnUiThread {
                            if (closed) return@runOnUiThread
                            if (err.isNotEmpty()) note("⚠ $err")
                            else openSfSettings("mobile", "📄 «" + pk.name + "» — " + pk.cues + " جملة وصلت للتلفزيون. ظبّط الإعدادات واضغط ترجم.")
                        }
                    }.also { it.isDaemon = true }.start()
                }
            }
            // ── (v746) ترجمة أونلاين / متصفح / ضبط توقيت تلقائي ──
            fun sheetSites() = sheet("🌐 موقع الترجمة") { body, _, close ->
                body.addView(label("بيفتح متصفح البرنامج على البحث باسم الفيديو، ودوس «تحميل» على ملف الترجمة (أو ZIP) وهيتضاف علطول", 12.5f, cDim, false), lp(-1, -2).apply { topMargin = dp(6) })
                SubHunt.siteNames.forEachIndexed { i, n -> opt(body, n) { go("subhunt:$i"); note("🌐 بيفتح المتصفح على التلفزيون…"); close() } }
            }
            fun fetchFromPhone() {
                if (!(LinkSession.mirror.live && LinkSession.mirror.gotFirst)) { note("شغّل فيديو على التلفزيون الأول"); return }
                val vn = st.optString("vname", ""); val vf = st.optString("vfolder", "")
                if (vn.isBlank()) { note("مش عارف اسم الفيديو"); return }
                note("⏳ بدوّر من الموبايل…")
                Thread {
                    try {
                        val info = SubName.build(vn, vf); val langs = SubOnline.prefLangs()
                        val rep: (String) -> Unit = { m -> act.runOnUiThread { if (!closed) note(m) } }
                        var g = SubOnline.cacheFind(act.applicationContext, info, langs)
                        if (g == null || g.lang != langs[0]) {
                            val s = SubOnline.Session(info, langs, SubOnline.search(info, langs, rep))
                            val g2 = SubOnline.fetchNext(act.applicationContext, s, rep)
                            if (g2 != null) g = g2
                        }
                        if (g == null) { act.runOnUiThread { if (!closed) note("⚠ مالقيتش ترجمة — جرّب المتصفح") }; return@Thread }
                        val err = SubPick.upload(g.fileName, g.text, true)
                        act.runOnUiThread { if (!closed) note(if (err.isEmpty()) "✅ اتبعتت للتلفزيون: " + g.source.take(40) else "⚠ $err") }
                    } catch (e: Throwable) { LogStore.err("PlayerPad:fetch", e); act.runOnUiThread { if (!closed) note("⚠ فشل البحث من الموبايل") } }
                }.also { it.isDaemon = true }.start()
            }
            fun sheetSubFile() = sheet("📄 ترجمة ملف الترجمة") { body, _, close ->
                val live = LinkSession.mirror.live && LinkSession.mirror.gotFirst
                val side = st.optString("sf_side", ""); val nat = st.optInt("sf_nat", 0); val busy = st.optString("sf_busy", ""); val mob = st.optString("sf_mob", "")
                if (!live) body.addView(label("⚠ شغّل فيديو في مشغّل التطبيق على التلفزيون الأول", 13f, 0xFFFFB74D.toInt()), lp(-1, -2).apply { topMargin = dp(8) })
                if (busy.isNotBlank()) infoCard(body, "📺 التلفزيون: $busy")
                val dualSt = SubDual.status
                if (dualSt.isNotBlank()) {
                    infoCard(body, dualSt)
                    opt(body, "⏹ إلغاء ترجمة الموبايل") { SubDual.cancel(); close() }
                }
                grp(body, "اللي مع الفيديو")
                opt(body, if (side.isNotBlank()) "💬 ترجم ملف الترجمة المرفق «$side»" else if (nat > 0) "💬 ترجم ترجمة الفيديو الأصلية ($nat مسار جوه الفيديو)" else "💬 ملف الترجمة المرفق مع الفيديو — مفيش") {
                    if (side.isBlank() && nat <= 0) note("مفيش ملف ترجمة مرفق مع الفيديو ده")
                    else { close(); openSfSettings("side", if (side.isNotBlank()) "📄 ملف الترجمة المرفق: «$side»" else "📄 ترجمة الفيديو الأصلية ($nat مسار)") }
                }
                grp(body, "من النت (من غير مفتاح)")
                opt(body, "🌍 جيب ترجمة أونلاين تلقائي (باسم الفيديو/الفولدر)") { go("subnet:auto"); note("⏳ بدأ البحث على التلفزيون…"); close() }
                if (st.optInt("net_more", 0) > 0) opt(body, "⏭ جرّب نتيجة تانية (" + st.optInt("net_more", 0) + " متبقية)") { go("subnet:next"); close() }
                opt(body, "📱 ابحث من الموبايل (لو التلفزيون مفيهوش نت)") { close(); fetchFromPhone() }
                opt(body, "🌐 افتح المتصفح على موقع ترجمة (بحث + sub file)") { close(); sheetSites() }
                opt(body, "🎯 ضبط توقيت الترجمة تلقائي") { go("subalign"); note("🎯 بسمع الفيديو بجيميناي…"); close() }
                grp(body, "من الموبايل")
                opt(body, "📂 اختار ملف ترجمة من الموبايل (SRT / ASS / VTT)") { close(); pickSubFromPhone() }
                if (mob.isNotBlank()) opt(body, "📄 ترجم الملف المستورد «$mob»") { close(); openSfSettings("mobile", "📄 الملف المستورد: «$mob»") }
            }
            var wtSel = ""
            var msLang: String? = null; var msModel: String? = null; var msWav: Boolean? = null; var msDual: Int? = null; var msSwap: Boolean? = null; var msTerm: Boolean? = null   // (v762) قيم مؤقتة لحد ما حالة التلفزيون ترجع
            fun confirmCancelTr() {   // (v759) تأكيد على الموبايل بعدين الأمر للتلفزيون
                android.app.AlertDialog.Builder(act).setTitle("⏹ إلغاء الترجمة؟")
                    .setMessage("هيوقف الترجمة خالص (مش مؤقت). اللي اتترجم لحد دلوقتي هيفضل محفوظ، وتقدر تبدأ تاني بـ «▶ ترجم».")
                    .setPositiveButton("إلغاء الترجمة") { _, _ -> go("tr_cancel") }.setNegativeButton("لأ، كمّل", null).show()
            }
            fun sheetTr() = sheet("🔤 الترجمة") { body, redo, close ->
                if (wtSel.isEmpty()) wtSel = st.optString("wtr", "free")
                body.addView(label(st.optString("tr", "▶ ترجم") + "   ·   " + st.optString("mode", ""), 13f, cDim, false), lp(-1, -2).apply { topMargin = dp(6) })
                opt(body, "📄 ترجمة ملف ترجمة (المرفق مع الفيديو أو من الموبايل)") { close(); sheetSubFile() }
                if (!started && st.optBoolean("tpart", false)) {
                    grp(body, "📼 فيه ترجمة محفوظة للفيديو ده (ناقصة)")
                    opt(body, "▶ كمّل الباقي (بيتخطّى المترجم)") { go("tr_cont"); close() }
                    opt(body, "📍 كمّل من مكان التشغيل الحالي") { go("tr_here"); close() }
                    opt(body, "🔁 ترجم من الأول وجديد (القديمة تتحفظ)") { go("tr_fresh"); close() }
                }
                if (!started) {
                    grp(body, "🔤 ترجمة")
                    opt(body, "☁ جيميناي — اختار ٧٥/٢٥ أو السماع الأدق") { go("tr_gem"); close() }
                    opt(body, "📴 وشبر + ترجمة أوفلاين (HY-MT1.5 · فصحى)") { go("tr_whisper"); close() }
                    grp(body, "✍ تفريغ بس (من غير ترجمة)")
                    opt(body, "☁ بجيميناي (من الصوت)") { go("tr_gtx"); close() }
                    opt(body, "📴 على الجهاز بوشبر") { go("tr_wtx"); close() }
                } else {
                    opt(body, if (paused) "▶ كمّل الترجمة" else "⏸ إيقاف مؤقت") { go(if (paused) "tr_resume" else "tr_pause"); close() }
                    opt(body, "⏹ إلغاء الترجمة (مش مؤقت)") { close(); confirmCancelTr() }
                    opt(body, "🔀 غيّر الوضع (بيفتح على التلفزيون)") { go("tr_wiz"); close() }
                    opt(body, "🌙 كمّل الترجمة في الخلفية") { go("tr_bg"); close() }
                    opt(body, "📋 الجمل") { go("sent"); close() }
                    opt(body, "🧑 الشخصيات") { go("chars"); close() }
                }
            }
            // ───── (v695) وضع عرض الترجمة · نسخ الترجمة ─────
            val dualNames = kotlin.collections.listOf("ترجمة فقط", "ترجمة + الأصلي تحت", "الإنجليزي تحت", "الأصلي فقط", "الأصلي فوق + الترجمة")
            val dualShort = kotlin.collections.listOf("ترجمة\nفقط", "ترجمة +\nالأصلي", "إنجليزي\nتحت", "الأصلي\nفقط", "الأصلي\nفوق")   // (v806) أسماء الأزرار الصغيرة
            fun sheetDual() = sheet("🔀 وضع عرض الترجمة") { body, redo, close ->
                for ((n, nm) in dualNames.withIndex()) opt(body, (if (n == dualSel) "✓ " else "") + nm) { dualSel = n; go("dualset", n.toLong()); redo() }
            }
            // ───── (v759) استوديو الترجمة على الموبايل: كل تعديل بيتبعت للتلفزيون على طول (cfgset) ومعاينة حية هنا ─────
            fun cfgSet(k: String, v: String) { go("cfgset:" + k + "|" + v) }
            fun sheetStudio() = sheet("🎨 استوديو الترجمة") { body, _, _ ->
                val s0 = st
                var pvScale = s0.optInt("scale", 100); var pvBg = s0.optInt("bgopa", 45)
                var pvCol = if (s0.optBoolean("suni_on", false)) (try { Color.parseColor(s0.optString("suni", "#FFFFFF")) } catch (_: Throwable) { Color.WHITE }) else Color.WHITE
                val prev = label("الترجمة بالعربي — معاينة حية", 15f).apply { setPadding(dp(10), dp(12), dp(10), dp(12)) }
                fun paintPv() {
                    prev.setTextColor(pvCol); prev.textSize = 15f * pvScale / 100f
                    prev.background = bg(Color.argb((pvBg * 255 / 100).coerceIn(0, 255), 0, 0, 0), 0x33FFFFFF, 10f)
                }
                paintPv()
                body.addView(prev, lp(-1, -2).apply { topMargin = dp(8) })
                fun seekRow(title: String, mx: Int, init: Int, shown: (Int) -> String, onVal: (Int) -> Unit) {
                    val tv = label(title + ": " + shown(init), 12.5f, cDim, false).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
                    body.addView(tv, lp(-1, -2).apply { topMargin = dp(12) })
                    val sb = android.widget.SeekBar(act).apply {
                        max = mx; progress = init.coerceIn(0, mx); layoutDirection = View.LAYOUT_DIRECTION_LTR
                        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                            var lastAt = 0L
                            override fun onProgressChanged(b: android.widget.SeekBar?, p: Int, u: Boolean) {
                                tv.text = title + ": " + shown(p)
                                if (u) { val n = System.currentTimeMillis(); if (n - lastAt > 120L) { lastAt = n; onVal(p) } }
                            }
                            override fun onStartTrackingTouch(b: android.widget.SeekBar?) { b?.parent?.requestDisallowInterceptTouchEvent(true) }
                            override fun onStopTrackingTouch(b: android.widget.SeekBar?) { if (b != null) onVal(b.progress) }
                        })
                    }
                    body.addView(sb, lp(-1, dp(40)))
                }
                seekRow("📏 حجم الخط", 140, s0.optInt("scale", 100) - 60, { p -> (p + 60).toString() + "%" }) { p -> pvScale = p + 60; paintPv(); cfgSet("sub_scale", (p + 60).toString()) }
                grp(body, "🎨 لون النص")
                val pal = kotlin.collections.listOf("#FFFFFF" to "أبيض", "#FFE600" to "أصفر", "#FF9F1C" to "برتقالي", "#00CFFF" to "سماوي", "#AEE2FF" to "أزرق فاتح", "#FBC9E0" to "وردي", "#CE93D8" to "بنفسجي", "#FFAB91" to "مرجاني")
                val prow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                for ((hex, nm) in pal) prow.addView(btn("", 12f, Color.parseColor(hex), 0x66FFFFFF) { pvCol = Color.parseColor(hex); paintPv(); cfgSet("sub_uni_color", hex); note("🎨 " + nm) }, lp(0, dp(40), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
                body.addView(prow, lp(-1, -2).apply { topMargin = dp(6) })
                opt(body, "🎭 ألوان المتحدثين (بدل لون موحّد)") { pvCol = Color.WHITE; paintPv(); cfgSet("sub_uni_on", "0"); note("🎭 ألوان المتحدثين") }
                seekRow("🌫 عتامة خلفية النص", 100, s0.optInt("bgopa", 45), { p -> p.toString() + "%" }) { p -> pvBg = p; paintPv(); go("bgopa", p.toLong()) }
                seekRow("✨ هالة/حدود حول النص", 20, s0.optInt("sblur", 0), { p -> p.toString() }) { p -> cfgSet("sub_blur", p.toString()) }
                seekRow("↕ موضع الترجمة (فوق / تحت)", 300, s0.optInt("posy", 0) + 60, { p -> (p - 60).toString() }) { p -> cfgSet("sub_posy", (p - 60).toString()) }
                grp(body, "🔤 نوع الخط")
                val fontOpts = SubStyle.fonts.take(10)
                chipRows(body, fontOpts.map { it.label }, 3, { false }) { l -> fontOpts.firstOrNull { it.label == l }?.let { cfgSet("sub_font", it.id); note("🔤 " + l) } }
                grp(body, "نمط الخط")
                chipRows(body, SubStyle.fontStyles.map { it.second }, 2, { false }) { l -> SubStyle.fontStyles.firstOrNull { it.second == l }?.let { cfgSet("sub_fontstyle", it.first); note("🔤 " + l) } }
                grp(body, "💬 وضع عرض الترجمة")
                chipRows(body, dualNames, 2, { l -> dualNames.indexOf(l) == dualSel }) { l -> val n = dualNames.indexOf(l).coerceAtLeast(0); dualSel = n; go("dualset", n.toLong()); note("🔀 " + l) }
            }
            fun applyVer(i: Int, saveFirst: Boolean = true) {
                try {
                    if (saveFirst && LinkSession.mirror.eng.versions.size < 12) LinkSession.send("engine.saveVersion", org.json.JSONObject().put("name", "نسخة قبل التبديل").put("dialect", ""))   // عشان اللي معروض دلوقتي ما يضيعش
                    LinkSession.send("engine.applyVersion", org.json.JSONObject().put("i", i)); appliedVer = i
                } catch (e: Throwable) { LogStore.err("PlayerPad:applyVer", e) }
            }
            fun sheetVerTr(i: Int) = sheet("ترجمة النسخة دي") { body, _, close ->
                infoCard(body, "هتتطبّق النسخة الأول وبعدين الترجمة بتشتغل عليها بالمحرك اللي تختاره (على التلفزيون).")
                opt(body, "🌐 ترجم بجوجل") {
                    close(); applyVer(i)
                    LinkSession.onOp("engine.googleTranslateAll", { r -> act.runOnUiThread { if (!closed) note(r) } }, { n -> act.runOnUiThread { if (!closed) note("✅ ترجمة جوجل خلصت — " + (n.toIntOrNull() ?: 0) + " جملة اتغيّرت") } })
                    LinkSession.send("engine.googleTranslateAll", org.json.JSONObject())
                    note("🌐 بترجم بجوجل…")
                }
                opt(body, "✨ ترجم بجيميناي") { close(); applyVer(i); try { LinkSession.send("engine.redoAll", org.json.JSONObject().put("keep", true)) } catch (_: Throwable) {}; note("✨ بترجم بجيميناي…") }
                opt(body, "👁 اعرضها من غير ترجمة") { close(); applyVer(i); note("👁 اتعرضت النسخة") }
            }
            fun sheetVersions() = sheet("🗂 نسخ الترجمة") { body, redo, close ->
                val vs = try { LinkSession.mirror.eng.versions } catch (_: Throwable) { emptyList<VerRow>() }
                infoCard(body, "كل نسخة ترجمة اتحفظت (سوفت صب · جيميناي · جوجل …). «👁 اعرض» بيطبّقها على الفيديو، و«ترجم» بيطبّقها وبعدين يترجم بالمحرك اللي تختاره.")
                if (vs.isEmpty()) infoCard(body, "مفيش نسخ محفوظة لسه — بتتحفظ لوحدها قبل أي ترجمة أو تبديل (مثلًا «قبل ترجمة جوجل»).", 0xFFFFD9A0.toInt(), 0xFF3A2A14.toInt(), 0xFF8A6A2A.toInt())
                for ((i, v) in vs.withIndex()) {
                    val rowBox = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; background = bg(cCard, if (i == appliedVer) cOnLine else cLine, 14f); setPadding(dp(10), dp(6), dp(6), dp(6)) }
                    val nm = (i + 1).toString() + ". " + v.name + (if (v.dialect.isNotBlank()) " · " + v.dialect else "") + "\n" + v.count + " جملة" + (if (i == appliedVer) "  ✓ المعروضة" else "")
                    rowBox.addView(label(nm, 13f, 0xFFFFFFFF.toInt(), false).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, lp(0, -2, 1f))
                    rowBox.addView(btn("👁 اعرض", 12f) { applyVer(i); note("👁 اتعرضت: " + v.name); redo() }, lp(dp(78), dp(44)).apply { marginStart = dp(6) })
                    rowBox.addView(btn("ترجم", 12f, 0xFF4F5BD5.toInt(), 0xFF7C8CF8.toInt()) { close(); sheetVerTr(i) }, lp(dp(64), dp(44)).apply { marginStart = dp(6) })
                    body.addView(rowBox, lp(-1, -2).apply { topMargin = dp(8) })
                }
                grp(body, "ترجمة جديدة")
                opt(body, "➕ إضافة ترجمة (من الموبايل) وترجمتها") { close(); sheetSubFile() }
            }

            fun sheetAi() = sheet("✨ لهجة وإعدادات الترجمة") { body, redo, close ->
                if (!started) body.addView(label("⚠ ابدأ الترجمة الأول (الزرار الأحمر تحت)", 13f, 0xFFFFB74D.toInt()), lp(-1, -2).apply { topMargin = dp(8) })
                grp(body, "لهجة الترجمة")
                chipRows(body, kotlin.collections.listOf("فصحى", "مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني"), 4,
                    { l -> l == (if (curDialect.isBlank()) "فصحى" else curDialect) }, { l -> if (l == "فصحى") null else "dialect" }) { l ->
                    curDialect = if (l == "فصحى") "" else l; if (started && l != "فصحى") tapFill("dialect"); go("dialect:$l"); redo()
                }
                grp(body, "الأسلوب")
                chipRows(body, kotlin.collections.listOf("حرفي", "شعبي", "جرئ", "+18"), 4, { l -> l == curStyle.ifBlank { "حرفي" } }) { l ->
                    curStyle = if (l == "حرفي") "" else l; go("tone:" + l + "|" + curStrength.ifBlank { "متوسطة" }); redo()
                }
                grp(body, "الشدة (مع شعبي / جرئ / +18)")
                chipRows(body, kotlin.collections.listOf("خفيفة", "متوسطة", "شديدة"), 3, { l -> l == curStrength.ifBlank { "متوسطة" } }) { l ->
                    curStrength = l; go("tone:" + curStyle.ifBlank { "حرفي" } + "|" + l); redo()
                }
                opt(body, "🔁 طبّق الأسلوب من هنا للآخر") { go("tone:" + curStyle.ifBlank { "حرفي" } + "|" + curStrength.ifBlank { "متوسطة" } + "|from"); close() }
                opt(body, "🔁 طبّق الأسلوب على الفيديو كله") { go("tone:" + curStyle.ifBlank { "حرفي" } + "|" + curStrength.ifBlank { "متوسطة" } + "|all"); close() }
                grp(body, "أدوات الذكاء (دوسة واحدة)")
                val tools = mapOf("✍ تنقيح" to "refine", "🔥 لهجة +" to "dial", "🧹 عائلي" to "family", "🔞 صريح" to "explicit", "🔧 ضمائر" to "pron", "🧠 دمج مكرر" to "dedup")
                chipRows(body, tools.keys.toList(), 3, { false }, { l -> tools[l] }) { l -> if (started) tapFill(tools[l] ?: ""); go("ai:" + (tools[l] ?: "")); close() }
                opt(body, "⚙ كل إعدادات الترجمة على التلفزيون") { go("subdlg", 1L); close() }
            }

            // ───── (v762) ⚙ إعدادات الترجمة على الموبايل (كانت بتفتح على شاشة التلفزيون) — كل ضغطة بتتبعت للتلفزيون وبتتطبّق فورًا ─────
            fun sheetSet() = sheet("⚙ إعدادات الترجمة") { body, redo, close ->
                val mk = st.optString("ms_mk", "")
                var lang = msLang ?: st.optString("ms_lang", "")
                var model = msModel ?: st.optString("ms_model", "")
                val wav = msWav ?: st.optBoolean("ms_wav", true)
                val dualL = msDual ?: st.optInt("ms_dual", 1)
                val swapOn = msSwap ?: st.optBoolean("ms_swap", true)
                val termOn = msTerm ?: st.optBoolean("ms_term", false)
                fun mset(c: String) { go("mset:$c") }
                body.addView(label(st.optString("mode", "") + "   ·   بتتطبّق على التلفزيون فورًا", 13f, cDim, false), lp(-1, -2).apply { topMargin = dp(6) })
                if (st.optBoolean("cdial", false)) opt(body, "🌐 اللهجة والأسلوب وأدوات الذكاء  ›") { close(); sheetAi() }
                fun langSec() {
                    grp(body, "🌍 لغة كلام الفيديو (بتتفرّغ بيها)")
                    val ids = Offline.langIds; val lbs = Offline.langLabels
                    chipRows(body, lbs, 3, { l -> ids.getOrNull(lbs.indexOf(l)) == lang }) { l ->
                        val id = ids.getOrNull(lbs.indexOf(l)) ?: ""; lang = id; msLang = id; mset("lang:$id"); redo()
                    }
                }
                fun modelSec() {
                    grp(body, "🎧 موديل وشبر (الأكبر أدق وأبطأ · ⬇ = مش نازل على التلفزيون)")
                    chipRows(body, Offline.models.map { it.label }, 2, { l -> Offline.models.firstOrNull { it.label == l }?.id == model }) { l ->
                        Offline.models.firstOrNull { it.label == l }?.let { m -> model = m.id; msModel = m.id; mset("model:" + m.id); redo() }
                    }
                }
                fun audioSec() {
                    grp(body, "🎧 جودة الصوت المرفوع لجيميناي")
                    chipRows(body, kotlin.collections.listOf("🧠 عادي (WAV)", "💾 توفير داتا (AAC)"), 2, { l -> (l.contains("WAV")) == wav }) { l ->
                        val w = l.contains("WAV"); msWav = w; mset("aud:" + (if (w) "wav" else "aac")); redo()
                    }
                }
                fun dualSec() {
                    grp(body, "🎯 تدقيق السماع بمفتاحين (بيستهلك طلبات أكتر)")
                    val names = kotlin.collections.listOf("⛔ مقفول", "🌍 ذكي", "🔁 دايمًا")
                    chipRows(body, names, 3, { l -> names.indexOf(l) == dualL }) { l -> val n = names.indexOf(l); msDual = n; mset("dual:$n"); redo() }
                }
                fun swapRow() = opt(body, "🔄 بديل تلقائي لو جيميناي وقف:  " + (if (swapOn) "✅ شغّال" else "⏸ مقفول")) { msSwap = !swapOn; mset("swap"); redo() }
                fun termRow() = opt(body, "📖 نبذة المصطلحات الطبية:  " + (if (termOn) "✅ شغّال" else "⏸ مقفول")) { msTerm = !termOn; mset("term"); redo() }
                fun keysNote() = body.addView(label("🔑 مفاتيح جيميناي على التلفزيون: " + st.optInt("ms_keys", 0), 13f, cDim, false), lp(-1, -2).apply { topMargin = dp(10) })
                when (mk) {
                    "gem" -> { audioSec(); dualSec(); keysNote(); swapRow(); termRow() }
                    "fgem" -> { audioSec(); keysNote(); swapRow() }
                    "free" -> { langSec(); modelSec(); termRow() }
                    "txg" -> { audioSec(); keysNote() }
                    else -> { langSec(); modelSec() }
                }
                opt(body, "🖥 باقي الإعدادات (بتفتح على التلفزيون)") { go("modeset"); close() }
            }

            // ── فوق: إغلاق + عنوان الفيديو ──
            val ttlTv = label("", 14f, 0xFFB0BEC5.toInt(), false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            var plusTap: () -> Unit = {}   // (v779) بتتملّي بعد تعريف ورقة ➕ الليستة تحت
            val headRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
            headRow.addView(btn("→ رجوع", 13f, 0xFF2563EB.toInt(), 0xFF5B8DEF.toInt()) { d.dismiss() }, lp(dp(78), dp(44)))   // (v700) زرار رجوع واضح للريموت
            headRow.addView(ttlTv, lp(0, -2, 1f).apply { leftMargin = dp(10) })
            val handoffB = btn("📱", 18f, 0xFF2563EB.toInt(), 0xFF5B8DEF.toInt()) { PlayQueueUi.handoffToPhone(act) { try { d.dismiss() } catch (_: Throwable) {}; try { onLeave?.invoke() } catch (_: Throwable) {} } }.apply { visibility = View.GONE }   // (v694) كمّل الفيديو ده على الموبايل من نفس الثانية
            headRow.addView(handoffB, lp(dp(48), dp(44)).apply { leftMargin = dp(6) })
            headRow.addView(btn("➕", 20f, 0xFF15803D.toInt(), 0xFF4ADE80.toInt()) { plusTap() }, lp(dp(48), dp(44)).apply { leftMargin = dp(6) })   // (v779) ➕ الليستة
            val logTop = btn("📜 لوج", 13f) { sheetLog() }   // (v770) مكان زرار السرعة فوق: لوج التلفزيون (وتشخيص الترجمة 🔬) على الموبايل. السرعة لسه تحت وفي ⚙
            headRow.addView(logTop, lp(dp(66), dp(44)).apply { leftMargin = dp(6) })

            // ── عدّاد الجمل (صوتي أو بصري) — دوسة = لوج الترجمة على التلفزيون ──
            val cntTv = label("—", 28f)
            val modeTv = label("", 12f, 0xFF9FB3D6.toInt(), false)
            val cntCard = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = bg(0xFF18284A.toInt(), 0xFF3B5A99.toInt(), 22f); setPadding(dp(8), dp(12), dp(8), dp(12))
                isClickable = true; setOnClickListener { go("log") }
            }
            cntCard.addView(cntTv, lp(-2, -2)); cntCard.addView(modeTv, lp(-2, -2).apply { topMargin = dp(3) })

            // ── كبسولات زي المشغّل: 👁 بصري · 🌐 النت · 🎧/🎬 وضع يوتيوب ──
            fun cap(t: String, tap: () -> Unit): TextView = btn(t, 12f) { tap() }.apply { setPadding(dp(8), dp(4), dp(8), dp(4)); background = bg(cCard, cLine, 18f) }
            val capVis = cap("👁 0") { go("visdlg") }
            val netLine = label("", 11f, cDim, false).apply { maxLines = 3; setPadding(dp(8), dp(2), dp(8), dp(2)) }   // (v759) بدل كبسولة 🌐 النت: عدّاد استهلاك نت الترجمة (التلفزيون + الموبايل)
            val capMode = cap("🎧") { go("ytmode") }
            val capRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
            capRow.addView(capVis, lp(-2, dp(34)).apply { marginStart = dp(3); marginEnd = dp(3) })
            capRow.addView(capMode, lp(-2, dp(34)).apply { marginStart = dp(3); marginEnd = dp(3) })

            // ── (v695) أزرار الترجمة: إظهار/إخفاء · وضع العرض · النسخ · إضافة ترجمة ──
            val ccTop = btn("💬\nالترجمة", 11.5f) { go("cc") }
            val dualTop = btn("🔀\nالعرض", 11.5f) { sheetDual() }
            val verB = btn("🗂\nالنسخ", 11.5f) { sheetVersions() }
            val addTrB = btn("➕\nإضافة ترجمة", 11.5f, 0xFF4F5BD5.toInt(), 0xFF7C8CF8.toInt()) { sheetSubFile() }
            val subRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            for (b0 in listOf(ccTop, dualTop, verB, addTrB)) { b0.setPadding(dp(2), dp(4), dp(2), dp(4)); subRow.addView(b0, lp(0, if (embedInto != null) dp(48) else dp(62), 1f).apply { marginStart = dp(2); marginEnd = dp(2) }) }

            // ── السرعة · اللهجة وإعدادات الترجمة ──
            val speedB = btn("⏩ 1x", 16f) { sheetSpeed() }
            val aiB = fillable(btn("✨ لهجة وترجمة", 14f) { sheetAi() }, "*")
            val quick = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            quick.addView(aiB, lp(0, dp(52), 1f))   // (v712) السرعة فوق جنب 🕘 — شلت المكرر هنا

            // ── تابات الإعدادات: 🔤 نص · ⏱ تزامن · 🗣 ذكاء · 🧰 أدوات ──
            fun newBody(): LinearLayout = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE }
            fun stepRow(body: LinearLayout, minus: () -> Unit, plus: () -> Unit): TextView {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; background = bg(cCard, cLine, 16f) }
                val v = label("", 13f).apply { maxLines = 1 }
                row.addView(btn("−", 22f) { minus() }, lp(dp(50), dp(46)))
                row.addView(v, lp(0, -2, 1f))
                row.addView(btn("+", 22f) { plus() }, lp(dp(50), dp(46)))
                body.addView(row, lp(-1, -2).apply { topMargin = dp(6) })
                return v
            }
            fun grid(body: LinearLayout, cs: List<TextView>, per: Int, h: Int) {
                var gr: LinearLayout? = null
                cs.forEachIndexed { i, c ->
                    if (i % per == 0) {
                        gr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                        body.addView(gr, lp(-1, -2).apply { topMargin = dp(6) })
                    }
                    gr?.addView(c, lp(0, dp(h), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
                }
                val rem = cs.size % per
                if (rem != 0) for (k in 0 until per - rem) gr?.addView(View(act), lp(0, dp(h), 1f))
            }
            // 🔤 نص
            val bodyText = newBody()
            val hiddenBody = newBody()   // (v795) الصفوف القديمة (+/−) مخفية: التحكم بقى بسلايدرات في الاستوديو
            val fontTv = stepRow(hiddenBody, { go("fontprev") }, { go("font") })
            val sizeTv = stepRow(hiddenBody, { go("scale", -10L) }, { go("scale", 10L) })
            val posTv = stepRow(hiddenBody, { go("posy", -10L) }, { go("posy", 10L) })
            val bgTv = stepRow(hiddenBody, { go("bgopa", (bgNow - 10).coerceIn(0, 100).toLong()) }, { go("bgopa", (bgNow + 10).coerceIn(0, 100).toLong()) })
            bodyText.addView(btn("🎨 استوديو الترجمة", 13f) { sheetStudio() }, lp(-1, dp(46)).apply { topMargin = dp(6) })
            val studioBtn = btn("🎚\nتحكم\nترجمة", 9.5f) { sheetStudio() }   // (v806) الاسم بس — من غير الشرح
            // ⏱ تزامن
            val bodySync = newBody()
            val timeTv = stepRow(bodySync, { go("shift", -250L) }, { go("shift", 250L) })
            bodySync.addView(btn("⟲ تصفير فرق التوقيت", 13f) { go("shift0") }, lp(-1, dp(44)).apply { topMargin = dp(6) })
            bodySync.addView(btn("🎯 ضبط توقيت الترجمة تلقائي", 13f, 0xFF2E7D32.toInt(), 0xFF43A047.toInt()) { go("subalign"); note("🎯 بسمع الفيديو بجيميناي…") }, lp(-1, dp(46)).apply { topMargin = dp(6) })
            val abTv = label("حلقة A-B: مش متحددة", 12f, cDim, false)
            bodySync.addView(abTv, lp(-1, -2).apply { topMargin = dp(10) })
            grid(bodySync, kotlin.collections.listOf(btn("🅰 نقطة أ", 12.5f) { go("ab_a") }, btn("🅱 نقطة ب", 12.5f) { go("ab_b") }, btn("✖ إلغاء", 12.5f) { go("ab_x") }), 3, 46)
            bodySync.addView(btn("⏱ شريط التزامن السريع", 13f) { go("syncstrip") }, lp(-1, dp(44)).apply { topMargin = dp(8) })
            // 🗣 ذكاء
            val bodyAi = newBody()
            val aiBtnSettings = fillable(btn("🗣 لهجة وأسلوب وشدة…", 13f) { sheetAi() }, "*")   // (v712) بقى مش في الصفحة (مكرر مع ✨ لهجة وترجمة) — بيفضل متعرّف عشان render
            bodyAi.addView(label("أدوات الذكاء (دوسة واحدة)", 12f, cDim).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, lp(-1, -2).apply { topMargin = dp(10) })
            val aiTools = kotlin.collections.listOf("✍ تنقيح" to "refine", "🔥 لهجة +" to "dial", "🧹 عائلي" to "family", "🔞 صريح" to "explicit", "🔧 ضمائر" to "pron", "🧠 دمج مكرر" to "dedup")
            grid(bodyAi, aiTools.map { pr -> fillable(btn(pr.first, 12.5f) { if (started) tapFill(pr.second); go("ai:" + pr.second) }, pr.second) }, 2, 46)
            // 🧰 أدوات
            val bodyTools = newBody()
            val ccB = btn("", 12f) { go("cc") }
            val fitB = btn("", 12f) { go("fit") }
            val rotB = btn("🔄\nتدوير الصورة", 12f) { go("rotate") }
            val dualB = btn("", 12f) { go("dual") }
            val loopB = btn("", 12f) { go("loop") }
            val visB = btn("👁\nلقطة بصرية", 12f) { go("vsnap") }
            val sentB = btn("📝\nالجمل", 12f) { go("sent") }
            val charsB = btn("🧑\nالشخصيات", 12f) { go("chars") }
            val subsB = btn("🔤\nمسارات الترجمة", 12f) { go("subs") }
            val qB = btn("🎞\nالجودة", 12f) { if (qList.size > 1) sheetList("🎞 الجودة", qList, qCur, "qset") else if (st.optBoolean("quality", false)) go("quality") else note("مفيش جودات للفيديو ده") }
            val aB = btn("🔊\nلغة الصوت", 12f) { if (aList.size > 1) sheetList("🔊 لغة الصوت", aList, aCur, "aset") else if (st.optBoolean("audio", false)) go("audio") else note("مفيش مسارات صوت تانية") }
            val lockB = btn("", 12f) { go("lock") }
            val notB = btn("🔔\nالإشعارات", 12f) { go("notice") }
            val menuB = btn("☰\nقايمة التلفزيون", 12f) { go("menu") }
            val toolsB = btn("🧰\nأدوات الفيديو", 12f) { go("tools") }
            val moreB = btn("⋯\nالمزيد", 12f) { go("more") }
            val killB = btn("⏹\nإغلاق الفيديو", 12f, 0xFF3B1A22.toInt(), 0xFF7A2B3A.toInt()) {
                android.app.AlertDialog.Builder(act).setMessage("أقفل الفيديو على التلفزيون؟").setPositiveButton("اقفل") { _, _ -> go("close") }.setNegativeButton("لأ", null).show()
            }
                        val pipB = btn("⧉\nنافذة صغيرة", 12f) { go("pip") }   // (v712) كانت ناقصة من الريموت
            val voiceB = btn("🎙\nفصل صوت الكلام", 12f) { go("voice") }
            val bgB = btn("◼\nخلفية الترجمة", 12f) { go("bgon") }
            val logB = btn("📜\nاللوج", 12f) { go("log") }
            val nextB = btn("📃\nالفيديوهات الجاية", 12f) { go("nextlist") }
            val queueB = btn("📋\nالليستة", 12f) { PlayQueueUi.show(act) }
            // (v797) في الريموت الموحّد: شلت اللي مكرر في الصفحة الرئيسية (العرض ⛶ · التدوير · التكرار · اللقطة البصرية · الجودة 🎞 · الليستة 📋)
            val toolsAll = kotlin.collections.listOf(fitB, rotB, loopB, visB, sentB, charsB, subsB, qB, aB, pipB, voiceB, bgB, nextB, queueB, lockB, notB, menuB, toolsB, moreB, killB)
            val toolsDup = kotlin.collections.setOf<View>(fitB, rotB, loopB, visB, qB, queueB)
            grid(bodyTools, if (embedInto != null) toolsAll.filter { it !in toolsDup } else toolsAll, 2, 60)
            val bodies = if (embedInto != null) kotlin.collections.listOf(bodySync, bodyAi, bodyTools) else kotlin.collections.listOf(bodyText, bodySync, bodyAi, bodyTools)   // (v797) «نص» كان فيه زرار واحد مكرر مع «تحكم الترجمة»
            val tabNames = if (embedInto != null) kotlin.collections.listOf("⏱ تزامن", "🗣 ذكاء", "🧰 أدوات") else kotlin.collections.listOf("🔤 نص", "⏱ تزامن", "🗣 ذكاء", "🧰 أدوات")
            val tabBtns = ArrayList<TextView>()
            var curTab = 0
            fun selectTab(i: Int) {
                curTab = i
                bodies.forEachIndexed { k, b -> b.visibility = if (k == i) View.VISIBLE else View.GONE }
                tabBtns.forEachIndexed { k, b -> b.background = bg(if (k == i) cOn else cCard, if (k == i) cOnLine else cLine, 12f) }
            }
            val tabBar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            tabNames.forEachIndexed { k, n ->
                val tb = btn(n, 11.5f) { selectTab(k) }.apply { setPadding(dp(2), dp(6), dp(2), dp(6)) }
                tabBtns.add(tb); tabBar.addView(tb, lp(0, if (embedInto != null) dp(34) else dp(38), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            }
            val holder = FrameLayout(act)
            bodies.forEach { holder.addView(it, FrameLayout.LayoutParams(-1, -2)) }
            val sc = ScrollView(act).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(holder, FrameLayout.LayoutParams(-1, -2)) }
            selectTab(0)

            // ── (v691) مفيش فيديو شغّال: بدل رسالة «مفيش فيديو شغّال» ثلاث أزرار — المكتبة (تلفزيون / موبايل) · يوتيوب · المتصفح ──
            fun leave() { try { d.dismiss() } catch (_: Throwable) {}; try { onLeave?.invoke() } catch (_: Throwable) {} }
            fun openLib(tv: Boolean) {
                try { RemoteClient.init(act) } catch (_: Throwable) {}
                if (tv && RemoteClient.lastTv() == null) { note("📺 مفيش تلفزيون متصل"); return }
                try {
                    act.startActivity(android.content.Intent(act, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("tab", "videos").putExtra(if (tv) "lib_tv" else "lib_phone", true))
                    leave()
                } catch (e: Throwable) { LogStore.err("PlayerPad:lib", e) }
            }
            fun sheetLib() = sheet("📚 المكتبة") { body, _, close ->
                body.addView(label("اختار تفتح المكتبة على أنهي شاشة", 13f, cDim, false), lp(-1, -2).apply { topMargin = dp(4) })
                val pr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                pr.addView(btn("📺\nشاشة التلفزيون", 15f, cOn, cOnLine) { close(); openLib(true) }, lp(0, dp(96), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                pr.addView(btn("📱\nشاشة الموبايل", 15f) { close(); openLib(false) }, lp(0, dp(96), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                body.addView(pr, lp(-1, -2).apply { topMargin = dp(12) })
            }
            val idleH = if (embedInto != null) dp(50) else dp(104)   // (v787) في الصفحة الموحّدة الكروت أصغر
            fun idleBtn(icon: String, t: String, fill: Int, line: Int, tap: () -> Unit): TextView =
                btn(icon + "\n" + t, if (embedInto != null) 10f else 12.5f, fill, line) { tap() }.apply { if (embedInto != null) setPadding(dp(1), dp(4), dp(1), dp(4)) else setPadding(dp(2), dp(10), dp(2), dp(10)) }
            val idleRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            idleRow.addView(idleBtn("📚", "المكتبة", 0xFF2563EB.toInt(), 0xFF5B8DEF.toInt()) { sheetLib() }, lp(0, idleH, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            idleRow.addView(idleBtn("▶", "يوتيوب", 0xFFD93A36.toInt(), 0xFFFF6F69.toInt()) { ControllerPad.openOnPhone(act, true); leave() }, lp(0, idleH, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            idleRow.addView(idleBtn("🌐", "المتصفح", 0xFF0B8BC4.toInt(), 0xFF38BDF8.toInt()) { ControllerPad.openOnPhone(act, false); leave() }, lp(0, idleH, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            idleRow.addView(idleBtn("🕘", "السجل", 0xFF6D4BB8.toInt(), 0xFF9B7BE6.toInt()) { RecentPicker.show(act) }, lp(0, idleH, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })   // (v692) آخر اللي اتشاف على التلفزيون والموبايل مع بعض
            val idleNote = label("", 12f, cDim, false)
            val idleBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            idleBox.addView(idleRow, lp(-1, -2)); idleBox.addView(idleNote, lp(-1, -2).apply { topMargin = dp(6) })

            // ── (v779) ➕ الليستة: ورقة فيها 3 حاجات — الليستة نفسها · إضافة الفيديو الشغّال · إضافة فيديوهات من (موبايل / تلفزيون / يوتيوب / متصفح) ──
            fun queueCurOnTv() {
                val ttl = st.optString("title", "")
                Thread {
                    var msg = ""
                    try {
                        RemoteClient.init(act)
                        if (!RemoteClient.ensureConnected(act, 8000L)) msg = "⚠ مش متصل بالتلفزيون"
                        else {
                            val (c, _) = RemoteClient.postJson("/queue", org.json.JSONObject().put("a", "addcur"))
                            msg = when (c) {
                                200 -> "➕ اتضاف للليستة" + (if (ttl.isNotBlank()) " — " + ttl.take(40) else "")
                                404 -> "⚠ مفيش فيديو شغّال على التلفزيون"
                                409 -> "⚠ الفيديو ده مينفعش يتضاف للليستة"
                                else -> "⚠ فشل الإضافة (كود $c)"
                            }
                        }
                    } catch (e: Throwable) { LogStore.err("PlayerPad:addcur", e); msg = "⚠ حصلت مشكلة" }
                    try { act.runOnUiThread { note(msg) } } catch (_: Throwable) {}
                }.also { it.isDaemon = true }.start()
            }
            fun pickPhoneForList() {   // بيفضل يفتح بعد كل اختيار عشان تضيف كذا فيديو ورا بعض (رجوع = خلاص)
                VideoPicker.show(act, "➕ اختار فيديو من الموبايل للليستة", { v ->
                    PlayQueueUi.add(act, v.uri, v.title, true)
                    pickPhoneForList()
                }, { note("الفيديوهات بتظهر هنا من مكتبة الموبايل") })
            }
            fun sheetPlus() = sheet("➕ الليستة") { body, _, close ->
                body.addView(btn("📋  الليستة (اعرضها · رتّبها · شغّل منها)", 15f, cOn, cOnLine) { close(); PlayQueueUi.show(act) }, lp(-1, dp(54)).apply { topMargin = dp(6) })
                body.addView(btn("➕  إضافة الفيديو الشغّال للليستة", 15f) { close(); queueCurOnTv() }, lp(-1, dp(54)).apply { topMargin = dp(8) })
                body.addView(label("➕ إضافة فيديوهات من:", 13f, cDim, false), lp(-1, -2).apply { topMargin = dp(14) })
                fun rowOf(a: TextView, b: TextView) = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_RTL
                    addView(a, lp(0, dp(78), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                    addView(b, lp(0, dp(78), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                }
                body.addView(rowOf(
                    btn("📱\nالموبايل", 15f) { close(); pickPhoneForList() },
                    btn("📺\nالتلفزيون", 15f) { close(); openLib(true) }), lp(-1, -2).apply { topMargin = dp(8) })
                body.addView(rowOf(
                    btn("▶\nيوتيوب", 15f, 0xFFD93A36.toInt(), 0xFFFF6F69.toInt()) { close(); ControllerPad.openOnPhone(act, true); leave() },
                    btn("🌐\nالمتصفح", 15f, 0xFF0B8BC4.toInt(), 0xFF38BDF8.toInt()) { close(); ControllerPad.openOnPhone(act, false); leave() }), lp(-1, -2).apply { topMargin = dp(8) })
                body.addView(label("في يوتيوب والمتصفح والمكتبة هتلاقي «➕ إضافة للليستة» على كل فيديو.", 12f, cDim, false), lp(-1, -2).apply { topMargin = dp(10) })
            }
            plusTap = { sheetPlus() }

            // ── العمود الشمال: عدّاد + كبسولات + سرعة/لهجة + تابات ──
            // ── (v787) أزرار الصفحة الموحّدة: سطر معلومة + صف (تدوير · تكرار واحد · تكرار الكل · قائمة التشغيل) + ⛶ العرض ──
            val infoLine = label("", 11.5f, cDim, false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val rotTop = btn("🔄\nتدوير الفيديو", 11f) { go("rotate") }
            val repCyc = btn("➡\nبدون تكرار", 11f) { go("loop") }   // (v797) زرار واحد بدل «تكرار واحد» + «تكرار الكل»: بدون ← نفس الفيديو ← كل الفولدر
            val plTop = btn("📋\nقائمة التشغيل", 11f) { plusTap() }

            // ── (v804) ⬇ تحميل الفيديو الشغّال: ملف على التلفزيون ← الموبايل · لينك/يوتيوب ← جودات بحجمها ← (موبايل / تلفزيون) ──
            fun dlHdr(j: JSONObject?): HashMap<String, String> { val m = HashMap<String, String>(); if (j != null) for (k in j.keys()) m[k] = j.optString(k); return m }
            fun isOwnHost(u: String): Boolean {
                try {
                    val host = java.net.URI(u).host ?: return false
                    val nis = java.net.NetworkInterface.getNetworkInterfaces() ?: return false
                    for (ni in java.util.Collections.list(nis)) for (a in java.util.Collections.list(ni.inetAddresses)) if (a.hostAddress == host) return true
                } catch (_: Throwable) {}
                return false
            }
            fun dlSize(u: String, a: String?, hs: Map<String, String>): Long {
                val x = Downloader.sizeOf(u, hs)
                if (x <= 0L) return -1L
                return if (a.isNullOrEmpty()) x else x + maxOf(0L, Downloader.sizeOf(a, hs))
            }
            fun sizeTxt(n: Long, approx: Boolean) = if (n > 0L) (if (approx) "≈ " else "") + Sniff.fmtSize(n) else "مش معروف"
            fun dlOnPhone(name: String, pk: DlPick, hs: HashMap<String, String>) {
                val app = act.applicationContext
                val job = Downloader.Job(name, pk.url, pk.audio, pk.hls, pk.h, hs)
                TaskCenter.add(app, "download", "⬇ " + job.name) { t ->
                    job.ctl.cancelled = false; job.ctl.paused = false
                    job.ctl.ext = { t.cancelled }
                    t.onCancel = { job.ctl.cancelled = true }
                    t.onPause = { p -> job.ctl.paused = p }
                    val res = Downloader.run(app, job) { what, pct -> t.pct = pct.coerceIn(0, 99); t.msg = what; TaskCenter.changed() }
                    if (!res.first) throw java.io.IOException(res.second)
                    t.msg = ""; t.outPath = res.second
                }
                note("⬇ اتضاف للتحميل على الموبايل — تابعه من «المهام»")
            }
            fun dlOnTv(name: String, pk: DlPick, hs: HashMap<String, String>) {
                val args = JSONObject().put("name", name).put("url", pk.url).put("audio", pk.audio ?: "").put("hls", pk.hls).put("h", pk.h).put("hdr", JSONObject(hs as Map<*, *>))
                LinkSession.send("dl.start", args) { ok, r ->
                    val started = r.optJSONObject("res")?.optBoolean("started", false) ?: false
                    note(if (ok && started) "⬇ التحميل بدأ على التلفزيون" else if (r.optString("err") == "readonly") "⚠ ريموت تاني ماسك التحكم" else "⚠ التلفزيون ما قدرش يبدأ التحميل")
                }
            }
            /** الخطوة الأخيرة: الحجم + «تحمّله فين؟» */
            fun sheetDlWhere(name: String, pk: DlPick, hs: HashMap<String, String>, own: Boolean) = sheet("⬇ تحميل") { body, _, close ->
                body.addView(label(name, 13f, cDim, false).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(-1, -2).apply { topMargin = dp(4) })
                val szTv = label("📦 الحجم: " + (if (pk.size > 0L) sizeTxt(pk.size, pk.approx) else "⏳ بحسبه…"), 16f, 0xFFFDE68A.toInt(), true)
                body.addView(szTv.apply { background = bg(0xFF3A2A12.toInt(), 0xFFF59E0B.toInt(), 14f); setPadding(dp(12), dp(12), dp(12), dp(12)) }, lp(-1, -2).apply { topMargin = dp(10) })
                if (pk.size <= 0L) Thread {
                    val n = try { dlSize(pk.url, pk.audio, hs) } catch (_: Throwable) { -1L }
                    try { act.runOnUiThread { szTv.text = "📦 الحجم: " + sizeTxt(n, false) } } catch (_: Throwable) {}
                }.also { it.isDaemon = true }.start()
                grp(body, "تحمّله فين؟")
                if (!own) opt(body, "📱  على الموبايل") { close(); dlOnPhone(name, pk, hs) }
                opt(body, "📺  على التلفزيون") { close(); dlOnTv(name, pk, hs) }
                if (own) body.addView(label("الفيديو ده أصلًا جاي من الموبايل — فالتحميل متاح للتلفزيون بس.", 12f, cDim, false), lp(-1, -2).apply { topMargin = dp(8) })
            }
            fun sheetDlNet(info: JSONObject) {
                val name = info.optString("name", "video").ifBlank { "video" }
                val hs = dlHdr(info.optJSONObject("hdr")); val dur = info.optLong("dur", 0L)
                val picks = ArrayList<DlPick>()
                val ql = info.optJSONArray("qlist")
                if (ql != null && ql.length() > 0) {
                    for (i in 0 until ql.length()) { val o = ql.getJSONObject(i); picks.add(DlPick(o.optString("l").ifEmpty { o.optInt("h").toString() + "p" }, o.optString("u"), o.optString("a").ifEmpty { null }, false, o.optInt("h"), -1L, false)) }
                } else {
                    val u = info.optString("url"); val au = info.optString("aurl").ifEmpty { null }
                    if (info.optBoolean("hls", false)) {
                        val hq = info.optJSONArray("hlsq")
                        if (hq == null || hq.length() == 0) picks.add(DlPick("أعلى جودة متاحة", u, null, true, 0, -1L, false))
                        else {
                            val l = ArrayList<JSONObject>(); for (i in 0 until hq.length()) l.add(hq.getJSONObject(i))
                            for (o in l.sortedByDescending { it.optInt("h") }) { val br = o.optLong("br"); picks.add(DlPick(o.optInt("h").toString() + "p", u, null, true, o.optInt("h"), if (br > 0L && dur > 0L) br / 8L * (dur / 1000L) else -1L, true)) }
                        }
                    } else {
                        val ht = info.optInt("ht", 0)
                        picks.add(DlPick("الجودة الحالية" + (if (ht > 0) " (${ht}p)" else ""), u, au, false, ht, -1L, false))
                    }
                }
                if (picks.isEmpty() || picks[0].url.isBlank()) { note("⚠ مفيش لينك قابل للتحميل للفيديو ده"); return }
                val own = isOwnHost(picks[0].url)
                if (picks.size == 1) { sheetDlWhere(name, picks[0], hs, own); return }
                sheet("🎞 اختار الجودة") { body, _, close ->
                    body.addView(label("الحجم بيظهر جنب كل جودة", 12f, cDim, false), lp(-1, -2).apply { topMargin = dp(4) })
                    for (pk in picks) {
                        val b = btn(pk.label + (if (pk.size > 0L) "   ·   " + sizeTxt(pk.size, pk.approx) else "   ·   ⏳"), 15f) { close(); sheetDlWhere(name, pk, hs, own) }
                        body.addView(b, lp(-1, dp(52)).apply { topMargin = dp(8) })
                        if (pk.size <= 0L) Thread {
                            val n = try { dlSize(pk.url, pk.audio, hs) } catch (_: Throwable) { -1L }
                            try { act.runOnUiThread { b.text = pk.label + "   ·   " + sizeTxt(n, false) } } catch (_: Throwable) {}
                        }.also { it.isDaemon = true }.start()
                    }
                }
            }
            fun sheetDlLocal(info: JSONObject) {
                val fn = info.optString("file", "video.mp4"); val sz = info.optLong("size", -1L)
                sheet("⬇ تحميل") { body, _, close ->
                    body.addView(label(fn, 13f, cDim, false).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(-1, -2).apply { topMargin = dp(4) })
                    body.addView(label("📦 الحجم: " + sizeTxt(sz, false), 16f, 0xFFFDE68A.toInt(), true).apply { background = bg(0xFF3A2A12.toInt(), 0xFFF59E0B.toInt(), 14f); setPadding(dp(12), dp(12), dp(12), dp(12)) }, lp(-1, -2).apply { topMargin = dp(10) })
                    infoCard(body, "الفيديو ملف على التلفزيون — هيتحمّل على الموبايل.")
                    opt(body, "📱  حمّل على الموبايل") {
                        close()
                        val tv = RemoteClient.current
                        if (tv == null) { note("📺 مفيش تلفزيون متصل"); return@opt }
                        val app = act.applicationContext
                        val url = "http://" + tv.ip + ":" + tv.port + "/file/cur"
                        val hd = mapOf("X-Token" to tv.token, "X-Ver" to LinkVer.code(app).toString())
                        val nm = Xfer.safeName(fn)
                        TaskCenter.add(app, "dm", "📥 $nm") { t -> XNet.download(app, t, url, hd, nm, sz, XCat.VIDEO) }
                        note("📥 اتضاف للتحميل على الموبايل — تابعه من «المهام»")
                    }
                }
            }
            fun startDownloadFlow() {
                if (RemoteClient.lastTv() == null) { note("📺 مفيش تلفزيون متصل"); return }
                note("⏳ بجيب بيانات الفيديو…")
                LinkSession.send("dl.info") { ok, r ->
                    val info = r.optJSONObject("res")?.optJSONObject("info")
                    when {
                        !ok && r.optString("err") == "noplayer" -> note("📺 مفيش فيديو شغّال في مشغّل التلفزيون")
                        !ok -> note("⚠ ما قدرتش أوصل للتلفزيون (" + r.optString("err") + ")")
                        info == null -> note("التلفزيون محتاج تحديث للنسخة دي")
                        info.optString("kind") == "local" -> sheetDlLocal(info)
                        info.optString("kind") == "net" -> sheetDlNet(info)
                        else -> note("الفيديو ده مش قابل للتحميل")
                    }
                }
            }
            val dlTop = btn("⬇\nتحميل", 11f) { startDownloadFlow() }
            val fitTop = btn("⛶", 20f) { go("fit") }
            var dimExtra: List<View> = kotlin.collections.emptyList()
            val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            left.addView(idleBox, lp(-1, -2))
            left.addView(cntCard, lp(-1, -2))
            left.addView(capRow, lp(-1, -2).apply { topMargin = dp(8) })
            left.addView(netLine, lp(-1, -2).apply { topMargin = dp(6) })
            left.addView(subRow, lp(-1, -2).apply { topMargin = dp(8) })   // (v695)
            left.addView(quick, lp(-1, -2).apply { topMargin = dp(8) })
            left.addView(tabBar, lp(-1, -2).apply { topMargin = dp(8) })
            left.addView(sc, lp(-1, 0, 1f).apply { topMargin = dp(4) })

            // ── الصوت: سلايدر رأسي طولي = 60% من طول الصفحة، على الجنب + كتم ──
            var volTouchAt = 0L; var muteBack = -1
            fun liveNow() = LinkSession.mirror.live && LinkSession.mirror.gotFirst
            /** (v712) فيديو شغّال على التلفزيون ← نفس مسار إشعار القفل (volume.set: بيراعي الصوت الثابت والتضخيم)؛ غير كده صوت النظام */
            fun sendVol(p: Int) { volTouchAt = System.currentTimeMillis(); if (liveNow()) TvVolume.setTo(p) else RemoteClient.setVolume(p) }
            val vol = VertVolume(act) { p -> sendVol(p) }
            val muteB = btn("🔇", 20f) {
                if (liveNow()) {
                    val c = vol.value
                    if (c > 0) { muteBack = c; vol.setValue(0); sendVol(0) } else { val b = if (muteBack > 0) muteBack else 50; vol.setValue(b); sendVol(b) }
                } else RemoteClient.cmd("mute")
            }
            val volH = minOf((dm.heightPixels * 0.60f).toInt(), dm.heightPixels - dp(330)).coerceAtLeast(dp(180))
            val volCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL }
            val loopTop = btn("➡", 20f) { go("loop") }   // (v781) تكرار: عادي ← نفس الفيديو 🔂 ← كل الفولدر 🔁 (بدل زرار التدوير اللي كان هنا في v780)
            volCol.addView(loopTop, lp(-1, dp(46)))
            volCol.addView(muteB, lp(-1, dp(46)).apply { topMargin = dp(8) })
            volCol.addView(vol, lp(-1, volH - dp(108)).apply { topMargin = dp(8) })
            vol.visibility = View.GONE; muteB.visibility = View.GONE   // (v782) الصوت بقى في الصفحة الرئيسية للريموت (مفيش تكرار)
            val mid = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            mid.addView(left, lp(0, -1, 1f)); mid.addView(volCol, lp(dp(76), -1).apply { leftMargin = dp(12) })

            // ── صف الترجمة (فوق أزرار التحكم): [▶ ترجم / نسبة] ▮▮ ⚙ 🔁 🌐 ──
            val trB = btn("▶ ترجم ▾", 16f, 0xFFE53935.toInt(), 0xFFFF6F69.toInt()) { sheetTr() }
            val pzB = btn("▮▮", 17f) { go(if (paused) "tr_resume" else "tr_pause") }
            val cxB = btn("⏹", 17f, 0xFF3B1A22.toInt(), 0xFF7A2B3A.toInt()) { confirmCancelTr() }   // (v759) إلغاء الترجمة خالص (مش مؤقت)
            val wzB = btn("⚙", 18f) { sheetSet() }   // (v762) بتفتح على الموبايل مش التلفزيون
            val rdB = btn("🔁", 18f, 0xFF6A1B9A.toInt(), 0xFF8E3FC4.toInt()) { go("redo") }
            // (v765) 🎞 الجودة جنب ⚙: بتفتح قايمة الجودات (يوتيوب / أي فيديو ليه جودات)؛ لو مفيش جودة بتفضل باهتة ومش شغّالة
            val qkB = btn("🎞", 18f) { if (qList.size > 1) sheetList("🎞 الجودة", qList, qCur, "qset") else if (st.optBoolean("quality", false)) go("quality") else note("مفيش جودات للفيديو ده") }
            val lgB = fillable(btn("🌐", 18f) { sheetAi() }, "*")
            val trRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
            trRow.addView(trB, lp(0, if (embedInto != null) dp(46) else dp(52), 2.6f))
            for (b in kotlin.collections.listOf(pzB, cxB, wzB, qkB, rdB)) trRow.addView(b, lp(0, if (embedInto != null) dp(46) else dp(52), 1f).apply { marginStart = dp(6) })

            // ── أزرار التحكم ──
            val playB = btn("▶", 28f, 0xFF2563EB.toInt(), 0xFF3B82F6.toInt()) { go("toggle") }
            val tr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
            tr.addView(btn("⏮", 22f) { go("prev") }, lp(0, if (embedInto != null) dp(50) else dp(58), 1f).apply { marginEnd = dp(6) })
            tr.addView(btn("⏪ 10", 14f) { go("seek", -10000L) }, lp(0, if (embedInto != null) dp(50) else dp(58), 1f).apply { marginEnd = dp(6) })
            tr.addView(playB, lp(0, if (embedInto != null) dp(58) else dp(70), 1.3f).apply { marginEnd = dp(6) })
            tr.addView(btn("10 ⏩", 14f) { go("seek", 10000L) }, lp(0, if (embedInto != null) dp(50) else dp(58), 1f).apply { marginEnd = dp(6) })
            tr.addView(btn("⏭", 22f) { go("next") }, lp(0, if (embedInto != null) dp(50) else dp(58), 1f).apply { marginEnd = dp(6) })
            val speedBot = btn("1x", 14f) { sheetSpeed() }   // (v766) السرعة تحت جنب السابق/التالي
            tr.addView(speedBot, lp(0, if (embedInto != null) dp(50) else dp(58), 1f))

            // ── شريط التقدم تحت خالص: نفس DualProgress بتاع المشغّل (أخضر = مترجم · كهرماني = لسه هيتغيّر · أزرق = تنقيح) ──
            val bar = DualProgress(act, th).apply { mini = true; big = embedInto != null }   // (v796)
            // (v799) الوقتين اتفصلوا بوضوح: «الآن» (اللحظة الحالية) شمال بالأزرق · «المدة» (طول الفيديو) يمين بالكهرماني — كل واحد بكبسولة وعنوان
            fun nowTxt(ms: Long) = "▶ الآن  " + tm(ms)
            fun durTxt(ms: Long) = "⏱ المدة  " + tm(ms)
            val tPos = label(nowTxt(0L), 13f, 0xFFBFDBFE.toInt(), true).apply { setPadding(dp(10), dp(3), dp(10), dp(3)); background = bg(0xFF12305A.toInt(), 0xFF3B82F6.toInt(), 12f) }
            val tDur = label(durTxt(0L), 13f, 0xFFFDE68A.toInt(), true).apply { setPadding(dp(10), dp(3), dp(10), dp(3)); background = bg(0xFF3A2A12.toInt(), 0xFFF59E0B.toInt(), 12f) }
            bar.onScrub = { f -> tPos.text = nowTxt((f * durNow).toLong()) }
            bar.onSeek = { f -> val ms = (f * durNow).toLong(); holdUntil = System.currentTimeMillis() + 1300L; tPos.text = nowTxt(ms); go("seekto", ms) }
            val times = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            times.addView(tPos, lp(-2, -2)); times.addView(View(act), lp(0, 1, 1f)); times.addView(tDur, lp(-2, -2))

            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(dp(16), dp(26), dp(16), dp(12)) }
            if (embedInto == null) {
                col.addView(headRow, lp(-1, dp(48)))
                col.addView(mid, lp(-1, 0, 1f).apply { topMargin = dp(6) })
                col.addView(trRow, lp(-1, dp(52)).apply { topMargin = dp(8) })
                col.addView(tr, lp(-1, dp(72)).apply { topMargin = dp(8) })
                col.addView(bar, lp(-1, -2).apply { topMargin = dp(8) })
                col.addView(times, lp(-1, -2))
                root.addView(col, FrameLayout.LayoutParams(-1, -1))
            } else {
                // (v787) الريموت هو اللي بيرصّ الأجزاء دي في صفحته الواحدة: نفكّها من أماكنها هنا الأول
                idleBox.removeView(idleRow); left.removeView(subRow); left.removeView(tabBar); left.removeView(sc)
                quick.removeView(aiB); bodyAi.addView(aiB, 0, lp(-1, dp(46)))   // ✨ لهجة وترجمة جوه تاب «ذكاء»
                headRow.removeView(logTop)
                for (i0 in 0 until subRow.childCount) { (subRow.getChildAt(i0) as? TextView)?.textSize = 9.5f }
                addTrB.text = "➕\nإضافة\nترجمة"   // (v806) 6 أزرار في صف واحد: الكلام على سطور عشان ما يتاكلش
                trRow.addView(fitTop, lp(0, dp(46), 1f).apply { marginStart = dp(6) })
                bodyTools.addView(btn("📱  كمّل الفيديو على الموبايل", 13f) { PlayQueueUi.handoffToPhone(act) { leave() } }, lp(-1, dp(46)).apply { topMargin = dp(6) })
                val repeatRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutDirection = View.LAYOUT_DIRECTION_LTR }
                val rb = kotlin.collections.listOf(rotTop, repCyc, plTop, dlTop)
                for (i0 in rb.indices) repeatRow.addView(rb[i0], lp(0, dp(48), 1f).apply { if (i0 > 0) marginStart = dp(6) })
                dimExtra = kotlin.collections.listOf(subRow, tabBar, sc, repeatRow)
                embedInto(PadParts(idleRow, subRow, tabBar, sc, trRow, tr, bar, times, logTop, repeatRow, infoLine, studioBtn) { t, sp, f, st0, tp -> btn(t, sp, f, st0) { tp() } })
            }

            // ── رسم الحالة (من LinkSession.mirror) ──
            fun render() {
                if (closed) return
                val m = LinkSession.mirror
                val s = m.st
                val live = m.live && m.gotFirst
                val busy = sheetsOpen > 0
                if (!busy) st = s
                val now = System.currentTimeMillis()
                val dur = s.optLong("dur", 0L); durNow = dur
                // (v709) تأثير الكوباية: التلفزيون بيبعت tool (مفتاح الأداة) + toolp (0..100)؛ الزرار بيتملّى لحد النهاية وبعدين ينزل
                run {
                    val tk = s.optString("tool", ""); val tp = s.optInt("toolp", 0)
                    if (tk.isNotEmpty()) { if (toolKey != tk) { toolKey = tk; toolSince = now }; toolPct = tp; tapKey = "" }
                    else if (toolKey.isNotEmpty()) { doneKey = toolKey; doneAt = now; toolKey = "" }
                    fills.removeAll { !it.v.isAttachedToWindow && now - it.at > 3000L }
                    for (f in fills) {
                        val tgt = when {
                            toolKey.isNotEmpty() && (f.key == "*" || f.key == toolKey) -> maxOf(toolPct / 100f, 0.9f * (1f - Math.exp(-(now - toolSince) / 20000.0).toFloat())).coerceIn(0.04f, 0.97f)
                            doneKey.isNotEmpty() && now - doneAt < 800L && (f.key == "*" || f.key == doneKey) -> 1f
                            tapKey.isNotEmpty() && now - tapAt < 6000L && (f.key == "*" || f.key == tapKey) -> 0.05f + 0.2f * ((now - tapAt) / 6000f)
                            else -> 0f
                        }
                        f.cur += (tgt - f.cur) * 0.35f
                        if (Math.abs(tgt - f.cur) < 0.005f) f.cur = tgt
                        f.clip.level = (f.cur * 10000f).toInt().coerceIn(0, 10000)
                    }
                    aiB.text = if (toolKey.isNotEmpty()) "✨ لهجة وترجمة · " + (if (toolPct > 0) "$toolPct%" else "⏳") else "✨ لهجة وترجمة"
                }
                val playing = s.optBoolean("playing", false)
                val spd = PosExtrap.speedOf(s.optString("speed", "1x"))
                val pos = PosExtrap.pos(s.optLong("pos", 0L), m.stStamp, now, spd, playing, dur)
                if (!bar.dragging && now > holdUntil) { bar.played = if (dur > 0L) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f; tPos.text = nowTxt(pos) }
                bar.durSec = dur / 1000.0; bar.cov = m.eng.coverage; bar.pend = m.eng.pending; bar.refine = m.eng.refining; bar.invalidate()
                tDur.text = durTxt(dur)
                playB.text = if (playing) "⏸" else "▶"
                ttlTv.text = s.optString("title", "").let { if (it.isBlank()) "" else "📺  " + it }
                dualSel = s.optInt("dual", 0).coerceIn(0, 4)
                if (embedInto != null) {   // (v806) الريموت: 6 أزرار في صف — أسماء قصيرة على سطرين
                    ccTop.text = if (s.optBoolean("cc", true)) "💬\nترجمة\nظاهرة" else "🚫\nترجمة\nمخفية"
                    dualTop.text = "🔀\n" + dualShort[dualSel]
                } else {
                    ccTop.text = if (s.optBoolean("cc", true)) "💬\nالترجمة ظاهرة" else "🚫\nالترجمة مخفية"   // (v695)
                    dualTop.text = "🔀\n" + dualNames[dualSel]
                }
                val cnt = s.optString("cnt", "")
                val vc = s.optInt("vcnt", 0)
                if (!live) {
                    cntTv.text = "📺 مفيش فيديو شغّال"
                    modeTv.text = if (RemoteClient.lastTv() == null) "⚠ مفيش تلفزيون متصل" else "شغّل فيديو في مشغّل التطبيق على التلفزيون وهيظهر هنا"
                } else {
                    val sfDone = s.optBoolean("sf_done", false)
                    val hasInt = s.optInt("sf_nat", 0) > 0 || s.optString("sf_side", "").isNotBlank()
                    if (sfDone) {   // (v765) ترجمة داخلية/ملف خلصت: «تمت» وعدد الجمل بس — من غير عدّادات جيميناي/صوتي/وشبر
                        cntTv.text = "✅ تمت ترجمة الترجمة الداخلية"
                        modeTv.text = s.optInt("sf_n", 0).toString() + " جملة"
                    } else if (!s.optBoolean("tstart", false) && hasInt && s.optInt("sf_n", 0) == 0) {   // فيه ترجمة داخلية لسه ماتترجمتش
                        cntTv.text = "💬 فيه ترجمة داخلية في الفيديو"
                        modeTv.text = "دوس ▶ ترجم عشان تترجمها"
                    } else {
                        cntTv.text = if (cnt.isNotBlank()) cnt else s.optString("tr", "—")
                        modeTv.text = s.optString("mode", "") + (if (vc > 0) "   ·   👁 $vc جملة بصرية" else "")
                    }
                }
                qkB.alpha = if (live && (qList.size > 1 || s.optBoolean("quality", false))) 1f else 0.35f
                qkB.text = qShort(); qkB.textSize = if (qList.isNotEmpty()) 13f else 18f   // (v779) رقم الجودة مكتوب على الزرار
                qB.text = "🎞\nالجودة" + (if (qList.isNotEmpty()) ": " + qShort() else "")   // (v765) ثابت/باهت لو مفيش جودة
                val al = if (live) 1f else 0.4f
                cntCard.visibility = if (live) View.VISIBLE else View.GONE
                handoffB.visibility = if (live) View.VISIBLE else View.GONE
                idleBox.visibility = View.GONE   // (v782) المكتبة/يوتيوب/المتصفح/السجل بقوا في الصفحة الرئيسية للريموت · (v702) أزرار المكتبة/يوتيوب/المتصفح/السجل ظاهرة دايمًا أول ما الريموت يتربط، حتى لو فيه فيديو شغّال
                idleNote.text = if (RemoteClient.lastTv() == null) "⚠ مفيش تلفزيون متصل" else if (!live) "📺 مفيش فيديو شغّال — شغّل من هنا" else ""
                idleNote.visibility = if (idleNote.text.isEmpty()) View.GONE else View.VISIBLE
                for (k in 0 until left.childCount) { val c = left.getChildAt(k); c.alpha = if (c === idleBox) 1f else al }   // (v691) التعتيم من غير الأزرار التلاتة
                volCol.alpha = al; tr.alpha = al; trRow.alpha = al; bar.alpha = al
                for (v0 in dimExtra) v0.alpha = al
                speedB.text = "⏩ " + s.optString("speed", "1x")
                speedBot.text = "⏩\n" + s.optString("speed", "1x")
                if (!busy) wtSel = s.optString("wtr", wtSel.ifEmpty { "free" })
                trB.text = s.optString("tr", "▶ ترجم") + " ▾"
                trB.background = bg(s.optInt("trcolor", 0xFFE53935.toInt()), 0x66FFFFFF, 18f)
                fontTv.text = "🔠 الخط: " + s.optString("font", "—")
                sizeTv.text = "🔤 حجم الترجمة: " + s.optInt("scale", 100) + "%"
                timeTv.text = String.format(Locale.US, "⏱ التوقيت: %+.2f ث", s.optLong("shift", 0L) / 1000.0)
                posTv.text = "↕ موضع الترجمة: " + s.optInt("posy", 0)
                val ab = s.optString("ab", "")
                abTv.text = if (ab.isBlank()) "حلقة A-B: مش متحددة" else "حلقة A-B: $ab"
                run {   // (v759) عدّاد نت الترجمة: التلفزيون (من حالته) + الموبايل (من العدّاد المحلي)
                    fun mb(b: Long): String = if (b < 0L) "—" else NetUse.fmt(b)
                    netLine.text = "📺 تلفزيون: الفيديو ده " + mb(s.optLong("net_v", -1L)) + " · النهارده " + mb(s.optLong("net_d", -1L)) +
                        "\n📱 موبايل: الفيديو ده " + mb(NetUse.trVideo()) + " · النهارده " + mb(NetUse.trToday())
                }
                capVis.visibility = if (vc > 0) View.VISIBLE else View.GONE
                capVis.text = "👁 $vc"
                val ym = s.optString("ytmode", "")
                capMode.visibility = if (ym.isNotBlank()) View.VISIBLE else View.GONE
                capMode.text = ym
                pzB.visibility = if (s.optBoolean("tstart", false)) View.VISIBLE else View.GONE
                pzB.text = if (s.optBoolean("tpause", false)) "▶" else "▮▮"
                cxB.visibility = pzB.visibility
                rdB.visibility = if (s.optBoolean("hsub", false)) View.VISIBLE else View.GONE
                lgB.visibility = if (s.optBoolean("cdial", false)) View.VISIBLE else View.GONE
                aiB.visibility = lgB.visibility; aiBtnSettings.visibility = lgB.visibility   // (v696) نفس قاعدة الوضع: من غير لهجات = الأزرار تختفي
                bgNow = s.optInt("bgopa", 45)
                bgTv.text = "◼ شفافية الخلفية: $bgNow%"
                ccB.text = "💬\n" + (if (s.optBoolean("cc", true)) "الترجمة: شغّالة" else "الترجمة: مقفولة")
                fitB.text = "🔳\nالعرض: " + s.optString("fit", "—")
                dualB.text = "🌐\n" + (if (s.optInt("dual", 0) != 0) "ثنائي: شغّال" else "ثنائي اللغة")
                val lmN = s.optInt("lm", if (s.optBoolean("loop", false)) 1 else 0)
                loopTop.text = when (lmN) { 1 -> "🔂"; 2 -> "🔁"; else -> "➡" }
                repCyc.text = when (lmN) { 1 -> "🔂\nتكرار واحد"; 2 -> "🔁\nتكرار الكل"; else -> "➡\nبدون تكرار" }
                repCyc.background = bg(if (lmN != 0) cOn else cCard, if (lmN != 0) cOnLine else cLine, 14f)
                infoLine.text = (if (!live) cntTv.text.toString() + "  ·  " + modeTv.text else cntTv.text.toString() + (if (modeTv.text.isNotBlank()) "  ·  " + modeTv.text else ""))
                loopB.text = "🔂\n" + when (lmN) { 1 -> "التكرار: نفس الفيديو"; 2 -> "التكرار: كل الفولدر"; else -> "التكرار: مقفول" }
                lockB.text = "🔒\n" + (if (s.optBoolean("locked", false)) "مقفول" else "قفل الشاشة")
                if (!busy) {
                    started = s.optBoolean("tstart", false); paused = s.optBoolean("tpause", false)
                    curDialect = m.eng.convDialect; curStyle = m.eng.toneStyle; curStrength = m.eng.toneStrength
                    qList = strList(s.optJSONArray("ql")); qCur = s.optInt("qc", 0)
                    aList = strList(s.optJSONArray("al")); aCur = s.optInt("ac", 0)
                }
            }
            val lsn: () -> Unit = { try { render() } catch (e: Throwable) { LogStore.err("PlayerPad:render", e) } }
            LinkSession.addListener(lsn)
            val tick = object : Runnable {
                override fun run() {
                    if (closed) return
                    try { render(); val v = if (liveNow()) TvVolume.current().coerceAtMost(100) else RemoteClient.tvVol; if (v >= 0 && !vol.dragging && System.currentTimeMillis() - volTouchAt > 1500L) vol.setValue(v) } catch (e: Throwable) { LogStore.err("PlayerPad:tick", e) }
                    hh.postDelayed(this, 250L)
                }
            }
            var startedHere = false
            fun cleanupEmbed() {   // (v787) تنضيف وضع الصفحة الموحّدة (نفس اللي كان بيحصل عند قفل الورقة)
                closed = true; hh.removeCallbacksAndMessages(null)
                try { LinkSession.removeListener(lsn) } catch (_: Throwable) {}
                if (startedHere) { try { LinkSession.stop() } catch (_: Throwable) {} }
            }
            if (embedInto != null) outDispose = { cleanupEmbed() }
            if (embedInto == null) {
            root.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(3f); setColor(0x66FFFFFF) }; setOnClickListener { d.dismiss() } },
                FrameLayout.LayoutParams(dp(48), dp(6), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })   // (v782) مقبض الورقة
            d.setContentView(root)
            d.setCanceledOnTouchOutside(true)
            d.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); setGravity(Gravity.BOTTOM)
                setLayout(-1, (dm.heightPixels * 0.92f).toInt()); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.55f)
                attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
            }
            d.setOnDismissListener {
                closed = true; hh.removeCallbacksAndMessages(null)
                try { LinkSession.removeListener(lsn) } catch (_: Throwable) {}
                try { act.requestedOrientation = oldOri } catch (_: Throwable) {}
                if (startedHere) { try { LinkSession.stop() } catch (_: Throwable) {} }
            }
            d.setOnKeyListener { _, code, ev ->   // (v712) زراري الفوليوم بتاعة الموبايل وانت جوه الصفحة: بيتحكموا في صوت التلفزيون والسلايدر يتحرك معاهم
                if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (ev.action == android.view.KeyEvent.ACTION_DOWN) {
                        val n = TvVolume.next(vol.value, if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1).coerceIn(0, 100)
                        vol.setValue(n); sendVol(n)
                    }
                    true
                } else false
            }
            d.setCancelable(true)   // (v700) زرار رجوع الموبايل بيقفل الصفحة
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            d.show()
            }
            hh.post(tick)
            Thread {
                val ok = try { RemoteClient.lastTv() != null && RemoteClient.ensureConnected(act.applicationContext, 6000L) } catch (_: Throwable) { false }
                act.runOnUiThread {
                    if (closed) return@runOnUiThread
                    if (ok) {
                        try { val was = LinkSession.connected; LinkSession.start(act); startedHere = !was } catch (e: Throwable) { LogStore.err("PlayerPad:link", e) }
                    } else modeTv.text = "⚠ مش متصل بالتلفزيون"
                }
            }.also { it.isDaemon = true }.start()
        } catch (e: Throwable) { LogStore.err("PlayerPad:show", e) }
        return outDispose
    }
}

/** (v709) زرار فيه طبقة «ميه» بتطلع من تحت: cur = المستوى الحالي المتنعّم (0..1) */
private class PadFill(val key: String, val v: android.widget.TextView, val clip: android.graphics.drawable.ClipDrawable, val at: Long) { var cur = 0f }
