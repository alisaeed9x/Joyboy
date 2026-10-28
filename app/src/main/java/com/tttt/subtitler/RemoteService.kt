package com.tttt.subtitler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * (v518) خدمة أمامية على الموبايل: بتحافظ على الاتصال بالتلفزيون وانت في أي تطبيق تاني.
 * إشعار ثابت: «متصل بـ …» وفيه أزرار 🔉 🔊 ⏯ ⏻فصل. ولو الاتصال انقطع/فشل بيطلع إشعار تنبيه منفصل.
 */
class RemoteService : Service() {
    companion object {
        const val CH = "tvremote"
        const val CH_ALERT = "tvremote_alert"
        const val NID = 5180
        const val NID_ALERT = 5181
        const val A_VOL_UP = "com.tttt.subtitler.TVR_VOL_UP"
        const val A_VOL_DOWN = "com.tttt.subtitler.TVR_VOL_DOWN"
        const val A_PLAY = "com.tttt.subtitler.TVR_PLAY"
        const val A_OFF = "com.tttt.subtitler.TVR_OFF"

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, RemoteService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Throwable) { LogStore.err("RemoteService:start", e) }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, RemoteService::class.java)) } catch (_: Throwable) {}
        }
    }

    private var lastState = RemoteClient.St.IDLE
    private var wake: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    /** (v624) والموبايل مقفول الشاشة: من غير القفلين الواي فاي بينام والاتصال/الأوامر بتقف. بنمسكهم طول ما متصلين بالتلفزيون بس */
    private fun locks(on: Boolean) {
        try {
            if (on) {
                if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "subtitler:tvlink").apply { setReferenceCounted(false) }
                if (wifi == null) wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "subtitler:tvlink").apply { setReferenceCounted(false) }
                if (wake?.isHeld == false) wake?.acquire(6 * 3600 * 1000L)
                if (wifi?.isHeld == false) wifi?.acquire()
            } else {
                if (wake?.isHeld == true) wake?.release()
                if (wifi?.isHeld == true) wifi?.release()
            }
        } catch (e: Throwable) { LogStore.err("RemoteService:locks", e) }
    }
    private val lis: () -> Unit = { onChange() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels()
        startFg(build())
        lastState = RemoteClient.state
        locks(lastState != RemoteClient.St.IDLE)
        RemoteClient.addListener(lis)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            A_VOL_UP -> RemoteClient.cmd("vol_up")
            A_VOL_DOWN -> RemoteClient.cmd("vol_down")
            A_PLAY -> RemoteClient.cmd("playpause")
            A_OFF -> { RemoteClient.disconnect(applicationContext); stopSelf(); return START_NOT_STICKY }
        }
        notifyNow()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RemoteClient.removeListener(lis)
        locks(false)
        super.onDestroy()
    }

    private fun channels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "ريموت التلفزيون", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "تنبيهات اتصال التلفزيون", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NID, n)
    }

    private fun act(label: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(this, code, Intent(this, RemoteService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(android.R.drawable.ic_media_play, label, pi).build()
    }

    private fun build(): Notification {
        val tv = RemoteClient.current
        val st = RemoteClient.state
        val name = tv?.name ?: "التلفزيون"
        val title = when (st) {
            RemoteClient.St.CONNECTED -> "📺 متصل بـ $name"
            RemoteClient.St.CONNECTING -> "📺 بتصل بـ $name…"
            RemoteClient.St.LOST -> "⚠ الاتصال بـ $name انقطع"
            else -> "📺 ريموت التلفزيون"
        }
        val sub = when (st) {
            RemoteClient.St.CONNECTED -> "تقدر تتحكم من هنا وانت في أي تطبيق"
            RemoteClient.St.LOST -> "بحاول أرجّع الاتصال تلقائي"
            else -> ""
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, LaunchRemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(sub)
            .setOngoing(true).setShowWhen(false)
            .setContentIntent(open)
        b.addAction(act("🔉", A_VOL_DOWN, 1))
        b.addAction(act("🔊", A_VOL_UP, 2))
        b.addAction(act("⏯", A_PLAY, 3))
        b.addAction(act("⏻ فصل", A_OFF, 4))
        b.setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        return b.build()
    }

    private fun notifyNow() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NID, build()) } catch (_: Throwable) {}
    }

    private fun alert(title: String, text: String) {
        try {
            val open = PendingIntent.getActivity(this, 9, Intent(this, LaunchRemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(this, CH_ALERT).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(open).build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NID_ALERT, n)
        } catch (_: Throwable) {}
    }

    private fun onChange() {
        val st = RemoteClient.state
        locks(st == RemoteClient.St.CONNECTED || st == RemoteClient.St.CONNECTING || st == RemoteClient.St.LOST)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if ((lastState == RemoteClient.St.CONNECTED || lastState == RemoteClient.St.CONNECTING) && st == RemoteClient.St.LOST)
            alert("⚠ انقطع الاتصال بالتلفزيون", "بحاول أرجّع تلقائي — لو التلفزيون مقفول أو الواي فاي وقع هيرجع لوحده")
        if (st == RemoteClient.St.CONNECTED) { try { nm.cancel(NID_ALERT) } catch (_: Throwable) {} }
        if (st == RemoteClient.St.IDLE) {
            val er = RemoteClient.lastError
            if (er.isNotEmpty()) alert("📺 اتفصل الريموت", er)
            lastState = st
            stopSelf()
            return
        }
        lastState = st
        notifyNow()
    }
}
