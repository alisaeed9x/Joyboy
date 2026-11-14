package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
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
        /** (v732) زرار «الكليبورد»: بيفتح سجل الكليبورد الدائم مباشرة (مش بوابة) */
        const val CLIP = 13
        /** (v815) زرار «النت» الحي: مكان «المخزن» في الشريط السفلي — بيعرض سرعة التحميل لايف، ودوسة = صفحة الداتا (جواها لوج النت). «المخزن» راح لـ«المزيد» */
        const val NET = 14
        /** (v821) زرار «ريموت IR»: كتالوج الماركات والموديلات وحفظ البروفايلات (بيفتح مباشرة، مش بوابة) */
        const val IR = 15
        /** (v829) زرار «العناية»: واخد مكان «ريموت IR» في الشريط الأساسي (IR راح لـ«المزيد») — بيفتح CareUi مباشرة */
        const val CARE = 16
        /** (v472) «الذكاء» تبويب اتمسح قديمًا — الرقم 6 محجوز عشان ترقيم البوابات ما يتغيّرش، لكن مش بيظهر في أي حته */
        const val DEAD_AI = 6
        /** (v467) ترتيب الشريط بطلب صاحب التطبيق: الفيديوهات ← يوتيوب ← الموسيقى ← المتصفح ← المزيد.
         *  أي بوابة مش في الشريط بتظهر تلقائيًا جوه «المزيد» — فأي إضافة جديدة هتبان من غير تعديل هنا. */
        val BAR_ORDER = listOf(0, REMOTE, 4, 2, CARE, NET, 8)   // (v654) 1 (الموسيقى) اتدمجت مع 0 في زرار «ميديا»
        /** (v475) خلايا يوتيوب الافتراضية (مش بوابات): الرئيسية 9 · الاشتراكات 10 · أنا 11 — بتحل محل خلية «يوتيوب» وانت جواه */
        const val YT_HOME = 9; const val YT_SUBS = 10; const val YT_ME = 11
        val NORMAL_ORDER = BAR_ORDER
        val YT_ORDER = listOf(0, YT_HOME, YT_SUBS, YT_ME, NET, MORE)  // نسخة كاملة وانت في يوتيوب: الموسيقى والمتصفح بيروحوا لـ«المزيد» · (v829) زرار النت بعد «أنا» (كان قبلها)
        val LITE_ORDER = listOf(YT_HOME, YT_SUBS, 0, YT_ME)           // نسخة اللايت: الرئيسية · الاشتراكات · محلي · أنا
        val DEFS = listOf("🎞" to "الفيديوهات", "🎵" to "الموسيقى", "🌐" to "المتصفح", "🗂" to "المخزن", "▶" to "يوتيوب", "📋" to "المهام", "" to "", "⚙️" to "الإعدادات", "⋯" to "المزيد",
            "🏠" to "الرئيسية", "📺" to "الاشتراكات", "👤" to "أنا", "📱" to "الريموت", "📎" to "الكليبورد", "📶" to "", "📡" to "ريموت IR", "🩺" to "العناية")
    }
    /** البوابات اللي ورا ⋯ المزيد: أي بوابة مش في الشريط (حاليًا: الملفات 3 · المهام 5 · الذكاء 6 · الإعدادات 7) — الزرار بيتلوّن لما واحدة منهم تبقى هي النشطة */
    private val lite = act.packageName.endsWith(".lite")
    private val tvDev = try { DeviceUi.isTv(act) } catch (_: Throwable) { false }   // (v521) على التلفزيون زرار الريموت مخفي
    private fun normalOrder(): List<Int> = NORMAL_ORDER.filter { it != REMOTE && !(it == IR && tvDev) }.map { if (it == NET && tvDev) 3 else it }   // (v815) على التلفزيون «المخزن» يفضل مكانه
    private fun ytOrder(): List<Int> = YT_ORDER.filter { it != NET || !tvDev }   // (v823) على التلفزيون زرار النت مش في الشريط
    private var order: List<Int> = if (lite) LITE_ORDER else normalOrder()   // (v554) زرار الريموت اتشال: وضع التلفزيون بزرار عايم
    private fun cellOf(a: Int): Int = if (a in order) a else if (a == 1 && !lite && 0 in order) 0 else if (a == 4 && YT_HOME in order) YT_HOME else if ((a == YT_HOME || a == YT_SUBS || a == YT_ME) && 4 in order) 4 else if (lite) (if (a == 7) YT_ME else -1) else MORE
    // ===== (v608) «المزيد» = صف تاني بينزلق لفوق جنب الصف الأساسي (بدل قايمة منبثقة). الريل (تابلت) لسه بالقايمة =====
    private var moreOpen = false
    /** (v820) بيتنده لما صف «المزيد» يتفتح/يتقفل (كبسولة إعدادات الصفحة بتختفي وهو مفتوح) */
    var onMoreToggle: ((Boolean) -> Unit)? = null
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
        val ids = DEFS.indices.filter { it != MORE && it != DEAD_AI && (it < YT_HOME || (it == IR && !tvDev)) && it !in order && !(it == 4 && YT_HOME in order) && !(it == 1 && !lite && 0 in order) }
        val items = ArrayList<MoreItem>()
        for (id in ids.filter { it != 7 }) items.add(MoreItem(DEFS[id].first, DEFS[id].second, id == active) { collapseMore(); if (id == IR) IrCatalogUi.show(act) else onPick(id) })
        if (!tvDev) {
            items.add(MoreItem("📺", "وضع TV", PlayTarget.enabled(), {
                // دوسة طويلة: باقي خيارات التلفزيون
                val labels = arrayOf(TvModeFab.alwaysLabel(), "📤  إرسال التحديث للتلفزيون")
                GAlert(act).setTitle("📺 التلفزيون").setItems(labels) { _, i -> collapseMore(); if (i == 0) TvModeFab.toggleAlways(act) else RemoteClient.forcePushFlow(act) }.setNegativeButton("إغلاق", null).show()
            }) { collapseMore(); TvModeFab.toggle(act) })
        }
        if (CARE !in order) items.add(MoreItem("🩺", "العناية", false) { collapseMore(); try { CareUi(act, ui, th).show() } catch (e: Throwable) { LogStore.err("Nav:care", e) } })   // (v825) · (v829) في الشريط الأساسي بقت زرار — هنا بس وانت في يوتيوب
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
        onMoreToggle?.invoke(open)
        val target = if (open) ui.dp(57) + (if (tvDev) 0 else ui.dp(46)) else 0   // (v612) + صف زرار «إرسال التحديث للتلفزيون»
        moreAnim?.cancel()
        val lp0 = host.layoutParams
        moreAnim = android.animation.ValueAnimator.ofInt(lp0.height, target).apply {
            duration = 260L; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { a -> val lp = host.layoutParams; lp.height = a.animatedValue as Int; host.layoutParams = lp }
            start()
        }
        icons.getOrNull(MORE)?.animate()?.rotation(if (open) 90f else 0f)?.setDuration(220L)?.start()
    }

    /** (v855) «المزيد» بقى شبكة مربعات فيها كل الأقسام والميزات (بدل الصف التاني اللي كان بينزلق) */
    private fun toggleMore(act: Activity) { moreSheet(act) }
    private fun collapseMore() { if (moreOpen) animateMore(false) }

    /** (v855) فتح المخزن على تبويب (fm ملفات · ft إرسال واستقبال) — لو إحنا أصلًا جواه بيبدّل الشاشة */
    private fun openHub(act: Activity, tab: String, open: String = "") {
        try {
            act.startActivity(FilesHub.open(act, tab, open))
            if (act is FileManagerActivity || act is FileTransferActivity) { act.finish(); act.overridePendingTransition(0, 0) }
        } catch (e: Throwable) { LogStore.err("Nav:hub", e) }
    }

    /** (v855) كل الأقسام والميزات مجمّعة في مجموعات (اسم المجموعة ← مربعاتها) */
    private fun moreGroups(act: Activity): List<Pair<String, List<MoreItem>>> {
        // (v861) ترتيب جديد: كل مجموعة مضبوطة على 3 في الصف، والأيقونات مش مكرّرة
        val secs = ArrayList<MoreItem>()
        for (id in listOf(0, 4, 2, 1, 3, 5)) secs.add(MoreItem(DEFS[id].first, if (id == 0) "الفيديوهات" else DEFS[id].second, id == active) { onPick(id) })   // فيديوهات · يوتيوب · متصفح · موسيقى · مخزن · مهام
        val trl = ArrayList<MoreItem>()
        if (!tvDev) trl.add(MoreItem("📚", "مكتبة الترجمات", false) { try { TrLibUi(act, ui, th).show() } catch (e: Throwable) { LogStore.err("Nav:trlib", e) } })   // (v990)
        if (!tvDev) trl.add(MoreItem("🎓", "تفريغ المحاضرات", false) { try { LectureUi(act, ui, th).show() } catch (e: Throwable) { LogStore.err("Nav:lecture", e) } })   // (v991)
        val store = listOf(
            MoreItem("📂", "مدير الملفات", false) { openHub(act, "fm") },
            MoreItem("↔", "إرسال واستقبال", false) { openHub(act, "ft") },
            MoreItem("\uE001", "اقتران جهاز بلوتوث", false) { openHub(act, "ft", "bt") }
        )
        val ctl = ArrayList<MoreItem>()
        if (!tvDev && !lite) ctl.add(MoreItem("📱", "الريموت", false) { try { ControllerPad.show(act) } catch (e: Throwable) { LogStore.err("Nav:remote", e) } })
        if (!tvDev) ctl.add(MoreItem("📡", "ريموت IR", false) { try { IrCatalogUi.show(act) } catch (e: Throwable) { LogStore.err("Nav:ir", e) } })
        if (!tvDev) ctl.add(MoreItem("📺", "وضع TV", PlayTarget.enabled(), {
            val labels = arrayOf(TvModeFab.alwaysLabel())
            GAlert(act).setTitle("📺 التلفزيون").setItems(labels) { _, _ -> TvModeFab.toggleAlways(act) }.setNegativeButton("إغلاق", null).show()
        }) { TvModeFab.toggle(act) })
        val sys = ArrayList<MoreItem>()
        sys.add(MoreItem("🩺", "العناية", false) { try { CareUi(act, ui, th).show() } catch (e: Throwable) { LogStore.err("Nav:care", e) } })
        sys.add(MoreItem("📊", "النت والداتا", false) { try { NetStatsUi(act, ui, th).show() } catch (e: Throwable) { LogStore.err("Nav:net", e) } })
        if (!tvDev) sys.add(MoreItem("🔄", "مركز التحديث", false) { try { GhCiUi.show(act) } catch (e: Throwable) { LogStore.err("Nav:gh", e) } })   // (v861) تحديث البرنامج + تحديث التلفزيون + لوج الفشل
        sys.add(MoreItem(DEFS[7].first, "الإعدادات", 7 == active) { onPick(7) })
        return listOf("الأقسام" to secs, "الترجمة" to trl, "المخزن والإرسال" to store, "تحكم وريموت" to ctl, "النظام والتحديث" to sys)
    }

    private fun tileView(act: Activity, t: MoreItem, onDone: () -> Unit): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(7))   // (v991) بلاطات أصغر عشان تتسع كلها (4 في الصف على الموبايل)
        background = ui.box(if (t.on) Glass.alpha(th.primary, 0x28) else th.card, if (t.on) th.primary else th.border, 14, if (t.on) 2 else 1)
        val ic = IconTextView(act).apply { text = t.emoji; textSize = 21f; gravity = Gravity.CENTER; includeFontPadding = false }
        val lb = ui.text(t.label, 10.5f, if (t.on) th.primary else th.text, true).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, ui.dp(4), 0, 0) }
        addView(ic, LinearLayout.LayoutParams(-2, ui.dp(28)))
        addView(lb, LinearLayout.LayoutParams(-1, -2))
        isClickable = true; isFocusable = true
        Glass.pressable(this)
        setOnClickListener { onDone(); t.tap() }
        val lpress = t.longPress
        if (lpress != null) setOnLongClickListener { onDone(); lpress(); true }
    }

    private fun moreSheet(act: Activity) {
        val d = android.app.Dialog(act)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val cols = if (rail || act.resources.displayMetrics.widthPixels >= ui.dp(600)) 5 else 4   // (v991) كانت 4/3
        val glass = MtGlass.on()
        val sheetR = ui.dp(28).toFloat()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(14))
            // (v910) زجاج: لوح عايم بحواف مدوّرة كلها (زي شريط MeeTube) — بلور MeeTube الحقيقي (SheetGlass.frost) بيتحط عليه بعد العرض؛ اللون هنا مؤقت (من غير حافة عشان ما تتكررش)
            background = if (glass) GradientDrawable().apply { setColor(MtGlass.sheet(th)); cornerRadius = sheetR }
                else GradientDrawable().apply { setColor(th.surface); cornerRadii = floatArrayOf(ui.dp(20).toFloat(), ui.dp(20).toFloat(), ui.dp(20).toFloat(), ui.dp(20).toFloat(), 0f, 0f, 0f, 0f) }
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(4)) }
        head.addView(ui.text("كل الأقسام والميزات", 16f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        val x = ui.text("✕", 18f, th.muted, true).apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(4), ui.dp(4)); isClickable = true }
        Glass.pressable(x)
        x.setOnClickListener { d.dismiss() }
        head.addView(x, LinearLayout.LayoutParams(-2, -2))
        box.addView(head, LinearLayout.LayoutParams(-1, -2))
        val inner = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        for ((title, tiles) in moreGroups(act)) {
            if (tiles.isEmpty()) continue
            inner.addView(ui.text(title, 12.5f, th.muted, true).apply { setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(2)) }, LinearLayout.LayoutParams(-1, -2))
            var row: LinearLayout? = null
            for ((i, t) in tiles.withIndex()) {
                if (i % cols == 0) {
                    row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                    inner.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
                }
                val rem0 = tiles.size % cols
                val lone = rem0 == 1 && i == tiles.size - 1 && tiles.size > 1   // (v861) مربع لوحده في آخر صف ← ياخد عرض الصف كله
                row?.addView(tileView(act, t) { d.dismiss() }, LinearLayout.LayoutParams(0, -2, if (lone) cols.toFloat() else 1f).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
            }
            val rem = tiles.size % cols
            if (rem > 1) for (k in 0 until (cols - rem)) row?.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
        }
        val sc = android.widget.ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(inner) }
        box.addView(sc, LinearLayout.LayoutParams(-1, -2))
        if (glass) {   // (v910) هوامش 12dp من الجنب والتحت: اللوح عايم زي الشريط (الركن السفلي مدوّر مش مقصوص)
            val wrap = FrameLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(12)) }
            wrap.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            d.setContentView(wrap)
        } else d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setGravity(Gravity.BOTTOM); setLayout(-1, -2)
        }
        moreOpen = true; onMoreToggle?.invoke(true)
        d.setOnDismissListener { moreOpen = false; onMoreToggle?.invoke(false) }
        TvFocus.attachDialog(act, d, th.primary)
        if (glass) try { Glass.styleWindow(d, windowBlur = false) } catch (_: Throwable) {}   // (v897) تعتيم خفيف · (v910) من غير بلور النافذة كلها: الصفحة بتفضل حادّة والشيت بس هو المبلّر
        d.show()
        if (glass) try { d.window?.decorView?.post { Glass.enter(d) } } catch (_: Throwable) {}
        if (glass) Glass.frostSheet(d, box, sheetR, MeeRes.color("meetube_glass_tint", 0x66000000, 0x66FAFAFA))   // (v915) نفس tint زجاج الشريط (meetube_glass_tint) بدل لون الشيت الكتيم -   // (v910) بلور MeeTube الحقيقي (كان بلور النافذة اللي مش بيبان على أجهزة كتير)
    }

    private fun navBg(): Int = if (MtGlass.on()) MtGlass.barSolid(th) else th.card   // (v893) زجاج MeeTube
    private fun navBgD(): android.graphics.drawable.Drawable =
        if (floatMode) android.graphics.drawable.ColorDrawable(0) else android.graphics.drawable.ColorDrawable(navBg())
    private var floatMode = false   // (v895) شريط عايم: من غير خلفية صلبة — الزجاج بيجي من MtGlassView
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
    private var navPending = false   // (v911) دوسة تبويب لسه مستنية تخلّص الأنيميشن قبل ما نبدّل الشاشة
    // (v939) عدّاد إعادة محاولة وضع الكبسولة: الخلية لسه ما اتقاستش — بنعيد لحد 10 مرات وبعدين نقف بدل ما ندوّر للأبد
    private var placeTries = 0
    private lateinit var rowLL: LinearLayout
    private val glassNav = MtGlass.on() && !rail   // (v893) تبويب مضيء (lit tab) بدل خط المؤشّر
    private val indicator = View(act).apply {
        background = GradientDrawable().apply {
            if (glassNav) { setColor(MtGlass.selected(th)); cornerRadius = ui.dp(22).toFloat(); setStroke(1, MtGlass.rim(th)) }
            else { setColor(th.primary); cornerRadius = ui.dp(2).toFloat() }
        }
    }

    init {
        val line = View(act).apply { setBackgroundColor(if (MtGlass.on()) MtGlass.rim(th) else th.border) }
        val row = LinearLayout(act).apply {
            orientation = if (rail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            background = navBgD()
        }
        // (v336) الأرقام ثابتة (0 فيديوهات · 1 موسيقى · 2 متصفح · 3 ملفات · 4 يوتيوب · 5 مهام) لكن ترتيب العرض: يوتيوب قبل الملفات، والمهام رجعت زرار في الشريط
        val defs = DEFS
        rowLL = row
        defs.forEachIndexed { idx, d ->
            val icon = IconTextView(act).apply { text = d.first; textSize = 16f; gravity = Gravity.CENTER; includeFontPadding = false }
            val lab = ui.text(if (lite && idx == 0) "محلي" else if (!lite && idx == 0) "ميديا" else d.second, 11f, th.muted, true).apply { gravity = Gravity.CENTER; setSingleLine() }   // (v458) كانت 9f صغيرة قوي
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
                    addView(icon, if (idx == NET) FrameLayout.LayoutParams(ui.dp(54), ui.dp(30), Gravity.CENTER) else FrameLayout.LayoutParams(ui.dp(24), ui.dp(20), Gravity.CENTER))
                    addView(bdg, FrameLayout.LayoutParams(-2, ui.dp(14), Gravity.TOP or Gravity.END))
                }
                addView(box, if (idx == NET) LinearLayout.LayoutParams(ui.dp(54), ui.dp(30)) else LinearLayout.LayoutParams(ui.dp(38), ui.dp(20)))
                addView(lab, LinearLayout.LayoutParams(-2, -2))
                Glass.pressable(this)
                setOnClickListener { if (idx == MORE) { set(idx); toggleMore(act) } else if (idx == REMOTE) { set(idx); try { ControllerPad.show(act) } catch (e: Throwable) { LogStore.err("Nav:remote", e) } } else if (idx == CARE) { set(idx); collapseMore(); try { CareUi(act, ui, th).show() } catch (e: Throwable) { LogStore.err("Nav:care", e) } } else if (idx == IR) { set(idx); collapseMore(); try { IrCatalogUi.show(act) } catch (e: Throwable) { LogStore.err("Nav:ir", e) } } else if (idx == NET) { set(idx); collapseMore(); try { NetStatsUi(act, ui, th).show() } catch (e: Throwable) { LogStore.err("Nav:net", e) } } else { collapseMore(); val leaves = act !is MainActivity || idx == 2 || idx == 3 || idx == 4   // (v911) كل اللي بيفتح شاشة تانية (متصفح/مخزن/يوتيوب)؛ التبويبات الداخلية في Main عندها أنيميشنها
                    if (leaves && !rail && cellOf(idx) != cellOf(active) && !navPending) {   // (v911) الشاشات التانية Activity لوحدها: كانت بتتفتح/تتقفل لحظة الدوسة فمفيش عدسة ولا انزلاق ولا ضغطة، والفتح الثقيل كان بيتزامن مع الأنيميشن فيقطّع. دلوقتي الأنيميشن بيخلّص الأول وبعدها الانتقال
                        val prev = active; val inMain = act is MainActivity
                        navPending = true; set(idx)
                        // (v914) الأنيميشن والانتقال مع بعض: الانتقال بيبدأ في أول فريم (والعدسة شغّالة على الشريط القديم وهو بيتلاشى) بدل ما يستنى 300ms
                        postOnAnimation {
                            onPick(idx)
                            // Main فاضلة تحت الشاشة الجديدة: نرجّع الكبسولة للتبويب اللي Main لسه عليه بعد ما الانتقال يغطّيها، وإلا لما نرجع هنلاقيها على التبويب اللي مشينا منه — بس لو المستخدم لسه ما بدّلش تبويب تاني في النص
                            if (inMain) postDelayed({ if (active == idx) set(prev) }, 600L)
                            postDelayed({ navPending = false }, 600L)
                        }
                    } else onPick(idx) } }
            }
            icons.add(icon); labels.add(lab); cells.add(cell)
        }
        fillRow()
        val wrap = FrameLayout(act)
        wrap.addView(row, if (rail) FrameLayout.LayoutParams(-2, -1) else FrameLayout.LayoutParams(-1, -2))
        wrap.addView(indicator, if (rail) FrameLayout.LayoutParams(ui.dp(2), ui.dp(22), Gravity.START or Gravity.CENTER_VERTICAL) else if (glassNav) FrameLayout.LayoutParams(ui.dp(56), ui.dp(46), Gravity.TOP or Gravity.LEFT) else FrameLayout.LayoutParams(ui.dp(22), ui.dp(2), Gravity.TOP or Gravity.LEFT))
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!moving && snap) { if (place(false)) snap = false } }
        rowV = row; lineV = line
        if (rail) { view.addView(wrap, LinearLayout.LayoutParams(0, -1, 1f)); view.addView(line, LinearLayout.LayoutParams(ui.dp(1), -1)) }
        else {
            view.addView(line, LinearLayout.LayoutParams(-1, ui.dp(1)))
            // (v608) الصف التاني (المزيد): ارتفاعه 0 وهو مقفول؛ بيطلع لفوق فوق الصف الأساسي وهو مفتوح
            val host = FrameLayout(act).apply { clipChildren = true; setBackgroundColor(navBg()) }
            val sc = android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; overScrollMode = View.OVER_SCROLL_NEVER }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            if (!tvDev) {   // (v612) زرار عريض فوق صف «المزيد»: إرسال التحديث للتلفزيون
                val upd = ui.text("📤  إرسال التحديث للتلفزيون", 15f, th.primary, true).apply { gravity = Gravity.CENTER; setSingleLine() }
                Glass.pressable(upd)
                upd.setOnClickListener { collapseMore(); try { RemoteClient.forcePushFlow(act) } catch (e: Throwable) { LogStore.err("Nav:pushUpd", e) } }
                col.addView(upd, LinearLayout.LayoutParams(-1, ui.dp(45)))
                col.addView(View(act).apply { setBackgroundColor(th.border) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
            }
            col.addView(sc, LinearLayout.LayoutParams(-1, ui.dp(56)))
            col.addView(View(act).apply { setBackgroundColor(th.border) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
            host.addView(col, FrameLayout.LayoutParams(-1, ui.dp(57) + (if (tvDev) 0 else ui.dp(46)), Gravity.BOTTOM))
            moreHost = host; moreScroll = sc
            view.addView(host, LinearLayout.LayoutParams(-1, 0))
            view.addView(wrap, LinearLayout.LayoutParams(-1, -2))
        }
        paint()
        badge(4, 0)
        // (v941) وضع الكبسولة أول مرة تحت الخلية النشطة: الشريط اللي بيتبني في شاشة تانية (متصفح/ملفات/يوتيوب) كان بيسيب الكبسولة على الحافة
        rowLL.post { place(false) }
        startLiveNet(act)
    }

    /** (v815) زرار النت = الكبسولة نفسها: سطرين زي الكبسولة المصغّرة (📶 داتا الجلسة · 🛜 واي فاي الجلسة) بخلفيتها الملوّنة (أزرق = موبايل · أخضر = واي فاي · برتقالي/أحمر = قرّب/عدّى حد اليوم) + تحتها سرعة التحميل لايف. بيتحدّث كل ثانية وهو ظاهر */
    private fun netSpeed(b: Double): String = when {
        b < 1024.0 -> "0 K/s"
        b < 1048576.0 -> String.format(java.util.Locale.US, "%.0fK/s", b / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1fM/s", b / 1048576.0)
    }
    private fun startLiveNet(act: Activity) {
        if (NET !in icons.indices) return
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val den = act.resources.displayMetrics.density
        val bg = GradientDrawable().apply { cornerRadius = 9f * den }
        var lastCol = 0
        icons[NET].apply { textSize = 9.5f; setLineSpacing(0f, 0.95f); maxLines = 2; background = bg; setTextColor(0xFFFFFFFF.toInt()); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        labels[NET].textSize = 10f
        val r = object : Runnable {
            override fun run() {
                try {
                    val i = icons[NET]; val l = labels[NET]
                    i.text = "📶 " + DataMeter.fmt(DataMeter.sCell) + "\n🛜 " + DataMeter.fmt(DataMeter.sWifi)
                    i.setTextColor(0xFFFFFFFF.toInt())
                    l.text = "⬇ " + netSpeed(DataMeter.speed)
                    val lim = (act.getSharedPreferences("p", 0).getString("sv_lim_day", "0")?.toLongOrNull() ?: 0L) * 1048576L
                    val col = when {
                        lim > 0 && DataMeter.dCell >= lim -> 0xFFB71C1C.toInt()
                        lim > 0 && DataMeter.dCell >= lim * 8 / 10 -> 0xFFE65100.toInt()
                        DataMeter.isCell(act) -> 0xFF1E2A3A.toInt()
                        else -> 0xFF244A36.toInt()
                    }
                    if (col != lastCol) { lastCol = col; bg.setColor(col) }
                } catch (_: Throwable) {}
                if (view.isAttachedToWindow) h.postDelayed(this, 1000L)
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { h.removeCallbacks(r); h.post(r) }
            override fun onViewDetachedFromWindow(v: View) { h.removeCallbacks(r) }
        })
    }

    /** (v350) عدّاد أحمر فوق أيقونة البوابة؛ 0 = مخفي */
    /** (v895) الشريط عايم فوق المحتوى جوه زجاج حقيقي (MtFloat): شيل الخلفية الصلبة والخط العلوي */
    fun floatGlass() { floatMode = true; rowLL.background = android.graphics.drawable.ColorDrawable(0); lineV?.visibility = View.GONE }
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
        val o = if (lite) LITE_ORDER else if (inYoutube) ytOrder() else normalOrder()
        if (o == order) return
        order = o; snap = true; indicator.animate().cancel(); moving = false
        fillRow(); paint(); rowLL.requestLayout()
        rowLL.post { if (place(false)) snap = false }
    }

    private fun place(animate: Boolean): Boolean {
        indicator.visibility = if (cellOf(active) in cells.indices) View.VISIBLE else View.INVISIBLE
        val c = cells.getOrNull(cellOf(active)) ?: return false
        if (rail) {   // (v462) المؤشر شريط رأسي بيتحرك على Y
            if (c.height == 0) { if (placeTries++ < 10) rowLL.post { place(false) }; return false }
            val y = (c.top + c.height / 2 - ui.dp(11)).toFloat()
            if (animate) { moving = true; indicator.animate().cancel(); indicator.animate().setStartDelay(0L).translationY(y).setDuration(300L).setInterpolator(OvershootInterpolator(1.4f)).withEndAction { moving = false }.start() }
            else { indicator.animate().cancel(); moving = false; indicator.translationY = y }
            placeTries = 0
            return true
        }
        if (c.width == 0) { if (placeTries++ < 10) rowLL.post { place(animate) }; return false }
        placeTries = 0
        var iw = ui.dp(22)
        if (glassNav) {   // (v893) المؤشّر = كبسولة زجاجية مضيئة بعرض الخلية تقريبًا
            iw = minOf(c.width - ui.dp(10), ui.dp(72)); val ih = ui.dp(46)
            val lp = indicator.layoutParams as FrameLayout.LayoutParams
            if (lp.width != iw || lp.height != ih) { lp.width = iw; lp.height = ih; indicator.layoutParams = lp }
            indicator.translationY = ui.dp(5).toFloat()
        }
        val x = (c.left + (c.width - iw) / 2).toFloat()
        if (animate) {
            moving = true
            indicator.animate().cancel()
            indicator.animate().setStartDelay(0L).translationX(x).setDuration(if (glassNav) 320L else 300L).setInterpolator(if (glassNav) MtGlass.EMPHASIZED else OvershootInterpolator(1.4f)).withEndAction { moving = false }.start()
        } else {
            indicator.animate().cancel(); moving = false
            indicator.translationX = x
        }
        return true
    }

    /** (v897) عدسة MeeTube: (من خلية · لخلية · لما العدسة تهدى) ← true لو العدسة شغّالة (الكبسولة بتستنى وتظهر بعدها) · false = زحلقة عادية */
    var lensHook: ((View, View, () -> Unit) -> Boolean)? = null

    fun set(i: Int) {
        val before = cellOf(active)
        val changed = cellOf(i) != before
        active = i; paint()
        if (snap) { rowLL.requestLayout(); rowLL.post { if (place(false)) snap = false } }
        else if (changed) {
            val from = cells.getOrNull(before); val to = cells.getOrNull(cellOf(i))
            var lensOn = false
            val hook = lensHook
            if (hook != null && !rail && from != null && to != null && to.width > 0 && glassNav) {
                try {
                    lensOn = hook(from, to) { indicator.animate().cancel(); indicator.animate().setStartDelay(0L).alpha(1f).setDuration(180L).start() }
                } catch (e: Throwable) { lensOn = false; LogStore.err("Nav:lens", e) }
            }
            if (lensOn) {
                indicator.animate().cancel(); indicator.alpha = 0f; place(false)
                indicator.postDelayed({ if (indicator.alpha < 1f) { indicator.alpha = 1f; place(false) } }, 900L)
            }
            // الكبسولة تستنى لحد ما العدسة تهدى؛ لو إشعار التهدئة ضاع (الأكتفيتي اتقفلت في النص) الـ900ms دي بترجّعها بدل ما تفضل مختفية
            else { indicator.alpha = 1f; place(true) }
            icons.getOrNull(cellOf(i))?.let { Motion.bounce(it) }
        }
    }
    /** (v135) نص تحت أيقونة البوابة (بيتغيّر لـ «المهام (2)» لما فيه مهام شغّالة) */
    fun label(i: Int, t: String) { if (i in labels.indices) labels[i].text = t }
    /** (v198) لون الشريط وهو مشغّل الموسيقى مفتوح (بيتبع لون الغلاف)؛ null = رجّعه للثيم */
    fun tint(c: Int?) {
        tinted = c != null
        // (v923) الشريط العايم: كان بيتلوّن بمربع صلب (ColorDrawable) فوق كبسولة الزجاج = شكل مربع بنفسجي غير منتظم. دلوقتي كبسولة مدوّرة شبه شفافة بنفس استدارة الزجاج
        val soft = if (c != null) (c and 0x00FFFFFF) or 0x55000000.toInt() else 0   // (v927) أشفّ: نفس شفافية الزجاج في باقي الصفحات
        rowV?.background = if (c != null) { if (floatMode) GradientDrawable().apply { setColor(soft); cornerRadius = ui.dp(28).toFloat() } else android.graphics.drawable.ColorDrawable(c) } else navBgD()
        moreHost?.setBackgroundColor(if (c != null) (if (floatMode) soft else c) else if (floatMode) 0 else navBg())
        lineV?.setBackgroundColor(if (c != null) 0x22FFFFFF else if (MtGlass.on()) MtGlass.rim(th) else th.border)
        paint()
    }
    private fun paint() {
        for (i in icons.indices) {
            val on = i == cellOf(active)
            val off = if (tinted) 0xAAFFFFFF.toInt() else if (glassNav) MtGlass.navInactive(th) else th.muted
            val onC = if (tinted) 0xFFFFFFFF.toInt() else if (glassNav) th.text else th.primary   // (v923) على لون مصبوغ (مخفي/غلاف موسيقى) الأيقونة النشطة بيضا مش لون الثيم
            icons[i].setTextColor(if (on) onC else off)
            labels[i].setTextColor(if (on) onC else off)
        }
        (indicator.background as? GradientDrawable)?.setColor(if (glassNav) (if (tinted) 0x33FFFFFF else MtGlass.selected(th)) else if (tinted) 0xFFFFFFFF.toInt() else th.primary)
    }
}
