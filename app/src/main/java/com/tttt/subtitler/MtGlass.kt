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
    // (v1071) مفيش زجاج خالص: كل التعبئات مصمتة من ألوان الثيم — من غير شفافية ولا لمعة ولا حافة مضيئة
    fun fill(th: Theme): Int = th.card
    fun stroke(th: Theme): Int = th.border
    fun rim(th: Theme): Int = th.border
    fun sheen(th: Theme): Int = 0x00000000
    fun selected(th: Theme): Int = if (th.isLight) Glass.blend(th.card, Color.BLACK, 0.08f) else Glass.blend(th.card, Color.WHITE, 0.12f)
    fun sheet(th: Theme): Int = th.surface
    fun navInactive(th: Theme): Int = th.muted
    /** شريط سفلي: لون الصفحة مرفوع شوية (زجاج من غير حاجة تتبلّر تحته: «9% أبيض عشان يبان على الصفحة المسطّحة») */
    fun barSolid(th: Theme): Int = Glass.blend(th.bg, if (th.isLight) Color.BLACK else Color.WHITE, 0.07f)

    // ===== (v897) زجاج حقيقي شفاف فوق الخلفية المتحركة (MtAurora) — الكروت مابقتش لون صلب =====
    /** كارت زجاجي: تدرّج شفاف (لمعة من فوق) + حافة مضيئة شعرية. بيتحط مكان كارت th.card الصلب */
    // (v1071) الكارت بقى مصمت بلون الثيم — من غير تدرّج شفاف ولا حافة مضيئة
    fun glassCard(th: Theme, radiusPx: Float, d: Float): GradientDrawable =
        shapePx(th.card, th.border, radiusPx, hair(d))

    /** «بئر» زجاجي: للحقول/الأسطح الداخلية (th.surface) — أغمق من الكارت شوية عشان النص يبان */
    // (v1071) «البئر» بقى مصمت بلون السطح
    fun glassWell(th: Theme, radiusPx: Float, d: Float): GradientDrawable =
        shapePx(th.surface, th.border, radiusPx, hair(d))

    /** شيت سفلي زجاجي (استدارة من فوق بس): سطح شبه شفاف + لمعة + حافة مضيئة */
    // (v1071) الشيت بقى مصمت بلون السطح — من غير لمعة ولا حافة مضيئة
    fun sheetTop(th: Theme, radiusPx: Float, density: Float): Drawable =
        GradientDrawable().apply { setColor(th.surface); cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f) }

    /** (v901) كبسولة/دوك عايم زجاجي داكن (نص أبيض فوقه): سطح شبه شفاف + لمعة + حافة مضيئة (أو لون تمييز لما accent != 0) */
    // (v1071) الكبسولة العايمة بقت مصمتة — من غير لمعة ولا حافة مضيئة (الـaccent لو موجود بيفضل كخط)
    fun floatPill(radiusPx: Float, density: Float, accent: Int = 0): Drawable =
        GradientDrawable().apply {
            setColor(0xFF303034.toInt())
            cornerRadius = radiusPx
            if (accent != 0) setStroke(max(1, density.roundToInt()), accent)
        }

    /** تعبئة مسطّحة مصمتة لأي مساحة (صف · لوحة) من غير استدارة */
    fun flatFill(th: Theme): Int = th.card

    /** استدارة زي ما هي — من غير تكبير الزجاج (×1.3) */
    fun rad(r: Int): Int = r
    fun radPx(px: Float, density: Float): Float = px
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
        GradientDrawable().apply { setColor(th.primary); cornerRadius = radiusPx }   // (v909) زي زرار MeeTube الرئيسي (bg_next_button_primary): كبسولة مصمتة من غير تدرّج

    /** كبسولة زجاجية (زرار ثانوي) */
    // (v1071) mt() اتشال — drawables الزجاج بتاعة MeeTube مابقتش بتتنده

    // (v1071) الكبسولة بقت مصمتة — من غير drawable الزجاج بتاع MeeTube
    fun pill(th: Theme, fill: Int, radiusPx: Float, density: Float): GradientDrawable =
        shapePx(fill, stroke(th), radiusPx, hair(density))

    /** chip: المختار = زجاج مضيء + خط لون التمييز · غير المختار = زجاج هادي */
    fun chip(th: Theme, on: Boolean, radiusPx: Float, density: Float): GradientDrawable =
        if (on) shapePx(selected(th), th.primary, radiusPx, max(1, density.roundToInt())) else shapePx(fill(th), stroke(th), radiusPx, hair(density))

    /** شيت/دايلوج: سطح شبه شفاف فوق بلور النافذة + لمعة من فوق + حافة مضيئة (bg_glass_sheet + sheen + rim) */
    // (v1071) خلفية الشيت/الدايلوج بقت مصمتة بلون السطح
    fun sheetBg(th: Theme, radiusPx: Float, density: Float): Drawable =
        GradientDrawable().apply { setColor(th.surface); cornerRadius = radiusPx }

    // ===== الحركة (Motion.java): منحنيات Material 3 =====
    // (v1072) منحنيات MeeTube نفسها بقت ثوابت محلية (Emphasized من Material 3) بعد ما اتشال المودول
    val EMPHASIZED_DECELERATE: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val EMPHASIZED_ACCELERATE: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val EMPHASIZED: Interpolator by lazy {
        val p = Path()
        p.moveTo(0f, 0f)
        p.cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        p.cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        PathInterpolator(p)
    }
    const val ENTER_MS = 250L      // سطح داخل (شيت بينزلق لفوق)
    const val EXIT_MS = 200L       // سطح خارج
    const val FADE_OUT_MS = 90L
    const val FROST_MS = 320L      // صفحة بتطلع من زجاج مثلّج (تبديل تبويب)
    const val POP_MS = 260L
    private const val FROST_BLUR_DP = 14f

    private fun dp(v: View, n: Float): Float = n * v.resources.displayMetrics.density

    /** «الدفعة»: بيصغّر لـ in وبيعدّي لـ over وبيستقر (لايك/اختيار/سويتش) */
    // (v1071) مفيش أنيميشن: «الدفعة» بقت فورية — الحجم النهائي على طول
    fun pop(v: View?, inS: Float = 0.78f, over: Float = 1.12f) {
        if (v == null) return
        v.animate().cancel()
        v.scaleX = 1f
        v.scaleY = 1f
    }

    private val frosting = WeakHashMap<View, Animator>()

    /**
     * (frostIn) الصفحة بتظهر وهي طالعة من زجاج مثلّج: فيد + بلور بيصفى على الـGPU (أندرويد 12+؛ الأقدم فيد بس).
     * البلور بيتشال في الآخر فمفيش تكلفة بعد كده. dx = انزلاقة أفقية خفيفة (اتجاه التبويب).
     */
    // (v1071) مفيش أنيميشن: الحالة النهائية فورًا — من غير فيد ولا انزلاق ولا بلور بيصفى
    fun frostIn(v: View, dx: Float = 0f) {
        v.animate().cancel()
        frosting.remove(v)?.cancel()
        v.alpha = 1f
        v.translationX = 0f
        try { v.setRenderEffect(null) } catch (_: Throwable) {}
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
                        .setDuration(Skins.dur(280L)).setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
            false   // مابيعترضش الـclick
        }
    }

    // ===== (v894) المرحلة 2: شاشات بتبني ألوانها بإيديها (المشغّل · الريموت · المكتبة) =====
    /** (v898) شكل أزرار المشغّل — نفس اختيارات MeeTube «Player buttons»: 0 شفاف مصبوغ (الافتراضي) · 1 مفروست · 2 مينيمال (أيقونات من غير دوائر). محفوظ في Cfg «player_btn_look». */
    fun playerBtnLook(): Int = try { Cfg.int("player_btn_look", 0).coerceIn(0, 2) } catch (_: Throwable) { 0 }

    /** زرار/قايمة صغيرة فوق الفيديو: زجاج داكن بلمعة من فوق + حافة شعرية (الخلفية دايمًا داكنة فمابيتبعش الثيم). المينيمال بيأثر على الدواير بس عشان القوايم تفضل مقروءة. */
    // (v1071) أزرار المشغّل فوق الفيديو بقت مصمتة — من غير تدرّج شفاف (الـ25% fade بتاع الأزرار العايمة زي ما هو، ده ألفا الـView مش الـdrawable)
    fun overlayPill(d: Float, rDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(0xFF30303C.toInt())
            cornerRadius = radPx(rDp * d, d)
            setStroke(hair(d), 0xFF4A4A52.toInt())
        }

    /** زرار دايري فوق الفيديو — حسب {@link #playerBtnLook} */
    fun overlayCircle(d: Float): GradientDrawable = when (playerBtnLook()) {
        2 -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x00000000) }   // مينيمال: من غير دايرة ولا حافة
        else -> GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF30303C.toInt())
            setStroke(hair(d), 0xFF4A4A52.toInt())
        }
    }

    // شاشات الريموت (ControllerPad/PlayerPad) ألوانها كحلي ثابت — بنحوّل بس الكحلي لزجاج، والألوان الدلالية (أزرق مفعّل/أخضر/أحمر) بتفضل زي ما هي
    private val PAD_FILLS = intArrayOf(
        0xFF0F1830.toInt(), 0xFF14203A.toInt(), 0xFF1B2C47.toInt(), 0xFF1E2F4D.toInt(), 0xE61E2F4D.toInt(),
        0xFF0B2338.toInt(), 0xFF0C2A44.toInt(), 0xFF0F3441.toInt(), 0xFF1C2A4A.toInt(), 0xFF121C34.toInt(), 0xFF0B1426.toInt())
    private val PAD_LINES = intArrayOf(0xFF2B3C60.toInt(), 0xFF34507A.toInt(), 0xFF3A5278.toInt(), 0xFF2F6A8C.toInt())

    /** تعبئة كحلي ← نفس اللون مرفوع شوية وشبه شفاف (زجاج) */
    // (v1071) تعبئة الريموت بقت زي ما هي — من غير تحويل لزجاج
    fun pf(c: Int): Int = c

    /** خط كحلي ← زي ما هو */
    fun ps(c: Int): Int = c

    /** استدارة زي ما هي — من غير تكبير الزجاج */
    fun pr(px: Float): Float = px
}
