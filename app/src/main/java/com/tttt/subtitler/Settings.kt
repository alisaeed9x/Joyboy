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
        Sec("keys", "🔑 المفاتيح", "مفاتيح جيميناي · مفتاح الوضع البصري · الاستهلاك والكوتة", "🚀 الترجمة والمحرك"),
        Sec("audio", "🎙 التفريغ والصوت", "الصوت · موديلات التفريغ على الجهاز · لغة الكلام · وارد (طول الجزء) · نموذج التفريغ الاحتياطي", "🚀 الترجمة والمحرك"),
        Sec("perf", "🤖 الموديل والأداء", "موديل الترجمة · اختبار السرعة · التقطيع والتنقيح · محرك الوضع البصري", "🚀 الترجمة والمحرك"),
        Sec("look", "🎬 شكل الترجمة", "الخط والحجم · الأنيميشن · وضع العرض · اللون · الخلفية · كبسولات العدّادات", "🎨 الشكل"),
        Sec("appearance", "🎨 المظهر", "المظهر (داكن · فاتح · أموليد · رملي) · درجة الدفء · نوع الجهاز · الحركة والوصول", "🎨 الشكل"),
        Sec("tvset", "📺 التلفزيون", "كل إعدادات التلفزيون في مكان واحد: الشكل والسمات · الخلفية · الأيقونات · الرئيسية والصفوف · الفيديوهات · المتصفح · الصلاة", "📺 التلفزيون"),
        // (v1012) قسم المشغّل: كل ما يخص المشغّل في مكان واحد (كان مبعثر بين يوتيوب والمظهر والخلفية)
        Sec("player", "🎬 المشغّل", "المشغّل الأساسي (MeeTube / Player.kt) · مظهر المشغّل · الجودة الافتراضية · حدّة الفيديو · السلاسة والفريمات · الخروج من المشغّل", "🎬 المشغّل"),
        Sec("youtube", "▶ يوتيوب", "فتح يوتيوب (MeeTube) · إعداداته الكاملة: الحساب · السجل · الإشعارات · التحميلات", "▶ يوتيوب"),
        Sec("music", "🎵 مشغّل الموسيقى", "الكلمات (جيميناي) · الخط والحجم · الكلمات التلقائية · فحص الأغاني", "🎵 الموسيقى"),
        Sec("more", "🔋 الخلفية والتشغيل", "الطابور · مجلد الترجمة في الخلفية · البطارية (عشان الجهاز ما يقتلش الخدمة)", "🛠 النظام"),
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
    // (v1014) بحث الإعدادات: مربع فوق + نتايج بتنقل الإعداد الحي نفسه (مش نسخة) وبترجّعه مكانه لما البحث يتقفل
    private var searchEt: EditText? = null
    private var searchPage: LinearLayout? = null
    private var searchIdx: List<SHit>? = null
    private val moved = ArrayList<MV>()
    private var searching = false
    private var silent = false
    private val sh = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var frameV: LinearLayout
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
        // (v1014) 🔎 بحث في كل الإعدادات (مطابقة مرنة: تشكيل/همزات/ال/غلطات إملائية بسيطة/مرادفات)
        searchPage = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val sEt = ui.input("🔎 ابحث عن أي إعداد…  (مثلًا: مشغل · صوت · بطارية)", "")
        searchEt = sEt
        sEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                if (silent) return
                sh.removeCallbacksAndMessages(null)
                val q = e?.toString() ?: ""
                sh.postDelayed({ runSearch(q) }, 200L)
            }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        root.addView(sEt, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) })

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
            // (v927) وصف القسم اتشال (نص شارح)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) })
            row.addView(IconTextView(act).apply { text = "‹"; textSize = 20f; setTextColor(th.muted); gravity = Gravity.CENTER })
            rowViews.add(row)
            listPage.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4)) })
        }

        val frame = LinearLayout(act).apply { orientation = if (split) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        frameV = frame
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
        dialog.onBack = { if (searching) { endSearch(); true } else if (cur >= 0) { back(); true } else false }
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
        if (searching) endSearch()
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
        if (searching) { endSearch(); return }
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

    // ===================== (v1014) بحث الإعدادات =====================
    private fun moveInto(v: View, to: android.view.ViewGroup) {
        val p = v.parent as? android.view.ViewGroup ?: return
        moved.add(MV(v, p, p.indexOfChild(v), v.layoutParams))
        p.removeView(v); to.addView(v)
    }

    /** بيرجّع كل إعداد اتنقل لنتايج البحث مكانه الأصلي بالظبط (عكس ترتيب النقل عشان الـindex يفضل صح) */
    private fun restoreMoved() {
        for (m in moved.asReversed()) {
            try {
                (m.v.parent as? android.view.ViewGroup)?.removeView(m.v)
                val at = m.idx.coerceIn(0, m.p.childCount)
                if (m.lp != null) m.p.addView(m.v, at, m.lp) else m.p.addView(m.v, at)
            } catch (e: Throwable) { LogStore.err("Settings:restore", e) }
        }
        moved.clear()
    }

    fun endSearch(clearText: Boolean = true) {
        sh.removeCallbacksAndMessages(null)
        restoreMoved()
        if (searching) {
            searching = false
            try { scrollBox.removeAllViews(); scrollBox.addView(frameV) } catch (_: Throwable) {}
        }
        searchPage?.removeAllViews()
        if (clearText) { silent = true; try { if (!searchEt?.text.isNullOrEmpty()) searchEt?.setText("") } finally { silent = false } }
    }

    private fun walkIdx(g: android.view.ViewGroup, tab: Int, secT: String, out: MutableList<SHit>) {
        var pending: TextView? = null
        val kids = ArrayList<View>(); for (k in 0 until g.childCount) kids.add(g.getChildAt(k))
        for (c in kids) {
            val m = SettingsIdx.meta[c]
            val st = SettingsIdx.sec[c]
            if (m != null) {
                val lab = pending
                out.add(SHit(tab, secT, lab, c, SettingsFuzzy.words(m + " " + (lab?.text ?: "")), SettingsFuzzy.words(secT)))
                pending = null
            } else if (st != null) {
                pending = null
                if (c is android.view.ViewGroup) walkIdx(c, tab, st, out)
            } else if (c is EditText) {
                pending = null
            } else if (c is TextView) {
                if (c.text.isNotBlank() && c.text.length < 160) pending = c
            } else if (c is android.view.ViewGroup) {
                pending = null
                walkIdx(c, tab, secT, out)
            } else pending = null
        }
    }

    private fun buildSearchIndex(): List<SHit> {
        val out = ArrayList<SHit>()
        for (i in tabs.indices) {
            if (!isShown(i)) continue
            ensureBody(i)
            try { walkIdx(bodies[i], i, "", out) } catch (e: Throwable) { LogStore.err("Settings:idx:" + tabs[i].id, e) }
        }
        return out
    }

    private class Sc(val h: SHit, val own: Int, val sec: Int, val tab: Int)

    private fun runSearch(q0: String) {
        val q = q0.trim()
        if (q.isEmpty()) { endSearch(false); return }
        val pg = searchPage ?: return
        restoreMoved()
        if (!searching) { searching = true; try { scrollBox.removeAllViews(); scrollBox.addView(pg) } catch (_: Throwable) {} }
        pg.removeAllViews()
        var idx = searchIdx
        if (idx == null) {
            idx = try { buildSearchIndex() } catch (e: Throwable) { LogStore.err("Settings:index", e); emptyList() }
            searchIdx = idx
        }
        val toks = SettingsFuzzy.queryTokens(q)
        val tabWords = tabs.map { SettingsFuzzy.words(it.label) }
        val scored = ArrayList<Sc>()
        if (toks.isNotEmpty()) for (h in idx) {
            var o = 0; var s = 0; var t = 0
            for (alts in toks) {
                if (SettingsFuzzy.hit(alts, h.ownW)) o++
                else if (SettingsFuzzy.hit(alts, h.secW)) s++
                else if (SettingsFuzzy.hit(alts, tabWords[h.tab])) t++
            }
            if (o + s + t > 0) scored.add(Sc(h, o, s, t))
        }
        var pick: List<Sc> = scored.filter { it.own + it.sec + it.tab == toks.size }.ifEmpty { scored }   // كل الكلمات أولًا، وإلا أي كلمة
        if (pick.any { it.own + it.sec > 0 }) pick = pick.filter { it.own + it.sec > 0 }                 // تطابق على التبويب بس مايزاحمش
        pick = pick.sortedWith(compareByDescending<Sc> { it.own * 4 + it.sec * 2 + it.tab }.thenBy { it.h.tab })
        if (pick.isEmpty()) {
            pg.addView(ui.text("مفيش إعداد مطابق — جرّب كلمة تانية", 13f, ui.th.muted).apply { setPadding(ui.dp(8), ui.dp(22), ui.dp(8), ui.dp(8)) })
            return
        }
        for (sc in pick.take(30)) {
            val h = sc.h
            val card = ui.card()
            val path = tabs[h.tab].label + (if (h.secTitle.isNotBlank()) "  ›  " + h.secTitle.replace(Regex("^[▸▾]\\s*"), "") else "")
            card.addView(ui.text(path, 11.5f, ui.th.primary, true).apply {
                setPadding(0, 0, 0, ui.dp(6)); Glass.pressable(this)
                setOnClickListener { val t = h.tab; endSearch(); select(t) }   // فتح القسم نفسه
            })
            h.label?.let { moveInto(it, card) }
            moveInto(h.ctl, card)
            pg.addView(card)
        }
        if (pick.size > 30) pg.addView(ui.text("＋ " + (pick.size - 30) + " نتيجة تانية — حدّد كلمة البحث أكتر", 12f, ui.th.muted).apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
    }

    fun select(i: Int, fromFocus: Boolean = false) {
        if (i !in tabs.indices) return
        if (searching) endSearch()
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
        endSearch()
        searchIdx = null
        searchEt?.visibility = if (ids == null) View.VISIBLE else View.GONE
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



// ===================== (v1014) فهرس بحث الإعدادات =====================
/** كل ui.switchRow/chips/slider/button بيسجّل نصه هنا، وكل ui.section بيسجّل عنوانه — بحث الإعدادات بيمشي على الـViews الحيّة دي */
object SettingsIdx {
    val meta = java.util.WeakHashMap<View, String>()
    val sec = java.util.WeakHashMap<View, String>()
    fun reg(v: View, text: String) { try { meta[v] = text } catch (_: Throwable) {} }
}

internal class SHit(val tab: Int, val secTitle: String, val label: TextView?, val ctl: View, val ownW: List<String>, val secW: List<String>)
internal class MV(val v: View, val p: android.view.ViewGroup, val idx: Int, val lp: android.view.ViewGroup.LayoutParams?)

/** مطابقة مرنة عربي/إنجليزي: بيشيل التشكيل والتطويل، يوحّد الهمزات (أإآ→ا) والياء/الألف المقصورة والتاء المربوطة، يشيل «ال»، يسامح في غلطة إملائية واحدة (اتنين في الكلمات الطويلة)، ومرادفات قليلة (مشغل=player…) */
object SettingsFuzzy {
    private val STOP = setOf("نوع", "اعداد", "اعدادات", "عايز", "عاوز", "ابحث", "عن", "في", "من", "علي", "على", "ده", "دي", "بتاع", "بتاعه", "اختيار", "setting", "settings", "the")
    private val SYN = mapOf(
        "مشغل" to listOf("player", "بلاير"), "player" to listOf("مشغل"),
        "ترجمه" to listOf("سبتايتل", "subtitle", "translation"), "سبتايتل" to listOf("ترجمه"),
        "صوت" to listOf("audio", "volume"), "audio" to listOf("صوت"),
        "فيديو" to listOf("video"), "video" to listOf("فيديو"),
        "خلفيه" to listOf("background"), "بطاريه" to listOf("battery"),
        "مفتاح" to listOf("key", "keys", "مفاتيح"), "مفاتيح" to listOf("مفتاح", "key"),
        "جوده" to listOf("quality"), "سرعه" to listOf("speed"),
        "ثيم" to listOf("مظهر", "theme"), "مظهر" to listOf("ثيم", "theme"),
        "خط" to listOf("font"), "لهجه" to listOf("dialect"), "تحميل" to listOf("download"), "اشعار" to listOf("notification"))

    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c0 in s.lowercase()) {
            if (c0 in '\u064B'..'\u0652' || c0 == '\u0670' || c0 == '\u0640') continue
            val c = when (c0) { 'أ', 'إ', 'آ', 'ٱ' -> 'ا'; 'ى' -> 'ي'; 'ة' -> 'ه'; 'ؤ' -> 'و'; 'ئ' -> 'ي'; else -> c0 }
            sb.append(if (c.isLetterOrDigit()) c else ' ')
        }
        return sb.toString()
    }

    fun words(s: String): List<String> = norm(s).split(' ').filter { it.isNotBlank() }.map { if (it.length > 4 && it.startsWith("ال")) it.substring(2) else it }

    /** كل عنصر = كلمة بحث + مرادفاتها (أي واحدة منهم تكفي) */
    fun queryTokens(q: String): List<List<String>> {
        val w = words(q)
        val keep = w.filter { it !in STOP }.ifEmpty { w }
        return keep.map { t -> listOf(t) + (SYN[t] ?: emptyList()) }
    }

    private fun lev(a: String, b: String, lim: Int): Int {
        if (Math.abs(a.length - b.length) > lim) return lim + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1))
            prev = cur
        }
        return prev[b.length]
    }

    private fun one(t: String, words: List<String>): Boolean {
        for (w in words) {
            if (w == t) return true
            if (t.length >= 3 && (w.contains(t) || (w.length >= 3 && t.contains(w)))) return true
            if (t.length >= 4 && w.length >= 4 && lev(t, w, if (t.length >= 8) 2 else 1) <= (if (t.length >= 8) 2 else 1)) return true
        }
        return false
    }

    fun hit(alts: List<String>, words: List<String>): Boolean = alts.any { one(it, words) }
}
