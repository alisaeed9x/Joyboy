package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * (v688) ترجمة ملف الترجمة بطريقتين مع بعض: التلفزيون + الموبايل. أول واحد يخلّص هو اللي بيتطبّق والتاني بيقف.
 *
 * ليه: لو التلفزيون (أو الواي فاي بتاعه) مفيهوش نت، الترجمة كانت بتفشل والفيديو بيفضل بنصه الأصلي.
 * دلوقتي الموبايل (اللي عليه نت) بيترجم نفس الجمل ويبعت النتيجة للتلفزيون على قطع (/subtr).
 *
 * الجانبين:
 *  - SfJob: حالة الشغلانة على التلفزيون (Player بيكتب فيها، TvLink بيقرا منها).
 *  - SubDual: الموبايل — بيسحب الجمل من التلفزيون (لو الملف مش عنده)، بيترجم، وبيبعت النتيجة.
 */
object SfJob {
    @Volatile var id = ""
    @Volatile var cues: List<SubFileTr.Cue>? = null
    @Volatile var winner = ""          // "" | "tv" | "phone"
    @Volatile var phoneSeen = 0L       // آخر مرة الموبايل سأل عن الحالة
    @Volatile var phoneLine = ""       // تقدّم الموبايل (نص بيتعرض على شريط التلفزيون)
    @Volatile var tvFailed = false

    @Synchronized fun begin(jid: String) { id = jid; cues = null; winner = ""; phoneSeen = 0L; phoneLine = ""; tvFailed = false }

    /** أول واحد بيطلب بيكسب. false = اللي قبلك خلّص (أو الشغلانة اتغيّرت) */
    @Synchronized fun claim(jid: String, who: String): Boolean {
        if (id != jid || winner.isNotEmpty()) return false
        winner = who; return true
    }

    fun phoneAlive(): Boolean = phoneSeen > 0L && System.currentTimeMillis() - phoneSeen < 25_000L
}

object SubDual {
    class Run(val jid: String) {
        @Volatile var cancel = false
        @Volatile var done = false
    }

    @Volatile private var cur: Run? = null
    /** سطر حالة للعرض في الورقة («📱 بترجم 12/40» …) — فاضي لو مفيش حاجة شغّالة */
    @Volatile var status: String = ""

    fun newJid(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    fun running(): Boolean = cur?.let { !it.cancel && !it.done } == true

    fun cancel() { cur?.cancel = true; status = "" }

    private fun post(path: String, j: JSONObject, ms: Int): Pair<Int, JSONObject?> {
        val (c, t) = try { RemoteClient.postJsonMs(path, j, ms) } catch (e: Throwable) { Pair(-1, "") }
        val o = try { if (t.isNotBlank()) JSONObject(t) else null } catch (_: Throwable) { null }
        return Pair(c, o)
    }

    /**
     * بيبدأ مسار الموبايل بالتوازي مع التلفزيون (التلفزيون بيبدأ لوحده بأمر sfgo اللي فيه نفس jid).
     * text = نص الملف لو معروف (ملف اتختار من الموبايل)؛ null = الجمل بتتسحب من التلفزيون.
     * say بتتنادى من خيط خلفي — اللي بينادي مسؤول إنه يرجّعها للواجهة.
     */
    fun start(jid: String, text: String?, engIn: String, dia: String, sty: String, str: String, say: (String) -> Unit) {
        try { cur?.cancel = true } catch (_: Throwable) {}
        val run = Run(jid); cur = run
        status = "📱 بجهّز الترجمة على الموبايل…"
        var lastLine = ""
        var tvWon = false
        var miss = 0

        // نبض: بيعرّف التلفزيون إن الموبايل شغّال، وبيعرف لو التلفزيون خلّص الأول
        Thread {
            try {
                while (!run.cancel && !run.done) {
                    val (c, o) = post("/subget", JSONObject().put("jid", jid).put("a", "status").put("pl", lastLine), 6000)
                    if (c == 200 && o != null) {
                        if (!o.optBoolean("job", false)) miss++ else miss = 0
                        if (o.optString("winner") == "tv") { tvWon = true; run.cancel = true; status = ""; say("📺 التلفزيون خلّص الترجمة الأول — وقّفت الموبايل"); break }
                        if (miss >= 8) { run.cancel = true; status = ""; say("⚠ التلفزيون مش بيترجم الملف ده دلوقتي — وقّفت ترجمة الموبايل"); break }
                    }
                    try { Thread.sleep(2500L) } catch (_: InterruptedException) {}
                }
            } catch (e: Throwable) { LogStore.err("SubDual:beat", e) }
        }.also { it.isDaemon = true }.start()

        Thread {
            try {
                // 1) الجمل
                var cues: List<SubFileTr.Cue> = emptyList()
                if (text != null) cues = SubFileTr.parse(text)
                else {
                    status = "📡 باخد الجمل من التلفزيون…"
                    var tries = 0
                    while (cues.isEmpty() && !run.cancel && tries++ < 45) {
                        val (c, o) = post("/subget", JSONObject().put("jid", jid).put("a", "cues"), 20000)
                        val arr = if (c == 200 && o != null) o.optJSONArray("cues") else null
                        if (arr != null && arr.length() > 0) {
                            val l = ArrayList<SubFileTr.Cue>(arr.length())
                            for (k in 0 until arr.length()) { val a = arr.optJSONArray(k) ?: continue; l.add(SubFileTr.Cue(a.optDouble(0, 0.0), a.optDouble(1, 0.0), a.optString(2, ""))) }
                            cues = l
                        } else try { Thread.sleep(1500L) } catch (_: InterruptedException) {}
                    }
                }
                if (run.cancel) return@Thread
                if (cues.isEmpty()) { status = ""; say("⚠ مقدرتش أجيب جمل الترجمة من التلفزيون"); return@Thread }

                // 2) الترجمة على الموبايل (النت بتاع الموبايل)
                val eng = if (engIn == "gemini" && SubFileTr.hasGemKeys()) "gemini" else "google"
                if (engIn == "gemini" && eng == "google") { say("⚠ مفيش مفتاح جيميناي على الموبايل — الموبايل مش هيترجم (ما حوّلتش لجوجل عشان اللهجة) والتلفزيون بيكمّل"); status = ""; return@Thread }
                val lang = dia.ifBlank { "فصحى" }
                val style = SubFileTr.styleOf(sty, str, Cfg.str("style", "تلقائي").ifBlank { "تلقائي" })
                val engName = if (eng == "gemini") "جيميناي" else "جوجل"
                say("📱 الموبايل بيترجم " + cues.size + " جملة بـ " + engName + " (التلفزيون كمان بيحاول)")
                var lastSay = 0L
                val out = SubFileTr.translate(cues, eng, { pr ->
                    lastLine = pr; status = "📱 $pr"
                    val n = System.currentTimeMillis(); if (n - lastSay > 6000L) { lastSay = n; say("📱 $pr") }
                }, { run.cancel }, lang, style)
                if (run.cancel) return@Thread
                if (out.error.isNotEmpty()) { status = ""; say("⚠ ترجمة الموبايل فشلت: " + out.error); return@Thread }
                // (v711) أغلب الجمل فشلت: ما نبعتهاش — التلفزيون كان بيحط مكانها النص الأصلي ويعرضه كإنه مترجم
                if (out.failed * 2 > cues.size) { status = ""; LogStore.add("📱 سوفت صب: فشل " + out.failed + "/" + cues.size + " على الموبايل (" + eng + ")"); say("⚠ ترجمة الموبايل فشل أغلبها (" + out.failed + "/" + cues.size + ") — ما بعتّهاش للتلفزيون"); return@Thread }

                // 3) نبعت النتيجة للتلفزيون على قطع
                status = "📤 ببعت الترجمة للتلفزيون…"
                val arr = JSONArray(); for (t in out.texts) arr.put(t)
                val payload = JSONObject().put("x", arr).put("f", out.failed).put("e", eng).toString()
                val parts = ArrayList<String>()
                var s = 0
                while (s < payload.length) {
                    var e = minOf(s + 50000, payload.length)
                    if (e < payload.length && Character.isHighSurrogate(payload[e - 1])) e--
                    parts.add(payload.substring(s, e)); s = e
                }
                for ((i, p) in parts.withIndex()) {
                    if (run.cancel) return@Thread
                    var res: Pair<Int, JSONObject?> = Pair(-1, null)
                    for (att in 0 until 3) {
                        res = post("/subtr", JSONObject().put("jid", jid).put("i", i).put("t", parts.size).put("d", p), 25000)
                        if (res.first != -1) break
                        try { Thread.sleep(1200L) } catch (_: InterruptedException) {}
                    }
                    val (c, o) = res
                    if (c == -1) { status = ""; say("⚠ مقدرتش أبعت الترجمة للتلفزيون — مش متصل"); return@Thread }
                    if (c == 409) { status = ""; say("⚠ التلفزيون مش مستني الترجمة دي (الفيديو اتغيّر؟)"); return@Thread }
                    if (c != 200) { status = ""; say("⚠ فشل إرسال الترجمة للتلفزيون (كود $c)"); return@Thread }
                    if (o != null && o.optBoolean("lost", false)) { status = ""; if (!tvWon) say("📺 التلفزيون خلّص الأول"); return@Thread }
                    if (o != null && o.optBoolean("none", false)) { status = ""; say("⚠ مفيش مشغّل فيديو شغّال على التلفزيون"); return@Thread }
                    if (o != null && o.optBoolean("applied", false)) { status = ""; say("✅ الموبايل خلّص الأول وطبّق الترجمة على التلفزيون (" + out.texts.size + " جملة)") }
                }
                status = ""
            } catch (e: Throwable) { LogStore.err("SubDual:run", e); status = ""; say("⚠ ترجمة الموبايل وقفت: " + (e.message ?: e.javaClass.simpleName).take(60)) }
            finally { run.done = true }
        }.also { it.isDaemon = true }.start()
    }
}
