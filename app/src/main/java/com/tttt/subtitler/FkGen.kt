package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** (v1275) توليد أسئلة «فتح كلام» بـGemini بمفاتيح التطبيق كلها بالتناوب (round-robin) وبالتوازي: كل جزء بيبدأ من مفتاح مختلف ولو فشل بيجرّب اللي بعده. */
object FkGen {
    private val rr = AtomicInteger(0)
    private const val SAFETY = "[{\"category\":\"HARM_CATEGORY_SEXUALLY_EXPLICIT\",\"threshold\":\"BLOCK_NONE\"},{\"category\":\"HARM_CATEGORY_HARASSMENT\",\"threshold\":\"BLOCK_NONE\"},{\"category\":\"HARM_CATEGORY_HATE_SPEECH\",\"threshold\":\"BLOCK_NONE\"},{\"category\":\"HARM_CATEGORY_DANGEROUS_CONTENT\",\"threshold\":\"BLOCK_NONE\"}]"

    private fun prompt(n: Int, cat: String, b: Int, r: Int, d: Int, mod: String, avoid: List<String>): String {
        val adult = if (cat in FkData.ADULT) "\nADULT/COUPLES MODE (married or engaged couples only): confidently bold, direct, intimate and private. Cover attraction, chemistry, preferences, boundaries and memorable private moments. Do not sanitize into generic romance. Keep it non-graphic: no step-by-step explicit acts, no erotic fiction. Everything is between consenting adult partners.\n" else ""
        return "ROLE: You write conversation-opening questions for an Arabic conversation game. Write EXCLUSIVELY in heavy street-level Egyptian colloquial Arabic (عامية مصرية شعبية), the way people actually talk in a WhatsApp voice note or a café. Never Modern Standard Arabic, never stiff semi-formal Arabic. This applies to every category equally.\n\n" +
            "TASK: Generate exactly $n DIFFERENT questions in one batch.\nCATEGORY: ${FkData.nameOf(cat)}\nBOLDNESS: $b/100\nROMANCE: $r/100\nDEPTH: $d/100\nMODIFIER: ${if (mod.isBlank()) "None" else mod}\n$adult\n" +
            "QUALITY BAR: hyper-specific, not a generic template. Force a specific scene, memory, number, dilemma or forced choice. It should sound like a clever, funny, emotionally sharp friend asking at 2am. Push past the obvious first ideas. Short punchy spoken-rhythm sentences. Never yes/no questions.\n" +
            "RULES: No two questions in the batch share a concept. Do not repeat or paraphrase the recent questions below. Spell every word correctly with no extra or missing letters. Never use MSA words like ماذا، كيف، أين، متى، لماذا، الآن، هذا، سوف.\n\n" +
            "RECENT QUESTIONS TO AVOID:\n" + avoid.joinToString("\n") { "- $it" } + "\n\n" +
            "OUTPUT: JSON array only, no markdown. Each item: {\"text\":\"...\",\"boldness\":0-100,\"romance\":0-100,\"depth\":0-100,\"concept\":\"short english slug\"}"
    }

    private fun call(key: String, prompt: String): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 1.0).put("maxOutputTokens", 8000).put("responseMimeType", "application/json"))
            .put("safetySettings", JSONArray(SAFETY))
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val c = URL(Api.base + "/models/" + Models.DEFAULT + ":generateContent").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 90000
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key); c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val txt = (if (code < 300) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code >= 300) throw ApiErr(code, try { JSONObject(txt).getJSONObject("error").getString("message") } catch (_: Exception) { "HTTP $code" })
            val cand = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0) ?: throw Exception("الرد فاضي")
            val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: throw Exception("الرد فاضي")
            val sb = StringBuilder(); for (i in 0 until ps.length()) sb.append(ps.optJSONObject(i)?.optString("text", "") ?: "")
            return sb.toString()
        } finally { c.disconnect() }
    }

    private fun parse(raw: String, cat: String): List<FkData.Q> {
        var t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val i = t.indexOf('['); val j = t.lastIndexOf(']')
        if (i < 0 || j <= i) throw Exception("الرد مش JSON")
        t = t.substring(i, j + 1)
        val a = JSONArray(t); val out = ArrayList<FkData.Q>()
        for (k in 0 until a.length()) {
            val o = a.optJSONObject(k) ?: continue
            val tx = o.optString("text", "").trim(); if (tx.length < 8) continue
            out.add(FkData.Q("g" + System.currentTimeMillis() + "_" + k + "_" + (Math.random() * 1000).toInt(), cat, tx, o.optInt("boldness", 30), o.optInt("romance", 30), o.optInt("depth", 30), true))
        }
        return out
    }

    /** بيرجّع الأسئلة الجديدة اللي اتضافت (بعد فلترة المكرر). بيرمي Exception برسالة واضحة لو كله فشل. Blocking: يتنده من thread. */
    fun generate(ctx: android.content.Context, cat: String, mod: String, n: Int): List<FkData.Q> {
        val keys = (try { Cfg.ensure(ctx); Cfg.allMainKeys() } catch (_: Throwable) { emptyList<String>() }).map { it.trim() }.filter { it.isNotEmpty() && it != LocalKey.ID }
        if (keys.isEmpty()) throw Exception("مفيش مفاتيح جيميناي في التطبيق. ضيفها من الإعدادات ← المفاتيح")
        val chunks = minOf(keys.size, 4, maxOf(1, n / 5)); val per = (n + chunks - 1) / chunks
        val avoid = FkData.recent().take(15)
        val results = arrayOfNulls<List<FkData.Q>>(chunks); val errs = arrayOfNulls<String>(chunks)
        val th = ArrayList<Thread>()
        for (ci in 0 until chunks) {
            val start = Math.floorMod(rr.getAndIncrement(), keys.size)
            val t = Thread {
                val p = prompt(per, cat, FkData.b(), FkData.r(), FkData.d(), mod, avoid)
                var last = ""
                for (round in 0 until 3) {   // (v1277) 503/500 = ضغط على موديل جيميناي (مش مشكلة مفتاح): لفّة على كل المفاتيح وبعدين استنى وجرّب تاني
                    var server = false
                    for (s in 0 until keys.size) {
                        val key = keys[(start + s + round) % keys.size]
                        try { results[ci] = parse(call(key, p), cat); return@Thread } catch (e: Throwable) { last = e.message ?: e.javaClass.simpleName; if (e is ApiErr && e.code >= 500) server = true }
                    }
                    if (!server) break
                    try { Thread.sleep(2500L * (round + 1)) } catch (_: Throwable) {}
                }
                errs[ci] = last
            }
            t.isDaemon = true; t.start(); th.add(t)
        }
        for (t in th) try { t.join(120000) } catch (_: Throwable) {}
        val seen = HashSet<String>(); for (q in FkData.all()) seen.add(FkData.norm(q.text))
        val fresh = ArrayList<FkData.Q>()
        for (l in results) if (l != null) for (q in l) if (seen.add(FkData.norm(q.text))) fresh.add(q)
        if (fresh.isEmpty()) throw Exception(errs.firstOrNull { it != null } ?: "ماجاش أسئلة جديدة، جرّب تاني")
        FkData.addGenerated(fresh)
        return fresh
    }
}
