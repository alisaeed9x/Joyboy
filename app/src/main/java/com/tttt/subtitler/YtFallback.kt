package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.bumptech.glide.Glide
import java.util.Locale

/**
 * (v344) لما البحث في مكتبة الجهاز (فيديو/موسيقى) ما يلاقيش حاجة: بنقترح نتايج من يوتيوب (دوسة = تشغيل)،
 * ولو يوتيوب كمان ماطلعش منه حاجة (أو مفيش نت) بنقترح «🌐 ابحث في المتصفح» (بيفتح المتصفح ويبحث).
 */
object YtFallback {
    private val cache = HashMap<String, List<NpItem>>()
    private fun fmtDur(s: Long): String = if (s <= 0) "" else if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)

    private fun msg(ui: Ui, th: Theme, t: String) = ui.text(t, 13.5f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(26), ui.dp(20), ui.dp(10)) }

    private fun browserBtn(ui: Ui, q: String, onBrowser: (String) -> Unit) = ui.text("🌐 ابحث في المتصفح عن «" + q.take(40) + "»", 14f, YtSkin.onAccent, true).apply {
        gravity = Gravity.CENTER; setPadding(ui.dp(18), ui.dp(13), ui.dp(18), ui.dp(13)); background = ui.box(YtSkin.accent, YtSkin.accent, 14)
        setOnClickListener { onBrowser(q) }
    }

    /** بيعرض «بدوّر…» فورًا */
    fun pending(ui: Ui, th: Theme, box: LinearLayout, q: String) {
        box.removeAllViews()
        box.addView(msg(ui, th, "🔎 مفيش نتايج على الجهاز لـ «" + q.take(40) + "»\n⏳ بدوّر في يوتيوب…"))
    }

    /** بيجيب نتايج يوتيوب ويملى [box]؛ [stale] = البحث اتغيّر/الشاشة اتقفلت فنتجاهل النتيجة */
    fun fill(act: Activity, ui: Ui, th: Theme, box: LinearLayout, q: String, stale: () -> Boolean, onPick: (NpItem) -> Unit, onBrowser: (String) -> Unit) {
        Thread {
            val hit = synchronized(cache) { cache[q] }
            val r: List<NpItem>? = hit ?: try { YtNp.searchFirst(q, "videos").items.filter { it.kind == 0 }.take(15) } catch (_: Throwable) { null }
            if (hit == null && !r.isNullOrEmpty()) synchronized(cache) { if (cache.size > 30) cache.clear(); cache[q] = r }
            act.runOnUiThread {
                if (stale() || act.isFinishing || act.isDestroyed) return@runOnUiThread
                box.removeAllViews()
                if (r.isNullOrEmpty()) {
                    box.addView(msg(ui, th, "مفيش نتايج على الجهاز ولا اقتراحات من يوتيوب لـ «" + q.take(40) + "»"))
                    box.addView(browserBtn(ui, q, onBrowser), LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6)) })
                    return@runOnUiThread
                }
                box.addView(ui.text("▶ مفيش نتايج على الجهاز — اقتراحات من يوتيوب", 12.5f, th.muted, true).apply { setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(8)) })
                for (v in r) {
                    val row = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                        setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7)); background = ui.box(0x14808080, th.border, 12)
                        setOnClickListener { onPick(v) }
                    }
                    val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
                    if (v.thumb.isNotBlank()) try { Glide.with(act).load(v.thumb).into(img) } catch (_: Throwable) {}
                    row.addView(img, LinearLayout.LayoutParams(ui.dp(112), ui.dp(63)))
                    val tc = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), 0, 0, 0) }
                    tc.addView(ui.text(v.title, 13.5f, th.text, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    tc.addView(ui.text(listOf(v.channel, fmtDur(v.dur)).filter { x -> x.isNotBlank() }.joinToString(" · "), 11.5f, th.muted).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
                    row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
                    box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
