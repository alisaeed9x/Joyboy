package com.tttt.subtitler

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * (v977) صيّاد جافا جوه كل الإطارات (iframes) — للمشغّلات الخارجية اللي مبتبيّنش لينك فيديو بامتداد في الشبكة:
 *  - fetch / XMLHttpRequest: أي رد Content-Type بتاعه mpegurl / dash+xml (قايمة تشغيل) · أو فيديو كبير (≥ 3 ميجا) · أو نص صغير أوله #EXTM3U / <MPD (حتى لو اللينك من غير امتداد)
 *  - عنصر video/audio: src و currentSrc و <source> لو http(s)
 * بيتحقن في بداية كل مستند وكل إطار (addDocumentStartJavaScript) ويبعت لـKotlin بـWebMessageListener مع عنوان الإطار الحقيقي (Referer/Origin الصح).
 * اللي مبيتبعتش: blob: (مفيش لينك يتحمّل) — بنلقط قايمة التشغيل/الملف اللي اتبنى منه الـblob بدل كده.
 */
object VidHook {
    const val NAME = "JoyHook"

    val JS = """
(function(){
 if (window.__jh) return; window.__jh = 1;
 var P = (typeof JoyHook !== 'undefined') ? JoyHook : null; if (!P) return;
 var seen = {};
 var SEG = /\.(ts|m4s|aac|mp3|vtt|srt|jpe?g|png|gif|webp|svg|css|js|json|woff2?|key|ico)(\?|#|$)/i;
 var EXTV = /\.(m3u8|mpd|mp4|webm|m4v|mkv|mov|flv)(\?|#|&|\/|$)/i;
 var LOOKS = /(m3u8|playlist|master|manifest|index|hls|stream|\.txt|\.php|video|source|get|load|file)/i;
 var CDN = /(okcdn\.ru|mycdn\.me|vkuser\.net|googlevideo\.com\/videoplayback|\/videoplayback\?)/i;
 function abs(u){ try { return new URL(u, document.baseURI).href; } catch(e){ return ''; } }
 // c = 1: اتأكدنا جوه الصفحة نفسها إن اللينك اشتغل فعلًا (رد 2xx من الطلب / عنصر الفيديو حمّل المدة) — مش مجرد اسم لينك
 function send(u, k, c){
  try {
   u = abs(u); if (!/^https?:/i.test(u)) return;
   var key = u.split('#')[0] + (c ? '#c' : ''); if (seen[key]) return; seen[key] = 1;
   P.postMessage(JSON.stringify({u: u, k: k || '', f: location.href, c: c ? 1 : 0}));
  } catch(e){}
 }
 function kindOf(a){ var p = a.split('?')[0].toLowerCase(); return /\.m3u8$/.test(p) ? 'hls' : (/\.mpd$/.test(p) ? 'dash' : 'mp4'); }
 function okName(u){
  try {
   var a = abs(u); if (!a || SEG.test(a.split('?')[0]) || !EXTV.test(a)) return;
   send(a, kindOf(a), 1);
  } catch(e){}
 }
 function sizeOf(cl, cr){
  var s = 0;
  if (cr) { var m = /\/(\d+)\s*$/.exec(cr); if (m) s = +m[1]; }
  else if (cl) s = +cl;
  return s || 0;
 }
 function peekOk(ct, size){ return (size > 0 && size < 2000000) || (size === 0 && /^(text\/|application\/(json|x-www-form-urlencoded))/.test(ct)); }
 function scanUrls(t){
  try {
   t = t.slice(0, 300000);
   if (/https?%3A%2F%2F/i.test(t)) { try { t = t + ' ' + decodeURIComponent(t); } catch(e){} }
   t = t.replace(/\\u0026/g, '&').replace(/\\u002F/gi, '/').replace(/\\\//g, '/');
   var m = t.match(/https?:\/\/[^\s"'<>\\]+/g) || [], n = 0;
   for (var i = 0; i < m.length && n < 14; i++) {
    var a = m[i], p = a.split('?')[0].toLowerCase();
    if (SEG.test(p)) continue;
    if (EXTV.test(a) || CDN.test(a)) { n++; send(a, kindOf(a), 0); }
   }
  } catch(e){}
 }
 function sniffText(u, t){
  var full = t || ''; t = full.slice(0, 400);
  if (/^\s*#EXTM3U/.test(t)) send(u, 'hls', 1); else if (/<MPD[\s>]/i.test(t)) send(u, 'dash', 1); else scanUrls(full);
 }
 function inspect(u, ct, cl, cr, peek){
  try {
   ct = (ct || '').toLowerCase(); var size = sizeOf(cl, cr);
   if (/mpegurl/.test(ct)) return send(u, 'hls', 1);
   if (/dash\+xml/.test(ct)) return send(u, 'dash', 1);
   if (/^video\//.test(ct)) { if (!/mp2t/.test(ct) && !SEG.test((u || '').split('?')[0]) && size >= 3145728) send(u, 'mp4', 1); return; }
   if ((/^(text\/|application\/(octet-stream|x-?binary|json|x-www-form-urlencoded))/.test(ct) || ct === '') && peekOk(ct, size) && LOOKS.test(u || '') && peek) peek();
  } catch(e){}
 }
 function ok2(s){ return s >= 200 && s < 300; }
 try {
  var OF = window.fetch;
  if (OF) window.fetch = function(inp, init){
   var u = ''; try { u = (typeof inp === 'string') ? inp : ((inp && inp.url) || String(inp)); } catch(e){}
   var p = OF.apply(this, arguments);
   try {
    p.then(function(r){
     try {
      if (!r || !ok2(r.status)) return;
      var uu = r.url || u, h = r.headers;
      okName(uu);
      inspect(uu, h.get('content-type'), h.get('content-length'), h.get('content-range'), function(){ r.clone().text().then(function(t){ sniffText(uu, t); }, function(){}); });
     } catch(e){}
    }, function(){});
   } catch(e){}
   return p;
  };
 } catch(e){}
 try {
  var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u){ try { this.__ju = u; } catch(e){} return XO.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(){
   var x = this;
   try {
    x.addEventListener('readystatechange', function(){
     try {
      if (x.readyState === 2) {
       if (!ok2(x.status)) return;
       var u2 = x.responseURL || x.__ju;
       okName(u2);
       inspect(u2, x.getResponseHeader('content-type'), x.getResponseHeader('content-length'), x.getResponseHeader('content-range'), null);
      } else if (x.readyState === 4 && ok2(x.status) && (x.responseType === '' || x.responseType === 'text')) {
       var ct = (x.getResponseHeader('content-type') || '').toLowerCase(), size = sizeOf(x.getResponseHeader('content-length'), x.getResponseHeader('content-range')), uu = x.responseURL || x.__ju || '';
       if (/^(text\/|application\/(octet-stream|x-?binary|json|x-www-form-urlencoded))/.test(ct) || ct === '') { if (peekOk(ct, size) && LOOKS.test(uu)) sniffText(uu, x.responseText); }
      }
     } catch(e){}
    });
   } catch(e){}
   return XS.apply(this, arguments);
  };
 } catch(e){}
 // عنصر الفيديو: اللينك بيتحسب بس لما العنصر يكون حمّل فعلًا (مدة > 0) — src اتحط ورفض/403 مش لقطة
 function played(v){ try { return !!(v && v.currentSrc && v.readyState >= 1 && v.duration > 0); } catch(e){ return false; } }
 function media(v){
  try {
   if (!v || typeof v !== 'string' || /^(blob|data|javascript):/i.test(v)) return;
   var a = abs(v); if (!/^https?:/i.test(a)) return;
   send(a, kindOf(a), 1);
  } catch(e){}
 }
 function scan(){
  try {
   var vs = document.querySelectorAll('video,audio');
   for (var i = 0; i < vs.length; i++) { if (played(vs[i])) media(vs[i].currentSrc); }
  } catch(e){}
 }
 try { setInterval(scan, 1500); document.addEventListener('loadedmetadata', function(ev){ try { if (played(ev.target)) media(ev.target.currentSrc); } catch(e){} }, true); } catch(e){}
})();
""".trimIndent()

    /** callback على الـmain thread: (لينك، النوع hls|dash|mp4، عنوان الإطار، أصل الإطار، مؤكَّد). مؤكَّد = الصفحة نفسها شافت رد 2xx أو عنصر الفيديو حمّل (مش مجرد اسم لينك). false = الجهاز مش داعم (WebView قديم) */
    fun install(w: WebView, onHit: (url: String, kind: String, frame: String, origin: String, confirmed: Boolean) -> Unit): Boolean {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
            WebViewCompat.addWebMessageListener(w, NAME, setOf("*"), object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                    val txt = message.data ?: return
                    try {
                        val j = JSONObject(txt); val u = j.optString("u")
                        if (u.startsWith("http", true)) onHit(u, j.optString("k"), j.optString("f"), sourceOrigin.toString(), j.optInt("c", 0) == 1)
                    } catch (_: Throwable) {}
                }
            })
            WebViewCompat.addDocumentStartJavaScript(w, JS, setOf("*"))
            return true
        } catch (e: Throwable) { LogStore.err("VidHook", e); return false }
    }
    /** (v1156) الصيغة القديمة (ClipWatch): من غير علم التأكيد */
    fun install(w: WebView, onHit: (url: String, kind: String, frame: String, origin: String) -> Unit): Boolean = install(w) { u, k, f, o, _ -> onHit(u, k, f, o) }
}
