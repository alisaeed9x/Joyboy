package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * (v821) إدارة بروفايلات IR المحفوظة: إضافة · إعادة تسمية · اختبار · تعديل (متقدّم/اختياري) · استبدال · اختيار · حذف + إعدادات IR التقنية (بعيد عن الريموت العادي).
 */
object IrProfilesUi {
    fun show(act: Activity, onClose: (() -> Unit)? = null) { try { Manager(act, onClose).open() } catch (e: Throwable) { LogStore.err("IrProfilesUi", e) } }

    private fun kindLabel(k: String) = when (k) { "sat" -> "🛰 ساتلايت/ريسيفر"; "other" -> "🔌 جهاز تاني"; else -> "📺 تلفزيون" }

    private class Manager(val act: Activity, val onClose: (() -> Unit)?) {
        val kit = IrUiKit(act)
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        lateinit var list: LinearLayout

        fun open() {
            val root = kit.vbox().apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(IrUiKit.BG); setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(10)) }
            val top = kit.hbox(Gravity.CENTER_VERTICAL)
            top.addView(kit.btn("→", IrUiKit.CARD2, 18f) { close() }, kit.lp(kit.dp(48), kit.dp(44)))
            top.addView(kit.text("📚 ريموتات IR المحفوظة", 17f, Color.WHITE, true).apply { setPadding(kit.dp(10), 0, kit.dp(10), 0) }, kit.lp(0, kit.dp(44), 1f))
            top.addView(kit.btn("⚙", IrUiKit.CARD2, 18f) { settings() }, kit.lp(kit.dp(48), kit.dp(44)))
            root.addView(top, kit.lp(-1, -2))
            list = kit.vbox()
            root.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, kit.lpm(-1, 0, 0, 8, 0, 0, 1f))
            // (v855) الإضافة بالتجربة (اشتغل / ما اشتغلش) أولًا — والكتالوج (ماركة ← موديل) لسه موجود جوّاها وفي الزرار التاني
            root.addView(kit.btn("🎯 إضافة ريموت بالتجربة — تلفزيون", IrUiKit.BLUE, 15f) { IrWizardUi.show(act, "tv") { fill() } }, kit.lpm(-1, kit.dp(48), 0, 6, 0, 0))
            root.addView(kit.btn("🎯 إضافة ريموت بالتجربة — ريسيفر ساتلايت", 0xFF4A3A7C.toInt(), 15f) { IrWizardUi.show(act, "sat") { fill() } }, kit.lpm(-1, kit.dp(48), 0, 6, 0, 0))
            root.addView(kit.btn("📚 اختيار بالماركة والموديل", IrUiKit.CARD2, 13f) { IrCatalogUi.show(act, null, "tv") { fill() } }, kit.lpm(-1, kit.dp(42), 0, 6, 0, 0))
            d.setContentView(root)
            d.setOnKeyListener { _, k, e -> if (k == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { close(); true } else false }
            d.show(); fill()
        }

        fun close() { try { d.dismiss() } catch (_: Throwable) {}; onClose?.invoke() }

        fun fill() {
            list.removeAllViews()
            val db = IrProfiles.db(act); val main = IrProfiles.mainTv(act); val tgt = IrProfiles.target(act).profile
            if (!IrTv.available(act)) list.addView(kit.banner("مفيش بلاستر IR في الموبايل ده: الريموتات بتتحفظ لكن مش هتتبعت. الواي فاي شغّال مستقل."), kit.lpm(-1, -2, 0, 0, 0, 8))
            if (db.profiles.isEmpty()) list.addView(kit.text("لسه مفيش ريموتات. اضغط ➕ واختار ماركة.", 14f, IrUiKit.MUTED, false, Gravity.CENTER), kit.lp(-1, kit.dp(120)))
            for (p in db.profiles) list.addView(card(p, p === main, p === tgt || (tgt == null && p === main), db.defaults[p.kind] == p.id), kit.lpm(-1, -2, 0, 4, 0, 4))
        }

        fun card(p: IrProfile, isMain: Boolean, isTarget: Boolean, isDefault: Boolean): View {
            val c = kit.vbox().apply { setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10)) }
            c.background = kit.shape(IrUiKit.CARD, 18, if (isTarget) IrUiKit.BLUE else IrUiKit.STROKE, if (isTarget) 2 else 1)
            c.addView(kit.text(p.name + (if (isDefault) "  ★" else ""), 15f, Color.WHITE, true))
            val rem = IrProfiles.remote(act, p)
            val sub = kindLabel(p.kind) + " · " + p.brand + " · " + p.remote + (if (rem == null) " · ⚠ مش في القاعدة" else " · ${IrProfiles.keysOf(act, p).size} زرار") +
                (if (isMain) " · 📶 مربوط بالتلفزيون" else "") + (if (p.remap.isNotEmpty() || p.custom.isNotEmpty()) " · معدّل" else "")
            c.addView(kit.text(sub, 11.5f, IrUiKit.MUTED))
            val r = kit.hbox().apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            fun a(t: String, f: () -> Unit) = r.addView(kit.btn(t, IrUiKit.CARD2, 12f, f), kit.lpm(0, kit.dp(40), 2, 6, 2, 0, 1f))
            a(if (isTarget) "✓ مختار" else "اختيار") { IrProfiles.setTarget(act, if (isMain) "" else p.id); fill() }
            a("🎛 اختبار") { test(p) }
            a("✏ تعديل") { Editor(act, p) { fill() }.open() }
            a("🔁 استبدال") { IrWizardUi.show(act, p.dbKind(), p.id) { fill() } }
            a("🗑") { GAlert(act).setTitle("حذف «${p.name}»؟").setMessage("الأكواد الأصلية في القاعدة مش بتتأثر.").setPositiveButton("حذف") { _, _ -> IrProfiles.delete(act, p.id); fill() }.setNegativeButton("إلغاء", null).show() }
            c.addView(r, kit.lp(-1, -2))
            if (!isDefault) c.addView(kit.btn("★ خليه الافتراضي لنوعه", IrUiKit.CARD2, 11f) { IrProfiles.setDefault(act, p.id); fill() }, kit.lpm(-1, kit.dp(34), 0, 4, 0, 0))
            return c
        }

        /** اختبار: نفس المعاينة الواقعية بأكواد البروفايل (بتشمل التعديلات اليدوية) */
        fun test(p: IrProfile) {
            val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val root = kit.vbox().apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(IrUiKit.BG); setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(10)) }
            root.addView(kit.btn("→ رجوع", IrUiKit.CARD2, 14f) { dlg.dismiss() }, kit.lp(-1, kit.dp(44)))
            root.addView(kit.text(p.name, 15f, Color.WHITE, true, Gravity.CENTER), kit.lpm(-1, -2, 0, 6, 0, 2))
            val info = kit.text("اضغط زرار لتجربته", 12f, IrUiKit.MUTED, false, Gravity.CENTER)
            root.addView(info, kit.lp(-1, -2))
            val keys = IrProfiles.keysOf(act, p).toSet()
            val hw = IrTv.available(act)
            val rv = IrRemoteView.build(kit, keys) { _, k ->
                val sig = p.custom[k]?.let { IrTv.parseSig(it) } ?: IrProfiles.remote(act, p)?.keys?.get(p.remap[k] ?: k)
                info.text = k + (sig?.let { "  ·  " + IrTv.sigText(it).take(40) } ?: "")
                if (!hw) Notice.show(act, "مفيش بلاستر IR في الموبايل ده", 2000L) else if (sig != null) RemoteRouter.testSig(act, sig) { ok -> if (!ok) Notice.show(act, "⚠ الإرسال فشل", 2000L) }
            }
            root.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(rv) }, kit.lpm(-1, 0, 0, 6, 0, 0, 1f))
            dlg.setContentView(root); dlg.show()
        }

        // ---------------- إعدادات IR التقنية
        fun settings() {
            val db = IrProfiles.db(act); val st = db.settings
            val box = kit.vbox().apply { setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(6)) }
            val modes = listOf("auto" to "تلقائي (واي فاي أساسي ← IR احتياطي) — الموصى به", "wifi" to "واي فاي بس", "ir" to "IR أولًا (واي فاي بس للرئيسية)", "irfirst" to "IR للباور/الأسهم/OK/الكتم (v820 القديم)")
            box.addView(kit.text("طريقة الإرسال", 13f, Color.BLACK, true))
            var sel = modes.indexOfFirst { it.first == st.mode }.coerceAtLeast(0)
            for ((i, m) in modes.withIndex()) box.addView(android.widget.RadioButton(act).apply { text = m.second; isChecked = i == sel; setOnClickListener { sel = i } }.also { rb -> rb.setOnClickListener { sel = i; for (j in 0 until box.childCount) (box.getChildAt(j) as? android.widget.RadioButton)?.isChecked = (box.getChildAt(j) === rb) } })
            val vol = android.widget.CheckBox(act).apply { text = "الصوت/الكتم يروح لتلفزيوني لو الريسيفر مفيهوش أزرار صوت"; isChecked = st.volFollowsTv }
            box.addView(vol)
            val dualNames = listOf(Cmds.OK to "OK", Cmds.MENU to "Menu", Cmds.HOME_TV to "TV Home")
            box.addView(kit.text("إرسال واي فاي + IR مع بعض (خطر: ممكن الأمر يتنفّذ مرتين — مش متاح لباور/كتم/أسهم/صوت)", 12f, Color.DKGRAY), kit.lpm(-1, -2, 0, 10, 0, 0))
            val dualCbs = dualNames.map { (id, n) -> android.widget.CheckBox(act).apply { text = n; isChecked = id in st.dual }.also { box.addView(it) } }
            box.addView(kit.text(RemoteRouter.diag(act), 11f, Color.DKGRAY), kit.lpm(-1, -2, 0, 10, 0, 0))
            GAlert(act).setTitle("⚙ إعدادات IR").setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("حفظ") { _, _ ->
                    st.mode = modes[sel].first; st.volFollowsTv = vol.isChecked
                    st.dual.clear(); dualNames.forEachIndexed { i, (id, _) -> if (dualCbs[i].isChecked) st.dual.add(id) }
                    IrProfiles.save(act); Notice.show(act, "✓ اتحفظت إعدادات IR", 1800L)
                }
                .setNeutralButton("الريموت الكامل القديم") { _, _ -> val k = IrProfiles.target(act).profile?.dbKind() ?: "tv"; IrTv.show(act, k) { } }
                .setNegativeButton("إغلاق", null).show()
        }
    }

    /** تعديل بروفايل: الاسم · نوع الجهاز · ربط التلفزيون · (متقدّم) ربط زرار بزرار تاني أو كود يدوي */
    private class Editor(val act: Activity, val p: IrProfile, val done: () -> Unit) {
        val kit = IrUiKit(act)
        fun open() {
            val box = kit.vbox().apply { setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(4)) }
            val name = EditText(act).apply { setText(p.name); setSingleLine() }
            box.addView(name)
            val kinds = listOf("tv" to "تلفزيون", "sat" to "ساتلايت/ريسيفر", "other" to "جهاز تاني")
            var k = p.kind
            val rg = android.widget.RadioGroup(act)
            for ((id, lb) in kinds) rg.addView(android.widget.RadioButton(act).apply { text = lb; tag = id; isChecked = id == k; setOnClickListener { k = id } })
            box.addView(rg)
            val cur = try { RemoteClient.current?.id ?: "" } catch (_: Throwable) { "" }
            val link = android.widget.CheckBox(act).apply { text = "مربوط بالتلفزيون المقترن حاليًا (احتياطي للواي فاي)"; isChecked = p.deviceId.isNotEmpty() && p.deviceId == cur; isEnabled = cur.isNotEmpty() }
            box.addView(link)
            box.addView(kit.btn("🔧 تعديل الأزرار (متقدّم)", 0xFF455A64.toInt(), 13f) { mapping() }, kit.lpm(-1, kit.dp(42), 0, 8, 0, 0))
            GAlert(act).setTitle("✏ تعديل").setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("حفظ") { _, _ ->
                    IrProfiles.rename(act, p.id, name.text.toString())
                    p.kind = k
                    if (cur.isNotEmpty()) p.deviceId = if (link.isChecked && k == "tv") cur else if (p.deviceId == cur) "" else p.deviceId
                    IrProfiles.db(act).defaults.let { dd -> if (dd[k] == null) dd[k] = p.id }
                    IrProfiles.save(act); done()
                }.setNegativeButton("إلغاء", null).show()
        }

        /** الأزرار المنطقية اللي الريموت الموحّد بيستخدمها: كل واحد ← أي زرار/كود بيتبعت فعلًا */
        fun mapping() {
            val cmds = Cmds.all.filter { it.irKeys.isNotEmpty() && it.id != Cmds.OK_LONG }
            val names = cmds.map { c ->
                val key = c.irKeys[0]; val ok = IrProfiles.has(act, p, c)
                (if (ok) "✓ " else "✗ ") + c.label + "  ←  " + (p.custom[key]?.let { "كود يدوي" } ?: p.remap[key] ?: if (ok) key else "مفيش")
            }.toTypedArray()
            GAlert(act).setTitle("ربط الأزرار").setItems(names) { _, i -> pick(cmds[i]) }.setNegativeButton("إغلاق", null).show()
        }

        fun pick(c: CmdDef) {
            val key = c.irKeys[0]
            GAlert(act).setTitle(c.label).setItems(arrayOf("استخدم زرار تاني من نفس الريموت", "اكتب كود يدوي", "رجوع للأصلي")) { _, w ->
                when (w) {
                    0 -> {
                        val ks = IrProfiles.remote(act, p)?.keys?.keys?.sorted() ?: emptyList()
                        GAlert(act).setTitle("زرار بديل").setItems(ks.toTypedArray()) { _, j -> p.custom.remove(key); p.remap[key] = ks[j]; IrProfiles.save(act); mapping() }.show()
                    }
                    1 -> {
                        val e = EditText(act).apply { hint = "مثال: NX|57088|58140  أو  RAW|38000|9000 4500 560 …"; setText(p.custom[key] ?: ""); inputType = InputType.TYPE_CLASS_TEXT }
                        GAlert(act).setTitle("كود يدوي").setMessage("البروتوكولات: N · NX · S · R5 · R6 · S12 · S15 · S20 · RAW").setView(e)
                            .setPositiveButton("حفظ") { _, _ ->
                                val t = e.text.toString().trim()
                                if (ProfileDb.SigText.valid(t)) { p.remap.remove(key); p.custom[key] = t; IrProfiles.save(act); mapping() } else Notice.show(act, "الصيغة غلط", 2200L)
                            }.setNeutralButton("جرّب") { _, _ ->
                                val s = IrTv.parseSig(e.text.toString().trim())
                                if (s == null) Notice.show(act, "الصيغة غلط", 2000L) else RemoteRouter.testSig(act, s) { ok -> Notice.show(act, if (ok) "اتبعت" else "فشل الإرسال", 1500L) }
                            }.setNegativeButton("إلغاء", null).show()
                    }
                    else -> { p.remap.remove(key); p.custom.remove(key); IrProfiles.save(act); mapping() }
                }
            }.show()
        }
    }
}
