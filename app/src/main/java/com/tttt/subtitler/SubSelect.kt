package com.tttt.subtitler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v1137) اختيار ملف الترجمة للفيديو:
 *  - كل الملفات اللي جنب الفيديو (والمستوردة بزرار ➕) بترتيب الأحدث الأول — مفيش اختيار صامت لو فيه أكتر من ملف.
 *  - اختيار المستخدم بيتحفظ لكل فيديو (Cfg subsel:<hash>): مسار الملف، أو "none" = بدون ترجمة.
 *  - الملفات المستوردة بتتنسخ في filesDir/ext_subs/<hash>/ (الفولدر اللي جنب الفيديو ممكن مايكونش قابل للكتابة).
 */
object SubSel {
    const val NONE = "none"
    val EXTS = listOf("srt", "ass", "ssa", "vtt", "ttml")

    /** مسار الفيديو الحقيقي (file:// أو content:// ليه عمود DATA) — null = مش معروف */
    fun videoPath(ctx: Context, u: Uri): String? = try {
        var path: String? = null
        if (u.scheme == "file") path = u.path
        else if (u.scheme == "content") ctx.contentResolver.query(u, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) path = c.getString(0) }
        path
    } catch (_: Throwable) { null }

    private fun hash(p: String): String = Integer.toHexString(p.hashCode())
    private fun choiceKey(ctx: Context, u: Uri): String? = videoPath(ctx, u)?.let { "subsel:" + hash(it) }
    fun extDir(ctx: Context, u: Uri): File? = videoPath(ctx, u)?.let { File(File(ctx.filesDir, "ext_subs"), hash(it)) }

    /** الملفات اللي اتستوردت يدويًا لفيديو ده */
    fun imported(ctx: Context, u: Uri): List<File> = try {
        extDir(ctx, u)?.listFiles()?.filter { it.isFile && it.extension.lowercase() in EXTS }.orEmpty()
    } catch (_: Throwable) { emptyList() }

    /** "" = مفيش اختيار محفوظ · NONE = بدون ترجمة · غير كده = مسار الملف */
    fun choice(ctx: Context, u: Uri): String = try { choiceKey(ctx, u)?.let { Cfg.str(it, "") } ?: "" } catch (_: Throwable) { "" }
    fun setChoice(ctx: Context, u: Uri, v: String) { try { choiceKey(ctx, u)?.let { Cfg.put(it, v) } } catch (e: Throwable) { LogStore.err("SubSel:set", e) } }

    /** الملف المختار: null = بدون ترجمة (أو مفيش ملف مطابق). اختيار المستخدم المحفوظ دايمًا محترم (حتى لو الاسم مش مطابق) · من غير اختيار محفوظ ← الأحدث *المطابق* بس */
    fun chosen(ctx: Context, u: Uri, all: List<File>): File? {
        val c = choice(ctx, u)
        if (c == NONE) return null
        if (c.isNotEmpty()) all.firstOrNull { it.absolutePath == c }?.let { return it }
        val vb = videoPath(ctx, u)?.let { File(it).nameWithoutExtension } ?: return all.firstOrNull()   // (v1353) مسار الفيديو مش معروف ← السلوك القديم (الأحدث)
        return all.firstOrNull { strictAuto(vb, it.nameWithoutExtension) }   // (v1353) الاختيار الصامت بقى صارم: نفس الاسم أو نفس توكن الحلقة بس — ممنوع يلقط أي ملف ترجمة من الفولدر (بطلب علي)
    }

    /** (v1353) توكن الحلقة في الاسم: S07E04 أو 7x04 ← زوج (الموسم، الحلقة) للمقارنة الصارمة (بطلب علي) */
    fun epToken(x: String): Pair<Int, Int>? {
        val m = Regex("[sS](\\d{1,3})[eE](\\d{1,4})").find(x) ?: Regex("(?<!\\d)(\\d{1,2})x(\\d{1,3})(?!\\d)").find(x) ?: return null
        val s = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return s to e
    }
    private fun normTok(x: String): String = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
    /** (v1353) مطابقة صارمة للاختيار التلقائي الصامت: نفس اسم الفيديو (مع لاحقة لغة زي .ar) أو —لو الفيديو فيه توكن حلقة— نفس التوكن بالظبط؛ لو الفيديو مفيهوش توكن حلقة ← قاعدة الرقم الأخير القديمة (v667/v703) زي ما هي (بطلب علي) */
    fun strictAuto(videoBase: String, subBase: String): Boolean {
        val base = videoBase.lowercase(); val n = subBase.lowercase()
        if (n == base || n.startsWith("$base.")) return true
        val nb = normTok(base); val nn = normTok(subBase)
        if (nb.isNotEmpty() && nn.startsWith(nb)) {
            val rest = nn.substring(nb.length)
            if (rest.isEmpty() || Regex("^(?:ar|ara|arb|arabic|عربي|عربى|en|eng|english|fr|fre|de|ger|es|spa|tr|tur|hi|ja|jpn|ko|kor|ru|rus|it|ita|pt|por|sub|subs|subtitle|subtitles|cc|sdh|forced|default|translated|ترجمه|ترجمة)+$").matches(rest)) return true
        }
        val ev = epToken(videoBase)
        if (ev != null) return epToken(subBase) == ev   // (v1353) فولدر مسلسلات: الحلقة بتتطابق بتوكنها — «720» في آخر الاسم كانت بتخلي كل الحلقات تلقط ترجمات بعض (بطلب علي)
        fun digits(x: String): String = buildString { for (ch in x) append(if (ch in '\u0660'..'\u0669') ('0' + (ch - '\u0660')) else if (ch in '\u06F0'..'\u06F9') ('0' + (ch - '\u06F0')) else ch) }
        fun lastNum(x: String): Long? = Regex("\\d+").findAll(digits(x)).lastOrNull()?.value?.toLongOrNull()
        val vn = lastNum(videoBase) ?: return false
        if (lastNum(subBase) != vn) return false
        val vl = normTok(videoBase.filter { !it.isDigit() })
        if (vl.isEmpty()) return true
        val sl = normTok(subBase.filter { !it.isDigit() })
        return sl.isNotEmpty() && (sl.contains(vl) || vl.contains(sl))
    }

    /** أكتر من ملف ومفيش اختيار صالح محفوظ ← لازم نسأل المستخدم (مفيش اختيار تلقائي صامت) */
    fun needsChoice(ctx: Context, u: Uri, all: List<File>): Boolean {
        if (all.size < 2) return false
        val c = choice(ctx, u)
        return !(c == NONE || (c.isNotEmpty() && all.any { it.absolutePath == c }))
    }

    /** نسخ ملف مختار من بره لفولدر التطبيق وإرجاعه (أحدث ملف فبيبقى الأول). null = مش ترجمة صالحة */
    fun importFrom(ctx: Context, u: Uri, src: Uri): File? {
        return try {
            val p = SubPick.read(ctx, src) ?: return null
            val dir = extDir(ctx, u) ?: return null
            dir.mkdirs()
            val safe = p.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "subtitle.srt" }
            val nm = if (safe.substringAfterLast('.', "").lowercase() in EXTS) safe else "$safe.srt"
            val out = File(dir, nm)
            out.writeText(p.text, Charsets.UTF_8)
            out.setLastModified(System.currentTimeMillis())
            out
        } catch (e: Throwable) { LogStore.err("SubSel:import", e); null }
    }

    fun whenOf(f: File): String = try { SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(f.lastModified())) } catch (_: Throwable) { "" }

    fun isImported(ctx: Context, u: Uri, f: File): Boolean = try { f.parentFile == extDir(ctx, u) } catch (_: Throwable) { false }
}

/** (v1137) زرار اختيار ملف الترجمة برقم عدد الملفات المتاحة — مرسوم باليد (من غير إيموجي/أيقونات) · (v1138) الأيقونة بقت قايمة ملفات */
class SubBadgeView(ctx: Context) : View(ctx) {
    var count = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var off = false          // الاختيار الحالي «بدون ترجمة»
        set(v) { if (field != v) { field = v; invalidate() } }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val rc = RectF()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h)
        rc.set(w / 2f - s * 0.30f, h / 2f - s * 0.20f, w / 2f + s * 0.30f, h / 2f + s * 0.22f)
        strokeP.strokeWidth = s * 0.06f
        strokeP.alpha = if (off) 120 else 255
        c.drawRoundRect(rc, s * 0.08f, s * 0.08f, strokeP)
        // (v1138) بقى أيقونة «قايمة ملفات» (3 سطور) بدل كلمة CC — عشان CC بقى زرار إظهار/إخفاء الترجمة لوحده
        strokeP.strokeWidth = s * 0.05f; strokeP.strokeCap = Paint.Cap.ROUND
        val lx0 = rc.left + rc.width() * 0.24f; val lx1 = rc.right - rc.width() * 0.24f
        for (i in -1..1) { val ly = rc.centerY() + i * rc.height() * 0.24f; c.drawLine(lx0, ly, lx1, ly, strokeP) }
        txtP.alpha = if (off) 120 else 255
        if (count > 0) {
            val r = s * 0.2f; val cx = rc.right; val cy = rc.top
            fillP.color = if (off) 0xFF8A8A8A.toInt() else 0xFFE53935.toInt()
            c.drawCircle(cx, cy, r, fillP)
            val t = if (count > 9) "9+" else count.toString()
            txtP.alpha = 255; txtP.textSize = r * 1.25f
            c.drawText(t, cx, cy + r * 0.42f, txtP)
        }
    }
}

/** (v1137) أيقونة ⏸ / ▶ مرسومة باليد (زي StopSquareView) — الإيموجي/الأيقونة كانت بتظهر شفافة في بعض الأجهزة/الباكات */
class PausePlayView(ctx: Context) : View(ctx) {
    var playing = false   // true = الترجمة ماشية (نعرض ⏸)، false = واقفة (نعرض ▶)
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val rc = RectF()
    private val path = android.graphics.Path()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sz = minOf(w, h) * 0.46f
        val cx = w / 2f; val cy = h / 2f
        if (playing) {
            val bw = sz * 0.34f; val gap = sz * 0.32f
            rc.set(cx - gap / 2f - bw, cy - sz / 2f, cx - gap / 2f, cy + sz / 2f); c.drawRoundRect(rc, bw * 0.25f, bw * 0.25f, p)
            rc.set(cx + gap / 2f, cy - sz / 2f, cx + gap / 2f + bw, cy + sz / 2f); c.drawRoundRect(rc, bw * 0.25f, bw * 0.25f, p)
        } else {
            path.reset()
            path.moveTo(cx - sz * 0.32f, cy - sz / 2f); path.lineTo(cx + sz * 0.5f, cy); path.lineTo(cx - sz * 0.32f, cy + sz / 2f); path.close()
            c.drawPath(path, p)
        }
    }
}

/** (v1137) علامة ➕ مرسومة باليد — لاستيراد ملف ترجمة من بره */
class PlusView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h); val a = s * 0.22f
        p.strokeWidth = s * 0.09f
        c.drawLine(w / 2f - a, h / 2f, w / 2f + a, h / 2f, p)
        c.drawLine(w / 2f, h / 2f - a, w / 2f, h / 2f + a, p)
    }
}

/** (v1138) زرار CC إظهار/إخفاء الترجمة — مرسوم باليد · شغّال: مربع أبيض منوّر وCC غامق · مقفول: إطار باهت وخط مايل على CC */
class CcToggleView(ctx: Context) : View(ctx) {
    var on = true
        set(v) { if (field != v) { field = v; postInvalidate() } }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val cutP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF363636.toInt() }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFFECEFF1.toInt() }
    private val rc = RectF()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h)
        rc.set(w / 2f - s * 0.31f, h / 2f - s * 0.22f, w / 2f + s * 0.31f, h / 2f + s * 0.22f)
        txtP.textSize = s * 0.27f
        if (on) {
            fillP.color = Color.WHITE
            c.drawRoundRect(rc, s * 0.09f, s * 0.09f, fillP)
            txtP.color = 0xFF141414.toInt()
        } else {
            strokeP.strokeWidth = s * 0.055f; strokeP.color = 0xFFA8A8A8.toInt()
            c.drawRoundRect(rc, s * 0.09f, s * 0.09f, strokeP)
            txtP.color = 0xFFA8A8A8.toInt()
        }
        c.drawText("CC", rc.centerX(), rc.centerY() + s * 0.095f, txtP)
        if (!on) {
            val x0 = rc.left - s * 0.04f; val y0 = rc.bottom + s * 0.06f
            val x1 = rc.right + s * 0.04f; val y1 = rc.top - s * 0.06f
            cutP.strokeWidth = s * 0.15f; c.drawLine(x0, y0, x1, y1, cutP)   // حز غامق بلون الزرار عشان الخط يبان فوق الحروف
            lineP.strokeWidth = s * 0.065f; c.drawLine(x0, y0, x1, y1, lineP)
        }
    }
}
