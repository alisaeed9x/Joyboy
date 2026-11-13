package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray

/**
 * (v716) شاشة اختيار جودة التحميل — بتظهر في كل تحميل فيديو (يوتيوب · لينك الكليبورد · المتصفح · أي موقع فيه أكتر من جودة).
 * لو الجودة واحدة بس مفيش اختيار والتحميل بيبدأ على طول (المنادي بيقرر).
 */
object DlQuality {
    /** tag = أي حاجة المنادي عايز يرجع له (مثلًا الاصطياد اللي الجودة دي بتاعته) */
    class Opt(val label: String, val h: Int, val url: String, val audio: String?, val hls: Boolean = false, val tag: Any? = null)

    /** qlist بتاع يوتيوب: [{l,u,a,h}] → خيارات من الأعلى للأقل */
    fun fromQlist(json: String): List<Opt> {
        if (json.isBlank()) return emptyList()
        return try {
            val a = JSONArray(json); val out = ArrayList<Opt>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val h = o.optInt("h")
                if (h <= 0) continue
                out.add(Opt(o.optString("l").ifEmpty { h.toString() + "p" }, h, o.optString("u"), o.optString("a").ifEmpty { null }))
            }
            out.sortedByDescending { it.h }
        } catch (_: Throwable) { emptyList() }
    }

    /** onAudio != null يضيف زرار «🎧 صوت بس». لازم يتنادى على الـ UI thread */
    fun show(act: Activity, title: String, opts: List<Opt>, onAudio: (() -> Unit)? = null, onPick: (Opt) -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        val th = Themes.current(); val ui = Ui(act, th)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        var d: Dialog? = null
        box.addView(ui.text("اختار الجودة اللي عايز تحمّل بيها", 12f, th.muted).apply { setPadding(0, 0, 0, ui.dp(6)) })
        for ((i, o) in opts.withIndex()) box.addView(ui.button(o.label, i == 0) { d?.dismiss(); onPick(o) })
        if (onAudio != null) box.addView(ui.button("🎧 صوت بس") { d?.dismiss(); onAudio() })
        val dlg = ui.sheet(act, title, listOf<View>(box), false)
        d = dlg
        dlg.show()
    }
}
