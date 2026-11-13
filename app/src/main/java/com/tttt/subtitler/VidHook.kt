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
 function abs(u){ try { return new URL(u, document.baseURI).href; } catch(e){ return ''; } }
 function send(u, k){
  try {
   u = abs(u); if (!/^https?:/i.test(u)) return;
   var key = u.split('#')[0]; if (seen[key]) return; seen[key] = 1;
   P.postMessage(JSON.stringify({u: u, k: k || '', f: location.href}));
  } catch(e){}
 }
 function byName(u){
  try {
   var a = abs(u); if (!a || SEG.test(a.split('?')[0]) || !EXTV.test(a)) return;
   var p = a.split('?')[0].toLowerCase();
   send(a, /\.m3u8$/.test(p) ? 'hls' : (/\.mpd$/.test(p) ? 'dash' : 'mp4'));
  } catch(e){}
 }
 function sizeOf(cl, cr){
  var s = 0;
  if (cr) { var m = /\/(\d+)\s*$/.exec(cr); if (m) s = +m[1]; }
  else if (cl) s = +cl;
  return s || 0;
 }
 function peekOk(ct, size){ return (size > 0 && size < 2000000) || (size === 0 && /^text\//.test(ct)); }
 function sniffText(u, t){
  t = (t || '').slice(0, 400);
  if (/^\s*#EXTM3U/.test(t)) send(u, 'hls'); else if (/<MPD[\s>]/i.test(t)) send(u, 'dash');
 }
 function inspect(u, ct, cl, cr, peek){
  try {
   ct = (ct || '').toLowerCase(); var size = sizeOf(cl, cr);
   if (/mpegurl/.test(ct)) return send(u, 'hls');
   if (/dash\+xml/.test(ct)) return send(u, 'dash');
   if (/^video\//.test(ct)) { if (!/mp2t/.test(ct) && !SEG.test((u || '').split('?')[0]) && size >= 3145728) send(u, 'mp4'); return; }
   if ((/^(text\/|application\/(octet-stream|x-?binary|json))/.test(ct) || ct === '') && peekOk(ct, size) && LOOKS.test(u || '') && peek) peek();
  } catch(e){}
 }
 try {
  var OF = window.fetch;
  if (OF) window.fetch = function(inp, init){
   var u = ''; try { u = (typeof inp === 'string') ? inp : ((inp && inp.url) || String(inp)); } catch(e){}
   byName(u);
   var p = OF.apply(this, arguments);
   try {
    p.then(function(r){
     try {
      var uu = r.url || u, h = r.headers;
      inspect(uu, h.get('content-type'), h.get('content-length'), h.get('content-range'), function(){ r.clone().text().then(function(t){ sniffText(uu, t); }, function(){}); });
     } catch(e){}
    }, function(){});
   } catch(e){}
   return p;
  };
 } catch(e){}
 try {
  var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u){ try { this.__ju = u; byName(u); } catch(e){} return XO.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(){
   var x = this;
   try {
    x.addEventListener('readystatechange', function(){
     try {
      if (x.readyState === 2) {
       inspect(x.responseURL || x.__ju, x.getResponseHeader('content-type'), x.getResponseHeader('content-length'), x.getResponseHeader('content-range'), null);
      } else if (x.readyState === 4 && (x.responseType === '' || x.responseType === 'text')) {
       var ct = (x.getResponseHeader('content-type') || '').toLowerCase(), size = sizeOf(x.getResponseHeader('content-length'), x.getResponseHeader('content-range')), uu = x.responseURL || x.__ju || '';
       if (/^(text\/|application\/(octet-stream|x-?binary|json))/.test(ct) || ct === '') { if (peekOk(ct, size) && LOOKS.test(uu)) sniffText(uu, x.responseText); }
      }
     } catch(e){}
    });
   } catch(e){}
   return XS.apply(this, arguments);
  };
 } catch(e){}
 function media(v){
  try {
   if (!v || typeof v !== 'string' || /^(blob|data|javascript):/i.test(v)) return;
   var a = abs(v); if (!/^https?:/i.test(a)) return;
   var p = a.split('?')[0].toLowerCase();
   send(a, /\.m3u8$/.test(p) ? 'hls' : (/\.mpd$/.test(p) ? 'dash' : 'mp4'));
  } catch(e){}
 }
 try {
  var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
  if (d && d.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {configurable: true, enumerable: d.enumerable, get: d.get, set: function(v){ try { media(v); } catch(e){} return d.set.call(this, v); }});
 } catch(e){}
 function scan(){
  try {
   var vs = document.querySelectorAll('video,audio');
   for (var i = 0; i < vs.length; i++) {
    var v = vs[i]; media(v.currentSrc); media(v.getAttribute('src'));
    var ss = v.querySelectorAll('source'); for (var j = 0; j < ss.length; j++) media(ss[j].getAttribute('src'));
   }
  } catch(e){}
 }
 try { setInterval(scan, 1500); document.addEventListener('loadstart', function(ev){ try { if (ev.target && ev.target.currentSrc) media(ev.target.currentSrc); } catch(e){} }, true); } catch(e){}
})();
""".trimIndent()

    /** callback على الـmain thread: (لينك، النوع hls|dash|mp4، عنوان الإطار، أصل الإطار). false = الجهاز مش داعم (WebView قديم) */
    fun install(w: WebView, onHit: (url: String, kind: String, frame: String, origin: String) -> Unit): Boolean {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
            WebViewCompat.addWebMessageListener(w, NAME, setOf("*"), object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                    val txt = message.data ?: return
                    try {
                        val j = JSONObject(txt); val u = j.optString("u")
                        if (u.startsWith("http", true)) onHit(u, j.optString("k"), j.optString("f"), sourceOrigin.toString())
                    } catch (_: Throwable) {}
                }
            })
            WebViewCompat.addDocumentStartJavaScript(w, JS, setOf("*"))
            return true
        } catch (e: Throwable) { LogStore.err("VidHook", e); return false }
    }
}
