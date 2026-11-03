package com.tttt.subtitler

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v717) لوحة مصمتة زرقا فوق مكان الفيديو في صفحة المتصفح (بعد ما يتلقط فيديو). عليها:
 * عمودين: الموبايل (▶ مشاهدة · 🌐 مشاهدة بالترجمة) | التلفزيون (▶ مشاهدة · 🌐 مشاهدة بالترجمة)، وتحتهم زرار «⬇ تحميل» عريض، و✕ صغير يقفلها.
 * onAct بيستلم: mw · mt · tw · tt · dl · close. الإحداثيات (place) بكسل نفس منطقة الصفحة. اللمس برّا اللوحة بيعدّي للصفحة.
 */
class VidMark(ctx: Context, private val onAct: (String) -> Unit) : FrameLayout(context) {
    companion object {
        /**
         * بيرجّع [يسار, فوق, عرض, ارتفاع, عرض-الشاشة-بوحدات-CSS] لأكبر فيديو ظاهر في الصفحة (الشغّال أو اللي فيه صورة أولًا)،
         * ولو مفيش <video> ظاهر: أكبر iframe بحجم مشغّل. أو null. الإحداثيات نسبة للشاشة المرئية (بعد الزووم).
         */
        const val RECT_JS = "(function(){try{" +
            "var vv=window.visualViewport,ox=vv?vv.offsetLeft:0,oy=vv?vv.offsetTop:0,vw=vv?vv.width:innerWidth;" +
            "function ok(e,mw,mh){var r=e.getBoundingClientRect();if(r.width<mw||r.height<mh)return null;" +
            "var cs=getComputedStyle(e);if(cs.display==='none'||cs.visibility==='hidden'||parseFloat(cs.opacity)===0)return null;" +
            "var w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0)),h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));" +
            "if(w*h<r.width*r.height*0.2)return null;return {r:r,a:w*h};}" +
            "var best=null,ba=0,vs=document.querySelectorAll('video');" +
            "for(var i=0;i<vs.length;i++){var v=vs[i],o=ok(v,120,70);if(!o)continue;" +
            "var sc=o.a*((v.videoWidth>0||v.readyState>0)?2:1)*(v.paused?1:1.5);if(sc>ba){ba=sc;best=o.r;}}" +
            "if(!best){var fs=document.querySelectorAll('iframe');for(var j=0;j<fs.length;j++){var o2=ok(fs[j],240,135);if(o2&&o2.a>ba){ba=o2.a;best=o2.r;}}}" +
            "if(!best)return null;return [best.left-ox,best.top-oy,best.width,best.height,vw];" +
            "}catch(e){return null}})()"
    }

    private val d = resources.displayMetrics.density
    private var showing = false
    private var lastL = -1f; private var lastT = -1f; private var lastR = -1f; private var lastB = -1f
    private val rows = ArrayList<LinearLayout>()
    private val panel = FrameLayout(context)

    private fun btn(label: String, fill: Int, fg: Int, act: String): TextView = TextView(context).apply {
        text = label; setTextColor(fg); textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; maxLines = 2
        background = GradientDrawable().apply { setColor(fill); cornerRadius = 10 * d }
        isClickable = true; isFocusable = true
        setOnClickListener { onAct(act) }
    }
    private fun row(vararg v: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        for (x in v) addView(x, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt()) })
        rows.add(this)
    }
    private fun head(t: String) = TextView(context).apply { text = t; setTextColor(Color.WHITE); textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }

    init {
        visibility = View.GONE
        val white = 0x40FFFFFF; val green = 0xFF34C759.toInt()
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt()) }
        col.addView(row(head("📱 الموبايل"), head("📺 التلفزيون")))
        col.addView(row(btn("▶ مشاهدة", white, Color.WHITE, "mw"), btn("▶ مشاهدة", white, Color.WHITE, "tw")))
        col.addView(row(btn("🌐 مشاهدة بالترجمة", green, Color.WHITE, "mt"), btn("🌐 مشاهدة بالترجمة", green, Color.WHITE, "tt")))
        col.addView(row(btn("⬇ تحميل", Color.WHITE, 0xFF1E6FE0.toInt(), "dl")))
        panel.addView(col, LayoutParams(-1, -1))
        panel.addView(TextView(context).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { onAct("close") }
        }, LayoutParams((30 * d).toInt(), (30 * d).toInt(), Gravity.TOP or Gravity.LEFT))
        panel.background = GradientDrawable().apply { setColor(0xF51E6FE0.toInt()); cornerRadius = 14 * d; setStroke((2 * d).toInt(), 0xCCFFFFFF.toInt()) }
        panel.isClickable = true; panel.elevation = 6 * d
        addView(panel, LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
    }

    /** بيحط اللوحة على مربع الفيديو (بكسل). لو الجزء الظاهر صغير أوي أو برّه الشاشة = بيخفي */
    fun place(l: Float, t: Float, w: Float, h: Float) {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val left = maxOf(l, 0f); val top = maxOf(t, 0f)
        val right = minOf(l + w, vw); val bottom = minOf(t + h, vh)
        if (right - left < 150f * d || bottom - top < 110f * d) { clear(); return }
        if (showing && Math.abs(lastL - left) < 1f && Math.abs(lastT - top) < 1f && Math.abs(lastR - right) < 1f && Math.abs(lastB - bottom) < 1f) return
        lastL = left; lastT = top; lastR = right; lastB = bottom
        val lp = panel.layoutParams as LayoutParams
        lp.leftMargin = left.toInt(); lp.topMargin = top.toInt(); lp.width = (right - left).toInt(); lp.height = (bottom - top).toInt()
        panel.layoutParams = lp
        // ارتفاع الصفوف حسب حجم المربع (4 صفوف)
        val rh = (((bottom - top) - 12f * d) / 4f).coerceIn(28f * d, 54f * d).toInt()
        for (r in rows) { val rl = r.layoutParams as LinearLayout.LayoutParams; if (rl.height != rh) { rl.height = rh; r.layoutParams = rl } }
        showing = true
        if (visibility != View.VISIBLE) visibility = View.VISIBLE
    }

    fun clear() {
        if (!showing && visibility == View.GONE) return
        showing = false; visibility = View.GONE
    }
}
