package com.tttt.subtitler.phonehome

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.tttt.subtitler.FreeLlm
import com.tttt.subtitler.LogStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * (v775) ستور الثيمات: زرار واحد بيولّد ثيم عشوائي كامل (اسم + ألوان + خلفية من Bing + شكل أيقونات + أوتلاين + ساعة)،
 * معاينة حقيقية قبل التثبيت (✅ تثبيت · ❌ إلغاء · 🔄 توليد آخر)، وستور بيحفظ كل ثيم (JSON + الخلفية + صورة مصغرة).
 * الثيم بيخص اللانشر بس — مفيش أي حاجة فيه بتلمس التطبيقات الداخلية (مشاهير/فيديوهات/متصفح).
 */

// ───────── ملف الثيم (.joytheme = JSON) ─────────
data class HomeTheme(
    val name: String, val accent: Int, val text: Int, val dock: Int, val drawer: Int,
    val iconShape: String = "default",   // default | circle | squircle | rounded | square | hexagon | diamond | oval | octagon | leaf | shield
    val iconDp: Int = 0,                 // 0 = تلقائي، وإلا 40..68
    val labels: Boolean = true,
    val labelSp: Float = 11f,
    val dockRadius: Int = 32,
    val drawerRadius: Int = 28,
    val clockSp: Int = 0,                // 0 = تلقائي، وإلا 40..96
    val clockFont: String = "",
    val transition: String = "",         // "" = الافتراضي | none | slide | zoom | fade
    val outline: Boolean = false,        // أوتلاين حوالين الأيقونة بنفس شكلها
    val outlineDp: Int = 2,
    val outlineColor: Int = Color.WHITE,
    val wallQuery: String = "",          // كلمات بحث الخلفية (إنجليزي)
    val wall: String = ""                // "" = الثيم من غير خلفية خاصة | "gradient" = تدرّج من ألوان الثيم | اسم ملف صورة الخلفية
) {
    val hasWall: Boolean get() = wall.isNotEmpty()

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("accent", hex(accent)).put("text", hex(text))
        .put("dock", hex(dock)).put("dock_alpha", Math.round(((dock ushr 24) / 255.0) * 100) / 100.0)
        .put("drawer", hex(drawer)).put("icon_shape", iconShape).put("icon_dp", iconDp)
        .put("labels", labels).put("label_sp", labelSp.toDouble()).put("dock_radius", dockRadius)
        .put("drawer_radius", drawerRadius).put("clock_sp", clockSp).put("clock_font", clockFont)
        .put("transition", transition)
        .put("outline", JSONObject().put("enabled", outline).put("width", outlineDp).put("color", hex(outlineColor)))
        .put("wall_query", wallQuery).put("wall", wall)

    companion object {
        val SHAPES = setOf("default", "circle", "squircle", "rounded", "square", "hexagon", "diamond", "oval", "octagon", "leaf", "shield")
        val PICK_SHAPES = listOf("circle", "squircle", "rounded", "square", "hexagon", "diamond", "oval", "octagon", "leaf", "shield")
        val FONTS = setOf("sans-serif", "sans-serif-light", "sans-serif-thin", "sans-serif-condensed", "sans-serif-medium", "serif", "monospace", "casual", "cursive")
        val TRANSITIONS = setOf("", "none", "slide", "zoom", "fade")
        private val WALL_NAME = Regex("[A-Za-z0-9_]{1,40}\\.wall\\.jpg")

        /** كل قيمة بتتراجع وتتقصّ لحدود آمنة — مهما رجّع الموديل الواجهة ما بتبوظش */
        fun fromJson(o: JSONObject): HomeTheme {
            fun col(k: String, d: Int): Int = try { Color.parseColor(o.getString(k).trim()) } catch (_: Throwable) { d }
            val accent = col("accent", Color.WHITE) or 0xFF000000.toInt()
            var text = col("text", Color.WHITE) or 0xFF000000.toInt()
            if (ColorUtils.calculateLuminance(text) < .55) text = ColorUtils.blendARGB(text, Color.WHITE, .75f)   // النص فوق خلفية متعتّمة: لازم يبان
            val alpha = (o.optDouble("dock_alpha", .35).coerceIn(.15, .85) * 255).toInt()
            val dock = (alpha shl 24) or (col("dock", 0xFF000000.toInt()) and 0x00FFFFFF)
            val drawer = col("drawer", 0xFF1F1F24.toInt()) or 0xFF000000.toInt()
            val rawShape = o.optString("icon_shape", "").trim().lowercase()
            val shape = if (rawShape.isEmpty()) "default" else if (rawShape in SHAPES) rawShape else "rounded"   // شكل مش معروف ← rounded
            val iconDp = o.optInt("icon_dp", 0).let { if (it <= 0) 0 else it.coerceIn(40, 68) }
            val clockSp = o.optInt("clock_sp", 0).let { if (it <= 0) 0 else it.coerceIn(40, 96) }
            val font = o.optString("clock_font", "").trim().lowercase().let { if (it in FONTS) it else "" }
            val tr = o.optString("transition", "").trim().lowercase().let { if (it in TRANSITIONS) it else "" }
            val ol = o.optJSONObject("outline")
            val olOn = ol?.optBoolean("enabled", false) ?: false
            val olW = (ol?.optInt("width", 2) ?: 2).coerceIn(1, 4)
            val olC = (if (ol != null) { try { Color.parseColor(ol.getString("color").trim()) } catch (_: Throwable) { accent } } else accent) or 0xFF000000.toInt()
            val q = o.optString("wall_query", "").replace(Regex("[^\\p{L}\\p{N} \\-]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
            val w0 = o.optString("wall", "").trim()
            val wall = if (w0 == "gradient" || WALL_NAME.matches(w0)) w0 else ""
            return HomeTheme(
                o.optString("name", "ثيم").trim().ifEmpty { "ثيم" }.take(24), accent, text, dock, drawer, shape, iconDp,
                o.optBoolean("labels", true), o.optDouble("label_sp", 11.0).coerceIn(9.0, 14.0).toFloat(),
                o.optInt("dock_radius", 32).coerceIn(0, 44), o.optInt("drawer_radius", 28).coerceIn(0, 44),
                clockSp, font, tr, olOn, olW, olC, q, wall
            )
        }
    }
}

// ───────── أشكال الأيقونات ─────────
object HomeShapes {
    /** بيرسم شكل [shape] جوه المستطيل [r] — كل الأشكال محدّبة (convex) عشان تنفع كـ Outline */
    fun path(shape: String, r: RectF): Path {
        val w = r.width(); val h = r.height()
        val l = r.left; val t = r.top; val rt = r.right; val b = r.bottom
        val cx = r.centerX(); val cy = r.centerY()
        val p = Path()
        when (shape) {
            "circle" -> p.addOval(r, Path.Direction.CW)
            "oval" -> { val ow = w * .8f; p.addOval(RectF(cx - ow / 2f, t, cx + ow / 2f, b), Path.Direction.CW) }
            "squircle" -> p.addRoundRect(r, w * .36f, w * .36f, Path.Direction.CW)
            "square" -> p.addRoundRect(r, w * .08f, w * .08f, Path.Direction.CW)
            "hexagon" -> {
                val rad = minOf(w, h) / 2.0
                return poly((0 until 6).map { i ->
                    val a = Math.toRadians(-90.0 + 60.0 * i)
                    Pair((cx + rad * cos(a)).toFloat(), (cy + rad * sin(a)).toFloat())
                }, w * .08f)
            }
            "diamond" -> return poly(listOf(Pair(cx, t), Pair(rt, cy), Pair(cx, b), Pair(l, cy)), w * .1f)
            "octagon" -> {
                val c = w * .29f
                return poly(listOf(
                    Pair(l + c, t), Pair(rt - c, t), Pair(rt, t + c), Pair(rt, b - c),
                    Pair(rt - c, b), Pair(l + c, b), Pair(l, b - c), Pair(l, t + c)
                ), w * .05f)
            }
            "leaf" -> {
                val big = w * .5f; val small = w * .1f
                p.addRoundRect(r, floatArrayOf(big, big, small, small, big, big, small, small), Path.Direction.CW)
            }
            "shield" -> {
                p.moveTo(l, t + h * .1f)
                p.quadTo(l, t, l + w * .1f, t)
                p.lineTo(rt - w * .1f, t)
                p.quadTo(rt, t, rt, t + h * .1f)
                p.lineTo(rt, t + h * .5f)
                p.cubicTo(rt, t + h * .78f, l + w * .7f, t + h * .92f, cx, b)
                p.cubicTo(l + w * .3f, t + h * .92f, l, t + h * .78f, l, t + h * .5f)
                p.close()
            }
            else -> p.addRoundRect(r, w * .22f, w * .22f, Path.Direction.CW)   // rounded / أي حاجة تانية
        }
        return p
    }

    private fun poly(pts: List<Pair<Float, Float>>, soft: Float): Path {
        val p = Path()
        pts.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.first, pt.second) else p.lineTo(pt.first, pt.second) }
        p.close()
        return soften(p, soft)
    }

    /** بيدوّر الزوايا (CornerPathEffect) وبيرجّع Path جديد */
    private fun soften(src: Path, rad: Float): Path {
        val dst = Path()
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; pathEffect = CornerPathEffect(rad) }
        pt.getFillPath(src, dst)
        return dst
    }
}

/** أيقونة بتتقص بالشكل المختار + أوتلاين (stroke) بنفس الشكل جوه الحدود */
class ShapedImageView(ctx: Context, private val shape: String, private val strokePx: Float, strokeColor: Int) : ImageView(ctx) {
    private val clip = Path()
    private val ring = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = strokeColor; strokeWidth = strokePx }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                if (clip.isEmpty) { o.setEmpty(); return }
                try { o.setConvexPath(clip) } catch (_: Throwable) { o.setRoundRect(0, 0, v.width, v.height, v.width * .22f) }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clip.set(HomeShapes.path(shape, RectF(0f, 0f, w.toFloat(), h.toFloat())))
        val half = strokePx / 2f
        ring.set(HomeShapes.path(shape, RectF(half, half, w - half, h - half)))
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        val soft = !canvas.isHardwareAccelerated   // رسم سوفتوير (الصورة المصغرة): القص بالـclipPath
        if (soft) { canvas.save(); canvas.clipPath(clip) }
        super.onDraw(canvas)
        if (strokePx > 0f) canvas.drawPath(ring, paint)
        if (soft) canvas.restore()
    }
}

object HomeIcons {
    /** أيقونة بحجم [px] بشكل وأوتلاين الثيم (أو ImageView عادي لو مفيش ثيم/شكل) */
    fun make(ctx: Context, th: HomeTheme?, px: Int): ImageView {
        val stroke = if (th != null && th.outline) ctx.dp(th.outlineDp).toFloat() else 0f
        val shape = if (th == null) "default" else if (th.iconShape == "default" && stroke > 0f) "rounded" else th.iconShape
        val iv: ImageView = if (th == null || shape == "default") ImageView(ctx) else ShapedImageView(ctx, shape, stroke, th.outlineColor)
        iv.layoutParams = LinearLayout.LayoutParams(px, px)
        return iv
    }

    fun shaped(th: HomeTheme?): Boolean = th != null && (th.iconShape != "default" || th.outline)
}

// ───────── خلفية الثيم: بحث صور Bing ─────────
object ThemeWall {
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_SIDE = 1920

    private fun http(url: String, max: Int): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 12000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
            if (c.responseCode !in 200..299) null
            else {
                val bo = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var tot = 0
                var tooBig = false
                c.inputStream.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        tot += n
                        if (tot > max) { tooBig = true; break }
                        bo.write(buf, 0, n)
                    }
                }
                if (tooBig || tot == 0) null else bo.toByteArray()
            }
        } catch (_: Throwable) { null } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }

    private fun urls(q0: String): List<String> {
        val q = if (q0.contains("wallpaper", true)) q0 else "$q0 wallpaper"
        val u = "https://www.bing.com/images/search?q=" + URLEncoder.encode(q, "UTF-8") +
            "&form=HDRSC2&first=1&qft=+filterui:aspect-tall+filterui:imagesize-large&adlt=strict"
        val html = http(u, 3_000_000)?.toString(Charsets.UTF_8) ?: return emptyList()
        val bad = listOf("wikipedia.org", "wikimedia.org", "pinterest", "alamy", "shutterstock", "istockphoto", "dreamstime", "gettyimages", ".svg", ".gif")
        val out = ArrayList<String>()
        for (m in Regex("murl&quot;:&quot;(.*?)&quot;").findAll(html)) {
            val link = m.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
            if (!link.startsWith("http") || bad.any { link.contains(it, true) }) continue
            if (link !in out) out.add(link)
            if (out.size >= 20) break
        }
        return out.sortedBy { if (it.startsWith("https")) 0 else 1 }
    }

    /** بيجيب صورة خلفية عمودية من Bing بكلمات [query]؛ null لو فشل (والمتصل بيستخدم تدرّج الألوان بدلها) */
    fun fetch(query: String): Bitmap? {
        if (query.isBlank()) return null
        val all = urls(query)
        val cands = all.take(8).shuffled() + all.drop(8)
        val t0 = System.currentTimeMillis()
        var tries = 0
        for (u in cands) {
            if (tries >= 6 || System.currentTimeMillis() - t0 > 30_000L) break
            tries++
            val data = http(u, 5_000_000) ?: continue
            val bmp = decode(data) ?: continue
            return bmp
        }
        return null
    }

    /** لازم تكون عمودية (أو قريبة من المربّعة) وكبيرة كفاية — مش بانر ولا صورة صغيرة */
    private fun decode(data: ByteArray): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        val w = o.outWidth; val h = o.outHeight
        if (w <= 0 || h <= 0 || minOf(w, h) < 600 || h < w * .95f) null
        else {
            var ss = 1
            while (maxOf(w, h) / ss > MAX_SIDE) ss *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = ss })
        }
    } catch (_: Throwable) { null }

    fun decodeFile(f: File, maxSide: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var ss = 1
        while (maxOf(o.outWidth, o.outHeight) / ss > maxSide) ss *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss })
    } catch (_: Throwable) { null }

    fun save(b: Bitmap, f: File): Boolean = try {
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    } catch (_: Throwable) { false }

    /** البديل لو الصورة فشلت: تدرّج غامق من ألوان الثيم */
    fun gradient(t: HomeTheme): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(ColorUtils.blendARGB(t.accent, Color.BLACK, .6f) or 0xFF000000.toInt(), ColorUtils.blendARGB(t.drawer, Color.BLACK, .35f) or 0xFF000000.toInt())
    )
}

// ───────── مخزن الثيمات ─────────
object HomeThemeStore {
    /** الثيم الشغّال دلوقتي (null = الافتراضي) — Ui.kt والأدابتر والساعة بيقروا منه */
    @Volatile var cur: HomeTheme? = null

    private fun dir(ctx: Context): File = File(ctx.filesDir, "themes").apply { mkdirs() }

    fun load(p: SharedPreferences): HomeTheme? {
        val s = p.getString("ai_theme", null) ?: return null
        return try { HomeTheme.fromJson(JSONObject(s)) } catch (_: Exception) { null }
    }

    fun curId(p: SharedPreferences): String = p.getString("ai_theme_id", "") ?: ""

    fun apply(p: SharedPreferences, t: HomeTheme, id: String = "") {
        p.edit().putString("ai_theme", t.toJson().toString()).putString("ai_theme_id", id).apply()
        cur = t
    }

    fun clear(p: SharedPreferences) {
        p.edit().remove("ai_theme").remove("ai_theme_id").apply()
        cur = null
    }

    fun idOf(f: File): String = f.name.removeSuffix(".joytheme")
    fun thumbFile(ctx: Context, id: String): File = File(dir(ctx), "$id.thumb.jpg")

    /** ملف صورة الخلفية بتاع الثيم (null لو تدرّج أو الملف اتمسح) */
    fun wallFile(ctx: Context, t: HomeTheme): File? =
        if (t.wall.isEmpty() || t.wall == "gradient") null else File(dir(ctx), t.wall).takeIf { it.exists() }

    /** بيحفظ ثيم جديد: JSON + الخلفية + الصورة المصغرة. بيرجّع (id، الثيم بعد ما اتحدد له ملف الخلفية) */
    fun saveNew(ctx: Context, t: HomeTheme, wall: Bitmap?, thumb: Bitmap?): Pair<String, HomeTheme> {
        val id = "theme_" + System.currentTimeMillis()
        val wname = if (wall != null && ThemeWall.save(wall, File(dir(ctx), "$id.wall.jpg"))) "$id.wall.jpg" else "gradient"
        val t2 = t.copy(wall = wname)
        File(dir(ctx), "$id.joytheme").writeText(t2.toJson().toString(2))
        if (thumb != null) ThemeWall.save(thumb, thumbFile(ctx, id))
        return id to t2
    }

    /** خلفية جديدة للثيم الشغّال (زرار «غيّر الخلفية») — بيحدّث الملف المحفوظ والثيم الشغّال */
    fun setWall(ctx: Context, p: SharedPreferences, bmp: Bitmap): HomeTheme? {
        val t = cur ?: return null
        val id = curId(p)
        val name = (if (id.isNotEmpty()) id else "current") + ".wall.jpg"
        if (!ThemeWall.save(bmp, File(dir(ctx), name))) return null
        val t2 = t.copy(wall = name)
        if (id.isNotEmpty()) {
            try { File(dir(ctx), "$id.joytheme").takeIf { it.exists() }?.writeText(t2.toJson().toString(2)) } catch (_: Throwable) {}
            try { thumbFile(ctx, id).delete() } catch (_: Throwable) {}   // الصورة المصغرة تتولّد من جديد
        }
        apply(p, t2, id)
        return t2
    }

    fun delete(ctx: Context, id: String) {
        for (n in listOf("$id.joytheme", "$id.wall.jpg", "$id.thumb.jpg")) try { File(dir(ctx), n).delete() } catch (_: Throwable) {}
    }

    fun files(ctx: Context): List<File> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".joytheme") }?.toList() ?: emptyList()).sortedByDescending { it.lastModified() }

    fun read(f: File): HomeTheme? = try { HomeTheme.fromJson(JSONObject(f.readText())) } catch (_: Exception) { null }
}

// ───────── جمناي: بيصمّم ثيم عشوائي ─────────
object HomeAi {
    /** موديل Gemini اللي بيولّد ملف الثيم */
    const val MODEL = "gemini-flash-lite-latest"

    private val TOPICS = listOf(
        "wolf", "lion", "eagle", "black panther", "fox", "owl", "horse", "shark", "butterfly", "dragon",
        "desert dunes at night", "ocean waves", "tropical beach", "snowy mountains", "autumn forest", "foggy pine forest",
        "northern lights", "outer space nebula", "astronaut on the moon", "volcano", "waterfall jungle", "cherry blossom",
        "rainy city window", "Tokyo neon street", "old Cairo at sunset", "pharaonic temple", "Venice canals", "Santorini",
        "retro arcade", "cyberpunk city", "synthwave sunset", "samurai", "pirate ship", "steampunk gears",
        "chess", "vintage cars", "race track", "lighthouse storm", "hot air balloons", "coffee and books",
        "minimal geometric", "marble and gold", "stained glass", "japanese garden", "viking longship", "medieval castle",
        "deep sea jellyfish", "sunflower field", "lavender field", "city skyline at dawn"
    )

    private val SYSTEM = """
You design complete themes for an Android home-screen launcher. You get a random topic. Reply with ONE JSON object only (no markdown, no commentary). Include every key:
{
 "name": short Arabic theme name, max 24 chars, like "ثيم وولف" or "ثيم الصحرا",
 "wall_query": 2-6 precise English search words for a high-quality vertical phone wallpaper that matches the topic (no brand or character names),
 "accent": "#RRGGBB" (page dots, button outlines),
 "text": "#RRGGBB" (clock and icon labels; it sits over a dimmed wallpaper so it MUST be light),
 "dock": "#RRGGBB", "dock_alpha": 0.15-0.85,
 "drawer": "#RRGGBB" (app drawer background, dark or light),
 "icon_shape": one of circle|squircle|rounded|square|hexagon|diamond|oval|octagon|leaf|shield,
 "icon_dp": 44-68,
 "outline": {"enabled": true|false, "width": 1-4, "color": "#RRGGBB"},
 "labels": true|false, "label_sp": 9-14,
 "dock_radius": 0-44, "drawer_radius": 0-44,
 "clock_sp": 40-96,
 "clock_font": one of sans-serif|sans-serif-light|sans-serif-thin|sans-serif-condensed|sans-serif-medium|serif|monospace|casual|cursive,
 "transition": one of none|slide|zoom|fade
}
Pick colors that match the wallpaper mood and are easy on the eyes; avoid green unless the topic is green. Make the icon shape, outline and clock font fit the topic. Be creative and never repeat a previous look.
""".trimIndent()

    private class BadJson(msg: String) : Exception(msg)

    fun ready(): Boolean = try { FreeLlm.gemKey().length > 8 } catch (_: Throwable) { false }

    /** بيتنادى من ثريد خلفي. موضوع وشكل أيقونات عشوائيين؛ [avoid] = أسماء الثيمات المحفوظة (عشان ما يكررش). لو JSON باظ بيجرّب مرة تانية بس. */
    fun generate(avoid: List<String>): HomeTheme {
        val topic = TOPICS.random()
        val shape = HomeTheme.PICK_SHAPES.random()
        val ask = "Topic: " + topic + "\n" +
            "Suggested icon_shape: " + shape + " (change it only if it clashes badly with the topic)\n" +
            "Theme names already used, do not repeat: " + (if (avoid.isEmpty()) "none" else avoid.take(40).joinToString(", ")) + "\n" +
            "Design the theme now."
        var last: Exception? = null
        for (i in 0 until 2) {
            try { return parse(call(ask)) } catch (e: BadJson) { last = e }
        }
        throw IllegalStateException("جمناي رجّع JSON باظ مرتين (" + (last?.message ?: "") + ")")
    }

    private fun call(ask: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", ask)))))
            .put("generationConfig", JSONObject().put("temperature", 1.0).put("maxOutputTokens", 1200).put("responseMimeType", "application/json"))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 40000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", FreeLlm.gemKey())
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val txt = ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }) ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP " + code + " " + txt.take(160))
        try {
            return JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        } catch (e: JSONException) { throw BadJson("رد فاضي أو محجوب") }
    }

    private fun parse(out: String): HomeTheme {
        val t = out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) throw BadJson("الرد مش JSON")
        try { return HomeTheme.fromJson(JSONObject(t.substring(a, b + 1))) } catch (e: JSONException) { throw BadJson("JSON باظ") }
    }
}

// ───────── معاينة اللانشر بالثيم (حقيقية: خلفية + ساعة + أيقونات + دوك) ─────────
object ThemePreview {
    /** نسخة بحجم الشاشة الحقيقي من اللانشر بالثيم [t] (من غير ما يتطبّق فعليًا) */
    fun build(ctx: Context, t: HomeTheme, wall: Bitmap?, entries: List<Entry>, w: Int, h: Int): FrameLayout {
        val fl = FrameLayout(ctx)
        val bg = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (wall != null) setImageBitmap(wall) else setImageDrawable(ThemeWall.gradient(t))
        }
        fl.addView(bg, FrameLayout.LayoutParams(-1, -1))
        fl.addView(View(ctx).apply { setBackgroundColor(0x59000000) }, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, ctx.dp(36), 0, 0) }
        val compact = ctx.resources.configuration.screenHeightDp < 720
        val now = Date()
        val sp = if (t.clockSp > 0) (if (compact) minOf(t.clockSp, 64) else t.clockSp) else if (compact) 56 else 72
        col.addView(TextView(ctx).apply {
            text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
            textSize = sp.toFloat(); setTextColor(t.text); gravity = Gravity.CENTER; includeFontPadding = false
            setShadowLayer(10f, 0f, 2f, 0x99000000.toInt())
            typeface = Typeface.create(if (t.clockFont.isNotBlank()) t.clockFont else "sans-serif-light", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
        col.addView(TextView(ctx).apply {
            text = SimpleDateFormat("EEEE، d MMMM", Locale.getDefault()).format(now)
            textSize = 16f; setTextColor(t.text); gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
        }, LinearLayout.LayoutParams(-2, -2))

        // شبكة الأيقونات: 3 صفوف × 4
        val icPx = ctx.dp(if (t.iconDp > 0) t.iconDp else 56)
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), ctx.dp(16), ctx.dp(12), 0) }
        for (r in 0 until 3) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 4) {
                val e = entries.getOrNull(r * 4 + c)
                val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, ctx.dp(8), 0, 0) }
                val iv = HomeIcons.make(ctx, t, icPx)
                iv.setImageDrawable(e?.makeIcon() ?: ColorDrawable(0x66FFFFFF))
                cell.addView(iv)
                cell.addView(TextView(ctx).apply {
                    text = e?.title() ?: ""
                    textSize = t.labelSp; maxLines = 1; gravity = Gravity.CENTER; ellipsize = TextUtils.TruncateAt.END
                    setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), 0)
                    setTextColor(t.text); setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
                    if (!t.labels) visibility = View.GONE
                })
                row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, ctx.dp(88)))
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, -2))
        col.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))

        // نقط الصفحات (الأولى بلون التمييز)
        val dots = LinearLayout(ctx).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, ctx.dp(8)) }
        for (i in 0 until 3) {
            dots.addView(View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == 0) t.accent else 0x66FFFFFF) }
            }, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply { setMargins(ctx.dp(4), 0, ctx.dp(4), 0) })
        }
        col.addView(dots, LinearLayout.LayoutParams(-1, -2))

        // الدوك
        val dock = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER; setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(10))
            background = GradientDrawable().apply { cornerRadius = ctx.dp(t.dockRadius).toFloat(); setColor(t.dock) }
        }
        val dockPx = ctx.dp(if (t.iconDp > 0) t.iconDp.coerceIn(44, 60) else 56)
        for (i in 0 until 4) {
            val e = entries.getOrNull(12 + i) ?: entries.getOrNull(i)
            val iv = HomeIcons.make(ctx, t, dockPx)
            iv.setImageDrawable(e?.makeIcon() ?: ColorDrawable(0x66FFFFFF))
            val cell = FrameLayout(ctx)
            cell.addView(iv, FrameLayout.LayoutParams(dockPx, dockPx, Gravity.CENTER))
            dock.addView(cell, LinearLayout.LayoutParams(0, ctx.dp(58), 1f))
        }
        col.addView(dock, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ctx.dp(16), 0, ctx.dp(16), ctx.dp(8)) })

        fl.addView(col, FrameLayout.LayoutParams(-1, -1))
        fl.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        fl.layout(0, 0, w, h)
        return fl
    }

    /** صورة مصغرة (عرضها [outW]) من نفس المعاينة — للستور */
    fun render(ctx: Context, t: HomeTheme, wall: Bitmap?, entries: List<Entry>, outW: Int): Bitmap? = try {
        val m = ctx.resources.displayMetrics
        val v = build(ctx, t, wall, entries, m.widthPixels, m.heightPixels)
        val s = outW.toFloat() / m.widthPixels
        val bmp = Bitmap.createBitmap(outW, (m.heightPixels * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(s, s)
        v.draw(c)
        bmp
    } catch (e: Throwable) { LogStore.err("PhoneHome:thumb", e); null }
}

// ───────── ستور الثيمات + التوليد + المعاينة ─────────
class ThemeStudio(
    private val act: Activity,
    private val prefs: SharedPreferences,
    private val sample: () -> List<Entry>,   // تطبيقات حقيقية للمعاينة (الصفحة الأولى)
    private val onChanged: () -> Unit        // بعد تثبيت/تطبيق/إرجاع ثيم (النشاط بيعمل recreate)
) {
    private var store: Dialog? = null
    private var genBtn: TextView? = null
    private var busy = false

    private fun dp(v: Int) = act.dp(v)

    private fun round(color: Int, r: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(color)
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun btn(label: String, bg: Int, click: () -> Unit) = TextView(act).apply {
        text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(12), dp(14), dp(12), dp(14)); background = round(bg, 18)
        pressEffect(); setOnClickListener { click() }
    }

    private fun toast(s: String) { Toast.makeText(act, s, Toast.LENGTH_LONG).show() }

    private fun setBusy(b: Boolean) {
        busy = b
        genBtn?.text = if (b) "⏳ جمناي بيصمّم الثيم…" else "🎲 توليد ثيم عشوائي"
        genBtn?.alpha = if (b) .6f else 1f
    }

    // ----- الستور -----
    fun openStore() {
        store?.dismiss()
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF101014.toInt()); setPadding(dp(16), dp(40), dp(16), dp(12))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(act).apply {
            text = "🎨 ستور الثيمات"; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(act).apply {
            text = "✕"; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { d.dismiss() }
        }, LinearLayout.LayoutParams(-2, -2))
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        val gen = btn("🎲 توليد ثيم عشوائي", 0xFF7C4DFF.toInt()) { generate() }
        genBtn = gen
        col.addView(gen, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        col.addView(TextView(act).apply {
            text = "↩ رجوع للثيم الافتراضي"; textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12)); pressEffect()
            setOnClickListener { HomeThemeStore.clear(prefs); d.dismiss(); onChanged() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val curId = HomeThemeStore.curId(prefs)
        val items = HomeThemeStore.files(act).mapNotNull { f -> HomeThemeStore.read(f)?.let { Triple(f, HomeThemeStore.idOf(f), it) } }
        if (items.isEmpty()) {
            list.addView(TextView(act).apply {
                text = "مفيش ثيمات لسه — دوس «توليد ثيم عشوائي»"
                setTextColor(0xAAFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(50), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        for (pair in items.chunked(2)) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (tr in pair) {
                row.addView(card(d, tr.first, tr.second, tr.third, tr.second == curId),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, dp(5), dp(10)) })
            }
            if (pair.size == 1) row.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        val sv = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        sv.addView(list, FrameLayout.LayoutParams(-1, -2))
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        d.setContentView(col)
        d.setOnDismissListener { if (store === d) { store = null; genBtn = null } }
        d.show()
        store = d
        if (busy) setBusy(true)
    }

    private fun card(d: Dialog, f: File, id: String, t: HomeTheme, isCur: Boolean): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(if (isCur) 0x30FFFFFF else 0x14FFFFFF, 16, if (isCur) 2 else 0, t.accent)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val cw = (act.resources.displayMetrics.widthPixels - dp(32) - dp(20)) / 2 - dp(16)
        val img = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat()) }
            }
        }
        thumb(id, t)?.let { img.setImageBitmap(it) }
        box.addView(img, LinearLayout.LayoutParams(-1, (cw * 1.5f).toInt()))
        box.addView(TextView(act).apply {
            text = (if (isCur) "✓ " else "") + t.name; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, dp(4))
        })
        fun small(label: String, c: () -> Unit) = TextView(act).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8)); background = round(0x22FFFFFF, 12); pressEffect(); setOnClickListener { c() }
        }
        val acts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        acts.addView(small("طبّق") { HomeThemeStore.apply(prefs, t, id); d.dismiss(); onChanged() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3) })
        acts.addView(small("شارك") {
            try {
                act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, f.readText()), "شارك الثيم"))
            } catch (e: Throwable) { LogStore.err("PhoneHome:share", e) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3) })
        acts.addView(small("احذف") {
            AlertDialog.Builder(act).setTitle("تحذف «" + t.name + "»؟")
                .setPositiveButton("احذف") { _, _ ->
                    HomeThemeStore.delete(act, id)
                    if (id == HomeThemeStore.curId(prefs)) { HomeThemeStore.clear(prefs); d.dismiss(); onChanged() } else openStore()
                }
                .setNegativeButton("إلغاء", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(acts, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    /** الصورة المصغرة للثيم — لو مش موجودة (ثيمات v774 القديمة) بتتولّد وتتحفظ */
    private fun thumb(id: String, t: HomeTheme): Bitmap? {
        val tf = HomeThemeStore.thumbFile(act, id)
        if (tf.exists()) {
            val b = try { BitmapFactory.decodeFile(tf.path) } catch (_: Throwable) { null }
            if (b != null) return b
        }
        val wb = HomeThemeStore.wallFile(act, t)?.let { ThemeWall.decodeFile(it, 1080) }
        val b = ThemePreview.render(act, t, wb, sample(), 270) ?: return null
        ThemeWall.save(b, tf)
        return b
    }

    // ----- التوليد -----
    fun generate() {
        if (busy) return
        if (!HomeAi.ready()) { toast("محتاج مفتاح Gemini مضاف في Joyboy"); return }
        setBusy(true)
        val avoid = HomeThemeStore.files(act).mapNotNull { HomeThemeStore.read(it)?.name }
        Thread {
            var th: HomeTheme? = null
            var wall: Bitmap? = null
            var err = ""
            try {
                val t0 = HomeAi.generate(avoid)
                th = t0
                wall = try { ThemeWall.fetch(t0.wallQuery) } catch (e: Throwable) { LogStore.err("PhoneHome:wall", e); null }
            } catch (e: Throwable) {
                LogStore.err("PhoneHome:ai", e)
                err = e.message ?: e.javaClass.simpleName
            }
            val t1 = th
            val w1 = wall
            act.runOnUiThread {
                setBusy(false)
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (t1 == null) { toast("مقدرتش أولّد الثيم: " + err.take(140)); return@runOnUiThread }
                showPreview(t1, w1)
            }
        }.apply { isDaemon = true }.start()
    }

    // ----- المعاينة -----
    private fun showPreview(t: HomeTheme, wall: Bitmap?) {
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val m = act.resources.displayMetrics
        val w = m.widthPixels; val h = m.heightPixels
        val full = ThemePreview.build(act, t, wall, sample(), w, h)
        val availW = w - dp(24)
        val availH = h - dp(28) - dp(12) - dp(44) - dp(84)
        val s = minOf(availW.toFloat() / w, availH.toFloat() / h)

        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF0B0B0E.toInt()); setPadding(dp(12), dp(28), dp(12), dp(12))
        }
        col.addView(TextView(act).apply {
            text = "🎨 " + t.name + if (wall == null) "  (خلفية تدرّج)" else ""
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(44)))
        val holder = FrameLayout(act).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(22).toFloat()) }
            }
        }
        full.pivotX = 0f; full.pivotY = 0f; full.scaleX = s; full.scaleY = s
        holder.addView(full, FrameLayout.LayoutParams(w, h))
        col.addView(holder, LinearLayout.LayoutParams((w * s).toInt(), (h * s).toInt()))
        col.addView(View(act), LinearLayout.LayoutParams(1, 0, 1f))

        val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(btn("✅ تثبيت", 0xFF2E9E5B.toInt()) { commit(d, t, wall) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        btns.addView(btn("❌ إلغاء", 0xFF9E3B3B.toInt()) { d.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        btns.addView(btn("🔄 توليد آخر", 0xFF4A4A56.toInt()) { d.dismiss(); generate() }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(btns, LinearLayout.LayoutParams(-1, -2))

        d.setContentView(col)
        d.show()
    }

    private fun commit(d: Dialog, t: HomeTheme, wall: Bitmap?) {
        try {
            val thumb = ThemePreview.render(act, t, wall, sample(), 270)
            val (id, t2) = HomeThemeStore.saveNew(act, t, wall, thumb)
            HomeThemeStore.apply(prefs, t2, id)
        } catch (e: Throwable) {
            LogStore.err("PhoneHome:commit", e)
            toast("مقدرتش أثبّت الثيم: " + (e.message ?: "").take(100))
            return
        }
        d.dismiss(); store?.dismiss()
        onChanged()
    }

    // ----- «غيّر الخلفية» لما الثيم له خلفية: بيجيب صورة تانية بنفس كلمات البحث -----
    fun nextWall(t: HomeTheme, done: () -> Unit) {
        if (t.wallQuery.isBlank()) { toast("الثيم ده مالوش كلمات بحث للخلفية"); return }
        Toast.makeText(act, "بجيب خلفية جديدة…", Toast.LENGTH_SHORT).show()
        Thread {
            val bmp = try { ThemeWall.fetch(t.wallQuery) } catch (e: Throwable) { LogStore.err("PhoneHome:wall", e); null }
            act.runOnUiThread {
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (bmp == null || HomeThemeStore.setWall(act, prefs, bmp) == null) { toast("مقدرتش أنزّل خلفية جديدة (اتأكد من النت)"); return@runOnUiThread }
                done()
            }
        }.apply { isDaemon = true }.start()
    }
}
