package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import android.widget.TextView
import kotlin.math.abs
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

/** (v590) شاشة كنترولر التلفزيون (v603: الريموت الوحيد بعد مسح القديم): جويستيك بدايرة OK + ⚙ إعدادات البرنامج (فوق شمال) · 🏠 رئيسية (فوق يمين) · ↩ رجوع (تحت شمال) · 🎤 بحث صوتي (تحت يمين) · (v637) 🔉/🔊 صوت التلفزيون فوق في النص */
object ControllerPad {
    fun show(act: Activity, onClose: (() -> Unit)? = null) {
        try {
            try { RemoteClient.init(act) } catch (_: Throwable) {}
            val dm = act.resources.displayMetrics
            fun dp(v: Int) = (v * dm.density).toInt()
            val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val root = FrameLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; setBackgroundColor(0xF2101820.toInt()) }

            fun sendKey(k: String) { RemoteClient.key(k) }
            fun round(txt: String, desc: String, color: Int, gravity: Int, onClick: () -> Unit) {
                val tv = TextView(act).apply {
                    text = txt; textSize = 28f; setTextColor(Color.WHITE); this.gravity = Gravity.CENTER; includeFontPadding = false
                    contentDescription = desc; elevation = dp(6).toFloat()
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(2, 0x66FFFFFF) }
                    setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); try { onClick() } catch (e: Throwable) { LogStore.err("ControllerPad:btn", e) } }
                }
                root.addView(tv, FrameLayout.LayoutParams(dp(68), dp(68), gravity).apply { setMargins(dp(20), dp(40), dp(20), dp(40)) })
            }
            round("⚙", "إعدادات البرنامج على التلفزيون", 0xFF4A5568.toInt(), Gravity.TOP or Gravity.LEFT) { RemoteClient.cmd("open_settings") }
            round("🏠", "الصفحة الرئيسية", 0xFF2E9E5B.toInt(), Gravity.TOP or Gravity.RIGHT) { sendKey("home") }
            round("↩", "رجوع", 0xFF4A5568.toInt(), Gravity.BOTTOM or Gravity.LEFT) { sendKey("back") }
            round("🎤", "بحث صوتي", 0xFFE53935.toInt(), Gravity.BOTTOM or Gravity.RIGHT) { RemoteClient.cmd("mic") }

            // (v637) رفع/خفض صوت التلفزيون: فوق في النص (بين ⚙ و🏠)، والضغط المستمر بيكرّر الخطوة
            val volH = Handler(Looper.getMainLooper())
            val volRow = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            fun volBtn(txt: String, desc: String, c: String, first: Boolean) {
                val tv = TextView(act).apply {
                    text = txt; textSize = 22f; setTextColor(Color.WHITE); this.gravity = Gravity.CENTER; includeFontPadding = false
                    contentDescription = desc; elevation = dp(6).toFloat()
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF37474F.toInt()); setStroke(2, 0x66FFFFFF) }
                }
                val rep = object : Runnable { override fun run() { RemoteClient.cmd(c); volH.postDelayed(this, 230L) } }
                tv.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { v.isPressed = true; v.alpha = 0.7f; v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); RemoteClient.cmd(c); volH.postDelayed(rep, 420L) }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; v.alpha = 1f; volH.removeCallbacks(rep) }
                    }
                    true
                }
                volRow.addView(tv, android.widget.LinearLayout.LayoutParams(dp(54), dp(54)).apply { if (!first) leftMargin = dp(14) })
            }
            volBtn("🔉", "خفض الصوت", "vol_down", true)
            volBtn("🔊", "رفع الصوت", "vol_up", false)
            root.addView(volRow, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(92) })

            val size = min(dm.widthPixels - dp(40), dp(340))
            root.addView(JoystickView(act) { k -> sendKey(k) }, FrameLayout.LayoutParams(size, size, Gravity.CENTER))

            val st = TextView(act).apply { text = "📺 بتوصّل…"; textSize = 14f; setTextColor(0xFFB0BEC5.toInt()); gravity = Gravity.CENTER }
            root.addView(st, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(60) })
            var connectFn: () -> Unit = {}
            val close = TextView(act).apply {
                text = "✕ إغلاق"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(10))
                background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(20).toFloat() }
                setOnClickListener { d.dismiss() }
            }
            root.addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(52) })

            d.setContentView(root)
            d.setOnDismissListener { try { onClose?.invoke() } catch (_: Throwable) {} }
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
