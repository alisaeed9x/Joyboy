package me.prince.launcher

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "TvUpdate"

/** Update pushed from the phone: download the APK over Wi-Fi, check it is this launcher, hand it to the system installer. */
object TvUpdate {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    @Volatile private var pendingApk: File? = null

    fun ownVersionCode(c: Context): Long = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Throwable) { 0L }
    fun ownVersionName(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }

    private fun toast(c: Context, s: String) = h.post { try { Toast.makeText(c, s, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} }

    fun start(app: Context, url: String, ver: Long) {
        if (busy) return
        busy = true
        toast(app, "⬇ بنزّل التحديث من الموبايل…")
        Thread {
            val f = File(app.cacheDir, "tv_update.apk")
            try {
                f.delete()
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 15000
                if (c.responseCode != 200) throw IOException("رد الموبايل ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { ins -> FileOutputStream(f).use { out -> ins.copyTo(out, 65536) } }
                if (total > 0 && f.length() != total) throw IOException("الملف ناقص")
                val info = app.packageManager.getPackageArchiveInfo(f.path, 0)
                if (info == null || info.packageName != app.packageName) throw IOException("الملف مش اللانشر")
                h.post { install(app, f) }
            } catch (e: Exception) {
                try { f.delete() } catch (_: Exception) {}
                toast(app, "⚠ التحديث فشل: ${e.message}")
            } finally { busy = false }
        }.also { it.isDaemon = true }.start()
    }

    private fun install(app: Context, f: File) {
        try {
            if (!app.packageManager.canRequestPackageInstalls()) {
                pendingApk = f
                toast(app, "🔐 فعّل «تثبيت تطبيقات غير معروفة» للانشر وارجع — التثبيت هيكمل لوحده")
                app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            pendingApk = null
            val pi = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(app.packageName)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                f.inputStream().use { ins -> s.openWrite("launcher.apk", 0, f.length()).use { out -> ins.copyTo(out, 65536); s.fsync(out) } }
                val cb = PendingIntent.getBroadcast(app, id, Intent(app, InstallResult::class.java).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(cb.intentSender)
            }
        } catch (e: Throwable) { Log.w(TAG, "install", e); toast(app, "⚠ ما قدرتش أبدأ التثبيت") }
    }

    /** Called when any screen comes back: continue an install that was waiting for the "unknown apps" permission */
    fun resumePending(a: Activity) {
        val f = pendingApk ?: return
        try { if (a.packageManager.canRequestPackageInstalls() && f.isFile) install(a.applicationContext, f) } catch (_: Throwable) {}
    }
}

class InstallResult : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            try { if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) { Log.w(TAG, "confirm", e) }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            Log.w(TAG, "install status $st: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
