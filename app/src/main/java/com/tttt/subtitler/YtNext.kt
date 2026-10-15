package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import java.util.Locale

/**
 * (v340) «التالي»: لوحة بتطلع من تحت (في مشغّل الفيديو بسحب لفوق، وفي مشغّل الموسيقى من زرار 📃 التالي) فيها الفيديوهات اللي بعد الحالي.
 * لو فتحت الفيديو من قايمة تشغيل → فيديوهات القايمة نفسها (والحالي متعلّم)، وغير كده → المقترحات (related) بتاعة الفيديو.
 * دوسة على أي واحد = يشتغل (فيديو أو صوت حسب المشغّل اللي إنت فيه).
 */
object YtNext {
    @Volatile private var ctxList: List<NpItem> = emptyList()
    @Volatile private var ctxName = ""

    /** YtProUi بتسجّل هنا قايمة التشغيل اللي الفيديو اتفتح منها (أو بتفضّيها لو اتفتح من حتة تانية) */
    fun setContext(items: List<NpItem>, name: String) { ctxList = items.filter { it.kind == 0 }; ctxName = name }
    fun clearContext() { ctxList = emptyList(); ctxName = "" }

    // (v341) اللي اتشغّل قريب — عشان «التالي» من المقترحات ما يلفّش في نفس الفيديوهات
    private val played = LinkedHashSet<String>()
    fun markPlayed(id: String) { synchronized(played) { played.add(id); if (played.size > 40) played.remove(played.first()) } }

    /** (v341) الفيديو اللي بعد [id]: من قايمة التشغيل لو جاي منها، وإلا أول مقترح ما اتشغّلش. بتتنادى من خيط خلفي */
    fun nextAfter(id: String): NpItem? {
        markPlayed(id)
        val c = ctxList
        val i = c.indexOfFirst { it.id == id }
        if (i >= 0 && i + 1 < c.size) return c[i + 1]
        YtNp.init()
        val rel = YtNp.related(id)
        val seen = synchronized(played) { played.toSet() }
        return rel.firstOrNull { it.id !in seen } ?: rel.firstOrNull { it.id != id }
    }

    private fun fmtDur(s: Long): String = if (s <= 0) "" else if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)

    /** (القايمة، العنوان) للفيديو [id] — بتتنادى من خيط خلفي */
    private fun fetch(id: String): Pair<List<NpItem>, String> {
        val c = ctxList
        if (c.any { it.id == id }) return c to ("📃 " + ctxName.ifBlank { "قايمة التشغيل" })
        YtNp.init()
        return YtNp.related(id) to "⏭ المقترحة بعد الفيديو ده"
    }

    fun show(act: Activity, ui: Ui, th: Theme, curId: String, onPick: (NpItem) -> Unit) {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        val status = ui.text("⏳ بجيب الفيديوهات…", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(24), 0, ui.dp(24)) }
        col.addView(status)
        val dlg: Dialog = ui.sheet(act, "⏭ التالي", listOf<View>(col))
        dlg.show()
        Thread {
            val r = try { fetch(curId) } catch (_: Throwable) { null }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed || !dlg.isShowing) return@runOnUiThread
                if (r == null || r.first.isEmpty()) { status.text = "ما لقيتش فيديوهات تالية — جرّب تاني"; return@runOnUiThread }
                col.removeAllViews()
                col.addView(ui.text(r.second, 12.5f, th.muted, true).apply { setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(6)) })
                for (v in r.first.take(60)) {
                    val on = v.id == curId
                    val row = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                        setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7))
                        background = ui.box(if (on) 0x445B9DFF else 0x14808080, if (on) 0xFF5B9DFF.toInt() else th.border, 12)
                        setOnClickListener { dlg.dismiss(); if (!on) onPick(v) }
                    }
                    val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
                    if (v.thumb.isNotBlank()) try { Glide.with(act).load(v.thumb).into(img) } catch (_: Throwable) {}
                    row.addView(img, LinearLayout.LayoutParams(ui.dp(112), ui.dp(63)))
                    val tc = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), 0, 0, 0) }
                    tc.addView(ui.text((if (on) "▶ " else "") + v.title, 13.5f, th.text, on).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    tc.addView(ui.text(listOf(v.channel, fmtDur(v.dur)).filter { x -> x.isNotBlank() }.joinToString(" · "), 11.5f, th.muted).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
                    row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
                    col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
