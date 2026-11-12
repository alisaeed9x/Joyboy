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
        // (v815) 5 مجموعات واضحة — كل حاجة شبه بعضها جنب بعضها، ومكان كل إعداد واحد بس
        Sec("general", "🚀 خط الترجمة", "اللهجة · الأسلوب · الشدة · الخطة (تفريغ ← ترجمة ← لهجة) · 📝 البرومبتات", "🚀 الترجمة والمحرك"),
        Sec("keys", "🔑 المفاتيح", "مفاتيح جيميناي · الاستهلاك والكوتة", "🚀 الترجمة والمحرك"),
        Sec("audio", "🎙 التفريغ والصوت", "موديلات التفريغ على الجهاز · لغة الكلام · مسار الصوت · جودة الصوت · الأصوات الخلفية · دقة التوقيت", "🚀 الترجمة والمحرك"),
        Sec("perf", "🤖 الموديل والأداء", "الموديل الواحد (Gemini Flash-Lite) · اختبار السرعة · طول المقطع · المقاطع قدّام التشغيل · التنقيح", "🚀 الترجمة والمحرك"),
        Sec("visual", "👁 الصورة (الوضع البصري)", "مفتاح الوضع البصري · تلقائي أو يدوي · المسافة قدّام التشغيل", "🚀 الترجمة والمحرك"),
        Sec("look", "🎬 شكل الترجمة", "الخط والحجم · الأنيميشن · وضع العرض · اللون · الخلفية · كبسولات العدّادات", "🎨 الشكل"),
        Sec("appearance", "🎨 المظهر", "المظهر (داكن · فاتح · أموليد · رملي) · الجلاس والحركة · راحة العين · نوع الجهاز · الوصول", "🎨 الشكل"),
        Sec("tvset", "📺 التلفزيون", "كل إعدادات التلفزيون في مكان واحد: الشكل والسمات · الخلفية · الأيقونات · الرئيسية والصفوف · الفيديوهات · المتصفح · الصلاة", "📺 التلفزيون"),
        Sec("youtube", "▶ يوتيوب", "الجودة · السجل · الإشعارات · الحساب · التحميلات", "▶ يوتيوب والموسيقى"),
        Sec("music", "🎵 مشغّل الموسيقى", "الكلمات (جيميناي) · الخط والحجم · الكلمات التلقائية · فحص الأغاني", "▶ يوتيوب والموسيقى"),
        Sec("more", "🔋 الخلفية والتشغيل", "الخروج من المشغّل · الطابور · البطارية (عشان الجهاز ما يقتلش الخدمة)", "🛠 النظام"),
        Sec("capture", "🔗 الاصطياد والترجمة الحية", "مراقب صفحات الفيديو في المتصفحات · الترجمة الحية فوق أي تطبيق", "🛠 النظام"),
        Sec("security", "🔒 الأمان والنسخ", "القفل بالنمط والبصمة · النسخة الاحتياطية والاسترجاع", "🛠 النظام"))
    val video = sections.filter { it.id != "music" }.map { it.id to it.label }
    val all = sections.map { it.id to it.label }
    private fun sec(id: String) = sections.first { it.id == id }
    fun label(id: String): String = sec(id).label
    fun sub(id: String): String = sec(id).sub
    fun group(id: String): String = sec(id).group
}

/** (v836) المحتوى lazy: [build] بتتنده أول مرة القسم يتفتح بس (قبل كده كل الأقسام كانت بتتبني مع فتح التطبيق). */
class TabDef(val id: String, val label: String, private val build: () -> List<View>, val preview: Boolean = false, val sub: String = "", val group: String = "") {
    private var built: List<View>? = null
    val content: List<View> get() = built ?: build().also { built = it }
}

/** إعدادات على طريقة MX Player: قايمة أقسام (أيقونة · عنوان · وصف) ← دوس على قسم يفتح شاشته ← سهم الرجوع يرجّعك للقايمة. preview = معاينة الترجمة بتظهر فوق المحتوى في الأقسام اللي preview=true */
class TabbedDialog(val act: Activity, val ui: Ui, val title: String, val tabs: List<TabDef>, val preview: View?, private val onClose: () -> Unit) {
    /** (v370) لو اتحط: الإعدادات صفحة جوه التطبيق (تبويب ⚙️) مش دايلوج، و«إغلاق/رجوع من القايمة» بينده ده */
    var embedClose: (() -> Unit)? = null
    private var embedded = false
    private lateinit var root: LinearLayout
    private lateinit var scrollBox: MaxHeightScroll
    val dialog = GDialog(act)
    private val listPage = LinearLayout(act)
    private val bodies = ArrayList<LinearLayout>()
    private val filled = BooleanArray(tabs.size)   // (v836) أي قسم جسمه اتملى فعلًا
    private val rowViews = ArrayList<View>()   // (v480 م7) صفوف القايمة (تلفزيون: التركيز يرجع للصف اللي كنت فيه)
    private val headerViews = ArrayList<View>()   // (v820) عناوين المجموعات (بتتخبّى في العرض المفلتر لإعدادات الصفحة)
    private var filter: Set<String>? = null       // (v820) null = كل الأقسام (الإعدادات الشاملة) · غير كده = أقسام صفحة معيّنة بس (PageSettings)
    private var curTitle = title
    private val tvMode = TvFocus.on(act)
    private val previewBox = FrameLayout(act)
    private val titleTv: TextView
    private val backTv: TextView
    private var cur = -1
    private var directEntry = false   // (v459) القسم اتفتح مباشرة من بره (مثلًا إعدادات يوتيوب من صفحة يوتيوب): أول رجوع يخرج على طول مش يرجّع لقايمة الإعدادات الرئيسية
    private val split = DeviceUi.isTablet(act) || tvMode   // (v462) وضع التابلت: القايمة والمحتوى جنب بعض (Master-Detail زي يوتيوب/إعدادات التابلت)
    private lateinit var bodyCol: LinearLayout
    private lateinit var splitHint: TextView

    private fun iconOf(l: String): String { val i = l.indexOf(' '); return if (i in 1..4) l.substring(0, i) else "⚙️" }
    private fun nameOf(l: String): String { val i = l.indexOf(' '); return if (i in 1..4) l.substring(i + 1) else l }

    init {
        val th = ui.th
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dm = act.resources.displayMetrics
        root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12)); background = ui.box(th.card, th.border, 18)
        }
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, 0, 0, ui.dp(6)) }
        backTv = IconTextView(act).apply {
            text = "→"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(th.primary); visibility = View.GONE
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.box(th.surface, th.border, 20)
            Glass.pressable(this)
            setOnClickListener { back() }
        }
        head.addView(backTv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
        titleTv = ui.text(title, 17f, th.primary, true)
        head.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconTextView(act).apply { text = "✕"; textSize = 18f; setTextColor(th.muted); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); setOnClickListener { embedClose?.invoke() ?: dialog.dismiss() } })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        if (preview != null) { previewBox.addView(preview); previewBox.visibility = View.GONE; root.addView(previewBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }) }

        // صفحة القايمة الرئيسية
        listPage.apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        // (v370) الأقسام صفوف على طريقة MX Player (أيقونة · عنوان · وصف) بدل المربعات — نفس الشكل في الصفحة الكاملة وفي الدايلوج
        var lastGroup = ""
        tabs.forEachIndexed { i, t ->
            if (t.group.isNotBlank() && t.group != lastGroup) {
                lastGroup = t.group
                if (tabs.map { it.group }.distinct().size > 1 && tabs.count { x -> x.group == t.group } > 1) {   // (v454) عنوان المجموعة بشريط لون جنبه · (v459) مجموعة من قسم واحد مالهاش عنوان (كان بيكرر اسم القسم نفسه)
                    val hd = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(16), ui.dp(6), ui.dp(4)) }
                    hd.addView(View(act).apply { background = ui.box(th.primary, th.primary, 3) }, LinearLayout.LayoutParams(ui.dp(4), ui.dp(16)).apply { marginEnd = ui.dp(8) })
                    hd.addView(ui.text(t.group, 13.5f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
                    listPage.addView(hd, LinearLayout.LayoutParams(-1, -2))
                    headerViews.add(hd)
                }
            }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(12), ui.dp(if (tvMode) 15 else 12), ui.dp(12), ui.dp(if (tvMode) 15 else 12)); background = ui.box(th.surface, th.border, 16)
                Glass.pressable(this)
                setOnClickListener { directEntry = false; select(i) }
                // (v487) تلفزيون: مجرد التركيز على قسم بالأسهم بيعرض محتواه جنبه؛ OK أو → بيدخل جواه
                if (tvMode) setOnFocusChangeListener { _, f -> if (f && cur != i) { directEntry = false; select(i, true) } }
            }
            row.addView(IconTextView(act).apply {   // (v454) الأيقونة جوه دايرة بلون الثيم الخفيف
                text = iconOf(t.label); textSize = 20f; gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor((th.primary and 0x00FFFFFF) or (0x26 shl 24)) }
            }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            col.addView(ui.text(nameOf(t.label), if (tvMode) 17f else 15.5f, th.text, true))
            if (t.sub.isNotBlank()) col.addView(ui.text(t.sub, 11.5f, th.muted).apply { maxLines = 2; setPadding(0, ui.dp(2), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) })
            row.addView(IconTextView(act).apply { text = "‹"; textSize = 20f; setTextColor(th.muted); gravity = Gravity.CENTER })
            rowViews.add(row)
            listPage.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4)) })
        }

        val frame = LinearLayout(act).apply { orientation = if (split) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        frame.addView(listPage, if (split) LinearLayout.LayoutParams(ui.dp(if (tvMode) 360 else 300), -2) else LinearLayout.LayoutParams(-1, -2))
        bodyCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        splitHint = ui.text("👈 اختار قسم من القايمة", 14f, th.muted).apply { setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(24)) }
        if (split) bodyCol.addView(splitHint)
        tabs.forEach { t ->
            val b = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE }
            bodies.add(b); bodyCol.addView(b, LinearLayout.LayoutParams(-1, -2))
        }
        frame.addView(bodyCol, if (split) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        scrollBox = MaxHeightScroll(act, (dm.heightPixels * 0.66f).toInt()).apply { addView(frame) }
        root.addView(scrollBox, LinearLayout.LayoutParams(-1, -2))
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
        val prev = cur
        cur = -1; directEntry = false
        if (::splitHint.isInitialized) splitHint.visibility = if (split) View.VISIBLE else View.GONE
        listPage.visibility = View.VISIBLE
        swapIn(listPage, false)
        bodies.forEach { it.visibility = View.GONE }
        previewBox.visibility = View.GONE
        backTv.visibility = View.GONE
        titleTv.text = curTitle
        if (tvMode && split && tabs.isNotEmpty()) select((if (prev >= 0 && isShown(prev)) prev else firstShown()), true)   // (v487) تلفزيون: أول قسم معروض على طول
        if (tvMode && rowViews.isNotEmpty()) TvFocus.focusIn(rowViews.getOrNull(if (isShown(prev)) prev else firstShown()) ?: rowViews[0])   // (v480 م7)
    }

    fun back() {
        if (tvMode && split) {   // (v487) تلفزيون: Back من جوه القسم = رجوع لقايمة الأقسام؛ ومن القايمة = خروج
            val f = if (embedded) act.currentFocus else (dialog.currentFocus ?: act.currentFocus)
            if (f != null && cur >= 0 && isInBody(f)) { rowViews.getOrNull(cur)?.let { TvFocus.focusIn(it) }; return }
            embedClose?.invoke() ?: dialog.dismiss(); return
        }
        if (split && cur >= 0) { showList(); return }; if (cur >= 0 && !directEntry) showList() else (embedClose?.invoke() ?: dialog.dismiss()) }   // (v459) دخول مباشر: رجوع واحد يخرج · (v462) التابلت: الرجوع بيفرّغ الاختيار بس

    private fun isInBody(v: View): Boolean { var p: android.view.ViewParent? = v.parent; while (p != null) { if (p === bodyCol) return true; p = p.parent }; return false }

    /** (v836) بيبني محتوى القسم أول مرة بس ويحطه في جسمه الفاضي */
    private fun ensureBody(i: Int) {
        if (filled[i]) return
        filled[i] = true
        try { tabs[i].content.forEach { bodies[i].addView(it) } } catch (e: Throwable) { LogStore.err("Settings:tab:" + tabs[i].id, e) }
    }

    fun select(i: Int, fromFocus: Boolean = false) {
        if (i !in tabs.indices) return
        ensureBody(i)
        cur = i
        if (::splitHint.isInitialized) splitHint.visibility = View.GONE
        listPage.visibility = if (split) View.VISIBLE else View.GONE
        bodies.forEachIndexed { k, b -> b.visibility = if (k == i) View.VISIBLE else View.GONE }
        previewBox.visibility = if (preview != null && tabs[i].preview) View.VISIBLE else View.GONE
        backTv.visibility = View.VISIBLE
        if (!fromFocus) swapIn(bodies[i], true)
        titleTv.text = tabs[i].label
        if (tvMode && !fromFocus) TvFocus.focusIn(bodies[i])   // (v480 م7) أول عنصر في القسم، مش ✕
    }

    fun show(tabId: String? = null) {
        val f = filter
        val tid = tabId ?: if (f != null && f.size == 1) f.first() else null   // (v820) إعدادات صفحة فيها قسم واحد (الموسيقى) بتفتح عليه على طول
        if (tid == null) showList() else tabs.indexOfFirst { it.id == tid }.takeIf { it >= 0 }?.let { directEntry = true; select(it) } ?: showList()
        if (!embedded) dialog.show()
    }

    private fun isShown(i: Int): Boolean { val f = filter; return i in tabs.indices && (f == null || tabs[i].id in f) }
    private fun firstShown(): Int = tabs.indices.firstOrNull { isShown(it) } ?: 0

    /**
     * (v820) عرض مفلتر: بيعرض أقسام معيّنة بس من نفس القايمة (إعدادات الصفحة — PageSettings) ويخبّي الباقي، من غير ما يلمس محتوى الأقسام نفسها.
     * ids = null بيرجّع القايمة الكاملة (الإعدادات الشاملة) — الشاشة الشاملة بتنده كده في كل مرة بتفتح.
     */
    fun setFilter(ids: List<String>?, ttl: String? = null) {
        filter = ids?.toSet()
        curTitle = if (ids != null && ttl != null) ttl else title
        rowViews.forEachIndexed { i, r -> r.visibility = if (isShown(i)) View.VISIBLE else View.GONE }
        headerViews.forEach { it.visibility = if (filter == null) View.VISIBLE else View.GONE }
        if (cur < 0) titleTv.text = curTitle
    }

    /** (v370) بيطلّع الإعدادات من الدايلوج ويحطها صفحة كاملة جوه [parent] (تبويب ⚙️ في الشريط السفلي) */
    fun embedInto(parent: android.view.ViewGroup) {
        (root.parent as? android.view.ViewGroup)?.removeView(root)
        embedded = true
        root.background = ColorDrawable(ui.th.bg)
        scrollBox.maxH = act.resources.displayMetrics.heightPixels
        root.removeView(scrollBox)
        root.addView(scrollBox, LinearLayout.LayoutParams(-1, 0, 1f))
        parent.addView(root, FrameLayout.LayoutParams(-1, -1))
    }

    /** (v370) في الوضع المدمج: يفتح قسم (أو القايمة لو null) */
    fun showEmbedded(tabId: String? = null) = show(tabId)
}

