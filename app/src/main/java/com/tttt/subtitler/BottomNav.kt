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
class BottomNav(act: Activity, private val ui: Ui, private val th: Theme, private var active: Int, private val rail: Boolean = false, private val onPick: (Int) -> Unit) {   // (v462) rail = شريط جانبي رأسي لوضع التابلت (زي يوتيوب التابلت)
    companion object { const val MORE = 8
        /** (v521) زرار «الريموت» (موبايل/تابلت بس) جنب الفيديوهات — بيفتح الكنترولر الجديد (ControllerPad) مباشرة ومش بوابة عادية */
        const val REMOTE = 12
        /** (v472) «الذكاء» تبويب اتمسح قديمًا — الرقم 6 محجوز عشان ترقيم البوابات ما يتغيّرش، لكن مش بيظهر في أي حته */
        const val DEAD_AI = 6
        /** (v467) ترتيب الشريط بطلب صاحب التطبيق: الفيديوهات ← يوتيوب ← الموسيقى ← المتصفح ← المزيد.
         *  أي بوابة مش في الشريط بتظهر تلقائيًا جوه «المزيد» — فأي إضافة جديدة هتبان من غير تعديل هنا. */
        val BAR_ORDER = listOf(0, REMOTE, 4, 1, 2, 8)
        /** (v475) خلايا يوتيوب الافتراضية (مش بوابات): الرئيسية 9 · الاشتراكات 10 · أنا 11 — بتحل محل خلية «يوتيوب» وانت جواه */
        const val YT_HOME = 9; const val YT_SUBS = 10; const val YT_ME = 11
        val NORMAL_ORDER = BAR_ORDER
        val YT_ORDER = listOf(0, YT_HOME, YT_SUBS, YT_ME, MORE)       // نسخة كاملة وانت في يوتيوب: الموسيقى والمتصفح بيروحوا لـ«المزيد»
        val LITE_ORDER = listOf(YT_HOME, YT_SUBS, 0, YT_ME)           // نسخة اللايت: الرئيسية · الاشتراكات · محلي · أنا
        val DEFS = listOf("🎞" to "الفيديوهات", "🎵" to "الموسيقى", "🌐" to "المتصفح", "📂" to "الملفات", "▶" to "يوتيوب", "📋" to "المهام", "" to "", "⚙️" to "الإعدادات", "⋯" to "المزيد",
            "🏠" to "الرئيسية", "📺" to "الاشتراكات", "👤" to "أنا", "📱" to "الريموت")
    }
    /** البوابات اللي ورا ⋯ المزيد: أي بوابة مش في الشريط (حاليًا: الملفات 3 · المهام 5 · الذكاء 6 · الإعدادات 7) — الزرار بيتلوّن لما واحدة منهم تبقى هي النشطة */
    private val lite = act.packageName.endsWith(".lite")
    private val tvDev = try { DeviceUi.isTv(act) } catch (_: Throwable) { false }   // (v521) على التلفزيون زرار الريموت مخفي
    private var order: List<Int> = if (lite) LITE_ORDER else NORMAL_ORDER.filter { it != REMOTE }   // (v554) زرار الريموت اتشال: وضع التلفزيون بزرار عايم
    private fun cellOf(a: Int): Int = if (a in order) a else if (lite) (if (a == 7) YT_ME else -1) else MORE
    // ===== (v608) «المزيد» = صف تاني بينزلق لفوق جنب الصف الأساسي (بدل قايمة منبثقة). الريل (تابلت) لسه بالقايمة =====
    private var moreOpen = false
    private var moreHost: FrameLayout? = null
    private var moreScroll: android.widget.HorizontalScrollView? = null
    private var moreAnim: android.animation.ValueAnimator? = null
    private class MoreItem(val emoji: String, val label: String, val on: Boolean, val longPress: (() -> Unit)? = null, val tap: () -> Unit)

    private fun moreCell(act: Activity, mi: MoreItem, w: Int): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, ui.dp(3), 0, ui.dp(2))
        val ic = IconTextView(act).apply { text = mi.emoji; textSize = 16f; gravity = Gravity.CENTER; includeFontPadding = false }
        val lb = ui.text(mi.label, 11f, if (mi.on) th.primary else (if (tinted) 0xAAFFFFFF.toInt() else th.text), true).apply { gravity = Gravity.CENTER; setSingleLine() }
        addView(ic, LinearLayout.LayoutParams(ui.dp(38), ui.dp(20)))
        addView(lb, LinearLayout.LayoutParams(-2, -2))
        layoutParams = LinearLayout.LayoutParams(w, ui.dp(56))
        Glass.pressable(this)
        setOnClickListener { mi.tap() }
        val lpress = mi.longPress
        if (lpress != null) setOnLongClickListener { lpress(); true }
    }

    /** يبني الصف التاني من جديد (الأعضاء بتتغير حسب الوضع: يوتيوب/عادي/تلفزيون) */
    private fun rebuildMore(act: Activity) {
        val sc = moreScroll ?: return
        val inner = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val ids = DEFS.indices.filter { it != MORE && it != DEAD_AI && it < YT_HOME && it !in order && !(it == 4 && YT_HOME in order) }
        val items = ArrayList<MoreItem>()
        for (id in ids.filter { it != 7 }) items.add(MoreItem(DEFS[id].first, DEFS[id].second, id == active) { collapseMore(); onPick(id) })
        items.add(MoreItem("📤", "نقل", false) { collapseMore(); try { act.startActivity(android.content.Intent(act, TransferActivity::class.java)) } catch (e: Throwable) { LogStore.err("Nav:transfer", e) } })
        if (!tvDev) {
            items.add(MoreItem("📺", "وضع TV", PlayTarget.enabled(), {
                // دوسة طويلة: باقي خيارات التلفزيون
                val labels = arrayOf(TvModeFab.alwaysLabel(), "📤  ادفع التحديث للتلفزيون")
                GAlert(act).setTitle("📺 التلفزيون").setItems(labels) { _, i -> collapseMore(); if (i == 0) TvModeFab.toggleAlways(act) else RemoteClient.forcePushFlow(act) }.setNegativeButton("إغلاق", null).show()
            }) { collapseMore(); TvModeFab.toggle(act) })
        }
        if (7 in ids) items.add(MoreItem(DEFS[7].first, DEFS[7].second, 7 == active) { collapseMore(); onPick(7) })
        val sw = act.resources.displayMetrics.widthPixels
        val w = if (items.size <= 5) sw / items.size else (sw / 5.4f).toInt()
        for (mi in items) inner.addView(moreCell(act, mi, w))
        sc.removeAllViews()
        sc.addView(inner, FrameLayout.LayoutParams(-2, ui.dp(56)))
    }

    private fun animateMore(open: Boolean) {
        val host = moreHost ?: return
        moreOpen = open
        val target = if (open) ui.dp(57) else 0
        moreAnim?.cancel()
        val lp0 = host.layoutParams
        moreAnim = android.animation.ValueAnimator.ofInt(lp0.height, target).apply {
            duration = 260L; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { a -> val lp = host.layoutParams; lp.height = a.animatedValue as Int; host.layoutParams = lp }
            start()
        }
        icons.getOrNull(MORE)?.animate()?.rotation(if (open) 90f else 0f)?.setDuration(220L)?.start()
    }

    private fun toggleMore(act: Activity) {
        if (moreHost == null || rail) { moreSheet(act); return }
        if (!moreOpen) rebuildMore(act)
        animateMore(!moreOpen)
    }
    private fun collapseMore() { if (moreOpen) animateMore(false) }

    private fun moreSheet(act: Activity) {
        val d = android.app.Dialog(act)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(14))
            background = GradientDrawable().apply { setColor(th.card); cornerRadii = floatArrayOf(ui.dp(18).toFloat(), ui.dp(18).toFloat(), ui.dp(18).toFloat(), ui.dp(18).toFloat(), 0f, 0f, 0f, 0f) }
        }
        val items = DEFS.indices.filter { it != MORE && it != DEAD_AI && it < YT_HOME && it !in order && !(it == 4 && YT_HOME in order) }.map { id -> id to (DEFS[id].first + "  " + DEFS[id].second) }   // (v467) ديناميكي: أي بوابة جديدة بره الشريط هتظهر هنا لوحدها
        if (!tvDev) {   // (v555) وضع التلفزيون: الدخول/الخروج من «المزيد»
            val row = ui.text(TvModeFab.label(), 15f, th.primary, true).apply { setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) }
            Glass.pressable(row)
            row.setOnClickListener { d.dismiss(); TvModeFab.toggle(act) }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
            val row2 = ui.text(TvModeFab.alwaysLabel(), 13f, th.text, true).apply { setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(14)) }
            Glass.pressable(row2)
            row2.setOnClickListener { d.dismiss(); TvModeFab.toggleAlways(act) }
            box.addView(row2, LinearLayout.LayoutParams(-1, -2))
        }
        for ((id, label) in items) {
            val row = ui.text(label, 15f, if (id == active) th.primary else th.text, true).apply { setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) }
            Glass.pressable(row)
            row.setOnClickListener { d.dismiss(); onPick(id) }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (!tvDev) {   // (v521) دفع تحديث إجباري للتلفزيون من نسخة الموبايل (حتى لو التلفزيون ما لقاش نسخة أحدث)
            val row = ui.text("📤  ادفع التحديث للتلفزيون", 15f, th.text, true).apply { setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) }
            Glass.pressable(row)
            row.setOnClickListener { d.dismiss(); RemoteClient.forcePushFlow(act) }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setGravity(Gravity.BOTTOM); setLayout(-1, -2)
        }
        TvFocus.attachDialog(act, d, th.primary)   // (v480 م7)
        d.show()
    }

    private val icons = ArrayList<IconTextView>()
    private val labels = ArrayList<IconTextView>()
    private val cells = ArrayList<View>()
    private val badges = ArrayList<android.widget.TextView>()   // (v350) عدّاد أحمر فوق الأيقونة
    val view = LinearLayout(act).apply { orientation = if (rail) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; tag = "tvfab_nav" }   // (v562) TvModeFab بيلاقيه عشان يحط خط «وضع التلفزيون» فوقه
    private var rowV: View? = null
    private var lineV: View? = null
    private var tinted = false
    private var moving = false
    private var snap = false
    private lateinit var rowLL: LinearLayout
    private val indicator = View(act).apply {
        background = GradientDrawable().apply { setColor(th.primary); cornerRadius = ui.dp(2).toFloat() }
    }

    init {
        val line = View(act).apply { setBackgroundColor(th.border) }
        val row = LinearLayout(act).apply {
            orientation = if (rail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(th.card)
        }
        // (v336) الأرقام ثابتة (0 فيديوهات · 1 موسيقى · 2 متصفح · 3 ملفات · 4 يوتيوب · 5 مهام) لكن ترتيب العرض: يوتيوب قبل الملفات، والمهام رجعت زرار في الشريط
        val defs = DEFS
        rowLL = row
        defs.forEachIndexed { idx, d ->
            val icon = IconTextView(act).apply { text = d.first; textSize = 16f; gravity = Gravity.CENTER; includeFontPadding = false }
            val lab = ui.text(if (lite && idx == 0) "محلي" else d.second, 11f, th.muted, true).apply { gravity = Gravity.CENTER; setSingleLine() }   // (v458) كانت 9f صغيرة قوي
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
                setOnClickListener { if (idx == MORE) toggleMore(act) else if (idx == REMOTE) { try { ControllerPad.show(act) } catch (e: Throwable) { LogStore.err("Nav:remote", e) } } else { collapseMore(); onPick(idx) } }
            }
            icons.add(icon); labels.add(lab); cells.add(cell)
        }
        fillRow()
        val wrap = FrameLayout(act)
        wrap.addView(row, if (rail) FrameLayout.LayoutParams(-2, -1) else FrameLayout.LayoutParams(-1, -2))
        wrap.addView(indicator, if (rail) FrameLayout.LayoutParams(ui.dp(2), ui.dp(22), Gravity.START or Gravity.CENTER_VERTICAL) else FrameLayout.LayoutParams(ui.dp(22), ui.dp(2), Gravity.TOP or Gravity.START))
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!moving) { place(false); snap = false } }
        rowV = row; lineV = line
        if (rail) { view.addView(wrap, LinearLayout.LayoutParams(0, -1, 1f)); view.addView(line, LinearLayout.LayoutParams(ui.dp(1), -1)) }
        else {
            view.addView(line, LinearLayout.LayoutParams(-1, ui.dp(1)))
            // (v608) الصف التاني (المزيد): ارتفاعه 0 وهو مقفول؛ بيطلع لفوق فوق الصف الأساسي وهو مفتوح
            val host = FrameLayout(act).apply { clipChildren = true; setBackgroundColor(th.card) }
            val sc = android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; overScrollMode = View.OVER_SCROLL_NEVER }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(sc, LinearLayout.LayoutParams(-1, ui.dp(56)))
            col.addView(View(act).apply { setBackgroundColor(th.border) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
            host.addView(col, FrameLayout.LayoutParams(-1, ui.dp(57), Gravity.BOTTOM))
            moreHost = host; moreScroll = sc
            view.addView(host, LinearLayout.LayoutParams(-1, 0))
            view.addView(wrap, LinearLayout.LayoutParams(-1, -2))
        }
        paint()
        badge(4, YtNotify.badgeCount())
    }

    /** (v350) عدّاد أحمر فوق أيقونة البوابة؛ 0 = مخفي */
    fun badge(i: Int, n: Int) {
        for (k in (if (i == 4) listOf(4, YT_SUBS) else listOf(i))) {   // (v475) عدّاد يوتيوب الجديد بيظهر كمان على خلية «الاشتراكات»
            val b = badges.getOrNull(k) ?: continue
            b.text = if (n > 99) "99+" else n.toString()
            b.visibility = if (n > 0) View.VISIBLE else View.GONE
        }
    }

    private fun fillRow() {
        val row = rowLL
        row.removeAllViews()
        for (id in order) {
            val c = cells[id]
            if (rail) row.addView(c, LinearLayout.LayoutParams(ui.dp(76), ui.dp(66)))   // (v462) خلايا الريل مكدّسة رأسيًا
            else row.addView(c, LinearLayout.LayoutParams(0, ui.dp(56), 1f))             // (v458) شريط تنقّل بارتفاع قياسي 56dp
        }
    }
    /** (v475) وانت في يوتيوب (نسخة كاملة) الشريط بيعرض الرئيسية/الاشتراكات/أنا بدل «يوتيوب»؛ اللايت ترتيبه ثابت */
    fun mode(inYoutube: Boolean) {
        val o = if (lite) LITE_ORDER else if (inYoutube) YT_ORDER else NORMAL_ORDER.filter { it != REMOTE }
        if (o == order) return
        order = o; snap = true; indicator.animate().cancel(); moving = false
        fillRow(); paint(); rowLL.requestLayout()
    }

    private fun place(animate: Boolean) {
        indicator.visibility = if (cellOf(active) in cells.indices) View.VISIBLE else View.INVISIBLE
        val c = cells.getOrNull(cellOf(active)) ?: return
        if (rail) {   // (v462) المؤشر شريط رأسي بيتحرك على Y
            if (c.height == 0) return
            val y = (c.top + c.height / 2 - ui.dp(11)).toFloat()
            if (animate) { moving = true; indicator.animate().cancel(); indicator.animate().setStartDelay(0L).translationY(y).setDuration(300L).setInterpolator(OvershootInterpolator(1.4f)).withEndAction { moving = false }.start() }
            else { indicator.animate().cancel(); moving = false; indicator.translationY = y }
            return
        }
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
        if (snap) { rowLL.requestLayout() }
        else if (changed) { place(true); icons.getOrNull(cellOf(i))?.let { Motion.bounce(it) } }
    }
    /** (v135) نص تحت أيقونة البوابة (بيتغيّر لـ «المهام (2)» لما فيه مهام شغّالة) */
    fun label(i: Int, t: String) { if (i in labels.indices) labels[i].text = t }
    /** (v198) لون الشريط وهو مشغّل الموسيقى مفتوح (بيتبع لون الغلاف)؛ null = رجّعه للثيم */
    fun tint(c: Int?) {
        tinted = c != null
        rowV?.setBackgroundColor(c ?: th.card)
        moreHost?.setBackgroundColor(c ?: th.card)
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
