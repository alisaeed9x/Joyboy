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
import android.os.PowerManager

/**
 * (v420) خدمة أمامية لمهام صفحة المهام (حرق ترجمة ثابتة · قص · ضغط · GIF · صوت…): من غيرها الأندرويد بيجمّد/يقتل العملية لما الشاشة تتقفل
 * أو التطبيق يتقفل فالمهمة بتفشل. TaskCenter بينده sync() مع كل تغيير: لو فيه مهمة شغّالة (غير تحميلات DownloadManager) بتشتغل + WakeLock، ولو خلصوا بتقف.
 */
class TaskService : Service() {
    companion object {
        private const val ID = 7421
        private const val CH = "task_run"
        @Volatile private var running = false
        @Volatile private var lastPost = 0L

        private fun busy(): List<TaskItem> = TaskCenter.items.filter { (it.state == 0 || it.state == 1) && it.kind != "dm" }

        fun sync(c: Context) {
            try {
                val b = busy()
                if (b.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    if (running && now - lastPost < 2500L) return   // تحديث الإشعار مش أكتر من مرة كل ~2.5 ث
                    lastPost = now
                    c.applicationContext.startForegroundService(Intent(c.applicationContext, TaskService::class.java))
                } else if (running) {
                    c.applicationContext.stopService(Intent(c.applicationContext, TaskService::class.java))
                }
            } catch (e: Exception) { LogStore.err("TaskService:sync", e) }
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null   // (v456) عشان تحميل الفيديو قبل المعالجة ما يتقطعش لما الشاشة تتقفل

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "مهام شغّالة", NotificationManager.IMPORTANCE_LOW))
        if (i == null) { try { TaskStore.resume(applicationContext) } catch (_: Throwable) {} }   // (v457) رجعنا بعد قتل العملية: كمّل المهام المحفوظة
        val b = busy()
        if (b.isEmpty()) { stopSelf(startId); return START_NOT_STICKY }
        val n = build(b)
        try { if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n) }
        catch (e: Exception) { LogStore.err("TaskService:fg", e); stopSelf(startId); return START_NOT_STICKY }
        running = true
        try {
            if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "subtitler:task").apply { setReferenceCounted(false) }
            wake?.acquire(2 * 3600 * 1000L)   // بيتجدد مع كل تحديث؛ حد أقصى ساعتين من غير تحديث
        } catch (e: Exception) { LogStore.err("TaskService:wake", e) }
        try {
            if (wifi == null) wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "subtitler:task").apply { setReferenceCounted(false) }
            if (wifi?.isHeld != true) wifi?.acquire()
        } catch (e: Exception) { LogStore.err("TaskService:wifi", e) }
        return START_STICKY
    }

    private fun build(b: List<TaskItem>): Notification {
        val first = b.firstOrNull { it.state == 1 } ?: b.first()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "tasks"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val extra = if (b.size > 1) " · +" + (b.size - 1) + " في الانتظار" else ""
        return Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_sub).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(Icons.plain("⚙ شغّال: " + first.title + " — " + first.pct + "%"))
            .setContentText((first.msg.ifBlank { "التطبيق بيكمّل الشغل حتى لو قفلت الشاشة" }) + extra)
            .setProgress(100, first.pct.coerceIn(0, 100), first.state == 0 || first.pct <= 0).build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }   // الخدمة الأمامية بتكمّل بعد مسح التطبيق من الأخيرة (stopWithTask=false)

    override fun onDestroy() {
        running = false
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
