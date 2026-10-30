package com.tttt.subtitler

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * (v654) اكتشاف أجهزة Quick Share على الشبكة المحلية (mDNS) — من غير ما الجهاز التاني يكون عنده Joyboy.
 * الاكتشاف بس: أي جهاز Quick Share ظاهر للجميع بيعلن نفسه بخدمة _FC9F5ED42C8A._tcp. واسمه/نوعه جوه سجل TXT «n».
 * الإرسال لجهاز Quick Share بيتم عن طريق شاشة المشاركة الرسمية بتاعة أندرويد (مش بروتوكول Joyboy المشفّر).
 */
object FtQuick {
    const val TYPE = "_FC9F5ED42C8A._tcp."

    /** @return (اسم, نوع) من سجل n؛ null لو مش صالح. النوع: phone · tablet · pc */
    fun parseInfo(raw: ByteArray): Pair<String, String>? {
        return try {
            val b = Base64.decode(String(raw, Charsets.US_ASCII), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            if (b.size < 19) return null
            val dev = (b[0].toInt() shr 1) and 7
            val len = b[17].toInt() and 0xFF
            if (len <= 0 || 18 + len > b.size) return null
            val name = FtIdentity.sanitize(String(b, 18, len, Charsets.UTF_8))
            if (name.isEmpty()) null else Pair(name, when (dev) { 2 -> "tablet"; 3 -> "pc"; else -> "phone" })
        } catch (_: Throwable) { null }
    }

    /** blocking لمدة ms — من خيط خلفي. onFound بيتنادى من خيط خلفي */
    fun discover(ctx: Context, ms: Long, onFound: (FtPeer) -> Unit) {
        val nsd = try { ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager } catch (_: Throwable) { null } ?: return
        val seen = ConcurrentHashMap<String, Boolean>()
        val queue = CopyOnWriteArrayList<NsdServiceInfo>()
        var resolving = false
        var stopped = false
        fun next() {
            if (stopped || resolving) return
            val si = queue.firstOrNull() ?: return
            queue.remove(si); resolving = true
            try {
                nsd.resolveService(si, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo?, code: Int) { resolving = false; next() }
                    override fun onServiceResolved(s: NsdServiceInfo?) {
                        resolving = false
                        try {
                            val raw = s?.attributes?.get("n")
                            val ip = s?.host?.hostAddress ?: ""
                            val info = if (raw != null) parseInfo(raw) else null
                            if (s != null && info != null && ip.isNotEmpty() && !ip.contains(':'))
                                onFound(FtPeer("qs:" + s.serviceName, info.first, info.second, ip, s.port, "", "", 0, 0, false, null, true))
                        } catch (_: Throwable) {}
                        next()
                    }
                })
            } catch (_: Throwable) { resolving = false; next() }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, c: Int) {}
            override fun onStopDiscoveryFailed(t: String?, c: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceLost(s: NsdServiceInfo?) {}
            override fun onServiceFound(s: NsdServiceInfo?) {
                if (s == null || seen.put(s.serviceName, true) != null) return
                queue.add(s); next()
            }
        }
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < ms) try { Thread.sleep(150) } catch (_: InterruptedException) { break }
        } catch (e: Throwable) { LogStore.err("FtQuick", e) }
        finally { stopped = true; try { nsd.stopServiceDiscovery(l) } catch (_: Throwable) {} }
    }
}
