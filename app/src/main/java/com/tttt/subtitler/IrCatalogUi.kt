package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * (v821) كتالوج ريموتات IR: ماركات شهيرة (10) ← ماركات تانية / كل الماركات / ساتلايت ← موديلات ← بروفايلات متوافقة ← معاينة واقعية + تجربة + حفظ بضغطة.
 * بيستخدم قاعدة IR الموجودة (irdb_*.txt) بس — مفيش ريموت مخترع، ومفيش افتراض إن ريموتات الماركة الواحدة بنفس الأكواد (المستخدم بيجرّب ويحفظ اللي يشتغل).
 */
object IrCatalogUi {
    /** [replaceId]: لو متحدد، الحفظ بيستبدل ريموت البروفايل ده (بيحافظ على اسمه وربطه). [startKind]: tv | sat */
    fun show(act: Activity, replaceId: String? = null, startKind: String? = null, onDone: (() -> Unit)? = null) {
        try { Screen(act, replaceId, startKind, onDone).open() } catch (e: Throwable) { LogStore.err("IrCatalogUi", e) }
    }

    private val BRAND_COLORS = mapOf(
        "samsung" to 0xFF1428A0.toInt(), "lg" to 0xFFA50034.toInt(), "tcl" to 0xFFD81E2B.toInt(), "toshiba" to 0xFFB71C1C.toInt(),
        "sharp" to 0xFFC62828.toInt(), "sony" to 0xFF263238.toInt(), "hisense" to 0xFF00897B.toInt(), "panasonic" to 0xFF0D47A1.toInt(),
        "philips" to 0xFF1565C0.toInt(), "haier" to 0xFF2E7D32.toInt())

    private class Screen(val act: Activity, val replaceId: String?, val startKind: String?, val onDone: (() -> Unit)?) {
        val kit = IrUiKit(act)
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        var idx: CatIndex? = null
        val stack = ArrayList<() -> Unit>()
        var current: () -> Unit = {}
        var query = ""
        lateinit var body: LinearLayout
        lateinit var titleT: TextView
        lateinit var searchBox: EditText
        lateinit var scroll: ScrollView
        val hw = IrTv.available(act)

        fun open() {
            val root = kit.vbox().apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(IrUiKit.BG); setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(6)) }
            val top = kit.hbox(Gravity.CENTER_VERTICAL)
            top.addView(kit.btn("→", IrUiKit.CARD2, 18f) { back() }, kit.lp(kit.dp(48), kit.dp(44)))
            titleT = kit.text("📡 ريموت IR", 17f, Color.WHITE, true).apply { setPadding(kit.dp(10), 0, kit.dp(10), 0) }
            top.addView(titleT, kit.lp(0, kit.dp(44), 1f))
            top.addView(kit.btn("📚 ريموتاتي", IrUiKit.BLUE, 13f) { IrProfilesUi.show(act) { render() } }, kit.lp(-2, kit.dp(44)))
            root.addView(top, kit.lp(-1, -2))
            searchBox = EditText(act).apply {
                hint = "🔍 ابحث: ماركة · موديل التلفزيون · رقم الريموت · ريسيفر"; textSize = 14f; setTextColor(Color.WHITE); setHintTextColor(IrUiKit.SOFT)
                setSingleLine(); background = kit.shape(IrUiKit.CARD, 16, IrUiKit.STROKE); setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; render() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            root.addView(searchBox, kit.lpm(-1, -2, 0, 8, 0, 6))
            body = kit.vbox()
            scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(body) }
            root.addView(scroll, kit.lp(-1, 0, 1f))
            d.setContentView(root)
            d.setCancelable(true)
            d.setOnKeyListener { _, k, e -> if (k == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { back(); true } else false }
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            d.show()
            body.addView(kit.text("جاري تحميل قاعدة الأكواد…", 14f, IrUiKit.MUTED, false, Gravity.CENTER), kit.lp(-1, kit.dp(120)))
            Thread {
                val i = try { IrTv.catalog(act) } catch (e: Throwable) { LogStore.err("IrCatalogUi:load", e); null }
                act.runOnUiThread { idx = i; if (i == null) body.removeAllViews() else if (startKind == "sat") go { brandsScreen("sat", "🛰 ريسيفر ساتلايت") } else go { home() } }
            }.also { it.isDaemon = true }.start()
        }

        fun close() { try { d.dismiss() } catch (_: Throwable) {}; onDone?.invoke() }
        fun back() {
            if (query.isNotBlank()) { searchBox.setText(""); return }
            if (stack.size > 1) { stack.removeAt(stack.size - 1); current = stack[stack.size - 1]; render() } else close()
        }
        fun go(r: () -> Unit) { stack.add(r); current = r; render() }
        fun render() {
            body.removeAllViews(); scroll.scrollTo(0, 0)
            titleT.text = "📡 ريموت IR"
            searchBox.visibility = View.VISIBLE
            if (query.isNotBlank()) results() else current()
        }

        fun banner() {
            if (!hw) body.addView(kit.banner("الموبايل ده مفيهوش بلاستر IR: تقدر تتصفّح وتحفظ، لكن التجربة والإرسال مش هيشتغلوا (الواي فاي مستقل وشغّال عادي). المرسلات الخارجية مش مدعومة في النسخة دي."), kit.lpm(-1, -2, 0, 0, 0, 8))
            if (replaceId != null) IrProfiles.db(act).byId(replaceId)?.let { body.addView(kit.banner("وضع الاستبدال: اختيارك هيحل مكان «${it.name}» (الاسم والربط بيفضلوا).", IrUiKit.SOFT), kit.lpm(-1, -2, 0, 0, 0, 8)) }
        }

        fun sectionT(t: String) = body.addView(kit.text(t, 13f, IrUiKit.SOFT, true), kit.lpm(-1, -2, 4, 10, 4, 6))

        /** شبكة بلاطات متجاوبة (موبايل/تابلت) */
        fun grid(items: List<View>, minDp: Int) {
            val cols = (kit.widthDp / minDp).coerceIn(2, 6)
            for (chunk in items.chunked(cols)) {
                val r = kit.hbox(Gravity.CENTER_VERTICAL).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
                for (v in chunk) r.addView(v, kit.lpm(0, kit.dp(74), 4, 4, 4, 4, 1f))
                for (i in chunk.size until cols) r.addView(View(act), kit.lpm(0, kit.dp(74), 4, 4, 4, 4, 1f))
                body.addView(r, kit.lp(-1, -2))
            }
        }

        fun tile(title: String, sub: String, fill: Int, onTap: () -> Unit): View {
            val t = kit.vbox().apply { gravity = Gravity.CENTER; setPadding(kit.dp(6), kit.dp(6), kit.dp(6), kit.dp(6)) }
            t.addView(kit.text(title, if (title.length > 9) 13f else 16f, Color.WHITE, true, Gravity.CENTER).apply { maxLines = 2 })
            if (sub.isNotEmpty()) t.addView(kit.text(sub, 10.5f, 0xCCFFFFFF.toInt(), false, Gravity.CENTER))
            kit.clickable(t, kit.shape(fill, 18, IrUiKit.STROKE), onTap)
            return t
        }

        fun home() {
            val i = idx ?: return
            banner()
            // (v855) الطريقة الأسهل: جرّب أزرار على جهازك وقول «اشتغل» / «ما اشتغلش» — من غير ما تدوّر على ماركة أو أكواد
            body.addView(kit.btn("🎯 اظبط الريموت بالتجربة (الأسهل)", 0xFF1D4ED8.toInt(), 15f) { try { d.dismiss() } catch (_: Throwable) {}; IrWizardUi.show(act, startKind, replaceId, onDone) }, kit.lpm(-1, kit.dp(56), 0, 0, 0, 4))
            body.addView(kit.text("أو اختار بنفسك لو عارف الماركة والموديل:", 11.5f, IrUiKit.MUTED, false, Gravity.CENTER), kit.lpm(-1, -2, 0, 0, 0, 6))
            // اقتراح من ماركة التلفزيون المقترن (من /ping) لو معروفة
            val mfr = IrTv.tvMfr(act)
            if (mfr.isNotEmpty()) {
                val b = i.brands("tv").firstOrNull { it.name.equals(mfr, true) || mfr.lowercase().contains(it.name.lowercase()) && it.name.length >= 3 }
                if (b != null) {
                    val tvm = IrTv.tvModel(act)
                    body.addView(kit.btn("✨ اقتراح لتلفزيونك: ${b.name}" + (if (tvm.isNotEmpty()) " $tvm" else ""), IrUiKit.BLUE, 14f) { go { modelsScreen("tv", b.name) } }, kit.lpm(-1, kit.dp(48), 0, 0, 0, 6))
                }
            }
            sectionT("الماركات الشهيرة")
            grid(i.popular().map { b -> tile(b.name, "${b.count} ريموت", BRAND_COLORS[b.name.lowercase()] ?: IrUiKit.CARD) { go { modelsScreen("tv", b.name) } } }, 104)
            sectionT("المزيد")
            val sat = i.brands("sat")
            grid(listOf(
                tile("ماركات تانية", "الأكتر ريموتات", IrUiKit.CARD2) { go { brandsScreen("other", "ماركات تانية") } },
                tile("كل الماركات", "${i.brands("tv").size} ماركة", IrUiKit.CARD2) { go { brandsScreen("all", "كل الماركات") } },
                tile("🛰 ريسيفر ساتلايت", "${sat.size} ماركة", 0xFF4A3A7C.toInt()) { go { brandsScreen("sat", "🛰 ريسيفر ساتلايت") } }), 104)
            val n = IrProfiles.db(act).profiles.size
            body.addView(kit.text(if (n == 0) "لسه مفيش ريموتات محفوظة." else "محفوظ عندك $n ريموت — «📚 ريموتاتي» فوق.", 12f, IrUiKit.MUTED, false, Gravity.CENTER), kit.lpm(-1, -2, 0, 14, 0, 14))
        }

        fun brandsScreen(mode: String, title: String) {
            val i = idx ?: return
            titleT.text = title
            banner()
            val list = when (mode) { "sat" -> i.brands("sat"); "other" -> i.otherBrands(); else -> i.brands("tv") }
            val kind = if (mode == "sat") "sat" else "tv"
            if (mode == "all") {
                var letter = ' '
                for (b in list) {
                    val l = b.name.firstOrNull()?.uppercaseChar() ?: '#'
                    if (l != letter) { letter = l; sectionT(l.toString()) }
                    body.addView(row(b.name, "${b.count} ريموت") { go { modelsScreen(kind, b.name) } }, kit.lpm(-1, kit.dp(52), 0, 2, 0, 2))
                }
            } else grid(list.map { b -> tile(b.name, "${b.count} ريموت", IrUiKit.CARD2) { go { modelsScreen(kind, b.name) } } }, 104)
        }

        fun row(title: String, sub: String, onTap: () -> Unit): View {
            val r = kit.hbox(Gravity.CENTER_VERTICAL).apply { setPadding(kit.dp(14), kit.dp(6), kit.dp(14), kit.dp(6)) }
            r.addView(kit.text(title, 14f, Color.WHITE, true), kit.lp(0, -2, 1f))
            r.addView(kit.text(sub, 11.5f, IrUiKit.MUTED))
            kit.clickable(r, kit.shape(IrUiKit.CARD, 14, IrUiKit.STROKE), onTap)
            return r
        }

        fun modelsScreen(kind: String, brand: String) {
            val i = idx ?: return
            titleT.text = (if (kind == "sat") "🛰 " else "📺 ") + brand
            banner()
            val ms = i.models(kind, brand)
            if (ms.isEmpty()) { body.addView(kit.text("مفيش موديلات لـ$brand", 14f, IrUiKit.MUTED, false, Gravity.CENTER), kit.lp(-1, kit.dp(100))); return }
            sectionT("اختار الموديل (${ms.size})")
            for (m in ms) body.addView(row(modelTitle(m), "${m.entries.size} ريموت") { go { profilesScreen(m) } }, kit.lpm(-1, kit.dp(52), 0, 2, 0, 2))
        }

        fun modelTitle(m: CatModel) = if (m.label.isEmpty()) "أكواد عامة لـ${m.brand}" else m.label

        fun results() {
            val i = idx ?: return
            titleT.text = "🔍 نتايج البحث"
            val hits = i.search(query)
            if (hits.isEmpty()) { body.addView(kit.text("مفيش نتايج لـ«$query» — جرّب جزء من الاسم أو رقم الريموت", 14f, IrUiKit.MUTED, false, Gravity.CENTER), kit.lp(-1, kit.dp(120))); return }
            for (h in hits) {
                val m = h.model
                body.addView(row((if (m.kind == "sat") "🛰 " else "📺 ") + m.brand + " — " + modelTitle(m), "${m.entries.size} ريموت") {
                    stack.clear(); stack.add { home() }; query = ""; searchBox.setText(""); go { profilesScreen(m) }
                }, kit.lpm(-1, kit.dp(52), 0, 2, 0, 2))
            }
        }

        fun profilesScreen(m: CatModel) {
            val i = idx ?: return
            titleT.text = m.brand + " · " + modelTitle(m)
            banner()
            val list = i.profilesFor(m)
            body.addView(kit.text("الريموتات المتوافقة (${list.size}) — جرّب كل واحد واحفظ اللي بيشغّل جهازك. الأولانيين لنفس الموديل، وبعدهم ريموتات من نفس الماركة.", 12f, IrUiKit.MUTED), kit.lpm(-1, -2, 4, 0, 4, 8))
            val saved = IrProfiles.db(act).profiles
            list.forEachIndexed { n, e ->
                val isSaved = saved.any { it.brand == e.brand && it.remote == e.name }
                body.addView(row((if (isSaved) "✓ " else "") + shortName(e), "${e.keys.size} زرار") { go { previewScreen(list, n) } }, kit.lpm(-1, kit.dp(52), 0, 2, 0, 2))
            }
        }

        fun shortName(e: CatEntry): String = CatIndex.modelPart(e.brand, e.name).ifEmpty { e.name }.let { if (it == e.name) it else "${it}  (${e.name})".take(60) }

        fun previewScreen(list: List<CatEntry>, start: Int) {
            var n = start
            var fillFn: () -> Unit = {}
            val e0 = list[n]
            titleT.text = "معاينة الريموت"
            searchBox.visibility = View.GONE
            banner()
            val info = kit.text("", 12f, IrUiKit.MUTED, false, Gravity.CENTER).apply { setPadding(kit.dp(6), kit.dp(6), kit.dp(6), kit.dp(6)) }
            val remoteHost = kit.vbox()
            val nameT = kit.text("", 15f, Color.WHITE, true, Gravity.CENTER)
            val saveB = TextView(act)
            val nav = kit.hbox(Gravity.CENTER_VERTICAL)
            val cnt = kit.text("", 13f, IrUiKit.SOFT, true, Gravity.CENTER)
            nav.addView(kit.btn("◀", IrUiKit.CARD2, 18f) { if (list.size > 1) { n = (n - 1 + list.size) % list.size; fillFn() } }, kit.lp(kit.dp(56), kit.dp(44)))
            nav.addView(cnt, kit.lp(0, -2, 1f))
            nav.addView(kit.btn("▶", IrUiKit.CARD2, 18f) { if (list.size > 1) { n = (n + 1) % list.size; fillFn() } }, kit.lp(kit.dp(56), kit.dp(44)))
            body.addView(nav, kit.lp(-1, -2)); body.addView(nameT, kit.lpm(-1, -2, 0, 6, 0, 2)); body.addView(info, kit.lp(-1, -2))
            body.addView(kit.btn("📋 كل الريموتات (${list.size})", IrUiKit.CARD2, 12f) {
                val names = list.map { shortName(it) }.toTypedArray()
                GAlert(act).setTitle("اختار ريموت").setItems(names) { _, w -> n = w; fillFn() }.show()
            }, kit.lpm(-1, kit.dp(40), 0, 0, 0, 6))
            body.addView(remoteHost, kit.lp(-1, -2))
            body.addView(saveB, kit.lpm(-1, kit.dp(54), 0, 14, 0, 14))

            fun fill() {
                val e = list[n]
                cnt.text = "${n + 1} / ${list.size}"
                nameT.text = e.name
                info.text = "اضغط أي زرار لتجربته على جهازك (وشوف كوده). جرّب ⏻ الأول."
                val r = IrTv.remoteOf(act, e.kind, e.brand, e.name)
                remoteHost.removeAllViews()
                if (r == null) { remoteHost.addView(kit.text("الريموت مش لاقيه في القاعدة", 14f, IrUiKit.AMBER, false, Gravity.CENTER)); return }
                remoteHost.addView(IrRemoteView.build(kit, r.keys.keys.toSet()) { v, key ->
                    val sig = r.keys[key]
                    val code = sig?.let { IrTv.sigText(it).take(40) } ?: ""
                    info.text = "$key  ·  $code"
                    if (!hw) { Notice.show(act, "مفيش بلاستر IR في الموبايل ده — التجربة مش متاحة", 2200L); return@build }
                    if (sig == null) return@build
                    RemoteRouter.testSig(act, sig) { ok -> if (!ok) Notice.show(act, "⚠ البروفايل ده فيه بروتوكول مش مدعوم أو الإرسال فشل", 2200L) }
                })
                val savedP = IrProfiles.db(act).profiles.firstOrNull { it.brand == e.brand && it.remote == e.name }
                val rp = if (replaceId != null) IrProfiles.db(act).byId(replaceId) else null
                saveB.apply {
                    gravity = Gravity.CENTER; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                    text = when { rp != null -> "🔁 استبدل «${rp.name}» بالريموت ده"; savedP != null -> "✓ محفوظ بالفعل — احفظ نسخة تانية"; else -> "💾 احفظ الريموت ده" }
                    kit.clickable(this, kit.shape(IrUiKit.BLUE, 18, 0x66FFFFFF)) {
                        if (rp != null) { IrProfiles.replaceWith(act, rp.id, e); Notice.show(act, "✓ اتبدّل — ${rp.name}", 2400L); close() }
                        else {
                            val p = IrProfiles.saveFromCatalog(act, e)
                            RemoteRouter.init(act)
                            Notice.show(act, "✓ اتحفظ: ${p.name} — بيشتغل على نفس أزرار الريموت", 3000L)
                            text = "✓ اتحفظ — ارجع للريموت"; setOnClickListener { Haptic.tap(it); close() }
                        }
                    }
                }
            }
            fillFn = { fill() }
            fill()
        }
    }
}
