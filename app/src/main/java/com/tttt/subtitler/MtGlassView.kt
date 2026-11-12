package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.util.WeakHashMap
import kotlin.math.min

/**
 * (v906) مصدر واحد للزجاج: الشريط السفلي بقى بيستخدم كلاسات MeeTube الأصلية (GlassView · GlassSource · NavLens من مودول :mobile)
 * بدل نسختنا المنقولة (MtGlassView/MtGlassSource/MtLens اللي فضلت للأماكن التانية). فبلور 8dp + تشبّع 1.5× + حبيبات + انكسار (أندرويد 13+)
 * + حافة لامعة + العدسة — كلها من نفس الكود اللي في شريط MeeTube، ولونه من meetube_glass_tint.
 */
class JoyGlassSource(ctx: Context) : com.newtube.mobile.ui.common.GlassSource(ctx) {
    /** الخلفية المتحركة (MtAurora) اللي الزجاج لازم يبلّرها لما الصفحة نفسها شفافة — GlassView بيسأل المصدر عن getBackground() */
    var ground: android.graphics.drawable.Drawable? = null
    override fun getBackground(): android.graphics.drawable.Drawable? {
        val g = ground ?: return super.getBackground()
        g.setBounds(0, 0, width, height)
        return g
    }
}

object MeeGlass {
    /** سياق بنفس فاتح/داكن ثيم Joyboy الحالي: ألوان MeeTube (values / values-night) تتحلّ على الثيم مش على إعداد النظام */
    fun ctx(act: Activity, th: Theme): Context {
        val cfg = android.content.res.Configuration(act.resources.configuration)
        cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (th.isLight) android.content.res.Configuration.UI_MODE_NIGHT_NO else android.content.res.Configuration.UI_MODE_NIGHT_YES)
        val w = android.view.ContextThemeWrapper(act, 0)
        w.applyOverrideConfiguration(cfg)
        return w
    }

    /** كبسولة الشريط الزجاجية بنفس مقاسات mobile_nav_glass في MeeTube (استدارة 34 · حشو 6 · ظل 6 · tint = meetube_glass_tint) — layout/joy_nav_glass.xml */
    fun bar(act: Activity, th: Theme): com.newtube.mobile.ui.common.GlassView =
        android.view.LayoutInflater.from(ctx(act, th)).inflate(R.layout.joy_nav_glass, null) as com.newtube.mobile.ui.common.GlassView

    fun lens(act: Activity, th: Theme): com.newtube.mobile.ui.common.NavLens =
        com.newtube.mobile.ui.common.NavLens(ctx(act, th), null).apply { visibility = View.GONE; elevation = 8f * act.resources.displayMetrics.density }
}

/** (v895) الشريط السفلي العايم فوق المحتوى (موبايل بس): المصدر = الصفحات، والزجاج = الشريط + شريط ميديا */
object MtFloat {
    private val padded = WeakHashMap<View, Int>()
    private val sources = WeakHashMap<Activity, JoyGlassSource>()
    private val bars = WeakHashMap<Activity, View>()
    /** (v910) الشريط السفلي الزجاجي العايم بتاع الشاشة دي (لو فيه) — عشان الأزرار العايمة (⚙️ الصفحة...) تقعد فوقه بالظبط */
    fun barOf(act: Activity): View? = bars[act]
    /** الارتفاع اللي الشريط بياخده من تحت الشاشة (ارتفاعه + هامشه التحتاني)؛ 0 لو مفيش شريط عايم */
    fun barTop(act: Activity): Int {
        val b = bars[act] ?: return 0
        if (b.height <= 0 || b.visibility == View.GONE) return 0
        return b.height + ((b.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0)
    }
    /** (v910) أي View متعلّم بالتاج ده (FAB عايم جوه الصفحة) بيتزحزح لفوق بارتفاع الشريط عشان ما يستخباش وراه */
    const val LIFT_TAG = "joy_lift_above_bar"
    private fun liftFabs(v: View, b: Int) {
        if (v.tag == LIFT_TAG) v.translationY = -b.toFloat()
        if (v is ViewGroup) for (i in 0 until v.childCount) liftFabs(v.getChildAt(i), b)
    }
    /** (v909) مصدر الزجاج (GlassSource) بتاع شاشة الأكتيفيتي دي — عشان الدوك العايم يبلّر الصفحة اللي تحته بـGlassDrawable بتاع MeeTube */
    fun sourceFor(act: Activity): JoyGlassSource? = sources[act]

    /** بيزوّد padding سفلي لكل سكرول/قايمة عشان آخر عنصر يطلع فوق الشريط والمحتوى يعدّي تحته (clipToPadding=false) */
    private fun padScrollers(v: View, bottom: Int) {
        if (v is ViewGroup) {
            val cn = v.javaClass.name
            val vertScroll = v !is HorizontalScrollView && (v is AbsListView || cn.endsWith("ScrollView") || cn.endsWith("RecyclerView"))   // (v922) + RecyclerView
            if (vertScroll) {
                val old0 = padded[v] ?: 0
                val old = if (v.paddingBottom >= old0) old0 else 0   // (v922) الصفحة رجّعت الحشو لنفسها (setPadding) = نعيده من الأول
                if (old != bottom || old0 != bottom) {
                    v.clipToPadding = false
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom - old + bottom)
                    padded[v] = bottom
                }
            }
            for (i in 0 until v.childCount) padScrollers(v.getChildAt(i), bottom)
        }
    }

    /** (v900) الشريط العايم متاح؟ موبايل بس (مش تابلت/تلفزيون) وأندرويد 12+ — نفس شرط الشاشة الرئيسية */
    fun eligible(act: Activity): Boolean = try { MtGlass.on() && !DeviceUi.isTablet(act) && !DeviceUi.isTv(act) && Build.VERSION.SDK_INT >= 31 } catch (_: Throwable) { false }

    fun attach(act: Activity, th: Theme, ui: Ui, shell: LinearLayout, paneWrap: View, nav: BottomNav, msw: MediaSwitch?) {
        shell.addView(build(act, th, ui, paneWrap, nav, msw), LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** (v900) يبني صفحة كاملة = المحتوى + الشريط السفلي العايم الزجاجي (نفس شريط صفحة الميديا). reserve=true: المحتوى مايتغطّاش تحت الشريط (للمتصفح — WebView مش سكرول عادي) */
    fun build(act: Activity, th: Theme, ui: Ui, paneWrap: View, nav: BottomNav, msw: MediaSwitch?, reserve: Boolean = false, onReserve: ((Int) -> Unit)? = null): FrameLayout {
        val d = act.resources.displayMetrics.density
        val fl = FrameLayout(act)
        val skin = MtSkin.on()   // (v897) الخلفية المتحركة في الـwindow — الصفحة شفافة، والشريط بيبلّر نسخة منها
        val src = JoyGlassSource(act).apply { if (!skin) setBackgroundColor(th.bg) }
        sources[act] = src
        // (v923) الرعشة في المتصفح: الزجاج كان بيسجّل الـWebView كلها في RenderNode ويرسمها مرتين كل فريم (مرة للشاشة ومرة للبلور) فالصفحة بترتعش.
        // في المتصفح (reserve) الـWebView بتترسم مباشرة على النافذة والشريط بياخد لون الزجاج من غير بلور حي.
        if (reserve) src.setSampling(false)
        src.addView(paneWrap, FrameLayout.LayoutParams(-1, -1))
        fl.addView(src, FrameLayout.LayoutParams(-1, -1))
        nav.floatGlass()
        val bar = MeeGlass.bar(act, th)   // (v906) GlassView بتاع MeeTube الأصلي
        bar.setSource(src)
        // (v922) زرارين فيديوهات/موسيقى كانوا جوه نفس كبسولة الشريط فكل أنيميشن (عدسة/اهتزاز/ضغطة) كان بيجري عليهم ويتقل — دلوقتي كبسولة زجاجية لوحدها فوق الشريط، من غير أي أنيميشن للشريط
        var mswBar: com.newtube.mobile.ui.common.GlassView? = null
        if (msw != null) {
            val mb = MeeGlass.bar(act, th)
            mb.setSource(src)
            msw.view.setBackgroundColor(0)
            mb.addView(msw.view, FrameLayout.LayoutParams(-1, -2))
            msw.host = mb   // ظهور/اختفاء الكبسولة بيتبع ظهور الزرارين
            mb.visibility = msw.view.visibility
            mswBar = mb
        }
        val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        if (mswBar != null) wrap.addView(mswBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        wrap.addView(bar, LinearLayout.LayoutParams(-1, -2))
        if (reserve) wrap.background = android.graphics.drawable.GradientDrawable().apply { setColor((th.bg and 0x00FFFFFF) or 0xC8000000.toInt()); cornerRadius = 34f * d }   // (v923) من غير بلور: خلفية شبه معتمة عشان الصفحة ما تبانش تحت الأزرار
        bars[act] = wrap   // الارتفاع الكلي (كبسولة الميديا + الشريط) — PageSettings/⚙️ بتقعد فوقه كله
        if (skin) {
            val groundAurora = MtSkin.aurora(act, th)
            src.ground = groundAurora
            groundAurora.addHost(bar)
            mswBar?.let { groundAurora.addHost(it) }
            if (MtSkin.animated(act)) groundAurora.start()
            bar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { if (MtSkin.animated(act)) groundAurora.start() }
                override fun onViewDetachedFromWindow(v: View) { groundAurora.stop() }
            })
        }
        bar.addView(nav.view, FrameLayout.LayoutParams(-1, -2))
        val gap = ui.dp(12)   // (v906) نفس هوامش MeeTube: 16 جنب · 12 تحت
        fl.addView(wrap, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(ui.dp(16), 0, ui.dp(16), gap) })
        // (v922) العدسة والاهتزاز كانوا سبب اللاج الرهيب (بلور حي + ريندر لكل فريم فوق الصفحة كلها): اتقفلوا افتراضيًا. الانزلاقة + قفزة الأيقونة بس.
        // لو عايزهم تاني: Cfg glass_lens / glass_jiggle = true.
        val lensOn = Cfg.bool("glass_lens", false) && com.newtube.mobile.ui.common.NavLens.enabled(act)
        if (lensOn) {
            val lens = MeeGlass.lens(act, th)
            fl.addView(lens, FrameLayout.LayoutParams(ui.dp(1), ui.dp(1), Gravity.TOP or Gravity.START))
            nav.lensHook = { from, to, settled ->
                val fx = com.newtube.mobile.ui.common.NavLens.centreInBar(from, bar); val tx = com.newtube.mobile.ui.common.NavLens.centreInBar(to, bar)
                if (Cfg.bool("glass_jiggle", false)) com.newtube.mobile.ui.common.NavLens.jiggle(bar, if (tx > fx) 1 else -1)
                if (com.newtube.mobile.ui.common.NavLens.supported(act)) { lens.play(bar, from, to, Runnable { settled() }); true } else false
            }
        } else {
            nav.lensHook = null
        }
        var scheduled = false
        fun pad() {
            scheduled = false
            if (wrap.height <= 0) return
            val b = wrap.height + gap + ui.dp(4)
            // (v910) reserve (المتصفح): ما بنحطّش حشو أسفل الصفحة (كان بيعمل شريط أبيض ورا الشريط الزجاجي فالبلور مابيبانش) — الصفحة بتكمّل تحت الشريط والزجاج بيبلّرها، والمتصفح بيرفع أزراره بنفسه عن طريق onReserve
            if (reserve) onReserve?.invoke(b)
            else padScrollers(paneWrap, b)
            liftFabs(paneWrap, b)
        }
        fl.viewTreeObserver.addOnGlobalLayoutListener { if (!scheduled) { scheduled = true; fl.postDelayed({ pad() }, 200L) } }
        return fl
    }
}
