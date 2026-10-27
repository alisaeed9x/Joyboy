package com.tttt.subtitler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

/**
 * (v95) نظام أيقونات مرسومة بالكود بدل الإيموجي: كل إيموجي/رمز في النص بيتغطى بـ IconSpan بيرسم أيقونة واضحة وكبيرة
 * (خط سميك بأطراف مدوّرة، ألوان ثابتة لأيقونات الحالة ✅ ❌ ⚠ وباقي الأيقونات بلون النص).
 * النص نفسه ما بيتغيّرش (text.toString() يرجّع نفس الحروف) فأي مقارنة نصوص أو نسخ للّوج شغالة زي ما هي.
 * IconTextView / IconButton / IconSwitch بيحوّلوا أي نص بيتحط فيهم تلقائيًا — فبيشتغل في الدايلوجات والقوايم والإشعارات وصفوف القوايم.
 */
object Icons {
    /** أيقونة الروتيشن (شاشة بسهمين دايريين زي MX Player) — بتتكتب في أي نص كـ \uE000 */
    const val ROT = "\uE000"

    /** (v598 م3) sw/sAlpha/cap/join/corner بتيجي من باك الأيقونات (الافتراضي: خط 2.2 · أطراف مدوّرة · من غير تدوير زوايا) */
    private class Layer(val fill: Boolean, val stroke: Boolean, val color: Int, val hasColor: Boolean, val alpha: Float, val path: Path,
                        val sw: Float = 2.2f, val sAlpha: Float = alpha, val cap: Paint.Cap = Paint.Cap.ROUND, val join: Paint.Join = Paint.Join.ROUND, val corner: Float = 0f)

    private val DEF: Map<String, Array<String>> get() = IconDefs.DEF

    private val CP: Map<Int, String> = mapOf(
        0x2139 to "info",
        0x2194 to "swap_h",
        0x2195 to "swap_v",
        0x21A9 to "undo",
        0x21BB to "rotate",
        0x22EE to "more_v",
        0x22EF to "more_h",
        0x23E9 to "ff",
        0x23EA to "rew",
        0x23EB to "up2",
        0x23ED to "next",
        0x23EE to "prev",
        0x23EF to "playpause",
        0x23F1 to "stopwatch",
        0x23F3 to "hourglass",
        0x23F8 to "pause",
        0x23F9 to "stop",
        0x25A3 to "box_in",
        0x25AB to "box_s",
        0x25AF to "box_tall",
        0x25B4 to "chev_u",
        0x25B6 to "play",
        0x25B8 to "chev_r",
        0x25BE to "chev_d",
        0x25C0 to "play_l",
        0x25C2 to "chev_l",
        0x25CF to "dot",
        0x2600 to "sun",
        0x2610 to "box",
        0x2611 to "check_box",
        0x261D to "touch",
        0x2630 to "menu",
        0x2640 to "female",
        0x2642 to "male",
        0x266A to "note",
        0x266B to "note2",
        0x267B to "refresh",
        0x2699 to "gear",
        0x26A0 to "warn",
        0x26A1 to "bolt",
        0x26D4 to "no_entry",
        0x26F6 to "fullscreen",
        0x2702 to "scissors",
        0x2705 to "check_c",
        0x270F to "pencil",
        0x2713 to "check",
        0x2714 to "check",
        0x2715 to "cross",
        0x2717 to "cross",
        0x2726 to "sparkle",
        0x2728 to "sparkles",
        0x274C to "cross_c",
        0x2753 to "question",
        0x276E to "chev_l",
        0x276F to "chev_r",
        0x2795 to "plus",
        0x2922 to "expand",
        0x29C9 to "pip",
        0x2B06 to "up",
        0x2B07 to "down",
        0x2B1B to "fit",
        0xE000 to "rot_screen",
        0xFF0B to "plus",
        0x1F22F to "translate",
        0x1F3E0 to "home",
        0x1F4FA to "subs",
        0x1F464 to "person",
        0x1F30A to "wave",
        0x1F310 to "globe",
        0x1F319 to "moon",
        0x1F32B to "fog",
        0x1F399 to "mic",
        0x1F39A to "sliders",
        0x1F39E to "film",
        0x1F3A7 to "headphones",
        0x1F3A8 to "palette",
        0x1F3AC to "clapper",
        0x1F3B5 to "note",
        0x1F3B6 to "note2",
        0x1F441 to "eye",
        0x1F446 to "touch",
        0x1F493 to "heart",
        0x1F4A5 to "burst",
        0x1F4AC to "chat",
        0x1F4BE to "save",
        0x1F4C1 to "folder",
        0x1F4C2 to "folder_open",
        0x1F4C8 to "trend",
        0x1F4CA to "bars",
        0x1F4CB to "clipboard",
        0x1F4CD to "pin",
        0x1F4CF to "ruler",
        0x1F4DC to "scroll",
        0x1F4DD to "note_edit",
        0x1F4E1 to "signal",
        0x1F4E4 to "upload",
        0x1F4E5 to "download",
        0x1F4E6 to "box_pkg",
        0x1F4F6 to "signal",
        0x1F4FB to "radio",
        0x1F4FC to "film",
        0x1F500 to "shuffle",
        0x1F501 to "repeat",
        0x1F504 to "refresh",
        0x1F507 to "mute",
        0x1F508 to "vol_low",
        0x1F50A to "vol",
        0x1F50B to "battery",
        0x1F50D to "search",
        0x1F50E to "search",
        0x1F510 to "lock",
        0x1F511 to "key",
        0x1F512 to "lock",
        0x1F513 to "unlock",
        0x1F515 to "bell_off",
        0x1F517 to "link",
        0x1F51A to "next",
        0x1F51E to "age18",
        0x1F524 to "text_aa",
        0x1F525 to "flame",
        0x1F527 to "wrench",
        0x1F534 to "red_dot",
        0x1F552 to "clock",
        0x1F558 to "clock",
        0x1F573 to "hole",
        0x1F5C2 to "folders",
        0x1F5D1 to "trash",
        0x1F610 to "face",
        0x1F648 to "eye_off",
        0x1F680 to "bolt",
        0x1F6AB to "prohibited",
        0x1F6DF to "lifebuoy",
        0x1F6E1 to "shield",
        0x1F90F to "touch",
        0x1F916 to "robot",
        0x1F91D to "users",
        0x1F9D1 to "person",
        0x1F9E0 to "brain",
        0x1F9E9 to "grid",
        0x1F9F0 to "toolbox",
        0x1F9F9 to "broom",
        0x1FA7A to "steth",
        0x1F3A4 to "mic",
        0x1F634 to "moon",
        0x25B2 to "chev_u",
        0x25BC to "chev_d",
        0x23FB to "power",
        0x2328 to "keyboard",
        0x1F4F1 to "phone",
        0x1F590 to "touch",
        0x1F502 to "repeat",
        0x21BA to "rotate",
        0x1F4C4 to "scroll",
        0x1F7E2 to "dot_g",
        0x1F7E1 to "dot_y",
        0x26AA to "dot_w",
    )

    private val SC: Map<String, Float> = mapOf("chev_l" to 1.0f, "chev_r" to 1.0f, "chev_d" to 1.0f, "chev_u" to 1.0f, "dot" to 1.0f, "red_dot" to 1.1f, "box_s" to 1.1f, "plus" to 1.2f, "check" to 1.15f, "cross" to 1.15f, "more_v" to 1.3f, "more_h" to 1.3f, "rot_screen" to 1.3f)
    private const val DEFAULT_SCALE = 1.3f

    private val cache = HashMap<String, List<Layer>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** (v598 م3) كاش طبقات الباك النشط — بيتفضّى لوحده لما الباك النشط يتغيّر (مقارنة بالكائن) */
    private val packCache = HashMap<String, List<Layer>>()
    private var packOwner: IconPackData? = null
    private val cornerFx = HashMap<Float, CornerPathEffect>()

    private fun parse(d: String): Path {
        val p = Path(); p.fillType = Path.FillType.EVEN_ODD
        var i = 0; val n = d.length
        fun num(): Float {
            val s = i
            if (i < n && (d[i] == '-' || d[i] == '+')) i++
            while (i < n && (d[i].isDigit() || d[i] == '.')) i++
            val v = d.substring(s, i).toFloat()
            while (i < n && (d[i] == ' ' || d[i] == ',')) i++
            return v
        }
        while (i < n) {
            val c = d[i++]
            when (c) {
                'M' -> p.moveTo(num(), num())
                'L' -> p.lineTo(num(), num())
                'C' -> p.cubicTo(num(), num(), num(), num(), num(), num())
                'Z' -> p.close()
                else -> {}
            }
        }
        return p
    }

    private fun toPath(o: PathOps, evenOdd: Boolean): Path {
        val p = Path(); p.fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        var k = 0; val q = o.pts
        for (c in o.codes) when (c) {
            PathOps.M -> { p.moveTo(q[k], q[k + 1]); k += 2 }
            PathOps.L -> { p.lineTo(q[k], q[k + 1]); k += 2 }
            PathOps.C -> { p.cubicTo(q[k], q[k + 1], q[k + 2], q[k + 3], q[k + 4], q[k + 5]); k += 6 }
            else -> p.close()
        }
        return p
    }

    private fun packLayers(pk: IconPackData, name: String): List<Layer>? {
        if (packOwner !== pk) { packCache.clear(); packOwner = pk }
        packCache[name]?.let { return it }
        val src = pk.layers(name) ?: return null
        val out = src.map { d ->
            Layer(d.fill, d.stroke, d.color, d.hasColor, d.fillAlpha, toPath(d.ops, d.evenOdd), d.strokeWidth, d.strokeAlpha,
                when (d.cap) { 0 -> Paint.Cap.BUTT; 2 -> Paint.Cap.SQUARE; else -> Paint.Cap.ROUND },
                when (d.join) { 0 -> Paint.Join.MITER; 2 -> Paint.Join.BEVEL; else -> Paint.Join.ROUND }, pk.cornerRadius)
        }
        packCache[name] = out
        return out
    }

    private fun layers(name00: String): List<Layer>? {
        // (v598 م3) اسم بديل (settings = gear …) ← الاسم الأساسي؛ وبعدها الباك النشط الأول، ولو ناقص الأيقونة دي بنرجع للافتراضي
        val name0 = if (DEF.containsKey(name00)) name00 else IconNames.canon(name00)
        val pk = IconPacks.active()
        if (pk != null) packLayers(pk, name0)?.let { return it }
        val name = Skins.icon(name0).let { if (DEF.containsKey(it)) it else name0 }   // (v352) فولدرات حسب وضع الواجهة
        cache[name]?.let { return it }
        val def = DEF[name] ?: return null
        val out = def.map { s ->
            val a = s.split('|', limit = 4)
            val col = if (a[1] == "_") 0 else a[1].toLong(16).toInt()
            Layer(a[0].contains('F'), a[0].contains('S'), col, a[1] != "_", a[2].toFloat(), parse(a[3]))
        }
        cache[name] = out
        return out
    }

    /** بيرسم الأيقونة في مربع [size] بكسل عند (left, top). textColor بيتستخدم للطبقات اللي من غير لون ثابت */
    fun draw(c: Canvas, name: String, left: Float, top: Float, size: Float, textColor: Int, alphaMul: Int = 255) {
        val ls = layers(name) ?: return
        c.save(); c.translate(left, top); val s = size / 24f; c.scale(s, s)
        for (l in ls) {
            val col = if (l.hasColor) l.color else textColor
            val a = (Color.alpha(col) * l.alpha * (alphaMul / 255f)).toInt().coerceIn(0, 255)
            paint.reset(); paint.isAntiAlias = true
            if (l.corner > 0f) paint.pathEffect = cornerFx.getOrPut(l.corner) { CornerPathEffect(l.corner) }
            if (l.fill) { paint.style = Paint.Style.FILL; paint.color = col; paint.alpha = a; c.drawPath(l.path, paint) }
            if (l.stroke) {
                val sa = if (l.sAlpha == l.alpha) a else (Color.alpha(col) * l.sAlpha * (alphaMul / 255f)).toInt().coerceIn(0, 255)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = l.sw; paint.strokeCap = l.cap; paint.strokeJoin = l.join
                paint.color = col; paint.alpha = sa; c.drawPath(l.path, paint)
            }
        }
        c.restore()
    }

    class IconSpan(val name: String, val scale: Float) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (paint.textSize * scale + paint.textSize * 0.14f).toInt()
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val s = paint.textSize * scale
            val fm = paint.fontMetrics
            val cy = y + (fm.ascent + fm.descent) / 2f
            draw(canvas, name, x + paint.textSize * 0.07f, cy - s / 2f, s, paint.color, paint.alpha)
        }
    }

    /** (v96) لو النص كله أيقونة واحدة (زي ⏮ ▶ ☰ ⛶) بيرجّع اسمها ومقياسها — الزرار بيرسمها مباشرة من غير Span (الـ Span كان بيطلّع الرموز المفردة فاضية/سودا) */
    fun singleIcon(cs: CharSequence?): Pair<String, Float>? {
        if (cs == null || cs.isEmpty() || cs.length > 4) return null
        val s = cs.toString()
        var i = 0; var name: String? = null
        while (i < s.length) {
            val cp = s.codePointAt(i); val cc = Character.charCount(cp)
            if (cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D) { i += cc; continue }
            val nm = CP[cp] ?: return null
            if (name != null) return null
            name = nm; i += cc
        }
        val n = name ?: return null
        return n to (SC[n] ?: DEFAULT_SCALE)
    }

    fun has(cs: CharSequence?): Boolean {
        if (cs == null) return false
        var i = 0; val n = cs.length
        while (i < n) {
            val ch = cs[i]
            if (ch.code >= 0x2139) { val cp = Character.codePointAt(cs, i); if (CP.containsKey(cp)) return true; i += Character.charCount(cp) } else i++
        }
        return false
    }

    /** بيرجّع نفس النص + IconSpan فوق كل إيموجي/رمز له أيقونة. لو مفيش، بيرجّع نفس الكائن من غير نسخ */
    fun convert(cs: CharSequence?): CharSequence? {
        if (cs == null || cs.isEmpty() || !has(cs)) return cs
        val out = SpannableStringBuilder(cs)
        var i = 0; val n = out.length
        while (i < n) {
            val cp = Character.codePointAt(out, i); val cc = Character.charCount(cp)
            val nm = CP[cp]
            if (nm != null) {
                var e = i + cc
                while (e < n && (out[e] == '\uFE0F' || out[e] == '\uFE0E' || out[e] == '\u200D')) e++
                if (out.getSpans(i, e, IconSpan::class.java).isEmpty())
                    out.setSpan(IconSpan(nm, SC[nm] ?: DEFAULT_SCALE), i, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = e
            } else i += cc
        }
        return out
    }

    /** نفس النص من غير الإيموجي (للإشعارات وشاشة البصمة ورسائل النظام اللي مينفعش ترسم فيها) */
    fun plain(s: String): String {
        if (!has(s)) return s
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); val cc = Character.charCount(cp)
            if (!CP.containsKey(cp) && cp != 0xFE0F && cp != 0xFE0E) sb.appendCodePoint(cp)
            i += cc
        }
        return sb.toString().replace(Regex("[ ]{2,}"), " ").trim()
    }

    /** تحويل مكان مباشر لنص Spannable موجود (لـ append وللنصوص اللي بتتعدّل) */
    fun applyInPlace(sp: Spannable) {
        val conv = convert(sp) as? Spanned ?: return
        for (s in conv.getSpans(0, conv.length, IconSpan::class.java)) {
            val st = conv.getSpanStart(s); val en = conv.getSpanEnd(s)
            if (sp.getSpans(st, en, IconSpan::class.java).isEmpty()) sp.setSpan(IconSpan(s.name, s.scale), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

/** اسم عربي لكل أيقونة (لقارئ الشاشة TalkBack) — بيتحط تلقائيًا على أي زرار نصه أيقونة واحدة بس */
object A11y {
    private val L = mapOf(
        "info" to "معلومات", "swap_h" to "تبديل", "swap_v" to "تبديل", "undo" to "تراجع", "rotate" to "تدوير الشاشة", "rot_screen" to "تدوير الشاشة",
        "more_v" to "المزيد", "more_h" to "المزيد", "ff" to "تقديم", "rew" to "رجوع", "up2" to "للأول", "next" to "التالي", "prev" to "السابق",
        "playpause" to "تشغيل أو إيقاف", "stopwatch" to "التوقيت", "hourglass" to "جاري التنفيذ", "pause" to "إيقاف مؤقت", "stop" to "إيقاف",
        "play" to "تشغيل", "play_l" to "تشغيل", "chev_u" to "لأعلى", "chev_d" to "لأسفل", "chev_l" to "يسار", "chev_r" to "يمين",
        "refresh" to "تحديث", "gear" to "الإعدادات", "warn" to "تنبيه", "bolt" to "سريع", "no_entry" to "ممنوع", "fullscreen" to "ملء الشاشة",
        "scissors" to "قص", "check" to "تأكيد", "check_c" to "تأكيد", "check_box" to "تحديد", "box" to "تحديد", "cross" to "إغلاق", "cross_c" to "إغلاق",
        "pencil" to "تعديل", "plus" to "إضافة", "expand" to "تكبير", "pip" to "نافذة صغيرة", "up" to "لأعلى", "down" to "لأسفل", "fit" to "ملاءمة الشاشة",
        "box_in" to "ملاءمة الشاشة", "menu" to "القائمة", "translate" to "الترجمة", "note" to "صوت", "note2" to "صوت", "globe" to "اللغة", "moon" to "ترجمة في الخلفية",
        "mic" to "الصوت", "sliders" to "ضبط", "film" to "فيديو", "headphones" to "الصوت", "palette" to "الألوان", "clapper" to "فيديو", "eye" to "عرض",
        "eye_off" to "إخفاء", "chat" to "تعليق", "save" to "حفظ", "folder" to "مجلد", "folder_open" to "فتح", "bars" to "الإحصائيات", "clipboard" to "اللوجز",
        "ruler" to "المقاس", "scroll" to "ملخص", "note_edit" to "الجمل", "upload" to "تصدير", "download" to "استيراد", "shuffle" to "عشوائي", "repeat" to "تكرار",
        "mute" to "كتم", "vol_low" to "الصوت", "vol" to "الصوت", "search" to "بحث", "lock" to "قفل", "unlock" to "فتح القفل", "key" to "المفاتيح", "bell_off" to "إشعار مكتوم",
        "link" to "رابط", "text_aa" to "النص", "flame" to "اللهجة", "wrench" to "أدوات", "clock" to "الوقت", "hole" to "سد الفجوات", "folders" to "الترجمات",
        "power" to "طاقة", "keyboard" to "كيبورد", "phone" to "الموبايل", "trash" to "حذف", "prohibited" to "ممنوع", "shield" to "مانع الإعلانات", "robot" to "الذكاء الاصطناعي", "brain" to "ذكي", "toolbox" to "أدوات", "broom" to "مسح"
    )
    fun label(icon: String): String? = L[icon]
}

/** TextView بيرسم الإيموجي كأيقونات تلقائيًا (بديل TextView العادي في كل البرنامج) */
open class IconTextView(ctx: Context) : TextView(ctx) {
    private var autoCd: String? = null
    private var icT: CharSequence? = null
    private var icV: Pair<String, Float>? = null
    private fun oneIcon(): Pair<String, Float>? {
        val t: CharSequence? = text
        if (t === icT) return icV
        icT = t; icV = Icons.singleIcon(t)
        return icV
    }
    override fun setText(text: CharSequence?, type: BufferType?) {
        icT = null
        super.setText(Icons.convert(text), type)
        val one = Icons.singleIcon(text)
        if (one != null) { val l = A11y.label(one.first); if (l != null && (contentDescription == null || contentDescription == autoCd)) { contentDescription = l; autoCd = l } }
        else if (autoCd != null && contentDescription == autoCd) { contentDescription = null; autoCd = null }
    }
    /** (v96) نص = أيقونة واحدة بس → بنرسمها في نص الزرار مباشرة بلون النص (أبيض) بدل الـ Span */
    override fun onDraw(canvas: Canvas) {
        val one = oneIcon()
        if (one == null || width <= 0 || height <= 0) { super.onDraw(canvas); return }
        val aw = (width - paddingLeft - paddingRight).toFloat(); val ah = (height - paddingTop - paddingBottom).toFloat()
        var s = textSize * one.second
        val cap = minOf(aw, ah) * 0.9f
        if (cap > 0f && s > cap) s = cap
        val cx = paddingLeft + aw / 2f; val cy = paddingTop + ah / 2f
        Icons.draw(canvas, one.first, cx - s / 2f, cy - s / 2f, s, currentTextColor)
    }
    override fun append(text: CharSequence?, start: Int, end: Int) {
        super.append(text, start, end)
        icT = null
        (getText() as? Spannable)?.let { Icons.applyInPlace(it) }
    }
}

open class IconButton(ctx: Context) : Button(ctx) {
    private var autoCd: String? = null
    private var icT: CharSequence? = null
    private var icV: Pair<String, Float>? = null
    private fun oneIcon(): Pair<String, Float>? {
        val t: CharSequence? = text
        if (t === icT) return icV
        icT = t; icV = Icons.singleIcon(t)
        return icV
    }
    override fun setText(text: CharSequence?, type: BufferType?) {
        icT = null
        super.setText(Icons.convert(text), type)
        val one = Icons.singleIcon(text)
        if (one != null) { val l = A11y.label(one.first); if (l != null && (contentDescription == null || contentDescription == autoCd)) { contentDescription = l; autoCd = l } }
        else if (autoCd != null && contentDescription == autoCd) { contentDescription = null; autoCd = null }
    }
    /** (v535) زرار نصه رمز واحد (↩ 🏠 ☰ 🎤 ⏻ ▲ ▼ …): بنرسم الأيقونة مباشرة — الـ Span لوحده كان بيطلّعه فاضي في الأزرار */
    override fun onDraw(canvas: Canvas) {
        val one = oneIcon()
        if (one == null || width <= 0 || height <= 0) { super.onDraw(canvas); return }
        val aw = (width - paddingLeft - paddingRight).toFloat(); val ah = (height - paddingTop - paddingBottom).toFloat()
        var s = textSize * one.second
        val cap = minOf(aw, ah) * 0.9f
        if (cap > 0f && s > cap) s = cap
        Icons.draw(canvas, one.first, paddingLeft + aw / 2f - s / 2f, paddingTop + ah / 2f - s / 2f, s, currentTextColor)
    }
}

@Suppress("DEPRECATION")
open class IconSwitch(ctx: Context) : Switch(ctx) {
    override fun setText(text: CharSequence?, type: BufferType?) { super.setText(Icons.convert(text), type) }
}

/** (v97) زرار أيقونة مرسومة مباشرة (View عادي من غير TextView) — لزرار القايمة اللي كان بيطلع أسود */
class IconGlyphButton(ctx: Context, iconName0: String) : android.view.View(ctx) {
    var iconName: String = iconName0
        set(v) { field = v; invalidate() }
    init { contentDescription = A11y.label(iconName0) }
    override fun onDraw(canvas: Canvas) {
        val sz = minOf(width, height) * 0.56f
        if (sz <= 0f) return
        Icons.draw(canvas, iconName, (width - sz) / 2f, (height - sz) / 2f, sz, Color.WHITE)
    }
}
