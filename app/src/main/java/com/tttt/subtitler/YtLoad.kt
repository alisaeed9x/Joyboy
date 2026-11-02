package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * (v339) صفحة «مشغّل الفيديو» اللي بتفتح فورًا لما تدوس على فيديو يوتيوب — بدل ما اللودينج يفضل في صفحة يوتيوب:
 * نفس شكل المشغّل (مربع فيديو أسود 16:9 فوق) ولودينج ويندوز جواه، واسم الفيديو تحته، لحد ما رابط الفيديو يتجاب
 * والمشغّل الحقيقي يفتح. الرجوع (أو ✕) بيلغي ويرجّعك لنفس المكان في يوتيوب.
 */
class YtLoadingPage(act: Activity, ui: Ui, th: Theme, private val onCancel: () -> Unit) {
    private val spin = WinSpinnerDrawable()
    private val titleTv = ui.text("", 16f, th.text, true).apply { gravity = Gravity.CENTER; maxLines = 3; setPadding(ui.dp(20), ui.dp(16), ui.dp(20), 0) }
    private val subTv = ui.text("⏳ بجيب الفيديو…", 13f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0) }
    val root = FrameLayout(act).apply { visibility = View.GONE; isClickable = true; setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_LTR }
    val visible: Boolean get() = root.visibility == View.VISIBLE

    init {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), 0) }
        val box = FrameLayout(act).apply {
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = ui.dp(14).toFloat() }
            clipToOutline = true
        }
        box.addView(View(act).apply { foreground = spin }, FrameLayout.LayoutParams(ui.dp(84), ui.dp(84), Gravity.CENTER))
        box.addView(ui.text("✕", 20f, Color.WHITE).apply { gravity = Gravity.CENTER; setOnClickListener { onCancel() } },
            FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.TOP or Gravity.START))
        // نسبة 16:9 زي مربع الفيديو في المشغّل الحقيقي
        box.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val w = r - l; val h = w * 9 / 16
            if (w > 0 && v.layoutParams.height != h) { v.layoutParams.height = h; v.post { v.requestLayout() } }
        }
        col.addView(box, LinearLayout.LayoutParams(-1, ui.dp(200)))
        col.addView(titleTv, LinearLayout.LayoutParams(-1, -2)); col.addView(subTv, LinearLayout.LayoutParams(-1, -2))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    fun show(title: String) {
        titleTv.text = title; titleTv.visibility = if (title.isBlank()) View.GONE else View.VISIBLE
        root.visibility = View.VISIBLE; spin.start()
    }
    fun hide() { root.visibility = View.GONE; spin.stop() }
}
