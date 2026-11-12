package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * (v855) «اظبط الريموت بالتجربة»: المستخدم مش بيدوّر على ماركة/موديل/أكواد. بيجرّب زرار على جهازه ويقول «اشتغل» أو «ما اشتغلش»
 * (المنطق في IrWizardCore). لما يتأكد زرارين مختلفين بيتعرض الريموت كامل (بنفس الترتيب لكل الريموتات) ويتثبّت بضغطة واحدة كبروفايل.
 * التلفزيون: بيتحفظ كاحتياطي للواي فاي (الواي فاي لسه الأساسي). الساتلايت: بيتحفظ وبيبقى هو الهدف الحالي للأزرار.
 */
object IrWizardUi {
    /** [startKind]: tv | sat (null = يسأل). [replaceId]: بروفايل موجود نستبدل ريموته (الاسم والربط بيفضلوا) */
    fun show(act: Activity, startKind: String? = null, replaceId: String? = null, onDone: (() -> Unit)? = null) {
        try { Screen(act, startKind?.takeIf { it == "tv" || it == "sat" }, replaceId, onDone).open() } catch (e: Throwable) { LogStore.err("IrWizardUi", e) }
    }

    private const val OK_COL = 0xFF2563EB.toInt()     // أزرق (مش أخضر)
    private const val NO_COL = 0xFF8E3B4E.toInt()     // وردي غامق هادي
    private const val PRIMARY = 0xFF1D4ED8.toInt()

    private fun keyName(k: String) = when (k) {
        "POWER" -> "باور (تشغيل / إيقاف)"; "VOLUP" -> "رفع الصوت"; "VOLDN" -> "خفض الصوت"; "MUTE" -> "كتم"
        "OK", "SEL", "ENTER" -> "موافق OK"; "UP" -> "فوق"; "DOWN" -> "تحت"; "LEFT" -> "شمال"; "RIGHT" -> "يمين"
        "MENU" -> "قايمة"; "HOME" -> "رئيسية"; "BACK" -> "رجوع"; "SRC" -> "المصدر"; "CHUP" -> "القناة +"; "CHDN" -> "القناة −"
        else -> k
    }

    private class Screen(val act: Activity, val startKind: String?, val replaceId: String?, val onDone: (() -> Unit)?) {
        val kit = IrUiKit(act)
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        lateinit var body: LinearLayout
        lateinit var titleT: TextView
        val hw = IrTv.available(act)
        var kind = startKind ?: ""
        var brand = ""
        var db: List<IrTv.Remote> = emptyList()
        var core: IrWizardCore? = null
        var backFn: () -> Unit = { close() }
        var sent = false
        var altIdx = 0
        val h = Handler(Looper.getMainLooper())
        var auto = false
        var autoSteps = 0
        var autoInfo: TextView? = null

        fun open() {
            val root = kit.vbox().apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(IrUiKit.BG); setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(6)) }
            val top = kit.hbox(Gravity.CENTER_VERTICAL)
            top.addView(kit.btn("→", IrUiKit.CARD2, 18f) { backFn() }, kit.lp(kit.dp(48), kit.dp(44)))
            titleT = kit.text("🎯 اظبط الريموت بالتجربة", 17f, Color.WHITE, true).apply { setPadding(kit.dp(10), 0, kit.dp(10), 0) }
            top.addView(titleT, kit.lp(0, kit.dp(44), 1f))
            root.addView(top, kit.lp(-1, -2))
            body = kit.vbox()
            root.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(body) }, kit.lpm(-1, 0, 0, 8, 0, 0, 1f))
            d.setContentView(root)
            d.setCancelable(true)
            d.setOnKeyListener { _, k, e -> if (k == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { backFn(); true } else false }
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            d.setOnDismissListener { auto = false; h.removeCallbacksAndMessages(null) }
            d.show()
            body.addView(kit.text("جاري تحميل قاعدة الأكواد…", 14f, IrUiKit.MUTED, false, Gravity.CENTER), kit.lp(-1, kit.dp(120)))
            Thread {
                val l = try { IrTv.db(act) } catch (e: Throwable) { LogStore.err("IrWizardUi:load", e); emptyList() }
                act.runOnUiThread { db = l; if (kind.isEmpty()) kindScreen() else brandScreen() }
            }.also { it.isDaemon = true }.start()
        }

        fun close() { auto = false; h.removeCallbacksAndMessages(null); try { d.dismiss() } catch (_: Throwable) {}; onDone?.invoke() }

        fun openCatalog() {
            auto = false; h.removeCallbacksAndMessages(null)
            try { d.dismiss() } catch (_: Throwable) {}
            IrCatalogUi.show(act, replaceId, kind.ifEmpty { null }, onDone)
        }

        fun reset(title: String) { body.removeAllViews(); titleT.text = title; try { (body.parent as? ScrollView)?.scrollTo(0, 0) } catch (_: Throwable) {} }

        fun note(t: String, col: Int = IrUiKit.MUTED, sp: Float = 13f, top: Int = 6, bottom: Int = 6) =
            body.addView(kit.text(t, sp, col, false, Gravity.CENTER).apply { setPadding(kit.dp(8), 0, kit.dp(8), 0) }, kit.lpm(-1, -2, 0, top, 0, bottom))

        // ============================================================ ١) نوع الجهاز
        fun kindScreen() {
            backFn = { close() }
            reset("🎯 اظبط الريموت بالتجربة")
            if (!hw) body.addView(kit.banner("الموبايل ده مفيهوش بلاستر IR: مينفعش نجرّب أزرار على الجهاز. الواي فاي شغّال عادي ومش محتاج ده."), kit.lpm(-1, -2, 0, 0, 0, 8))
            note("إزاي بتشتغل؟\n١) هنبعت زرار من الموبايل ناحية الجهاز.\n٢) لو الجهاز اتحرّك دوس «اشتغل» — لو لأ دوس «ما اشتغلش» ونجرّب كود تاني.\n٣) لما زرارين يشتغلوا، الريموت بيتثبّت لوحده.\nمن غير ماركات ولا موديلات ولا أكواد.", IrUiKit.MUTED, 13f, 8, 14)
            fun big(t: String, sub: String, fill: Int, k: String) {
                val v = kit.vbox().apply { gravity = Gravity.CENTER; setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(10)) }
                v.addView(kit.text(t, 22f, Color.WHITE, true, Gravity.CENTER)); v.addView(kit.text(sub, 12f, 0xCCFFFFFF.toInt(), false, Gravity.CENTER))
                kit.clickable(v, kit.shape(fill, 22, IrUiKit.STROKE)) { if (!hw) Notice.show(act, "مفيش بلاستر IR في الموبايل ده", 2200L) else { kind = k; brandScreen() } }
                body.addView(v, kit.lpm(-1, kit.dp(96), 6, 6, 6, 6))
            }
            big("📺 تلفزيون", "اظبط ريموت للتلفزيون", PRIMARY, "tv")
            big("🛰 ريسيفر / ساتلايت", "اظبط ريموت للريسيفر", 0xFF4A3A7C.toInt(), "sat")
            body.addView(kit.btn("📚 أنا عارف الماركة والموديل — اختار بنفسي", IrUiKit.CARD2, 13f) { openCatalog() }, kit.lpm(-1, kit.dp(46), 6, 14, 6, 0))
        }

        // ============================================================ ٢) الماركة (اختياري)
        fun detectBrand(): String {
            if (kind != "tv") return ""
            val m = IrTv.tvMfr(act).trim().lowercase(); if (m.isEmpty()) return ""
            val bs = db.filter { it.kind == "tv" }.map { it.brand }.distinct()
            bs.firstOrNull { it.lowercase() == m }?.let { return it }
            return bs.firstOrNull { it.length >= 3 && m.contains(it.lowercase()) } ?: ""
        }

        fun chips(names: List<String>, onTap: (String) -> Unit) {
            for (chunk in names.chunked(3)) {
                val r = kit.hbox(Gravity.CENTER_VERTICAL).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
                for (n in chunk) r.addView(kit.btn(n, IrUiKit.CARD2, 14f) { onTap(n) }, kit.lpm(0, kit.dp(46), 3, 3, 3, 3, 1f))
                for (i in chunk.size until 3) r.addView(View(act), kit.lpm(0, kit.dp(46), 3, 3, 3, 3, 1f))
                body.addView(r, kit.lp(-1, -2))
            }
        }

        fun brandScreen() {
            backFn = { if (startKind == null) { kind = ""; kindScreen() } else close() }
            reset((if (kind == "sat") "🛰 " else "📺 ") + "ماركة الجهاز")
            note("لو تعرف ماركة الجهاز اختارها — ده بيقلّل عدد المحاولات. ولو مش عارف ابدأ على طول.", IrUiKit.MUTED, 13f, 6, 8)
            val det = detectBrand()
            if (det.isNotEmpty()) body.addView(kit.btn("✨ تلفزيونك متعرّف إنه: $det — ابدأ بيه", PRIMARY, 15f) { brand = det; startProbe() }, kit.lpm(-1, kit.dp(52), 0, 0, 0, 8))
            val counts = db.filter { it.kind == kind }.groupingBy { it.brand }.eachCount()
            val shown: List<String> = if (kind == "tv") CatIndex.POPULAR.mapNotNull { p -> counts.keys.firstOrNull { it.equals(p, true) } }
                else counts.entries.sortedByDescending { it.value }.take(12).map { it.key }
            chips(shown) { brand = it; startProbe() }
            body.addView(kit.btn("🔍 ماركة تانية…", IrUiKit.CARD2, 14f) {
                val all = counts.keys.sortedBy { it.lowercase() }
                GAlert(act).setTitle("اختار الماركة").setItems(all.toTypedArray()) { _, i -> brand = all[i]; startProbe() }.setNegativeButton("إلغاء", null).show()
            }, kit.lpm(-1, kit.dp(46), 3, 6, 3, 0))
            body.addView(kit.btn("❓ مش عارف الماركة — ابدأ", IrUiKit.BLUE, 15f) { brand = ""; startProbe() }, kit.lpm(-1, kit.dp(54), 3, 12, 3, 0))
            note("الماركة غالبًا مكتوبة على الجهاز نفسه (تحت الشاشة أو ورا). من غير ماركة بنبدأ بالماركات الشهيرة — ممكن ياخد محاولات أكتر، وفي زرار «جرّب تلقائي» بيسهّلها عليك.", IrUiKit.SOFT, 11.5f, 8, 4)
        }

        // ============================================================ ٣) التجربة
        fun startProbe() {
            val keys = IrWizardCore.KEY_ORDER
            val cands = ArrayList<WizCand>()
            for (r in db) {
                if (r.kind != kind) continue
                val m = HashMap<String, String>()
                for (k in keys) { val s = r.keys[k] ?: continue; m[k] = IrWizardCore.sigId(s.proto, s.a, s.b, s.freq, s.raw) }
                if (m.isEmpty()) continue
                cands.add(WizCand(r.brand, r.name, r.kind, m, r.keys.size))
            }
            val names = cands.map { it.brand }.distinct()
            val prio: List<String> = when {
                brand.isNotEmpty() -> listOf(brand)
                kind == "tv" -> CatIndex.POPULAR.mapNotNull { p -> names.firstOrNull { it.equals(p, true) } }   // من غير ماركة: الشهيرة الأول
                else -> cands.groupingBy { it.brand }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key }
            }
            core = IrWizardCore(cands, prio, brand.isNotEmpty())
            altIdx = 0
            probeScreen()
        }

        fun sendProbe(p: WizProbe) {
            val c = core ?: return
            val r = IrTv.remoteOf(act, p.sample.kind, p.sample.brand, p.sample.name)
            val sig = r?.keys?.get(p.key)
            if (sig == null) { Notice.show(act, "الكود مش متاح — هنجرّب اللي بعده", 1800L); c.answer(false); probeScreen(); return }
            sent = true
            RemoteRouter.testSig(act, sig) { ok ->
                if (!ok && core?.probe() === p) { Notice.show(act, "⚠ الكود ده مش مدعوم على الموبايل — هنجرّب اللي بعده", 2200L); c.answer(false); probeScreen() }
            }
        }

        fun answer(worked: Boolean) {
            val c = core ?: return
            if (!sent) { Notice.show(act, "دوس على الزرار الكبير الأول عشان يتبعت للجهاز", 2200L); return }
            val first = c.confirmedKeys.isEmpty()
            c.answer(worked)
            if (worked && first) Notice.show(act, "✓ كويس! نأكّد بزرار تاني", 1800L)
            probeScreen()
        }

        fun probeScreen() {
            val c = core ?: return
            if (c.dead) { noMatchScreen(); return }
            if (c.locked()) { resultScreen(); return }
            val p = c.probe() ?: run { noMatchScreen(); return }
            sent = false
            backFn = { if (c.canUndo) { c.undo(); probeScreen() } else brandScreen() }
            reset("🎯 جرّب الزرار ده")
            val confirmed = c.confirmedKeys.size
            note("محاولة ${c.tries + 1} · لسه ${c.aliveCount} ريموت محتمل" + (if (confirmed > 0) " · اتأكد $confirmed من ${IrWizardCore.NEED_CONFIRMS}" else ""), IrUiKit.SOFT, 12f, 2, 6)
            val what = if (kind == "sat") "الريسيفر" else "التلفزيون"
            note(if (confirmed == 0) "وجّه الموبايل (البلاستر غالبًا فوق) ناحية $what واضغط الزرار الكبير." else "تمام، اشتغل! نأكّد بزرار تاني — اضغط الزرار الكبير.", Color.WHITE, 15f, 4, 8)
            val big = kit.vbox().apply { gravity = Gravity.CENTER; setPadding(kit.dp(8), kit.dp(8), kit.dp(8), kit.dp(8)) }
            big.addView(kit.text(IrRemoteView.labelOf(p.key), 40f, Color.WHITE, true, Gravity.CENTER))
            big.addView(kit.text(keyName(p.key), 15f, Color.WHITE, true, Gravity.CENTER))
            big.addView(kit.text("اضغط هنا لإرسال الزرار", 11f, 0xCCFFFFFF.toInt(), false, Gravity.CENTER))
            kit.clickable(big, kit.shape(PRIMARY, 28, 0x66FFFFFF)) { sendProbe(p) }
            body.addView(big, kit.lpm(-1, kit.dp(150), 36, 6, 36, 6))
            note(IrWizardCore.hint(p.key) + " (تقدر تدوسه أكتر من مرة)", IrUiKit.MUTED, 12.5f, 2, 12)
            val ans = kit.hbox(Gravity.CENTER_VERTICAL).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            ans.addView(kit.btn("👍 اشتغل", OK_COL, 18f) { answer(true) }, kit.lpm(0, kit.dp(66), 4, 0, 4, 0, 1f))
            ans.addView(kit.btn("👎 ما اشتغلش", NO_COL, 18f) { answer(false) }, kit.lpm(0, kit.dp(66), 4, 0, 4, 0, 1f))
            body.addView(ans, kit.lp(-1, -2))
            val aux = kit.hbox(Gravity.CENTER_VERTICAL).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            aux.addView(kit.btn("⏭ جرّب زرار تاني", IrUiKit.CARD2, 13f) {
                if (c.skipKey()) probeScreen() else Notice.show(act, "مفيش زرار تاني نجرّبه دلوقتي", 1800L)
            }, kit.lpm(0, kit.dp(44), 4, 12, 4, 0, 1f))
            if (c.canUndo) aux.addView(kit.btn("↩ رجّع خطوة", IrUiKit.CARD2, 13f) { c.undo(); probeScreen() }, kit.lpm(0, kit.dp(44), 4, 12, 4, 0, 1f))
            body.addView(aux, kit.lp(-1, -2))
            if (confirmed == 0) body.addView(kit.btn("▶ جرّب تلقائي (بيبعت كود كل ثانية ونص وانت تتفرّج)", IrUiKit.CARD2, 13f) { startAuto() }, kit.lpm(-1, kit.dp(44), 4, 8, 4, 0))
            if (c.needTip) body.addView(kit.banner("جرّبت كذا مرة ومفيش حاجة اشتغلت: اتأكد إن الجهاز شغّال، وإن البلاستر (فتحة صغيرة غالبًا في حرف الموبايل العلوي) ناحية الجهاز ومفيش حاجة قدّامه. جرّب كمان «زرار تاني» (مثلًا الصوت بدل الباور)."), kit.lpm(-1, -2, 0, 14, 0, 0))
            body.addView(kit.btn("📚 اختار بنفسي من الكتالوج", IrUiKit.CARD, 12f) { openCatalog() }, kit.lpm(-1, kit.dp(40), 0, 14, 0, 0))
        }

        // ============================================================ تجربة تلقائية (قبل أول نجاح بس)
        fun startAuto() {
            val c = core ?: return
            if (c.confirmedKeys.isNotEmpty()) return
            auto = true; autoSteps = 0
            backFn = { stopAuto(false) }
            reset("🔎 بجرّب تلقائي…")
            note("وجّه الموبايل ناحية الجهاز وراقبه. أول ما يحصل أي رد فعل (يفتح / يقفل / صوت…) دوس الزرار الكبير.", Color.WHITE, 14f, 6, 8)
            val info = kit.text("", 13f, IrUiKit.SOFT, true, Gravity.CENTER)
            autoInfo = info
            body.addView(info, kit.lpm(-1, -2, 0, 4, 0, 10))
            val big = kit.vbox().apply { gravity = Gravity.CENTER; setPadding(kit.dp(8), kit.dp(8), kit.dp(8), kit.dp(8)) }
            big.addView(kit.text("✋", 40f, Color.WHITE, true, Gravity.CENTER))
            big.addView(kit.text("وقّف — الجهاز استجاب!", 16f, Color.WHITE, true, Gravity.CENTER))
            kit.clickable(big, kit.shape(PRIMARY, 28, 0x66FFFFFF)) { stopAuto(true) }
            body.addView(big, kit.lpm(-1, kit.dp(150), 36, 6, 36, 10))
            body.addView(kit.btn("⏹ إلغاء التجربة التلقائية", IrUiKit.CARD2, 13f) { stopAuto(false) }, kit.lpm(-1, kit.dp(44), 4, 4, 4, 0))
            h.removeCallbacksAndMessages(null)
            h.post(autoTick)
        }

        val autoTick: Runnable = object : Runnable {
            override fun run() {
                if (!auto) return
                val c = core ?: return
                val p = c.probe()
                if (p == null || c.dead || c.locked()) { auto = false; probeScreen(); return }
                try {
                    val sig = IrTv.remoteOf(act, p.sample.kind, p.sample.brand, p.sample.name)?.keys?.get(p.key)
                    if (sig != null) RemoteRouter.testSig(act, sig) { }
                } catch (_: Throwable) {}
                c.answer(false)   // مؤقتًا «ما اشتغلش»؛ لو المستخدم وقّف بنرجّع كام خطوة
                autoSteps++
                autoInfo?.text = "الكود رقم $autoSteps · لسه ${c.aliveCount} ريموت محتمل"
                h.postDelayed(this, 1500L)
            }
        }

        /** [hit] = المستخدم شاف رد فعل: بنرجّع 3 خطوات (رد الفعل بيتأخر لحظة) ونرجع للتجربة اليدوية عشان يحدّد الكود بالظبط */
        fun stopAuto(hit: Boolean) {
            auto = false; h.removeCallbacksAndMessages(null)
            val c = core
            if (c == null) { brandScreen(); return }
            if (hit) {
                var n = 0
                while (n < 3 && c.canUndo) { c.undo(); n++ }
                if (n > 0) Notice.show(act, "رجّعنا $n خطوات — دوس الزرار الكبير وشوف أنهي كود بيشغّل الجهاز", 4200L)
            }
            probeScreen()
        }

        fun noMatchScreen() {
            val c = core
            backFn = { if (c != null && c.canUndo) { c.undo(); probeScreen() } else brandScreen() }
            reset("😕 مفيش ريموت اشتغل")
            note("مفيش ريموت في القاعدة اشتغل مع الأزرار اللي جرّبناها.\nممكن يكون البلاستر مش مصوّب ناحية الجهاز، أو الجهاز بيستخدم كود مش موجود عندنا.", IrUiKit.MUTED, 14f, 10, 14)
            if (c != null && c.canUndo) body.addView(kit.btn("↩ رجّع خطوة (يمكن ضغطت «ما اشتغلش» بالغلط)", IrUiKit.CARD2, 14f) { c.undo(); probeScreen() }, kit.lpm(-1, kit.dp(50), 0, 4, 0, 4))
            body.addView(kit.btn("🔁 ابدأ من الأول", PRIMARY, 15f) { brandScreen() }, kit.lpm(-1, kit.dp(50), 0, 4, 0, 4))
            body.addView(kit.btn("📚 اختار من الكتالوج بنفسي", IrUiKit.CARD2, 14f) { openCatalog() }, kit.lpm(-1, kit.dp(50), 0, 4, 0, 4))
        }

        // ============================================================ ٤) النتيجة + التثبيت
        fun resultScreen() {
            val c = core ?: return
            val list = c.ranked()
            if (list.isEmpty()) { noMatchScreen(); return }
            if (altIdx >= list.size) altIdx = 0
            val e = list[altIdx]
            backFn = { if (c.canUndo) { c.undo(); probeScreen() } else brandScreen() }
            reset("🎉 لقينا الريموت")
            note("الريموت ده اشتغل على الأزرار اللي جرّبناها:\n${e.brand} · ${e.name}", Color.WHITE, 15f, 4, 6)
            val rem = IrTv.remoteOf(act, e.kind, e.brand, e.name)
            val ce = CatEntry(e.brand, e.name, e.kind, rem?.keys?.keys?.toSet() ?: emptySet(), "")
            if (list.size > 1) {
                val nav = kit.hbox(Gravity.CENTER_VERTICAL)
                nav.addView(kit.btn("◀", IrUiKit.CARD2, 18f) { altIdx = (altIdx - 1 + list.size) % list.size; resultScreen() }, kit.lp(kit.dp(56), kit.dp(44)))
                nav.addView(kit.text("ريموت قريب ${altIdx + 1} / ${list.size}", 13f, IrUiKit.SOFT, true, Gravity.CENTER), kit.lp(0, -2, 1f))
                nav.addView(kit.btn("▶", IrUiKit.CARD2, 18f) { altIdx = (altIdx + 1) % list.size; resultScreen() }, kit.lp(kit.dp(56), kit.dp(44)))
                body.addView(nav, kit.lp(-1, -2))
                note("كلهم بنفس كود الأزرار اللي اتأكدت. الفرق في باقي الأزرار — اختار اللي أزراره أكمل.", IrUiKit.MUTED, 11.5f, 2, 4)
            }
            val existing = if (replaceId == null) IrProfiles.db(act).defaultFor(kind) else null
            if (replaceId != null) {
                val rp = IrProfiles.db(act).byId(replaceId)
                body.addView(kit.btn("🔁 استبدل «${rp?.name ?: ""}» بالريموت ده", PRIMARY, 16f) { saveEntry(ce, replaceId) }, kit.lpm(-1, kit.dp(56), 0, 6, 0, 4))
            } else {
                body.addView(kit.btn("💾 ثبّت الريموت ده", PRIMARY, 17f) { saveEntry(ce, null) }, kit.lpm(-1, kit.dp(58), 0, 6, 0, 4))
                if (existing != null) body.addView(kit.btn("🔁 استبدل «${existing.name}» بيه", IrUiKit.CARD2, 13f) { saveEntry(ce, existing.id) }, kit.lpm(-1, kit.dp(44), 0, 2, 0, 4))
            }
            val info = kit.text("جرّب أي زرار قبل ما تثبّت — الأزرار الباهتة مش موجودة في الريموت ده.", 12f, IrUiKit.MUTED, false, Gravity.CENTER).apply { setPadding(kit.dp(6), kit.dp(6), kit.dp(6), kit.dp(6)) }
            body.addView(info, kit.lp(-1, -2))
            if (rem == null) { note("الريموت مش لاقيه في القاعدة", IrUiKit.AMBER); return }
            body.addView(IrRemoteView.build(kit, rem.keys.keys.toSet()) { _, key ->
                val sig = rem.keys[key]
                info.text = "$key  ·  " + (sig?.let { IrTv.sigText(it).take(40) } ?: "")
                if (sig != null) RemoteRouter.testSig(act, sig) { ok -> if (!ok) Notice.show(act, "⚠ الإرسال فشل", 2000L) }
            })
        }

        fun saveEntry(ce: CatEntry, replaceTarget: String?) {
            try {
                if (replaceTarget != null) {
                    val rp = IrProfiles.db(act).byId(replaceTarget)
                    IrProfiles.replaceWith(act, replaceTarget, ce)
                    RemoteRouter.init(act)
                    Notice.show(act, "✓ اتبدّل — ${rp?.name ?: ""}", 2600L)
                } else {
                    val p = IrProfiles.saveFromCatalog(act, ce, kind)
                    RemoteRouter.init(act)
                    if (kind == "sat") {
                        IrProfiles.setTarget(act, p.id)
                        Notice.show(act, "✓ اتثبّت: ${p.name} — أزرار الريموت دلوقتي على الريسيفر. دوس على شريط الحالة فوق عشان تبدّل بينه وبين التلفزيون", 4200L)
                    } else Notice.show(act, "✓ اتثبّت: ${p.name} — الواي فاي لسه الأساسي، والـIR بيشتغل احتياطي لوحده", 3600L)
                }
                close()
            } catch (t: Throwable) { LogStore.err("IrWizardUi:save", t); Notice.show(act, "⚠ ما قدرتش أحفظ الريموت", 2200L) }
        }
    }
}
