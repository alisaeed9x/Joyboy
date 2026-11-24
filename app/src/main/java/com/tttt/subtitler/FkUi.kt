package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** (v1275) تبويب «فتح كلام» الأصلي: صفحتين (الرئيسية · الأقسام) بنفس نمط يوتيوب، والتنقل بينهم من الشريط السفلي للتطبيق. ثيم وخط التطبيق. محلي بالكامل. */
class FkUi(private val act: Activity, private val ui: Ui, private val th: Theme, private val onTab: () -> Unit) {
    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL }
    var currentTab = 0; private set
    private val content = FrameLayout(act)
    private val home = ScrollView(act).apply { isFillViewport = true }
    private val secs = ScrollView(act)
    private val homeCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(24)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val secCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(24)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var cur: FkData.Q? = null
    private var busy = false
    private val expanded = HashSet<String>()
    private var built = false
    private var loaded = false

    init {
        FkData.init(act)
        home.addView(homeCol, FrameLayout.LayoutParams(-1, -2)); secs.addView(secCol, FrameLayout.LayoutParams(-1, -2))
        content.addView(home, FrameLayout.LayoutParams(-1, -1)); content.addView(secs, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        secs.visibility = View.GONE
    }

    fun tapTab(i: Int) { currentTab = i.coerceIn(0, 1); home.visibility = if (currentTab == 0) View.VISIBLE else View.GONE; secs.visibility = if (currentTab == 1) View.VISIBLE else View.GONE; if (currentTab == 1) buildSections(); try { onTab() } catch (_: Throwable) {} }
    fun back(): Boolean { if (currentTab == 1) { tapTab(0); return true }; return false }
    fun refresh() {
        if (!built) { built = true; Thread { try { FkData.all() } catch (_: Throwable) {}; h.post { loaded = true; buildHome(true); if (currentTab == 1) buildSections() } }.apply { isDaemon = true; start() }; buildHome(false) }
        else if (currentTab == 1) buildSections()
    }

    private fun toast(t: String) { try { Toast.makeText(act, t, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} }
    private fun chip(label: String, on: Boolean, f: () -> Unit): View = ui.text(label, 13f, if (on) th.onPrimary else th.text, on).apply {
        setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7)); gravity = Gravity.CENTER
        background = ui.box(if (on) th.primary else th.card, if (on) th.primary else th.border, 18)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) }
        setOnClickListener { f() }
    }
    private fun hrow(vararg v: View): HorizontalScrollView = HorizontalScrollView(act).apply {
        isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL
        addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; for (x in v) addView(x) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) }
    }

    // ===== اختيار المود (مع تنبيه +18 وكلمة سر كوكو واوا كل مرة) =====
    private fun pickMood(m: String, then: () -> Unit = {}) {
        if (m !in FkData.ADULT) { FkData.setMood(m); then(); return }
        GAlert(act).setTitle("🔞 تنبيه").setMessage("القسم ده للمتزوجين والمخطوبين فقط، وفيه أسئلة جريئة. متأكد إنك عايز تكمّل؟")
            .setPositiveButton("أيوه، كمّل") { _, _ ->
                if (m != FkData.LOCKED) { FkData.setMood(m); then() }
                else {
                    val inp = ui.input("كلمة السر", "")
                    inp.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    GAlert(act).setTitle("🔒 كوكو واوا").setView(inp).setPositiveButton("دخول") { _, _ ->
                        if (inp.text.toString().trim() == FkData.PASS) { FkData.setMood(m); then() } else toast("كلمة السر غلط")
                    }.setNegativeButton("إلغاء", null).show()
                }
            }.setNegativeButton("لأ", null).show()
    }

    // ===== الرئيسية =====
    private fun buildHome(withQuestion: Boolean) {
        homeCol.removeAllViews()
        val mood = FkData.mood()
        val top = ArrayList<View>()
        top.add(chip("🎲 الكل", mood == "all") { pickMood("all") { showNext(); buildHome(false) } })
        for (g in FkData.groups) top.add(chip(g.icon + " " + g.name, mood == g.id || g.subs.any { it.id == mood }) { pickMood(g.id) { showNext(); buildHome(false) } })
        homeCol.addView(hrow(*top.toTypedArray()))
        val g = FkData.groups.firstOrNull { gg -> gg.subs.isNotEmpty() && (gg.id == mood || gg.subs.any { it.id == mood }) }
        if (g != null) homeCol.addView(hrow(*g.subs.map { c -> chip(c.icon + " " + c.name, mood == c.id) { pickMood(c.id) { showNext(); buildHome(false) } } }.toTypedArray()))

        val card = ui.card().apply { setPadding(ui.dp(18), ui.dp(22), ui.dp(18), ui.dp(18)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(12), 0, ui.dp(8)) } }
        qText = ui.text("", FkData.textSize().toFloat(), th.text, true).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f); minHeight = ui.dp(150); setTextIsSelectable(false) }
        qMeta = ui.text("", 12f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, 0) }
        card.addView(qText, LinearLayout.LayoutParams(-1, -2)); card.addView(qMeta, LinearLayout.LayoutParams(-1, -2))
        homeCol.addView(card)
        homeCol.addView(ui.button("➜ سؤال تاني", true) { showNext() })
        val acts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        favBtn = ui.button("🤍 مفضلة") { cur?.let { q -> val on = FkData.toggleFav(q.id); updFav(); toast(if (on) "اتضاف للمفضلة" else "اتشال من المفضلة") } }
        val cp = ui.button("📋 نسخ") { cur?.let { q -> try { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("q", q.text)); toast("اتنسخ") } catch (_: Throwable) {} } }
        val sh = ui.button("📤 مشاركة") { cur?.let { q -> try { act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, q.text), "مشاركة")) } catch (_: Throwable) {} } }
        for (b in listOf<View>(favBtn!!, cp, sh)) acts.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        homeCol.addView(acts)
        homeCol.addView(ui.button("🤖 ولّد أسئلة جديدة للقسم") { genDialog(mood) })
        homeCol.addView(ui.button("⭐ المفضلة (" + FkData.favs().size + ")") { favList() })
        val sliders = ui.card()
        sliders.addView(ui.slider("🔥 الجرأة", FkData.b(), 0, 100, "") { FkData.setB(it) })
        sliders.addView(ui.slider("💕 الرومانسية", FkData.r(), 0, 100, "") { FkData.setR(it) })
        sliders.addView(ui.slider("🌊 العمق", FkData.d(), 0, 100, "") { FkData.setD(it) })
        homeCol.addView(sliders)
        genStatus = ui.text("", 12f, th.muted).apply { gravity = Gravity.CENTER }
        homeCol.addView(genStatus)
        if (!loaded) { qText?.text = "⏳ بيحمّل الأسئلة…"; return }
        if (withQuestion || cur == null) showNext() else cur?.let { showQ(it) }
    }
    private var qText: android.widget.TextView? = null
    private var qMeta: android.widget.TextView? = null
    private var favBtn: android.widget.Button? = null
    private var genStatus: android.widget.TextView? = null

    private fun updFav() { val q = cur ?: return; favBtn?.text = if (FkData.isFav(q.id)) "💛 في المفضلة" else "🤍 مفضلة" }
    private fun showQ(q: FkData.Q) {
        cur = q; qText?.text = q.text
        val c = FkData.catOf(q.cat); qMeta?.text = (if (c != null) c.icon + " " + c.name else q.cat) + (if (q.gen) "  ·  🤖 مولّد" else "")
        updFav(); FkData.markSeen(q.id); FkData.pushRecent(q.text)
    }
    private fun showNext() {
        val q = try { FkData.next(FkData.mood()) } catch (e: Throwable) { LogStore.err("Fk:next", e); null }
        if (q == null) { qText?.text = if (loaded) "مفيش أسئلة في القسم ده لسه. دوس «ولّد أسئلة»" else "⏳ بيحمّل الأسئلة…"; qMeta?.text = ""; cur = null; return }
        showQ(q)
    }

    private fun favList() {
        val ids = FkData.favs(); val qs = FkData.all().filter { it.id in ids }
        if (qs.isEmpty()) { toast("مفيش مفضلة لسه"); return }
        val labels = qs.map { it.text.take(80) }.toTypedArray()
        GAlert(act).setTitle("⭐ المفضلة").setItems(labels) { _, i -> showQ(qs[i]) }.setNegativeButton("إغلاق", null).show()
    }

    // ===== التوليد =====
    private fun genCat(mood: String): String {
        val rnd = java.util.Random()
        if (mood == "all") { val l = FkData.allCats().filter { it.id !in FkData.ADULT && !FkData.needsMedia(it.id) }; return l[rnd.nextInt(l.size)].id }
        val g = FkData.groupOf(mood)
        if (g != null && g.subs.isNotEmpty()) { val l = g.subs.filter { it.id !in FkData.ADULT }; return l[rnd.nextInt(l.size)].id }
        return mood
    }
    private fun genDialog(mood: String) {
        if (busy) { toast("فيه توليد شغال"); return }
        val cat = genCat(mood)
        val hint = if (FkData.needsMedia(cat)) "اسم الفيلم/المسلسل (اختياري)" else if (cat == "coworkers") "طبيعة شغلك (اختياري)" else "تعليمات إضافية (اختياري)"
        val inp = ui.input(hint, "")
        GAlert(act).setTitle("🤖 توليد: " + FkData.nameOf(cat)).setMessage("هيولّد " + FkData.batch() + " سؤال بمفاتيح التطبيق بالتناوب.").setView(inp)
            .setPositiveButton("ولّد") { _, _ -> runGen(cat, inp.text.toString().trim()) }.setNegativeButton("إلغاء", null).show()
    }
    fun runGen(cat: String, mod0: String) {
        if (busy) return
        busy = true; genStatus?.text = "⏳ بيولّد أسئلة " + FkData.nameOf(cat) + "…"
        val mod = if (FkData.needsMedia(cat) && mod0.isNotBlank()) "الأسئلة عن العمل ده: $mod0" else if (cat == "coworkers" && mod0.isNotBlank()) "طبيعة شغل المستخدم: $mod0" else mod0
        Thread {
            var res: List<FkData.Q>? = null; var err = ""
            try { res = FkGen.generate(act, cat, mod, FkData.batch()) } catch (e: Throwable) { err = e.message ?: e.javaClass.simpleName }
            h.post {
                busy = false
                val r = res
                if (r != null) { genStatus?.text = "✅ اتضاف " + r.size + " سؤال جديد"; toast("اتضاف " + r.size + " سؤال"); if (FkData.pool(FkData.mood()).any { it.id == r[0].id }) showQ(r[0]) }
                else { genStatus?.text = "⚠ " + err; toast("فشل التوليد") }
                if (currentTab == 1) buildSections()
            }
        }.apply { isDaemon = true; start() }
    }

    // ===== الأقسام =====
    private fun row(icon: String, name: String, sub: String, onTap: () -> Unit, onGen: (() -> Unit)?): View {
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); background = ui.box(th.card, th.border, 14)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) }; setOnClickListener { onTap() } }
        r.addView(ui.text(icon, 22f, th.text).apply { setPadding(0, 0, ui.dp(2), 0) }, LinearLayout.LayoutParams(-2, -2))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), 0, ui.dp(10), 0) }
        col.addView(ui.text(name, 15f, th.text, true)); col.addView(ui.text(sub, 11f, th.muted))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (onGen != null) r.addView(ui.text("🤖", 20f, th.primary).apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(4), ui.dp(4)); setOnClickListener { onGen() } }, LinearLayout.LayoutParams(-2, -2))
        return r
    }
    private fun openMood(m: String) { pickMood(m) { tapTab(0); buildHome(true) } }
    private fun buildSections() {
        if (!loaded) return
        secCol.removeAllViews()
        secCol.addView(row("🎲", "الكل", FkData.count("all").toString() + " سؤال", { openMood("all") }, { runGen(genCat("all"), "") }))
        for (g in FkData.groups) {
            if (g.subs.isEmpty()) { secCol.addView(row(g.icon, g.name, FkData.count(g.id).toString() + " سؤال", { openMood(g.id) }, { genDialogFor(g.id) })); continue }
            val open = g.id in expanded
            secCol.addView(row(g.icon, g.name + (if (open) "  ▴" else "  ▾"), g.subs.size.toString() + " أقسام · " + FkData.count(g.id) + " سؤال", { if (open) expanded.remove(g.id) else expanded.add(g.id); buildSections() }, null))
            if (open) {
                secCol.addView(row("▶", "كل " + g.name, "", { openMood(g.id) }, null).apply { alpha = 0.85f })
                for (c in g.subs) {
                    val cnt = FkData.count(c.id)
                    secCol.addView(row(c.icon, c.name, (if (c.id in FkData.ADULT) "🔞 " else "") + cnt + " سؤال", { openMood(c.id) }, { genDialogFor(c.id) }).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(ui.dp(22), ui.dp(3), 0, ui.dp(3)) })
                }
            }
        }
    }
    private fun genDialogFor(cat: String) {
        if (cat in FkData.ADULT) { pickMood(cat) { runGenAsk(cat) } } else runGenAsk(cat)
    }
    private fun runGenAsk(cat: String) {
        if (busy) { toast("فيه توليد شغال"); return }
        val hint = if (FkData.needsMedia(cat)) "اسم الفيلم/المسلسل (اختياري)" else if (cat == "coworkers") "طبيعة شغلك (اختياري)" else "تعليمات إضافية (اختياري)"
        val inp = ui.input(hint, "")
        GAlert(act).setTitle("🤖 توليد: " + FkData.nameOf(cat)).setView(inp).setPositiveButton("ولّد") { _, _ -> runGen(cat, inp.text.toString().trim()) }.setNegativeButton("إلغاء", null).show()
    }

    companion object {
        /** صفحة «إعدادات فتح كلام» جوه إعدادات التطبيق */
        fun settingsViews(act: Activity, ui: Ui, th: Theme): List<View> {
            FkData.init(act)
            val l = ArrayList<View>()
            l.add(ui.section("🎚 الأسئلة", true,
                ui.slider("🔥 الجرأة", FkData.b(), 0, 100, "") { FkData.setB(it) },
                ui.slider("💕 الرومانسية", FkData.r(), 0, 100, "") { FkData.setR(it) },
                ui.slider("🌊 العمق", FkData.d(), 0, 100, "") { FkData.setD(it) },
                ui.slider("🔠 حجم خط السؤال", FkData.textSize(), 14, 36, "") { FkData.setTextSize(it) },
                ui.switchRow("🔁 ماتكررش سؤال شفته النهاردة", FkData.noRepeat()) { FkData.setNoRepeat(it) }))
            l.add(ui.section("🤖 التوليد", true,
                ui.slider("🧮 عدد الأسئلة في كل توليد", FkData.batch(), 5, 50, "") { FkData.setBatch(it) },
                ui.text("التوليد بمفاتيح التطبيق كلها بالتناوب (إعدادات ← المفاتيح) وبموديل التطبيق الواحد.", 12f, th.muted),
                ui.button("🗑 مسح الأسئلة المولّدة (" + FkData.genCount() + ")") { FkData.clearGenerated(); Toast.makeText(act, "اتمسحت", Toast.LENGTH_SHORT).show() },
                ui.button("↺ إعادة أسئلة النهاردة (تظهر تاني)") { FkData.resetSeen(); Toast.makeText(act, "تمام", Toast.LENGTH_SHORT).show() }))
            return l
        }
    }
}
