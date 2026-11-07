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
class Found(val url: String, val kind: String, val title: String = "", val ref: String = "", val ua: String = "") {
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
    private val ID = Regex("(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})")
    fun videoId(u: String): String? = ID.find(u)?.groupValues?.get(1)

    /** (v119) سبب فشل آخر استخراج — سطر لكل عميل (للعرض في الرسالة واللوج) */
    @Volatile var lastWhy: String = ""

    private class Cl(val name: String, val ver: String, val id: Int, val ua: String, val extra: String, val ctx: String = "", val tag: String = name)
    private val clients = listOf(
        Cl("ANDROID_VR", "1.65.10", 28, "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\",\"osName\":\"Android\",\"osVersion\":\"12L\",\"androidSdkVersion\":32", tag = "VR"),
        Cl("ANDROID_VR", "1.60.19", 28, "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\",\"osName\":\"Android\",\"osVersion\":\"12L\",\"androidSdkVersion\":32", tag = "VR-old"),
        Cl("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", 85, "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            "\"clientScreen\":\"EMBED\"", ",\"thirdParty\":{\"embedUrl\":\"https://www.youtube.com/\"}", tag = "TV-embed"),
        Cl("ANDROID_TESTSUITE", "1.9", 30, "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            "\"osName\":\"Android\",\"osVersion\":\"11\",\"androidSdkVersion\":30", tag = "TEST"),
        Cl("ANDROID", "19.44.38", 3, "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip",
            "\"osName\":\"Android\",\"osVersion\":\"14\",\"androidSdkVersion\":34", tag = "ANDROID"),
        Cl("ANDROID_MUSIC", "7.27.52", 21, "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14) gzip",
            "\"osName\":\"Android\",\"osVersion\":\"14\",\"androidSdkVersion\":34", tag = "MUSIC"),
        Cl("IOS", "19.45.4", 5, "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            "\"deviceMake\":\"Apple\",\"deviceModel\":\"iPhone16,2\",\"osName\":\"iPhone\",\"osVersion\":\"18.1.0.22B83\"", tag = "IOS")
    )

    private class Res(val j: JSONObject?, val why: String)

    private fun playerJson(c: Cl, id: String): Res {
        val body = "{\"context\":{\"client\":{\"clientName\":\"${c.name}\",\"clientVersion\":\"${c.ver}\",${c.extra},\"hl\":\"en\",\"gl\":\"US\"}${c.ctx}},\"videoId\":\"$id\",\"contentCheckOk\":true,\"racyCheckOk\":true}"
        var con: HttpURLConnection? = null
        try {
            con = URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection() as HttpURLConnection
            con.requestMethod = "POST"; con.connectTimeout = 10000; con.readTimeout = 15000; con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json"); con.setRequestProperty("User-Agent", c.ua)
            con.setRequestProperty("X-YouTube-Client-Name", c.id.toString()); con.setRequestProperty("X-YouTube-Client-Version", c.ver)
            con.setRequestProperty("Origin", "https://www.youtube.com")
            con.outputStream.use { it.write(body.toByteArray()) }
            val code = con.responseCode
            if (code != 200) return Res(null, "HTTP $code")
            val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            val ps = j.optJSONObject("playabilityStatus")
            val st = ps?.optString("status") ?: "?"
            if (st != "OK") {
                val rs = ps?.optString("reason") ?: ""
                return Res(null, st + (if (rs.isNotEmpty()) " – " + rs.take(90) else ""))
            }
            return Res(j, "")
        } catch (e: Throwable) {
            return Res(null, e.javaClass.simpleName + (e.message?.let { ": " + it.take(60) } ?: ""))
        } finally { try { con?.disconnect() } catch (_: Throwable) {} }
    }

    /** بيشتغل على thread خلفي: كل الصيغ الجاهزة (صوت+صورة) + HLS لو موجود — للمتصفح والكليبورد */
    fun fetch(id: String): List<Found> {
        val out = ArrayList<Found>(); val seen = HashSet<String>(); val whys = ArrayList<String>()
        for (c in clients) {
            try {
                val r = playerJson(c, id)
                val j = r.j
                if (j == null) { whys.add(c.tag + ": " + r.why); continue }
                val title = j.optJSONObject("videoDetails")?.optString("title") ?: ""
                val sd = j.optJSONObject("streamingData")
                if (sd == null) { whys.add(c.tag + ": مفيش streamingData"); continue }
                val fm = sd.optJSONArray("formats")
                if (fm != null) for (i in 0 until fm.length()) {
                    val f = fm.getJSONObject(i); val u = f.optString("url")
                    if (u.isEmpty() || !seen.add(u)) continue
                    val q = f.optString("qualityLabel").ifEmpty { f.optInt("height").toString() + "p" }
                    val fo = Found(u, "YT $q", title, "https://www.youtube.com/", c.ua)
                    fo.mime = f.optString("mimeType").substringBefore(';')
                    f.optString("contentLength").toLongOrNull()?.let { fo.size = it }
                    out.add(fo)
                }
                val hls = sd.optString("hlsManifestUrl")
                if (hls.isNotEmpty() && seen.add(hls)) out.add(Found(hls, "HLS", title, "https://www.youtube.com/", c.ua))
                if (out.isNotEmpty()) break
                whys.add(c.tag + ": مفيش روابط مباشرة (محمي)")
            } catch (e: Throwable) { LogStore.err("Sniffer:169", e); whys.add(c.tag + ": " + e.javaClass.simpleName) }
        }
        lastWhy = if (out.isEmpty()) whys.joinToString("\n") else ""
        if (out.isEmpty()) LogStore.add("⚠ YtExtract.fetch فشل ($id):\n" + lastWhy)
        return out
    }

    /** (v118) خيار جودة: رابط فيديو + (لو الصورة لوحدها) رابط صوت بيتدمج معاه في المشغّل — زي NewPipe/SnapTube */
    class Opt(val label: String, val url: String, val audio: String?, val h: Int)
    /** نتيجة الاستخراج: الخيار المختار + كل الجودات المتاحة (لزرار الجودة في المشغّل) */
    class Pick(val url: String, val audio: String?, val title: String, val ua: String, val label: String, val opts: List<Opt>) {
        var tag: String = ""   // (v363) أي عميل جاب النتيجة دي (للتثبيت)
        var at: JSONArray? = null   // (v388) مسارات اللغات (لو الفيديو فيه أكتر من لغة صوت) — بتتحط في أول عنصر
        fun optsJson(): String { val a = JSONArray(); for ((ix, o) in opts.withIndex()) { val j = JSONObject().put("l", o.label).put("u", o.url).put("a", o.audio ?: "").put("h", o.h); if (ix == 0) at?.let { j.put("at", it) }; a.put(j) }; return a.toString() }
    }

    private fun mb(n: Long) = if (n > 0) " · " + (if (n >= 10_485_760L) (n / 1_048_576L).toString() else String.format("%.1f", n / 1048576.0)) + "MB" else ""

    /**
     * بيجرّب العملاء واحد ورا التاني ويرجّع كل الجودات المتاحة (144p…1080p بحجمها التقريبي).
     * maxH > 0: بيختار أعلى جودة لحد الارتفاع ده (للداتا القليلة)، غير كده أعلى جودة متاحة.
     * (v119) لو ولا عميل دّى روابط مباشرة لكن في HLS: بيرجّع HLS (المشغّل بيختار الجودة منه). لو مفيش خالص: null وlastWhy فيه السبب.
     */
    // ===== (v363) تثبيت العميل الشغّال =====
    // أول ما التطبيق يفتح بنجرّب كل عملاء يوتيوب على فيديو اختبار ثابت (بالتوازي) ونثبّت الأسرع الشغّال؛ fetchPick بيبدأ بيه الأول.
    // أي نجاح فعلي بعميل تاني بيثبّته. لو الجلب فشل كله بنعيد الفحص في الخلفية (مرة كل 5 دقايق بالكتير).
    class Probe(val tag: String, val ok: Boolean, val ms: Long, val why: String)
    @Volatile private var pinTag = ""
    @Volatile private var probing = false
    @Volatile private var lastProbe = 0L
    fun pinned(): String = pinTag
    fun loadPin() { try { pinTag = Cfg.str("yt_pin", "") } catch (_: Throwable) {} }
    fun setPin(t: String) {
        if (t.isEmpty() || t == pinTag) return
        pinTag = t; try { Cfg.put("yt_pin", t) } catch (_: Throwable) {}
        LogStore.add("📌 يوتيوب: العميل المثبّت دلوقتي " + t)
    }
    private fun ordered(): List<Cl> {
        val p = clients.firstOrNull { it.tag == pinTag } ?: return clients
        return listOf(p) + clients.filter { it !== p }
    }
    fun probeAll(testId: String = "jNQXAC9IVRw"): List<Probe> {
        val out = java.util.Collections.synchronizedList(ArrayList<Probe>())
        val ths = clients.map { c -> Thread {
            val t0 = System.currentTimeMillis()
            var ok = false; var why = ""
            try {
                val r = playerJson(c, testId); why = r.why
                val sd = r.j?.optJSONObject("streamingData")
                fun hasUrl(a: JSONArray?): Boolean { if (a == null) return false; for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("url").orEmpty().isNotEmpty()) return true; return false }
                ok = sd != null && (hasUrl(sd.optJSONArray("formats")) || hasUrl(sd.optJSONArray("adaptiveFormats")))
                if (!ok && why.isEmpty()) why = "مفيش روابط مباشرة"
            } catch (e: Throwable) { why = e.javaClass.simpleName }
            out.add(Probe(c.tag, ok, System.currentTimeMillis() - t0, why))
        }.also { it.isDaemon = true; it.start() } }
        ths.forEach { try { it.join(20000L) } catch (_: Throwable) {} }
        return out.toList()
    }
    /** بيفحص ويثبّت. بيرجّع true لو فيه عميل واحد على الأقل شغّال */
    fun reprobe(force: Boolean = false): Boolean {
        if (probing) return pinTag.isNotEmpty()
        if (!force && System.currentTimeMillis() - lastProbe < 5L * 60 * 1000L) return pinTag.isNotEmpty()
        probing = true; lastProbe = System.currentTimeMillis()
        try {
            val r = probeAll()
            LogStore.add("🔎 فحص عملاء يوتيوب: " + r.sortedBy { it.ms }.joinToString(" · ") { it.tag + (if (it.ok) " ✅" + it.ms + "ms" else " ❌ " + it.why.take(24)) })
            val best = r.filter { it.ok }.minByOrNull { it.ms }
            if (best != null) { setPin(best.tag); YtFast.markAlive(); return true }
            pinTag = ""; try { Cfg.put("yt_pin", "") } catch (_: Throwable) {}
            YtFast.markDead(); return false
        } finally { probing = false }
    }
    fun reprobeAsync() { if (probing) return; Thread { try { reprobe(false) } catch (_: Throwable) {} }.also { it.isDaemon = true; it.start() } }

    // (v354) كاش + تشغيل العملاء بالتوازي (hedged) بدل واحد ورا التاني: أسرع بكتير لو أول عميل بطيء/مقفول
    private val pickCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pick>>()
    private const val PICK_TTL = 3L * 60 * 60 * 1000L
    /** النتيجة المخزّنة (لو لسه صالحة) من غير شبكة */
    fun cachedPick(id: String, maxH: Int = 0): Pick? = pickCache["$id/$maxH"]?.takeIf { System.currentTimeMillis() - it.first < PICK_TTL }?.second
    fun clearPicks() { pickCache.clear() }   // (v388) تغيير لغة الصوت الافتراضية

    private fun pickFrom(c: Cl, id: String, maxH: Int, whys: MutableList<String>, hls: java.util.concurrent.atomic.AtomicReference<Pick?>): Pick? {
        try {
            val r = playerJson(c, id)
            val j = r.j
            if (j == null) { whys.add(c.tag + ": " + r.why); return null }
            val title = j.optJSONObject("videoDetails")?.optString("title") ?: ""
            val sd = j.optJSONObject("streamingData")
            if (sd == null) { whys.add(c.tag + ": مفيش streamingData"); return null }
            val byH = HashMap<Int, Opt>()
            sd.optJSONArray("formats")?.let { a -> for (i in 0 until a.length()) {
                val f = a.optJSONObject(i) ?: continue
                val u = f.optString("url"); val h = f.optInt("height")
                if (u.isEmpty() || h <= 0) continue
                byH[h] = Opt(h.toString() + "p" + mb(f.optString("contentLength").toLongOrNull() ?: 0L), u, null, h)
            } }
            var ba: JSONObject? = null; var baR = 0
            val auds = ArrayList<JSONObject>()
            val vids = ArrayList<JSONObject>()
            sd.optJSONArray("adaptiveFormats")?.let { a -> for (i in 0 until a.length()) {
                val f = a.optJSONObject(i) ?: continue
                if (f.optString("url").isEmpty()) continue
                val m = f.optString("mimeType")
                if (m.startsWith("video/mp4") && m.contains("avc1")) { val h = f.optInt("height"); if (h in 1..1080) vids.add(f) }
                else if (m.startsWith("audio/mp4")) { auds.add(f); val rt = f.optInt("bitrate"); if (rt > baR) { ba = f; baR = rt } }
            } }
            // (v388) مسارات الصوت (audioTrack.id مثل ar.4 / en-US.4): نختار لغتك المفضّلة (عربي افتراضيًا) بدل أعلى bitrate (كان بيجيب الدبلجة الإنجليزي أحيانًا)
            val trs = ArrayList<YtLang.Tr>(); val byLang = HashMap<String, JSONObject>()
            for (f in auds.sortedByDescending { it.optInt("bitrate") }) {
                val atk = f.optJSONObject("audioTrack") ?: continue
                val dn = atk.optString("displayName").lowercase()
                if (dn.contains("descriptive")) continue
                val lg = YtLang.code(atk.optString("id"))
                if (lg.isEmpty() || byLang.containsKey(lg)) continue
                byLang[lg] = f
                trs.add(YtLang.Tr(lg, dn.contains("original"), atk.optBoolean("audioIsDefault"), f.optString("url"), f.optInt("bitrate")))
            }
            val multi = trs.size > 1
            val chTr = if (multi) YtLang.choose(trs) else null
            val au = if (chTr != null) (byLang[chTr.lang] ?: ba) else ba
            if (au != null) {
                val aSize = au.optString("contentLength").toLongOrNull() ?: 0L
                val seenVo = HashSet<Int>()
                for (f in vids.sortedByDescending { it.optInt("bitrate") }) {
                    val h = f.optInt("height")
                    if (if (multi) !seenVo.add(h) else byH.containsKey(h)) continue   // (v388) أكتر من لغة صوت: الفيديو-بس + الصوت المختار بيحلّ محل المدمج
                    val vs = f.optString("contentLength").toLongOrNull() ?: 0L
                    byH[h] = Opt(h.toString() + "p" + mb(if (vs > 0) vs + aSize else 0L), f.optString("url"), au.optString("url"), h)
                }
            }
            if (byH.isEmpty()) {
                val hu = sd.optString("hlsManifestUrl")
                if (hu.isNotEmpty()) hls.compareAndSet(null, Pick(hu, null, title, c.ua, "HLS", emptyList()))
                whys.add(c.tag + ": مفيش روابط مباشرة (محمي)" + (if (hu.isNotEmpty()) " — فيه HLS" else ""))
                return null
            }
            val opts = byH.values.sortedByDescending { it.h }
            val chosen = (if (maxH > 0) opts.firstOrNull { it.h <= maxH } ?: opts.last() else opts.first())
            return Pick(chosen.url, chosen.audio, title, c.ua, chosen.label, opts).also { it.tag = c.tag; if (multi) it.at = YtLang.json(trs, chTr) }
        } catch (e: Throwable) { LogStore.err("Sniffer:pick", e); whys.add(c.tag + ": " + e.javaClass.simpleName); return null }
    }

    fun fetchPick(id: String, maxH: Int = 0, fresh: Boolean = false): Pick? {
        if (!fresh) cachedPick(id, maxH)?.let { lastWhy = ""; return it }
        val whys = java.util.Collections.synchronizedList(ArrayList<String>())
        val hls = java.util.concurrent.atomic.AtomicReference<Pick?>(null)
        val q = java.util.concurrent.LinkedBlockingQueue<Any>()
        val none = Any()
        val order = ordered()
        var launched = 0; var next = 0; var done = 0
        fun launch() { val c = order[next++]; launched++
            Thread { q.put(try { pickFrom(c, id, maxH, whys, hls) ?: none } catch (_: Throwable) { none }) }.apply { isDaemon = true; start() } }
        launch()
        val deadline = System.currentTimeMillis() + 40000L
        while (done < launched) {
            // لو العميل الحالي مردّش في ثانية ونص: ابدأ اللي بعده من غير ما تقفل الأول
            val r = q.poll(if (next < order.size) 1500L else 30000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (r == null) { if (next < order.size) launch() else if (System.currentTimeMillis() > deadline) break; continue }
            done++
            if (r is Pick) { lastWhy = ""; pickCache["$id/$maxH"] = Pair(System.currentTimeMillis(), r); if (r.tag.isNotEmpty()) setPin(r.tag); return r }
            if (next < order.size) launch()   // فشل: ابدأ اللي بعده فورًا
        }
        lastWhy = whys.joinToString("\n")
        reprobeAsync()   // (v363)
        val hp = hls.get()
        LogStore.add("⚠ YtExtract.fetchPick ($id)" + (if (hp != null) " — بجرّب HLS" else " فشل") + ":\n" + lastWhy)
        return hp
    }
}
