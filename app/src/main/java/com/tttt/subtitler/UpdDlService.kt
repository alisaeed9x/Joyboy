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

/** (v1268) خدمة أمامية بإشعار تقدّم: تنزيل التحديث (والبلاجن) يكمّل والتطبيق في الخلفية. */
class UpdDlService : Service() {
    private var ticker: Thread? = null
    @Volatile private var alive = false

    override fun onBind(i: Intent?): IBinder? = null

    private fun chan() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "تنزيل التحديث", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun open(): PendingIntent? {
        val li = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, li, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun build(title: String, text: String, done: Long, total: Long, ongoing: Boolean): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        b.setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setOngoing(ongoing).setOnlyAlertOnce(true).setContentIntent(open())
        if (ongoing) { if (total > 0L) b.setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false) else b.setProgress(0, 0, true) }
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        chan()
        val n = build("⬇ بينزّل تحديث Joyboy", "…", 0L, 0L, true)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        } catch (_: Throwable) { stopSelf(); return START_NOT_STICKY }
        if (alive) return START_NOT_STICKY
        alive = true
        ticker = Thread {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            var idle = 0
            while (alive) {
                val st = Upd.svcState()
                if (st == null) { if (++idle >= 3) break } else {
                    idle = 0
                    val pct = if (st[1] > 0L) (st[0] * 100 / st[1]).toInt() else 0
                    try { nm.notify(ID, build("⬇ بينزّل تحديث Joyboy", if (st[1] > 0L) "$pct%" else Upd.mb(st[0]), st[0], st[1], true)) } catch (_: Throwable) {}
                }
                try { Thread.sleep(1000L) } catch (_: Throwable) {}
            }
            val res = Upd.lastResult
            try { stopForeground(true) } catch (_: Throwable) {}
            try {
                if (res == "ok") nm.notify(ID + 1, build("✅ التحديث جاهز", "دوس عشان تفتح Joyboy وتثبّت", 0L, 0L, false).also { it.flags = it.flags or Notification.FLAG_AUTO_CANCEL })
                else if (res != null && res != "stop") nm.notify(ID + 1, build("⚠ التنزيل وقف", res, 0L, 0L, false).also { it.flags = it.flags or Notification.FLAG_AUTO_CANCEL })
            } catch (_: Throwable) {}
            alive = false
            stopSelf()
        }.apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() { alive = false; super.onDestroy() }

    companion object {
        const val CH = "upd_dl"; const val ID = 7421
        fun start(c: Context) {
            try {
                val i = Intent(c, UpdDlService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (_: Throwable) {}
        }
    }
}
