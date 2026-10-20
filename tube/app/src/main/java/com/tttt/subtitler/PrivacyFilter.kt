package com.tttt.subtitler

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * (v358) تصنيف اللينكات اللي بتدخل عليها في المتصفح: اللي للكبار (إباحي / محتوى صريح) بتروح «سجل الخصوصية» المقفول
 * بنفس نمط/بصمة المجلد المخفي، وبتتشال من السجل العادي. التصنيف على مرحلتين:
 *  1) محلي وسريع: كلمات ودومينات واضحة (من غير نت ولا مفتاح).
 *  2) بالذكاء الصناعي (Gemini) للباقي: مرة لكل موقع (النتيجة بتتخزّن)، ومن غير ما نبعت غير الرابط والعنوان.
 */
object PrivacyFilter {
    private val bg = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private const val SAFE_TTL = 7L * 24 * 3600 * 1000

    fun on() = Cfg.bool("pf_on", true)
    fun aiOn() = Cfg.bool("pf_ai", true)

    // كلمات طويلة واضحة: كفاية إنها تظهر جوه الدومين أو العنوان
    private val STRONG = listOf("porn", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "brazzers", "onlyfans", "chaturbate", "livejasmin",
        "hentai", "nsfw", "camgirl", "erotic", "nudes", "xxx", "bangbros", "spankbang", "stripchat", "bongacams", "motherless", "rule34", "e-hentai", "nhentai",
        "اباحي", "إباحي", "اباحية", "إباحية", "عاريات")
    // كلمات قصيرة ممكن تتلخبط جوه كلمات تانية (essex, ميكانيك…): لازم تظهر ككلمة لوحدها
    private val WORDS = setOf("sex", "sexy", "nude", "naked", "milf", "escort", "porno", "fuck", "nsfw", "adult", "نيك", "سكس", "سكسي", "بورن", "عاري", "عارية", "مثير", "شراميط", "سحاق")

    fun localAdult(url: String, title: String): Boolean {
        val dec = try { URLDecoder.decode(url, "UTF-8") } catch (_: Throwable) { url }
        val low = (dec + " " + title).lowercase()
        if (STRONG.any { low.contains(it) }) return true
        val toks = low.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val hits = toks.count { it in WORDS }
        return hits >= 2 || (hits >= 1 && toks.any { it in setOf("sex", "porno", "nude", "naked", "milf", "escort", "نيك", "سكس", "سكسي", "بورن", "سحاق", "شراميط") })
    }

    private fun host(u: String) = try { java.net.URL(u).host.lowercase().removePrefix("www.") } catch (_: Throwable) { "" }
    // مواقع كبيرة معروفة: مفيش داعي نبعتها للذكاء الصناعي
    private val TRUSTED = listOf("google.", "youtube.", "youtu.be", "wikipedia.", "github.", "facebook.", "instagram.", "whatsapp.", "microsoft.", "apple.",
        "amazon.", "anthropic.", "gmail.", "yahoo.", "bing.", "duckduckgo.", "stackoverflow.", "linkedin.", "netflix.", "spotify.", "gov.", ".edu")

    private fun cacheGet(h: String): Int? {                       // 1 = للكبار · 0 = عادي (لسه سارية) · null = مش معروف
        return try {
            val o = JSONObject(Cfg.str("pf_hosts", "{}")).optJSONObject(h) ?: return null
            val v = o.optInt("v", -1); val t = o.optLong("t", 0L)
            if (v == 1) 1 else if (v == 0 && System.currentTimeMillis() - t < SAFE_TTL) 0 else null
        } catch (_: Throwable) { null }
    }
    private fun cachePut(h: String, v: Int) {
        try {
            val all = JSONObject(Cfg.str("pf_hosts", "{}"))
            if (all.length() > 400) { val ks = all.keys(); var n = 0; val del = ArrayList<String>(); while (ks.hasNext() && n < 100) { del.add(ks.next()); n++ }; for (k in del) all.remove(k) }
            all.put(h, JSONObject().put("v", v).put("t", System.currentTimeMillis()))
            Cfg.put("pf_hosts", all.toString())
        } catch (_: Throwable) {}
    }

    /** بيتنده من الـ UI thread. onAdult بترجع على الـ UI thread لو التصنيف طلع «للكبار». */
    fun aiCheck(url: String, title: String, onAdult: () -> Unit) {
        if (!aiOn()) return
        val h = host(url); if (h.isEmpty() || h.contains("localhost") || TRUSTED.any { h.contains(it) }) return
        when (cacheGet(h)) { 1 -> { onAdult(); return }; 0 -> return }
        val keys = try { SongId.textKeys() } catch (_: Throwable) { emptyList() }
        if (keys.isEmpty()) return
        bg.execute {
            val p = "Classify ONE web page a user visited. URL: " + url.take(300) + "\nPage title: " + title.take(160) + "\n\n" +
                "Answer true ONLY if the page is pornographic, sexually explicit, an adult-only site (porn, hentai, erotic videos/stories, escort/adult chat, nude content). " +
                "Normal news, health, education, shopping, social media, dating advice, art or fashion pages are false. If unsure answer false.\n" +
                "OUTPUT: one JSON object only, no markdown: {\"adult\":true} or {\"adult\":false}"
            var verdict: Boolean? = null
            for (k in keys.take(2)) {
                try {
                    val r = Api.generate(Models.DEFAULT, k, p, null, 64, 0.0, true, false)
                    val o = Parse.json(r.text)
                    if (o != null && o.has("adult")) { verdict = o.optBoolean("adult", false); break }
                } catch (e: ApiErr) { if (e.code == 429 || e.code == 503) continue } catch (_: Throwable) {}
            }
            val v = verdict ?: return@execute
            cachePut(h, if (v) 1 else 0)
            if (v) main.post { onAdult() }
        }
    }
}
