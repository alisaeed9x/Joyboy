package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.min

/**
 * (v603) جويستيك بدايرة OK: حوالين الجويستيك دايرة واضحة (دايرة الـOK).
 *  - دوسة أو سحبة صغيرة جوه الدايرة (من غير ما تعدّي حدّها بـ50% من نصف قطرها) = زرار OK، وبتتنفّذ لما ترفع صباعك (مهما طالت الدوسة).
 *  - خروج بره الدايرة بمقدار 50% أو أكتر من نصف قطرها = حركة (فوق/تحت/يمين/شمال) فورًا، وكل ما تبعد أكتر السرعة تزيد.
 *  - لو اتحرّكت (خطوة اتبعتت) ورجعت جوه الدايرة ورفعت صباعك: مفيش OK (عشان الحركة ما تنقلبش OK بالغلط).
 */
class JoystickView(ctx: Context, private val onKey: (String) -> Unit) : View(ctx) {
    private val h = Handler(Looper.getMainLooper())
    private val den = resources.displayMetrics.density
    private val pBase = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF1A2230.toInt() }
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * den; color = 0x55FFFFFF }
    private val pOkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pOkRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * den }
    private val pArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pKnob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF1E6BFF.toInt() }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private var kx = 0f
    private var ky = 0f
    private var dir: String? = null
    private var strength = 0f
    private var fired = false      // اتبعتت حركة في الدوسة دي ← مفيش OK عند الرفع
    private var down = false

    private val repeat = object : Runnable {
        override fun run() {
            val d = dir ?: return
            onKey(d)
            h.postDelayed(this, (420f - 350f * strength).toLong().coerceAtLeast(60L))   // 420ms (سحب خفيف) ← 70ms (لآخره)
        }
    }

    private fun rad(): Float = min(width, height) / 2f - 6f * den
    /** نصف قطر دايرة الـOK */
    private fun okRad(): Float = rad() * 0.30f * 1.3f   // (v613) زرار OK (الدايرة الزرقا) + 30%
    /** مسافة بداية الحركة = حد الدايرة + 50% من نصف قطرها */
    private fun moveAt(): Float = okRad()   // (v613) أي لمسة جوه دايرة OK = OK؛ أول ما تعدّي حدّها = حركة/سلايد

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                fired = false; down = true
                update(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> update(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                val isOk = down && !fired
                stop()
                if (isOk) { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onKey("ok") }
            }
            MotionEvent.ACTION_CANCEL -> stop()
        }
        return true
    }

    private fun stop() {
        h.removeCallbacks(repeat); dir = null; kx = 0f; ky = 0f; strength = 0f; down = false; invalidate()
    }

    private fun update(x: Float, y: Float) {
        val dx = x - width / 2f; val dy = y - height / 2f
        val dist = hypot(dx, dy); val r = rad(); val mv = moveAt()
        if (dist > 0f) { val k = min(dist, r * 0.55f) / dist; kx = dx * k; ky = dy * k } else { kx = 0f; ky = 0f }
        if (dist < mv) { h.removeCallbacks(repeat); dir = null; strength = 0f; invalidate(); return }   // جوه الدايرة (أو أقل من 50% بره منها) = لسه OK
        strength = ((dist - mv) / (r - mv).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val d = if (abs(dx) >= abs(dy)) (if (dx > 0f) "right" else "left") else (if (dy > 0f) "down" else "up")
        if (d != dir) {   // أول ما يدخل اتجاه (أو يغيّره): خطوة فورية، وبعد 380ms يبدأ التكرار
            h.removeCallbacks(repeat); dir = d; fired = true
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onKey(d)
            h.postDelayed(repeat, 380L)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { h.removeCallbacks(repeat); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = rad(); val ok = okRad()
        c.drawCircle(cx, cy, r, pBase); c.drawCircle(cx, cy, r, pRing)
        // دايرة الـOK: بتنوّر وهي مدوسة وجوّاها (من غير حركة)
        val armed = down && dir == null && !fired
        pOkFill.color = if (armed) 0x552F80FF else 0x222F80FF
        c.drawCircle(cx, cy, ok, pOkFill)
        pOkRing.color = if (armed) 0xFFFFFFFF.toInt() else 0xAAFFFFFF.toInt()
        c.drawCircle(cx, cy, ok, pOkRing)
        val a = r * 0.80f; val s = 14f * den
        fun arrow(name: String, ax: Float, ay: Float, ux: Float, uy: Float) {
            val px = -uy; val py = ux
            path.reset()
            path.moveTo(ax + ux * s, ay + uy * s)
            path.lineTo(ax - ux * s * 0.6f + px * s, ay - uy * s * 0.6f + py * s)
            path.lineTo(ax - ux * s * 0.6f - px * s, ay - uy * s * 0.6f - py * s)
            path.close()
            pArrow.color = if (dir == name) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt()
            c.drawPath(path, pArrow)
        }
        arrow("up", cx, cy - a, 0f, -1f); arrow("down", cx, cy + a, 0f, 1f)
        arrow("left", cx - a, cy, -1f, 0f); arrow("right", cx + a, cy, 1f, 0f)
        c.drawCircle(cx + kx, cy + ky, r * 0.30f, pKnob)
        pTxt.textSize = r * 0.16f
        c.drawText("OK", cx + kx, cy + ky + pTxt.textSize / 3f, pTxt)
    }
}

/** (v670) أيقونات خطية مرسومة بالكود (شبه أيقونات ريموت Morph TV) — مفيش ملفات صور */
class PadIcon(ctx: Context, private val kind: String, private val tint: Int = Color.WHITE) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = tint }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = tint }
    private val path = Path()
    private val rc = RectF()

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = min(w, h)
        val u = s / 24f
        cv.save(); cv.translate((w - s) / 2f, (h - s) / 2f)
        p.strokeWidth = u * 1.9f
        fun ln(a: Float, b: Float, c2: Float, d: Float) { cv.drawLine(a * u, b * u, c2 * u, d * u, p) }
        fun poly(close: Boolean, vararg v: Float) {
            path.reset(); path.moveTo(v[0] * u, v[1] * u)
            var i = 2
            while (i < v.size) { path.lineTo(v[i] * u, v[i + 1] * u); i += 2 }
            if (close) path.close()
            cv.drawPath(path, p)
        }
        fun box(l: Float, t: Float, r: Float, b: Float, rad: Float) { rc.set(l * u, t * u, r * u, b * u); cv.drawRoundRect(rc, rad * u, rad * u, p) }
        fun speaker() { poly(true, 3f, 9f, 7f, 9f, 12f, 5f, 12f, 19f, 7f, 15f, 3f, 15f) }
        when (kind) {
            "up" -> poly(false, 5f, 15f, 12f, 8f, 19f, 15f)
            "down" -> poly(false, 5f, 9f, 12f, 16f, 19f, 9f)
            "left" -> poly(false, 15f, 5f, 8f, 12f, 15f, 19f)
            "right" -> poly(false, 9f, 5f, 16f, 12f, 9f, 19f)
            "check" -> poly(false, 4f, 13f, 9f, 18f, 20f, 6f)
            "home" -> {
                poly(false, 3f, 11f, 12f, 3f, 21f, 11f)
                poly(false, 5f, 9.5f, 5f, 21f, 19f, 21f, 19f, 9.5f)
                poly(false, 10f, 21f, 10f, 14f, 14f, 14f, 14f, 21f)
            }
            "back" -> {
                poly(false, 9f, 5f, 4f, 10f, 9f, 15f)
                path.reset(); path.moveTo(4f * u, 10f * u); path.lineTo(15f * u, 10f * u)
                path.quadTo(20f * u, 10f * u, 20f * u, 15f * u); path.quadTo(20f * u, 20f * u, 15f * u, 20f * u); path.lineTo(10f * u, 20f * u)
                cv.drawPath(path, p)
            }
            "gear" -> {
                cv.drawCircle(12f * u, 12f * u, 3.2f * u, p)
                cv.drawCircle(12f * u, 12f * u, 7f * u, p)
                for (i in 0 until 8) {
                    val a = Math.PI / 4.0 * i
                    val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                    ln(12f + ca * 7f, 12f + sa * 7f, 12f + ca * 9.6f, 12f + sa * 9.6f)
                }
            }
            "inputs" -> {
                box(3f, 4f, 21f, 16f, 2.2f)
                ln(12f, 16f, 12f, 20f); ln(8f, 20f, 16f, 20f)
                ln(7f, 10f, 14f, 10f); poly(false, 11.5f, 7.5f, 14f, 10f, 11.5f, 12.5f)
            }
            "vol_plus" -> { speaker(); ln(16.5f, 12f, 22f, 12f); ln(19.25f, 9.25f, 19.25f, 14.75f) }
            "vol_minus" -> { speaker(); ln(16.5f, 12f, 22f, 12f) }
            "mute" -> { speaker(); ln(16f, 9f, 21.5f, 15f); ln(21.5f, 9f, 16f, 15f) }
            "play" -> poly(true, 7f, 4f, 20f, 12f, 7f, 20f)
            "pause" -> {
                rc.set(6f * u, 4f * u, 10.2f * u, 20f * u); cv.drawRoundRect(rc, u, u, f)
                rc.set(13.8f * u, 4f * u, 18f * u, 20f * u); cv.drawRoundRect(rc, u, u, f)
            }
            "ch_up", "ch_down" -> {
                box(3.5f, 8f, 20.5f, 21f, 3f)
                poly(false, 8f, 3.5f, 12f, 8f, 16f, 3.5f)
                if (kind == "ch_up") { ln(12f, 18f, 12f, 12f); poly(false, 9f, 14.8f, 12f, 11.8f, 15f, 14.8f) }
                else { ln(12f, 11.5f, 12f, 17.5f); poly(false, 9f, 14.7f, 12f, 17.7f, 15f, 14.7f) }
            }
            "mic" -> { box(9f, 3f, 15f, 14f, 3f); poly(false, 5.5f, 11f, 5.5f, 12f); path.reset(); path.moveTo(5.5f * u, 11f * u); path.quadTo(5.5f * u, 18f * u, 12f * u, 18f * u); path.quadTo(18.5f * u, 18f * u, 18.5f * u, 11f * u); cv.drawPath(path, p); ln(12f, 18f, 12f, 21f) }
        }
        cv.restore()
    }
}

/** (v590) شاشة كنترولر التلفزيون — (v670) اتغيّر الترتيب والحجم عشان يبقى زي ريموت Morph TV:
 *  تبديل (أزرار الاتجاهات | لوحة اللمس) ← دايرة كبيرة فيها 4 أزرار اتجاهات + «موافق» في النص ← ⚙ الإعدادات عرض كامل ←
 *  صف (رجوع · الرئيسية · المداخل) ← صف (خفض الصوت · كتم · رفع الصوت) ← صف (تشغيل · إيقاف مؤقت · قناة + · قناة −).
 *  «لوحة اللمس» = الجويستيك القديم بالظبط. الشاشة بتتسحب (ScrollView) والإغلاق بزرار/إيماءة الرجوع. */
object ControllerPad {
    fun show(act: Activity, onClose: (() -> Unit)? = null) {
        try {
            try { RemoteClient.init(act) } catch (_: Throwable) {}
            val dm = act.resources.displayMetrics
            fun dp(v: Int) = (v * dm.density).toInt()
            fun dpf(v: Float) = v * dm.density
            val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val root = FrameLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; setBackgroundColor(0xFF0F1830.toInt()) }
            val hh = Handler(Looper.getMainLooper())

            fun sendKey(k: String) { RemoteClient.key(k) }
            fun safe(tag: String, fn: () -> Unit) { try { fn() } catch (e: Throwable) { LogStore.err(tag, e) } }

            /** دوسة عادية، أو (repeat=true) دوسة بتتكرر وإنت ماسك */
            fun bind(v: View, repeat: Boolean, fire: () -> Unit) {
                if (!repeat) {
                    v.setOnClickListener { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); safe("ControllerPad:btn", fire) }
                    return
                }
                var rep: Runnable? = null
                v.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.alpha = 0.7f; v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            safe("ControllerPad:hold", fire)
                            val r = object : Runnable { override fun run() { safe("ControllerPad:hold", fire); hh.postDelayed(this, 110L) } }
                            rep = r; hh.postDelayed(r, 450L)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; rep?.let { hh.removeCallbacks(it) }; rep = null }
                    }
                    true
                }
            }

            fun cardBg(dashed: Boolean) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(22f); setColor(if (dashed) 0xFF1B2C47.toInt() else 0xFF14203A.toInt())
                if (dashed) setStroke(dp(1), 0xFF3A5278.toInt(), dpf(5f), dpf(4f)) else setStroke(dp(1), 0xFF2B3C60.toInt())
            }
            fun iconBox(kind: String, c1: Int, c2: Int, dim: Boolean): View {
                val box = FrameLayout(act).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(15f) }
                    if (dim) alpha = 0.6f
                }
                box.addView(PadIcon(act, kind, if (dim) 0xFFC9CFE6.toInt() else Color.WHITE), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                return box
            }
            /** كارت: مربع أيقونة ملوّن 40dp + الاسم تحته (زي الصورة) */
            fun card(label: String, kind: String, c1: Int, c2: Int, dashed: Boolean = false, repeat: Boolean = false, fs: Float = 15f, fire: () -> Unit): View {
                val cd = android.widget.LinearLayout(act).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = cardBg(dashed)
                    contentDescription = label; setPadding(dp(2), dp(8), dp(2), dp(8))
                }
                cd.addView(iconBox(kind, c1, c2, dashed), android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
                val tv = TextView(act).apply {
                    text = label; textSize = fs; setTextColor(if (dashed) 0xFF8FA0BC.toInt() else Color.WHITE); gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD; includeFontPadding = false; maxLines = 1
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                cd.addView(tv, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                bind(cd, repeat, fire)
                return cd
            }
            /** صف كروت متساوية العرض (الترتيب شمال ← يمين) */
            fun row(vararg cells: View): View {
                val r = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
                cells.forEachIndexed { i, c ->
                    r.addView(c, android.widget.LinearLayout.LayoutParams(0, dp(91), 1f).apply { if (i > 0) leftMargin = dp(10) })
                }
                return r
            }

            val col = android.widget.LinearLayout(act).apply {
                orientation = android.widget.LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(dp(24), dp(36), dp(24), dp(28))
            }

            // ── شريط الحالة + 🎤 بحث صوتي (صغير فوق) ──
            val top = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val st = TextView(act).apply { text = "📺 بتوصّل…"; textSize = 14f; setTextColor(0xFFB0BEC5.toInt()); gravity = Gravity.CENTER_VERTICAL }
            top.addView(st, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            val mic = FrameLayout(act).apply {
                contentDescription = "بحث صوتي"
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE53935.toInt()) }
            }
            mic.addView(PadIcon(act, "mic"), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            bind(mic, false) { RemoteClient.cmd("mic") }
            top.addView(mic, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
            col.addView(top, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

            // ── التبديل: أزرار الاتجاهات | لوحة اللمس ──
            val padSize = dp(259)
            val sw = android.widget.LinearLayout(act).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(20f); setColor(0xFF1E2F4D.toInt()); setStroke(dp(1), 0xFF34507A.toInt()) }
            }
            fun tab(txt: String) = TextView(act).apply {
                text = txt; textSize = 17f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            val tabTouch = tab("لوحة اللمس"); val tabDpad = tab("أزرار الاتجاهات")
            sw.addView(tabTouch, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
            sw.addView(tabDpad, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
            col.addView(sw, android.widget.LinearLayout.LayoutParams(-1, dp(56)))

            // ── منطقة الاتجاهات: دايرة كبيرة (أزرار 4 + موافق) أو الجويستيك (لوحة اللمس) ──
            val padBox = FrameLayout(act)
            col.addView(padBox, android.widget.LinearLayout.LayoutParams(-1, padSize).apply { topMargin = dp(22); bottomMargin = dp(23) })

            val dpad = FrameLayout(act).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF0F3441.toInt()); setStroke(dp(1), 0xFF2C7A82.toInt()) }
            }
            fun dBtn(kind: String, tx: Int, ty: Int) {
                val b = FrameLayout(act).apply {
                    contentDescription = kind
                    background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(26f); setColor(0xFF0B2338.toInt()); setStroke(dp(1), 0xFF2F6A8C.toInt()) }
                    translationX = dpf(tx.toFloat()); translationY = dpf(ty.toFloat())
                }
                b.addView(PadIcon(act, kind, 0xFF8EEBFF.toInt()), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                bind(b, true) { sendKey(kind) }
                dpad.addView(b, FrameLayout.LayoutParams(dp(69), dp(69), Gravity.CENTER))
            }
            dBtn("up", 0, -76); dBtn("down", 0, 76); dBtn("left", -76, 0); dBtn("right", 76, 0)
            val okB = android.widget.LinearLayout(act).apply {
                orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER; contentDescription = "موافق"; elevation = dpf(6f)
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(26f); setColor(0xFF0C2A44.toInt()); setStroke(dp(1), 0xFF3B82F6.toInt()) }
            }
            okB.addView(PadIcon(act, "check", 0xFF8EEBFF.toInt()), android.widget.LinearLayout.LayoutParams(dp(26), dp(26)))
            okB.addView(TextView(act).apply { text = "موافق"; textSize = 17f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; includeFontPadding = false },
                android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            bind(okB, false) { sendKey("ok") }
            dpad.addView(okB, FrameLayout.LayoutParams(dp(69), dp(69), Gravity.CENTER))

            val joy = JoystickView(act) { k -> sendKey(k) }
            padBox.addView(dpad, FrameLayout.LayoutParams(padSize, padSize, Gravity.CENTER))
            padBox.addView(joy, FrameLayout.LayoutParams(padSize, padSize, Gravity.CENTER))

            fun setMode(dpadMode: Boolean) {
                dpad.visibility = if (dpadMode) View.VISIBLE else View.GONE
                joy.visibility = if (dpadMode) View.GONE else View.VISIBLE
                fun style(t: TextView, on: Boolean) {
                    t.setTextColor(if (on) Color.WHITE else 0xFFB8C4DA.toInt())
                    t.background = if (on) GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(15f); setColor(0xFF2563EB.toInt()) } else null
                }
                style(tabDpad, dpadMode); style(tabTouch, !dpadMode)
            }
            tabDpad.setOnClickListener { setMode(true) }
            tabTouch.setOnClickListener { setMode(false) }
            setMode(true)

            // ── ⚙ الإعدادات (عرض كامل) ──
            col.addView(card("الإعدادات", "gear", 0xFF3B8DFF.toInt(), 0xFF1D4ED8.toInt()) { RemoteClient.cmd("open_settings") },
                android.widget.LinearLayout.LayoutParams(-1, dp(91)))

            // ── صف: رجوع · الرئيسية · المداخل (من اليمين) ──
            col.addView(row(
                card("المداخل", "inputs", 0xFF7C4DBB.toInt(), 0xFF5B3A9B.toInt(), dashed = true) { sendKey("inputs") },
                card("الرئيسية", "home", 0xFF0FA3B1.toInt(), 0xFF0B7285.toInt()) { sendKey("home") },
                card("رجوع", "back", 0xFFF97316.toInt(), 0xFFC2410C.toInt()) { sendKey("back") }
            ), android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

            // ── (v672) سلايدر الصوت بالعرض (شريط رفيع فوق صف الصوت) — اسحب يمين = أعلى، شمال = أوطى ──
            val volBar = VolumeBar(act) { p -> RemoteClient.setVolume(p) }
            col.addView(volBar, android.widget.LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
            val volSync = object : Runnable {   // لو الصوت اتغيّر من ريموت التلفزيون نفسه: السلايدر يلحق (إلا وإنت ماسكه)
                override fun run() {
                    val v = RemoteClient.tvVol
                    if (v >= 0 && !volBar.dragging) volBar.setValue(v)
                    hh.postDelayed(this, 1500L)
                }
            }
            hh.post(volSync)

            // ── صف: خفض الصوت · كتم · رفع الصوت (من اليمين) ──
            col.addView(row(
                card("رفع الصوت", "vol_plus", 0xFF16A068.toInt(), 0xFF0F7A4F.toInt(), repeat = true) { RemoteClient.cmd("vol_up") },
                card("كتم الصوت", "mute", 0xFFE8456F.toInt(), 0xFFBE185D.toInt()) { RemoteClient.cmd("mute") },
                card("خفض الصوت", "vol_minus", 0xFF16A068.toInt(), 0xFF0F7A4F.toInt(), repeat = true) { RemoteClient.cmd("vol_down") }
            ), android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

            // ── صف: تشغيل · إيقاف مؤقت · قناة + · قناة − (من اليمين) ──
            col.addView(row(
                card("قناة −", "ch_down", 0xFF5560B8.toInt(), 0xFF3F4A9E.toInt(), dashed = true, fs = 14f) { sendKey("ch_down") },
                card("قناة +", "ch_up", 0xFF5560B8.toInt(), 0xFF3F4A9E.toInt(), dashed = true, fs = 14f) { sendKey("ch_up") },
                card("إيقاف مؤقت", "pause", 0xFFD99A12.toInt(), 0xFFB45309.toInt(), fs = 14f) { sendKey("pause") },
                card("تشغيل", "play", 0xFF14A58A.toInt(), 0xFF0F766E.toInt(), fs = 14f) { sendKey("play") }
            ), android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

            val sc = ScrollView(act).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; addView(col, FrameLayout.LayoutParams(-1, -2)) }
            root.addView(sc, FrameLayout.LayoutParams(-1, -1))

            var connectFn: () -> Unit = {}

            d.setContentView(root)
            d.setOnDismissListener { hh.removeCallbacksAndMessages(null); try { onClose?.invoke() } catch (_: Throwable) {} }
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            d.show()

            connectFn = {
                Thread {
                    val ok = try { RemoteClient.lastTv() != null && RemoteClient.ensureConnected(act, 6000L) } catch (_: Throwable) { false }
                    act.runOnUiThread {
                        if (!d.isShowing) return@runOnUiThread
                        st.text = if (ok) "📺 متصل" else "⚠ مش متصل بالتلفزيون"
                        st.setTextColor(if (ok) 0xFF66BB6A.toInt() else 0xFFFFB74D.toInt())
                    }
                }.also { it.isDaemon = true }.start()
            }
            connectFn()
        } catch (e: Throwable) { LogStore.err("ControllerPad:show", e) }
    }
}


/** (v662) سلايدر صوت طويل عمودي: اسحب لفوق يعلّي ولتحت يوطّي. onChange(0..100) بتتنادى أثناء السحب (متخفّفة) وعند الرفع */
class VolumeSlider(ctx: Context, private val onChange: (Int) -> Unit) : View(ctx) {
    private val den = resources.displayMetrics.density
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x33FFFFFF }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF2F80FF.toInt() }
    private val pKnob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 13f * resources.displayMetrics.density }
    private val rect = android.graphics.RectF()
    private var value = 50
    var dragging = false; private set
    private var lastSent = 0L

    fun setValue(v: Int) { val n = v.coerceIn(0, 100); if (n != value) { value = n; invalidate() } }

    private fun top() = 34f * den + 6f * den       // فوق: مكان الأيقونة/الرقم
    private fun bot() = height - 10f * den
    private fun fromY(y: Float): Int {
        val t = top(); val b = bot()
        return Math.round(((b - y) / (b - t).coerceAtLeast(1f)).coerceIn(0f, 1f) * 100f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; apply(e.y, true); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            MotionEvent.ACTION_MOVE -> apply(e.y, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { apply(e.y, true); dragging = false; invalidate() }
        }
        return true
    }

    private fun apply(y: Float, force: Boolean) {
        val v = fromY(y)
        val changed = v != value
        value = v; invalidate()
        val now = SystemClock.uptimeMillis()
        if (force || (changed && now - lastSent >= 90L)) { lastSent = now; try { onChange(v) } catch (_: Throwable) {} }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val cx = w / 2f
        val tw = 16f * den; val t = top(); val b = bot()
        c.drawText(if (value == 0) "🔇" else "🔊", cx, 20f * den, pTxt)
        c.drawText(value.toString(), cx, 38f * den, pTxt)
        rect.set(cx - tw / 2, t, cx + tw / 2, b); c.drawRoundRect(rect, tw / 2, tw / 2, pTrack)
        val ky = b - (b - t) * value / 100f
        rect.set(cx - tw / 2, ky, cx + tw / 2, b); c.drawRoundRect(rect, tw / 2, tw / 2, pFill)
        c.drawCircle(cx, ky, if (dragging) 15f * den else 12f * den, pKnob)
    }
}


/** (v672) سلايدر صوت أفقي رفيع: اسحب يمين يعلّي وشمال يوطّي. onChange(0..100) متخفّفة أثناء السحب وعند الرفع */
class VolumeBar(ctx: Context, private val onChange: (Int) -> Unit) : View(ctx) {
    private val den = resources.displayMetrics.density
    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF14203A.toInt() }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = den; color = 0xFF2B3C60.toInt() }
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x33FFFFFF }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF2F80FF.toInt() }
    private val pKnob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 14f * den }
    private val rect = RectF()
    private var value = 50
    var dragging = false; private set
    private var lastSent = 0L

    fun setValue(v: Int) { val n = v.coerceIn(0, 100); if (n != value) { value = n; invalidate() } }

    private fun left() = 46f * den
    private fun right() = width - 50f * den
    private fun fromX(x: Float): Int {
        val l = left(); val r = right()
        return Math.round(((x - l) / (r - l).coerceAtLeast(1f)).coerceIn(0f, 1f) * 100f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; apply(e.x, true); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            MotionEvent.ACTION_MOVE -> apply(e.x, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { apply(e.x, true); dragging = false; invalidate() }
        }
        return true
    }

    private fun apply(x: Float, force: Boolean) {
        val v = fromX(x)
        val changed = v != value
        value = v; invalidate()
        val now = SystemClock.uptimeMillis()
        if (force || (changed && now - lastSent >= 90L)) { lastSent = now; try { onChange(v) } catch (_: Throwable) {} }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
        rect.set(den, den, w - den, h - den); c.drawRoundRect(rect, 22f * den, 22f * den, pBg); c.drawRoundRect(rect, 22f * den, 22f * den, pStroke)
        c.drawText(if (value == 0) "🔇" else "🔊", 24f * den, cy + 5f * den, pTxt)
        c.drawText(value.toString(), w - 25f * den, cy + 5f * den, pTxt)
        val l = left(); val r = right(); val th = 10f * den
        rect.set(l, cy - th / 2, r, cy + th / 2); c.drawRoundRect(rect, th / 2, th / 2, pTrack)
        val kx = l + (r - l) * value / 100f
        rect.set(l, cy - th / 2, kx, cy + th / 2); c.drawRoundRect(rect, th / 2, th / 2, pFill)
        c.drawCircle(kx, cy, if (dragging) 13f * den else 10f * den, pKnob)
    }
}
