package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * (v407) «🕘 سجل يوتيوب»: صفحة لوحدها لكل اللي اتفتح من يوتيوب (فيديو أو صوت بس) — منفصلة عن المصطادة من المتصفح.
 * بتتفتح من فولدر «المصطادة من الإنترنت» (شريحة 🕘 سجل يوتيوب) ومن الإعدادات ▸ يوتيوب. نفس البيانات في المكانين (yt_history.json).
 * دوسة = تشغيل (فيديو أو صوت زي ما اتفتح) · ⋮ = تشغيل فيديو / صوت بس / نسخ الرابط / شيل من السجل.
 */
object YtHistoryUi {
    private const val PAGE = 60

    fun show(act: Activity, ui: Ui, th: Theme, onVideo: (String, String) -> Unit, onAudio: (String, String, String) -> Unit) {
        val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(24)) }
        val countTv = ui.text("", 12f, th.muted)
        var kind = "الكل"
        var query = ""
        var limit = PAGE
        val dayFmt = SimpleDateFormat("d/M/yyyy", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        lateinit var render: () -> Unit

        fun dayLabel(ts: Long): String {
            val now = Calendar.getInstance()
            val c = Calendar.getInstance().apply { timeInMillis = ts }
            fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            if (sameDay(now, c)) return "اليوم"
            val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            if (sameDay(y, c)) return "أمس"
            return dayFmt.format(Date(ts))
        }

        fun copyLink(id: String) {
            try {
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", YtHistory.watchUrl(id)))
                Notice.show(act, "📋 اتنسخ الرابط", 1600L)
            } catch (_: Throwable) {}
        }

        fun rowOf(x: YtItem): View {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(ui.dp(8), ui.dp(7), ui.dp(4), ui.dp(7)); background = ui.box(th.card, th.border, 12)
            }
            val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
            try { Glide.with(act).load("https://i.ytimg.com/vi/" + x.id + "/mqdefault.jpg").centerCrop().override(ui.dp(224), ui.dp(126)).into(img) } catch (_: Throwable) {}
            row.addView(img, LinearLayout.LayoutParams(ui.dp(112), ui.dp(63)))
            val tc = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), 0, ui.dp(4), 0) }
            tc.addView(ui.text(x.title.ifBlank { x.id }, 13.5f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val meta = listOf(if (x.audio) "🎧 صوت" else "", x.chan, timeFmt.format(Date(x.ts))).filter { it.isNotBlank() }.joinToString(" · ")
            tc.addView(ui.text(meta, 11.5f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
            row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
            val dots = ui.text("⋮", 20f, th.muted, true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)) }
            row.addView(dots, LinearLayout.LayoutParams(-2, -2))
            fun playIt(audio: Boolean) {
                dlg.dismiss()
                if (audio) onAudio(x.id, x.title, x.chan) else onVideo(x.id, x.title)
            }
            fun menu(anchor: View) {
                val m = PopupMenu(act, anchor)
                m.menu.add(0, 1, 0, "▶ تشغيل فيديو"); m.menu.add(0, 2, 1, "🎧 تشغيل صوت بس")
                m.menu.add(0, 3, 2, "📋 نسخ الرابط"); m.menu.add(0, 4, 3, "🗑 شيل من السجل")
                m.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> playIt(false)
                        2 -> playIt(true)
                        3 -> copyLink(x.id)
                        4 -> { YtHistory.remove(act, x.id); render() }
                    }
                    true
                }
                m.show()
            }
            row.setOnClickListener { playIt(x.audio) }
            row.setOnLongClickListener { menu(dots); true }
            dots.setOnClickListener { menu(dots) }
            return row
        }

        render = {
            body.removeAllViews()
            val all = YtHistory.load(act)
            val q = query.trim().lowercase()
            val shown = all.filter { x ->
                (kind == "الكل" || (kind == "🎧 صوت" && x.audio) || (kind == "🎬 فيديو" && !x.audio)) &&
                    (q.isEmpty() || x.title.lowercase().contains(q) || x.chan.lowercase().contains(q))
            }
            countTv.text = if (all.isEmpty()) "" else if (shown.size == all.size) "${all.size} عنصر" else "${shown.size} من ${all.size}"
            if (shown.isEmpty()) {
                body.addView(ui.text(if (all.isEmpty()) "سجل يوتيوب فاضي.\nأي فيديو أو صوت تفتحه من تبويب يوتيوب بيتسجّل هنا لوحده." else "مفيش نتائج.", 13.5f, th.muted).apply {
                    gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(40), ui.dp(20), 0)
                })
            } else {
                var lastDay = ""
                for (x in shown.take(limit)) {
                    val d = dayLabel(x.ts)
                    if (d != lastDay) {
                        lastDay = d
                        body.addView(ui.text(d, 13f, th.primary, true).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(2)) }, LinearLayout.LayoutParams(-1, -2))
                    }
                    body.addView(rowOf(x), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(5) })
                }
                if (shown.size > limit) {
                    body.addView(ui.text("⬇ عرض المزيد (${shown.size - limit})", 14f, th.primary, true).apply {
                        gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(14)); background = ui.box(th.surface, th.border, 14)
                        setOnClickListener { limit += PAGE; render() }
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
                } else {
                    body.addView(ui.text("ضغطة طويلة على أي عنصر = قايمة (تشغيل · صوت · نسخ · شيل)", 11f, th.muted).apply { setPadding(0, ui.dp(12), 0, 0) })
                }
            }
        }

        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(14), ui.dp(8), ui.dp(4)) }
        val titleCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(ui.text("🕘 سجل يوتيوب", 19f, th.text, true))
        titleCol.addView(countTv)
        head.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ui.text("🗑 مسح الكل", 12f, th.muted, true).apply {
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            setOnClickListener {
                if (YtHistory.load(act).isEmpty()) return@setOnClickListener
                GAlert(act).setTitle("🗑 مسح سجل يوتيوب").setMessage("هيتمسح كل سجل المشاهدة اللي جاي من يوتيوب. (الفيديوهات المصطادة والترجمات المحفوظة مش هتتأثر)")
                    .setPositiveButton("مسح") { _, _ -> YtHistory.clear(act); PlayTarget.clearTvHistory(act, "yt"); render() }   // (v560) يمسح سجل يوتيوب على التلفزيون كمان.setNegativeButton("إلغاء", null).show()
            }
        })
        head.addView(ui.text("✕", 18f, th.text, true).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); setOnClickListener { dlg.dismiss() } })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        val search = EditText(act).apply {
            hint = "🔎 دوّر في السجل (اسم الفيديو أو القناة)"; setHintTextColor(th.muted); setTextColor(th.text); textSize = 14f
            setSingleLine(); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = GradientDrawable().apply { setColor(th.card); cornerRadius = ui.dp(14).toFloat(); setStroke(ui.dp(1), th.border) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; limit = PAGE; render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(2)) })

        val kinds = listOf("الكل", "🎬 فيديو", "🎧 صوت")
        val chips = ui.chips(kinds, { kind }) { n -> kind = n; limit = PAGE; render() }
        root.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), 0, ui.dp(8), 0); addView(chips) },
            LinearLayout.LayoutParams(-1, -2))

        root.addView(ScrollView(act).apply { addView(body, ViewGroup.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        dlg.setContentView(root)
        render()
        TvFocus.attachDialog(act, dlg, th.primary)   // (v480 م7)
        dlg.show()
    }
}
