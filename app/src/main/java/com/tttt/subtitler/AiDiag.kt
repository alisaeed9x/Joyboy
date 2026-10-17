package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * (v393) اختيار تلقائي لأنسب موديل للترجمة (عربي) من قايمة موديلات جت من الخدمة نفسها — من غير ما تختار.
 * الترتيب تقديري من الاسم (مش من الخدمة): عيلات قوية في العربي أول، السريع (flash/mini/haiku) مفضّل لأن الترجمة باتشات كتير،
 * والتفكير/الكود/الصغير جدًا/الرؤية ينزلوا. لو اخترت موديل بإيدك بيتجرّب هو الأول.
 */
object TranslatePick {
    fun score(raw: String): Int {
        val l = raw.lowercase()
        var s = when {
            l.contains("gemini") -> 90
            l.contains("claude") -> 88
            l.contains("gpt-5") || l.contains("gpt-4.1") || l.contains("gpt-4o") -> 86
            l.contains("qwen") -> 80
            l.contains("deepseek") -> 78
            l.contains("glm") -> 76
            l.contains("kimi") -> 76
            l.contains("llama") -> 70
            l.contains("mistral") || l.contains("mixtral") -> 68
            l.contains("minimax") || l.contains("big-pickle") || l.contains("mimo") -> 62
            l.contains("nemotron") -> 60
            else -> 40
        }
        if (Regex("flash|haiku|mini|turbo|instant|lite|fast").containsMatchIn(l)) s += 8
        if (Regex("\\br1\\b|reason|think|\\bo1\\b|\\bo3\\b|\\bo4\\b|qwq|magistral").containsMatchIn(l)) s -= 40
        if (Regex("coder|codex|codestral|devstral|-code").containsMatchIn(l)) s -= 25
        if (Regex("(^|[^0-9.])(1|2|3|4|7|8)b\\b").containsMatchIn(l)) s -= 30
        if (Regex("vision|-vl|ocr|embed|image").containsMatchIn(l)) s -= 20
        if (l.contains("opus") || l.contains("405b")) s -= 12
        return s
    }

    /** موديلات الشات بس (من غير auto/… ومن غير تفريغ/صور/embeddings) مرتّبة الأنسب للترجمة الأول */
    fun rank(ids: List<String>): List<String> = ids
        .filter { AiModels.isChat(it) && ExtLlm.skipWords.none { w -> it.contains(w, true) } && !it.startsWith("auto", true) }
        .distinct()
        .sortedByDescending { score(it) }
}

/**
 * (v393) 🩺 فحص شامل لأومني روت وأوبن كود — بيوري السبب الحقيقي خطوة خطوة (بدل ما الخطأ يتبلع ويتحوّل لجيميناي من ورا ضهرك):
 * Termux · السيرفر (كل العناوين) · المفتاح · قايمة الموديلات (حقيقية ولا احتياطية) · تجربة ترجمة بزمنها · إمكانية التفريغ الصوتي.
 * لازم يتنادى من thread خلفية. [say] بتتنادى بكل سطر.
 */
object AiDiag {
    /** سبب مفهوم بالعربي لأي استثناء شبكة/خدمة */
    fun explain(e: Throwable): String = when (e) {
        is ConnectException -> "مفيش حاجة شغّالة على العنوان ده (Connection refused) — السيرفر مش شغّال"
        is UnknownHostException -> "العنوان مش موجود أو مفيش نت (" + (e.message ?: "") + ")"
        is SocketTimeoutException -> "مهلة — الخدمة مردّتش في الوقت"
        is ExtLlm.LlmErr -> e.message ?: ("HTTP " + e.code)
        else -> e.message ?: e.javaClass.simpleName
    }

    private class Hit(val code: Int, val ms: Long, val body: String, val err: Throwable?)

    private fun get(url: String, key: String, readMs: Int): Hit {
        val t0 = System.currentTimeMillis()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "GET"; c.connectTimeout = 4000; c.readTimeout = readMs
                if (key.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                Hit(code, System.currentTimeMillis() - t0, txt, null)
            } finally { try { c.disconnect() } catch (_: Throwable) {} }
        } catch (e: Throwable) { Hit(0, System.currentTimeMillis() - t0, "", e) }
    }

    private fun ids(body: String): List<String> = try {
        val arr: JSONArray? = JSONObject(body).optJSONArray("data")
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("id", "")?.takeIf { s -> s.isNotBlank() } }
    } catch (_: Throwable) { emptyList() }

    private const val SYS = "You are a professional subtitle translator. Translate the line to Arabic. Output only the translation."
    private const val SAMPLE = "Hello, how are you today?"

    fun run(c: Context?, say: (String) -> Unit) {
        val verdict = ArrayList<String>()
        say("🩺 فحص شامل (v393)")

        // ===== عام =====
        say("\n— عام —")
        val gk = try { Cfg.allMainKeys().size } catch (_: Throwable) { 0 }
        say("🔑 مفاتيح جيميناي: $gk")
        val lxOk = c != null && Linux.supported(c)
        val lxReady = c != null && lxOk && Linux.ready(c)
        if (c != null && lxOk) say(Linux.summary(c))
        val tmx = c != null && OmniLauncher.termuxInstalled(c)
        val perm = c != null && tmx && OmniLauncher.termuxHasPerm(c)
        if (tmx || !lxOk) say(if (!tmx) "📱 Termux: مش متسطب ❌" else "📱 Termux: متسطب ✅ · إذن RUN_COMMAND: " + (if (perm) "✅" else "❌ (مطلوب)"))

        // ===== أومني روت =====
        say("\n— 🛰 OmniRoute —")
        val op = ExtLlm.byId("omniroute")!!
        val okey = ExtLlm.key(op)
        say("الرابط المحفوظ: " + ExtLlm.url(op) + " · المفتاح: " + (if (okey.isEmpty()) "(مفيش — عادي لو السيرفر على جهازك)" else "محفوظ"))
        var omniUp = false
        var omniIds: List<String> = emptyList()
        for (u in OmniAuto.candidates()) {
            if (omniUp) break
            val h = get("$u/models", okey, 8000)
            val e = h.err
            if (e != null) {
                say("• $u ← ❌ " + explain(e) + " (" + h.ms + "ms)")
            } else if (h.code in 200..299) {
                omniUp = true; omniIds = ids(h.body)
                say("• $u ← ✅ شغّال · " + omniIds.size + " موديل (" + h.ms + "ms)")
            } else if (h.code == 401 || h.code == 403) {
                say("• $u ← 🔑 السيرفر شغّال بس رفض المفتاح (HTTP " + h.code + ")")
                verdict.add("🛰 OmniRoute: السيرفر شغّال بس رافض المفتاح. المفتاح هناك لازم يكون «Endpoint/API key» بتعمله من لوحة OmniRoute نفسها — مش مفتاح مزوّد (جيميناي/أوبن راوتر…).")
            } else {
                say("• $u ← ⚠ " + ExtLlm.errMsg(h.code, h.body))
            }
        }
        if (omniUp) {
            val best = TranslatePick.rank(omniIds)
            if (best.isEmpty()) {
                say("⚠ السيرفر شغّال بس مفيش فيه موديلات شات — ضيف مزوّد (Providers) من لوحة OmniRoute")
                verdict.add("🛰 OmniRoute: شغّال بس من غير مزوّدين/موديلات — ضيف مزوّد واحد على الأقل من لوحته.")
            } else {
                say("🎯 الأنسب للترجمة (ترتيب تلقائي): " + best.take(5).joinToString(" · "))
                val stt = omniIds.filter { m -> Regex("whisper|transcrib|nova|deepgram|voxtral|scribe|speech", RegexOption.IGNORE_CASE).containsMatchIn(m) }
                say(if (stt.isNotEmpty()) "🎙 تفريغ صوت متاح: " + stt.take(4).joinToString(" · ") + " ← ينفع تبعت باتشات صوت" else "🎙 مفيش موديل تفريغ صوت هناك ← استخدم Whisper المحلي + ترجمة نصية")
                val m = best.first()
                val t0 = System.currentTimeMillis()
                try {
                    val r = ExtLlm.chatWith(op, m, SYS, SAMPLE, 200)
                    say("✅ تجربة ترجمة بـ $m: «" + r.trim().take(60) + "» (" + (System.currentTimeMillis() - t0) + "ms)")
                } catch (e: Throwable) {
                    say("❌ تجربة $m فشلت: " + explain(e) + " (" + (System.currentTimeMillis() - t0) + "ms)")
                    verdict.add("🛰 OmniRoute: السيرفر شغّال بس الموديل $m فشل — " + explain(e))
                }
            }
        } else if (verdict.none { it.startsWith("🛰") }) {
            if (lxOk && !lxReady) verdict.add("🛰 OmniRoute: البيئة الداخلية لسه ما اتجهزتش — افتح 💻 كونسول Linux وادوس 🚀 جهّز تلقائي (مرة واحدة، 15–30 دقيقة).")
            else if (lxOk) verdict.add("🛰 OmniRoute: السيرفر واقف — دوس 🔧 إصلاح تلقائي يشغّله وبيعمل المفتاح لوحده." + (if (Linux.lastError.isNotBlank()) " آخر خطأ: " + Linux.lastError.take(160) else ""))
            else if (!tmx) verdict.add("🛰 OmniRoute: مفيش سيرفر شغّال ومفيش Termux. أومني روت مش بيشتغل لوحده — هو سيرفر لازم يشتغل: على الموبايل عن طريق Termux، أو على سيرفر برا وتحط رابطه في ⚙ ضبط OmniRoute.")
            else if (!perm) verdict.add("🛰 OmniRoute: Termux موجود بس إذن RUN_COMMAND ناقص — دوس 🔧 إصلاح تلقائي ووافق على الإذن.")
            else verdict.add("🛰 OmniRoute: السيرفر واقف — دوس 🔧 إصلاح تلقائي يشغّله (أول مرة بياخد 5–10 دقايق تثبيت).")
            if (InternalRouter.hasPool()) say("ℹ جوه المشغّل: لو أومني روت واقف الترجمة بتكمّل بالراوتر الداخلي (جيميناي/مزوّديك) — ده مش أومني روت.")
        }

        // ===== أوبن كود =====
        say("\n— 🆓 OpenCode —")
        val ocKey = Cfg.str("llm_opencode_key").trim().ifEmpty { OpenCodeFree.BUILTIN_KEY }
        say("🔑 المفتاح: " + (if (ocKey.isEmpty()) "مفيش (الطلب بيروح مجهول)" else "محفوظ"))
        val cli = OpenCodeLocal.enabled()
        say("🧰 وضع Termux/CLI: " + (if (cli) "شغّال (الطلبات بتعدّي من opencode run)" else "مش متاح (محتاج Termux + إذن)"))
        val savedModels = Cfg.str("oc_models").split(',').count { it.isNotBlank() }
        say("📜 التجهيز التلقائي: " + OpenCodeAuto.state.name + " · موديلات CLI محفوظة: " + savedModels)
        val h = get(OpenCodeFree.BASE + "/models", ocKey.ifEmpty { "public" }, 12000)
        var free: List<String> = emptyList()
        val he = h.err
        if (he != null) {
            say("• القايمة من السيرفر ← ❌ " + explain(he) + " (" + h.ms + "ms)")
            say("⚠ القايمة اللي بتظهر في التطبيق في الحالة دي احتياطية جاهزة (أسماء مش جاية من السيرفر)")
            verdict.add("🆓 OpenCode: مقدرتش أوصل لسيرفر opencode.ai — " + explain(he))
        } else if (h.code in 200..299) {
            val all = ids(h.body)
            free = all.filter { OpenCodeFree.isFree(it) }
            say("• القايمة من السيرفر ← ✅ " + all.size + " موديل (" + free.size + " مجاني · " + (all.size - free.size) + " مدفوع) (" + h.ms + "ms)")
            if (free.isNotEmpty()) say("  مجاني: " + TranslatePick.rank(free).take(5).joinToString(" · "))
        } else {
            say("• القايمة من السيرفر ← ⚠ " + ExtLlm.errMsg(h.code, h.body))
            if (h.code == 401 || h.code == 403) verdict.add("🆓 OpenCode: السيرفر رفض المفتاح (HTTP " + h.code + ") — اتأكد إنه مفتاح Zen من opencode.ai/zen.")
        }
        val pick = TranslatePick.rank(free.ifEmpty { OpenCodeFree.list(false) }).firstOrNull()
        if (pick != null) {
            // اختبار مباشر على السيرفر (من غير تحويل لموديل تاني ومن غير Termux) عشان نشوف الرد الحقيقي
            val t0 = System.currentTimeMillis()
            try {
                val body = JSONObject().put("model", pick).put("max_tokens", 120).put("temperature", 0.2)
                    .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", SYS)).put(JSONObject().put("role", "user").put("content", SAMPLE))).toString()
                val txt = ExtLlm.post(OpenCodeFree.BASE + "/chat/completions", OpenCodeFree.headers(), body, 30000)
                val r = JSONObject(txt).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
                say("✅ طلب مباشر (API) بـ $pick: «" + r.trim().take(60) + "» (" + (System.currentTimeMillis() - t0) + "ms)")
            } catch (e: Throwable) {
                val msg = explain(e)
                say("❌ طلب مباشر (API) بـ $pick: $msg (" + (System.currentTimeMillis() - t0) + "ms)")
                if (msg.contains("free tier", true) || msg.contains("FreeTier", true) || (e is ExtLlm.LlmErr && e.code == 403)) {
                    verdict.add("🆓 OpenCode: الموديلات المجانية بترفض أي طلب جاي من خارج أداة OpenCode نفسها (403 FreeTierError) — ده قيد من OpenCode مش من مفتاحك. اللي ينفع: Termux + OpenCode CLI (التطبيق بيجهّزهم تلقائي)، أو موديلات Zen المدفوعة بنفس المفتاح (بتتحاسب).")
                } else verdict.add("🆓 OpenCode: الطلب المباشر فشل — $msg")
            }
            if (cli) {
                val t1 = System.currentTimeMillis()
                try {
                    val r = OpenCodeLocal.ask(pick, SYS, listOf(AiApi.Msg("user", SAMPLE)))
                    say("✅ طلب عن طريق Termux/CLI بـ $pick: «" + r.trim().take(60) + "» (" + (System.currentTimeMillis() - t1) + "ms)")
                } catch (e: Throwable) {
                    say("❌ طلب عن طريق Termux/CLI فشل: " + explain(e) + " (" + (System.currentTimeMillis() - t1) + "ms)")
                    verdict.add("🆓 OpenCode (Termux): " + explain(e))
                }
            } else if (!tmx && !lxOk) {
                verdict.add("🆓 OpenCode: مفيش Termux فمفيش طريق للموديلات المجانية دلوقتي (الـ API المباشر بيرفضها).")
            }
        }
        if (OpenCodeAuto.state != OpenCodeAuto.St.IDLE) say("— لوج التجهيز —\n" + OpenCodeAuto.logText().takeLast(700))

        // ===== الراوتر الداخلي =====
        say("\n— 🧭 الراوتر الداخلي —")
        say(if (InternalRouter.hasPool()) InternalRouter.summary() else "مفيش مزوّدين")

        // ===== الخلاصة =====
        say("\n🎯 الخلاصة")
        if (verdict.isEmpty()) say("✅ مفيش مشكلة ظاهرة في أومني روت ولا أوبن كود")
        else verdict.forEach { say("• $it") }
    }

    /** 🔧 إصلاح تلقائي: يشغّل OmniRoute في Termux ويجهّز OpenCode. لازم تتنادى من الـ UI thread (فيها طلب إذن) */
    fun fix(act: Activity, say: (String) -> Unit) {
        val c = act.applicationContext
        if (Linux.supported(c)) {   // (v394) البيئة الداخلية
            say("🐧 بجهّز/بشغّل البيئة الداخلية في الخلفية (أول مرة 15–30 دقيقة) — تابع من 💻 كونسول Linux")
            Linux.setupAsync(c) { ok -> act.runOnUiThread { say(if (ok) "✅ OmniRoute جاهز والمفتاح اتحفظ — دوس «فحص شامل» تاني" else "⚠ " + Linux.lastError.take(200)) } }
            OpenCodeAuto.start(act, true)
            return
        }
        if (!OmniLauncher.installed(c)) { say("❌ Termux مش متسطب — نزّله من F-Droid أو GitHub (مش Play Store) وبعدين دوس تاني."); return }
        if (!OmniLauncher.hasPerm(c)) {
            try { OmniLauncher.requestPerm(act) } catch (_: Throwable) {}
            say("🔐 وافق على إذن RUN_COMMAND اللي ظهر وارجع دوس «إصلاح تلقائي» تاني.")
            return
        }
        say("▶ بشغّل OmniRoute في Termux (أول مرة 5–10 دقايق تثبيت)…")
        val r = OmniLauncher.start(c)
        if (r == OmniLauncher.R.SENT) {
            OmniLauncher.waitUntilUp { act.runOnUiThread { say("✅ OmniRoute اشتغل — دوس «فحص شامل» تاني") } }
            say("⏳ الأمر اتبعت. لو ماشتغلش بعد دقيقتين: دوس «أمر إعداد Termux» والصقه في Termux مرة واحدة.")
        } else say("❌ مقدرتش أكلّم Termux (" + r.name + ")")
        OpenCodeAuto.start(act, true)
        say("⏳ وبجهّز OpenCode في الخلفية — اللوج بيظهر في «فحص شامل».")
    }
}
