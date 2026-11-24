package com.tttt.subtitler

import android.content.Context
import android.content.res.Configuration
import com.tttt.subtitler.mtui.ThemeMode

/**
 * (v907) ثيم واحد بس — مصدره MeeTube.
 * اتمسح كل ثيمات Joyboy (يوتيوب زجاجي/فاتح/أموليد · MX · iOS · ويندوز · XP · سيبيا · استوديو السمات المخصّصة…).
 * الألوان بتتقرا من موارد MeeTube نفسها (colors_mobile.xml: values = فاتح · values-night = داكن) والجانب (فاتح/داكن/نظام)
 * من إعداد MeeTube (ThemeMode). تغيير الثيم يتم من MeeTube بس، وJoyboy بيتبعه.
 */
class Theme(val id: String, val name: String, val bg: Int, val surface: Int, val card: Int, val border: Int,
            val primary: Int, val accent: Int, val text: Int, val muted: Int, val danger: Int, val success: Int) {
    val isLight: Boolean get() = lum(bg) > 0.5
    /** (v1208) فيروزي هادي لحالات التقدّم/المشاهدة (لمسة بس، مش لون مسيطر) */
    val progress: Int get() = if (isLight) 0xFF3E8E88.toInt() else if (Design.isMaterial() || Design.hasAccent()) primary else if (Design.isNew()) 0xFF8B7CFF.toInt() else 0xFF6CC4BC.toInt()
    /** (v913) لون النص/الأيقونة فوق primary — accent MeeTube أبيض في الداكن وأسود في الفاتح فالنص لازم يتعكس */
    val onPrimary: Int get() = if (lum(primary) > 0.5) 0xFF0F0F0F.toInt() else 0xFFFFFFFF.toInt()
    companion object {
        fun lum(c: Int): Double { val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; return (0.299*r + 0.587*g + 0.114*b) / 255.0 }
        fun parse(s: String): Int { var h = s.trim().removePrefix("#"); if (h.length == 3) h = h.map { "$it$it" }.joinToString(""); return (0xFF000000L or h.take(6).toLong(16)).toInt() }
    }
}

/** (v907) قراءة موارد MeeTube بالاسم (getIdentifier) بسياق مُجبَر على جانب فاتح/داكن — من غير ما MeeTube يتشغّل ومن غير اعتماد وقت-الكومبايل على R. */
object MeeRes {
    @Volatile private var cachedNight = -1
    @Volatile private var cachedCtx: Context? = null

    private fun appCtx(): Context? = MtYt.app?.applicationContext

    /** الجانب الحالي: إعداد MeeTube (فاتح/داكن) أو النظام لو «نظام» */
    fun night(app: Context): Boolean = try {
        when (ThemeMode.peek(app)) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    } catch (_: Throwable) { true }

    fun night(): Boolean { val a = appCtx() ?: return true; return night(a) }

    private fun ctx(app: Context): Context {
        val n = if (night(app)) 1 else 0
        cachedCtx?.let { if (cachedNight == n) return it }
        val cfg = Configuration(app.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (n == 1) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        val c = try { app.createConfigurationContext(cfg) } catch (_: Throwable) { app }
        cachedCtx = c; cachedNight = n
        return c
    }

    /** (v908) drawable MeeTube الأصلي بالاسم (bg_glass_card · bg_glass_pill · bg_glass_sheet · bg_glass_circle…) — نسخة جديدة معدّلة، أو null */
    fun drawable(name: String): android.graphics.drawable.Drawable? {
        val app = appCtx() ?: return null
        return try {
            val c = ctx(app)
            val id = c.resources.getIdentifier(name, "drawable", app.packageName)
            if (id != 0) c.getDrawable(id)?.mutate() else null
        } catch (_: Throwable) { null }
    }

    /** لون MeeTube بالاسم (meetube_glass_fill · mobile_color_card …) — fallbackDark/fallbackLight لو المورد مش موجود */
    fun color(name: String, fallbackDark: Int, fallbackLight: Int): Int {
        val app = appCtx() ?: return fallbackDark
        return try {
            val c = ctx(app)
            val id = c.resources.getIdentifier(name, "color", app.packageName)
            if (id != 0) c.getColor(id) else if (night(app)) fallbackDark else fallbackLight
        } catch (_: Throwable) { if (night(app)) fallbackDark else fallbackLight }
    }
}

/** (v1219) مفتاح الشكل: جديد أو القديم (الافتراضي: القديم) — القديم بيرجّع ألوان v1217 بالظبط ومفيش أي كود جديد بيشتغل فيه */
object Design {
    const val CLASSIC = "classic"; const val VANILLA = "vanilla"; const val MATERIAL = "material"
    val order = listOf(CLASSIC, VANILLA, MATERIAL)
    fun label(d: String): String = when (d) { CLASSIC -> "كلاسيك"; VANILLA -> "فانيلا"; else -> "ماتيريال" }
    /** (v1248) التصميم الحالي: كلاسيك (القديم · الافتراضي) · فانيلا (الجديد) · ماتيريال (Material You). ui_new القديم بيتحوّل لفانيلا */
    fun cur(): String = try { val s = Cfg.str("ui_design", ""); if (s == CLASSIC || s == VANILLA || s == MATERIAL) s else if (Cfg.bool("ui_new", false)) VANILLA else CLASSIC } catch (_: Throwable) { CLASSIC }
    /** هيكل الواجهة الجديدة (كارت كمّل المشاهدة، شاشة التحديث…) شغّال في فانيلا وماتيريال؛ كلاسيك متلمسش */
    fun isNew(): Boolean = cur() != CLASSIC
    fun isMaterial(): Boolean = cur() == MATERIAL
    /** كل تصميم له وضع فاتح/داكن/نظام خاص بيه: وقت التبديل بنحفظ وضع القديم ونرجّع وضع الجديد (لو اتحفظ قبل كده) */
    fun set(ctx: android.content.Context, d: String) {
        val old = cur(); if (old == d) return
        try { Cfg.put("mode_" + old, com.tttt.subtitler.mtui.ThemeMode.peek(ctx).toString()) } catch (_: Throwable) {}
        Cfg.put("ui_design", d); Cfg.put("ui_new", if (d == CLASSIC) "0" else "1")
        try { Cfg.str("mode_" + d, "").toIntOrNull()?.let { com.tttt.subtitler.mtui.ThemeMode.set(ctx, it) } } catch (_: Throwable) {}
    }
    fun next(): String = order[(order.indexOf(cur()) + 1) % order.size]
    /** (v1250) ألوان مميّزة (بدون أخضر): اسم · فاتح · داكن */
    val accents: List<Triple<String, Int, Int>> = listOf(
        Triple("بنفسجي", 0xFF6B4FD8.toInt(), 0xFF9D8CFF.toInt()), Triple("أزرق", 0xFF2F6FDB.toInt(), 0xFF7FB0FF.toInt()),
        Triple("وردي", 0xFFC2436F.toInt(), 0xFFFF8FB1.toInt()), Triple("برتقالي", 0xFFD9622B.toInt(), 0xFFFFA066.toInt()),
        Triple("أحمر", 0xFFC0392B.toInt(), 0xFFFF7A6B.toInt()), Triple("لاتيه", 0xFF8B6B50.toInt(), 0xFFE6D8C5.toInt()))
    /** 0 = لون التصميم الافتراضي · 1.. = من accents (كلاسيك مالوش لون مختار) */
    fun accIdx(): Int = try { if (!isNew()) 0 else (Cfg.str("acc_" + cur(), "").toIntOrNull() ?: 0).coerceIn(0, accents.size) } catch (_: Throwable) { 0 }
    fun hasAccent(): Boolean = accIdx() > 0
    /** (v1250) صفحة التصميم: التصاميم التلاتة + فاتح/داكن/تلقائي + لون مميّز للتصميم المختار. التغييرات بتتحفظ وبتتطبّق بزرار «تطبيق» أو بقفل الصفحة */
    fun pick(act: android.app.Activity) {
        val ui = Ui(act, Themes.current()); val th = ui.th
        val root = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
        var dirty = false
        fun pill(t: String, on: Boolean, col: Int, click: () -> Unit): android.view.View = ui.text(t, 13f, if (on) 0xFFFFFFFF.toInt() else th.text, true).apply {
            gravity = android.view.Gravity.CENTER; setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9))
            background = ui.box(if (on) col else th.card, if (on) col else th.border, 14, 1)
            setOnClickListener { click() }
        }
        fun row(vararg v: android.view.View): android.widget.LinearLayout = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            for (x in v) addView(x, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        }
        fun build() {
            root.removeAllViews()
            val pri = th.primary
            root.addView(ui.text("التصميم", 12f, th.muted, true))
            root.addView(row(*order.map { d -> pill(label(d), cur() == d, pri) { set(act, d); dirty = true; build() } }.toTypedArray()))
            root.addView(ui.text("الوضع", 12f, th.muted, true).apply { setPadding(0, ui.dp(10), 0, 0) })
            val m = com.tttt.subtitler.mtui.ThemeMode.peek(act)
            root.addView(row(
                pill("☀️ فاتح", m == com.tttt.subtitler.mtui.ThemeMode.LIGHT, pri) { com.tttt.subtitler.mtui.ThemeMode.set(act, com.tttt.subtitler.mtui.ThemeMode.LIGHT); dirty = true; build() },
                pill("🌙 داكن", m == com.tttt.subtitler.mtui.ThemeMode.DARK, pri) { com.tttt.subtitler.mtui.ThemeMode.set(act, com.tttt.subtitler.mtui.ThemeMode.DARK); dirty = true; build() },
                pill("⚙ تلقائي", m != com.tttt.subtitler.mtui.ThemeMode.LIGHT && m != com.tttt.subtitler.mtui.ThemeMode.DARK, pri) { com.tttt.subtitler.mtui.ThemeMode.set(act, com.tttt.subtitler.mtui.ThemeMode.SYSTEM); dirty = true; build() }))
            if (isNew()) {
                root.addView(ui.text("اللون المميّز لـ" + label(cur()), 12f, th.muted, true).apply { setPadding(0, ui.dp(10), 0, 0) })
                val night = MeeRes.night(act)
                val cells = ArrayList<android.view.View>()
                cells.add(pill("الأصلي", accIdx() == 0, pri) { Cfg.put("acc_" + cur(), "0"); dirty = true; build() })
                accents.forEachIndexed { k, a ->
                    val c = if (night) a.third else a.second
                    cells.add(pill(a.first, accIdx() == k + 1, c) { Cfg.put("acc_" + cur(), (k + 1).toString()); dirty = true; build() })
                }
                cells.chunked(3).forEach { ch -> root.addView(row(*(ch + List(3 - ch.size) { android.view.View(act) }).toTypedArray())) }
            }
        }
        build()
        GAlert(act).setTitle("🎨 تصميم التطبيق").setView(android.widget.ScrollView(act).apply { addView(root) })
            .setPositiveButton("تطبيق") { _, _ -> try { act.recreate() } catch (e: Throwable) { LogStore.err("Design:apply", e) } }
            .setNegativeButton("إغلاق") { _, _ -> if (dirty) try { act.recreate() } catch (_: Throwable) {} }
            .setOnCancelListener { if (dirty) try { act.recreate() } catch (_: Throwable) {} }.show()
    }
    private fun al(c: Int, a: Int): Int = (a shl 24) or (c and 0x00FFFFFF)
    /** اللون المميّز الأول (فانيلا: نعناعي · ماتيريال: primary بتاع الثيم) بشفافية a (0..255) */
    fun mint(a: Int): Int = if (isMaterial() || hasAccent()) al(Themes.current().primary, a) else al(0x8B7CFF, a)
    fun violet(a: Int): Int = if (isMaterial() || hasAccent()) al(Themes.current().accent, a) else al(0xE6D8C5, a)
}

object Themes {
    @Volatile private var cached: Theme? = null
    @Volatile private var cachedNight = -1
    @Volatile private var cachedNew = ""

    /** تركيب لون شفاف فوق خلفية → لون معتم (كارت/حد MeeTube شفافين، وJoyboy محتاج لون صلب) */
    private fun over(c: Int, bg: Int): Int {
        val a = android.graphics.Color.alpha(c) / 255f
        fun m(x: Int, y: Int) = (x * a + y * (1f - a)).toInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(m(android.graphics.Color.red(c), android.graphics.Color.red(bg)),
            m(android.graphics.Color.green(c), android.graphics.Color.green(bg)), m(android.graphics.Color.blue(c), android.graphics.Color.blue(bg)))
    }

    /** (v1248) ماتيريال: ألوان Material You (M3). أندرويد 12+ بياخد الـaccent/السطوح من خلفية الجهاز (ألوان النظام)، وغير كده الألوان الأساسية الثابتة لـM3 (بنفسجي) */
    private fun materialTheme(night: Boolean): Theme {
        val c = { h: Long -> (0xFF000000L or h).toInt() }
        var pri = if (night) c(0xD0BCFF) else c(0x6750A4); var acc = if (night) c(0xCCC2DC) else c(0x625B71)
        var bg = if (night) c(0x141218) else c(0xFEF7FF); var sur = if (night) c(0x1D1B20) else c(0xF7F2FA); var card = if (night) c(0x211F26) else c(0xECE6F0)
        var bor = if (night) c(0x49454F) else c(0xCAC4D0); var txt = if (night) c(0xE6E0E9) else c(0x1D1B20); var mut = if (night) c(0xCAC4D0) else c(0x49454F)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val x = MtYt.app?.applicationContext
                if (x != null) {
                    pri = x.getColor(if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
                    acc = x.getColor(if (night) android.R.color.system_accent2_200 else android.R.color.system_accent2_600)
                    bg = x.getColor(if (night) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_10)
                    sur = x.getColor(if (night) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_50)
                    card = x.getColor(if (night) android.R.color.system_neutral2_800 else android.R.color.system_neutral2_100)
                    bor = x.getColor(if (night) android.R.color.system_neutral2_700 else android.R.color.system_neutral2_200)
                    txt = x.getColor(if (night) android.R.color.system_neutral1_100 else android.R.color.system_neutral1_900)
                    mut = x.getColor(if (night) android.R.color.system_neutral2_200 else android.R.color.system_neutral2_700)
                }
            }
        } catch (_: Throwable) {}
        return Theme(if (night) "material_dark" else "material_light", if (night) "ماتيريال داكن" else "ماتيريال فاتح",
            bg, sur, card, bor, pri, acc, txt, mut, if (night) c(0xF2B8B5) else c(0xB3261E), if (night) c(0xA8DAB5) else c(0x386A20))
    }

    /** الثيم الحالي = ألوان MeeTube للجانب المختار في إعداداته */
    fun current(): Theme {
        val night = MeeRes.night()
        val n = if (night) 1 else 0
        val dsg = Design.cur(); val isNew = dsg != Design.CLASSIC
        cached?.let { if (cachedNight == n && cachedNew == dsg + Design.accIdx()) return it }
        // (v1205) هوية «Vanilla Studio»: فاتح = Vanilla/Latte/Mocha/Espresso · داكن = AMOLED أسود حقيقي بسطوح رمادية محايدة (v1216، بدل البني) + لمسة Latte.
        // الجانب (فاتح/داكن) لسه من إعداد MeeTube؛ الألوان بس هي اللي بقت دافية.
        val c = { h: Long -> (0xFF000000L or h).toInt() }
        val t = if (dsg == Design.MATERIAL) materialTheme(night) else if (night) Theme("vanilla_dark", "Vanilla داكن",
            c(0x000000), c(0x0E0E0E), c(0x161616), c(0x2A2A2A),
            if (isNew) c(0x8B7CFF) else c(0xE6D8C5), if (isNew) c(0xE6D8C5) else c(0xBDBDBD), if (isNew) c(0xFFFFFF) else c(0xF5F0E7), c(0xA8A8A8), c(0xD9806E), c(0x6FB27C))
        else Theme("vanilla_light", "Vanilla فاتح",
            c(0xF5F0E7), c(0xFBF8F2), c(0xFFFDF9), c(0xE6D8C5), c(0x8B6B50), c(0x6F6052), c(0x29241F), c(0x6F6052), c(0xB3503F), c(0x3E8E55))
        val ai = Design.accIdx()
        val t2 = if (ai > 0) {
            val a = Design.accents[ai - 1]; val pri = if (night) a.third else a.second
            val hsv = FloatArray(3); android.graphics.Color.colorToHSV(pri, hsv); hsv[0] = (hsv[0] + 25f) % 360f
            Theme(t.id, t.name, t.bg, t.surface, t.card, t.border, pri, android.graphics.Color.HSVToColor(hsv), t.text, t.muted, t.danger, t.success)
        } else t
        cached = t2; cachedNight = n; cachedNew = dsg + ai
        return t2
    }

    /** (v907) أي id بيرجّع ثيم MeeTube الحالي — الثيمات المتعددة اتمسحت (الدالة باقية عشان الاستدعاءات القديمة تفضل شغّالة) */
    @Suppress("UNUSED_PARAMETER")
    fun byId(id: String?): Theme = current()

    /** (v907) ثيم واحد بس */
    val all: List<Theme> get() = listOf(current())

    /** (v907) مفيش ثيم مقابل — سويتش فاتح/داكن بقى من إعداد MeeTube */
    @Suppress("UNUSED_PARAMETER")
    fun partner(id: String): String = current().id
}
