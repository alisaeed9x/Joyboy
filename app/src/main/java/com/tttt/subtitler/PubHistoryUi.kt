package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** (v349) شاشة «السجل العام»: تبويب فيديوهات / صوتيات، وكل تبويب مقسّم بالمصدر، وزرار مسح لكل قسم وللكل. onOpen(kind, src, ref, title) */
object PubHistoryUi {
    fun show(act: Activity, ui: Ui, th: Theme, onOpen: (String, String, String, String) -> Unit) {
        val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(24)) }
        var kind = "v"
        val fmt = SimpleDateFormat("d/M  HH:mm", Locale.US)
        val tabs = ArrayList<android.widget.TextView>()

        fun chip(tv: android.widget.TextView, on: Boolean) {
            tv.setTextColor(if (on) Color.WHITE else th.text)
            tv.background = GradientDrawable().apply { setColor(if (on) th.primary else th.surface); cornerRadius = ui.dp(16).toFloat() }
        }
        lateinit var render: () -> Unit

        fun confirm(title: String, msg: String, go: () -> Unit) {
            GAlert(act).setTitle(title).setMessage(msg).setPositiveButton("مسح") { _, _ -> go(); render() }.setNegativeButton("إلغاء", null).show()
        }

        render = {
            body.removeAllViews()
            tabs.forEachIndexed { i, t -> chip(t, (i == 0) == (kind == "v")) }
            val secs = if (kind == "v") listOf("browser" to "🌐 من المتصفح", "youtube" to "▶ من يوتيوب", "local" to "📂 من الجهاز")
                       else listOf("youtube" to "▶ من يوتيوب", "music" to "🎵 من مشغّل الموسيقى")
            var any = false
            for ((src, name) in secs) {
                val items = PubHistory.list(kind, src)
                val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(14), 0, ui.dp(4)) }
                head.addView(ui.text("$name (${items.size})", 15f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
                if (items.isNotEmpty()) head.addView(ui.text("🗑 مسح القسم", 12f, th.muted, true).apply {
                    setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
                    setOnClickListener { confirm("🗑 مسح القسم", "هتتمسح كل العناصر في «$name».") { PubHistory.clear(kind, src) } }
                })
                body.addView(head, LinearLayout.LayoutParams(-1, -2))
                if (items.isEmpty()) { body.addView(ui.text("فاضي", 12f, th.muted), LinearLayout.LayoutParams(-1, -2)); continue }
                any = true
                for (it in items) {
                    val row = LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                        background = GradientDrawable().apply { setColor(th.card); cornerRadius = ui.dp(10).toFloat() }
                    }
                    row.addView(ui.text(it.title, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    row.addView(ui.text(fmt.format(Date(it.ts)), 11f, th.muted))
                    val x = it
                    row.setOnClickListener { if (x.src != "music") { dlg.dismiss(); onOpen(x.kind, x.src, x.ref, x.title) } else Notice.show(act, "افتحها من صفحة الموسيقى", 1800L) }
                    row.setOnLongClickListener { PubHistory.remove(x); render(); true }
                    body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(5) })
                }
            }
            if (any) body.addView(ui.text("ضغطة طويلة على أي عنصر = شيله من السجل", 11f, th.muted).apply { setPadding(0, ui.dp(12), 0, 0) })
        }

        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(14), ui.dp(8), ui.dp(6)) }
        head.addView(ui.text("🕘 السجل العام", 19f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ui.text("🗑 مسح الكل", 12f, th.muted, true).apply {
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            setOnClickListener { confirm("🗑 مسح السجل العام", "هيتمسح سجل الفيديوهات والصوتيات كله.") { PubHistory.clearAll() } }
        })
        head.addView(ui.text("✕", 18f, th.text, true).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); setOnClickListener { dlg.dismiss() } })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        val tabRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(4)) }
        listOf("🎬 فيديوهات" to "v", "🎧 صوتيات" to "a").forEach { (n, k) ->
            val t = ui.text(n, 13f, th.text, true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)); setOnClickListener { kind = k; render() } }
            tabs.add(t); tabRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
        root.addView(tabRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(ScrollView(act).apply { addView(body, ViewGroup.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        dlg.setContentView(root)
        render()
        dlg.show()
    }
}
