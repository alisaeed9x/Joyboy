package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v809) ريموت IR (بلاستر الأشعة تحت الحمراء في الموبايل) بقاعدة أكواد تلفزيونات كبيرة جوه التطبيق (assets/irdb_tv.txt — من Flipper-IRDB، شغّالة من غير نت).
 * التلفزيون (نسخة v809+) بيبعت الماركة والموديل في /ping، والموبايل بيختار أقرب ريموت من القاعدة؛ لو مش شغّال تجرّب اللي بعده.
 * الأزرار الأساسية: ⏻ · ▲▼◀▶ · موافق · رجوع · رئيسية · قايمة · صوت + - · كتم · مصدر.
 * البروتوكولات: NEC · NECext · Samsung32 · RC5 · RC6 · SIRC (12/15/20) · RAW. (Kaseikyo/RCA/Pioneer مش مدعومة لسه)
 */
object IrTv {
    class Sig(val proto: String, val a: Long, val b: Long, val freq: Int, val raw: IntArray?)
    class Remote(val brand: String, val name: String, val keys: Map<String, Sig>)

    @Volatile private var all: List<Remote>? = null
    private var toggle = 0

    fun available(c: Context): Boolean = try {
        (c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true
    } catch (_: Throwable) { false }

    @Synchronized
    fun db(c: Context): List<Remote> {
        all?.let { return it }
        val out = ArrayList<Remote>()
        try {
            var brand = ""; var name = ""; var keys = LinkedHashMap<String, Sig>()
            fun flush() { if (name.isNotEmpty() && keys.isNotEmpty()) out.add(Remote(brand, name, keys)); keys = LinkedHashMap() }
            c.assets.open("irdb_tv.txt").bufferedReader(Charsets.UTF_8).useLines { seq ->
                for (line in seq) {
                    if (line.isEmpty()) continue
                    val p = line.split('|', limit = 4)
                    if (p[0] == "#") { flush(); brand = p.getOrElse(1) { "" }; name = p.getOrElse(2) { "" }; continue }
                    if (p.size < 4) continue
                    try {
                        if (p[1] == "RAW") {
                            val arr = p[3].trim().split(' ').map { it.toInt() }.toIntArray()
                            keys[p[0]] = Sig("RAW", 0L, 0L, p[2].toInt(), arr)
                        } else keys[p[0]] = Sig(p[1], p[2].toLong(), p[3].toLong(), 0, null)
                    } catch (_: Throwable) {}
                }
            }
            flush()
        } catch (e: Throwable) { LogStore.err("IrTv:db", e) }
        all = out
        return out
    }

    fun brands(c: Context): List<String> = db(c).map { it.brand }.distinct().sortedBy { it.lowercase() }

    private fun brandOf(c: Context, mfr: String): String {
        val m = mfr.trim().lowercase()
        if (m.isEmpty()) return ""
        val bs = brands(c)
        bs.firstOrNull { it.lowercase() == m }?.let { return it }
        val alias = mapOf("redmi" to "Xiaomi", "mi" to "Xiaomi", "mitv" to "Xiaomi", "samsung electronics" to "Samsung", "lge" to "LG", "lg electronics" to "LG")
        alias[m]?.let { a -> if (bs.contains(a)) return a }
        return bs.firstOrNull { it.length >= 2 && m.contains(it.lowercase()) } ?: ""
    }

    /** ريموتات الماركة مترتبة: الأقرب لاسم الموديل الأول، وبعدها الأكتر أزرارًا */
    fun candidates(c: Context, brand: String, model: String): List<Remote> {
        val toks = model.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        return db(c).filter { it.brand == brand }.sortedWith(
            compareByDescending<Remote> { r -> val n = r.name.lowercase(); toks.count { n.contains(it) } }
                .thenByDescending { it.keys.size }
                .thenBy { it.name })
    }

    // ===== التشفير: كل إشارة ← (تردد، نبضات µs بالتبادل: شغّال/مطفي) =====
    private class Pat {
        val l = ArrayList<Int>(); var lastMark = false
        fun on(us: Int) { if (l.isNotEmpty() && lastMark) l[l.size - 1] = l[l.size - 1] + us else { l.add(us); lastMark = true } }
        fun off(us: Int) { if (l.isEmpty()) return; if (!lastMark) l[l.size - 1] = l[l.size - 1] + us else { l.add(us); lastMark = false } }
        fun total() = l.sum()
    }

    private fun necBytes(p: Pat, bytes: IntArray) {
        p.on(9000); p.off(4500)
        for (b in bytes) for (i in 0 until 8) { p.on(560); p.off(if (((b shr i) and 1) == 1) 1690 else 560) }
        p.on(560)
    }

    fun encode(s: Sig): Pair<Int, IntArray>? {
        s.raw?.let { return if (it.size >= 2) Pair(if (s.freq in 20000..60000) s.freq else 38000, it) else null }
        val a = s.a; val c = s.b
        val p = Pat()
        when (s.proto) {
            "N" -> { val ad = (a and 0xFF).toInt(); val cm = (c and 0xFF).toInt(); necBytes(p, intArrayOf(ad, ad.inv() and 0xFF, cm, cm.inv() and 0xFF)); return Pair(38000, p.l.toIntArray()) }
            "NX" -> { necBytes(p, intArrayOf((a and 0xFF).toInt(), ((a shr 8) and 0xFF).toInt(), (c and 0xFF).toInt(), ((c shr 8) and 0xFF).toInt())); return Pair(38000, p.l.toIntArray()) }
            "S" -> {
                val a0 = (a and 0xFF).toInt(); val a1 = if (a > 0xFF) ((a shr 8) and 0xFF).toInt() else a0
                val cm = (c and 0xFF).toInt()
                p.on(4500); p.off(4500)
                for (b in intArrayOf(a0, a1, cm, cm.inv() and 0xFF)) for (i in 0 until 8) { p.on(550); p.off(if (((b shr i) and 1) == 1) 1650 else 550) }
                p.on(550)
                return Pair(38000, p.l.toIntArray())
            }
            "R5" -> {
                val t = toggle and 1
                val bits = ArrayList<Int>()
                bits.add(1); bits.add(if (c >= 64) 0 else 1); bits.add(t)
                for (i in 4 downTo 0) bits.add(((a shr i) and 1L).toInt())
                for (i in 5 downTo 0) bits.add(((c shr i) and 1L).toInt())
                for (b in bits) if (b == 1) { p.off(889); p.on(889) } else { p.on(889); p.off(889) }
                return Pair(36000, p.l.toIntArray())
            }
            "R6" -> {
                val t = toggle and 1
                p.on(2664); p.off(889)
                p.on(444); p.off(444)                                   // بت البداية = 1
                for (i in 0 until 3) { p.off(444); p.on(444) }          // مود 000
                if (t == 1) { p.on(889); p.off(889) } else { p.off(889); p.on(889) }   // بت التبديل (ضعف العرض)
                for (i in 7 downTo 0) if (((a shr i) and 1L) == 1L) { p.on(444); p.off(444) } else { p.off(444); p.on(444) }
                for (i in 7 downTo 0) if (((c shr i) and 1L) == 1L) { p.on(444); p.off(444) } else { p.off(444); p.on(444) }
                return Pair(36000, p.l.toIntArray())
            }
            "S12", "S15", "S20" -> {
                val an = when (s.proto) { "S12" -> 5; "S15" -> 8; else -> 13 }
                val one = Pat()
                one.on(2400); one.off(600)
                for (i in 0 until 7) { val b = ((c shr i) and 1L) == 1L; one.on(if (b) 1200 else 600); one.off(600) }
                for (i in 0 until an) { val b = ((a shr i) and 1L) == 1L; one.on(if (b) 1200 else 600); one.off(600) }
                val frame = one.l.toIntArray(); val ft = one.total()
                val gap = (45000 - ft).coerceAtLeast(5000)
                for (r in 0 until 3) { for ((i, v) in frame.withIndex()) if (i % 2 == 0) p.on(v) else p.off(v); p.off(gap) }
                return Pair(40000, p.l.toIntArray())
            }
        }
        return null
    }

    /** يبعت زرار [key] من [r] بالبلاستر. بيرجع false لو مفيش بلاستر أو الزرار مش في الريموت ده أو بروتوكوله مش مدعوم */
    fun send(c: Context, r: Remote, key: String): Boolean {
        val s = r.keys[key] ?: return false
        val e = encode(s) ?: return false
        return try {
            val m = c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager ?: return false
            if (!m.hasIrEmitter()) return false
            m.transmit(e.first, e.second)
            if (s.proto == "R5" || s.proto == "R6") toggle = toggle xor 1
            true
        } catch (t: Throwable) { LogStore.err("IrTv:send", t); false }
    }

    // ===== حفظ الاختيار =====
    private fun prefs(c: Context) = c.getSharedPreferences("irtv", Context.MODE_PRIVATE)
    fun savedBrand(c: Context): String = prefs(c).getString("brand", "") ?: ""
    fun savedRemote(c: Context): String = prefs(c).getString("remote", "") ?: ""
    fun save(c: Context, brand: String, remote: String) { prefs(c).edit().putString("brand", brand).putString("remote", remote).apply() }
    /** (v809) من /ping التلفزيون: الماركة والموديل */
    fun saveTvInfo(c: Context, mfr: String, model: String) { prefs(c).edit().putString("tv_mfr", mfr).putString("tv_model", model).apply() }
    fun tvMfr(c: Context): String = prefs(c).getString("tv_mfr", "") ?: ""
    fun tvModel(c: Context): String = prefs(c).getString("tv_model", "") ?: ""
    /** (v809) سويتش الريموت: false = واي فاي (الأساسي) · true = IR (بلاستر الموبايل) */
    fun irMode(c: Context): Boolean = prefs(c).getBoolean("ir_mode", false)
    fun setIrMode(c: Context, on: Boolean) { prefs(c).edit().putBoolean("ir_mode", on).apply() }
    /** الريموت المختار (المحفوظ، وإلا الأقرب لماركة وموديل التلفزيون)؛ null لو الماركة مش معروفة لسه */
    fun current(c: Context): Remote? {
        val b = savedBrand(c).ifEmpty { brandOf(c, tvMfr(c)) }
        if (b.isEmpty()) return null
        val l = candidates(c, b, tvModel(c))
        val sv = savedRemote(c)
        return l.firstOrNull { it.name == sv } ?: l.firstOrNull()
    }
    /** يبعت [key] (POWER/UP/DOWN/LEFT/RIGHT/OK/BACK/HOME/MENU/MUTE/VOLUP/VOLDN/SRC) من الريموت المختار */
    fun press(c: Context, key: String): Boolean { val r = current(c) ?: return false; return send(c, r, key) }

    // ===== الواجهة =====
    fun show(act: Activity) {
        val dm = act.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        if (!available(act)) {
            AlertDialog.Builder(act).setTitle("📡 ريموت IR").setMessage("الموبايل ده مفيهوش بلاستر أشعة تحت الحمراء، فمش هينفع نتحكم في التلفزيون بيه. (الريموت العادي بالواي فاي شغّال زي ما هو.)").setPositiveButton("تمام", null).show()
            return
        }
        var brand = savedBrand(act).ifEmpty { brandOf(act, tvMfr(act)) }
        var list: List<Remote> = emptyList()
        var idx = 0
        fun reload() {
            list = if (brand.isEmpty()) emptyList() else candidates(act, brand, tvModel(act))
            val sv = savedRemote(act)
            idx = list.indexOfFirst { it.name == sv }.let { if (it < 0) 0 else it }
        }
        reload()

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { setColor(0xFF0F1830.toInt()); cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0xFF2B3C60.toInt()) }
        }
        fun txt(t: String, sp: Float, col: Int = Color.WHITE, bold: Boolean = false) = TextView(act).apply {
            text = t; textSize = sp; setTextColor(col); gravity = Gravity.CENTER; if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun btn(t: String, w: Int, h: Int, fill: Int = 0xFF1E2A44.toInt(), sp: Float = 18f, onTap: () -> Unit) = TextView(act).apply {
            text = t; textSize = sp; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0x44FFFFFF) }
            layoutParams = LinearLayout.LayoutParams(dp(w), dp(h)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            setOnClickListener { onTap() }
        }
        fun spacer() = View(act).apply { layoutParams = LinearLayout.LayoutParams(dp(70), dp(56)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) } }
        fun row(vararg v: View) = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR; for (x in v) addView(x) }

        val brandB = btn("", 260, 40, 0xFF22304F.toInt(), 13f) {}
        val modelB = btn("", 260, 40, 0xFF22304F.toInt(), 13f) {}
        val info = txt("", 12f, 0xFFB8BDC9.toInt())
        root.addView(txt("📡 ريموت IR", 17f, Color.WHITE, true))
        root.addView(brandB); root.addView(modelB)
        root.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        fun cur(): Remote? = list.getOrNull(idx)
        fun press(key: String) {
            val r = cur()
            if (r == null) { Notice.show(act, "اختار الماركة الأول", 1800L); return }
            if (!send(act, r, key)) Notice.show(act, "الزرار ده مش موجود في الريموت ده", 1500L)
        }
        fun refresh() {
            brandB.text = "الماركة: " + (if (brand.isEmpty()) "اختار ▾" else "$brand ▾")
            val r = cur()
            modelB.text = if (r == null) "الريموت: —" else "الريموت: ${r.name.take(28)} (${idx + 1}/${list.size}) ▾"
            info.text = if (tvModel(act).isNotEmpty()) "التلفزيون: ${tvMfr(act)} ${tvModel(act)}" else "جرّب ⏻ — لو التلفزيون ما استجابش دوس «الكود اللي بعده»"
        }
        brandB.setOnClickListener {
            val bs = brands(act)
            AlertDialog.Builder(act).setTitle("الماركة").setItems(bs.toTypedArray()) { _, i -> brand = bs[i]; reload(); save(act, brand, cur()?.name ?: ""); refresh() }.show()
        }
        modelB.setOnClickListener {
            if (list.isEmpty()) return@setOnClickListener
            val names = list.map { it.name }.toTypedArray()
            AlertDialog.Builder(act).setTitle("الريموت").setItems(names) { _, i -> idx = i; save(act, brand, list[i].name); refresh() }.show()
        }

        root.addView(row(btn("⏻", 70, 48, 0xFFC62828.toInt(), 22f) { press("POWER") }, btn("المصدر", 70, 48, sp = 12f) { press("SRC") }, btn("🔇", 70, 48) { press("MUTE") }),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        root.addView(row(spacer(), btn("▲", 70, 56) { press("UP") }, spacer()))
        root.addView(row(btn("◀", 70, 56) { press("LEFT") }, btn("OK", 70, 56, 0xFF1D4ED8.toInt(), 16f) { press("OK") }, btn("▶", 70, 56) { press("RIGHT") }))
        root.addView(row(spacer(), btn("▼", 70, 56) { press("DOWN") }, spacer()))
        root.addView(row(btn("رجوع", 70, 48, sp = 13f) { press("BACK") }, btn("رئيسية", 70, 48, sp = 13f) { press("HOME") }, btn("قايمة", 70, 48, sp = 13f) { press("MENU") }))
        root.addView(row(btn("🔉 −", 100, 48) { press("VOLDN") }, btn("🔊 +", 100, 48) { press("VOLUP") }))
        root.addView(btn("⏭ الكود اللي بعده", 220, 44, 0xFF7C4DBB.toInt(), 14f) {
            if (list.isEmpty()) { Notice.show(act, "اختار الماركة الأول", 1800L) }
            else { idx = (idx + 1) % list.size; save(act, brand, list[idx].name); refresh(); press("POWER") }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        refresh()

        val d = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
        d.setContentView(root)
        d.setCanceledOnTouchOutside(true)
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); setLayout((dm.widthPixels * 0.94f).toInt(), -2); setGravity(Gravity.CENTER) }
        d.show()
    }
}
