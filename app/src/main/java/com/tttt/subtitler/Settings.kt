package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*

/** مرجع للمحرك الشغّال حاليًا (بيستخدمه تبويب الشخصيات عشان يستورد المكتشفة تلقائيًا) */
object Live { @Volatile var engine: Engine? = null }

/**
 * (v212) مصدر واحد لأقسام الإعدادات: شاشة الإعدادات اللي بره وقايمة ⚙️ الإعدادات في المشغّل الاتنين بيقروا منه (id + الاسم).
 * قسم جديد يتضاف هنا مرة واحدة ويظهر في الاتنين؛ مفيش قسم في المشغّل ملوش قسم بره.
 */
object SettingsTabs {
    class Sec(val id: String, val label: String, val sub: String, val group: String)
    /** (v235) ترتيب واحد لكل أشكال الإعدادات (شاشة الإعدادات · قايمة ⚙️ في المكتبة · قايمة ⚙️ في المشغّل): كل حاجة جنب أخواتها */
    val sections = listOf(
        Sec("general", "🚀 خط الترجمة", "الخطة (تفريغ ← ترجمة ← لهجة) · اللهجة · الأسلوب · شدة الترجمة · تحويل اللهجة · 📝 البرومبتات", "🚀 الترجمة والمحرك"),
        Sec("keys", "🔑 المفاتيح", "مفاتيح جيميناي · مفتاح الوضع البصري · محركات خارجية (ChatGPT · Claude · …) · الاستهلاك", "🚀 الترجمة والمحرك"),
        Sec("audio", "🎙 التفريغ والصوت", "موديلات التفريغ على الجهاز · لغة الكلام · مسار الصوت · جودة الصوت · الأصوات الخلفية · دقة التوقيت", "🚀 الترجمة والمحرك"),
        Sec("visual", "👁 الصورة (الوضع البصري)", "تلقائي أو يدوي · قراءة الكلام المكتوب في الفيديو", "🚀 الترجمة والمحرك"),
        Sec("perf", "🤖 الموديل والأداء", "الموديل · اختبار السرعة · طول المقطع · المقاطع قدّام التشغيل · التنقيح", "🚀 الترجمة والمحرك"),
        Sec("look", "🎬 شكل الترجمة", "الخطوط · الأنيميشن · وضع العرض · الألوان · الخلفية", "🎬 الشكل"),
        Sec("more", "🔋 الخلفية والتشغيل", "الخروج من المشغّل · طابور الترجمة في الخلفية · البطارية", "🛠 عام"),
        Sec("live", "🔴 الترجمة الحية", "ترجمة عايمة فوق أي تطبيق", "🛠 عام"),
        Sec("security", "🔒 الأمان والنسخ", "القفل بالنمط والبصمة · النسخة الاحتياطية والاسترجاع", "🛠 عام"),
        Sec("appearance", "🎨 المظهر", "الثيم · داكن/فاتح · وضع الواجهة · الأصوات", "🛠 عام"),
        Sec("youtube", "▶ يوتيوب", "الجودة · السجل · الإشعارات · الحساب · التحميلات", "▶ يوتيوب"),
        Sec("music", "🎵 مشغّل الموسيقى", "الكلمات (جيميناي) · الخط والحجم · الكلمات التلقائية · فحص الأغاني", "🎵 الموسيقى"))
    val video = sections.filter { it.id != "music" }.map { it.id to it.label }
    val all = sections.map { it.id to it.label }
    private fun sec(id: String) = sections.first { it.id == id }
    fun label(id: String): String = sec(id).label
    fun sub(id: String): String = sec(id).sub
    fun group(id: String): String = sec(id).group
}

class TabDef(val id: String, val label: String, val content: List<View>, val preview: Boolean = false, val sub: String = "", val group: String = "")

/** إعدادات على طريقة MX Player: قايمة أقسام (أيقونة · عنوان · وصف) ← دوس على قسم يفتح شاشته ← سهم الرجوع يرجّعك للقايمة. preview = معاينة الترجمة بتظهر فوق المحتوى في الأقسام اللي preview=true */
class TabbedDialog(val act: Activity, val ui: Ui, val title: String, val tabs: List<TabDef>, val preview: View?, onClose: () -> Unit) {
    val dialog = GDialog(act)
    private val listPage = LinearLayout(act)
    private val bodies = ArrayList<LinearLayout>()
    private val previewBox = FrameLayout(act)
    private val titleTv: TextView
    private val backTv: TextView
    private var cur = -1

    private fun iconOf(l: String): String { val i = l.indexOf(' '); return if (i in 1..4) l.substring(0, i) else "⚙️" }
    private fun nameOf(l: String): String { val i = l.indexOf(' '); return if (i in 1..4) l.substring(i + 1) else l }

    init {
        val th = ui.th
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dm = act.resources.displayMetrics
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12)); background = ui.box(th.card, th.border, 18)
        }
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, 0, 0, ui.dp(6)) }
        backTv = IconTextView(act).apply {
            text = "→"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(th.primary); visibility = View.GONE
            setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4)); background = ui.box(th.surface, th.border, 20)
            Glass.pressable(this)
            setOnClickListener { back() }
        }
        head.addView(backTv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
        titleTv = ui.text(title, 17f, th.primary, true)
        head.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconTextView(act).apply { text = "✕"; textSize = 18f; setTextColor(th.muted); setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)); setOnClickListener { dialog.dismiss() } })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        if (preview != null) { previewBox.addView(preview); previewBox.visibility = View.GONE; root.addView(previewBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }) }

        // صفحة القايمة الرئيسية
        listPage.apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        // (v355) الأقسام بقت مربعات: أيقونة كبيرة + اسم قصير، 3 في الصف، وعنوان لكل مجموعة. ضغطة طويلة على المربع = وصف اللي جواه
        var lastGroup = ""
        var rowBox: LinearLayout? = null; var inRow = 0
        fun flushRow() { val r = rowBox ?: return; while (inRow < 3) { r.addView(View(act), LinearLayout.LayoutParams(0, ui.dp(92), 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }); inRow++ }; rowBox = null; inRow = 0 }
        tabs.forEachIndexed { i, t ->
            // عنوان قسم لما المجموعة تتغيّر: عام · مشغّل الفيديو · مشغّل الموسيقى · يوتيوب
            if (t.group.isNotBlank() && t.group != lastGroup) {
                flushRow(); lastGroup = t.group
                if (tabs.map { it.group }.distinct().size > 1) listPage.addView(ui.text(t.group, 13f, th.muted, true).apply { setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(2)) }, LinearLayout.LayoutParams(-1, -2))
            }
            if (rowBox == null) { rowBox = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }; listPage.addView(rowBox, LinearLayout.LayoutParams(-1, -2)); inRow = 0 }
            val tile = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)); background = ui.box(th.surface, th.border, 16)
                Glass.pressable(this)
                setOnClickListener { select(i) }
                if (t.sub.isNotBlank()) setOnLongClickListener { Notice.show(act, t.sub, 3200L); true }
            }
            tile.addView(IconTextView(act).apply { text = iconOf(t.label); textSize = 30f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2))
            tile.addView(ui.text(nameOf(t.label), 12.5f, th.text, true).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, ui.dp(5), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
            rowBox!!.addView(tile, LinearLayout.LayoutParams(0, ui.dp(92), 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            if (++inRow == 3) { rowBox = null; inRow = 0 }
        }
        flushRow()

        val frame = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        frame.addView(listPage, LinearLayout.LayoutParams(-1, -2))
        tabs.forEach { t ->
            val b = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE }
            t.content.forEach { b.addView(it) }
            bodies.add(b); frame.addView(b, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(MaxHeightScroll(act, (dm.heightPixels * 0.66f).toInt()).apply { addView(frame) }, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setLayout((dm.widthPixels * 0.95f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // رجوع (زرار أو سويب): لو جوه قسم يرجع للقايمة، لو في القايمة يقفل الإعدادات
        dialog.onBack = { if (cur >= 0) { back(); true } else false }
        dialog.setOnDismissListener { onClose() }
        showList()
    }

    private fun swapIn(v: View, fromLeft: Boolean) {
        try {
            v.animate().cancel()
            v.alpha = 0f; v.translationX = (if (fromLeft) -1f else 1f) * ui.dp(28).toFloat()
            v.animate().alpha(1f).translationX(0f).setDuration(230).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        } catch (_: Exception) {}
    }

    private fun showList() {
        cur = -1
        listPage.visibility = View.VISIBLE
        swapIn(listPage, false)
        bodies.forEach { it.visibility = View.GONE }
        previewBox.visibility = View.GONE
        backTv.visibility = View.GONE
        titleTv.text = title
    }

    fun back() { if (cur >= 0) showList() else dialog.dismiss() }

    fun select(i: Int) {
        if (i !in tabs.indices) return
        cur = i
        listPage.visibility = View.GONE
        bodies.forEachIndexed { k, b -> b.visibility = if (k == i) View.VISIBLE else View.GONE }
        previewBox.visibility = if (preview != null && tabs[i].preview) View.VISIBLE else View.GONE
        backTv.visibility = View.VISIBLE
        swapIn(bodies[i], true)
        titleTv.text = tabs[i].label
    }

    fun show(tabId: String? = null) {
        if (tabId == null) showList() else tabs.indexOfFirst { it.id == tabId }.takeIf { it >= 0 }?.let { select(it) } ?: showList()
        dialog.show()
    }
}

