package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient.CustomViewCallback
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/** فيديو اتلقط (زي «لينك التحميل» في 1DM): رابط + نوعه + مصدره (ref/ua) عشان المشغّل يفتحه بنفس الهيدرز */
class Found(val url: String, val kind: String, val title: String = "", val ref: String = "", val ua: String = "", val page: String = "") {   // (v847) ref = الـReferer الحقيقي للطلب (إطار المشغّل) · page = صفحة المتصفح (للعنوان/التجميع)
    @Volatile var size = -1L
    @Volatile var mime = ""
    /** (v203) التبويب + «جيل» الصفحة اللي اتلقط منها: لما الصفحة تتغير الجيل بيزيد واللقطات القديمة بتختفي */
    @Volatile var tabId = -1L
    @Volatile var gen = 0
}

/** منطق الصيد (من غير Android): إيه اللي يتحسب فيديو، وتنضيف الروابط، وأسماء العرض */
object Sniff {
    private val EXT = Regex("\\.(mp4|m3u8|mpd|webm|mkv|mov|m4v|flv|3gp|avi)(?=[?#&/]|$)", RegexOption.IGNORE_CASE)   // (v467) + DASH/AVI ونهايات بلينكات موقّعة
    // (v722) + midroll/vast/vmap/banner/وسوم إعلان في اسم الملف — وبعدها AdBlock.blocked (شبكات إعلانات + مراهنات) في accept
    private val JUNK = Regex("(doubleclick|googlesyndication|adservice|/ads?/|/preroll|/midroll|/vast|/vmap|/adtag|/banner|[?&/_-]ads?[_-]|analytics|\\.gif)", RegexOption.IGNORE_CASE)

    fun isDirect(u: String) = u.startsWith("http", true) && EXT.containsMatchIn(u)

    /** رابط الطلب → رابط فيديو صالح أو null */
    fun accept(raw: String): String? {
        if (!raw.startsWith("http", true) || JUNK.containsMatchIn(raw) || AdBlock.blocked(raw)) return null
        if (raw.contains("googlevideo.com/videoplayback")) {
            // بس الصيغ اللي فيها صوت وصورة مع بعض (18 = 360p، 22 = 720p)؛ باقي الـ itags صوت أو صورة لوحدها
            val itag = Regex("[?&]itag=(\\d+)").find(raw)?.groupValues?.get(1)
            if (itag != "18" && itag != "22") return null
            var u = raw.replace(Regex("[?&](range|rn|rbuf)=[^&]*"), "")
            if (!u.contains('?') && u.contains('&')) u = u.replaceFirst('&', '?')
            return u
        }
        return if (EXT.containsMatchIn(raw)) raw else null
    }
    fun kindOf(u: String): String {
        val p = u.substringBefore('?').lowercase()
        return when {
            p.endsWith(".m3u8") -> "HLS"
            p.endsWith(".mpd") -> "DASH"
            p.endsWith(".webm") -> "WEBM"
            p.endsWith(".mkv") -> "MKV"
            u.contains("videoplayback") -> "YT"
            else -> "MP4"
        }
    }
    /** مفتاح منع التكرار */
    fun key(u: String) = if (u.contains("videoplayback")) u else u.substringBefore('?').substringBefore('#')
    fun nameOf(u: String): String {
        val n = u.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return if (n.isNotEmpty() && !n.contains(':')) n else u.substringAfter("://").substringBefore('/')
    }
    /** أقل حجم يتحسب فيديو: أصغر من كده غالبًا إعلان */
    const val MIN_BYTES = 3L * 1024 * 1024
    private val SEP = Regex("\\s+[|\\-–—»·•]\\s+")
    private val LEAD = Regex("^(watch|download|stream|مشاهدة|تحميل|تنزيل|شاهد)\\s+", RegexOption.IGNORE_CASE)
    /** عنوان صفحة الموقع → اسم نضيف (بيشيل اسم الموقع اللي بعد | أو - وكلمات زي مشاهدة/Watch) */
    fun cleanTitle(t: String?): String {
        var x = (t ?: "").trim()
        if (x.isEmpty() || x.startsWith("http", true) || x.equals("about:blank", true)) return ""
        val parts = x.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) x = parts.maxByOrNull { it.length } ?: x
        x = x.replace(LEAD, "").trim()
        return if (x.length > 2) x.take(120) else ""
    }
    // (v120) جودات الفيديو في أي موقع: بنستنتج الارتفاع من اللينك (…/720/… أو _1080p) ونجمّع اللينكات اللي نفس الفيديو بجودات مختلفة
    private val HQ = Regex("(?<![0-9])(2160|1440|1080|720|576|540|480|432|406|360|288|270|240|180|144)(?![0-9])")
    fun heightOf(u: String): Int {
        // (v203) يوتيوب: الجودة من الـ itag (18 = 360p، 22 = 720p)
        if (u.contains("videoplayback")) { when (Regex("[?&]itag=(\\d+)").find(u)?.groupValues?.get(1)) { "18" -> return 360; "22" -> return 720 } }
        return heightOf0(u)
    }
    private fun heightOf0(u: String): Int = HQ.findAll(u.substringBefore('?').substringBefore('#')).lastOrNull()?.value?.toIntOrNull() ?: 0
    /** هيكل اللينك من غير رقم الجودة — اللينكات اللي ليها نفس الهيكل بجودات مختلفة = نفس الفيديو */
    fun skeleton(u: String): String = key(u).replace(HQ, "#")
    /** سطر الجودة: «720p · 85.3 MB» (الحجم الكلي لو معروف) */
    fun qLabel(h: Int, idx: Int, size: Long, approx: Boolean = false): String =
        (if (h > 0) h.toString() + "p" else "جودة " + idx) + (if (size > 0) " · " + (if (approx) "≈" else "") + fmtSize(size) else "")
    fun fmtSize(b: Long): String = if (b >= 1_048_576L * 1024) String.format("%.2f GB", b / 1073741824.0) else String.format("%.1f MB", b / 1048576.0)
}

/** مانع إعلانات مدمج: بيقطع طلبات شبكات الإعلانات والبوب-أب، وبيخبّي عناصر الإعلان في الصفحة */
object AdBlock {
    private val HOSTS = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "google-analytics.com",
        "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com", "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "juicyads.com",
        "trafficjunky.net", "clickadu.com", "adsterra.com", "hilltopads.net", "onclickads.net", "mgid.com", "revcontent.com", "criteo.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "amazon-adsystem.com", "adform.net", "smartadserver.com", "yieldmo.com",
        "bidswitch.net", "2mdn.net", "moatads.com", "scorecardresearch.com", "admaven.com", "ad-maven.com", "a-ads.com", "adcash.com",
        "monetag.com", "galaksion.com", "trafficstars.com", "adskeeper.com", "zedo.com", "yllix.com", "popunder.net", "adspyglass.com",
        "magsrv.com", "exosrv.com", "clickadilla.com", "trafficfactory.biz", "adsco.re", "pushground.com", "pushnami.com", "webpushr.com",
        "subscribers.com", "onesignal.com", "propu.sh", "push.house", "richads.com", "evadav.com", "mybid.io", "kadam.net", "bidgear.com",
        "poptm.com", "adnium.com", "youradexchange.com", "bemobtrk.com", "voluumtrk.com", "zeroredirect.com", "hilltopads.com",
        "ero-advertising.com", "tsyndicate.com", "realsrv.com", "syndication.exdynsrv.com", "exdynsrv.com", "pushails.com", "richpush.co",
        // (v722) شبكات فيديو/بوب-أب/بانرات إضافية
        "ad-delivery.net", "highperformanceformat.com", "profitableratecpm.com", "effectivegatecpm.com", "highcpmrevenuegate.com", "cpmrevenuegate.com",
        "iclickcdn.com", "acscdn.com", "adexchangeprediction.com", "clickaine.com", "vidcrunch.com", "springserve.com", "spotx.tv", "spotxchange.com",
        "adsafeprotected.com", "vidazoo.com", "primis.tech", "connatix.com", "teads.tv", "tubecorporate.com", "adtng.com", "trafficforce.com",
        "bidvertiser.com", "adtelligent.com", "rtbhouse.com", "yandexadexchange.net", "an.yandex.ru", "lijit.com", "sharethrough.com", "33across.com",
        "indexww.com", "casalemedia.com", "sovrn.com", "media.net", "gumgum.com", "triplelift.com", "contextweb.com", "serving-sys.com", "adblade.com",
        "adroll.com", "advertising.com", "pemsrv.com", "pemsrv.net", "mnaspm.com", "adsrvmedia.net", "clickdealer.com", "adopexchange.com", "ad4m.at",
        "vidoomy.com", "doubleverify.com", "adsymptotic.com", "adspeed.net", "adsupply.com", "adcolony.com", "unityads.unity3d.com", "applovin.com",
        "mopub.com", "inmobi.com", "smaato.net", "startapp.com", "chartboost.com", "ironsrc.com", "vungle.com", "liftoff.io", "tapjoy.com",
        "pubmine.com", "adnetworkperformance.com", "cdn4ads.com", "adxpansion.com", "s0-greate.net", "go.oclasrv.com", "oclasrv.com", "tzegilo.com")
    private val PATH = Regex("(/popunder|/pop\\.js|/adserver|/banner_ads|/prebid|/vast\\.xml|/vast\\.php|/vmap|/vpaid|/afu\\.php|/adclick|/clickunder|/inpagepush|/ntf\\.js|/preroll|/midroll|/adtag|/pagead/|/adsbygoogle|/ads\\.js)", RegexOption.IGNORE_CASE)
    /** (v722) مراهنات/كازينو: بالاسم في الدومين أو في مسار اللينك (من غير الـquery عشان البحث ما يتحجبش) */
    private val BET_URL = Regex("1x\\s?bet|1xlite|1xstavka|melbet|mostbet|betwinner|22bet|linebet|megapari|paripesa|betandyou|888starz|parimatch", RegexOption.IGNORE_CASE)
    private val BET_HOST = Regex("casino|jackpot|sportsbook|betting|bet365|betway|(?:^|[.\\-])refpa", RegexOption.IGNORE_CASE)
    fun blocked(url: String): Boolean {
        val host = try { Uri.parse(url).host?.lowercase() } catch (_: Throwable) { null } ?: return false
        val parts = host.split('.')
        for (i in 0 until parts.size - 1) if (parts.subList(i, parts.size).joinToString(".") in HOSTS) return true
        if (PopupGuard.isLearned(host)) return true   // (v816) دومين إعلانات اتعلّم من بوب-أب اتمنع
        if (BET_HOST.containsMatchIn(host)) return true
        if (BET_URL.containsMatchIn(url.substringBefore('?'))) return true
        return PATH.containsMatchIn(url)
    }
    const val CSS = "ins.adsbygoogle,.adsbygoogle,[id^=div-gpt-ad],[id*=google_ads],[class*=ad-banner],[class*=adbanner],[class*=popup-ad],[id*=popup-ad]," +
        "[class*=sponsored-],[id^=aswift],iframe[src*=doubleclick],iframe[src*=googlesyndication],iframe[src*=adsterra],iframe[src*=exoclick]," +
        "iframe[src*=propeller],a[href*=popads],a[href*=exoclick],a[href*=adsterra],[class*=ad-overlay],[id*=ad-overlay],[class*=ad-container],[id*=ad-container]," +
        "[class*=ads-container],[class*=ad-wrapper],[class*=floatAd],[class*=floating-ad],[class*=popunder],iframe[src*=magsrv],iframe[src*=exosrv]," +
        "a[href*=clickadu],a[href*=propellerads],a[href*=hilltopads]{display:none!important;visibility:hidden!important}"
    /** (v467) محدّدات بس (من غير قاعدة العرض) — لمراقب الـ DOM اللي بيمسح الإعلانات المتأخرة أول ما تظهر */
    private const val SEL = "ins.adsbygoogle,.adsbygoogle,[id^=div-gpt-ad],[id*=google_ads],[class*=ad-banner],[class*=adbanner],[class*=popup-ad],[id*=popup-ad],[id^=aswift],[class*=ad-overlay],[id*=ad-overlay],[class*=ad-container],[id*=ad-container],[class*=ads-container],[class*=ad-wrapper],[class*=floatAd],[class*=floating-ad],[class*=popunder],iframe[src*=doubleclick],iframe[src*=googlesyndication],iframe[src*=adsterra],iframe[src*=exoclick],iframe[src*=propeller],iframe[src*=magsrv],iframe[src*=exosrv]"
    // (v722) اللوجيك كله في الصفحة: إزالة عناصر إعلانية + فيديو الإعلان (كتم وقفز لآخره + ضغط «Skip») + بانرات اللينكات الخارجية ومقاسات IAB + بانرات المراهنات
    private val CORE_JS = """
var st=document.getElementById('__svab');
if(!st){st=document.createElement('style');st.id='__svab';st.textContent='""" + CSS + """';(document.head||document.documentElement).appendChild(st)}
if(window.__svab2)return;
window.__svab2=1;
var SEL='""" + SEL + """';
var TOK=/(?:^|[^A-Za-z0-9])(?:ads?|adv|advert|advertisement|adslot|adsbox|adbox|adunit|preroll|vast|vpaid|sponsored)(?![A-Za-z0-9])/i;
var BETB=/1x\s?bet|1xlite|1xstavka|melbet|mostbet|betwinner|22bet|linebet|megapari|paripesa|betandyou|888starz|parimatch|(?:^|[^A-Za-z])refpa/i;
var BETH=/casino|jackpot|slots|sportsbook|betting|bet365|betway|pin-?up/i;
var TRUST=/youtube|youtu\.be|facebook|fb\.com|twitter|x\.com|instagram|tiktok|telegram|t\.me|wa\.me|whatsapp|imdb|wikipedia|google|apple\.com|github|discord|reddit|pinterest|linkedin|themoviedb|tmdb/i;
var IAB=[[728,90],[468,60],[320,50],[320,100],[300,250],[300,600],[160,600],[970,90],[970,250],[336,280],[250,250],[120,600],[234,60]];
var SKW=/^(?:skip\s*(?:after|in)?\s*\d+\s*s?|skip\s+ads?|skip\s+now|skip|ad|ads|advertisement|تخطي(?:\s+الإعلان|\s+الاعلان)?)$/i;
var CLICKW=/^(?:skip\s+ads?|skip\s+now|skip|تخطي(?:\s+الإعلان|\s+الاعلان)?)$/i;
function iab(w,h){for(var i=0;i<IAB.length;i++){if(Math.abs(IAB[i][0]-w)<=3&&Math.abs(IAB[i][1]-h)<=3)return true}return false}
function regd(h){var p=(h||'').split('.');if(p.length<=2)return h||'';var s=p[p.length-2];return(p.length>=3&&p[p.length-1].length===2&&/^(co|com|org|net|gov|edu|ac)$/.test(s))?p.slice(-3).join('.'):p.slice(-2).join('.')}
var ME=regd(location.hostname);
function hostOf(u){try{return new URL(u,location.href).hostname}catch(e){return ''}}
function txt(e){return((e.innerText||e.textContent||'')+'').trim()}
function kill(e){try{var p=e.parentElement;e.remove();if(p&&p!==document.body&&p!==document.documentElement&&!p.children.length&&!txt(p))p.remove()}catch(e2){}}
function cls(e){return(typeof e.className==='string'?e.className:'')+' '+(e.id||'')}
function inAd(e){for(var i=0;e&&e.nodeType===1&&i<7;i++,e=e.parentElement){if(TOK.test(cls(e)))return true}return false}
function extA(a){try{var h=hostOf(a.href);if(!h||regd(h)===ME||TRUST.test(h)||!/^https?:/i.test(a.href))return false;var tg=(a.target==='_blank')||/sponsored|nofollow/i.test(a.rel||'');if(!tg)return false;var r=a.getBoundingClientRect();if(r.width<180||r.height<40||txt(a).length>25)return false;return !!a.querySelector('img,iframe,video,canvas')}catch(e){return false}}
function shortVid(v){var d=v.duration;return isFinite(d)&&d>0&&d<130}
function fast(v){try{v.muted=true;v.volume=0;if(shortVid(v)){v.playbackRate=16;if(v.currentTime<v.duration-0.3)v.currentTime=v.duration-0.2}}catch(e){}}
function leaves(){var out=[];try{var all=document.body?document.body.querySelectorAll('div,span,button,a,p'):[];for(var i=0;i<all.length;i++){var e=all[i];if(e.childElementCount>2)continue;var t=(e.textContent||'').trim();if(!t||t.length>26)continue;if(SKW.test(t.replace(/\s+/g,' ')))out.push(e)}}catch(e2){}return out}
function nearVids(el){var p=el;for(var i=0;p&&i<7;i++,p=p.parentElement){var vs=p.querySelectorAll?p.querySelectorAll('video'):[];if(vs.length)return[].slice.call(vs)}return[]}
function ov(el){try{var p=el;for(var i=0;p&&i<8;i++,p=p.parentElement){var cs=getComputedStyle(p);if(cs.position==='absolute'||cs.position==='fixed'){var r=p.getBoundingClientRect();if(r.width*r.height>=window.innerWidth*window.innerHeight*0.12){if(!p.querySelector('video'))p.style.setProperty('display','none','important');return}}}}catch(e){}}
function tick(){
 try{
  var vids=document.querySelectorAll('video');
  for(var i=0;i<vids.length;i++){var v=vids[i];var s=v.currentSrc||v.src||'';if(inAd(v)||(s&&BETB.test(s)))fast(v)}
  if(vids.length||document.querySelector('iframe')){
   var lv=leaves();
   for(var k=0;k<lv.length;k++){
    var el=lv[k];var t=(el.textContent||'').trim().replace(/\s+/g,' ');
    if(CLICKW.test(t)){try{(el.closest('button,a')||el).click()}catch(e){}continue}
    var nv=nearVids(el);var done=false;
    for(var m=0;m<nv.length;m++){if(shortVid(nv[m])||inAd(nv[m])){fast(nv[m]);done=true}}
    if(!done)ov(el)
   }
  }
 }catch(e){}
}
function scan(){
 try{
  document.querySelectorAll(SEL).forEach(function(e){e.remove()});
  var xs=document.querySelectorAll('iframe,img,a[href]');
  for(var i=0;i<xs.length;i++){
   var e=xs[i];var tg=e.tagName;var u=(tg==='A')?e.href:(e.src||'');
   if(!u)continue;
   var h=hostOf(u);var ext=!!h&&regd(h)!==ME;
   if(BETB.test(u)||(ext&&BETH.test(h))){if(tg==='IMG'){kill(e.closest('a')||e)}else{kill(e)}continue}
   if(tg==='IFRAME'){var r0=e.getBoundingClientRect();if(ext&&!TRUST.test(h)&&iab(Math.round(r0.width),Math.round(r0.height)))kill(e);continue}
   if(tg==='A'&&extA(e)){kill(e);continue}
   if(tg==='IMG'&&ext&&!TRUST.test(h)&&e.naturalWidth&&iab(e.naturalWidth,e.naturalHeight))kill(e.closest('a')||e)
  }
 }catch(e2){}
 tick()
}
scan();
try{new MutationObserver(function(){clearTimeout(window.__svt);window.__svt=setTimeout(scan,400)}).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}
try{setInterval(tick,800)}catch(e){}
"""
    val JS: String = "(function(){try{window.open=function(){return null};" + CORE_JS + "}catch(e){}})()"
    /** (v401) نفس الـ JS من غير تعطيل window.open — البوب-أب بيعدّي على PopupGuard يحكم عليه */
    val JS_SMART: String = "(function(){try{" + CORE_JS + "}catch(e){}})()"
}

/**
 * استخراج روابط يوتيوب المباشرة (صيغ فيها صوت وصورة + HLS لو موجود) من نفس واجهة تطبيق يوتيوب.
 * ملحوظة: يوتيوب بيغيّر الواجهة دي كل شوية، فبنجرّب أكتر من «عميل» واحد ورا التاني، وسبب فشل كل واحد بيتسجّل في lastWhy
 * (v119) عشان نعرف بالظبط إيه اللي حصل بدل ما نرمي المستخدم على المتصفح.
 */
object YtExtract {
    // (v889) المستخرج القديم (عملاء InnerTube بتوع Joyboy) اتمسح — كل جلب الروابط بقى من محرك MeeTube (MtYt). الاسم فضل عشان المتصفح والمشغّل.
    private val ID = Regex("(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})")
    fun videoId(u: String): String? = ID.find(u)?.groupValues?.get(1)

    var lastWhy: String
        get() = MtYt.lastWhy
        set(v) { MtYt.lastWhy = v }

    /** خيار جودة: رابط فيديو + (لو الصورة لوحدها) رابط صوت بيتدمج معاه في المشغّل */
    class Opt(val label: String, val url: String, val audio: String?, val h: Int)

    class Pick(val url: String, val audio: String?, val title: String, val ua: String, val label: String, val opts: List<Opt>) {
        var tag: String = ""
        var at: JSONArray? = null
        fun optsJson(): String { val a = JSONArray(); for (o in opts) a.put(JSONObject().put("l", o.label).put("u", o.url).put("a", o.audio ?: "").put("h", o.h)); return a.toString() }
    }

    fun cachedPick(id: String, maxH: Int = 0): Pick? = null
    fun clearPicks() { MtYt.clearCache() }

    fun fetchPick(id: String, maxH: Int = 0, fresh: Boolean = false): Pick? {
        val g = MtYt.video(id, maxH, fresh) ?: return null
        val opts = ArrayList<Opt>()
        try { val qa = JSONArray(g.qlist); for (i in 0 until qa.length()) { val o = qa.getJSONObject(i); opts.add(Opt(o.optString("l"), o.optString("u"), o.optString("a").ifEmpty { null }, o.optInt("h"))) } } catch (_: Throwable) {}
        val label = if (g.by.endsWith("HLS")) "HLS" else (opts.firstOrNull()?.label ?: "")
        return Pick(g.url, g.audio, g.title, g.ua, label, opts).also { it.tag = "MeeTube" }
    }

    /** روابط مباشرة لفيديو يوتيوب (للمتصفح): كل جودة كـFound + HLS لو موجود */
    fun fetch(id: String): List<Found> {
        val g = MtYt.video(id, 0) ?: return emptyList()
        val out = ArrayList<Found>()
        try {
            val qa = JSONArray(g.qlist)
            for (i in 0 until qa.length()) {
                val o = qa.getJSONObject(i)
                if (o.optString("a").isNotEmpty()) continue   // فيديو بدون صوت لا يصلح كلينك مباشر
                out.add(Found(o.optString("u"), "YT " + o.optString("l"), g.title, "https://www.youtube.com/", g.ua))
            }
        } catch (_: Throwable) {}
        if (g.by.endsWith("HLS")) out.add(Found(g.url, "HLS", g.title, "https://www.youtube.com/", g.ua))
        return out
    }
}
