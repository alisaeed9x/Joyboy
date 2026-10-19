package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * (v418) خطوات فيديو لينجو (VideoLingo) في الترجمة، على جيميناي بمفاتيحك أو Gemma المحلي:
 *  1) ملخص الفيديو + مصطلحات (مرة واحدة ويتجدد مرة لما النص يكبر)
 *  2) ترجمة حرفية للدفعة (direct) بصيغة JSON
 *  3) مراجعة وتحرير (reflect → free) بالعامية المصرية، ولو فشلت بنكمّل بالحرفية
 * الإعدادات: vl_mode = on|off (الافتراضي on) · vl_reflect = on|off (الافتراضي on)
 */
object VideoLingo {
    fun on(): Boolean = Cfg.str("vl_mode", "on") != "off"
    private fun reflectOn(): Boolean = Cfg.str("vl_reflect", "on") != "off"

    private const val TARGET = "Egyptian Arabic (colloquial spoken Egyptian, اللهجة المصرية العامية)"

    /** محرك نص: جيميناي بمفاتيحك أو Gemma المحلي. [chat] (system, user, maxTokens) → نص الرد */
    class Backend(val id: String, val label: String, val local: Boolean = false, val chat: (String, String, Int) -> String)

    private class Term(val src: String, val tgt: String, val note: String)
    private class Theme(val theme: String, val terms: List<Term>, val chars: Int, val at: Long)
    private class Prev(val src: List<String>, val tr: List<String>, val at: Long)

    private val themes = ConcurrentHashMap<String, Theme>()
    private val seen = ConcurrentHashMap<String, StringBuilder>()
    private val prevMap = ConcurrentHashMap<String, Prev>()

    private fun langName(sl: String): String = when (sl) {
        "en" -> "English"; "ja" -> "Japanese"; "ko" -> "Korean"; "zh", "zh-CN" -> "Chinese"; "ru" -> "Russian"
        "fr" -> "French"; "es" -> "Spanish"; "de" -> "German"; "it" -> "Italian"; "tr" -> "Turkish"
        "hi" -> "Hindi"; "pt" -> "Portuguese"; "auto", "" -> "the source language"; else -> sl
    }

    private fun clean(raw: String): String = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    private fun parseObj(raw: String): JSONObject? {
        val s = clean(raw)
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(s.substring(a, b + 1)) } catch (_: Exception) { null }
    }

    // ===== 1) الملخص والمصطلحات =====
    private fun themeFor(b: Backend, texts: List<String>, sl: String): Theme? {
        val now = System.currentTimeMillis()
        val sb = seen.getOrPut(b.id) { StringBuilder() }
        synchronized(sb) {
            val cur = themes[b.id]
            if (cur != null && now - cur.at > 30 * 60_000L) { themes.remove(b.id); sb.setLength(0) }
            if (sb.length < 3000) sb.append(texts.joinToString("\n")).append('\n')
        }
        val have = themes[b.id]
        val grown = have != null && have.chars < 1200 && sb.length >= 2200
        if (have != null && !grown) return have
        val src = synchronized(sb) { sb.toString().take(3000) }
        if (src.length < 120 && have == null) return null   // دفعة صغيرة قوي: مفيش ملخص يستاهل
        val lang = langName(sl)
        val prompt = """
## Role
You are a video translation expert and terminology consultant, specializing in $lang comprehension and Egyptian Arabic expression.

## Task
For the provided $lang video text:
1. Summarize the main topic in two sentences (write the summary in English)
2. Extract professional terms and names with Egyptian Arabic translations (or keep the original)
3. Give a very brief explanation for each term. Extract fewer than 12 terms.

## INPUT
<text>
$src
</text>

## Output in only JSON format and no other text
{"theme": "Two-sentence video summary", "terms": [{"src": "$lang term", "tgt": "Arabic translation or original", "note": "Brief explanation"}]}
""".trim()
        val th = try {
            val o = parseObj(b.chat("You output only valid JSON.", prompt, 1800))
            if (o == null) null else {
                val arr = o.optJSONArray("terms") ?: JSONArray()
                val ts = ArrayList<Term>()
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val s = t.optString("src", "").trim(); val g = t.optString("tgt", "").trim()
                    if (s.isNotEmpty() && g.isNotEmpty()) ts.add(Term(s, g, t.optString("note", "").trim()))
                }
                Theme(o.optString("theme", "").trim(), ts, src.length, now)
            }
        } catch (x: ExtLlm.LlmErr) { if (x.code == 401 || x.code == 403 || x.code == 429) throw x else null } catch (_: Exception) { null }
        if (th != null) themes[b.id] = th
        return th ?: have
    }

    /** نقط للانتباه: مصطلحات الملخص اللي ظهرت في الدفعة + قاموس الطب الداخلي (TermNotes) */
    private fun notes(th: Theme?, joined: String): String {
        val low = joined.lowercase()
        val out = ArrayList<String>()
        th?.terms?.forEach { if (low.contains(it.src.lowercase())) out.add("- ${it.src}: ${it.tgt}" + (if (it.note.isNotEmpty()) " (${it.note})" else "")) }
        try {
            if (TermNotes.enabled()) TermNotes.match(joined).take(8).forEach { en -> out.add("- ${en.keys.firstOrNull() ?: ""}: ${en.ar}" + (if (en.note.isNotEmpty()) " (${en.note})" else "")) }
        } catch (_: Throwable) {}
        return if (out.isEmpty()) "None" else out.distinct().joinToString("\n")
    }

    private fun shared(prev: Prev?, th: Theme?, joined: String): String {
        val p = if (prev == null || prev.src.isEmpty()) "None" else prev.src.joinToString("\n")
        return "### Context Information\n<previous_content>\n$p\n</previous_content>\n\n### Content Summary\n" +
            (th?.theme?.ifEmpty { null } ?: "None") + "\n\n### Points to Note\n" + notes(th, joined)
    }

    private fun jsonTemplate(lines: List<String>, f: (Int, String) -> JSONObject): String {
        val o = JSONObject()
        lines.forEachIndexed { i, l -> o.put("${i + 1}", f(i + 1, l)) }
        return o.toString(2)
    }

    private fun validKeys(o: JSONObject?, n: Int, sub: String): Boolean {
        if (o == null) return false
        for (i in 1..n) { val v = o.optJSONObject("$i") ?: return false; if (!v.has(sub)) return false }
        return true
    }

    // ===== 2) الترجمة الحرفية =====
    private fun faithful(b: Backend, lines: List<String>, sl: String, shared: String): List<String>? {
        val lang = langName(sl)
        val fmt = jsonTemplate(lines) { i, l -> JSONObject().put("origin", l).put("direct", "direct Egyptian Arabic translation $i.") }
        val prompt = """
## Role
You are a professional Netflix subtitle translator, fluent in both $lang and Egyptian Arabic, as well as their cultures.
You accurately understand the semantics and structure of the original $lang text and faithfully translate it into $TARGET while preserving the original meaning.

## Task
Translate the original $lang subtitles into $TARGET line by line.
1. Be faithful to the original, accurately conveying its meaning
2. Consider the context and professional terminology
3. Keep names, numbers and brand names; never merge, split, skip or add lines; do not censor

$shared

<translation_principles>
1. Faithful to the original: do not arbitrarily change, add or omit content.
2. Accurate terminology: use professional terms correctly and consistently.
3. Understand the context.
</translation_principles>

## INPUT
<subtitles>
${lines.joinToString("\n")}
</subtitles>

## Output in only JSON format and no other text
$fmt
""".trim()
        val maxTok = (lines.sumOf { it.length } * 4 + 900).coerceIn(1200, 7000)
        for (attempt in 0..2) {
            val o = parseObj(b.chat("You are a professional subtitle translator. Output only valid JSON.", prompt + " ".repeat(attempt), maxTok))
            if (validKeys(o, lines.size, "direct")) return List(lines.size) { o!!.getJSONObject("${it + 1}").optString("direct", "").replace('\n', ' ').trim() }
        }
        return null
    }

    // ===== 3) المراجعة والتحرير (reflect → free) =====
    private fun express(b: Backend, lines: List<String>, direct: List<String>, sl: String, shared: String): List<String>? {
        val lang = langName(sl)
        val fmt = jsonTemplate(lines) { i, l ->
            JSONObject().put("origin", l).put("direct", direct[i - 1]).put("reflect", "your reflection on direct translation").put("free", "your free translation")
        }
        val prompt = """
## Role
You are a professional Netflix subtitle translator and language consultant.
You not only understand the original $lang accurately but also optimize the $TARGET translation to suit how Egyptians actually speak.

## Task
We already have a direct translation of the original $lang subtitles. Reflect on it and improve it into natural, fluent $TARGET subtitles.
1. Analyze the direct translation line by line, pointing out issues
2. Provide a free translation based on your analysis
3. Do not add comments or explanations inside the translation (it is for the audience to read)
4. Do not leave empty lines in the free translation; keep each line short like a subtitle

$shared

<analysis_steps>
1. Direct translation reflection: language fluency, style consistency with the original, conciseness (point out wordiness).
2. Free translation: natural colloquial Egyptian, easy for the audience to understand; match the tone of the content (casual for tutorials, formal for documentaries, professional terms for technical content).
</analysis_steps>

## INPUT
<subtitles>
${lines.joinToString("\n")}
</subtitles>

## Output in only JSON format and no other text
$fmt
""".trim()
        val maxTok = (lines.sumOf { it.length } * 8 + 1200).coerceIn(1800, 8000)
        for (attempt in 0..1) {
            val o = parseObj(b.chat("You are a professional subtitle translator. Output only valid JSON.", prompt + " ".repeat(attempt), maxTok))
            if (validKeys(o, lines.size, "free")) return List(lines.size) { o!!.getJSONObject("${it + 1}").optString("free", "").replace('\n', ' ').trim() }
        }
        return null
    }

    private val gemRr = java.util.concurrent.atomic.AtomicInteger(0)
    private val gemBackend = Backend("vl_gemini", "جيميناي + لينجو") { sys, usr, mx ->
        val keys = Cfg.allMainKeys()
        if (keys.isEmpty()) throw ExtLlm.LlmErr(401, "مفيش مفتاح جيميناي")
        val model = Cfg.snapshot().model
        val start = gemRr.getAndIncrement()
        var last: ExtLlm.LlmErr? = null
        var out: String? = null
        for (i in keys.indices) {
            val k = keys[Math.floorMod(start + i, keys.size)]
            try { out = Api.generate(model, k, sys + "\n\n" + usr, null, mx, 0.2, true, false).text; break }
            catch (x: ApiErr) { last = ExtLlm.LlmErr(x.code, "جيميناي: " + (x.message ?: "").take(120)); if (x.code != 429 && x.code != 403 && x.code < 500) throw last }
        }
        out ?: throw (last ?: ExtLlm.LlmErr(429, "كل مفاتيح جيميناي واقفة مؤقتًا", 60))
    }

    /** (v420) Gemma محلي على الجهاز (LocalLlm) — من غير نت. ملخص/مراجعة مقفولين افتراضيًا عشان السرعة (vl_local_summary / vl_local_reflect) */
    private val localBackend = Backend("vl_local", "Gemma محلي + لينجو", true) { sys, usr, mx -> LocalLlm.chat(sys, usr, mx) }
    fun translateLocal(texts: List<String>, sl: String): List<String?> = translate(localBackend, texts, sl)

    /** جيميناي بخطوات لينجو (للمزوّد vl_gemini في ExtTranslate) */
    fun translateGemini(texts: List<String>, sl: String): List<String?> = translate(gemBackend, texts, sl)

    /** نفس الطول، والفاضي = null. بيرمي ExtLlm.LlmErr لو المفاتيح/الحد وقفوا */
    fun translate(b: Backend, texts: List<String>, sl: String): List<String?> {
        if (texts.isEmpty()) return emptyList()
        val lines = texts.map { it.replace('\n', ' ').trim() }
        val joined = lines.joinToString("\n")
        val th = if (b.local && Cfg.str("vl_local_summary", "off") != "on") null else themeFor(b, lines, sl)
        val now = System.currentTimeMillis()
        val pv = prevMap[b.id]?.takeIf { now - it.at < 120_000L }
        val sh = shared(pv, th, joined)
        val direct = faithful(b, lines, sl, sh) ?: throw Exception("${b.label}: الترجمة الحرفية مش بنفس عدد الأسطر")
        val finalList = if (if (b.local) Cfg.str("vl_local_reflect", "off") == "on" else reflectOn()) (try { express(b, lines, direct, sl, sh) } catch (x: ExtLlm.LlmErr) { if (x.code == 401 || x.code == 403) throw x else null } catch (_: Exception) { null }) ?: direct else direct
        val res: List<String?> = finalList.map { it.trim().takeIf { s -> s.isNotEmpty() } }
        val ok = lines.indices.filter { res[it] != null }.takeLast(3)
        prevMap[b.id] = Prev(ok.map { lines[it] + " → " + res[it] }, ok.map { res[it] ?: "" }, now)
        return res
    }
}
