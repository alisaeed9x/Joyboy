package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast

/** (v1273) تخصيص البلاطات: ترتيب/إخفاء أزرار الشريط السفلي وبلاطات «المزيد»، وإضافة أقسام الإعدادات كبلاطات، واختيار صفحة البداية.
 *  كله اختياري: من غير تخصيص الشريط و«المزيد» بيفضلوا زي ما هم. المخفي بيفضل ظاهر هنا عشان يرجعه. */
object NavCustom {
    const val K_BAR = "nav_bar"; const val K_ORDER = "nav_more_order"; const val K_HIDE = "nav_more_hide"; const val K_EXTRA = "nav_more_extra"; const val K_START = "start_page"
    /** بلاطة «الإعدادات» ماتتخفّاش في «المزيد» (عشان ماتتقفلش على نفسك) */
    const val PROTECTED = "الإعدادات"
    const val BAR_MAX = 5
    val BAR_OPTS = listOf(Triple("0", "🎞", "ميديا"), Triple("4", "▶", "يوتيوب"), Triple("2", "🌐", "المتصفح"), Triple("12", "📱", "الريموت"),
        Triple("14", "📶", "النت"), Triple("5", "⬇", "مركز التحميل"), Triple("7", "⚙️", "الإعدادات"))

    private fun csv(k: String): List<String> = try { Cfg.str(k, "").split(",").map { it.trim() }.filter { it.isNotEmpty() } } catch (_: Throwable) { emptyList() }
    private fun put(k: String, l: List<String>) { try { Cfg.put(k, l.joinToString(",")) } catch (_: Throwable) {} }
    private fun hasBar(): Boolean = csv(K_BAR).isNotEmpty()
    fun barTokens(): List<String> = if (hasBar()) csv(K_BAR).filter { it != "-" } else listOf("0", "12", "4", "2", "14")
    fun customTabs(): List<String> = barTokens().filter { it.startsWith("s:") }.map { it.substring(2) }
    /** ids الخلايا للشريط الأساسي (آخرها المزيد) أو null = الافتراضي */
    fun barIds(): List<Int>? {
        if (!hasBar()) return null
        val cs = customTabs(); val out = ArrayList<Int>()
        for (t in barTokens()) {
            val id = if (t.startsWith("s:")) BottomNav.DEFS.size + cs.indexOf(t.substring(2)) else (t.toIntOrNull() ?: continue)
            if (id !in out && id != BottomNav.MORE) out.add(id)
        }
        out.add(BottomNav.MORE); return out
    }
    fun moreOrder(): List<String> = csv(K_ORDER)
    fun moreHide(): Set<String> = csv(K_HIDE).filter { it != PROTECTED }.toSet()
    fun moreExtras(): List<String> = csv(K_EXTRA)

    fun tabLabel(id: String): String = if (id == "applang") "🌐 " + I18n.languageWord() else try { SettingsTabs.label(id) } catch (_: Throwable) { id }
    fun split(l: String): Pair<String, String> { val i = l.indexOf(' '); return if (i in 1..4) l.substring(0, i) to l.substring(i + 1).trim() else "⚙️" to l }
    fun customDefs(): List<Pair<String, String>> = customTabs().map { split(tabLabel(it)) }

    /** فتح قسم من الإعدادات من أي شاشة */
    fun open(act: Activity, tab: String) {
        try {
            if (act is MainActivity) act.openSettingsTab(tab)
            else act.startActivity(Intent(act, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("reopen_tab", tab))
        } catch (e: Throwable) { LogStore.err("NavCustom:open", e) }
    }

    private val START_NAMES = listOf("يوتيوب (الرئيسية) - الافتراضي", "اشتراكات يوتيوب", "يوتيوب (أنا)", "الفيديوهات", "المتصفح", "الموسيقى", "الإعدادات")
    private val START_VALS = listOf("yt_home", "yt_subs", "yt_me", "videos", "browser", "music", "settings")
    /** (v1274) الافتراضي: يوتيوب الرئيسية */
    fun startPage(): String = try { Cfg.str(K_START, "").ifEmpty { "yt_home" } } catch (_: Throwable) { "yt_home" }

    /** catalog: (مفتاح البلاطة، إيموجي، اسم) لكل بلاطات «المزيد» الأصلية */
    fun views(act: Activity, ui: Ui, th: Theme, catalog: List<Triple<String, String, String>>): List<View> {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun sb(t: String, f: () -> Unit) = ui.text(t, 13f, th.primary, true).apply {
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)); gravity = Gravity.CENTER; background = ui.box(th.card, th.border, 10); setOnClickListener { f() }
        }
        fun row(emoji: String, text: String, dim: Boolean, vararg btns: View): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); alpha = if (dim) 0.5f else 1f
            addView(ui.text(emoji + "  " + text, 14f, th.text, false), LinearLayout.LayoutParams(0, -2, 1f))
            for (b in btns) addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) })
        }
        fun head(t: String) = ui.text(t, 14f, th.primary, true).apply { setPadding(0, ui.dp(14), 0, ui.dp(4)) }
        fun note(t: String) = ui.text(t, 12f, th.muted)
        fun toast(m: String) { try { Toast.makeText(act, m, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} }
        lateinit var render: () -> Unit

        fun barInfo(tok: String): Pair<String, String> {
            if (tok.startsWith("s:")) return split(tabLabel(tok.substring(2)))
            val o = BAR_OPTS.firstOrNull { it.first == tok }; return if (o != null) o.second to o.third else "•" to tok
        }
        fun saveBar(l: List<String>) { put(K_BAR, if (l.isEmpty()) listOf("-") else l) }
        fun moveIn(l: List<String>, i: Int, d: Int): List<String> { val j = i + d; if (j < 0 || j >= l.size) return l; val m = l.toMutableList(); val t = m[i]; m[i] = m[j]; m[j] = t; return m }

        fun effectiveKeys(): List<String> {
            val keys = catalog.map { it.first }; val ord = moreOrder()
            return keys.sortedBy { k -> ord.indexOf(k).let { if (it < 0) 10000 + keys.indexOf(k) else it } }
        }

        render = {
            col.removeAllViews()
            // ───── شريط الأدوات ─────
            col.addView(head("📱 أزرار الشريط السفلي"))
            col.addView(note("رتّب الأزرار أو شيل منها (لحد " + BAR_MAX + " أزرار + «المزيد» دايمًا آخر واحد). اللي بتشيله بيفضل متاح تحت وتقدر ترجّعه."))
            val toks = barTokens()
            toks.forEachIndexed { i, t ->
                val (e, n) = barInfo(t)
                col.addView(row(e, n, false, sb("▲") { saveBar(moveIn(toks, i, -1)); render() }, sb("▼") { saveBar(moveIn(toks, i, 1)); render() }, sb("✕") { saveBar(toks.filterIndexed { k, _ -> k != i }); render() }))
            }
            val avail = BAR_OPTS.filter { o -> o.first !in toks }
            if (avail.isNotEmpty()) col.addView(note("متاح للإضافة:"))
            for (o in avail) col.addView(row(o.second, o.third, true, sb("＋ إضافة") { if (toks.size >= BAR_MAX) toast("الشريط ممتلئ") else { saveBar(toks + o.first); render() } }))
            col.addView(sb("↺ رجّع شريط الأدوات للافتراضي") { put(K_BAR, emptyList()); render() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(8) })

            // ───── المزيد ─────
            col.addView(head("🧩 بلاطات «المزيد»"))
            col.addView(note("رتّب أو أخفِ أي بلاطة. المخفية بتفضل هنا باهتة وتقدر تظهرها تاني."))
            if (catalog.isEmpty()) col.addView(note("افتح الشاشة الرئيسية الأول عشان تظهر القايمة."))
            val keys = effectiveKeys(); val hid = moreHide()
            keys.forEachIndexed { i, k ->
                val c = catalog.first { it.first == k }
                val hidden = k in hid
                val btns = ArrayList<View>()
                btns.add(sb("▲") { put(K_ORDER, moveIn(keys, i, -1)); render() })
                btns.add(sb("▼") { put(K_ORDER, moveIn(keys, i, 1)); render() })
                if (k != PROTECTED) btns.add(sb(if (hidden) "👁 إظهار" else "🚫 إخفاء") { put(K_HIDE, if (hidden) hid.filter { it != k } else hid.toList() + k); render() })
                col.addView(row(c.second, c.third + (if (hidden) "  (مخفي)" else ""), hidden, *btns.toTypedArray()))
            }
            col.addView(sb("↺ رجّع «المزيد» للافتراضي") { put(K_ORDER, emptyList()); put(K_HIDE, emptyList()); put(K_EXTRA, emptyList()); render() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(8) })

            // ───── صفحات الإعدادات كبلاطات ─────
            col.addView(head("⚙️ صفحات الإعدادات كبلاطات"))
            col.addView(note("اختار أي قسم من الإعدادات (المصدر، يوتيوب، المفاتيح...) يظهر كبلاطة في «المزيد» أو كزرار في الشريط."))
            val ex = moreExtras()
            for (id in listOf("applang") + SettingsTabs.sections.map { it.id }) {
                val (e, n) = split(tabLabel(id))
                val inMore = id in ex; val inBar = ("s:" + id) in toks
                col.addView(row(e, n, false,
                    sb(if (inMore) "المزيد ✓" else "المزيد ＋") { put(K_EXTRA, if (inMore) ex.filter { it != id } else ex + id); render() },
                    sb(if (inBar) "الشريط ✓" else "الشريط ＋") { if (inBar) saveBar(toks.filter { it != "s:" + id }) else if (toks.size >= BAR_MAX) toast("الشريط ممتلئ") else saveBar(toks + ("s:" + id)); render() }))
            }

            // ───── صفحة البداية ─────
            col.addView(head("🚪 صفحة البداية"))
            col.addView(note("الصفحة اللي التطبيق يفتح عليها لما تشغّله."))
            col.addView(ui.chips(START_NAMES, { START_NAMES[START_VALS.indexOf(startPage()).coerceAtLeast(0)] }) { n -> try { Cfg.put(K_START, START_VALS[START_NAMES.indexOf(n)]) } catch (_: Throwable) {} })
            col.addView(ui.button("↻ طبّق دلوقتي (إعادة فتح الشاشة)", true) { try { act.recreate() } catch (_: Throwable) {} }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14) })
        }
        render()
        return listOf(col)
    }
}
