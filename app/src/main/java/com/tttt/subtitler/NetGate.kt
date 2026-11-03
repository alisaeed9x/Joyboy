package com.tttt.subtitler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * (v722) «واي فاي بس»: لما يكون شغّال، كل نت التطبيق (متصفح، تشغيل أونلاين، تحميل، API) بيتربط بشبكة الواي فاي.
 * لو مفيش واي فاي → النت واقف (مش بيروح على داتا الموبايل). الشبكة المحلية على الواي فاي (التلفزيون) شغّالة عادي.
 * الإعداد: Cfg "wifi_only" (الافتراضي شغّال). لو بتستخدم هوت سبوت من الموبايل نفسه وقف الخيار.
 */
object NetGate {
    private var cb: ConnectivityManager.NetworkCallback? = null
    private var cm: ConnectivityManager? = null
    @Volatile private var lastWifi: Network? = null

    fun enabled(ctx: Context): Boolean { Cfg.ensure(ctx); return Cfg.bool("wifi_only", true) }

    fun set(ctx: Context, on: Boolean) {
        Cfg.ensure(ctx)
        Cfg.p.edit().putBoolean("wifi_only", on).apply()
        apply(ctx)
    }

    /** مفيش واي فاي: اربط على شبكة وهمية ميتة ← أي اتصال بيفشل بدل ما يروح على الداتا (أندرويد 12+؛ قبل كده الحجب بيبدأ أول ما الواي فاي يتوصّل) */
    private fun blockAll(m: ConnectivityManager) {
        try { if (android.os.Build.VERSION.SDK_INT >= 31) m.bindProcessToNetwork(Network.fromNetworkHandle((60000L shl 32) or 0xcafed00dL)) } catch (_: Throwable) {}
    }

    @Synchronized fun apply(ctx: Context) {
        try {
            val a = ctx.applicationContext
            val m = cm ?: (a.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also { cm = it }
            if (!enabled(a)) {
                cb?.let { try { m.unregisterNetworkCallback(it) } catch (_: Throwable) {} }
                cb = null; lastWifi = null
                m.bindProcessToNetwork(null)
                return
            }
            if (cb != null) return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val c = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { lastWifi = n; try { m.bindProcessToNetwork(n) } catch (_: Throwable) {} }
                override fun onLost(n: Network) { if (lastWifi == n) { lastWifi = null; blockAll(m) } }
            }
            cb = c
            m.registerNetworkCallback(req, c)
            // لو الواي فاي شغّال أصلًا قبل التسجيل
            @Suppress("DEPRECATION")
            for (n in m.allNetworks) {
                val cc = m.getNetworkCapabilities(n) ?: continue
                if (cc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !cc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { lastWifi = n; m.bindProcessToNetwork(n); break }
            }
            if (lastWifi == null) blockAll(m)
        } catch (_: Throwable) {}
    }
}
