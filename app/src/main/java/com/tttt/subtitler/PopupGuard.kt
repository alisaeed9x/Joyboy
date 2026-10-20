package com.tttt.subtitler

import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * (v401) مانع البوب-أب الذكي.
 * بدل ما كل نافذة منبثقة تتفتح تبويب أو تتقفل عمياني: البوب-أب بيتفتح في «حجر» (WebView مخفي مش في قايمة التبويبات)،
 * وبنحكم عليه بنقاط: لينك الصفحة وسلسلة تحويلاتها · هل كانت ضغطة حقيقية منك ولا غطا شفاف · نفس الموقع ولا موقع غريب ·
 * فيها فيديو/مشغّل · نفس اسم الفيلم اللي كنت فيه · كلام إعلانات/مراهنات.
 * إعلان = بيتقفل بصمت (وبيظهر إشعار «افتحه» لو غلطان). صفحة تحويل لمشاهدة الفيديو = بتتفتح تبويب عادي.
 */
object PopupGuard {
    const val OFF = 0
    const val SMART = 1
    const val STRICT = 2
    const val BLOCK = 0
    const val ALLOW = 1
    const val WAIT = 2
    const val MAX_WAIT_MS = 9000L

    fun modeOf(s: String?): Int = when (s) { "صارم" -> STRICT; "مقفول" -> OFF; else -> SMART }

    class Verdict(val action: Int, val score: Int, val why: List<String>)
    class Blocked(val url: String, val why: String, val at: Long)

    /** نافذة منبثقة في الحجر: لسه ما اتقررش مصيرها */
    class Hold(val tab: BTab, val openerUrl: String, val openerTitle: String, val gesture: Boolean, val touchAgo: Long) {
        val born: Long = SystemClock.uptimeMillis()
        val chain = CopyOnWriteArrayList<String>()
        val sniffed = CopyOnWriteArrayList<String>()
        @Volatile var media = false
        @Volatile var done = false
        @Volatile var click: JSONObject? = null
        @Volatile var probe: JSONObject? = null
        var timer: Runnable? = null
        fun addUrl(u: String) { if (u.isNotBlank() && !u.startsWith("about:") && chain.lastOrNull() != u) chain.add(u) }
        fun setClick(r: String?) { click = unq(r) }
        fun setProbe(r: String?) { probe = unq(r) }
        fun ageMs(): Long = SystemClock.uptimeMillis() - born
    }

    /** نتيجة evaluateJavascript جاية كـ JSON متغلّف (string جواه JSON) */
    fun unq(r: String?): JSONObject? {
        if (r.isNullOrBlank() || r == "null") return null
        return try {
            val v = JSONTokener(r).nextValue()
            when (v) {
                is String -> if (v.isBlank() || v == "null") null else JSONObject(v)
                is JSONObject -> v
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    // ===== JS =====
    /** بيسجّل آخر ضغطة في الصفحة (لينك حقيقي؟ ولا غطا شفاف كبير؟) — بيتحقن مرة واحدة في الصفحة */
    const val TRACK = "(function(){try{if(window.__svpg)return;window.__svpg=1;" +
        "function info(e){try{var t=e.target;var a=(t&&t.closest)?t.closest('a'):null;var el=a||t;var ov=false;" +
        "if(el&&window.getComputedStyle){var cs=getComputedStyle(el);var r=el.getBoundingClientRect();" +
        "var big=(r.width>=window.innerWidth*0.8&&r.height>=window.innerHeight*0.6);var pos=(cs.position==='fixed'||cs.position==='absolute');" +
        "var tr=(parseFloat(cs.opacity)<0.15)||(!((el.innerText||'').trim())&&el.children.length===0);ov=big&&pos&&tr}" +
        "window.__svlast={t:Date.now(),a:!!a,href:a?(a.href||''):'',ov:ov,x:((el&&(el.innerText||el.value))||'').slice(0,40)}}catch(_){}}" +
        "document.addEventListener('click',info,true);document.addEventListener('touchend',info,true)}catch(e){}})()"

    const val READ_CLICK = "(function(){try{var l=window.__svlast;return (l&&Date.now()-l.t<4000)?JSON.stringify(l):'null'}catch(e){return 'null'}})()"

    /** فحص صفحة البوب-أب: فيديو؟ مشغّلات؟ عنوان؟ نص؟ */
    const val PROBE = "(function(){try{var d=document;var vs=d.querySelectorAll('video').length;" +
        "var fr=[].slice.call(d.querySelectorAll('iframe')).map(function(f){return f.src||''}).filter(Boolean).slice(0,8);" +
        "var tx=((d.body&&d.body.innerText)||'');return JSON.stringify({v:vs,f:fr,t:d.title||'',x:tx.slice(0,1500),n:tx.length})}catch(e){return '{}'}})()"

    // ===== أدوات الدومين =====
    fun hostOf(url: String): String = try { Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: "" } catch (_: Throwable) { "" }

    private val SLD = setOf("co", "com", "org", "net", "gov", "edu", "ac")
    fun reg(host: String): String {
        val p = host.split('.')
        if (p.size <= 2) return host
        return if (p.last().length == 2 && p[p.size - 2] in SLD && p.size >= 3) p.subList(p.size - 3, p.size).joinToString(".") else p.subList(p.size - 2, p.size).joinToString(".")
    }
    fun sameReg(a: String, b: String): Boolean = a.isNotEmpty() && b.isNotEmpty() && reg(a) == reg(b)
    private fun brand(host: String): String = reg(host).substringBefore('.').replace(Regex("[0-9-]"), "")
    /** مرايا نفس الموقع (movie4u.com ↔ movie5u.net) */
    private fun sibling(a: String, b: String): Boolean { val x = brand(a); return x.length >= 4 && x == brand(b) }

    private val TRUST_EQ = setOf("youtube", "youtu", "dailymotion", "vimeo", "ok", "streamtape", "voe", "filemoon", "vidmoly", "upstream", "mp4upload",
        "vidoza", "streamlare", "uqload", "fembed", "imdb", "wikipedia", "google", "facebook", "twitter", "telegram", "instagram", "tiktok", "archive", "netflix")
    private val TRUST_PRE = listOf("dood", "mixdrop", "streamwish", "vidhide", "lulustream", "streamhub", "supervideo", "youtube")
    fun isTrusted(host: String): Boolean { val b = brand(host); return b in TRUST_EQ || TRUST_PRE.any { b.startsWith(it) } }

    private val BAD_TLD = listOf(".xyz", ".top", ".click", ".icu", ".buzz", ".monster", ".rest", ".cfd", ".sbs", ".cyou", ".quest", ".bond")
    private val QP = Regex("[?&](clickid|click_id|subid|sub_id|zoneid|zone_id|campaignid|campaign_id|aff_id|aff_sub|affid|trk|tracker|pubid|adid|ad_id|bannerid|p_id|source_id)=", RegexOption.IGNORE_CASE)
    private val PP = Regex("/(click|clk|redirect|redir|go|out|visit|jump|offer|offers|landing|lp|promo|track|tds)(/|\\?|$)", RegexOption.IGNORE_CASE)
    private val WATCH = Regex("(watch|play|stream|server|download|مشاهد|شاهد|تشغيل|سيرفر|تحميل|حمّل|تنزيل)", RegexOption.IGNORE_CASE)
    private val AD_WORDS = Regex("(casino|jackpot|free spins|1xbet|betting|sportsbook|you(?:'ve| have)? won|congratulations|claim (?:your|now)|your (?:phone|device|pc) (?:is|has)|adult dating|meet singles|xxx|crypto giveaway|كازينو|مراهنات|رهان|ربحت|مبروك|تهانينا|جائزتك|فيروس|ثغرة في جهازك)", RegexOption.IGNORE_CASE)
    private val STOP = setOf("watch", "online", "free", "movie", "film", "full", "the", "and", "download", "www", "com", "مشاهدة", "فيلم", "مسلسل", "اون", "لاين",
        "مترجم", "مترجمة", "حلقة", "الحلقة", "تحميل", "اونلاين", "كامل", "كاملة", "نسخة", "جودة", "1080p", "720p", "480p", "web", "hdrip", "bluray")

    /** نقاط لينك واحد من سلسلة التحويل (10+ = شبكة إعلانات أو تطبيق/سوق = اتمنع فورًا) */
    fun urlScore(url: String, why: MutableList<String>): Int {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")) return 0
        if (!url.startsWith("http", true)) { why.add("بيحاول يفتح تطبيق/سوق"); return 10 }
        var s = 0
        if (AdBlock.blocked(url)) { why.add("شبكة إعلانات معروفة"); return 10 }
        if (QP.containsMatchIn(url)) { s += 3; why.add("وسوم تتبّع إعلاني") }
        if (PP.containsMatchIn(url.substringBefore('?'))) { s += 2; why.add("مسار تحويل") }
        val host = hostOf(url)
        val b = reg(host).substringBefore('.')
        if (BAD_TLD.any { host.endsWith(it) } || b.length >= 16 || (b.count { it.isDigit() } >= 4 && b.length >= 8)) { s += 2; why.add("دومين مشبوه") }
        return s
    }

    private fun toks(t: String): Set<String> = Regex("[\\p{L}\\p{N}]{3,}").findAll(t.lowercase()).map { it.value }.filter { it !in STOP }.toSet()
    private fun titleOverlap(a: String, b: String): Boolean {
        val x = toks(a); val y = toks(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val c = x.intersect(y).size
        return c >= 2 || (c == 1 && minOf(x.size, y.size) == 1)
    }

    /** الحكم: بيتنادي أكتر من مرة (أول تحميل · بعد فحص الصفحة · عند انتهاء المهلة) */
    fun verdict(h: Hold, fin: String, mode: Int, final: Boolean, allowed: Set<String>): Verdict {
        val why = ArrayList<String>()
        var s = 0
        var evidence = false
        val finHost = hostOf(fin); val opHost = hostOf(h.openerUrl)

        // 1) سلسلة اللينكات: أسوأ لينك فيها
        var worst = 0; val wr = ArrayList<String>()
        for (u in h.chain) { val w = ArrayList<String>(); val x = urlScore(u, w); if (x > worst) { worst = x; wr.clear(); wr.addAll(w) } }
        s += worst; why.addAll(wr)
        if (worst >= 10) return Verdict(BLOCK, s, why)
        val hosts = h.chain.map { hostOf(it) }.filter { it.isNotEmpty() }.map { reg(it) }.toSet()
        if (hosts.size >= 4) { s += 3; why.add("سلسلة تحويلات طويلة") }

        // 2) الضغطة اللي فتحته
        if (!h.gesture) {
            if (h.touchAgo > 2500L) { s += 6; why.add("من غير ضغطة منك") } else { s += 2; why.add("مش من ضغطة مباشرة") }
        }
        val ck = h.click
        if (ck != null) {
            val ov = ck.optBoolean("ov"); val isA = ck.optBoolean("a")
            val href = ck.optString("href"); val txt = ck.optString("x")
            if (ov) { s += 5; why.add("الضغطة كانت على غطا شفاف") }
            else {
                val first = h.chain.firstOrNull() ?: ""
                if (isA && href.isNotEmpty() && sameReg(hostOf(href), hostOf(first))) { s -= 4; evidence = true }
                if (WATCH.containsMatchIn(txt)) { s -= 2; evidence = true }
            }
        }

        // 3) الموقع
        val cross = finHost.isNotEmpty() && opHost.isNotEmpty() && !sameReg(finHost, opHost)
        if (finHost.isNotEmpty()) {
            if (allowed.any { sameReg(it, finHost) }) { s -= 6; evidence = true }
            else if (!cross) { s -= 4; evidence = true }
            else if (sibling(finHost, opHost)) { s -= 3; evidence = true }
            else if (isTrusted(finHost)) { s -= 4; evidence = true }
        }

        // 4) الصفحة نفسها
        val p = h.probe
        if (h.media) { s -= 3; evidence = true }
        if (p != null) {
            if (p.optInt("v") > 0) { s -= 2; evidence = true }
            val fr = p.optJSONArray("f")
            if (fr != null) for (i in 0 until fr.length()) { val fh = hostOf(fr.optString(i)); if (fh.isNotEmpty() && isTrusted(fh)) { s -= 3; evidence = true; break } }
            val title = p.optString("t")
            if (AD_WORDS.containsMatchIn(title + " " + p.optString("x"))) { s += 4; why.add("محتوى إعلاني/مراهنات") }
            if (titleOverlap(h.openerTitle, title)) { s -= 4; evidence = true }
        }

        // 5) موقع غريب ومفيش أي دليل إنه صفحة مشاهدة
        if (cross && !evidence && (final || p != null)) { s += 2; why.add("موقع غريب ومفيهوش فيديو") }

        if (s >= 5) return Verdict(BLOCK, s, why)
        if (mode == STRICT) {
            if (s <= -4) return Verdict(ALLOW, s, why)
            return if (p != null || final) Verdict(BLOCK, s, why) else Verdict(WAIT, s, why)
        }
        if (s <= -4) return Verdict(ALLOW, s, why)
        if (s <= 0 && evidence && p != null) return Verdict(ALLOW, s, why)
        // صفحة فاضية = غالبًا وسيط بيحوّل لصفحة تانية: نستنى التحويل
        val blank = p != null && p.optInt("n") < 40 && !h.media && p.optInt("v") == 0 && h.ageMs() < 6500L
        if (!final && (p == null || blank)) return Verdict(WAIT, s, why)
        return if (s >= 2) Verdict(BLOCK, s, why) else Verdict(ALLOW, s, why)
    }
}
