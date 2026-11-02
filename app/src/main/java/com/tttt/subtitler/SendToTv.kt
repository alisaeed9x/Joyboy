package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * (v518) هدف مشاركة «📺 ابعت للتلفزيون»: من أي تطبيق (كروم / يوتيوب …) دوس مشاركة ← اختار الأيقونة دي ←
 * اللينك يتبعت للتلفزيون ويشتغل هناك على طول. من غير شاشة (شفّافة) وبتقفل لوحدها.
 */
class SendToTvActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this); RemoteClient.init(this)
        val raw = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val url = Regex("https?://\\S+").find(raw)?.value
        if (url == null) { Notice.show(this, "⚠ مفيش لينك في اللي اتشارك", 2600L); h.postDelayed({ finish() }, 2800L); return }
        if (RemoteClient.lastTv() == null) {
            // (v603) مفيش تلفزيون مقترن: الريموت القديم اتمسح — الاقتران من زرار 📺 (وضع التلفزيون)
            Notice.show(this, "⚠ مفيش تلفزيون مقترن — افتح التطبيق ودوس زرار 📺 واقترن الأول", 3600L)
            h.postDelayed({ finish() }, 3800L); return
        }
        Notice.show(this, "📺 بوصّل بالتلفزيون…", 6000L)
        Thread {
            var msg = ""
            try {
                if (!RemoteClient.ensureConnected(this, 8000L)) msg = "⚠ ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
                else {
                    val er = RemoteClient.openLink(url)
                    msg = if (er == null) "✅ اتبعت للتلفزيون" else "⚠ $er"
                }
            } catch (e: Throwable) { LogStore.err("SendToTv", e); msg = "⚠ حصلت مشكلة" }
            h.post { Notice.show(this, msg, 2600L); h.postDelayed({ if (!isFinishing) finish() }, 2800L) }
        }.also { it.isDaemon = true }.start()
    }
}
