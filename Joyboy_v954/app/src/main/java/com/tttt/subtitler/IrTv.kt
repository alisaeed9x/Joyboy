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
import android.widget.FrameLayout
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
    class Remote(val brand: String, val name: String, val keys: Map<String, Sig>, val kind: String = "tv")  // (v810) kind: tv | sat

    @Volatile private var all: List<Remote>? = null
    private var toggle = 0

    fun available(c: Context): Boolean = try {
        (c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true
    } catch (_: Throwable) { false }

    /** (v810) تشخيص: حالة البلاستر وترددات الموبايل المدعومة (لو 38000 برّه المدى الإرسال بيفشل) */
    fun irInfo(c: Context): String = try {
        val m = c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (m == null || !m.hasIrEmitter()) "البلاستر: ❌ مش موجود"
        else "البلاستر: ✅ موجود · " + (m.carrierFrequencies?.joinToString(" / ") { "${it.minFrequency / 1000}-${it.maxFrequency / 1000}kHz" } ?: "")
    } catch (t: Throwable) { "البلاستر: ⚠ " + (t.message ?: "") }

    /** (v821) قراءة صيغة القاعدة (سطر "#|ماركة|اسم" ثم "مفتاح|بروتوكول|a|b") — دالة صافية عشان تتختبر على JVM */
    fun parseInto(lines: Sequence<String>, kind: String, out: MutableList<Remote>) {
        var brand = ""; var name = ""; var keys = LinkedHashMap<String, Sig>()
        fun flush() { if (name.isNotEmpty() && keys.isNotEmpty()) out.add(Remote(brand, name, keys, kind)); keys = LinkedHashMap() }
        for (line in lines) {
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
        flush()
    }

    private fun loadFile(c: Context, asset: String, kind: String, out: ArrayList<Remote>) {
        try { c.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { seq -> parseInto(seq, kind, out) } }
        catch (e: Throwable) { LogStore.err("IrTv:db:" + asset, e) }
    }

    /** (v810) القاعدتين: تلفزيونات (irdb_tv.txt) + ساتلايت/ريسيفرات (irdb_sat.txt — من Mi Remote DB: Set-top box + Cable/Satellite box) */
    @Synchronized
    fun db(c: Context): List<Remote> {
        all?.let { return it }
        val out = ArrayList<Remote>()
        loadFile(c, "irdb_tv.txt", "tv", out)
        loadFile(c, "irdb_tv_mi.txt", "tv", out)   // (v810) تلفزيونات إضافية من Mi Remote DB (1272 ريموت · 557 ماركة، بينها TCL بأكواد مختلفة عن Roku)
        loadFile(c, "irdb_sat.txt", "sat", out)
        all = out
        return out
    }

    fun brands(c: Context, kind: String = "tv"): List<String> = db(c).filter { it.kind == kind }.map { it.brand }.distinct().sortedBy { it.lowercase() }

    private fun brandOf(c: Context, mfr: String): String {
        val m = mfr.trim().lowercase()
        if (m.isEmpty()) return ""
        val bs = brands(c, "tv")
        bs.firstOrNull { it.lowercase() == m }?.let { return it }
        val alias = mapOf("redmi" to "Xiaomi", "mi" to "Xiaomi", "mitv" to "Xiaomi", "samsung electronics" to "Samsung", "lge" to "LG", "lg electronics" to "LG")
        alias[m]?.let { a -> if (bs.contains(a)) return a }
        return bs.firstOrNull { it.length >= 2 && m.contains(it.lowercase()) } ?: ""
    }

    /** ريموتات الماركة مترتبة: الأقرب لاسم الموديل الأول، وبعدها الأكتر أزرارًا */
    fun candidates(c: Context, kind: String, brand: String, model: String): List<Remote> {
        val toks = model.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        return db(c).filter { it.kind == kind && it.brand == brand }.sortedWith(
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
        return sendSig(c, s)
    }

    /** (v821) يبعت إشارة واحدة بالبلاستر (بيستخدمها الراوتر مع البروفايلات والأكواد اليدوية) */
    fun sendSig(c: Context, s: Sig): Boolean {
        val e = encode(s) ?: return false
        return try {
            val m = c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager ?: return false
            if (!m.hasIrEmitter()) return false
            m.transmit(e.first, e.second)
            if (s.proto == "R5" || s.proto == "R6") toggle = toggle xor 1
            true
        } catch (t: Throwable) { LogStore.err("IrTv:send", t); false }
    }

    /** (v821) كود يدوي "N|addr|cmd" أو "RAW|freq|ints" ← إشارة (null لو الصيغة غلط) */
    fun parseSig(t: String): Sig? {
        if (!ProfileDb.SigText.valid(t)) return null
        val p = t.trim().split('|', limit = 3)
        return try {
            if (p[0] == "RAW") Sig("RAW", 0L, 0L, p[1].trim().toInt(), p[2].trim().split(Regex("\\s+")).map { it.toInt() }.toIntArray())
            else Sig(p[0], p[1].trim().toLong(), p[2].trim().toLong(), 0, null)
        } catch (_: Throwable) { null }
    }

    /** (v821) نص الكود لعرضه/تعديله (عكس parseSig) */
    fun sigText(s: Sig): String = if (s.raw != null) "RAW|${s.freq}|" + s.raw.joinToString(" ") else "${s.proto}|${s.a}|${s.b}"

    /** (v821) فهرس الكتالوج على كل القاعدة (بيتبني مرة واحدة) */
    @Volatile private var cat: CatIndex? = null
    @Synchronized fun catalog(c: Context): CatIndex {
        cat?.let { return it }
        val idx = catalogOf(db(c))
        cat = idx
        return idx
    }
    fun catalogOf(remotes: List<Remote>): CatIndex = CatIndex(remotes.map { r ->
        val pw = r.keys["POWER"]
        val sig = if (pw != null && pw.raw == null) "${pw.proto}|${pw.a}" else ""
        CatEntry(r.brand, r.name, r.kind, r.keys.keys.toSet(), sig)
    })
    /** (v821) الريموت الفعلي من قاعدة IR بنوعه وماركته واسمه */
    @Volatile private var byKey: Map<String, Remote>? = null
    @Synchronized fun remoteOf(c: Context, kind: String, brand: String, name: String): Remote? {
        val m = byKey ?: HashMap<String, Remote>().also { h -> for (r in db(c)) h.putIfAbsent(r.kind + "\u0001" + r.brand + "\u0001" + r.name, r) }.also { byKey = it }
        return m[kind + "\u0001" + brand + "\u0001" + name]
    }

    // ===== حفظ الاختيار =====
    private fun prefs(c: Context) = c.getSharedPreferences("irtv", Context.MODE_PRIVATE)
    private fun sfx(kind: String) = if (kind == "sat") "_sat" else ""
    fun savedBrand(c: Context, kind: String = "tv"): String = prefs(c).getString("brand" + sfx(kind), "") ?: ""
    fun savedRemote(c: Context, kind: String = "tv"): String = prefs(c).getString("remote" + sfx(kind), "") ?: ""
    fun save(c: Context, kind: String, brand: String, remote: String) { prefs(c).edit().putString("brand" + sfx(kind), brand).putString("remote" + sfx(kind), remote).apply() }
    /** (v809) من /ping التلفزيون: الماركة والموديل */
    fun saveTvInfo(c: Context, mfr: String, model: String) { prefs(c).edit().putString("tv_mfr", mfr).putString("tv_model", model).apply() }
    fun tvMfr(c: Context): String = prefs(c).getString("tv_mfr", "") ?: ""
    fun tvModel(c: Context): String = prefs(c).getString("tv_model", "") ?: ""
    /** (v810) سويتش الريموت: "" = واي فاي (الأساسي) · "tv" = IR تلفزيون · "sat" = IR ساتلايت. (قيمة v809 القديمة ir_mode=true بتتقرا "tv") */
    fun irKind(c: Context): String {
        val k = prefs(c).getString("ir_kind", null)
        if (k != null) return k
        return if (prefs(c).getBoolean("ir_mode", false)) "tv" else ""
    }
    fun setIrKind(c: Context, kind: String) { prefs(c).edit().putString("ir_kind", kind).apply() }
    fun irMode(c: Context): Boolean = irKind(c).isNotEmpty()
    /** الريموت المختار للنوع [kind] (المحفوظ، وإلا للتلفزيون الأقرب لماركة وموديل التلفزيون)؛ null لو الماركة مش معروفة لسه */
    fun current(c: Context, kind: String = irKind(c).ifEmpty { "tv" }): Remote? {
        val b = savedBrand(c, kind).ifEmpty { if (kind == "tv") brandOf(c, tvMfr(c)) else "" }
        if (b.isEmpty()) return null
        val l = candidates(c, kind, b, if (kind == "tv") tvModel(c) else "")
        val sv = savedRemote(c, kind)
        return l.firstOrNull { it.name == sv } ?: l.firstOrNull()
    }
    private fun aliasKeys(key: String): List<String> = when (key) {
        "BACK" -> listOf("EXIT"); "EXIT" -> listOf("BACK"); "OK" -> listOf("SEL", "ENTER"); "HOME" -> listOf("MENU")
        "UP" -> listOf("CHUP"); "DOWN" -> listOf("CHDN"); "LEFT" -> listOf("VOLDN"); "RIGHT" -> listOf("VOLUP")   // ريسيفرات كتير مفيهاش أسهم: القنوات/الصوت بيتحركوا في القوايم
        else -> emptyList()
    }
    /** يبعت [key] (POWER/UP/DOWN/LEFT/RIGHT/OK/BACK/HOME/MENU/MUTE/VOLUP/VOLDN/SRC…) من الريموت المختار؛ لو الزرار مش فيه بيجرّب بديله (رجوع↔خروج…) */
    fun press(c: Context, key: String): Boolean = pressOn(c, current(c), key)
    /** (v820) نفس press بس على ريموت نوع معيّن (tv/sat) مش اللي مختاره سويتش IR دلوقتي — للأزرار المأخودة على الريموت الأساسي */
    fun pressKind(c: Context, kind: String, key: String): Boolean = pressOn(c, current(c, kind), key)
    private fun pressOn(c: Context, r: Remote?, key: String): Boolean {
        if (r == null) return false
        if (r.keys.containsKey(key)) return send(c, r, key)
        for (a in aliasKeys(key)) if (r.keys.containsKey(a)) return send(c, r, a)
        return false
    }

    // (v820) «أزرار IR على الريموت الأساسي»: الريموت الأساسي (واي فاي) يفضل زي ما هو، بس ⏻ + ▲▼◀▶ + OK + 🔇 بتتبعت بالـIR من الريموت المختار.
    // الرئيسية مش منهم: بتفضل رئيسية البرنامج (واي فاي) عشان رئيسية التلفزيون بتفتح جوجل تي في.
    /** "" = مطفي · "tv" / "sat" = الريموت اللي الأزرار بتتاخد منه */
    fun hybridKind(c: Context): String = prefs(c).getString("hybrid", "") ?: ""
    fun setHybrid(c: Context, kind: String) { prefs(c).edit().putString("hybrid", kind).apply() }

    // ===== الواجهة: «الريموت الكامل» (صفحة كاملة بكل أزرار الريموت المختار) =====
    val LABELS = mapOf(
        "POWER" to "⏻", "UP" to "▲", "DOWN" to "▼", "LEFT" to "◀", "RIGHT" to "▶", "OK" to "OK",
        "BACK" to "رجوع", "EXIT" to "خروج", "MENU" to "قايمة", "HOME" to "رئيسية", "MUTE" to "🔇",
        "VOLUP" to "🔊 +", "VOLDN" to "🔉 −", "CHUP" to "CH +", "CHDN" to "CH −", "SRC" to "المصدر",
        "INFO" to "ℹ", "GUIDE" to "دليل", "FAV" to "★ مفضلة", "RADIO" to "راديو", "PGUP" to "صفحة ▲", "PGDN" to "صفحة ▼",
        "AUDIO" to "صوت", "REC" to "⏺ تسجيل", "PAUSE" to "⏸", "STOP" to "⏹", "PLAY" to "▶ تشغيل",
        "FF" to "⏩", "REW" to "⏪", "NEXT" to "⏭", "PREVIOUS" to "⏮", "LAST" to "↩ آخر", "SEL" to "OK", "ENTER" to "⏎",
        "RED" to "", "GREEN" to "", "YELLOW" to "", "BLUE" to "")

    /**
     * (v810) صفحة الريموت الكامل للريموت المختار (تلفزيون أو ساتلايت): كل الأزرار اللي في الريموت ده مترتبة (باور · أرقام · اتجاهات · صوت/قنوات · ألوان · باقي الأزرار).
     * [onBack] بيتنده لما تدوس «رجوع» ← بترجع لشكل الريموت الأساسي (واي فاي).
     */
    fun show(act: Activity, kind: String, onBack: () -> Unit) {
        val dm = act.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        if (!available(act)) {
            GAlert(act).setTitle("📡 ريموت IR").setMessage("الموبايل ده مفيهوش بلاستر أشعة تحت الحمراء، فمش هينفع نتحكم بيه. (الريموت العادي بالواي فاي شغّال زي ما هو.)").setPositiveButton("تمام", null).show()
            return
        }
        var brand = savedBrand(act, kind).ifEmpty { if (kind == "tv") brandOf(act, tvMfr(act)) else "" }
        var list: List<Remote> = emptyList()
        var idx = 0
        fun reload() {
            list = if (brand.isEmpty()) emptyList() else candidates(act, kind, brand, if (kind == "tv") tvModel(act) else "")
            val sv = savedRemote(act, kind)
            idx = list.indexOfFirst { it.name == sv }.let { if (it < 0) 0 else it }
        }
        reload()

        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(0xFF0F1830.toInt()); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        fun txt(t: String, sp: Float, col: Int = Color.WHITE, bold: Boolean = false) = TextView(act).apply {
            text = t; textSize = sp; setTextColor(col); gravity = Gravity.CENTER; if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun mkBtn(t: String, fill: Int = 0xFF1E2A44.toInt(), sp: Float = 15f, onTap: () -> Unit) = TextView(act).apply {
            text = t; textSize = sp; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0x44FFFFFF) }
            setOnClickListener { Haptic.tap(it); onTap() }
        }
        fun row(vararg v: View) = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR; for (x in v) addView(x) }

        // الشريط العلوي: رجوع للريموت الأساسي (واي فاي) + العنوان
        val backB = mkBtn("↩ رجوع للريموت", 0xFF2563EB.toInt(), 14f) { try { d.dismiss() } catch (_: Throwable) {}; onBack() }
        val titleT = txt(if (kind == "sat") "🛰 الريموت الكامل — ساتلايت" else "📺 الريموت الكامل — تلفزيون", 15f, Color.WHITE, true)
        root.addView(row(backB.also { it.layoutParams = LinearLayout.LayoutParams(dp(140), dp(42)) }, titleT.also { it.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f) }))
        val brandB = mkBtn("", 0xFF22304F.toInt(), 13f) {}
        val modelB = mkBtn("", 0xFF22304F.toInt(), 13f) {}
        val info = txt("", 12f, 0xFFB8BDC9.toInt())
        root.addView(brandB, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
        root.addView(modelB, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
        root.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_HORIZONTAL }
        val sc = android.widget.ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(body, FrameLayout.LayoutParams(-1, -2)) }
        root.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })

        fun cur(): Remote? = list.getOrNull(idx)
        fun fire(v: View, key: String) {
            val r = cur()
            if (r == null) { Notice.show(act, "اختار الماركة الأول", 1800L); return }
            Haptic.tap(v)
            if (!send(act, r, key)) Notice.show(act, "الزرار ده مش شغّال في الريموت ده", 1500L)
        }
        fun fillOf(k: String): Int = when (k) {
            "POWER" -> 0xFFC62828.toInt(); "OK" -> 0xFF1D4ED8.toInt(); "RED" -> 0xFFD32F2F.toInt(); "GREEN" -> 0xFF2E7D32.toInt()
            "YELLOW" -> 0xFFF9A825.toInt(); "BLUE" -> 0xFF1565C0.toInt(); else -> 0xFF1E2A44.toInt()
        }
        fun keyBtn(k: String): TextView {
            val lb = LABELS[k] ?: k.replace('_', ' ').lowercase()
            return mkBtn(lb, fillOf(k), if (lb.length > 6) 12f else 16f) {}.also { b -> b.setOnClickListener { fire(b, k) } }
        }
        fun cell(k: String?, r: Remote): View =
            if (k != null && r.keys.containsKey(k)) keyBtn(k).also { it.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) } }
            else View(act).apply { layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) } }
        fun rowOf(r: Remote, keys: List<String?>) { if (keys.any { it != null && r.keys.containsKey(it) }) body.addView(row(*keys.map { cell(it, r) }.toTypedArray()).also { it.layoutParams = LinearLayout.LayoutParams(-1, -2) }) }
        fun section(t: String) = body.addView(txt(t, 11f, 0xFF8FA3C7.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        fun rebuild() {
            body.removeAllViews()
            val r = cur()
            if (r == null) { body.addView(txt("اختار الماركة والريموت من فوق", 14f, 0xFFB8BDC9.toInt()), LinearLayout.LayoutParams(-1, dp(80))); return }
            val used = HashSet<String>()
            fun mark(vararg ks: String?) { for (k in ks) if (k != null) used.add(k) }
            rowOf(r, listOf("POWER", "SRC", "MUTE", "INFO")); mark("POWER", "SRC", "MUTE", "INFO")
            val digits = (1..9).map { it.toString() }
            if (digits.any { r.keys.containsKey(it) }) {
                section("الأرقام")
                for (i in 0 until 3) rowOf(r, digits.subList(i * 3, i * 3 + 3))
                rowOf(r, listOf(null, "0", null)); mark(*digits.toTypedArray(), "0")
            }
            section("التنقل")
            rowOf(r, listOf("BACK", "UP", "EXIT")); rowOf(r, listOf("LEFT", "OK", "RIGHT")); rowOf(r, listOf("HOME", "DOWN", "MENU")); mark("BACK", "UP", "EXIT", "LEFT", "OK", "RIGHT", "HOME", "DOWN", "MENU")
            if (listOf("VOLUP", "VOLDN", "CHUP", "CHDN").any { r.keys.containsKey(it) }) {
                section("الصوت والقنوات")
                rowOf(r, listOf("VOLUP", "CHUP")); rowOf(r, listOf("VOLDN", "CHDN")); mark("VOLUP", "VOLDN", "CHUP", "CHDN")
            }
            if (listOf("RED", "GREEN", "YELLOW", "BLUE").any { r.keys.containsKey(it) }) {
                section("الألوان"); rowOf(r, listOf("RED", "GREEN", "YELLOW", "BLUE")); mark("RED", "GREEN", "YELLOW", "BLUE")
            }
            val rest = r.keys.keys.filter { it !in used }
            if (rest.isNotEmpty()) {
                section("باقي الأزرار (${rest.size})")
                for (ch in rest.chunked(4)) rowOf(r, ch + List(4 - ch.size) { null })
            }
        }
        fun refresh() {
            brandB.text = "الماركة: " + (if (brand.isEmpty()) "اختار ▾" else "$brand ▾")
            val r = cur()
            modelB.text = if (r == null) "الريموت: —" else "الريموت: ${r.name.take(30)} (${idx + 1}/${list.size}) ▾"
            info.text = (if (kind == "tv" && tvModel(act).isNotEmpty()) "التلفزيون: ${tvMfr(act)} ${tvModel(act)}\n" else "") + irInfo(act) + "\nجرّب ⏻ — لو الجهاز ما استجابش جرّب «الكود اللي بعده»"
            rebuild()
        }
        brandB.setOnClickListener {
            Haptic.tap(it)
            val bs = brands(act, kind)
            GAlert(act).setTitle("الماركة").setItems(bs.toTypedArray()) { _, i -> brand = bs[i]; reload(); save(act, kind, brand, cur()?.name ?: ""); refresh() }.show()
        }
        modelB.setOnClickListener {
            Haptic.tap(it)
            if (list.isEmpty()) return@setOnClickListener
            val names = list.map { it.name }.toTypedArray()
            GAlert(act).setTitle("الريموت").setItems(names) { _, i -> idx = i; save(act, kind, brand, list[i].name); refresh() }.show()
        }
        val nextB = mkBtn("⏭ الكود اللي بعده (يجرّب ⏻)", 0xFF7C4DBB.toInt(), 14f) {
            if (list.isEmpty()) Notice.show(act, "اختار الماركة الأول", 1800L)
            else { idx = (idx + 1) % list.size; save(act, kind, brand, list[idx].name); refresh(); cur()?.let { send(act, it, "POWER") } }
        }
        root.addView(nextB, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        // (v820) خُد 5 أزرار بس من الريموت ده وطبّقهم على الريموت الأساسي (واي فاي)
        fun hyLabel() = if (hybridKind(act) == kind) "⛔ إلغاء الأزرار المأخودة (الريموت الأساسي واي فاي بس)" else "✅ خُد منه ⏻ · ▲▼◀▶ · OK · 🔇 على الريموت الأساسي"
        val hyB = mkBtn(hyLabel(), 0xFF0F7B4A.toInt(), 13f) {
            if (hybridKind(act) == kind) { setHybrid(act, ""); Notice.show(act, "اتلغت — الريموت الأساسي واي فاي بس", 2200L) }
            else if (cur() == null) { Notice.show(act, "اختار الماركة والريموت الأول", 2000L); return@mkBtn }
            else { setHybrid(act, kind); Notice.show(act, "✓ ⏻ والأسهم وOK والكتم بقوا بالـIR على الريموت الأساسي — والرئيسية فضلت رئيسية البرنامج", 3500L) }
            try { d.dismiss() } catch (_: Throwable) {}; onBack()
        }
        root.addView(hyB, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        root.addView(txt("الرئيسية مش بتتاخد: بتفضل رئيسية البرنامج، لأن رئيسية التلفزيون بتفتح جوجل تي في.", 11f, 0xFF8FA3C7.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        refresh()

        d.setContentView(root)
        d.setCancelable(true)
        d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        d.show()
    }
}
