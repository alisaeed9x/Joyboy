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
    val progress: Int get() = if (isLight) 0xFF3E8E88.toInt() else 0xFF6CC4BC.toInt()
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

object Themes {
    @Volatile private var cached: Theme? = null
    @Volatile private var cachedNight = -1

    /** تركيب لون شفاف فوق خلفية → لون معتم (كارت/حد MeeTube شفافين، وJoyboy محتاج لون صلب) */
    private fun over(c: Int, bg: Int): Int {
        val a = android.graphics.Color.alpha(c) / 255f
        fun m(x: Int, y: Int) = (x * a + y * (1f - a)).toInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(m(android.graphics.Color.red(c), android.graphics.Color.red(bg)),
            m(android.graphics.Color.green(c), android.graphics.Color.green(bg)), m(android.graphics.Color.blue(c), android.graphics.Color.blue(bg)))
    }

    /** الثيم الحالي = ألوان MeeTube للجانب المختار في إعداداته */
    fun current(): Theme {
        val night = MeeRes.night()
        val n = if (night) 1 else 0
        cached?.let { if (cachedNight == n) return it }
        // (v1205) هوية «Vanilla Studio»: فاتح = Vanilla/Latte/Mocha/Espresso · داكن = AMOLED أسود حقيقي بسطوح رمادية محايدة (v1216، بدل البني) + لمسة Latte.
        // الجانب (فاتح/داكن) لسه من إعداد MeeTube؛ الألوان بس هي اللي بقت دافية.
        val c = { h: Long -> (0xFF000000L or h).toInt() }
        val t = if (night) Theme("vanilla_dark", "Vanilla داكن",
            c(0x000000), c(0x0E0E0E), c(0x161616), c(0x2A2A2A), c(0xE6D8C5), c(0xBDBDBD), c(0xF5F0E7), c(0xA8A8A8), c(0xD9806E), c(0x6FB27C))
        else Theme("vanilla_light", "Vanilla فاتح",
            c(0xF5F0E7), c(0xFBF8F2), c(0xFFFDF9), c(0xE6D8C5), c(0x8B6B50), c(0x6F6052), c(0x29241F), c(0x6F6052), c(0xB3503F), c(0x3E8E55))
        cached = t; cachedNight = n
        return t
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
