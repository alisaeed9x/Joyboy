package com.tttt.subtitler

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** (v1258) جولة أول تشغيل: شاشة معتمة وسبوتلايت على كل زرار مهم مع شرحه. بتظهر مرة واحدة، وتتعاد من المزيد ← شرح الاستخدام. */
object Tour {
    class Step(val title: String, val text: String, val find: (Activity) -> View?)

    @Volatile private var running = false

    private fun visible(v: View): Boolean {
        if (!v.isShown || v.width <= 0 || v.height <= 0) return false
        val r = Rect()
        return v.getGlobalVisibleRect(r) && r.width() > 8 && r.height() > 8
    }

    private fun walk(v: View, f: (View) -> Unit) {
        f(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f)
    }

    fun byDesc(desc: String): (Activity) -> View? = { a ->
        var hit: View? = null
        walk(a.window.decorView) { v -> if (hit == null && v.contentDescription?.toString() == desc && visible(v)) hit = v }
        hit
    }

    fun byText(match: (String) -> Boolean, lowest: Boolean = false): (Activity) -> View? = { a ->
        var hit: View? = null; var best = -1
        walk(a.window.decorView) { v ->
            if (v is TextView && visible(v) && match(v.text?.toString()?.trim() ?: "")) {
                val loc = IntArray(2); v.getLocationInWindow(loc)
                val y = if (lowest) loc[1] else 0
                if (hit == null || y > best) { hit = v; best = y }
            }
        }
        hit
    }

    private fun mainSteps(): List<Step> = listOf(
        Step("الفيديوهات", "هنا فيديوهاتك على الموبايل. اختار فيديو وبعدها دوس «ترجم» وهيتترجم.", byText({ it == "الفيديوهات" }, true)),
        Step("يوتيوب", "يوتيوب جوه البرنامج. افتح أي فيديو وترجمه من نفس الشاشة.", byText({ it == "يوتيوب" }, true)),
        Step("الموسيقى", "مشغّل الأغاني والكلمات.", byText({ it == "الموسيقى" }, true)),
        Step("المتصفح", "متصفح جوه البرنامج. لو دوست على فيديو في موقع البرنامج يلقطه ويترجمه.", byText({ it == "المتصفح" }, true)),
        Step("المزيد", "كل باقي الميزات: ستريمر، بلاجن، مركز التحديث، الإعدادات، وشرح الاستخدام ده لو عايز تشوفه تاني.", byText({ it == "المزيد" }, true))
    )

    private fun playerSteps(): List<Step> = listOf(
        Step("ترجم", "دوس هنا تبدأ ترجمة الفيديو، أو تغيّر طريقة الترجمة وتوقفها وتكمّلها.", byText({ it.contains("ترجم") && it.endsWith("▾") || it.startsWith("✍ تفريغ") })),
        Step("إظهار/إخفاء الترجمة", "لو دوست هنا الترجمة تظهر أو تختفي من على الفيديو.", byDesc("إظهار/إخفاء الترجمة")),
        Step("ملف ترجمة", "دوس ➕ عشان تضيف ملف ترجمة جاهز من بره.", byDesc("إضافة ملف ترجمة من بره")),
        Step("ملفات الترجمة", "هنا كل ملفات الترجمة المتاحة للفيديو، اختار منها.", byDesc("ملفات الترجمة المتاحة")),
        Step("اللغة", "اختار اللغة اللي عايز الترجمة تطلع بيها.", byText({ it == "🌐" })),
        Step("الإعدادات", "إعدادات الترجمة: الأسلوب والدقة والسرعة.", byText({ it == "⚙" })),
        Step("التحميل", "نزّل الفيديو أو الترجمة على جهازك.", byText({ it == "⬇" })),
        Step("إيقاف مؤقت", "إيقاف الترجمة مؤقتًا وتكملها بعدين من غير ما تبدأ من الأول.", byDesc("إيقاف مؤقت للترجمة"))
    )

    fun maybeMain(a: Activity) {
        try {
            if (DeviceUi.isTv(a) || Cfg.bool("tour_main_done", false)) return
            a.window.decorView.postDelayed({ run(a, mainSteps(), "tour_main_done", 2) }, 2500L)
        } catch (_: Throwable) {}
    }

    fun maybePlayer(a: Activity) {
        try {
            if (DeviceUi.isTv(a) || Cfg.bool("tour_player_done", false)) return
            a.window.decorView.postDelayed({ run(a, playerSteps(), "tour_player_done", 3) }, 3500L)
        } catch (_: Throwable) {}
    }

    /** إعادة الجولة: بيصفّر العلامتين ويشغّل جولة الشاشة الرئيسية دلوقتي (جولة المشغّل بتظهر أول فيديو بعدها) */
    fun replay(a: Activity) {
        Cfg.put("tour_main_done", "0"); Cfg.put("tour_player_done", "0")
        a.window.decorView.postDelayed({ run(a, mainSteps(), "tour_main_done", 1) }, 450L)
    }

    private fun run(a: Activity, all: List<Step>, doneKey: String, minTargets: Int) {
        try {
            if (running || a.isFinishing || a.isDestroyed || Cfg.bool(doneKey, false)) return
            val found = all.mapNotNull { s -> s.find(a)?.let { s to it } }
            if (found.size < minTargets) return
            running = true
            show(a, found, doneKey)
        } catch (e: Throwable) { running = false; LogStore.err("Tour:run", e) }
    }

    private class Spot(a: Activity) : View(a) {
        var rect = RectF()
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f * a.resources.displayMetrics.density }
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
        override fun onDraw(c: Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            val r = 14f * resources.displayMetrics.density
            c.drawRoundRect(rect, r, r, clear)
            c.drawRoundRect(rect, r, r, ring)
        }
    }

    private fun show(a: Activity, steps: List<Pair<Step, View>>, doneKey: String) {
        val d = a.resources.displayMetrics.density
        val root = a.window.decorView as ViewGroup
        val host = FrameLayout(a).apply { isClickable = true; isFocusable = true; elevation = 60f * d }
        val spot = Spot(a)
        host.addView(spot, FrameLayout.LayoutParams(-1, -1))
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = (14 * d).toInt(); setPadding(p, p, p, p)
            background = GradientDrawable().apply { setColor(0xFF1E2127.toInt()); cornerRadius = 16 * d; setStroke((1 * d).toInt(), 0x55FFFFFF) }
        }
        val title = TextView(a).apply { setTextColor(Color.WHITE); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.START }
        val body = TextView(a).apply { setTextColor(0xFFDDDDDD.toInt()); textSize = 14f; gravity = Gravity.START; setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt()) }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val cnt = TextView(a).apply { setTextColor(0xFF999999.toInt()); textSize = 12f }
        val skip = TextView(a).apply { text = "تخطي"; setTextColor(0xFFBBBBBB.toInt()); textSize = 14f; setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt()) }
        val next = TextView(a).apply {
            setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            setPadding((18 * d).toInt(), (8 * d).toInt(), (18 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { setColor(0xFFE53935.toInt()); cornerRadius = 12 * d }
        }
        row.addView(cnt, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(skip); row.addView(next)
        card.addView(title); card.addView(body); card.addView(row)
        host.addView(card, FrameLayout.LayoutParams(-2, -2))
        var i = 0
        fun finish(done: Boolean) {
            try { root.removeView(host) } catch (_: Throwable) {}
            running = false
            if (done) Cfg.put(doneKey, "1")
        }
        fun paint() {
            val (s, v) = steps[i]
            val hl = IntArray(2); root.getLocationInWindow(hl)
            val loc = IntArray(2); v.getLocationInWindow(loc)
            val pad = 6 * d
            val l = loc[0] - hl[0] - pad; val t = loc[1] - hl[1] - pad
            spot.rect = RectF(l, t, l + v.width + 2 * pad, t + v.height + 2 * pad); spot.invalidate()
            title.text = s.title; body.text = s.text
            cnt.text = (i + 1).toString() + " / " + steps.size
            next.text = if (i == steps.size - 1) "تمام" else "التالي"
            val w = minOf(root.width - (32 * d).toInt(), (340 * d).toInt())
            card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply {
                width = w; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val ch = card.measuredHeight
                val below = spot.rect.bottom + 14 * d
                topMargin = if (below + ch < root.height - 20 * d) below.toInt() else maxOf((20 * d).toInt(), (spot.rect.top - ch - 14 * d).toInt())
            }
        }
        next.setOnClickListener { if (i >= steps.size - 1) finish(true) else { i++; paint() } }
        skip.setOnClickListener { finish(true) }
        root.addView(host, FrameLayout.LayoutParams(-1, -1))
        host.post { try { paint() } catch (e: Throwable) { LogStore.err("Tour:paint", e); finish(false) } }
    }
}
