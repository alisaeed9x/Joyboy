package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
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
import android.widget.LinearLayout
import android.widget.ImageView
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
class JoystickView(ctx: Context, private val onKey: (String) -> Unit, private val onLong: (() -> Unit)? = null) : View(ctx) {
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
    private var longDone = false   // (v679) ضغطة طويلة على دايرة OK = ميكروفون (مش OK)
    private val longRun = Runnable {
        if (down && !fired && dir == null && onLong != null) {
            longDone = true; Haptic.tap(this, true); invalidate()
            try { onLong.invoke() } catch (_: Throwable) {}
        }
    }

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
                fired = false; down = true; longDone = false
                h.removeCallbacks(longRun)
                if (onLong != null) h.postDelayed(longRun, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                update(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> update(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                val isOk = down && !fired && !longDone
                stop()
                if (isOk) { Haptic.tap(this); onKey("ok") }
            }
            MotionEvent.ACTION_CANCEL -> stop()
        }
        return true
    }

    private fun stop() {
        h.removeCallbacks(repeat); h.removeCallbacks(longRun); dir = null; kx = 0f; ky = 0f; strength = 0f; down = false; invalidate()
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
            Haptic.tap(this)
            onKey(d)
            h.postDelayed(repeat, 380L)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { h.removeCallbacks(repeat); h.removeCallbacks(longRun); super.onDetachedFromWindow() }

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
            "headphone" -> {   // (v811) مصدر الصوت
                rc.set(4f * u, 3f * u, 20f * u, 19f * u); cv.drawArc(rc, 180f, 180f, false, p)
                ln(4f, 11f, 4f, 14f); ln(20f, 11f, 20f, 14f)
                box(3f, 13f, 7.5f, 20f, 2f); box(16.5f, 13f, 21f, 20f, 2f)
            }
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
            "recent" -> {
                cv.drawCircle(12f * u, 12f * u, 8.5f * u, p)
                ln(12f, 7f, 12f, 12f); ln(12f, 12f, 15.6f, 14.2f)
            }
            "youtube" -> {
                box(2.5f, 5.5f, 21.5f, 18.5f, 4.5f)
                poly(true, 10f, 9f, 10f, 15f, 15.5f, 12f)
            }
            "globe" -> {
                cv.drawCircle(12f * u, 12f * u, 9f * u, p)
                rc.set(7.6f * u, 3f * u, 16.4f * u, 21f * u); cv.drawOval(rc, p)
                ln(3f, 12f, 21f, 12f)
            }
            "eye" -> { path.reset(); path.moveTo(2f * u, 12f * u); path.quadTo(12f * u, 3f * u, 22f * u, 12f * u); path.quadTo(12f * u, 21f * u, 2f * u, 12f * u); cv.drawPath(path, p); cv.drawCircle(12f * u, 12f * u, 3.2f * u, p) }
            "mic" -> { box(9f, 3f, 15f, 14f, 3f); poly(false, 5.5f, 11f, 5.5f, 12f); path.reset(); path.moveTo(5.5f * u, 11f * u); path.quadTo(5.5f * u, 18f * u, 12f * u, 18f * u); path.quadTo(18.5f * u, 18f * u, 18.5f * u, 11f * u); cv.drawPath(path, p); ln(12f, 18f, 12f, 21f) }
        }
        cv.restore()
    }
}

/** (v590) شاشة كنترولر التلفزيون — (v670) اتغيّر الترتيب والحجم عشان يبقى زي ريموت Morph TV:
 *  تبديل (أزرار الاتجاهات | لوحة اللمس) ← دايرة كبيرة فيها 4 أزرار اتجاهات + «موافق» في النص ← ⚙ الإعدادات عرض كامل ←
 *  صف (رجوع · الرئيسية · آخر المشغّل) ← صف (خفض الصوت · كتم · رفع الصوت) ← صف (تشغيل · إيقاف مؤقت · يوتيوب · متصفح). (v678: «آخر المشغّل» مكان «المداخل»، ويوتيوب/متصفح مكان قناة ±)
 *  «لوحة اللمس» = الجويستيك القديم بالظبط. الشاشة بتتسحب (ScrollView) والإغلاق بزرار/إيماءة الرجوع. */
object ControllerPad {
    fun show(act: Activity, onClose: (() -> Unit)? = null) {
        try {
            try { RemoteClient.init(act) } catch (_: Throwable) {}
            val dm = act.resources.displayMetrics
            fun dp(v: Int) = (v * dm.density).toInt()
            fun dpf(v: Float) = v * dm.density
            val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            var plDispose: (() -> Unit)? = null   // (v787) تنضيف صفحة المشغّل المدمجة عند قفل الريموت
            val root = FrameLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; setBackgroundColor(0xFF0F1830.toInt()) }
            val hh = Handler(Looper.getMainLooper())

            var stRef: TextView? = null
            val sayHide = Runnable { stRef?.visibility = View.GONE }
            fun say(t: String, c: Int = 0xFFB0BEC5.toInt()) { stRef?.let { it.text = t; it.setTextColor(c); it.visibility = View.VISIBLE; hh.removeCallbacks(sayHide); hh.postDelayed(sayHide, 3500L) } }
            // (v809) سويتش واي فاي 📶 / IR 📡: على IR أزرار التنقل والرجوع بتتبعت بالبلاستر بدل الواي فاي
            var irKind = if (IrTv.available(act)) IrTv.irKind(act) else ""   // (v810) "" واي فاي · "tv" IR تلفزيون · "sat" IR ساتلايت
            var irOn = irKind.isNotEmpty()
            fun irKey(k: String): String? = when (k) { "up" -> "UP"; "down" -> "DOWN"; "left" -> "LEFT"; "right" -> "RIGHT"; "ok", "ok_long" -> "OK"; "back" -> "BACK"; "home" -> "HOME"; "menu" -> "MENU"; else -> null }
            fun sendKey(k: String) {
                if (irOn) {
                    val ik = irKey(k)
                    if (ik != null) { if (!IrTv.press(act, ik)) say("⚠ 📡 الزرار ده مش في ريموت IR المختار (اضغط مطوّلًا على 📺/🛰 تغيّر الريموت)", 0xFFFFB74D.toInt()); return }
                }
                RemoteClient.key(k)
            }
            var voice: PadVoice? = null
            /** (v679) بحث صوتي: التسجيل بيبدأ على الموبايل فورًا (مش بيستنى التلفزيون يفتح)، والنص بيتبعت للتلفزيون يبحث بيه */
            fun startVoice() {
                voice?.stop()
                voice = PadVoice(act, { t -> say(t) }) { q ->
                    say("📺 بحث: $q", 0xFF66BB6A.toInt())
                    Thread {
                        val ok = try { RemoteClient.ensureConnected(act.applicationContext, 6000L) && RemoteClient.postJson("/type", org.json.JSONObject().put("text", q).put("search", true)).first == 200 } catch (_: Throwable) { false }
                        act.runOnUiThread { if (!ok) say("⚠ ما قدرتش أبعت البحث للتلفزيون", 0xFFFFB74D.toInt()) }
                    }.also { it.isDaemon = true }.start()
                }.also { it.start() }
            }
            fun safe(tag: String, fn: () -> Unit) { try { fn() } catch (e: Throwable) { LogStore.err(tag, e) } }

            /** دوسة عادية، أو (repeat=true) دوسة بتتكرر وإنت ماسك */
            fun bind(v: View, repeat: Boolean, fire: () -> Unit) {
                if (!repeat) {
                    v.setOnClickListener { Haptic.tap(v); safe("ControllerPad:btn", fire) }
                    return
                }
                var rep: Runnable? = null
                v.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.alpha = 0.7f; Haptic.tap(v)
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
            fun iconBox(kind: String, c1: Int, c2: Int, dim: Boolean, ic: Int = 40): View {
                val box = FrameLayout(act).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(15f) }
                    if (dim) alpha = 0.6f
                }
                box.addView(PadIcon(act, kind, if (dim) 0xFFC9CFE6.toInt() else Color.WHITE), FrameLayout.LayoutParams(dp((ic * 0.6f).toInt()), dp((ic * 0.6f).toInt()), Gravity.CENTER))
                return box
            }
            /** كارت: مربع أيقونة ملوّن 40dp + الاسم تحته (زي الصورة) */
            fun card(label: String, kind: String, c1: Int, c2: Int, dashed: Boolean = false, repeat: Boolean = false, fs: Float = 15f, ic: Int = 40, fire: () -> Unit): View {
                val cd = android.widget.LinearLayout(act).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = cardBg(dashed)
                    contentDescription = label; setPadding(dp(2), if (ic < 40) dp(4) else dp(8), dp(2), if (ic < 40) dp(4) else dp(8))
                }
                cd.addView(iconBox(kind, c1, c2, dashed, ic), android.widget.LinearLayout.LayoutParams(dp(ic), dp(ic)))
                val tv = TextView(act).apply {
                    text = label; textSize = fs; setTextColor(if (dashed) 0xFF8FA0BC.toInt() else Color.WHITE); gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD; includeFontPadding = false; maxLines = if (ic < 40) 2 else 1
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                cd.addView(tv, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (ic < 40) dp(3) else dp(8) })
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
                setPadding(dp(10), dp(8), dp(10), dp(10))
            }

            // ── (v680) شريط الحالة اتشال من فوق — كبسولة عايمة بتظهر مع الصوت/البحث الصوتي وبتختفي لوحدها ──
            val st = TextView(act).apply {
                textSize = 13f; setTextColor(0xFFB0BEC5.toInt()); gravity = Gravity.CENTER; visibility = View.GONE
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(16f); setColor(0xE61E2F4D.toInt()) }
            }
            stRef = st

            // ── (v787) صفحة واحدة بشكل الصورة: يمين = الدايرة + الإعدادات + الصوت + تشغيل · شمال = الوصول السريع + الترجمة + التابات · تحت = المشغّل ──
            // (v797) كل المجموعة (الدايرة + 4 أزرار حواليها) في نص الشاشة بالظبط، والصوت متماثل على الجنبين:
            //        شمال = رفع/كتم/خفض (عمودي) · يمين = سلايدر الصوت العمودي
            val inner = dm.widthPixels - dp(20)
            val gapP = dp(6)
            val stripW = dp(46)
            // (v799) الدايرة بقت أكبر بكتير: مساحتها = الشاشة كلها ناقص عمود الصوت (يمين) — والمقاس الفعلي بيتظبط بعد الرسم على المساحة الفاضية (fitPad)
            val availG = inner - 2 * (stripW + gapP)   // (v817) عمود السطوع شمال + عمود الصوت يمين
            val s0 = minOf(availG, (dm.heightPixels - dp(540)).coerceAtLeast(dp(170)))
            val cornerW = (s0 * 0.22f).toInt().coerceAtLeast(dp(48))
            val padFull = dp(259)
            val padSize = (s0 * 0.78f).toInt().coerceAtLeast(dp(110))
            val padK = padSize.toFloat() / padFull.toFloat()   // الدايرة بتتصغّر على قد المساحة

            // ── التبديل: أزرار الاتجاهات | لوحة اللمس ──
            val sw = android.widget.LinearLayout(act).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(20f); setColor(0xFF1E2F4D.toInt()); setStroke(dp(1), 0xFF34507A.toInt()) }
            }
            fun tab(txt: String) = TextView(act).apply {
                text = txt; textSize = 13.5f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD; maxLines = 1
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            val tabTouch = tab("لوحة اللمس"); val tabDpad = tab("أزرار الاتجاهات")
            sw.addView(tabTouch, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
            sw.addView(tabDpad, android.widget.LinearLayout.LayoutParams(0, -1, 1f))

            // ── الدايرة (أزرار 4 + موافق) أو الجويستيك (لوحة اللمس) ──
            val padBox = FrameLayout(act)
            padBox.clipChildren = false
            val dpad = FrameLayout(act).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF0F3441.toInt()); setStroke(dp(2), 0xFF2CC4D2.toInt()) }
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
            okB.contentDescription = "موافق"
            okB.addView(PadIcon(act, "check", 0xFF8EEBFF.toInt()), android.widget.LinearLayout.LayoutParams(dp(26), dp(26)))
            okB.addView(TextView(act).apply { text = "موافق"; textSize = 20f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; includeFontPadding = false },
                android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            bind(okB, false) { sendKey("ok") }
            // (v807) دوسة = موافق · ضغطة طويلة = ضغطة طويلة على OK في التلفزيون نفسه (مش ميكروفون)
            okB.setOnLongClickListener { Haptic.tap(okB, true); sendKey("ok_long"); true }
            dpad.addView(okB, FrameLayout.LayoutParams(dp(69), dp(69), Gravity.CENTER))

            val joy = JoystickView(act, { k -> sendKey(k) }, { startVoice() })
            dpad.scaleX = padK; dpad.scaleY = padK
            padBox.addView(dpad, FrameLayout.LayoutParams(padFull, padFull, Gravity.CENTER))
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
            tabDpad.setOnClickListener { Haptic.tap(it); setMode(true) }
            tabTouch.setOnClickListener { Haptic.tap(it); setMode(false) }
            setMode(true)

            // ── (v672) سلايدر الصوت بالعرض — (v678) اسحب شمال = أعلى، يمين = أوطى ──
            val volBar = VolumeBar(act) { p -> RemoteClient.setVolume(p) }
            val volSync = object : Runnable {   // لو الصوت اتغيّر من ريموت التلفزيون نفسه: السلايدر يلحق (إلا وإنت ماسكه)
                override fun run() {
                    val v = RemoteClient.tvVol
                    if (v >= 0 && !volBar.dragging) volBar.setValue(v)
                    hh.postDelayed(this, 1500L)
                }
            }
            hh.post(volSync)

            fun cardS(label: String, kind: String, c1: Int, c2: Int, repeat: Boolean = false, fs: Float = 10.5f, ic: Int = 26, fire: () -> Unit): View =
                card(label, kind, c1, c2, false, repeat, fs, ic, fire)
            fun rowC(h: Int, gp: Int, vararg cells: View): View {
                val r = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
                cells.forEachIndexed { i, c -> r.addView(c, android.widget.LinearLayout.LayoutParams(0, h, 1f).apply { if (i > 0) leftMargin = gp }) }
                return r
            }

            // ── (v797) الدايرة في نص الشاشة + 4 أزرار في الأركان + صوت متماثل (شمال أزرار · يمين سلايدر) ──
            fun miniBtn(kind: String, c1: Int, c2: Int, desc: String, repeat: Boolean, fire: () -> Unit): View {
                val bx = FrameLayout(act).apply { background = cardBg(false); contentDescription = desc }
                bx.addView(iconBox(kind, c1, c2, false, 32), FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
                bind(bx, repeat, fire)
                return bx
            }
            // (v799) الدايرة + الأربع أزرار حواليها = مربع واحد بيتحط في نص المساحة الفاضية تحت وبياخدها كلها (مقاسه بيتظبط في fitPad)
            val padHolder = FrameLayout(act).apply { clipChildren = false }
            val padWrap = FrameLayout(act).apply { clipChildren = false }
            val cBack = cardS("رجوع", "back", 0xFF2563EB.toInt(), 0xFF5B8DEF.toInt(), false, 10.5f, 22) { sendKey("back") }
            val cHome = cardS("الرئيسية", "home", 0xFF0FA3B1.toInt(), 0xFF0B7285.toInt(), false, 10.5f, 22) { sendKey("home") }
            val cGear = cardS("بحث صوتي", "mic", 0xFFF59E0B.toInt(), 0xFFD97706.toInt(), false, 10.5f, 22) { startVoice() }   // (v807) مكان «إعدادات التلفزيون»
            val cRecent = cardS("آخر المشغّل", "recent", 0xFF7C4DBB.toInt(), 0xFF5B3A9B.toInt(), false, 10.5f, 22) { RecentPicker.show(act) }
            padWrap.addView(padBox, FrameLayout.LayoutParams(padSize, padSize, Gravity.CENTER))
            padWrap.addView(cBack, FrameLayout.LayoutParams(cornerW, cornerW, Gravity.BOTTOM or Gravity.RIGHT))
            padWrap.addView(cHome, FrameLayout.LayoutParams(cornerW, cornerW, Gravity.BOTTOM or Gravity.LEFT))
            padWrap.addView(cGear, FrameLayout.LayoutParams(cornerW, cornerW, Gravity.TOP or Gravity.RIGHT))
            padWrap.addView(cRecent, FrameLayout.LayoutParams(cornerW, cornerW, Gravity.TOP or Gravity.LEFT))
            padHolder.addView(padWrap, FrameLayout.LayoutParams(s0, s0, Gravity.CENTER))
            var lastFit = 0
            fun fitPad(w: Int, h: Int) {
                val sq = minOf(w, h)
                if (sq <= dp(120) || sq == lastFit) return
                lastFit = sq
                val cwN = (sq * 0.22f).toInt()          // الأزرار الأربعة في أركان المربع
                val dN = (sq * 0.78f).toInt()           // الدايرة: بتدخل بين الأركان من غير ما تتغطّى
                (padWrap.layoutParams as FrameLayout.LayoutParams).apply { width = sq; height = sq }
                (padBox.layoutParams as FrameLayout.LayoutParams).apply { width = dN; height = dN }
                for (v0 in listOf(cBack, cHome, cGear, cRecent)) (v0.layoutParams as FrameLayout.LayoutParams).apply { width = cwN; height = cwN }
                (joy.layoutParams as FrameLayout.LayoutParams).apply { width = dN; height = dN }
                dpad.scaleX = dN.toFloat() / padFull.toFloat(); dpad.scaleY = dpad.scaleX
                padWrap.requestLayout()
            }
            padHolder.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> fitPad(r - l, b - t) }

            // (v799) سلايدر الصوت العمودي على اليمين: بيبدأ من تحت زرار ⛶ (تكبير الشاشة) ويكمّل بطول المساحة لحد الكتم
            volBar.vertical = true
            val muteH = dp(44)
            val volCol = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            volCol.addView(volBar, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            volCol.addView(miniBtn("headphone", 0xFF0EA5E9.toInt(), 0xFF2563EB.toInt(), "مصدر الصوت", false) { AudioBridge.showMenu(act) }, android.widget.LinearLayout.LayoutParams(-1, muteH).apply { topMargin = gapP })   // (v811) مصدر الصوت: تلفزيون / موبايل
            volCol.addView(miniBtn("mute", 0xFFE8456F.toInt(), 0xFFBE185D.toInt(), "كتم الصوت", false) { if (irOn) IrTv.press(act, "MUTE") else RemoteClient.cmd("mute") }, android.widget.LinearLayout.LayoutParams(-1, muteH).apply { topMargin = gapP })

            // (v817) سلايدر السطوع العمودي على الشمال (مقابل سلايدر الصوت): بيغيّر سطوع التلفزيون الحقيقي. تحته: 🌙 تعتيم برمجي إضافي · ☀ أقصى سطوع
            val briBar = VolumeBar(act) { p -> RemoteClient.setBrightness(p) }
            briBar.vertical = true; briBar.icon = "🔆"; briBar.zeroIcon = "0"; briBar.setFillColor(0xFFF59E0B.toInt())
            val briSync = object : Runnable {   // لو السطوع اتغيّر من التلفزيون نفسه: السلايدر يلحق (إلا وإنت ماسكه)
                override fun run() {
                    val v = RemoteClient.tvBri
                    if (v >= 0 && !briBar.dragging) briBar.setValue(v)
                    hh.postDelayed(this, 1500L)
                }
            }
            hh.post(briSync)
            var briWarned = false
            // الأمر اتبعت بس التلفزيون مارد بـ«none» = مفيش إذن: نبّه مرة واحدة على الموبايل
            val briWatch = object : Runnable {
                override fun run() {
                    if (!briWarned && RemoteClient.tvBriMode == "none" && briBar.dragging.not() && RemoteClient.tvBri >= 0) {
                        briWarned = true
                        Notice.show(act, "⚠ التلفزيون مش واخد إذن «تعديل إعدادات النظام» — السطوع مش هيتغيّر. فعّله من إعدادات التلفزيون (أو بالـadb)", 6000L)
                    }
                    hh.postDelayed(this, 2000L)
                }
            }
            hh.postDelayed(briWatch, 4000L)
            fun txtMini(t: String, desc: String, fire: () -> Unit): View {
                val bx = FrameLayout(act).apply { background = cardBg(false); contentDescription = desc }
                bx.addView(TextView(act).apply { text = t; textSize = 20f; gravity = Gravity.CENTER; includeFontPadding = false }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                bind(bx, false, fire)
                return bx
            }
            val softBtn = txtMini("🌙", "تعتيم برمجي إضافي") {
                val on = !RemoteClient.tvBriSoft
                RemoteClient.tvBriSoft = on
                RemoteClient.cmd("bri_soft:" + (if (on) "1" else "0"))
                Notice.show(act, if (on) "🌙 التعتيم البرمجي اشتغل (فوق سطوع النظام)" else "🌙 التعتيم البرمجي اتقفل", 1800L)
            }
            val maxBtn = txtMini("☀", "أقصى سطوع") { briBar.setValue(100); RemoteClient.setBrightness(100) }
            val briCol = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            briCol.addView(briBar, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            briCol.addView(softBtn, android.widget.LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = gapP })
            briCol.addView(maxBtn, android.widget.LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = gapP })

            // ── باقي الأزرار (مكتبة/يوتيوب/متصفح/ترجمة/نص…): كلها فوق، والمنطقة اللي تحت للتنقل (الدايرة) + الصوت ──
            plDispose = PlayerPad.show(act, { d.dismiss() }) { P ->
                val eyeB = cardS("الوضع\nالبصري", "eye", 0xFF8B5CF6.toInt(), 0xFF6D28D9.toInt(), false, 9.5f, 20) { Thread { try { val n = RemoteClient.player("vsnap")?.optString("note", "") ?: ""; if (n.isNotEmpty()) act.runOnUiThread { Notice.show(act, n, 2200L) } } catch (_: Throwable) {} }.also { it.isDaemon = true }.start() }   // (v799) الرسالة على الموبايل بس
                val top = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
                top.addView(sw, android.widget.LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
                top.addView(P.logBtn, android.widget.LinearLayout.LayoutParams(dp(66), dp(46)))
                // ⛶ (تكبير الشاشة) آخر زرار في صف الترجمة: بعرض عمود الصوت بالظبط عشان السلايدر يكمّل تحته على نفس الخط
                (P.tr as? android.view.ViewGroup)?.let { g -> if (g.childCount > 0) g.getChildAt(g.childCount - 1).layoutParams = android.widget.LinearLayout.LayoutParams(stripW, dp(46)).apply { marginStart = dp(6) } }

                col.addView(P.netTop, android.widget.LinearLayout.LayoutParams(-1, dp(16)).apply { bottomMargin = dp(2) })   // (v810) عدّاد النت: سطر صغير لوحده فوق كل حاجة
                // (v809) سويتش فوق: 📶 واي فاي (الريموت الحالي) | 📡 IR (بلاستر الموبايل) + ⏻ باور بيظهر في وضع IR بس. ضغطة طويلة على 📡 = اختيار الماركة/الريموت
                run {
                    fun seg(t: String) = TextView(act).apply { text = t; textSize = 15f; gravity = Gravity.CENTER; includeFontPadding = false; typeface = android.graphics.Typeface.DEFAULT_BOLD }
                    val sWifi = seg("📶"); val sTv = seg("📺"); val sSat = seg("🛰")
                    val pill = android.widget.LinearLayout(act).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(dp(3), dp(3), dp(3), dp(3))
                        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(18f); setColor(0xFF1E2F4D.toInt()); setStroke(dp(1), 0xFF34507A.toInt()) }
                    }
                    pill.addView(sWifi, android.widget.LinearLayout.LayoutParams(dp(42), -1)); pill.addView(sTv, android.widget.LinearLayout.LayoutParams(dp(42), -1)); pill.addView(sSat, android.widget.LinearLayout.LayoutParams(dp(42), -1))
                    fun irBtn(t: String, fill: Int, desc: String, tap: () -> Unit) = TextView(act).apply {
                        text = t; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); includeFontPadding = false; typeface = android.graphics.Typeface.DEFAULT_BOLD
                        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(16f); setColor(fill); setStroke(dp(1), 0x66FFFFFF) }
                        contentDescription = desc
                        setOnClickListener { Haptic.tap(it); tap() }
                    }
                    val pwr = irBtn("⏻", 0xFFC62828.toInt(), "باور IR") { if (!IrTv.press(act, "POWER")) say("⚠ اختار الماركة/الريموت الأول (ضغطة طويلة على 📺 أو 🛰)", 0xFFFFB74D.toInt()) }
                    lateinit var paintFn: () -> Unit
                    // «الريموت الكامل»: صفحة بكل أزرار الريموت المختار؛ «رجوع» فيها بيرجّعك للريموت الأساسي (واي فاي)
                    val fullB = irBtn("🎛", 0xFF1E2F4D.toInt(), "الريموت الكامل") {
                        IrTv.show(act, irKind) { irKind = ""; irOn = false; IrTv.setIrKind(act, ""); paintFn() }
                    }
                    fun paint() {
                        for ((v, on) in listOf(sWifi to (irKind == ""), sTv to (irKind == "tv"), sSat to (irKind == "sat"))) {
                            v.background = if (on) GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(15f); setColor(0xFF2563EB.toInt()) } else null
                            v.alpha = if (on) 1f else 0.6f
                        }
                        pwr.visibility = if (irOn) View.VISIBLE else View.GONE
                        fullB.visibility = if (irOn) View.VISIBLE else View.GONE
                    }
                    paintFn = { paint() }
                    fun pick(k: String) {
                        if (!IrTv.available(act)) { IrTv.show(act, k) { }; return }   // بيشرح إن مفيش بلاستر
                        irKind = k; irOn = true; IrTv.setIrKind(act, k); paint()
                        if (IrTv.current(act, k) == null) IrTv.show(act, k) { irKind = ""; irOn = false; IrTv.setIrKind(act, ""); paint() }   // الماركة مش معروفة: اختارها
                    }
                    sWifi.setOnClickListener { Haptic.tap(it); irKind = ""; irOn = false; IrTv.setIrKind(act, ""); paint() }
                    sWifi.setOnLongClickListener { Haptic.tap(it, true); PhoneHotspot.show(act); true }   // (v814) هوت سبوت للابتوب
                    sTv.setOnClickListener { Haptic.tap(it); pick("tv") }
                    sSat.setOnClickListener { Haptic.tap(it); pick("sat") }
                    sTv.setOnLongClickListener { Haptic.tap(it, true); IrTv.show(act, "tv") { irKind = ""; irOn = false; IrTv.setIrKind(act, ""); paint() }; true }
                    sSat.setOnLongClickListener { Haptic.tap(it, true); IrTv.show(act, "sat") { irKind = ""; irOn = false; IrTv.setIrKind(act, ""); paint() }; true }
                    paint()
                    val irRow = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER }
                    irRow.addView(pill, android.widget.LinearLayout.LayoutParams(-2, dp(36)))
                    irRow.addView(pwr, android.widget.LinearLayout.LayoutParams(dp(44), dp(36)).apply { leftMargin = dp(10) })
                    irRow.addView(fullB, android.widget.LinearLayout.LayoutParams(dp(44), dp(36)).apply { leftMargin = dp(6) })
                    col.addView(irRow, android.widget.LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(4) })
                }
                col.addView(top, android.widget.LinearLayout.LayoutParams(-1, dp(46)))
                col.addView(P.info, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                // (v806) 6 أزرار في صف واحد: تحكم ترجمة · ترجمة ظاهرة/مخفية · العرض (ترجمة فقط…) · النسخ · إضافة ترجمة · الوضع البصري
                run {
                    val six = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; isBaselineAligned = false }
                    val kids = ArrayList<View>()
                    (P.sub as? android.view.ViewGroup)?.let { g -> for (i in 0 until g.childCount) kids.add(g.getChildAt(i)); g.removeAllViews() }
                    val cells = ArrayList<View>(); cells.add(P.studio); cells.addAll(kids); cells.add(eyeB)
                    for ((i, c) in cells.withIndex()) {
                        (c as? TextView)?.apply { textSize = 9.5f; setPadding(dp(1), dp(3), dp(1), dp(3)) }
                        six.addView(c, android.widget.LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(3) })
                    }
                    col.addView(six, android.widget.LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(6) })
                }
                // (v804) رجّعنا صف المكتبة · يوتيوب · المتصفح · السجل تحت صف الوضع البصري
                col.addView(P.quick, android.widget.LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
                col.addView(P.tabs, android.widget.LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(5) })
                // (v800) التزامن/الذكاء/الأدوات: الدوسة بتفتح صفحة منبثقة صغيرة فيها أدوات التاب — مفيش مساحة فاضية تحت التابات
                run {
                    val sheetBox = FrameLayout(act).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadii = floatArrayOf(dpf(24f), dpf(24f), dpf(24f), dpf(24f), 0f, 0f, 0f, 0f); setColor(0xFF0F1830.toInt()); setStroke(dp(1), 0xFF2B3C60.toInt()) }
                        setPadding(dp(12), dp(14), dp(12), dp(12))
                    }
                    var sheet: Dialog? = null
                    fun openSheet() {
                        try {
                            if (sheet?.isShowing == true) return
                            (P.tabBody.parent as? android.view.ViewGroup)?.removeView(P.tabBody)
                            sheetBox.removeAllViews()
                            sheetBox.addView(P.tabBody, FrameLayout.LayoutParams(-1, -1))
                            val dlg = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
                            dlg.setContentView(sheetBox)
                            dlg.setCanceledOnTouchOutside(true)
                            dlg.window?.apply {
                                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); setGravity(Gravity.BOTTOM)
                                setLayout(-1, (dm.heightPixels * 0.55f).toInt()); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.45f)
                            }
                            dlg.setOnDismissListener { sheetBox.removeAllViews() }
                            sheet = dlg; dlg.show()
                        } catch (e: Throwable) { LogStore.err("ControllerPad:tabSheet", e) }
                    }
                    (P.tabs as? android.view.ViewGroup)?.let { g ->
                        for (k in 0 until g.childCount) g.getChildAt(k).setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_UP) hh.post { openSheet() }; false }   // false = الدوسة تكمّل وتختار التاب الأول
                    }
                }
                col.addView(P.tr, android.widget.LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })

                // تحت صف الترجمة: يسار = التشغيل + التكرار + الدايرة الكبيرة · يمين = سلايدر الصوت بطول المساحة
                val leftCol = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
                leftCol.addView(padHolder, android.widget.LinearLayout.LayoutParams(-1, dp(150), 1f).apply { topMargin = dp(6) })
                val lower = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
                lower.addView(briCol, android.widget.LinearLayout.LayoutParams(stripW, -1).apply { rightMargin = gapP; topMargin = dp(5) })   // (v817) سطوع التلفزيون
                lower.addView(leftCol, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
                lower.addView(volCol, android.widget.LinearLayout.LayoutParams(stripW, -1).apply { leftMargin = gapP; topMargin = dp(5) })
                col.addView(lower, android.widget.LinearLayout.LayoutParams(-1, dp(276), 1f))
                // (v804) أزرار التشغيل (⏮ ⏪ ▶ ⏩ ⏭ 1x) + (تدوير · تكرار · قائمة التشغيل) نزلوا فوق شريط التقدم بالظبط
                col.addView(P.transport, android.widget.LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(6) })
                col.addView(P.repeatRow, android.widget.LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(5) })
                col.addView(P.bar, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                col.addView(P.times, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }

            // (v680) الريموت ما بيسكرولش: لو المحتوى أطول من الشاشة بيتصغّر كله بنسبة تلائمها
            val sc = object : ScrollView(act) {
                override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
                override fun onTouchEvent(ev: MotionEvent?): Boolean = false
            }.apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; addView(col, FrameLayout.LayoutParams(-1, -2)) }
            root.addView(sc, FrameLayout.LayoutParams(-1, -1))
            root.addView(st, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(40) })
            root.post {
                try {
                    val avail = root.height.toFloat(); val need = col.height.toFloat()
                    if (avail > 0f && need > avail) {
                        val k = avail / need
                        col.pivotX = col.width / 2f; col.pivotY = 0f; col.scaleX = k; col.scaleY = k
                    }
                } catch (_: Throwable) {}
            }

            var connectFn: () -> Unit = {}

            d.setContentView(root)
            d.setOnDismissListener { try { voice?.stop() } catch (_: Throwable) {}; try { plDispose?.invoke() } catch (_: Throwable) {}; hh.removeCallbacksAndMessages(null); try { onClose?.invoke() } catch (_: Throwable) {} }
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            d.show()

            connectFn = {
                Thread {
                    val ok = try { RemoteClient.lastTv() != null && RemoteClient.ensureConnected(act, 6000L) } catch (_: Throwable) { false }
                    act.runOnUiThread {
                        if (!d.isShowing) return@runOnUiThread
                        // (v680) مفيش نص حالة اتصال فوق الريموت
                    }
                }.also { it.isDaemon = true }.start()
            }
            connectFn()
        } catch (e: Throwable) { LogStore.err("ControllerPad:show", e) }
    }

    /**
     * (v678) يوتيوب / المتصفح بتوع الموبايل: بتدوّر وتختار على الموبايل والفيديو بيتشغّل على التلفزيون.
     * لو وضع التلفزيون مش شغّال وفي تلفزيون متصل بنشغّله هنا (كل الإرسال في التطبيق بيعدّي على PlayTarget.enabled()).
     */
    fun openOnPhone(act: Activity, youtube: Boolean) {
        try {
            try { RemoteClient.init(act) } catch (_: Throwable) {}
            if (!PlayTarget.enabled()) {
                if (RemoteClient.lastTv() != null) { Cfg.put(PlayTarget.KEY, "1"); try { TvModeFab.refresh() } catch (_: Throwable) {} }
                else Notice.show(act, "📺 مفيش تلفزيون متصل — الفيديو هيتشغّل على الموبايل", 3600L)
            }
            val i = if (youtube) Intent(act, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "youtube")
                    else Intent(act, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            act.startActivity(i)
        } catch (e: Throwable) { LogStore.err("ControllerPad:openOnPhone", e) }
    }
}




/** (v672) سلايدر صوت أفقي رفيع — (v678) معكوس: اسحب شمال يعلّي ويمين يوطّي. onChange(0..100) متخفّفة أثناء السحب وعند الرفع */
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
    /** (v796) عمودي: فوق = أعلى صوت، تحت = صفر (بطول الشاشة) */
    var vertical = false
    /** (v817) نفس السلايدر بيتستخدم للسطوع: أيقونة تحت + لون الملى + أيقونة الصفر */
    var icon = "🔊"; var zeroIcon = "🔇"
    fun setFillColor(c: Int) { pFill.color = c; invalidate() }

    fun setValue(v: Int) { val n = v.coerceIn(0, 100); if (n != value) { value = n; invalidate() } }

    private fun left() = 46f * den
    private fun right() = width - 50f * den
    private fun fromX(x: Float): Int {   // (v678) الاتجاه معكوس: شمال = أعلى، يمين = أوطى
        val l = left(); val r = right()
        return Math.round((1f - ((x - l) / (r - l).coerceAtLeast(1f)).coerceIn(0f, 1f)) * 100f)
    }

    private fun fromY(y: Float): Int {
        val t = 36f * den; val b = (height - 28f * den).coerceAtLeast(t + 1f)
        return Math.round((1f - ((y - t) / (b - t)).coerceIn(0f, 1f)) * 100f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pos = if (vertical) e.y else e.x
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; apply(pos, true); Haptic.tap(this) }
            MotionEvent.ACTION_MOVE -> apply(pos, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { apply(pos, true); dragging = false; invalidate() }
        }
        return true
    }

    private fun apply(x: Float, force: Boolean) {
        val v = if (vertical) fromY(x) else fromX(x)
        val changed = v != value
        value = v; invalidate()
        val now = SystemClock.uptimeMillis()
        if (force || (changed && now - lastSent >= 90L)) { lastSent = now; try { onChange(v) } catch (_: Throwable) {} }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2f
        if (vertical) {
            rect.set(den, den, w - den, h - den); c.drawRoundRect(rect, 22f * den, 22f * den, pBg); c.drawRoundRect(rect, 22f * den, 22f * den, pStroke)
            c.drawText(if (value == 0) zeroIcon else value.toString(), w / 2f, 26f * den, pTxt)
            c.drawText(icon, w / 2f, h - 9f * den, pTxt)
            val t = 36f * den; val b = h - 28f * den; val th = 14f * den; val cx = w / 2f
            rect.set(cx - th / 2, t, cx + th / 2, b); c.drawRoundRect(rect, th / 2, th / 2, pTrack)
            val ky = t + (b - t) * (100 - value) / 100f
            rect.set(cx - th / 2, ky, cx + th / 2, b); c.drawRoundRect(rect, th / 2, th / 2, pFill)
            c.drawCircle(cx, ky, if (dragging) 17f * den else 14f * den, pKnob)
            return
        }
        rect.set(den, den, w - den, h - den); c.drawRoundRect(rect, 22f * den, 22f * den, pBg); c.drawRoundRect(rect, 22f * den, 22f * den, pStroke)
        c.drawText("🔊", 24f * den, cy + 5f * den, pTxt)   // (v678) طرف الصوت العالي = شمال
        c.drawText(if (value == 0) "🔇" else value.toString(), w - 25f * den, cy + 5f * den, pTxt)
        val l = left(); val r = right(); val th = 10f * den
        rect.set(l, cy - th / 2, r, cy + th / 2); c.drawRoundRect(rect, th / 2, th / 2, pTrack)
        val kx = l + (r - l) * (100 - value) / 100f
        rect.set(kx, cy - th / 2, r, cy + th / 2); c.drawRoundRect(rect, th / 2, th / 2, pFill)
        c.drawCircle(kx, cy, if (dragging) 13f * den else 10f * den, pKnob)
    }
}


/**
 * (v678) «آخر المشغّل»: قايمتين جنب بعض — آخر فيديوهات اتشغّلت على الموبايل (يمين) وآخر فيديوهات اتشغّلت على التلفزيون (شمال).
 * دوسة على أي فيديو منهم = يتشغّل على التلفزيون: فيديو الموبايل بيتبعت (ملف محلي ← التلفزيون يسحبه، لينك/يوتيوب ← يتفتح عليه)،
 * وفيديو التلفزيون بيتفتح عنده بنفس دوسة رئيسية التلفزيون (/recent/play).
 */
object RecentPicker {
    private class Item(val id: String, val title: String, val pos: Double, val dur: Double, val yt: Boolean, val local: Boolean, val rec: Recent?, val ytid: String = "", val onTv: Boolean = false)
    private val thumbPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    fun show(act: Activity) {
        try {
            try { RemoteClient.init(act) } catch (_: Throwable) {}
            val dm = act.resources.displayMetrics
            fun dp(v: Int) = (v * dm.density).toInt()
            fun dpf(v: Float) = v * dm.density
            val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                setBackgroundColor(0xFF0F1830.toInt()); setPadding(dp(12), dp(34), dp(12), dp(12))
            }
            // ── الراس: العنوان + ✕ ──
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val close = TextView(act).apply {
                text = "✕"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; contentDescription = "إغلاق"
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF1E2F4D.toInt()) }
                setOnClickListener { Haptic.tap(it); d.dismiss() }
            }
            head.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
            head.addView(TextView(act).apply {
                text = "🕘 آخر المشغّل"; textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD; layoutDirection = View.LAYOUT_DIRECTION_RTL
            }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(View(act), LinearLayout.LayoutParams(dp(40), dp(40)))   // توازن مع ✕
            root.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

            // ── العمودين (شمال: التلفزيون · يمين: الموبايل) ──
            val cols = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            fun column(title: String, color: Int): Triple<LinearLayout, LinearLayout, TextView> {
                val box = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(18f); setColor(0xFF14203A.toInt()); setStroke(dp(1), 0xFF2B3C60.toInt()) }
                    setPadding(dp(6), dp(8), dp(6), dp(6))
                }
                box.addView(TextView(act).apply {
                    text = title; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, dp(7), 0, dp(7))
                    background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(12f); setColor(color) }
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                val status = TextView(act).apply { textSize = 13f; setTextColor(0xFFB0BEC5.toInt()); gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
                val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                list.addView(status, LinearLayout.LayoutParams(-1, -2))
                val sv = ScrollView(act).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list, FrameLayout.LayoutParams(-1, -2)) }
                box.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
                return Triple(box, list, status)
            }
            val (tvBox, tvList, tvStatus) = column("📺 على التلفزيون", 0xFF2563EB.toInt())
            val (phBox, phList, phStatus) = column("📱 على موبايلي", 0xFF0F766E.toInt())
            cols.addView(tvBox, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(5) })
            cols.addView(phBox, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(5) })
            root.addView(cols, LinearLayout.LayoutParams(-1, 0, 1f))

            /** (v680) صورة مصغّرة لكل عنصر: يوتيوب ← من يوتيوب · ملف على الموبايل ← لقطة من الملف · لينك ← اللقطة المحفوظة · عنصر التلفزيون ← بتتجاب من التلفزيون */
            fun loadThumb(itm: Item, iv: ImageView) {
                try {
                    if (itm.ytid.isNotEmpty()) { com.bumptech.glide.Glide.with(act).load(LinkThumb.ytThumb(itm.ytid)).centerCrop().into(iv); return }
                    val rc = itm.rec
                    if (rc != null && rc.uri.isEmpty()) { LinkThumb.into(act, rc, iv); return }
                    thumbPool.execute {
                        var bmp: android.graphics.Bitmap? = null
                        try {
                            if (itm.onTv) {
                                val (c, b) = RemoteClient.getBytes("/recent/thumb?id=" + java.net.URLEncoder.encode(itm.id, "UTF-8"), 9000)
                                if (c == 200 && b.isNotEmpty()) bmp = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)
                            } else if (rc != null) {
                                val u = android.net.Uri.parse(rc.uri)
                                if (android.os.Build.VERSION.SDK_INT >= 29) { try { bmp = act.contentResolver.loadThumbnail(u, android.util.Size(320, 180), null) } catch (_: Throwable) {} }
                                if (bmp == null) {
                                    val mr = android.media.MediaMetadataRetriever()
                                    try { mr.setDataSource(act, u); bmp = mr.getFrameAtTime(3_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: mr.getFrameAtTime(0L) ?: mr.getFrameAtTime() } catch (_: Throwable) {} finally { try { mr.release() } catch (_: Throwable) {} }
                                }
                            }
                        } catch (_: Throwable) {}
                        val bb = bmp
                        if (bb != null) act.runOnUiThread { try { if (!act.isDestroyed) iv.setImageBitmap(bb) } catch (_: Throwable) {} }
                    }
                } catch (e: Throwable) { LogStore.err("RecentPicker:thumb", e) }
            }

            fun row(itm: Item, onTap: () -> Unit): View {
                val r = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(12f); setColor(0xFF1B2C47.toInt()) }
                    setPadding(dp(6), dp(6), dp(8), dp(6))
                    setOnClickListener { Haptic.tap(this); try { onTap() } catch (e: Throwable) { LogStore.err("RecentPicker:tap", e) } }
                }
                val iv = ImageView(act).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dpf(8f); setColor(0xFF0B1426.toInt()) }
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dpf(8f)) } }
                }
                r.addView(iv, LinearLayout.LayoutParams(dp(64), dp(40)).apply { marginStart = dp(8) })
                val tx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                val ic = if (itm.yt) "▶ " else if (itm.local) "🎞 " else "🌐 "
                tx.addView(TextView(act).apply { text = ic + itm.title; textSize = 12.5f; setTextColor(Color.WHITE); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = android.graphics.Typeface.DEFAULT_BOLD })
                val sub = if (itm.pos > 1.0) "وقف عند " + PlayerLogic.clock((itm.pos * 1000).toLong()) + (if (itm.dur > 1.0) " من " + PlayerLogic.clock((itm.dur * 1000).toLong()) else "") else ""
                if (sub.isNotEmpty()) tx.addView(TextView(act).apply { text = sub; textSize = 10.5f; setTextColor(0xFF8FA0BC.toInt()) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
                r.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
                val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                r.layoutParams = lp
                loadThumb(itm, iv)
                return r
            }

            // ── قايمة الموبايل: recent.json بتاع الموبايل نفسه ──
            val phone: List<Item> = try {
                Recents.merged(act, 60)
                    .map { Item(it.id, it.title.ifBlank { Recents.titleOf(it.id) }, it.posSec, it.durSec, Recents.ytIdOf(it) != null, it.uri.isNotEmpty(), it, Recents.ytIdOf(it) ?: "") }
            } catch (_: Throwable) { emptyList() }
            fun playPhone(r: Recent) {
                val yid = Recents.ytIdOf(r)
                val spec = when {
                    r.uri.isNotEmpty() -> MediaSpec(uri = r.uri, title = r.title)
                    yid != null -> MediaSpec(ytid = yid, title = r.title, how = Links.VIDEO)
                    else -> MediaSpec(url = r.url, title = r.title, startMs = (r.posSec * 1000).toLong(), how = Links.VIDEO)
                }
                if (PlayTarget.force(act, spec)) d.dismiss() else Notice.show(act, "📺 ما اتبعتش للتلفزيون", 3000L)
            }
            if (phone.isEmpty()) phStatus.text = "مفيش فيديوهات اتشغّلت على الموبايل لسه"
            else { phList.removeView(phStatus); phone.forEach { it0 -> phList.addView(row(it0) { it0.rec?.let { rr -> playPhone(rr) } }) } }

            d.setContentView(root)
            d.show()

            // ── قايمة التلفزيون: من التلفزيون نفسه (/recent) ──
            tvStatus.text = "⏳ بجيب من التلفزيون…"
            Thread {
                var items: List<Item>? = null; var err: String? = null
                try {
                    if (!RemoteClient.ensureConnected(act.applicationContext, 8000L)) err = "⚠ مش متصل بالتلفزيون"
                    else {
                        val (c, b) = RemoteClient.getText("/recent", 10000)
                        if (c == 200) {
                            val a = org.json.JSONObject(b).optJSONArray("items") ?: org.json.JSONArray()
                            items = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                                Item(it.optString("id"), it.optString("title"), it.optDouble("pos", 0.0), it.optDouble("dur", 0.0), it.optBoolean("yt"), it.optBoolean("local"), null, it.optString("ytid").ifEmpty { if (it.optString("id").startsWith("yt:")) it.optString("id").removePrefix("yt:") else "" }, true)
                            }.filter { it.id.isNotEmpty() }
                        } else err = if (c == 404) "⚠ التلفزيون محتاج التحديث الجديد عشان القايمة دي" else "⚠ فشل ($c)"
                    }
                } catch (e: Throwable) { LogStore.err("RecentPicker:tv", e); err = "⚠ حصلت مشكلة" }
                act.runOnUiThread {
                    if (!d.isShowing) return@runOnUiThread
                    val l = items
                    when {
                        l == null -> tvStatus.text = err ?: "⚠ حصلت مشكلة"
                        l.isEmpty() -> tvStatus.text = "مفيش فيديوهات اتشغّلت على التلفزيون لسه"
                        else -> {
                            tvList.removeView(tvStatus)
                            l.forEach { it0 ->
                                tvList.addView(row(it0) {
                                    Thread {
                                        val ok = try { RemoteClient.postJson("/recent/play", org.json.JSONObject().put("id", it0.id)).first == 200 } catch (_: Throwable) { false }
                                        act.runOnUiThread { if (ok) d.dismiss() else Notice.show(act, "⚠ ما قدرتش أشغّله على التلفزيون", 3000L) }
                                    }.also { t -> t.isDaemon = true }.start()
                                })
                            }
                        }
                    }
                }
            }.also { it.isDaemon = true }.start()

        } catch (e: Throwable) { LogStore.err("RecentPicker:show", e) }
    }
}


/**
 * (v679) تعرّف صوتي على الموبايل نفسه (SpeechRecognizer) لبحث التلفزيون.
 * المشكلة القديمة: الزرار كان بيبعت للتلفزيون يفتح التسجيل عنده، والتلفزيون بياخد وقت لحد ما يفتح، فالكلام بتاعك (على الموبايل) كان بيضيع والتسجيل بيتقفل.
 * دلوقتي التسجيل بيبدأ فورًا على الموبايل، ولما تخلّص كلام بيتبعت النص للتلفزيون (/type بـ search=true) فيفتح البحث بيه.
 * بنطوّل مهلة الصمت عشان التسجيل ما يتقفلش وإنت لسه بتفكّر.
 */
class PadVoice(private val act: Activity, private val onState: (String) -> Unit, private val onText: (String) -> Unit) {
    private var sr: android.speech.SpeechRecognizer? = null
    private var done = false

    fun start() {
        try {
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(act)) { onState("⚠ مفيش خدمة تعرّف على الصوت على الموبايل (محتاج تطبيق Google)"); return }
            if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try { act.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 92) } catch (_: Throwable) {}
                onState("🎤 اسمح بالميكروفون وبعدها اضغط OK ضغطة طويلة تاني"); return
            }
            val r = android.speech.SpeechRecognizer.createSpeechRecognizer(act); sr = r
            r.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: android.os.Bundle?) { onState("🎤 اتكلّم دلوقتي…") }
                override fun onBeginningOfSpeech() { onState("🎤 سامعك…") }
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { onState("⏳ بفهم الكلام…") }
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
                override fun onPartialResults(p: android.os.Bundle?) {
                    val t = p?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    if (!t.isNullOrEmpty() && !done) onState("🎤 $t")
                }
                override fun onError(e: Int) {
                    if (done) return
                    onState(if (e == android.speech.SpeechRecognizer.ERROR_NO_MATCH || e == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "⚠ ما سمعتش حاجة — اضغط OK ضغطة طويلة تاني" else "⚠ البحث الصوتي فشل (كود $e)")
                }
                override fun onResults(b: android.os.Bundle?) {
                    if (done) return
                    val q = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    if (q.isNullOrEmpty()) { onState("⚠ ما سمعتش حاجة — اضغط OK ضغطة طويلة تاني"); return }
                    done = true; onText(q)
                }
            })
            r.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            })
        } catch (e: Throwable) { LogStore.err("PadVoice:start", e); onState("⚠ البحث الصوتي مش متاح") }
    }

    fun stop() { done = true; try { sr?.cancel(); sr?.destroy() } catch (_: Throwable) {}; sr = null }
}
