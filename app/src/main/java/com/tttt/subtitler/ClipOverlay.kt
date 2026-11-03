package com.tttt.subtitler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager

/**
 * (v735) مراقب الكليبورد بإذن «العرض فوق التطبيقات» (من غير ما يحتاج خدمة إمكانية الوصول):
 * خدمة foreground بتسمع على الكليبورد، وأول ما تنسخ أي حاجة من أي تطبيق: بتتحفظ في السجل + يطلع إشعار «اتحفظ»
 * + لو فيها لينك بتظهر الدايرة (فيديو ← تشغيل/تحميل/تلفزيون · غير فيديو ← افتح في متصفح Joyboy).
 * لو خدمة إمكانية الوصول شغّالة بتسيب لها الشغل (هي اللي بتعرف التطبيق اللي نسخت منه).
 */
object ClipOverlay {
    @Volatile var svc: ClipOverlayService? = null
    val running: Boolean get() = svc != null

    fun canRun(ctx: Context): Boolean = LinkHub.overlayOk(ctx)

    /** لازم يشتغل: الإذن متاح ومش كل السويتشات مقفولة ومش موقوف يدويًا */
    fun wanted(ctx: Context): Boolean = canRun(ctx) && !Cfg.bool("lw_ovl_off", false) && !(Cfg.bool("lw_clip_off", false) && Cfg.bool("lw_hist_off", false))

    fun ensure(ctx: Context) {
        try {
            Cfg.ensure(ctx.applicationContext)
            if (wanted(ctx)) { if (svc == null) start(ctx) } else if (svc != null) stop(ctx)
        } catch (e: Throwable) { LogStore.err("ClipOverlay:ensure", e) }
    }

    fun start(ctx: Context) {
        try {
            val i = Intent(ctx.applicationContext, ClipOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.applicationContext.startForegroundService(i) else ctx.applicationContext.startService(i)
        } catch (e: Throwable) { LogStore.err("ClipOverlay:start", e) }   // أندرويد 12+ ممكن يرفض من الخلفية — هيشتغل أول ما تفتح البرنامج
    }

    fun stop(ctx: Context) { try { ctx.applicationContext.stopService(Intent(ctx.applicationContext, ClipOverlayService::class.java)) } catch (_: Throwable) {} }

    /** زرار «جرّب الدايرة» في الإعدادات: بيعرض الدايرة بلينك تجريبي من نفس مسار الخدمة */
    fun test(ctx: Context): Boolean {
        if (!canRun(ctx)) return false
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        LinkHub.toast(app, wm, overlayType(), "📎 اتحفظ في سجل Joyboy")
        LinkHub.show(app, wm, overlayType(), listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), false)
        return true
    }

    fun overlayType(): Int = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
}

class ClipOverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var cm: ClipboardManager? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var grabbing = false
    private lateinit var wm: WindowManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ClipOverlay.svc = this
        try { Cfg.ensure(applicationContext) } catch (_: Throwable) {}
        wm = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            val c = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val l = ClipboardManager.OnPrimaryClipChangedListener { main.post { onClip() } }
            c.addPrimaryClipChangedListener(l)
            cm = c; listener = l
        } catch (e: Throwable) { LogStore.err("ClipOverlay:create", e) }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = notif()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NID, n)
        } catch (e: Throwable) { LogStore.err("ClipOverlay:fg", e) }
        return START_STICKY
    }

    override fun onDestroy() {
        ClipOverlay.svc = null
        try { listener?.let { cm?.removePrimaryClipChangedListener(it) } } catch (_: Throwable) {}
        listener = null
        LinkHub.closeCur()
        super.onDestroy()
    }

    private fun notif(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "مراقب الكليبورد", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 7351,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("clip_history", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_sub).setContentTitle("📎 Joyboy بيراقب الكليبورد")
            .setContentText("اضغط لفتح سجل الكليبورد").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun onClip() {
        if (LinkWatch.connected) return   // خدمة إمكانية الوصول شغّالة: هي اللي بتتعامل (وبتعرف مصدر النسخ)
        if (!LinkHub.overlayOk(this)) return
        if (Cfg.bool("lw_clip_off", false) && Cfg.bool("lw_hist_off", false)) return
        val desc = try { cm?.primaryClipDescription } catch (_: Throwable) { null }
        if (desc != null && !desc.hasMimeType("text/*")) return
        val t = LinkHub.readClip(this)
        if (t.isNotBlank()) { LinkHub.gotClip(this, wm, ClipOverlay.overlayType(), t); return }
        if (grabbing) return
        grabbing = true
        LinkHub.grab(this, wm, ClipOverlay.overlayType()) { txt ->
            grabbing = false
            if (txt.isNotBlank()) LinkHub.gotClip(this, wm, ClipOverlay.overlayType(), txt)
        }
    }

    companion object { private const val CH = "clip_overlay"; private const val NID = 7351 }
}
