package com.tttt.subtitler

import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * (v149) كتم/إيقاف أي صوت جاي من WebView في التطبيق (متصفح، يوتيوب، كشف الروابط، تجديد اللينك…).
 * المشكلة: صفحات مخفية (كشف الكليب / تجديد اللينك) بتشغّل فيديو تلقائي بصوت، وتبويبات المتصفح ممكن تفضل شغّالة
 * في الخلفية، فالصوت ده بيطلع فوق فيديو المشغّل. المشغّل بيوقفهم كلهم أول ما يفتح.
 */
object WebMute {
    private val live = ArrayList<WeakReference<WebView>>()
    private val last = java.util.WeakHashMap<WebView, Long>()
    private const val PAUSE_JS = "(function(){try{var a=document.querySelectorAll('video,audio');for(var i=0;i<a.length;i++){try{a[i].pause()}catch(e){}}}catch(e){}})()"
    private const val MUTE_JS = "(function(){try{if(!window.__stMute){window.__stMute=1;var P=HTMLMediaElement.prototype,o=P.play;P.play=function(){try{this.muted=true;this.volume=0}catch(e){}return o.apply(this,arguments)}}var a=document.querySelectorAll('video,audio');for(var i=0;i<a.length;i++){try{a[i].muted=true;a[i].volume=0}catch(e){}}}catch(e){}})()"

    @Synchronized fun register(w: WebView) { live.removeAll { it.get() == null }; if (live.none { it.get() === w }) live.add(WeakReference(w)) }

    /** كتم كل الفيديوهات في الصفحة (للـ WebViews المخفية). بتتنادى كتير فبتتخنق كل نص ثانية. من أي thread. */
    fun mute(w: WebView) {
        val now = System.currentTimeMillis()
        synchronized(this) { val t = last[w] ?: 0L; if (now - t < 500L) return; last[w] = now }
        try { w.post { try { w.evaluateJavascript(MUTE_JS, null) } catch (_: Throwable) {} } } catch (_: Throwable) {}
    }

    /** إيقاف أي صوت شغّال في كل الـ WebViews المسجّلة (بيتنادى لما المشغّل يفتح). */
    fun pauseAll() {
        val ws = synchronized(this) { live.removeAll { it.get() == null }; live.mapNotNull { it.get() } }
        val onMain = android.os.Looper.myLooper() === android.os.Looper.getMainLooper()
        for (w in ws) try {
            if (onMain) { try { w.evaluateJavascript(PAUSE_JS, null) } catch (_: Throwable) {} }   // على الـ UI thread مباشرة، قبل ما الـ WebView يتجمّد
            else w.post { try { w.evaluateJavascript(PAUSE_JS, null) } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }

    private const val PAUSE_MARK_JS = "(function(){var n=0;try{var a=document.querySelectorAll('video,audio');for(var i=0;i<a.length;i++){try{if(!a[i].paused&&!a[i].ended){a[i].__jbGuard=1;a[i].pause();n++}}catch(e){}}}catch(e){}return n})()"
    private const val RESUME_MARK_JS = "(function(){try{var a=document.querySelectorAll('video,audio');for(var i=0;i<a.length;i++){try{if(a[i].__jbGuard){a[i].__jbGuard=0;var p=a[i].play();if(p&&p.catch)p.catch(function(){})}}catch(e){}}}catch(e){}})()"

    /** (v1010) تحذير الداتا: بيوقّف اللي شغّال بس ويعلّم عليه عشان resumeMarked يكمّله. بيرجّع true لو في WebViews مسجّلة. UI thread. */
    fun pauseMarked(): Boolean {
        val ws = synchronized(this) { live.removeAll { it.get() == null }; live.mapNotNull { it.get() } }
        for (w in ws) try { w.evaluateJavascript(PAUSE_MARK_JS, null) } catch (_: Throwable) {}
        return ws.isNotEmpty()
    }
    fun resumeMarked() {
        val ws = synchronized(this) { live.mapNotNull { it.get() } }
        for (w in ws) try { w.evaluateJavascript(RESUME_MARK_JS, null) } catch (_: Throwable) {}
    }
}
