package com.tttt.subtitler

import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * (v598 م3) محرك Icon Packs — الجزء النقي (من غير أي اعتماد على أندرويد، بيتختبر على JVM).
 *  - IconNames: أسماء الأيقونات الأساسية + الأسماء البديلة (settings = gear …)
 *  - SvgPath / SvgDoc: قراءة مسارات SVG كاملة (M L H V C S Q T A Z مطلق ونسبي) وأشكال (rect/circle/ellipse/line/polyline/polygon) و transform و viewBox
 *  - IconPackReader: zip (pack.json + SVG) ← IconPackData + تقرير تحقق (PackReport) بأخطاء/تحذيرات
 *  - IconPackWriter: تصدير قالب (الأيقونات الافتراضية كملفات SVG) عشان أي حد يعدّل عليه
 * الرسم نفسه (Canvas) في Icons.kt؛ التخزين والتبديل في IconPacks.kt.
 */

/** مسار مرسوم: أوامر (0=M 1=L 2=C 3=Z) + إحداثيات مطلقة. */
class PathOps(val codes: ByteArray, val pts: FloatArray) {
    val size: Int get() = codes.size
    fun isEmpty() = codes.isEmpty()
    companion object { const val M: Byte = 0; const val L: Byte = 1; const val C: Byte = 2; const val Z: Byte = 3 }
}

/** طبقة أيقونة بصيغة مستقلة عن أندرويد */
class IconLayerData(
    val fill: Boolean,
    val stroke: Boolean,
    /** لون ثابت ARGB، أو (hasColor=false) لون النص */
    val color: Int,
    val hasColor: Boolean,
    val fillAlpha: Float,
    val strokeAlpha: Float,
    val strokeWidth: Float,
    /** 0 = butt · 1 = round · 2 = square */
    val cap: Int,
    /** 0 = miter · 1 = round · 2 = bevel */
    val join: Int,
    val evenOdd: Boolean,
    val ops: PathOps
)

/** باك أيقونات جاهز للرسم */
class IconPackData(
    val id: String,
    val name: String,
    val author: String,
    val version: Int,
    val description: String,
    /** نصف قطر تدوير الزوايا (وحدات 24×24) — 0 = من غير */
    val cornerRadius: Float,
    val builtin: Boolean,
    val icons: Map<String, List<IconLayerData>>
) {
    fun has(name: String) = icons.containsKey(name)
    fun layers(name: String): List<IconLayerData>? = icons[name]
    val coverage: Int get() = icons.size
}

object IconNames {
    /** أسماء بديلة مقبولة في ملفات الباكات وفي نداءات السكِنز ← الاسم الأساسي (من IconDefs) */
    val ALIASES: Map<String, String> = mapOf(
        "settings" to "gear", "setting" to "gear", "cog" to "gear", "options" to "gear",
        "theme" to "palette", "themes" to "palette", "colors" to "palette",
        "notification" to "bell", "notifications" to "bell", "bell_on" to "bell",
        "close" to "cross", "x" to "cross", "add" to "plus", "delete" to "trash", "remove" to "trash",
        "edit" to "pencil", "volume" to "vol", "time" to "clock", "tv" to "subs", "refresh_cw" to "refresh",
        "more" to "more_h", "kebab" to "more_v", "list" to "menu", "hamburger" to "menu",
        "back" to "chev_l", "forward" to "chev_r", "magnify" to "search", "find" to "search", "user" to "person",
        "videos" to "film", "video" to "film", "music" to "note", "favorite" to "heart", "favourite" to "heart"
    )

    /** الاسم الأساسي: حروف صغيرة · «-» و«space» ← «_» · بعدين الاسم البديل لو فيه */
    fun canon(raw: String): String {
        val n = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
        return ALIASES[n] ?: n
    }

    fun known(): Set<String> = IconDefs.names.toSet()
    fun isKnown(name: String) = IconDefs.DEF.containsKey(name) && !name.contains('@')
}

/** مصفوفة تحويل 2D (a b c d e f زي SVG) */
internal class Mat(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    /** this ∘ o  (بيطبّق o الأول ثم this) */
    fun mul(o: Mat) = Mat(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f)
    fun x(px: Float, py: Float) = a * px + c * py + e
    fun y(px: Float, py: Float) = b * px + d * py + f
    /** مقياس الخط: جذر |المحدد| */
    fun scale(): Float = sqrt(abs(a * d - b * c)).let { if (it.isNaN() || it == 0f) 1f else it }
    companion object { val ID = Mat(1f, 0f, 0f, 1f, 0f, 0f) }
}

/** قارئ مسارات SVG الكامل */
object SvgPath {
    private class Cur(val d: String) { var i = 0 }

    /** بيرمي IllegalArgumentException برسالة عربي لو المسار غلط */
    fun parse(d: String): PathOps = parse(d, Mat.ID)

    internal fun parse(d: String, m: Mat): PathOps {
        val codes = ArrayList<Byte>(); val pts = ArrayList<Float>()
        fun mv(x: Float, y: Float) { codes.add(PathOps.M); pts.add(m.x(x, y)); pts.add(m.y(x, y)) }
        fun ln(x: Float, y: Float) { codes.add(PathOps.L); pts.add(m.x(x, y)); pts.add(m.y(x, y)) }
        fun cu(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
            codes.add(PathOps.C)
            pts.add(m.x(x1, y1)); pts.add(m.y(x1, y1)); pts.add(m.x(x2, y2)); pts.add(m.y(x2, y2)); pts.add(m.x(x, y)); pts.add(m.y(x, y))
        }
        fun cl() { codes.add(PathOps.Z) }

        val cur = Cur(d)
        var cx = 0f; var cy = 0f          // النقطة الحالية
        var sx = 0f; var sy = 0f          // أول نقطة في المسار الفرعي
        var lcx = 0f; var lcy = 0f        // آخر نقطة تحكم (للانعكاس S/T)
        var lastCmd = ' '
        var cmd = ' '
        var first = true
        val n = d.length
        while (true) {
            skipSep(cur)
            if (cur.i >= n) break
            val ch = d[cur.i]
            if (ch.isLetter()) { cmd = ch; cur.i++ } else if (cmd == ' ') throw IllegalArgumentException("المسار لازم يبدأ بأمر (M)")
            else if (cmd == 'Z' || cmd == 'z') throw IllegalArgumentException("رقم بعد Z عند الموضع " + cur.i)
            else if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'   // أرقام زيادة بعد moveto = lineto
            if (first && cmd != 'M' && cmd != 'm') throw IllegalArgumentException("المسار لازم يبدأ بـ M")
            first = false
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> { val x = num(cur); val y = num(cur); cx = if (rel) cx + x else x; cy = if (rel) cy + y else y; sx = cx; sy = cy; mv(cx, cy); lcx = cx; lcy = cy }
                'L' -> { val x = num(cur); val y = num(cur); cx = if (rel) cx + x else x; cy = if (rel) cy + y else y; ln(cx, cy); lcx = cx; lcy = cy }
                'H' -> { val x = num(cur); cx = if (rel) cx + x else x; ln(cx, cy); lcx = cx; lcy = cy }
                'V' -> { val y = num(cur); cy = if (rel) cy + y else y; ln(cx, cy); lcx = cx; lcy = cy }
                'C' -> {
                    var x1 = num(cur); var y1 = num(cur); var x2 = num(cur); var y2 = num(cur); var x = num(cur); var y = num(cur)
                    if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy }
                    cu(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2; cx = x; cy = y
                }
                'S' -> {
                    var x2 = num(cur); var y2 = num(cur); var x = num(cur); var y = num(cur)
                    if (rel) { x2 += cx; y2 += cy; x += cx; y += cy }
                    val prevC = lastCmd.uppercaseChar() == 'C' || lastCmd.uppercaseChar() == 'S'
                    val x1 = if (prevC) 2 * cx - lcx else cx; val y1 = if (prevC) 2 * cy - lcy else cy
                    cu(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2; cx = x; cy = y
                }
                'Q' -> {
                    var qx = num(cur); var qy = num(cur); var x = num(cur); var y = num(cur)
                    if (rel) { qx += cx; qy += cy; x += cx; y += cy }
                    cu(cx + 2f / 3f * (qx - cx), cy + 2f / 3f * (qy - cy), x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y), x, y)
                    lcx = qx; lcy = qy; cx = x; cy = y
                }
                'T' -> {
                    var x = num(cur); var y = num(cur)
                    if (rel) { x += cx; y += cy }
                    val prevQ = lastCmd.uppercaseChar() == 'Q' || lastCmd.uppercaseChar() == 'T'
                    val qx = if (prevQ) 2 * cx - lcx else cx; val qy = if (prevQ) 2 * cy - lcy else cy
                    cu(cx + 2f / 3f * (qx - cx), cy + 2f / 3f * (qy - cy), x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y), x, y)
                    lcx = qx; lcy = qy; cx = x; cy = y
                }
                'A' -> {
                    val rx = num(cur); val ry = num(cur); val rot = num(cur); val laf = flag(cur); val sf = flag(cur)
                    var x = num(cur); var y = num(cur)
                    if (rel) { x += cx; y += cy }
                    arcToCubics(cx, cy, rx, ry, rot, laf, sf, x, y) { a, b, c2, d2, e, f -> cu(a, b, c2, d2, e, f) }
                    cx = x; cy = y; lcx = cx; lcy = cy
                }
                'Z' -> { cl(); cx = sx; cy = sy; lcx = cx; lcy = cy }
                else -> throw IllegalArgumentException("أمر مش مدعوم: " + cmd)
            }
            lastCmd = cmd
            if (codes.size > 60000) throw IllegalArgumentException("المسار طويل زيادة")
        }
        if (codes.isEmpty()) throw IllegalArgumentException("المسار فاضي")
        return PathOps(codes.toByteArray(), pts.toFloatArray())
    }

    private fun skipSep(c: Cur) { val d = c.d; while (c.i < d.length && (d[c.i] == ' ' || d[c.i] == ',' || d[c.i] == '\n' || d[c.i] == '\r' || d[c.i] == '\t')) c.i++ }

    private fun num(c: Cur): Float {
        skipSep(c)
        val d = c.d; val s = c.i; var i = c.i; val n = d.length
        if (i < n && (d[i] == '-' || d[i] == '+')) i++
        var digits = 0
        while (i < n && d[i].isDigit()) { i++; digits++ }
        if (i < n && d[i] == '.') { i++; while (i < n && d[i].isDigit()) { i++; digits++ } }
        if (digits == 0) throw IllegalArgumentException("رقم متوقع عند الموضع " + s)
        if (i < n && (d[i] == 'e' || d[i] == 'E')) {
            var j = i + 1
            if (j < n && (d[j] == '-' || d[j] == '+')) j++
            val ds = j
            while (j < n && d[j].isDigit()) j++
            if (j > ds) i = j
        }
        c.i = i
        val v = d.substring(s, i).toFloatOrNull() ?: throw IllegalArgumentException("رقم غلط عند الموضع " + s)
        if (v.isNaN() || v.isInfinite() || abs(v) > 1.0e6f) throw IllegalArgumentException("رقم خارج الحدود")
        return v
    }

    /** علم القوس: خانة واحدة 0/1 (ممكن يكون ملزوق في اللي بعده زي «a1 1 0 011 1») */
    private fun flag(c: Cur): Boolean {
        skipSep(c)
        if (c.i >= c.d.length) throw IllegalArgumentException("علم قوس ناقص")
        val ch = c.d[c.i]
        if (ch != '0' && ch != '1') throw IllegalArgumentException("علم القوس لازم 0 أو 1")
        c.i++
        return ch == '1'
    }

    /** SVG F.6.5: قوس بيضاوي ← مقاطع Bézier تكعيبية (كل مقطع ≤ 90°) */
    internal fun arcToCubics(x0: Float, y0: Float, rx0: Float, ry0: Float, rotDeg: Float, large: Boolean, sweep: Boolean,
                             x: Float, y: Float, out: (Float, Float, Float, Float, Float, Float) -> Unit) {
        var rx = abs(rx0.toDouble()); var ry = abs(ry0.toDouble())
        if (x0 == x && y0 == y) return
        if (rx == 0.0 || ry == 0.0) { out(x0, y0, x, y, x, y); return }   // قوس صفري = خط مستقيم
        val phi = Math.toRadians(rotDeg.toDouble()); val cp = cos(phi); val sp = sin(phi)
        val dx2 = (x0 - x) / 2.0; val dy2 = (y0 - y) / 2.0
        val x1p = cp * dx2 + sp * dy2; val y1p = -sp * dx2 + cp * dy2
        val lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lam > 1) { val s = sqrt(lam); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var co = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
        if (large == sweep) co = -co
        val cxp = co * (rx * y1p / ry); val cyp = co * -(ry * x1p / rx)
        val cx = cp * cxp - sp * cyp + (x0 + x) / 2.0; val cy = sp * cxp + cp * cyp + (y0 + y) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy; val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            var a = acos((dot / len).coerceIn(-1.0, 1.0)); if (ux * vy - uy * vx < 0) a = -a; return a
        }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * Math.PI else if (sweep && dth < 0) dth += 2 * Math.PI
        val segs = max(1, ceil(abs(dth) / (Math.PI / 2) - 1e-9).toInt())
        val delta = dth / segs
        val t = 4.0 / 3.0 * tan(delta / 4.0)
        var a = th1
        for (k in 0 until segs) {
            val cosA = cos(a); val sinA = sin(a); val cosB = cos(a + delta); val sinB = sin(a + delta)
            // نقط التحكم في فضاء الدايرة الوحدة ثم مدّ + لف + إزاحة
            val e1x = cosA - t * sinA; val e1y = sinA + t * cosA
            val e2x = cosB + t * sinB; val e2y = sinB - t * cosB
            fun px(ux: Double, uy: Double) = (cp * rx * ux - sp * ry * uy + cx).toFloat()
            fun py(ux: Double, uy: Double) = (sp * rx * ux + cp * ry * uy + cy).toFloat()
            val ex = if (k == segs - 1) x else px(cosB, sinB); val ey = if (k == segs - 1) y else py(cosB, sinB)
            out(px(e1x, e1y), py(e1x, e1y), px(e2x, e2y), py(e2x, e2y), ex, ey)
            a += delta
        }
    }
}

/** (تقرير) مستوى الرسالة */
enum class PackSev { ERROR, WARN, INFO }

class PackIssue(val sev: PackSev, val file: String?, val msg: String)

/** تقرير فحص باك: أخطاء بتمنع الاستيراد · تحذيرات بتتعرض بس · معلومات */
class PackReport {
    val issues = ArrayList<PackIssue>()
    var iconsAccepted = 0
    var iconsIgnored = 0
    fun err(file: String?, msg: String) { issues.add(PackIssue(PackSev.ERROR, file, msg)) }
    fun warn(file: String?, msg: String) { issues.add(PackIssue(PackSev.WARN, file, msg)) }
    fun info(file: String?, msg: String) { issues.add(PackIssue(PackSev.INFO, file, msg)) }
    val errors: List<PackIssue> get() = issues.filter { it.sev == PackSev.ERROR }
    val warnings: List<PackIssue> get() = issues.filter { it.sev == PackSev.WARN }
    val ok: Boolean get() = issues.none { it.sev == PackSev.ERROR }

    fun toText(maxLines: Int = 40): String {
        val sb = StringBuilder()
        val ordered = issues.sortedBy { it.sev.ordinal }
        var shown = 0
        for (it in ordered) {
            if (shown >= maxLines) { sb.append("… و").append(ordered.size - shown).append(" رسالة تانية\n"); break }
            sb.append(when (it.sev) { PackSev.ERROR -> "❌ "; PackSev.WARN -> "⚠ "; PackSev.INFO -> "ℹ " })
            if (it.file != null) sb.append(it.file).append(" — ")
            sb.append(it.msg).append('\n')
            shown++
        }
        return sb.toString().trimEnd()
    }
}

class PackReadResult(val pack: IconPackData?, val report: PackReport)

/** قارئ SVG ← طبقات */
internal object SvgDoc {
    const val MAX_LAYERS = 48
    const val MAX_OPS = 24000
    private const val MAX_DEPTH = 14

    private val NAMED = mapOf(
        "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(), "green" to 0xFF008000.toInt(), "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(), "orange" to 0xFFFFA500.toInt(), "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(),
        "lime" to 0xFF00FF00.toInt(), "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(), "pink" to 0xFFFFC0CB.toInt(),
        "purple" to 0xFF800080.toInt(), "gold" to 0xFFFFD700.toInt(), "silver" to 0xFFC0C0C0.toInt()
    )

    /** قيمة طلاء: none / tint (لون النص) / لون ثابت */
    private class Paint(val none: Boolean, val tint: Boolean, val argb: Int, val alpha: Float)

    private class St(
        val fill: String?, val stroke: String?, val sw: Float?, val fo: Float, val so: Float, val op: Float,
        val cap: Int, val join: Int, val evenOdd: Boolean
    )

    private class Ctx(val file: String?, val report: PackReport, val defSw: Float) {
        val layers = ArrayList<IconLayerData>(); var ops = 0; var warnedGrad = false; val warnedTags = HashSet<String>()
    }

    /** بيرجّع الطبقات، أو null لو فشل (الخطأ في التقرير). */
    fun parse(bytes: ByteArray, file: String, report: PackReport, defSw: Float): List<IconLayerData>? {
        val head = try { String(bytes, 0, min(bytes.size, 4096), Charsets.UTF_8).lowercase() } catch (_: Throwable) { "" }
        if (head.contains("<!doctype") || head.contains("<!entity")) { report.err(file, "فيه DOCTYPE/ENTITY — مش مسموح (أمان)"); return null }
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Throwable) {}
            try { f.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
            try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Throwable) {}
            try { f.isXIncludeAware = false } catch (_: Throwable) {}
            val db = f.newDocumentBuilder()
            db.setErrorHandler(null)   // من غير طباعة أخطاء الـparser على stderr (بنبلّغ بالتقرير)
            db.parse(ByteArrayInputStream(bytes))
        } catch (e: Throwable) { report.warn(file, "XML مش سليم — اتجاهل (" + (e.message ?: "").take(60).replace('\n', ' ') + ")"); return null }
        val root = doc.documentElement
        if (root == null || localName(root) != "svg") { report.warn(file, "مش ملف SVG (الجذر مش <svg>)"); return null }

        // viewBox ← مربع 24×24 (مقياس موحّد + توسيط)
        var minX = 0f; var minY = 0f; var vw = 24f; var vh = 24f
        val vbS = root.getAttribute("viewBox")
        if (vbS.isNotBlank()) {
            val p = vbS.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            if (p.size == 4) { minX = p[0]; minY = p[1]; vw = p[2]; vh = p[3] } else { report.warn(file, "viewBox غلط — اتفترض 24×24") }
        } else {
            val w = lenOf(root.getAttribute("width")); val h = lenOf(root.getAttribute("height"))
            if (w != null && h != null) { vw = w; vh = h } else report.warn(file, "مفيش viewBox — اتفترض 24×24")
        }
        if (!(vw > 0f && vh > 0f) || vw > 100000f || vh > 100000f) { report.err(file, "أبعاد الـ viewBox غلط"); return null }
        val s = min(24f / vw, 24f / vh)
        val base = Mat(s, 0f, 0f, s, (24f - vw * s) / 2f - minX * s, (24f - vh * s) / 2f - minY * s)

        val ctx = Ctx(file, report, defSw)
        val st0 = St(null, null, null, 1f, 1f, 1f, 1, 1, false)
        try { walk(root, st0, base, ctx, 0) } catch (e: FatalSvg) { report.err(file, e.message ?: "SVG مرفوض"); return null }
        if (ctx.layers.isEmpty()) { report.warn(file, "مفيش أشكال مرسومة جواه — اتجاهل"); return null }
        return ctx.layers
    }

    private class FatalSvg(m: String) : RuntimeException(m)

    private fun localName(e: Element): String = (e.localName ?: e.tagName).substringAfter(':').lowercase()

    private fun lenOf(s: String?): Float? {
        if (s == null) return null
        val t = s.trim().removeSuffix("px")
        return t.toFloatOrNull()
    }

    private fun styleMap(e: Element): Map<String, String> {
        val s = e.getAttribute("style"); if (s.isBlank()) return emptyMap()
        val m = HashMap<String, String>()
        for (decl in s.split(';')) { val k = decl.substringBefore(':', "").trim().lowercase(); val v = decl.substringAfter(':', "").trim(); if (k.isNotEmpty() && v.isNotEmpty()) m[k] = v }
        return m
    }

    private fun walk(e: Element, parent: St, parentMat: Mat, ctx: Ctx, depth: Int) {
        if (depth > MAX_DEPTH) throw FatalSvg("تداخل عناصر أعمق من اللازم")
        val tag = localName(e)
        when (tag) {
            "script", "foreignobject" -> throw FatalSvg("عنصر <" + tag + "> مش مسموح (أمان)")
            "defs", "title", "desc", "metadata", "namedview", "symbol", "clippath", "mask", "lineargradient", "radialgradient", "pattern", "filter", "marker" -> {
                if (tag == "clippath" || tag == "mask" || tag == "filter") warnOnce(ctx, tag, "<$tag> مش مدعوم (بيتجاهل)")
                return
            }
            "style" -> { warnOnce(ctx, tag, "<style>/class مش مدعوم — استخدم خصائص fill/stroke على العناصر نفسها"); return }
            "image", "text", "tspan", "switch", "animate", "animatetransform", "set" -> { warnOnce(ctx, tag, "<$tag> مش مدعوم (بيتجاهل)"); return }
            "use" -> {
                val href = e.getAttribute("href").ifBlank { e.getAttributeNS("http://www.w3.org/1999/xlink", "href") }.ifBlank { e.getAttribute("xlink:href") }
                if (href.isNotBlank() && !href.startsWith("#")) throw FatalSvg("<use> بمرجع خارجي مش مسموح (أمان)")
                warnOnce(ctx, tag, "<use> مش مدعوم — انسخ الشكل نفسه (بيتجاهل)"); return
            }
        }
        val sm = styleMap(e)
        fun a(name: String): String? = sm[name] ?: e.getAttribute(name).takeIf { it.isNotBlank() }
        fun f(name: String): Float? = a(name)?.trim()?.removeSuffix("px")?.toFloatOrNull()
        if (a("display")?.trim() == "none" || a("visibility")?.trim() == "hidden") return

        val capS = a("stroke-linecap"); val joinS = a("stroke-linejoin")
        val st = St(
            fill = a("fill")?.takeIf { it != "inherit" } ?: parent.fill,
            stroke = a("stroke")?.takeIf { it != "inherit" } ?: parent.stroke,
            sw = f("stroke-width") ?: parent.sw,
            fo = (f("fill-opacity") ?: 1f).coerceIn(0f, 1f) * parent.fo,
            so = (f("stroke-opacity") ?: 1f).coerceIn(0f, 1f) * parent.so,
            op = (f("opacity") ?: 1f).coerceIn(0f, 1f) * parent.op,
            cap = when (capS) { "butt" -> 0; "round" -> 1; "square" -> 2; else -> parent.cap },
            join = when (joinS) { "miter" -> 0; "round" -> 1; "bevel" -> 2; else -> parent.join },
            evenOdd = when (a("fill-rule")?.trim()) { "evenodd" -> true; "nonzero" -> false; else -> parent.evenOdd }
        )
        val mat = a("transform")?.let { parentMat.mul(parseTransform(it, ctx)) } ?: parentMat

        when (tag) {
            "svg", "g", "a" -> {
                var ch = e.firstChild
                while (ch != null) { if (ch.nodeType == Node.ELEMENT_NODE) walk(ch as Element, st, mat, ctx, depth + 1); ch = ch.nextSibling }
            }
            "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" -> {
                val ops = try { shapeOps(tag, e, { n -> f(n) }, mat) } catch (ex: IllegalArgumentException) { ctx.report.warn(ctx.file, "<" + tag + "> غلط: " + (ex.message ?: "") + " (الشكل اتجاهل)"); return }
                ctx.ops += ops.size
                if (ctx.ops > MAX_OPS) throw FatalSvg("الأيقونة معقّدة زيادة (أكتر من " + MAX_OPS + " أمر رسم)")
                addLayers(ctx, st, mat, ops, tag)
            }
            else -> warnOnce(ctx, tag, "عنصر <$tag> مش معروف (بيتجاهل)")
        }
    }

    private fun warnOnce(ctx: Ctx, key: String, msg: String) { if (ctx.warnedTags.add(key)) ctx.report.warn(ctx.file, msg) }

    private fun paintOf(v: String?, defNone: Boolean, ctx: Ctx): Paint {
        val s = v?.trim()
        if (s == null || s.isEmpty()) return if (defNone) Paint(true, false, 0, 1f) else Paint(false, true, 0, 1f)   // fill الافتراضي = أسود = لون النص
        val l = s.lowercase()
        if (l == "none" || l == "transparent") return Paint(true, false, 0, 1f)
        if (l == "currentcolor") return Paint(false, true, 0, 1f)
        if (l.startsWith("url(")) { if (!ctx.warnedGrad) { ctx.warnedGrad = true; ctx.report.warn(ctx.file, "تدرّج/باترن مش مدعوم — اتستخدم لون النص بداله") }; return Paint(false, true, 0, 1f) }
        val c = parseColor(l) ?: run { warnOnce(ctx, "color:" + l, "لون مش مفهوم «" + s + "» — اتستخدم لون النص"); return Paint(false, true, 0, 1f) }
        val a = ((c ushr 24) and 0xFF) / 255f
        // الأسود الصريح = لون النص (الأيقونات الأحادية)
        if ((c and 0xFFFFFF) == 0) return Paint(false, true, 0, a)
        return Paint(false, false, c or (0xFF shl 24), a)
    }

    internal fun parseColor(l: String): Int? {
        NAMED[l]?.let { return it }
        if (l == "black") return 0xFF000000.toInt()
        if (l.startsWith("#")) {
            val h = l.substring(1)
            return try {
                when (h.length) {
                    3 -> { val r = h.substring(0, 1).repeat(2); val g = h.substring(1, 2).repeat(2); val b = h.substring(2, 3).repeat(2); (0xFF shl 24) or (r + g + b).toInt(16) }
                    4 -> { val a = h.substring(3, 4).repeat(2).toInt(16); val rgb = (h.substring(0, 1).repeat(2) + h.substring(1, 2).repeat(2) + h.substring(2, 3).repeat(2)).toInt(16); (a shl 24) or rgb }
                    6 -> (0xFF shl 24) or h.toInt(16)
                    8 -> { val rgb = h.substring(0, 6).toInt(16); val a = h.substring(6, 8).toInt(16); (a shl 24) or rgb }
                    else -> null
                }
            } catch (_: NumberFormatException) { null }
        }
        if (l.startsWith("rgb")) {
            val inner = l.substringAfter('(', "").substringBefore(')', "")
            val p = inner.split(Regex("[\\s,/]+")).filter { it.isNotEmpty() }
            if (p.size < 3) return null
            fun ch(s: String): Int? = if (s.endsWith("%")) s.dropLast(1).toFloatOrNull()?.let { (it * 2.55f).toInt().coerceIn(0, 255) } else s.toFloatOrNull()?.toInt()?.coerceIn(0, 255)
            val r = ch(p[0]) ?: return null; val g = ch(p[1]) ?: return null; val b = ch(p[2]) ?: return null
            val a = if (p.size >= 4) (p[3].let { if (it.endsWith("%")) it.dropLast(1).toFloatOrNull()?.div(100f) else it.toFloatOrNull() } ?: 1f).coerceIn(0f, 1f) else 1f
            return ((a * 255f).toInt() shl 24) or (r shl 16) or (g shl 8) or b
        }
        return null
    }

    private fun addLayers(ctx: Ctx, st: St, mat: Mat, ops: PathOps, tag: String) {
        if (ctx.layers.size >= MAX_LAYERS) throw FatalSvg("طبقات كتير في الأيقونة الواحدة (الحد " + MAX_LAYERS + ")")
        val fp = paintOf(st.fill, false, ctx)
        val sp = paintOf(st.stroke, true, ctx)
        val sw = (st.sw ?: ctx.defSw) * mat.scale()
        val fillOn = !fp.none && tag != "line"   // الخط مالوش مساحة؛ polyline بتتعبّى زي معيار SVG
        val strokeOn = !sp.none && sw > 0f
        if (!fillOn && !strokeOn) return
        val fa = (st.op * st.fo * fp.alpha).coerceIn(0f, 1f)
        val sa = (st.op * st.so * sp.alpha).coerceIn(0f, 1f)
        val sameColor = fillOn && strokeOn && fp.tint == sp.tint && (fp.tint || fp.argb == sp.argb)
        if (fillOn && strokeOn && !sameColor) {
            ctx.layers.add(IconLayerData(true, false, fp.argb, !fp.tint, fa, fa, sw, st.cap, st.join, st.evenOdd, ops))
            if (ctx.layers.size >= MAX_LAYERS) throw FatalSvg("طبقات كتير في الأيقونة الواحدة (الحد " + MAX_LAYERS + ")")
            ctx.layers.add(IconLayerData(false, true, sp.argb, !sp.tint, sa, sa, sw, st.cap, st.join, st.evenOdd, ops))
        } else {
            val p = if (fillOn) fp else sp
            ctx.layers.add(IconLayerData(fillOn, strokeOn, p.argb, !p.tint, fa, sa, sw, st.cap, st.join, st.evenOdd, ops))
        }
    }

    private fun shapeOps(tag: String, e: Element, f: (String) -> Float?, m: Mat): PathOps {
        fun g(n: String, d: Float = 0f) = f(n) ?: d
        return when (tag) {
            "path" -> SvgPath.parse(e.getAttribute("d"), m)
            "rect" -> {
                val x = g("x"); val y = g("y"); val w = g("width"); val h = g("height")
                if (!(w > 0f && h > 0f)) throw IllegalArgumentException("عرض/ارتفاع غلط")
                var rx = f("rx"); var ry = f("ry")
                if (rx == null && ry != null) rx = ry; if (ry == null && rx != null) ry = rx
                val r1 = min(rx ?: 0f, w / 2f); val r2 = min(ry ?: 0f, h / 2f)
                if (r1 <= 0f || r2 <= 0f) SvgPath.parse("M$x $y H${x + w} V${y + h} H$x Z", m)
                else SvgPath.parse("M${x + r1} ${y}H${x + w - r1}A$r1 $r2 0 0 1 ${x + w} ${y + r2}V${y + h - r2}A$r1 $r2 0 0 1 ${x + w - r1} ${y + h}H${x + r1}A$r1 $r2 0 0 1 $x ${y + h - r2}V${y + r2}A$r1 $r2 0 0 1 ${x + r1} ${y}Z", m)
            }
            "circle" -> { val r = g("r"); if (!(r > 0f)) throw IllegalArgumentException("نصف قطر غلط"); ellipse(g("cx"), g("cy"), r, r, m) }
            "ellipse" -> { val rx = g("rx"); val ry = g("ry"); if (!(rx > 0f && ry > 0f)) throw IllegalArgumentException("أنصاف أقطار غلط"); ellipse(g("cx"), g("cy"), rx, ry, m) }
            "line" -> SvgPath.parse("M${g("x1")} ${g("y1")}L${g("x2")} ${g("y2")}", m)
            else -> {   // polyline / polygon
                val nums = e.getAttribute("points").trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.toFloatOrNull() ?: throw IllegalArgumentException("نقطة غلط") }
                if (nums.size < 4 || nums.size % 2 != 0) throw IllegalArgumentException("نقط ناقصة")
                val sb = StringBuilder("M").append(nums[0]).append(' ').append(nums[1])
                var i = 2; while (i < nums.size) { sb.append('L').append(nums[i]).append(' ').append(nums[i + 1]); i += 2 }
                if (tag == "polygon") sb.append('Z')
                SvgPath.parse(sb.toString(), m)
            }
        }
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, m: Mat): PathOps =
        SvgPath.parse("M${cx - rx} ${cy}A$rx $ry 0 1 0 ${cx + rx} ${cy}A$rx $ry 0 1 0 ${cx - rx} ${cy}Z", m)

    private fun parseTransform(s: String, ctx: Ctx): Mat {
        var m = Mat.ID
        val re = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")
        for (mt in re.findAll(s)) {
            val a = mt.groupValues[2].trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.mapNotNull { it.toFloatOrNull() }
            val t: Mat? = when (mt.groupValues[1]) {
                "translate" -> if (a.isNotEmpty()) Mat(1f, 0f, 0f, 1f, a[0], a.getOrElse(1) { 0f }) else null
                "scale" -> if (a.isNotEmpty()) Mat(a[0], 0f, 0f, a.getOrElse(1) { a[0] }, 0f, 0f) else null
                "rotate" -> if (a.isNotEmpty()) {
                    val r = Math.toRadians(a[0].toDouble()); val c = cos(r).toFloat(); val sn = sin(r).toFloat()
                    val rot = Mat(c, sn, -sn, c, 0f, 0f)
                    if (a.size >= 3) Mat(1f, 0f, 0f, 1f, a[1], a[2]).mul(rot).mul(Mat(1f, 0f, 0f, 1f, -a[1], -a[2])) else rot
                } else null
                "matrix" -> if (a.size == 6) Mat(a[0], a[1], a[2], a[3], a[4], a[5]) else null
                "skewx" -> if (a.isNotEmpty()) Mat(1f, 0f, tan(Math.toRadians(a[0].toDouble())).toFloat(), 1f, 0f, 0f) else null
                "skewy" -> if (a.isNotEmpty()) Mat(1f, tan(Math.toRadians(a[0].toDouble())).toFloat(), 0f, 1f, 0f, 0f) else null
                else -> null
            }
            if (t == null) warnOnce(ctx, "tf:" + mt.value, "transform مش مفهوم «" + mt.value + "» (بيتجاهل)") else m = m.mul(t)
        }
        return m
    }
}

/** قارئ الباك (zip) + فاحص */
object IconPackReader {
    const val MAX_ZIP_BYTES = 3 * 1024 * 1024
    const val MAX_ENTRIES = 600
    const val MAX_SVG_BYTES = 128 * 1024
    const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    private val ID_RE = Regex("^[a-z0-9][a-z0-9_-]{1,31}$")
    /** ids محجوزة للباكات المدمجة */
    /** الباكات المدمجة بالترتيب + اسمها (مطابق لـ pack.json جوه كل zip في assets/iconpacks — الاختبار بيتأكد) */
    val BUILTIN: List<Pair<String, String>> = listOf(
        "outline" to "Outline — خطوط رفيعة", "filled" to "Filled — مجسّم", "duotone" to "Duotone — نغمتين", "rounded" to "Rounded — مدوّر")
    val RESERVED = setOf("default", "outline", "filled", "duotone", "rounded")

    /** قراءة خفيفة: اسم الباك والمؤلف من pack.json بس (للقوايم) — null لو مش باك سليم */
    fun peek(zip: ByteArray): Pair<String, String>? {
        if (zip.size > MAX_ZIP_BYTES) return null
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
                var n = 0
                while (n++ < MAX_ENTRIES) {
                    val en = zis.nextEntry ?: break
                    val nm = en.name.replace('\\', '/').lowercase()
                    if (en.isDirectory || !(nm == "pack.json" || nm.endsWith("/pack.json"))) continue
                    val buf = ByteArrayOutputStream(); val tmp = ByteArray(4096); var sz = 0
                    while (true) { val r = zis.read(tmp); if (r < 0) break; sz += r; if (sz > 64 * 1024) return null; buf.write(tmp, 0, r) }
                    val j = JSONObject(String(buf.toByteArray(), Charsets.UTF_8))
                    val name = j.optString("name", "").trim()
                    return if (name.isEmpty()) null else name.take(48) to j.optString("author", "").take(60)
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * @param builtin true للباكات المدمجة (بتسمح بالـids المحجوزة)
     * @param expectId لو اتحدد، لازم id الباك يطابقه (للمدمج)
     */
    fun read(zip: ByteArray, builtin: Boolean = false, expectId: String? = null): PackReadResult {
        val rep = PackReport()
        if (zip.size > MAX_ZIP_BYTES) { rep.err(null, "الملف كبير (" + zip.size / 1024 + " ك.ب) — الحد " + MAX_ZIP_BYTES / 1024 + " ك.ب"); return PackReadResult(null, rep) }
        if (zip.size < 22 || zip[0] != 'P'.code.toByte() || zip[1] != 'K'.code.toByte()) { rep.err(null, "الملف مش zip"); return PackReadResult(null, rep) }
        val files = LinkedHashMap<String, ByteArray>()
        var entries = 0; var total = 0L; var skipped = 0
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
                while (true) {
                    val en: ZipEntry = zis.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) { rep.err(null, "عدد الملفات جوه الـzip كتير (الحد " + MAX_ENTRIES + ")"); return PackReadResult(null, rep) }
                    val raw = en.name.replace('\\', '/')
                    if (en.isDirectory) continue
                    if (raw.startsWith("/") || raw.split('/').any { it == ".." } || raw.contains(':')) { rep.err(raw, "مسار خطر داخل الـzip (مرفوض)"); return PackReadResult(null, rep) }
                    val base = raw.substringAfterLast('/')
                    if (raw.startsWith("__MACOSX/") || base.startsWith(".")) continue
                    val lower = raw.lowercase()
                    if (!(lower.endsWith(".svg") || lower == "pack.json" || lower.endsWith("/pack.json"))) { skipped++; continue }
                    val buf = ByteArrayOutputStream(); val tmp = ByteArray(8192)
                    val lim = if (lower.endsWith(".svg")) MAX_SVG_BYTES else 64 * 1024
                    var sz = 0
                    while (true) {
                        val r = zis.read(tmp); if (r < 0) break
                        sz += r; total += r
                        if (sz > lim) { rep.err(raw, "الملف أكبر من " + lim / 1024 + " ك.ب"); return PackReadResult(null, rep) }
                        if (total > MAX_TOTAL_BYTES) { rep.err(null, "الحجم بعد فك الضغط كبير زيادة"); return PackReadResult(null, rep) }
                        buf.write(tmp, 0, r)
                    }
                    if (files.containsKey(raw)) { rep.warn(raw, "اسم مكرر في الـzip — اتجاهل التاني"); continue }
                    files[raw] = buf.toByteArray()
                }
            }
        } catch (e: Throwable) { rep.err(null, "ملف zip تالف (" + (e.message ?: "") + ")"); return PackReadResult(null, rep) }
        if (skipped > 0) rep.info(null, "$skipped ملف مش SVG اتجاهلوا")

        // pack.json: في الجذر، أو جوه فولدر واحد (zip بيتعمل غالبًا بفولدر برّاني)
        val mfName = files.keys.filter { it.lowercase() == "pack.json" }.firstOrNull()
            ?: files.keys.filter { it.lowercase().endsWith("/pack.json") }.minByOrNull { it.count { c -> c == '/' } }
        if (mfName == null) { rep.err(null, "pack.json مش موجود"); return PackReadResult(null, rep) }
        val root = mfName.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val mf = try { JSONObject(String(files[mfName]!!, Charsets.UTF_8)) } catch (e: Throwable) { rep.err(mfName, "pack.json مش JSON سليم"); return PackReadResult(null, rep) }

        val id = mf.optString("id", "").trim()
        val name = mf.optString("name", "").trim()
        if (id.isEmpty()) rep.err(mfName, "الحقل «id» ناقص")
        else if (!ID_RE.matches(id)) rep.err(mfName, "«id» لازم حروف إنجليزي صغيرة/أرقام/_/- (2–32 حرف)")
        else if (!builtin && id in RESERVED) rep.err(mfName, "الـid «$id» محجوز للباكات المدمجة")
        else if (expectId != null && id != expectId) rep.err(mfName, "الـid «$id» مش مطابق للمتوقع «$expectId»")
        if (name.isEmpty()) rep.err(mfName, "الحقل «name» ناقص")
        else if (name.length > 48) rep.err(mfName, "«name» أطول من 48 حرف")
        val version = mf.optInt("version", 1)
        val author = mf.optString("author", "").take(60)
        val desc = mf.optString("description", "").take(200)
        val st = mf.optJSONObject("style")
        val corner = (st?.optDouble("cornerRadius", 0.0) ?: 0.0).toFloat().coerceIn(0f, 6f)
        val defSw = (mf.optDouble("strokeWidth", st?.optDouble("strokeWidth", 2.0) ?: 2.0)).toFloat().coerceIn(0.5f, 6f)
        if (!rep.ok) return PackReadResult(null, rep)

        // ربط الأسماء بالملفات: icons{} الصريحة أولًا، وبعدها اسم الملف نفسه
        val nameToFile = LinkedHashMap<String, String>()
        val explicit = mf.optJSONObject("icons")
        if (explicit != null) {
            val ks = explicit.keys()
            while (ks.hasNext()) {
                val k = ks.next(); val path = explicit.optString(k, "")
                val cn = IconNames.canon(k)
                if (!IconNames.isKnown(cn)) { rep.warn(mfName, "اسم أيقونة مش معروف في icons: «$k» (متجاهَل)"); rep.iconsIgnored++; continue }
                val full = (root + path.replace('\\', '/').trimStart('/'))
                if (path.split('/').any { p -> p == ".." }) { rep.err(mfName, "مسار «$path» فيه .. (مرفوض)"); continue }
                if (!files.containsKey(full)) { rep.warn(mfName, "الملف «$path» (للأيقونة $k) مش موجود"); rep.iconsIgnored++; continue }
                nameToFile[cn] = full
            }
        }
        if (!rep.ok) return PackReadResult(null, rep)
        for (fn in files.keys.sortedBy { it }) {
            if (!fn.lowercase().endsWith(".svg") || !fn.startsWith(root)) continue
            val stem = fn.substringAfterLast('/').substring(0, fn.substringAfterLast('/').length - 4)
            val cn = IconNames.canon(stem)
            if (!IconNames.isKnown(cn)) { rep.warn(fn, "اسم مش معروف «$stem» — مش هيتستخدم"); rep.iconsIgnored++; continue }
            if (nameToFile.containsKey(cn)) { if (nameToFile[cn] != fn && explicit?.has(stem) != true) rep.warn(fn, "الأيقونة «$cn» متعرّفة قبل كده في ${nameToFile[cn]} — اتجاهل ده"); continue }
            nameToFile[cn] = fn
        }

        val icons = LinkedHashMap<String, List<IconLayerData>>()
        for ((cn, fn) in nameToFile) {
            val ls = SvgDoc.parse(files[fn]!!, fn, rep, defSw)
            if (!rep.ok) return PackReadResult(null, rep)   // خطأ قاتل (script …)
            if (ls == null) { rep.iconsIgnored++; continue }
            icons[cn] = ls
        }
        rep.iconsAccepted = icons.size
        val total0 = IconNames.known().size
        if (icons.isEmpty()) { rep.err(null, "مفيش ولا أيقونة صالحة في الباك"); return PackReadResult(null, rep) }
        val missing = total0 - icons.size
        rep.info(null, "${icons.size} أيقونة اتقرت من $total0" + if (missing > 0) " — الـ$missing الباقيين هيستخدموا الأيقونة الافتراضية" else " (تغطية كاملة)")
        if (icons.size * 4 < total0 && !builtin) rep.warn(null, "الباك بيغطي أقل من ربع الأيقونات — الباقي هيبان بالشكل الافتراضي (ممكن يبان مخلوط)")
        return PackReadResult(IconPackData(id, name, author, version, desc, corner, builtin, icons), rep)
    }
}

/** تصدير قالب: الأيقونات الافتراضية كملفات SVG + pack.json (نقطة بداية لأي باك جديد) */
object IconPackWriter {
    private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    /** طبقة من IconDefs ← عنصر <path> */
    fun layerToSvg(spec: String): String {
        val a = spec.split('|', limit = 4)
        val kind = a[0]; val col = a[1]; val alpha = a[2].toFloat(); val d = a[3]
        val hex = if (col == "_") "currentColor" else "#" + col.substring(2).uppercase()
        val sb = StringBuilder("<path d=\"").append(d).append("\"")
        sb.append(" fill=\"").append(if (kind.contains('F')) hex else "none").append("\"")
        if (kind.contains('F') && alpha < 1f) sb.append(" fill-opacity=\"").append(fmt(alpha)).append("\"")
        if (kind.contains('S')) {
            sb.append(" stroke=\"").append(hex).append("\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"")
            if (alpha < 1f) sb.append(" stroke-opacity=\"").append(fmt(alpha)).append("\"")
        }
        sb.append(" fill-rule=\"evenodd\"/>")
        return sb.toString()
    }

    fun iconToSvg(name: String): String? {
        val def = IconDefs.DEF[name] ?: return null
        val sb = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">\n")
        for (s in def) sb.append("  ").append(layerToSvg(s)).append('\n')
        sb.append("</svg>\n")
        return sb.toString()
    }

    fun template(id: String = "my_pack", name: String = "باكتي"): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(path: String, data: ByteArray) { val e = ZipEntry(path); e.time = 0L; z.putNextEntry(e); z.write(data); z.closeEntry() }
            val mf = JSONObject()
            mf.put("id", id); mf.put("name", name); mf.put("version", 1); mf.put("author", ""); mf.put("description", "قالب مبدئي — عدّل ملفات svg/ وسيب اللي مش عايز تغيّره أو امسحه (هيرجع للافتراضي)")
            mf.put("style", JSONObject().put("cornerRadius", 0).put("strokeWidth", 2.2))
            put("pack.json", mf.toString(2).toByteArray(Charsets.UTF_8))
            for (n in IconDefs.names) put("svg/$n.svg", (iconToSvg(n) ?: continue).toByteArray(Charsets.UTF_8))
        }
        return bos.toByteArray()
    }
}
