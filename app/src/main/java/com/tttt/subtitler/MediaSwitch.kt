package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** (v654) زرارين فوق صفحة «ميديا»: 🎞 فيديوهات (الأساسي) · 🎵 موسيقى — بيتبدّلوا من غير ما يرجعوا للشريط السفلي */
class MediaSwitch(private val act: Activity, private val ui: Ui, private val th: Theme, private val labels: List<String> = listOf("🎞  فيديوهات", "🎵  موسيقى"), private val sizeSp: Float = 14f, private val onPick: (Int) -> Unit) {
    val view = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)); setBackgroundColor(th.bg); visibility = View.GONE
    }
    /** (v922) الكبسولة الزجاجية اللي شايلة الزرارين (لو متفصلة عن الشريط) — ظهورها بيتبع ظهورهم */
    var host: View? = null
    private val tabs = ArrayList<IconTextView>()
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK

    init {
        val defs = labels.mapIndexed { i, l -> i to l }
        for ((tab, label) in defs) {
            val t = IconTextView(act).apply {
                text = label; textSize = sizeSp; gravity = Gravity.CENTER; maxLines = 1; isClickable = true; isFocusable = true
                setPadding(ui.dp(if (labels.size > 2) 4 else 12), ui.dp(9), ui.dp(if (labels.size > 2) 4 else 12), ui.dp(9))
                setOnClickListener { onPick(tab) }
            }
            tabs.add(t)
            view.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(if (labels.size > 2) 2 else 3), 0, ui.dp(if (labels.size > 2) 2 else 3), 0) })
        }
    }

    /** cur = 0 فيديوهات · 1 موسيقى · غير كده = الشريط مخفي */
    fun show(cur: Int) {
        view.visibility = if (cur >= 0 && cur < tabs.size) View.VISIBLE else View.GONE
        host?.visibility = view.visibility
        for ((i, t) in tabs.withIndex()) {
            val on = i == cur
            t.setTextColor(if (on) onPrimary else th.text)
            t.background = ui.box(if (on) th.primary else th.surface, if (on) th.primary else th.border, 18, 1)
        }
    }
}

/** (v654) سويب أفقي سريع بين فيديوهات ↔ موسيقى (لفّة لا نهائية: أي اتجاه بيبدّل). بيتفعّل بس لما enabledSwipe ترجع true */
class SwipeFrame(ctx: android.content.Context) : android.widget.FrameLayout(ctx) {
    var enabledSwipe: () -> Boolean = { false }
    var onSwipe: () -> Unit = {}
    private val d = ctx.resources.displayMetrics.density
    private var x0 = 0f
    private var y0 = 0f
    private var fired = false
    private var vt: android.view.VelocityTracker? = null

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!enabledSwipe()) return false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; fired = false; vt?.recycle(); vt = android.view.VelocityTracker.obtain(); vt?.addMovement(e) }
            android.view.MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!fired) {
                    val dx = e.x - x0; val dy = e.y - y0
                    if (Math.abs(dx) > 80f * d && Math.abs(dx) > 2.5f * Math.abs(dy)) {
                        vt?.computeCurrentVelocity(1000)
                        if (Math.abs(vt?.xVelocity ?: 0f) > 900f * d) { fired = true; onSwipe() }
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null }
        }
        return fired
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) { val f = fired; fired = false; return f }
        return fired
    }
}

/**
 * (v660) «المخزن» = تبويب واحد في الشريط السفلي بتبويبين فوق: 📂 الملفات (مدير الملفات) · 📡 نقل (نقل الملفات).
 * بيفتح آخر تبويب استخدمته. التبديل بين الاتنين من الشريط اللي فوق (بيقفل الشاشة الحالية ويفتح التانية من غير أنيميشن).
 */
object FilesHub {
    private const val K = "hub_last"
    fun intent(ctx: android.content.Context): android.content.Intent =
        android.content.Intent(ctx, if (Cfg.str(K, "fm") == "ft") FileTransferActivity::class.java else FileManagerActivity::class.java)

    /** cur = 0 ملفات · 1 إرسال واستقبال (الاتنين جوه المخزن) — 2 موسيقى · 3 مهام بيفتحوا صفحاتهم من نفس الشريط */
    fun switchBar(act: Activity, ui: Ui, th: Theme, cur: Int): MediaSwitch {
        Cfg.put(K, if (cur == 1) "ft" else "fm")
        val sw = MediaSwitch(act, ui, th, listOf("📂 الملفات", "📡 إرسال واستقبال", "🎵 الموسيقى", "📋 المهام"), 12.5f) { t ->
            if (t != cur) {
                try {
                    if (t >= 2) {
                        act.startActivity(android.content.Intent(act, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", if (t == 2) "music" else "tasks"))
                    } else {
                        act.startActivity(android.content.Intent(act, if (t == 1) FileTransferActivity::class.java else FileManagerActivity::class.java))
                    }
                    act.finish(); act.overridePendingTransition(0, 0)
                } catch (e: Throwable) { LogStore.err("FilesHub:switch", e) }
            }
        }
        sw.show(cur)
        return sw
    }

    /** (v855) فتح المخزن على تبويب معيّن من أي مكان (شبكة «المزيد»): fm ملفات · ft إرسال واستقبال؛ open = send/recv/bt جوه مركز الإرسال والاستقبال */
    fun open(ctx: android.content.Context, tab: String, open: String = ""): android.content.Intent {
        Cfg.put(K, if (tab == "ft") "ft" else "fm")
        val i = android.content.Intent(ctx, if (tab == "ft") FileTransferActivity::class.java else FileManagerActivity::class.java)
        if (open.isNotEmpty()) i.putExtra("ft_open", open)
        return i
    }
}
