package devtests.iconpacktest

import com.tttt.subtitler.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private var fails = 0
private fun check(name: String, ok: Boolean) { if (ok) println("PASS $name") else { fails++; println("FAIL $name") } }

private fun zip(files: Map<String, String>): ByteArray {
    val b = ByteArrayOutputStream()
    ZipOutputStream(b).use { z -> for ((k, v) in files) { z.putNextEntry(ZipEntry(k)); z.write(v.toByteArray()); z.closeEntry() } }
    return b.toByteArray()
}
private fun mf(id: String = "t_pack", extra: String = "") = """{"id":"$id","name":"تجربة"$extra}"""
private fun svg(body: String, vb: String = "0 0 24 24") = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$vb">$body</svg>"""
private fun one(body: String, vb: String = "0 0 24 24", name: String = "home") = IconPackReader.read(zip(mapOf("pack.json" to mf(), "$name.svg" to svg(body, vb))))
private fun thrown(d: String) = try { SvgPath.parse(d); false } catch (e: IllegalArgumentException) { true }

fun main() {
    // ---- مسارات ----
    val p1 = SvgPath.parse("M1 2L3 4C5 6 7 8 9 10Z")
    check("مسار مطلق: M L C Z", p1.codes.toList() == listOf(PathOps.M, PathOps.L, PathOps.C, PathOps.Z) && p1.pts.size == 2 + 2 + 6)
    val p2 = SvgPath.parse("m1 1 l2 0 h3 v4 z")
    check("نسبي + H V", p2.pts.toList() == listOf(1f, 1f, 3f, 1f, 6f, 1f, 6f, 5f))
    check("lineto ضمني بعد M", SvgPath.parse("M0 0 5 5 6 6").codes.toList() == listOf(PathOps.M, PathOps.L, PathOps.L))
    check("أرقام ملزوقة .5.5 و -1", SvgPath.parse("M.5.5L1-1").pts.toList() == listOf(0.5f, 0.5f, 1f, -1f))
    check("أُس 1e1", SvgPath.parse("M1e1 0").pts[0] == 10f)
    val arc = SvgPath.parse("M0 0 A5 5 0 0 1 10 0")
    check("قوس ← Bézier ينتهي عند الهدف", arc.codes.all { it == PathOps.M || it == PathOps.C } && arc.pts[arc.pts.size - 2] == 10f && arc.pts.last() == 0f)
    check("أعلام قوس ملزوقة", !thrown("M0 0a1 1 0 011 1"))
    check("S/T/Q بتتقبل", !thrown("M0 0C1 1 2 2 3 3S5 5 6 6Q7 7 8 8T9 9"))
    check("مسار فاضي/غلط بيترفض", thrown("") && thrown("L1 1") && thrown("M0 0 Z 5") && thrown("M0 0 X1") && thrown("M a"))

    // ---- أشكال ----
    var r = one("""<path d="M2 2L10 10" stroke="currentColor" fill="none" stroke-width="3"/>""")
    var l = r.pack!!.layers("home")!!
    check("خط: stroke فقط بلون النص", r.report.ok && l.size == 1 && l[0].stroke && !l[0].fill && !l[0].hasColor && l[0].strokeWidth == 3f)
    r = one("""<path d="M2 2L10 10L2 10Z"/>""")
    l = r.pack!!.layers("home")!!
    check("fill افتراضي = لون النص", l[0].fill && !l[0].stroke && !l[0].hasColor)
    r = one("""<path d="M2 2L10 10L2 10Z" fill="#FF0000"/>""")
    check("لون ثابت", r.pack!!.layers("home")!![0].let { it.hasColor && it.color == 0xFFFF0000.toInt() })
    r = one("""<path d="M2 2L10 10L2 10Z" fill="#000"/>""")
    check("الأسود = لون النص", !r.pack!!.layers("home")!![0].hasColor)
    r = one("""<path d="M2 2L10 10L2 10Z" fill="red" stroke="blue" opacity=".5"/>""")
    l = r.pack!!.layers("home")!!
    check("لونين مختلفين ← طبقتين + opacity", l.size == 2 && l[0].fill && l[1].stroke && l[0].fillAlpha == 0.5f)
    r = one("""<g fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><rect x="1" y="1" width="6" height="6" rx="2"/><polyline points="1,1 5,5 9,1"/><polygon points="0,0 4,0 4,4"/><ellipse cx="5" cy="5" rx="3" ry="2"/><line x1="0" y1="0" x2="5" y2="5"/></g>""")
    check("أشكال أساسية + وراثة g", r.report.ok && r.pack!!.layers("home")!!.size == 6 && r.pack!!.layers("home")!!.all { it.stroke && !it.fill && it.strokeWidth == 2f })
    r = one("""<path d="M0 0L8 8" stroke="currentColor" style="stroke-width:4;stroke-linecap:butt"/>""")
    check("style attribute", r.pack!!.layers("home")!![0].let { it.strokeWidth == 4f && it.cap == 0 })
    r = one("""<path d="M0 0L24 24" stroke="currentColor" stroke-width="2"/>""", vb = "0 0 48 48")
    check("viewBox 48 ← مقياس 0.5", r.pack!!.layers("home")!![0].let { it.strokeWidth == 1f && it.ops.pts.last() == 12f })
    r = one("""<path d="M0 0L10 0" stroke="currentColor" transform="translate(2 3)"/>""")
    check("transform translate", r.pack!!.layers("home")!![0].ops.pts.toList().take(2) == listOf(2f, 3f))
    r = one("""<path d="M0 0L10 0" stroke="currentColor"/>""", vb = "0 0 24 12")
    check("viewBox مش مربع ← توسيط", r.pack!!.layers("home")!![0].ops.pts[1] == 6f)

    // ---- فحص ----
    check("مش zip", !IconPackReader.read("hello world, not a zip at all....".toByteArray()).report.ok)
    check("zip من غير pack.json", !IconPackReader.read(zip(mapOf("home.svg" to svg("<path d='M0 0L1 1'/>")))).report.ok)
    check("pack.json مش JSON", !IconPackReader.read(zip(mapOf("pack.json" to "{x", "home.svg" to svg("<path d='M0 0L1 1'/>")))).report.ok)
    check("id غلط", !IconPackReader.read(zip(mapOf("pack.json" to mf("Bad Id"), "home.svg" to svg("<path d='M0 0L1 1'/>")))).report.ok)
    check("id محجوز", !IconPackReader.read(zip(mapOf("pack.json" to mf("outline"), "home.svg" to svg("<path d='M0 0L1 1'/>")))).report.ok)
    check("بدون name", !IconPackReader.read(zip(mapOf("pack.json" to """{"id":"abc"}""", "home.svg" to svg("<path d='M0 0L1 1'/>")))).report.ok)
    check("zip-slip", !IconPackReader.read(zip(mapOf("pack.json" to mf(), "../evil.svg" to "x"))).report.ok)
    check("<script> مرفوض", !one("""<script>alert(1)</script><path d="M0 0L1 1" stroke="red"/>""").report.ok)
    check("<use> خارجي مرفوض", !one("""<use href="http://x/y.svg#a"/>""").report.ok)
    check("DOCTYPE مرفوض", !IconPackReader.read(zip(mapOf("pack.json" to mf(), "home.svg" to """<!DOCTYPE svg [<!ENTITY a "b">]><svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0L1 1" stroke="red"/></svg>"""))).report.ok)
    var rr = IconPackReader.read(zip(mapOf("pack.json" to mf(), "home.svg" to svg("<path d='M0 0L9 9' stroke='red'/>"), "play.svg" to "<svg><", "zzz_unknown.svg" to svg("<path d='M0 0L1 1'/>"))))
    check("SVG مكسور/اسم مجهول = تحذير والباقي يتقبل", rr.report.ok && rr.pack!!.coverage == 1 && rr.report.warnings.size >= 2)
    check("ولا أيقونة صالحة = خطأ", !IconPackReader.read(zip(mapOf("pack.json" to mf(), "play.svg" to "<svg>"))).report.ok)
    check("SVG كبير", !IconPackReader.read(zip(mapOf("pack.json" to mf(), "home.svg" to svg("<path d='M0 0L1 1' stroke='red'/>" + " ".repeat(200_000))))).report.ok)
    check("تقرير نصه فيه ❌", rr.report.toText().isNotEmpty() && IconPackReader.read(ByteArray(40)).report.toText().contains("❌"))

    // ---- أسماء ----
    check("alias settings=gear, theme=palette", IconNames.canon("Settings") == "gear" && IconNames.canon("theme") == "palette" && IconNames.canon("Chev-L") == "chev_l")
    check("الأسماء البديلة مش بتغطي أسماء أساسية", IconNames.ALIASES.keys.none { IconDefs.DEF.containsKey(it) })
    check("كل هدف alias موجود", IconNames.ALIASES.values.all { IconNames.isKnown(it) })
    check("bell + home + folder + play + search موجودين", listOf("bell", "home", "folder", "play", "search", "gear", "palette").all { IconNames.isKnown(it) })
    rr = IconPackReader.read(zip(mapOf("my/pack.json" to mf(), "my/icons/Settings.svg" to svg("<path d='M0 0L9 9' stroke='red'/>"), "my/x.svg" to svg("<path d='M0 0L9 9' stroke='red'/>"))))
    check("فولدر برّاني + alias في اسم الملف", rr.report.ok && rr.pack!!.has("gear"))
    rr = IconPackReader.read(zip(mapOf("pack.json" to mf(extra = ""","icons":{"theme":"a/b.svg"}"""), "a/b.svg" to svg("<path d='M0 0L9 9' stroke='red'/>"))))
    check("icons{} صريحة", rr.report.ok && rr.pack!!.has("palette"))

    // ---- القالب + الباكات المدمجة ----
    val tpl = IconPackReader.read(IconPackWriter.template())
    check("القالب بيتقري بتغطية كاملة", tpl.report.ok && tpl.pack!!.coverage == IconDefs.names.size)
    check("قالب: play طبقة FS", tpl.pack!!.layers("play")!!.let { it.size == 1 && it[0].fill && it[0].stroke && it[0].strokeWidth == 2.2f })
    check("قالب: check_c فيه لون ثابت", tpl.pack!!.layers("check_c")!!.any { it.hasColor && it.color == 0xFF4CAF50.toInt() })
    check("قالب: folder طبقتين (تعبئة باهتة + خط)", tpl.pack!!.layers("folder")!!.let { it.size == 2 && it[0].fill && it[0].fillAlpha == 0.25f && it[1].stroke })
    val assets = File(System.getenv("SUBTITLER_ASSETS") ?: "app/src/main/assets", "iconpacks")
    for ((id, nm) in IconPackReader.BUILTIN) {
        val f = File(assets, "$id.zip")
        val res = if (f.isFile) IconPackReader.read(f.readBytes(), builtin = true, expectId = id) else null
        check("مدمج $id: موجود وسليم", res != null && res.report.ok && res.pack != null)
        check("مدمج $id: تغطية كاملة (${IconDefs.names.size})", res?.pack?.coverage == IconDefs.names.size)
        check("مدمج $id: الاسم مطابق", res?.pack?.name == nm)
        check("مدمج $id: من غير تحذيرات", res?.report?.warnings?.isEmpty() == true)
    }
    val rd = IconPackReader.read(File(assets, "rounded.zip").readBytes(), true)
    check("rounded: cornerRadius 2", rd.pack?.cornerRadius == 2f)
    val dd = IconPackReader.read(File(assets, "duotone.zip").readBytes(), true)
    check("duotone: تعبئة باهتة", dd.pack!!.layers("folder")!!.any { it.fill && it.fillAlpha < 0.5f })
    val ol = IconPackReader.read(File(assets, "outline.zip").readBytes(), true)
    check("outline: play من غير تعبئة", ol.pack!!.layers("play")!!.none { it.fill })
    check("مدمج بيتقبل بـ id محجوز لكن المستورد لأ", IconPackReader.read(File(assets, "outline.zip").readBytes()).report.ok.not())

    println(if (fails == 0) "كل اختبارات IconPackTest نجحت" else "IconPackTest فشل: $fails")
    if (fails > 0) System.exit(1)
}
