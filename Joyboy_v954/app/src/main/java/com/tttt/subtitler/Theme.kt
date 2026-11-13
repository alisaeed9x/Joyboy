package com.tttt.subtitler

import android.content.Context
import android.content.res.Configuration
import com.newtube.mobile.ui.common.ThemeMode

/**
 * (v907) ثيم واحد بس — مصدره MeeTube.
 * اتمسح كل ثيمات Joyboy (يوتيوب زجاجي/فاتح/أموليد · MX · iOS · ويندوز · XP · سيبيا · استوديو السمات المخصّصة…).
 * الألوان بتتقرا من موارد MeeTube نفسها (colors_mobile.xml: values = فاتح · values-night = داكن) والجانب (فاتح/داكن/نظام)
 * من إعداد MeeTube (ThemeMode). تغيير الثيم يتم من MeeTube بس، وJoyboy بيتبعه.
 */
class Theme(val id: String, val name: String, val bg: Int, val surface: Int, val card: Int, val border: Int,
            val primary: Int, val accent: Int, val text: Int, val muted: Int, val danger: Int, val success: Int) {
    val isLight: Boolean get() = lum(bg) > 0.5
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
        val bg = MeeRes.color("mobile_color_background", 0xFF000000.toInt(), 0xFFFFFFFF.toInt()) or (0xFF shl 24)
        val surface = over(MeeRes.color("mobile_color_surface", 0xFF1C1C21.toInt(), 0xFFFFFFFF.toInt()), bg)
        val card = over(MeeRes.color("mobile_color_card", 0x17FFFFFF, 0xFFF2F2F2.toInt()), bg)
        val border = over(MeeRes.color("mobile_color_divider", 0x1FFFFFFF, 0x1A000000), bg)
        val primary = MeeRes.color("meetube_accent", 0xFFF1F1F1.toInt(), 0xFF0F0F0F.toInt()) or (0xFF shl 24)
        val text = MeeRes.color("mobile_color_on_surface", 0xFFF5F5F7.toInt(), 0xFF0F0F0F.toInt()) or (0xFF shl 24)
        val muted = over(MeeRes.color("mobile_color_on_surface_secondary", 0x99FFFFFF.toInt(), 0xFF606060.toInt()), bg)
        val t = Theme(if (night) "mt_dark" else "mt_light", if (night) "MeeTube داكن" else "MeeTube فاتح",
            bg, surface, card, border, primary, muted, text, muted,
            if (night) 0xFF8A8A8A.toInt() else 0xFF3C3C3C.toInt(), 0xFF2BA640.toInt())
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
