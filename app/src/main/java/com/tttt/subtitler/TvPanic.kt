package com.tttt.subtitler

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * (v612 م3) وضع الطوارئ.
 *  - الموبايل (PhonePanic): وهو متصل بالتلفزيون، ضغطتين باور ورا بعض (الشاشة تنوّر/تطفّي مرتين في ≤1.6 ثانية) ← POST /panic للتلفزيون.
 *  - التلفزيون (TvPanic): يقفل الصوت، يقفل المشغّل/الموسيقى/المتصفح/يوتيوب، يمسح آخر المشاهدة وسجل المتصفح ويوتيوب،
 *    يرجع للرئيسية، ويحاول يقفل الشاشة (إمكانية الوصول)؛ ولو ماقدرش (أو للأمان) بيغطّيها بشاشة سودا لحد أي زرار.
 */
object PhonePanic {
    const val KEY = "panic_power"   // "1" = شغّال (الافتراضي) · "0" = مقفول
    private var rcv: BroadcastReceiver? = null
    private var hits = ArrayList<Long>()
    private var lastFire = 0L

    fun enabled(): Boolean = try { Cfg.str(KEY, "1") != "0" } catch (_: Throwable) { true }
    fun label(): String = if (enabled()) "🚨  طوارئ بالباور مرتين: شغّال (دوس يقفله)" else "🚨  طوارئ بالباور مرتين: مقفول (دوس يشغّله)"
    fun toggle(a: Activity) {
        Cfg.put(KEY, if (enabled()) "0" else "1")
        Notice.show(a, if (enabled()) "🚨 الطوارئ بضغطتين باور: شغّالة (وانت متصل بالتلفزيون)" else "الطوارئ بضغطتين باور: اتقفلت", 2600L)
    }

    /** بيتنادى من RemoteService.onCreate (الخدمة شغّالة طول ما الموبايل متصل بالتلفزيون) */
    @Synchronized fun start(ctx: Context) {
        if (rcv != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try { onScreen(c.applicationContext) } catch (e: Throwable) { LogStore.err("PhonePanic", e) }
            }
        }
        try {
            val f = IntentFilter(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_SCREEN_ON)
            ctx.applicationContext.registerReceiver(r, f)
            rcv = r
        } catch (e: Throwable) { LogStore.err("PhonePanic:start", e) }
    }

    @Synchronized fun stop(ctx: Context) {
        val r = rcv ?: return
        rcv = null
        try { ctx.applicationContext.unregisterReceiver(r) } catch (_: Throwable) {}
    }

    @Synchronized private fun onScreen(app: Context) {
        if (!enabled() || RemoteClient.state != RemoteClient.St.CONNECTED) { hits.clear(); return }
        val now = System.currentTimeMillis()
        hits.removeAll { now - it > 1600L }
        hits.add(now)
        if (hits.size < 2 || now - lastFire < 5000L) return
        hits.clear(); lastFire = now
        fire(app)
    }

    fun fire(app: Context) {
        try { PlayTarget.nowTitle = null; TvModeFab.refresh() } catch (_: Throwable) {}
        Thread {
            var ok = false
            for (i in 0 until 3) {   // 3 محاولات: الشبكة ممكن تكون لسه صاحية من النوم
                try {
                    RemoteClient.init(app)
                    if (RemoteClient.ensureConnected(app, 5000L)) {
                        val r = RemoteClient.postJson("/panic", JSONObject())
                        if (r.first == 200) { ok = true; break }
                    }
                } catch (e: Throwable) { LogStore.err("PhonePanic:fire", e) }
                try { Thread.sleep(700L) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true }.start()
    }
}

object TvPanic {
    private val h = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    /** على التلفزيون: ينفّذ كل حاجة. كل خطوة في try لوحدها عشان فشل واحدة ما يوقّفش الباقي */
    fun run(app: Context) {
        h.post {
            step("mute") { mute(app) }
            step("stop") {
                PlayerActivity.panicFinishAll()
                MusicService.stop(app)
                PlayTarget.nowTitle = null
                try { CastServer.clear() } catch (_: Throwable) {}
            }
            step("history") { clearHistories(app) }
            step("apps") { closeApps(app) }
            h.postDelayed({
                step("sleep") { screenOff() }
                step("mute2") { mute(app) }
            }, 900L)
        }
    }

    private fun step(n: String, f: () -> Unit) { try { f() } catch (e: Throwable) { try { LogStore.err("TvPanic:$n", e) } catch (_: Throwable) {} } }

    private fun mute(app: Context) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) } catch (_: Throwable) {}
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) } catch (_: Throwable) {}
    }

    private fun clearHistories(app: Context) {
        Recents.clearAll(app)                       // آخر المشاهدة
        try { PubHistory.clearAll() } catch (_: Throwable) {}
        try { BrowserStore.clearHistory(app) } catch (_: Throwable) {}
        try { BrowserStore.saveTabs(app, emptyList(), 0) } catch (_: Throwable) {}   // التبويبات المفتوحة
        try { YtHistory.clear(app) } catch (_: Throwable) {}
        try { android.webkit.CookieManager.getInstance().removeAllCookies(null) } catch (_: Throwable) {}
        try { android.webkit.WebStorage.getInstance().deleteAllData() } catch (_: Throwable) {}
        try { TvActivity.live?.get()?.onRemoteCleared() } catch (_: Throwable) {}
    }

    private fun closeApps(app: Context) {
        val top = try { TvLink.topActivity() } catch (_: Throwable) { null }
        if (top != null && top !is TvActivity) try { top.finishAndRemoveTask() } catch (_: Throwable) {}
        try { TvAccess.global(AccessibilityService.GLOBAL_ACTION_HOME) } catch (_: Throwable) {}   // أي تطبيق تاني (يوتيوب الرسمي مثلًا)
        // الرئيسية بتاعتنا (نفس مسار زرار Home: TvActivity.onNewIntent ← showHome)
        app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(app, TvActivity::class.java)
            .putExtra("tv_panic", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))   // (v613) TvActivity تفتح شاشة التوقف (الساعة)
    }

    private fun screenOff() {
        if (Build.VERSION.SDK_INT >= 28) TvAccess.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)   // لو فشلت/ماعملتش حاجة: الشاشة السودا فاضلة
    }

    /** شاشة سودا كاملة فوق كل حاجة؛ أي زرار/لمسة بيشيلها */
    private fun blackout(app: Context) {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(app)) { h.postDelayed({ step("blackDialog") { blackDialog() } }, 800L); return }   // من غير صلاحية الظهور فوق التطبيقات: سودا جوه اللانشر بعد ما نرجعله
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = FrameLayout(app).apply {
            setBackgroundColor(Color.BLACK); isFocusable = true; isFocusableInTouchMode = true
            setOnKeyListener { _, _, _ -> unblack(app); true }
            setOnClickListener { unblack(app) }
        }
        val lp = WindowManager.LayoutParams(-1, -1,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        overlay = v
        v.requestFocus()
    }

    private var dlg: android.app.Dialog? = null
    private fun blackDialog() {
        val a = TvActivity.live?.get() ?: return
        if (a.isFinishing || a.isDestroyed || dlg != null) return
        val d = android.app.Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        d.setOnKeyListener { x, _, _ -> try { x.dismiss() } catch (_: Throwable) {}; dlg = null; true }
        d.setCancelable(true)
        d.show(); dlg = d
    }

    fun unblack(app: Context) {
        val v = overlay ?: return
        overlay = null
        try { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
    }
}
