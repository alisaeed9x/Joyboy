package com.tttt.subtitler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * (v899) مظهر المشغّل من MeeTube — كل الخيارات هنا متوصّلة فعليًا ومحفوظة في Cfg (بتفضل بعد إعادة التشغيل):
 *  • mt_loader        اللودر (8 أنواع من res بتاع MeeTube: avd_*) — بيظهر في كل سبينر Joyboy (WinSpinnerDrawable)
 *  • progress_glow    توهّج رأس شريط التقدم (GlowProgressBar.Head بتاع MeeTube)
 *  • seek thumb       نقطة / صورة (نفس ملف filesDir/seek_thumb بتاع SeekThumb في MeeTube)
 *  • gest_levels / gest_seek / gest_dbl_ms   إيماءات المشغّل (مستويات الصوت/السطوع · سحب التقديم · مدة الضغطتين)
 *  • mt_player_anim   انتقال فتح/قفل المشغّل (player_open_enter / player_close_exit)
 *  • hide_btn_dl      إخفاء زرار ⬇ من شريط المشغّل
 * كله موبايل/تابلت (MtGlass.on()) — التلفزيون ما بيتلمسش.
 */
object MtLook {
    class LoaderDef(val id: String, val name: String, val res: Int)
    val loaders: List<LoaderDef> by lazy { listOf(
        LoaderDef("neon", "نيون", R.drawable.avd_neon_ring),
        LoaderDef("frosted", "مفروست", R.drawable.avd_frosted_ring),
        LoaderDef("cookie", "كوكي", R.drawable.meetube_loader),
        LoaderDef("frosted_cookie", "كوكي مفروست", R.drawable.avd_frosted_cookie),
        LoaderDef("liquid_ring", "حلقة سائلة", R.drawable.avd_liquid_ring),
        LoaderDef("glass_orb", "كرة زجاج", R.drawable.avd_glass_orb),
        LoaderDef("morphing_blob", "فقاعة متحوّلة", R.drawable.avd_morphing_blob),
        LoaderDef("glass_orbit", "مدار زجاجي", R.drawable.avd_glass_orbit)) }

    fun loaderId(): String = try { Cfg.str("mt_loader", "neon") } catch (_: Throwable) { "neon" }
    /** اللودر شغّال بس في وضع الجلاس (موبايل/تابلت)؛ غير كده السبينر القديم */
    fun loaderOn(): Boolean = try { MtGlass.on() && Cfg.bool("mt_loader_on", true) } catch (_: Throwable) { false }

    /** نسخة جديدة من الـAVD (كل سبينر ليه نسخته عشان الأنيميشن مستقل) */
    fun newLoader(ctx: Context, id: String = loaderId()): Drawable? = try {
        val res = (loaders.firstOrNull { it.id == id } ?: loaders[0]).res
        ctx.getDrawable(res)?.constantState?.newDrawable(ctx.resources)?.mutate()
    } catch (_: Throwable) { null }

    // ---------- توهّج رأس الشريط ----------
    fun glowOn(): Boolean = try { Cfg.bool("progress_glow", true) } catch (_: Throwable) { true }

    /** منقول من GlowProgressBar.Head في MeeTube: بيضاوي بلون الرأس بيتلاشى بنعومة (جرس مش خط مستقيم) */
    class Glow {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var radius = 0f
        private var colorSet = 0
        fun draw(c: Canvas, x: Float, cy: Float, r: Float, color: Int) {
            if (!glowOn() || r <= 0f) return
            if (color != colorSet || r != radius || paint.shader == null) {
                colorSet = color; radius = r
                val rgb = color and 0x00FFFFFF
                val a = Color.alpha(color) * 0xB3 / 255
                val colors = IntArray(FALLOFF.size) { rgb or (Math.round(a * FALLOFF[it]) shl 24) }
                paint.shader = RadialGradient(0f, 0f, r, colors, STOPS, Shader.TileMode.CLAMP)
            }
            val save = c.save()
            c.translate(x, cy); c.scale(1f, 0.6f)
            c.drawCircle(0f, 0f, r, paint)
            c.restoreToCount(save)
        }
        companion object {
            private val STOPS = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
            private val FALLOFF = floatArrayOf(1f, 0.78f, 0.45f, 0.2f, 0.06f, 0f)
        }
    }

    // ---------- مقبض السيك (نقطة / صورة) ----------
    private fun thumbFile(ctx: Context) = File(ctx.filesDir, "seek_thumb")
    fun thumbIsPicture(ctx: Context): Boolean = try { thumbFile(ctx).exists() } catch (_: Throwable) { false }

    /** منقول من SeekThumb.load: الصورة (GIF/WebP متحرك كمان) بحد أقصى 192px */
    fun loadThumb(ctx: Context): Drawable? {
        val f = thumbFile(ctx)
        if (!f.exists()) return null
        return try {
            val d: Drawable? = if (Build.VERSION.SDK_INT >= 28) {
                android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(f)) { dec, info, _ ->
                    val mx = Math.max(info.size.width, info.size.height)
                    if (mx > 192) dec.setTargetSampleSize(Math.max(1, mx / 192))
                }
            } else {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, o)
                o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / 192); o.inJustDecodeBounds = false
                android.graphics.BitmapFactory.decodeFile(f.path, o)?.let { BitmapDrawable(ctx.resources, it) }
            }
            (d as? Animatable)?.start()
            d
        } catch (_: Throwable) { null }
    }

    fun setThumbDot(ctx: Context) { try { thumbFile(ctx).delete() } catch (_: Throwable) {} }
    fun pickThumbPicture(ctx: Context) { try { com.newtube.mobile.ui.playback.SeekThumb.setType(ctx, com.newtube.mobile.ui.playback.SeekThumb.PICTURE) } catch (_: Throwable) {} }

    // ---------- الإيماءات (PlayerGesturePrefs في MeeTube) ----------
    fun levelSwipes(): Boolean = try { !MtGlass.on() || Cfg.bool("gest_levels", true) } catch (_: Throwable) { true }
    fun seekSwipe(): Boolean = try { !MtGlass.on() || Cfg.bool("gest_seek", true) } catch (_: Throwable) { true }
    fun doubleTapMs(): Long = try { if (!MtGlass.on()) 10_000L else Cfg.int("gest_dbl_ms", 10).coerceIn(5, 60) * 1000L } catch (_: Throwable) { 10_000L }

    // ---------- انتقال المشغّل ----------
    fun playerAnim(): Boolean = try { MtGlass.on() && Cfg.bool("mt_player_anim", true) } catch (_: Throwable) { false }
    fun hideDl(): Boolean = try { Cfg.bool("hide_btn_dl", false) } catch (_: Throwable) { false }

    // ================= واجهة الإعدادات =================
    fun loaderPicker(ctx: Context, th: Theme): View {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt()) }
        val tiles = ArrayList<Pair<String, LinearLayout>>()
        fun paint() = tiles.forEach { (id, t) ->
            val on = id == loaderId()
            t.background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(0xFF16161B.toInt()); setStroke(if (on) (2 * d).toInt() else 1, if (on) th.primary else 0x33FFFFFF) }
        }
        for (l in loaders) {
            val iv = ImageView(ctx).apply { setImageDrawable(newLoader(ctx, l.id)) }
            val name = TextView(ctx).apply { text = l.name; textSize = 10.5f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER; setSingleLine() }
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                addView(iv, LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt())); addView(name, LinearLayout.LayoutParams(-2, -2))
                setOnClickListener { Cfg.put("mt_loader", l.id); paint() }
            }
            iv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { (iv.drawable as? Animatable)?.start() }
                override fun onViewDetachedFromWindow(v: View) { (iv.drawable as? Animatable)?.stop() }
            })
            tiles.add(l.id to tile)
            row.addView(tile, LinearLayout.LayoutParams(-2, -2).apply { setMargins((4 * d).toInt(), 0, (4 * d).toInt(), 0) })
        }
        paint()
        return HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    fun views(ctx: Context, ui: Ui, th: Theme): List<View> = listOf(
        ui.noHint(),
        ui.text("⭕ اللودر (بيظهر في كل لودينج: التحميل · الترجمة · البافرنج)", 13f, th.text, true),
        ui.switchRow("استخدم لودر MeeTube (قفله = النقط القديمة)", Cfg.bool("mt_loader_on", true)) { Cfg.put("mt_loader_on", if (it) "1" else "0") },
        loaderPicker(ctx, th),
        ui.text("✨ شريط التقدّم ومقبض السيك", 13f, th.text, true),
        ui.switchRow("توهّج رأس شريط التقدّم", glowOn()) { Cfg.put("progress_glow", if (it) "1" else "0") },
        ui.chips(listOf("⚪ نقطة", "🖼 صورة"), { if (thumbIsPicture(ctx)) "🖼 صورة" else "⚪ نقطة" }) { n -> if (n == "🖼 صورة") pickThumbPicture(ctx) else setThumbDot(ctx) },
        ui.noHint(),
        ui.text("👆 إيماءات المشغّل", 13f, th.text, true),
        ui.switchRow("سحب الصوت/السطوع على جانبي الفيديو", levelSwipes()) { Cfg.put("gest_levels", if (it) "1" else "0") },
        ui.switchRow("سحب أفقي للتقديم/الترجيع", seekSwipe()) { Cfg.put("gest_seek", if (it) "1" else "0") },
        ui.slider("مدة الضغطتين للتقديم/الترجيع", Cfg.int("gest_dbl_ms", 10).coerceIn(5, 60), 5, 60, " ث") { Cfg.put("gest_dbl_ms", it.toString()) },
        ui.text("🖼 جودة صورة الفيديو المحلي (من غير AI ومن غير عتاد قوي)", 13f, th.text, true),
        ui.chips(listOf("إيقاف", "خفيف", "متوسط", "قوي"), { MtEnhance.levelName() }) { n -> MtEnhance.setLevel(n) },
        ui.switchRow("ألوان أوضح (تباين وتشبّع خفيف)", MtEnhance.vivid()) { Cfg.put(MtEnhance.K_VIVID, if (it) "1" else "0") },
        ui.text("🎞 الحركة والأزرار", 13f, th.text, true),
        ui.switchRow("حركة فتح/قفل المشغّل (من MeeTube: تكبير ناعم + تلاشي)", Cfg.bool("mt_player_anim", true)) { Cfg.put("mt_player_anim", if (it) "1" else "0") },
        ui.switchRow("إخفاء زرار ⬇ تحميل من شريط المشغّل", hideDl()) { Cfg.put("hide_btn_dl", if (it) "1" else "0") },
        ui.switchRow("مشغّل MeeTube لكل الملفات واللينكات (قفله = مشغّل Joyboy القديم)", MtPlay.enabled()) { Cfg.put(MtPlay.KEY, if (it) "1" else "0") })
}
