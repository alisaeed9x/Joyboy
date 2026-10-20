package com.tttt.subtitler

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/** (v117) شريط البوابات السفلي: 🎞 الفيديوهات · 🎵 الموسيقى · 🌐 المتصفح (v189) · 📂 الملفات (v310) · ▶ يوتيوب (v324) — بيظهر في الشاشة الرئيسية وفي المتصفح والملفات
 *  (v324) «المهام» اتشالت من الشريط وبقت زرار عايم (TasksFab)؛ active = -1 يعني مفيش بوابة نشطة (صفحة المهام مفتوحة)
 *  (v312) مؤشّر بيتزحلق تحت البوابة النشطة + قفزة للأيقونة لما تتفعّل */
class BottomNav(act: Activity, private val ui: Ui, private val th: Theme, private var active: Int, private val onPick: (Int) -> Unit) {
    companion object { const val MORE = 8 }
    /** البوابات اللي ورا ⋯ المزيد: الملفات (3) · المهام (5) · الإعدادات (7) — الزرار بيتلوّن لما واحدة منهم تبقى هي النشطة */
    private fun cellOf(a: Int): Int = if (a == 3 || a == 5 || a == 7) MORE else a
    private val moreIds = intArrayOf(3, 5, 7)
    private fun moreSheet(act: Activity) {
        val d = android.app.Dialog(act)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(14))
            background = GradientDrawable().apply { setColor(th.card); cornerRadii = floatArrayOf(ui.dp(18).toFloat(), ui.dp(18).toFloat(), ui.dp(18).toFloat(), ui.dp(18).toFloat(), 0f, 0f, 0f, 0f) }
        }
        val items = listOf(3 to "📂  الملفات", 5 to "📋  المهام", 7 to "⚙️  الإعدادات")
        for ((id, label) in items) {
            val row = ui.text(label, 15f, if (id == active) th.primary else th.text, true).apply { setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) }
            Glass.pressable(row)
            row.setOnClickListener { d.dismiss(); onPick(id) }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setGravity(Gravity.BOTTOM); setLayout(-1, -2)
        }
        d.show()
    }

    private val icons = ArrayList<IconTextView>()
    private val labels = ArrayList<IconTextView>()
    private val cells = ArrayList<View>()
    private val badges = ArrayList<android.widget.TextView>()   // (v350) عدّاد أحمر فوق الأيقونة
    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private var rowV: View? = null
    private var lineV: View? = null
    private var tinted = false
    private var moving = false
    private val indicator = View(act).apply {
        background = GradientDrawable().apply { setColor(th.primary); cornerRadius = ui.dp(2).toFloat() }
    }

    init {
        val line = View(act).apply { setBackgroundColor(th.border) }
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(th.card)
        }
        // (v336) الأرقام ثابتة (0 فيديوهات · 1 موسيقى · 2 متصفح · 3 ملفات · 4 يوتيوب · 5 مهام) لكن ترتيب العرض: يوتيوب قبل الملفات، والمهام رجعت زرار في الشريط
        val defs = listOf("🎞" to "الفيديوهات", "🎵" to "الموسيقى", "🌐" to "المتصفح", "📂" to "الملفات", "▶" to "يوتيوب", "📋" to "المهام", "🤖" to "الذكاء", "⚙️" to "الإعدادات", "⋯" to "المزيد")
        val order = listOf(0, 1, 2, 4, 8)   // (v420) 5 أزرار: الملفات والمهام والإعدادات اتجمعوا في ⋯ المزيد (رقم 8)
        defs.forEachIndexed { idx, d ->
            val icon = IconTextView(act).apply { text = d.first; textSize = 16f; gravity = Gravity.CENTER; includeFontPadding = false }
            val lab = ui.text(d.second, 11f, th.muted, true)   // (v458) كانت 9f صغيرة قوي.apply { gravity = Gravity.CENTER; setSingleLine() }
            val cell = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, ui.dp(3), 0, ui.dp(2))
                val bdg = android.widget.TextView(act).apply {
                    textSize = 8.5f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; includeFontPadding = false
                    setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(ui.dp(3), 0, ui.dp(3), 0); minWidth = ui.dp(14)
                    background = GradientDrawable().apply { setColor(th.danger); cornerRadius = ui.dp(8).toFloat() }   // (v458) لون الثيم بدل أحمر ثابت
                    visibility = View.GONE
                }
                badges.add(bdg)
                val box = FrameLayout(act).apply {
                    addView(icon, FrameLayout.LayoutParams(ui.dp(24), ui.dp(20), Gravity.CENTER))
                    addView(bdg, FrameLayout.LayoutParams(-2, ui.dp(14), Gravity.TOP or Gravity.END))
                }
                addView(box, LinearLayout.LayoutParams(ui.dp(38), ui.dp(20)))
                addView(lab, LinearLayout.LayoutParams(-2, -2))
                Glass.pressable(this)
                setOnClickListener { if (idx == MORE) moreSheet(act) else onPick(idx) }
            }
            icons.add(icon); labels.add(lab); cells.add(cell)
        }
        for (id in order) row.addView(cells[id], LinearLayout.LayoutParams(0, ui.dp(56), 1f))   // (v458) شريط تنقّل بارتفاع قياسي 56dp
        val wrap = FrameLayout(act)
        wrap.addView(row, FrameLayout.LayoutParams(-1, -2))
        wrap.addView(indicator, FrameLayout.LayoutParams(ui.dp(22), ui.dp(2), Gravity.TOP or Gravity.START))
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!moving) place(false) }
        rowV = row; lineV = line
        view.addView(line, LinearLayout.LayoutParams(-1, ui.dp(1)))
        view.addView(wrap, LinearLayout.LayoutParams(-1, -2))
        paint()
        badge(4, YtNotify.badgeCount())
    }

    /** (v350) عدّاد أحمر فوق أيقونة البوابة؛ 0 = مخفي */
    fun badge(i: Int, n: Int) {
        val b = badges.getOrNull(i) ?: return
        b.text = if (n > 99) "99+" else n.toString()
        b.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    private fun place(animate: Boolean) {
        indicator.visibility = if (cellOf(active) in cells.indices) View.VISIBLE else View.INVISIBLE
        val c = cells.getOrNull(cellOf(active)) ?: return
        if (c.width == 0) return
        val x = (c.left + c.width / 2 - ui.dp(11)).toFloat()
        if (animate) {
            moving = true
            indicator.animate().cancel()
            indicator.animate().setStartDelay(0L).translationX(x).setDuration(300L).setInterpolator(OvershootInterpolator(1.4f)).withEndAction { moving = false }.start()
        } else {
            indicator.animate().cancel(); moving = false
            indicator.translationX = x
        }
    }

    fun set(i: Int) {
        val changed = cellOf(i) != cellOf(active)
        active = i; paint()
        if (changed) { place(true); icons.getOrNull(cellOf(i))?.let { Motion.bounce(it) } }
    }
    /** (v135) نص تحت أيقونة البوابة (بيتغيّر لـ «المهام (2)» لما فيه مهام شغّالة) */
    fun label(i: Int, t: String) { if (i in labels.indices) labels[i].text = t }
    /** (v198) لون الشريط وهو مشغّل الموسيقى مفتوح (بيتبع لون الغلاف)؛ null = رجّعه للثيم */
    fun tint(c: Int?) {
        tinted = c != null
        rowV?.setBackgroundColor(c ?: th.card)
        lineV?.setBackgroundColor(if (c != null) 0x22FFFFFF else th.border)
        paint()
    }
    private fun paint() {
        for (i in icons.indices) {
            val on = i == cellOf(active)
            val off = if (tinted) 0xAAFFFFFF.toInt() else th.muted
            icons[i].setTextColor(if (on) th.primary else off)
            labels[i].setTextColor(if (on) th.primary else off)
        }
        (indicator.background as? GradientDrawable)?.setColor(if (tinted) 0xFFFFFFFF.toInt() else th.primary)
    }
}
