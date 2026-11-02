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
object PlayerPad {
    /** (v684) آخر مترجم اتختار لترجمة ملف الترجمة (google / gemini) */
    @Volatile private var lastSfEng = "gemini"

    fun show(act: Activity, onLeave: (() -> Unit)? = null) {
        try {
            try { RemoteClient.init(act) } catch (_: Throwable) {}
            val dm = act.resources.displayMetrics
            fun dp(v: Int) = (v * dm.density).toInt()
            fun dpf(v: Float) = v * dm.density
            val th = Themes.byId(Cfg.str("theme", "mx"))
            val hh = Handler(Looper.getMainLooper())
            val oldOri = act.requestedOrientation
            try { act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } catch (_: Throwable) {}
            val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val root = FrameLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; setBackgroundColor(0xFF0F1830.toInt()) }

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
                Thread { try { RemoteClient.player(a, v) } catch (e: Throwable) { LogStore.err("PlayerPad:go", e) } }.also { it.isDaemon = true }.start()
            }
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
            fun chipRows(body: LinearLayout, labels: List<String>, per: Int, isOn: (String) -> Boolean, tap: (String) -> Unit) {
                var row: LinearLayout? = null
                labels.forEachIndexed { i, l ->
                    if (i % per == 0) {
                        row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                        body.addView(row, lp(-1, -2).apply { topMargin = dp(8) })
                    }
                    val on = isOn(l)
                    row?.addView(btn(l, 15f, if (on) cOn else cCard, if (on) cOnLine else cLine) { tap(l) },
                        lp(0, dp(46), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                }
                val rem = labels.size % per
                if (rem != 0) for (k in 0 until per - rem) row?.addView(View(act), lp(0, dp(46), 1f))
            }
            fun opt(body: LinearLayout, t: String, tap: () -> Unit) {
                body.addView(btn(t, 15f) { tap() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }, lp(-1, dp(52)).apply { topMargin = dp(8) })
            }
            fun grp(body: LinearLayout, t: String) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(View(act).apply { background = pill(cOnLine) }, lp(dp(4), dp(14)))
                row.addView(label(t, 12.5f, cDim).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, lp(0, -2, 1f).apply { marginStart = dp(8) })
                body.addView(row, lp(-1, -2).apply { topMargin = dp(14) })
            }
            /** (v688) كارت معلومة/حالة جوه الورقة */
            fun infoCard(body: LinearLayout, t: String, col: Int = 0xFFBBD0FF.toInt(), fill: Int = 0xFF1B2B52.toInt(), line: Int = 0xFF3B5A99.toInt()) {
                body.addView(label(t, 12.5f, col, false).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; background = bg(fill, line, 14f); setPadding(dp(12), dp(10), dp(12), dp(10)) }, lp(-1, -2).apply { topMargin = dp(10) })
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
                grp(body, "من الموبايل")
                opt(body, "📂 اختار ملف ترجمة من الموبايل (SRT / ASS / VTT)") { close(); pickSubFromPhone() }
                if (mob.isNotBlank()) opt(body, "📄 ترجم الملف المستورد «$mob»") { close(); openSfSettings("mobile", "📄 الملف المستورد: «$mob»") }
            }
            fun sheetTr() = sheet("🔤 الترجمة") { body, _, close ->
                body.addView(label(st.optString("tr", "▶ ترجم") + "   ·   " + st.optString("mode", ""), 13f, cDim, false), lp(-1, -2).apply { topMargin = dp(6) })
                opt(body, "📄 ترجمة ملف ترجمة (المرفق مع الفيديو أو من الموبايل)") { close(); sheetSubFile() }
                if (!started && st.optBoolean("tpart", false)) {
                    grp(body, "📼 فيه ترجمة محفوظة للفيديو ده (ناقصة)")
                    opt(body, "▶ كمّل الباقي (بيتخطّى المترجم)") { go("tr_cont"); close() }
                    opt(body, "📍 كمّل من مكان التشغيل الحالي") { go("tr_here"); close() }
                    opt(body, "🔁 ترجم من الأول وجديد (القديمة تتحفظ)") { go("tr_fresh"); close() }
                }
                if (!started) {
                    grp(body, "🎧 من الصوت مباشرة — أدق · محتاج نت")
                    opt(body, "⭐ FreeLLM · جيميناي بكل مفاتيحك") { go("tr_free"); close() }
                    grp(body, "🌗 على الجهاز — وشبر يفرّغ وبعدين مترجم")
                    opt(body, "🌗 وشبر + مترجم") { go("tr_whisper"); close() }
                    grp(body, "✍ تفريغ بس (من غير ترجمة)")
                    opt(body, "☁ بجيميناي (من الصوت)") { go("tr_gtx"); close() }
                    opt(body, "📴 على الجهاز بوشبر") { go("tr_wtx"); close() }
                } else {
                    opt(body, if (paused) "▶ كمّل الترجمة" else "⏸ إيقاف مؤقت") { go(if (paused) "tr_resume" else "tr_pause"); close() }
                    opt(body, "🔀 غيّر الوضع (بيفتح على التلفزيون)") { go("tr_wiz"); close() }
                    opt(body, "🌙 كمّل الترجمة في الخلفية") { go("tr_bg"); close() }
                    opt(body, "📋 الجمل") { go("sent"); close() }
                    opt(body, "🧑 الشخصيات") { go("chars"); close() }
                }
            }
            fun sheetAi() = sheet("✨ لهجة وإعدادات الترجمة") { body, redo, close ->
                if (!started) body.addView(label("⚠ ابدأ الترجمة الأول (الزرار الأحمر تحت)", 13f, 0xFFFFB74D.toInt()), lp(-1, -2).apply { topMargin = dp(8) })
                grp(body, "لهجة الترجمة")
                chipRows(body, kotlin.collections.listOf("فصحى", "مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني"), 4,
                    { l -> l == (if (curDialect.isBlank()) "فصحى" else curDialect) }) { l ->
                    curDialect = if (l == "فصحى") "" else l; go("dialect:$l"); redo()
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
                chipRows(body, tools.keys.toList(), 3, { false }) { l -> go("ai:" + (tools[l] ?: "")); close() }
                opt(body, "⚙ كل إعدادات الترجمة على التلفزيون") { go("subdlg", 1L); close() }
            }

            // ── فوق: إغلاق + عنوان الفيديو ──
            val ttlTv = label("", 14f, 0xFFB0BEC5.toInt(), false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val headRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
            headRow.addView(btn("✕", 18f) { d.dismiss() }, lp(dp(48), dp(44)))
            headRow.addView(ttlTv, lp(0, -2, 1f).apply { leftMargin = dp(10) })
            val handoffB = btn("📱", 18f, 0xFF2563EB.toInt(), 0xFF5B8DEF.toInt()) { PlayQueueUi.handoffToPhone(act) { try { d.dismiss() } catch (_: Throwable) {}; try { onLeave?.invoke() } catch (_: Throwable) {} } }.apply { visibility = View.GONE }   // (v694) كمّل الفيديو ده على الموبايل من نفس الثانية
            headRow.addView(handoffB, lp(dp(48), dp(44)).apply { leftMargin = dp(6) })
            headRow.addView(btn("🎞", 18f) { PlayQueueUi.show(act) }, lp(dp(48), dp(44)).apply { leftMargin = dp(6) })   // (v694) طابور التشغيل

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
            val capNet = cap("🌐 النت") { go("netdlg") }
            val capMode = cap("🎧") { go("ytmode") }
            val capRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
            capRow.addView(capVis, lp(-2, dp(34)).apply { marginStart = dp(3); marginEnd = dp(3) })
            capRow.addView(capNet, lp(-2, dp(34)).apply { marginStart = dp(3); marginEnd = dp(3) })
            capRow.addView(capMode, lp(-2, dp(34)).apply { marginStart = dp(3); marginEnd = dp(3) })

            // ── السرعة · اللهجة وإعدادات الترجمة ──
            val speedB = btn("⏩ 1x", 16f) { sheetSpeed() }
            val aiB = btn("✨ لهجة وترجمة", 14f) { sheetAi() }
            val quick = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            quick.addView(speedB, lp(0, dp(52), 0.8f)); quick.addView(aiB, lp(0, dp(52), 1.3f).apply { marginStart = dp(8) })

            // ── تابات الإعدادات: 🔤 نص · ⏱ تزامن · 🗣 ذكاء · 🧰 أدوات ──
            fun newBody(): LinearLayout = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE }
            fun stepRow(body: LinearLayout, minus: () -> Unit, plus: () -> Unit): TextView {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; background = bg(cCard, cLine, 16f) }
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
                        gr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                        body.addView(gr, lp(-1, -2).apply { topMargin = dp(6) })
                    }
                    gr?.addView(c, lp(0, dp(h), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
                }
                val rem = cs.size % per
                if (rem != 0) for (k in 0 until per - rem) gr?.addView(View(act), lp(0, dp(h), 1f))
            }
            // 🔤 نص
            val bodyText = newBody()
            val fontTv = stepRow(bodyText, { go("fontprev") }, { go("font") })
            val sizeTv = stepRow(bodyText, { go("scale", -10L) }, { go("scale", 10L) })
            val posTv = stepRow(bodyText, { go("posy", -10L) }, { go("posy", 10L) })
            val bgTv = stepRow(bodyText, { go("bgopa", (bgNow - 10).coerceIn(0, 100).toLong()) }, { go("bgopa", (bgNow + 10).coerceIn(0, 100).toLong()) })
            bodyText.addView(btn("🎨 استوديو الترجمة (على التلفزيون)", 13f) { go("studio") }, lp(-1, dp(46)).apply { topMargin = dp(6) })
            // ⏱ تزامن
            val bodySync = newBody()
            val timeTv = stepRow(bodySync, { go("shift", -250L) }, { go("shift", 250L) })
            bodySync.addView(btn("⟲ تصفير فرق التوقيت", 13f) { go("shift0") }, lp(-1, dp(44)).apply { topMargin = dp(6) })
            val abTv = label("حلقة A-B: مش متحددة", 12f, cDim, false)
            bodySync.addView(abTv, lp(-1, -2).apply { topMargin = dp(10) })
            grid(bodySync, kotlin.collections.listOf(btn("🅰 نقطة أ", 12.5f) { go("ab_a") }, btn("🅱 نقطة ب", 12.5f) { go("ab_b") }, btn("✖ إلغاء", 12.5f) { go("ab_x") }), 3, 46)
            bodySync.addView(btn("⏱ شريط التزامن السريع", 13f) { go("syncstrip") }, lp(-1, dp(44)).apply { topMargin = dp(8) })
            // 🗣 ذكاء
            val bodyAi = newBody()
            bodyAi.addView(btn("🗣 لهجة وأسلوب وشدة…", 13f) { sheetAi() }, lp(-1, dp(46)).apply { topMargin = dp(6) })
            bodyAi.addView(btn("📄 ترجمة ملف ترجمة…", 13f) { sheetSubFile() }, lp(-1, dp(46)).apply { topMargin = dp(6) })
            bodyAi.addView(label("أدوات الذكاء (دوسة واحدة)", 12f, cDim).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, lp(-1, -2).apply { topMargin = dp(10) })
            val aiTools = kotlin.collections.listOf("✍ تنقيح" to "refine", "🔥 لهجة +" to "dial", "🧹 عائلي" to "family", "🔞 صريح" to "explicit", "🔧 ضمائر" to "pron", "🧠 دمج مكرر" to "dedup")
            grid(bodyAi, aiTools.map { pr -> btn(pr.first, 12.5f) { go("ai:" + pr.second) } }, 2, 46)
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
            grid(bodyTools, kotlin.collections.listOf(ccB, fitB, rotB, dualB, loopB, visB, sentB, charsB, subsB, qB, aB, lockB, notB, menuB, toolsB, moreB, killB), 2, 60)
            val bodies = kotlin.collections.listOf(bodyText, bodySync, bodyAi, bodyTools)
            val tabNames = kotlin.collections.listOf("🔤 نص", "⏱ تزامن", "🗣 ذكاء", "🧰 أدوات")
            val tabBtns = ArrayList<TextView>()
            var curTab = 0
            fun selectTab(i: Int) {
                curTab = i
                bodies.forEachIndexed { k, b -> b.visibility = if (k == i) View.VISIBLE else View.GONE }
                tabBtns.forEachIndexed { k, b -> b.background = bg(if (k == i) cOn else cCard, if (k == i) cOnLine else cLine, 12f) }
            }
            val tabBar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            tabNames.forEachIndexed { k, n ->
                val tb = btn(n, 11.5f) { selectTab(k) }.apply { setPadding(dp(2), dp(6), dp(2), dp(6)) }
                tabBtns.add(tb); tabBar.addView(tb, lp(0, dp(38), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
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
                val pr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                pr.addView(btn("📺\nشاشة التلفزيون", 15f, cOn, cOnLine) { close(); openLib(true) }, lp(0, dp(96), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                pr.addView(btn("📱\nشاشة الموبايل", 15f) { close(); openLib(false) }, lp(0, dp(96), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                body.addView(pr, lp(-1, -2).apply { topMargin = dp(12) })
            }
            fun idleBtn(icon: String, t: String, fill: Int, line: Int, tap: () -> Unit): TextView =
                btn(icon + "\n" + t, 12.5f, fill, line) { tap() }.apply { setPadding(dp(2), dp(10), dp(2), dp(10)) }
            val idleRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            idleRow.addView(idleBtn("📚", "المكتبة", 0xFF2563EB.toInt(), 0xFF5B8DEF.toInt()) { sheetLib() }, lp(0, dp(104), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            idleRow.addView(idleBtn("▶", "يوتيوب", 0xFFD93A36.toInt(), 0xFFFF6F69.toInt()) { ControllerPad.openOnPhone(act, true); leave() }, lp(0, dp(104), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            idleRow.addView(idleBtn("🌐", "المتصفح", 0xFF0B8BC4.toInt(), 0xFF38BDF8.toInt()) { ControllerPad.openOnPhone(act, false); leave() }, lp(0, dp(104), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            idleRow.addView(idleBtn("🕘", "سجل المشاهدة", 0xFF6D4BB8.toInt(), 0xFF9B7BE6.toInt()) { RecentPicker.show(act) }, lp(0, dp(104), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })   // (v692) آخر اللي اتشاف على التلفزيون والموبايل مع بعض
            val idleNote = label("", 12f, cDim, false)
            val idleBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            idleBox.addView(idleRow, lp(-1, -2)); idleBox.addView(idleNote, lp(-1, -2).apply { topMargin = dp(6) })

            // ── العمود الشمال: عدّاد + كبسولات + سرعة/لهجة + تابات ──
            val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            left.addView(idleBox, lp(-1, -2))
            left.addView(cntCard, lp(-1, -2))
            left.addView(capRow, lp(-1, -2).apply { topMargin = dp(8) })
            left.addView(quick, lp(-1, -2).apply { topMargin = dp(8) })
            left.addView(tabBar, lp(-1, -2).apply { topMargin = dp(8) })
            left.addView(sc, lp(-1, 0, 1f).apply { topMargin = dp(4) })

            // ── الصوت: سلايدر رأسي طولي = 60% من طول الصفحة، على الجنب + كتم ──
            val vol = VertVolume(act) { p -> RemoteClient.setVolume(p) }
            val muteB = btn("🔇", 20f) { RemoteClient.cmd("mute") }
            val volH = minOf((dm.heightPixels * 0.60f).toInt(), dm.heightPixels - dp(330)).coerceAtLeast(dp(180))
            val volCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL }
            volCol.addView(muteB, lp(-1, dp(46)))
            volCol.addView(vol, lp(-1, volH - dp(54)).apply { topMargin = dp(8) })
            val mid = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            mid.addView(left, lp(0, -1, 1f)); mid.addView(volCol, lp(dp(76), -1).apply { leftMargin = dp(12) })

            // ── صف الترجمة (فوق أزرار التحكم): [▶ ترجم / نسبة] ▮▮ ⚙ 🔁 🌐 ──
            val trB = btn("▶ ترجم ▾", 16f, 0xFFE53935.toInt(), 0xFFFF6F69.toInt()) { sheetTr() }
            val pzB = btn("▮▮", 17f) { go(if (paused) "tr_resume" else "tr_pause") }
            val wzB = btn("⚙", 18f) { go("modeset") }
            val rdB = btn("🔁", 18f, 0xFF6A1B9A.toInt(), 0xFF8E3FC4.toInt()) { go("redo") }
            val lgB = btn("🌐", 18f) { sheetAi() }
            val trRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
            trRow.addView(trB, lp(0, dp(52), 2.6f))
            for (b in kotlin.collections.listOf(pzB, wzB, rdB, lgB)) trRow.addView(b, lp(0, dp(52), 1f).apply { marginStart = dp(6) })

            // ── أزرار التحكم ──
            val playB = btn("▶", 28f, 0xFF2563EB.toInt(), 0xFF3B82F6.toInt()) { go("toggle") }
            val tr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
            tr.addView(btn("⏮", 22f) { go("prev") }, lp(0, dp(58), 1f).apply { marginEnd = dp(6) })
            tr.addView(btn("⏪ 10", 14f) { go("seek", -10000L) }, lp(0, dp(58), 1f).apply { marginEnd = dp(6) })
            tr.addView(playB, lp(0, dp(70), 1.3f).apply { marginEnd = dp(6) })
            tr.addView(btn("10 ⏩", 14f) { go("seek", 10000L) }, lp(0, dp(58), 1f).apply { marginEnd = dp(6) })
            tr.addView(btn("⏭", 22f) { go("next") }, lp(0, dp(58), 1f))

            // ── شريط التقدم تحت خالص: نفس DualProgress بتاع المشغّل (أخضر = مترجم · كهرماني = لسه هيتغيّر · أزرق = تنقيح) ──
            val bar = DualProgress(act, th).apply { mini = true }
            val tPos = label("00:00", 13f, 0xFFB8C4DA.toInt(), false); val tDur = label("00:00", 13f, 0xFFB8C4DA.toInt(), false)
            bar.onScrub = { f -> tPos.text = tm((f * durNow).toLong()) }
            bar.onSeek = { f -> val ms = (f * durNow).toLong(); holdUntil = System.currentTimeMillis() + 1300L; tPos.text = tm(ms); go("seekto", ms) }
            val times = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            times.addView(tPos, lp(0, -2, 1f)); times.addView(tDur, lp(-2, -2))
            tPos.gravity = Gravity.START or Gravity.CENTER_VERTICAL

            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(dp(16), dp(26), dp(16), dp(12)) }
            col.addView(headRow, lp(-1, dp(48)))
            col.addView(mid, lp(-1, 0, 1f).apply { topMargin = dp(6) })
            col.addView(trRow, lp(-1, dp(52)).apply { topMargin = dp(8) })
            col.addView(tr, lp(-1, dp(72)).apply { topMargin = dp(8) })
            col.addView(bar, lp(-1, -2).apply { topMargin = dp(8) })
            col.addView(times, lp(-1, -2))
            root.addView(col, FrameLayout.LayoutParams(-1, -1))

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
                val playing = s.optBoolean("playing", false)
                val spd = PosExtrap.speedOf(s.optString("speed", "1x"))
                val pos = PosExtrap.pos(s.optLong("pos", 0L), m.stStamp, now, spd, playing, dur)
                if (!bar.dragging && now > holdUntil) { bar.played = if (dur > 0L) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f; tPos.text = tm(pos) }
                bar.durSec = dur / 1000.0; bar.cov = m.eng.coverage; bar.pend = m.eng.pending; bar.refine = m.eng.refining; bar.invalidate()
                tDur.text = tm(dur)
                playB.text = if (playing) "⏸" else "▶"
                ttlTv.text = s.optString("title", "").let { if (it.isBlank()) "" else "📺  " + it }
                val cnt = s.optString("cnt", "")
                val vc = s.optInt("vcnt", 0)
                if (!live) {
                    cntTv.text = "📺 مفيش فيديو شغّال"
                    modeTv.text = if (RemoteClient.lastTv() == null) "⚠ مفيش تلفزيون متصل" else "شغّل فيديو في مشغّل التطبيق على التلفزيون وهيظهر هنا"
                } else {
                    cntTv.text = if (cnt.isNotBlank()) cnt else s.optString("tr", "—")
                    modeTv.text = s.optString("mode", "") + (if (vc > 0) "   ·   👁 $vc جملة بصرية" else "")
                }
                val al = if (live) 1f else 0.4f
                cntCard.visibility = if (live) View.VISIBLE else View.GONE
                handoffB.visibility = if (live) View.VISIBLE else View.GONE
                idleBox.visibility = if (live) View.GONE else View.VISIBLE
                idleNote.text = if (RemoteClient.lastTv() == null) "⚠ مفيش تلفزيون متصل" else "📺 مفيش فيديو شغّال — شغّل من هنا"
                for (k in 0 until left.childCount) { val c = left.getChildAt(k); c.alpha = if (c === idleBox) 1f else al }   // (v691) التعتيم من غير الأزرار التلاتة
                volCol.alpha = al; tr.alpha = al; trRow.alpha = al; bar.alpha = al
                speedB.text = "⏩ " + s.optString("speed", "1x")
                trB.text = s.optString("tr", "▶ ترجم") + " ▾"
                trB.background = bg(s.optInt("trcolor", 0xFFE53935.toInt()), 0x66FFFFFF, 18f)
                fontTv.text = "🔠 الخط: " + s.optString("font", "—")
                sizeTv.text = "🔤 حجم الترجمة: " + s.optInt("scale", 100) + "%"
                timeTv.text = String.format(Locale.US, "⏱ التوقيت: %+.2f ث", s.optLong("shift", 0L) / 1000.0)
                posTv.text = "↕ موضع الترجمة: " + s.optInt("posy", 0)
                val ab = s.optString("ab", "")
                abTv.text = if (ab.isBlank()) "حلقة A-B: مش متحددة" else "حلقة A-B: $ab"
                capVis.visibility = if (vc > 0) View.VISIBLE else View.GONE
                capVis.text = "👁 $vc"
                val ym = s.optString("ytmode", "")
                capMode.visibility = if (ym.isNotBlank()) View.VISIBLE else View.GONE
                capMode.text = ym
                pzB.visibility = if (s.optBoolean("tstart", false)) View.VISIBLE else View.GONE
                pzB.text = if (s.optBoolean("tpause", false)) "▶" else "▮▮"
                rdB.visibility = if (s.optBoolean("hsub", false)) View.VISIBLE else View.GONE
                lgB.visibility = if (s.optBoolean("cdial", false)) View.VISIBLE else View.GONE
                bgNow = s.optInt("bgopa", 45)
                bgTv.text = "◼ شفافية الخلفية: $bgNow%"
                ccB.text = "💬\n" + (if (s.optBoolean("cc", true)) "الترجمة: شغّالة" else "الترجمة: مقفولة")
                fitB.text = "🔳\nالعرض: " + s.optString("fit", "—")
                dualB.text = "🌐\n" + (if (s.optInt("dual", 0) != 0) "ثنائي: شغّال" else "ثنائي اللغة")
                loopB.text = "🔂\n" + (if (s.optBoolean("loop", false)) "التكرار: شغّال" else "تكرار الفيديو")
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
                    try { render(); val v = RemoteClient.tvVol; if (v >= 0 && !vol.dragging) vol.setValue(v) } catch (e: Throwable) { LogStore.err("PlayerPad:tick", e) }
                    hh.postDelayed(this, 250L)
                }
            }
            var startedHere = false
            d.setContentView(root)
            d.setOnDismissListener {
                closed = true; hh.removeCallbacksAndMessages(null)
                try { LinkSession.removeListener(lsn) } catch (_: Throwable) {}
                try { act.requestedOrientation = oldOri } catch (_: Throwable) {}
                if (startedHere) { try { LinkSession.stop() } catch (_: Throwable) {} }
            }
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            d.show()
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
    }
}
