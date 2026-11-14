package com.tttt.subtitler

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/**
 * (v820) إعدادات كل صفحة: زرار ⚙️ عايم في صفحات الفيديوهات والموسيقى والمهام، وزرار ⚙️ ثابت فوق زرار ☰ في المتصفح.
 * الإعدادات الشاملة (تبويب ⚙️ وقايمة ⚙️ المشغّل) زي ما هي بالظبط — ده «عرض مفلتر» لنفس الأقسام (نفس الـ Views) من غير ما أي إعداد يتكرر أو يتنقل،
 * فأي تغيير هنا بيتغيّر في الإعدادات الشاملة والعكس.
 * عشان تغيّر أي قسم يظهر في أي صفحة عدّل جدول SECTIONS تحت بس (الأرقام دي هي أرقام الأقسام في SettingsTabs).
 */
object PageSettings {
    const val VIDEOS = "videos"; const val MUSIC = "music"; const val TASKS = "tasks"; const val BROWSER = "browser"

    /** الصفحة ← أقسام الإعدادات اللي بتأثر عليها. قسم واحد بس = بيتفتح على طول من غير قايمة */
    private val SECTIONS = mapOf(
        VIDEOS to listOf("general", "keys", "audio", "perf", "look", "more"),   // خط الترجمة · المفاتيح · التفريغ والصوت · الموديل (والوضع البصري) · شكل الترجمة · الخروج من المشغّل
        MUSIC to listOf("music"),                                                          // الكلمات · شكل الكلمات · فحص الأغاني
        TASKS to listOf("more", "perf"))                                                   // الطابور والبطارية · الموديل وطول المقطع (اللي بيمشوا في المهام)

    private val TITLES = mapOf(VIDEOS to "⚙️ إعدادات الفيديوهات", MUSIC to "⚙️ إعدادات الموسيقى", TASKS to "⚙️ إعدادات المهام", BROWSER to "⚙️ إعدادات المتصفح")

    fun sections(page: String): List<String> = SECTIONS[page] ?: emptyList()
    fun title(page: String): String = TITLES[page] ?: "⚙️ الإعدادات"
    /** رقم التبويب الداخلي في MainActivity (0 فيديوهات · 1 موسيقى · 5 مهام) ← الصفحة اللي ليها زرار إعدادات */
    fun pageOfTab(tab: Int): String? = when (tab) { 0 -> VIDEOS; 1 -> MUSIC; 5 -> TASKS; else -> null }
}

/** (v823) كبسولة ⚙️ عايمة بس (مش صف): بتتحط فوق الصفحة، لازقة فوق الشريط السفلي، من غير ما تاخد صف ولا تقصّر الصفحة. host = الفريم اللي فيه كل الشاشة، anchor = الشريط السفلي (بيحدد ارتفاعها) */
class PageGear(private val act: Activity, host: FrameLayout, private val anchor: View, private val rail: Boolean, private val ui: Ui, th: Theme, private val lift: View?, private val onTap: (String) -> Unit) {   // (v829) lift = شريط فيديوهات/موسيقى اللي تحت الصفحة؛ الكبسولة بتقعد فوقه مش عليه
    private var page: String? = null
    /** بيتخبّى وقت مشغّل الموسيقى الكامل */
    var hidden = false
        set(v) { field = v; apply() }
    /** صف «المزيد» السفلي اتفتح (اتمدّد لفوق): الكبسولة تختفي */
    var moreOpen = false
        set(v) { field = v; apply() }
    val view = IconTextView(act).apply {
        text = "⚙️"; textSize = 16f; gravity = Gravity.CENTER; includeFontPadding = false
        background = ui.box(th.card, th.border, 15)
        elevation = ui.dp(6).toFloat()
        contentDescription = "إعدادات الصفحة"
        visibility = View.GONE
        Glass.pressable(this)
        setOnClickListener { page?.let { onTap(it) } }
    }

    init {
        host.addView(view, FrameLayout.LayoutParams(ui.dp(56), ui.dp(28), Gravity.RIGHT or Gravity.BOTTOM))
        reposition()
        // الشريط السفلي بيتغيّر ارتفاعه (صف «المزيد»، الدوران): الكبسولة تفضل لازقة فوقه
        anchor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reposition() }
        lift?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reposition() }   // (v829) ظهور/اختفاء شريط الميديا
        MtFloat.barOf(act)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reposition() }   // (v910) ارتفاع الشريط الزجاجي العايم
    }

    private fun reposition() {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val sw = act.resources.displayMetrics.widthPixels
        val rm: Int; var bm: Int
        val up = if (lift != null && lift.visibility == View.VISIBLE) lift.height else 0   // (v829)
        if (rail) { rm = anchor.width + ui.dp(10); bm = ui.dp(10) + up }   // تابلت: الريل على الجنب
        else {
            rm = if (DeviceUi.isTablet(act)) ui.dp(14) else (sw / 10 - ui.dp(28)).coerceAtLeast(ui.dp(4)); bm = anchor.height + ui.dp(6) + up
            // (v910) الشريط الزجاجي العايم له حشو 6dp + هامش 12dp تحت (والـlift جواه أصلًا): لازم نقعد فوق الزجاج كله مش فوق الأزرار بس، وإلا الكبسولة تستخبّى وراه
            val fb = MtFloat.barTop(act)
            if (fb > 0) bm = fb + ui.dp(8)
        }
        if (lp.rightMargin != rm || lp.bottomMargin != bm) { lp.rightMargin = rm; lp.bottomMargin = bm; view.layoutParams = lp }
    }

    /** بيتنده كل ما التبويب يتغيّر */
    fun sync(tab: Int) { page = PageSettings.pageOfTab(tab); apply() }
    private fun apply() { view.visibility = if (page != null && !hidden && !moreOpen) View.VISIBLE else View.GONE }
}
