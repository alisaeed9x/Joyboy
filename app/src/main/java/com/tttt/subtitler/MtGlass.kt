package com.tttt.subtitler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * (v893) «زجاج MeeTube وحركته» لواجهة الموبايل/التابلت — مصدر واحد للألوان والأشكال والحركة.
 * منقول من مودول :mobile (colors_mobile.xml · bg_glass_*.xml · GlassDrawable · Motion.java · press_3d.xml · player_open/close).
 * التلفزيون ما بيتلمسش: كل فرع جديد في Glass/Ui/Motion/NavAnim/BottomNav متحاوط بـ MtGlass.on() = موبايل/تابلت بس.
 */
object MtGlass {
    fun on(): Boolean = try { DeviceUi.phoneLike() } catch (_: Throwable) { false }

    // ===== ألوان الزجاج — بتتقرا من موارد MeeTube نفسها (colors_mobile.xml: values = فاتح · values-night = داكن) =====
    // (v907) كانت أرقام منسوخة؛ دلوقتي المصدر مورد MeeTube (MeeRes) والرقم اللي هنا مجرد fallback لو المورد مش لاقيه.
    /** (v1281) شريط «الشفافية والبلر» (glass_level 0..100، الافتراضي 50 = الشكل الحالي): فوق 50 أشفّ وأبلر، تحت 50 أعتم وأخف بلر */
    @JvmStatic fun level(): Int = try { Cfg.int("glass_level", 50).coerceIn(0, 100) } catch (_: Throwable) { 50 }
    @JvmStatic fun blurScale(): Float = 0.4f + level() / 50f * 0.6f   // 0.4 .. 1.6
    @JvmStatic fun ak(c: Int): Int { val k = (150 - level()) / 100f; val a = (((c ushr 24) and 0xFF) * k).toInt().coerceIn(0, 255); return (a shl 24) or (c and 0x00FFFFFF) }
    fun fill(th: Theme): Int = ak(MeeRes.color("meetube_glass_fill", 0x17FFFFFF, 0x0D000000))
    fun stroke(th: Theme): Int = ak(MeeRes.color("meetube_glass_stroke", 0x24FFFFFF, 0x1A000000))
    fun rim(th: Theme): Int = ak(MeeRes.color("meetube_glass_rim", 0x3DFFFFFF, 0x1F000000))
    fun sheen(th: Theme): Int = ak(MeeRes.color("meetube_glass_sheen", 0x1AFFFFFF, 0x80FFFFFF.toInt()))
    /** (v1207) التحديد (تبويب الشريط السفلي · chip مختار) بصبغة لون التمييز الدافي (Mocha/Latte) بدل الرمادي المحايد */
    fun selected(th: Theme): Int = (0x2B shl 24) or (th.primary and 0x00FFFFFF)
    /** (v1206) سطح الشيت/الدايلوج بلون سطح الثيم الدافي (كان رمادي مزرق من MeeTube وبيتنافر مع Espresso/Vanilla) */
    fun sheet(th: Theme): Int = ak(((if (th.isLight) 0xEB else 0xE6) shl 24) or (th.surface and 0x00FFFFFF))
    fun navInactive(th: Theme): Int = MeeRes.color("meetube_nav_inactive", 0xADFFFFFF.toInt(), 0x99000000.toInt())
    /** شريط سفلي: لون الصفحة مرفوع شوية (زجاج من غير حاجة تتبلّر تحته: «9% أبيض عشان يبان على الصفحة المسطّحة») */
    fun barSolid(th: Theme): Int = Glass.blend(th.bg, if (th.isLight) Color.BLACK else Color.WHITE, 0.07f)

    // ===== (v897) زجاج حقيقي شفاف فوق الخلفية المتحركة (MtAurora) — الكروت مابقتش لون صلب =====
    /** كارت زجاجي: تدرّج شفاف (لمعة من فوق) + حافة مضيئة شعرية. بيتحط مكان كارت th.card الصلب */
    fun glassCard(th: Theme, radiusPx: Float, d: Float): GradientDrawable {
        mt("bg_glass_card", radiusPx)?.let { g ->   // (v909) bg_glass_card بتاع MeeTube نفسه (fill + stroke شعري من colors_mobile)
            // (v1204) لمعة ناعمة من فوق لتحت: الكارت أفتح شوية عند الحافة العليا وبيهدى لتحت — عمق من غير ضوضاء
            try {
                val f = fill(th); val a = Color.alpha(f); val rgb = f and 0x00FFFFFF
                val top = ((minOf(255, (a * (if (th.isLight) 1.0f else 2.1f)).toInt())) shl 24) or rgb
                val bot = ((minOf(255, (a * (if (th.isLight) 0.55f else 0.65f)).toInt())) shl 24) or rgb
                g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM)
                g.setColors(intArrayOf(top, bot))
            } catch (_: Throwable) {}
            return g
        }
        val amoled = th.bg == 0xFF000000.toInt()
        val top = if (th.isLight) 0xB8FFFFFF.toInt() else if (amoled) 0x20FFFFFF else 0x2BFFFFFF
        val bot = if (th.isLight) 0x7AFFFFFF else if (amoled) 0x0DFFFFFF else 0x14FFFFFF
        val edge = if (th.isLight) 0x22000000 else 0x40FFFFFF
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bot)).apply {
            cornerRadius = radiusPx; setStroke(max(1, (d * 0.75f).roundToInt()), edge)
        }
    }

    /** «بئر» زجاجي: للحقول/الأسطح الداخلية (th.surface) — أغمق من الكارت شوية عشان النص يبان */
    fun glassWell(th: Theme, radiusPx: Float, d: Float): GradientDrawable {
        mt("bg_glass_pill", radiusPx)?.let { return it }   // (v909) bg_glass_pill بتاع MeeTube نفسه
        val fill = if (th.isLight) 0x1F000000 else 0x38000000
        val edge = if (th.isLight) 0x26000000 else 0x2EFFFFFF
        return GradientDrawable().apply { setColor(fill); cornerRadius = radiusPx; setStroke(max(1, (d * 0.75f).roundToInt()), edge) }
    }


    /** (v901) كبسولة/دوك عايم زجاجي داكن (نص أبيض فوقه): سطح شبه شفاف + لمعة + حافة مضيئة (أو لون تمييز لما accent != 0) */
    fun floatPill(radiusPx: Float, density: Float, accent: Int = 0): Drawable {
        // (v909) زجاج وحافة رسائل MeeTube (meetube_toast_glass / meetube_toast_rim) — نفس MobileSnackbar
        val base = GradientDrawable().apply { setColor(MeeRes.color("meetube_toast_glass", 0xC01A1A1A.toInt(), 0xC01A1A1A.toInt())); cornerRadius = radiusPx }   // (v1206) توست/كبسولة عايمة بلون Espresso دافي
        val top = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x1AFFFFFF, 0x00FFFFFF)).apply { cornerRadius = radiusPx }
        val edge = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = radiusPx; setStroke(max(1, density.roundToInt()), if (accent != 0) accent else MeeRes.color("meetube_toast_rim", 0x3DFFFFFF, 0x3DFFFFFF)) }
        return LayerDrawable(arrayOf<Drawable>(base, top, edge))
    }

    /** تعبئة مسطّحة شبه شفافة لأي مساحة (صف · لوحة) من غير استدارة */
    fun flatFill(th: Theme): Int = if (th.isLight) 0x66FFFFFF else 0x14FFFFFF

    /** استدارة MeeTube: الكروت 22 · الشيتس 28 · الأزرار/الكبسولات 23 — بنكبّر استدارة Joyboy بـ1.3 والدواير (≥40) زي ما هي */
    fun rad(r: Int): Int = if (r <= 0 || r >= 40) r else if (r < 8) (r * 1.3f).roundToInt() else (r * 1.5f).coerceIn(16f, 28f).roundToInt()   // (v1206) استدارة 16–28 للكروت والحقول والشيتس
    fun radPx(px: Float, density: Float): Float { val r = px / density; return if (r <= 0f || r >= 40f) px else if (r < 8f) px * 1.3f else (r * 1.5f).coerceIn(16f, 28f) * density }
    fun hair(density: Float): Int = max(1, (density * 0.5f).roundToInt())

    // ===== أشكال =====
    /** كارت/خلفية زجاجية بخط شعري (bg_glass_card / bg_glass_pill). نفس نوع الإرجاع GradientDrawable عشان الكود القديم اللي بيعدّل عليه يفضل شغّال */
    fun shapePx(fill: Int, stroke: Int, radiusPx: Float, strokePx: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = radiusPx
        if (Color.alpha(stroke) != 0 && strokePx > 0) setStroke(strokePx, stroke)
    }

    fun shape(th: Theme, fill: Int, stroke: Int, radiusPx: Float, density: Float, swPx: Int): GradientDrawable =
        shapePx(fill, if (stroke == th.border) stroke(th) else stroke, radiusPx, if (stroke == th.border) hair(density) else max(hair(density), swPx))

    /** زرار أساسي: لون التمييز بلمعة خفيفة من فوق (زي sheen الزجاج) */
    fun primary(th: Theme, radiusPx: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,   // (v1204) تدرّج رأسي هادي (لمعة من فوق) + حافة شعرية بدل المصمت المسطّح
            if (Theme.lum(th.primary) > 0.5) intArrayOf(th.primary, Glass.blend(th.primary, Color.BLACK, 0.14f))
            else intArrayOf(Glass.blend(th.primary, Color.WHITE, 0.18f), th.primary)
        ).apply { cornerRadius = radiusPx; setStroke(1, if (Theme.lum(th.primary) > 0.5) 0x33FFFFFF else 0x26FFFFFF) }

    /** كبسولة زجاجية (زرار ثانوي) */
    /** (v908) drawable MeeTube الأصلي (res/drawable/<name>.xml) بنصف قطر Joyboy — لو مش لاقيه يرجع null */
    private fun mt(name: String, radiusPx: Float): GradientDrawable? = (MeeRes.drawable(name) as? GradientDrawable)?.apply { cornerRadius = radiusPx }

    fun pill(th: Theme, fill: Int, radiusPx: Float, density: Float): GradientDrawable =
        (if (fill == this.fill(th)) mt("bg_glass_pill", radiusPx) else null) ?: shapePx(fill, stroke(th), radiusPx, hair(density))   // (v908) bg_glass_pill بتاع MeeTube نفسه

    /** chip: المختار = زجاج مضيء + خط لون التمييز · غير المختار = زجاج هادي */
    fun chip(th: Theme, on: Boolean, radiusPx: Float, density: Float): GradientDrawable =
        if (on) shapePx(selected(th), th.primary, radiusPx, max(1, density.roundToInt())) else (mt("bg_glass_pill", radiusPx) ?: shapePx(fill(th), stroke(th), radiusPx, hair(density)))

    /** شيت/دايلوج: سطح شبه شفاف فوق بلور النافذة + لمعة من فوق + حافة مضيئة (bg_glass_sheet + sheen + rim) */
    fun sheetBg(th: Theme, radiusPx: Float, density: Float): Drawable {
        val base = mt("bg_glass_sheet", radiusPx) ?: GradientDrawable().apply { setColor(sheet(th)); cornerRadius = radiusPx }   // (v909) bg_glass_sheet الأصلي
        val top = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sheen(th), 0x00FFFFFF)).apply { cornerRadius = radiusPx }
        val edge = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = radiusPx; setStroke(max(1, density.roundToInt()), rim(th)) }
        return LayerDrawable(arrayOf<Drawable>(base, top, edge))
    }

    // ===== الحركة (Motion.java): منحنيات Material 3 =====
    // (v908) منحنيات MeeTube نفسها (Motion.java) — مش نسخة
    val EMPHASIZED_DECELERATE: Interpolator = com.tttt.subtitler.mtui.Motion.EMPHASIZED_DECELERATE
    val EMPHASIZED_ACCELERATE: Interpolator = com.tttt.subtitler.mtui.Motion.EMPHASIZED_ACCELERATE
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** (v1203) «سريع ثم بطيء لوقت أطول» (expo-out): للدخول والاستقرار من غير أي ارتداد أو تغيير اتجاه */
    val EXPO_OUT: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    val EMPHASIZED: Interpolator by lazy {
        val p = Path()
        p.moveTo(0f, 0f)
        p.cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        p.cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        PathInterpolator(p)
    }
    const val ENTER_MS = 300L      // سطح داخل (شيت بينزلق لفوق)
    const val EXIT_MS = 200L       // سطح خارج
    const val FADE_OUT_MS = 90L
    const val FROST_MS = 320L      // صفحة بتطلع من زجاج مثلّج (تبديل تبويب)
    const val POP_MS = 260L
    private const val FROST_BLUR_DP = 14f

    private fun dp(v: View, n: Float): Float = n * v.resources.displayMetrics.density

    /** «الدفعة»: بيصغّر لـ in وبيعدّي لـ over وبيستقر (لايك/اختيار/سويتش) */
    fun pop(v: View?, inS: Float = 0.78f, over: Float = 1.12f) {
        // (v1203) «استقرار» بدل «دفعة»: من حجم أصغر شوية لـ1 في اتجاه واحد وبمنحنى expo-out — من غير تخطّي (1.12) ولا رجوع. over متساب للتوافق.
        if (v == null) return
        v.animate().cancel()
        val from = if (inS >= 0.95f) 0.985f else 0.9f
        v.scaleX = from; v.scaleY = from
        v.animate().setStartDelay(0L).scaleX(1f).scaleY(1f).setDuration(Skins.dur(POP_MS + 100L)).setInterpolator(EXPO_OUT).start()
    }

    private val frosting = WeakHashMap<View, Animator>()

    /**
     * (frostIn) الصفحة بتظهر وهي طالعة من زجاج مثلّج: فيد + بلور بيصفى على الـGPU (أندرويد 12+؛ الأقدم فيد بس).
     * البلور بيتشال في الآخر فمفيش تكلفة بعد كده. dx = انزلاقة أفقية خفيفة (اتجاه التبويب).
     */
    fun frostIn(v: View, dx: Float = 0f) {
        if (dx == 0f) { com.tttt.subtitler.mtui.Motion.frostIn(v); return }   // (v909) Motion.frostIn الأصلي (فيد + بلور بيصفى)
        v.animate().cancel()
        frosting.remove(v)?.cancel()
        v.alpha = 0f; v.translationX = dx
        v.animate().setStartDelay(0L).alpha(1f).translationX(0f).setDuration(Skins.dur(FROST_MS)).setInterpolator(EMPHASIZED_DECELERATE).withLayer().start()
        if (Build.VERSION.SDK_INT < 31) return
        val radius = FROST_BLUR_DP * v.resources.displayMetrics.density
        val frost = ValueAnimator.ofFloat(radius, 0f)
        frost.duration = Skins.dur(FROST_MS); frost.interpolator = EMPHASIZED_DECELERATE
        frost.addUpdateListener { a ->
            val r = a.animatedValue as Float
            try { v.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)) } catch (_: Throwable) {}
        }
        frost.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {   // بعد cancel() كمان
                try { v.setRenderEffect(null) } catch (_: Throwable) {}
                if (frosting[v] === animation) frosting.remove(v)
            }
        })
        frosting[v] = frost
        frost.start()
    }

    // ===== ضغطة «press_3d»: بتغوص وهي مضغوطة وبترجع بنطّة فوق مكانها =====
    fun press(v: View) {
        // (v909) press_3d.xml بتاع MeeTube نفسه (stateListAnimator)؛ لو المورد مش لاقيه نرجع للنسخة اليدوية
        try {
            val app = v.context.applicationContext
            val id = app.resources.getIdentifier("press_3d", "animator", app.packageName)
            if (id != 0) { v.stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(v.context, id); return }
        } catch (_: Throwable) {}
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val small = view.width in 1..(dp(view, 72f).toInt()) && view.height in 1..(dp(view, 72f).toInt())
                    val s = if (small) 0.84f else 0.965f
                    view.animate().setStartDelay(0L).scaleX(s).scaleY(s).translationY(dp(view, if (small) 2f else 1f)).alpha(0.85f)
                        .setDuration(Skins.dur(90L)).setInterpolator(DecelerateInterpolator(1.2f)).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().setStartDelay(0L).scaleX(1f).scaleY(1f).translationY(0f).alpha(1f)
                        .setDuration(Skins.dur(360L)).setInterpolator(EXPO_OUT).start()
                }
            }
            false   // مابيعترضش الـclick
        }
    }

    // ===== (v894) المرحلة 2: شاشات بتبني ألوانها بإيديها (المشغّل · الريموت · المكتبة) =====
    /** (v898) شكل أزرار المشغّل — نفس اختيارات MeeTube «Player buttons»: 0 شفاف مصبوغ (الافتراضي) · 1 مفروست · 2 مينيمال (أيقونات من غير دوائر). محفوظ في Cfg «player_btn_look». */
    fun playerBtnLook(): Int = try { Cfg.int("player_btn_look", 0).coerceIn(0, 2) } catch (_: Throwable) { 0 }

    /** (v1209) ألوان Espresso دافية بدل الكحلي البارد. زرار/قايمة صغيرة فوق الفيديو: زجاج داكن بلمعة من فوق + حافة شعرية (الخلفية دايمًا داكنة فمابيتبعش الثيم). المينيمال بيأثر على الدواير بس عشان القوايم تفضل مقروءة. */
    fun overlayPill(d: Float, rDp: Int): GradientDrawable {
        val frosted = playerBtnLook() == 1
        val cols = if (frosted) intArrayOf(0xCC2C2C2C.toInt(), 0xD90A0A0A.toInt()) else intArrayOf(0xA6282828.toInt(), 0xB3080808.toInt())
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, cols).apply {
            cornerRadius = radPx(rDp * d, d); setStroke(hair(d), if (frosted) 0x4DF5F0E7 else 0x33F5F0E7)
        }
    }

    /** زرار دايري فوق الفيديو — حسب {@link #playerBtnLook} */
    fun overlayCircle(d: Float): GradientDrawable = when (playerBtnLook()) {
        2 -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x00000000) }   // مينيمال: من غير دايرة ولا حافة
        1 -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC363636.toInt(), 0xD90A0A0A.toInt())).apply {
            shape = GradientDrawable.OVAL; setStroke(hair(d), 0x59F5F0E7)
        }
        else -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xA62C2C2C.toInt(), 0xB3080808.toInt())).apply {
            shape = GradientDrawable.OVAL; setStroke(hair(d), 0x40F5F0E7)
        }
    }

    // شاشات الريموت (ControllerPad/PlayerPad) ألوانها كحلي ثابت — بنحوّل بس الكحلي لزجاج، والألوان الدلالية (أزرق مفعّل/أخضر/أحمر) بتفضل زي ما هي
    private val PAD_FILLS = intArrayOf(
        0xFF0F1830.toInt(), 0xFF14203A.toInt(), 0xFF1B2C47.toInt(), 0xFF1E2F4D.toInt(), 0xE61E2F4D.toInt(),
        0xFF0B2338.toInt(), 0xFF0C2A44.toInt(), 0xFF0F3441.toInt(), 0xFF1C2A4A.toInt(), 0xFF121C34.toInt(), 0xFF0B1426.toInt())
    private val PAD_LINES = intArrayOf(0xFF2B3C60.toInt(), 0xFF34507A.toInt(), 0xFF3A5278.toInt(), 0xFF2F6A8C.toInt())

    /** تعبئة كحلي ← نفس اللون مرفوع شوية وشبه شفاف (زجاج) */
    fun pf(c: Int): Int =
        if (on() && PAD_FILLS.contains(c)) ((0xE0 shl 24) or (Glass.blend(c or 0xFF000000.toInt(), Color.WHITE, 0.05f) and 0x00FFFFFF)) else c

    /** خط كحلي ← حافة مضيئة شعرية */
    fun ps(c: Int): Int = if (on() && PAD_LINES.contains(c)) 0x2EFFFFFF else c

    /** استدارة ×1.3 للكروت (بكسل) */
    fun pr(px: Float): Float = if (on()) px * 1.3f else px
}
