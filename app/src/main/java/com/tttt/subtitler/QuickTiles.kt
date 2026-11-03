package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * (v733) بلاطات «الإعدادات السريعة» (القايمة اللي بتنزلها من فوق، جنب الواي فاي والبلوتوث):
 * 📷 ماسح QR · 📎 سجل الكليبورد · 🔗 مراقب اللينكات (تشغيل/إيقاف) · 🎵 الموسيقى · 🌐 المتصفح · ▶ يوتيوب · 📺 ريموت التلفزيون.
 * بتضيفها بنفسك: اسحب القايمة ← ✏ تعديل ← اسحب البلاطة اللي اسمها «Joyboy …» لفوق.
 */
abstract class JoyTile : TileService() {
    protected abstract val iconRes: Int
    protected abstract val tileLabel: String
    /** الحالة وقت ما القايمة تتفتح */
    protected open fun stateNow(): Int = Tile.STATE_INACTIVE
    protected abstract fun onTap()

    override fun onStartListening() { refreshTile() }
    override fun onTileAdded() { refreshTile() }
    override fun onClick() { try { onTap() } catch (e: Throwable) { try { LogStore.err("Tile:" + tileLabel, e) } catch (_: Throwable) {} }; refreshTile() }

    protected fun refreshTile() {
        val t = qsTile ?: return
        t.label = tileLabel
        t.icon = Icon.createWithResource(this, iconRes)
        t.state = stateNow()
        try { t.updateTile() } catch (_: Throwable) {}
    }

    /** يفتح شاشة ويقفل قايمة الإعدادات السريعة (أندرويد 14+ بيطلب PendingIntent) */
    @Suppress("DEPRECATION")
    protected fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, tileLabel.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else startActivityAndCollapse(i)
    }
    protected fun main(extra: String? = null, v: String = "") = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { if (extra != null) putExtra(extra, if (v.isEmpty()) true else v) }
}

class QrTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_qr
    override val tileLabel = "ماسح QR"
    override fun onTap() = launch(Intent(this, QrScanActivity::class.java))
}

class ClipTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_clip
    override val tileLabel = "سجل الكليبورد"
    override fun onTap() = launch(main("clip_history"))
}

class LinkWatchTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_link
    override val tileLabel = "مراقب اللينكات"
    override fun stateNow(): Int {
        try { Cfg.ensure(applicationContext) } catch (_: Throwable) {}
        return if (!LinkWatch.enabled(this)) Tile.STATE_UNAVAILABLE else if (Cfg.bool("lw_clip_off", false)) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
    }
    override fun onTap() {
        try { Cfg.ensure(applicationContext) } catch (_: Throwable) {}
        if (!LinkWatch.enabled(this)) { launch(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
        Cfg.put("lw_clip_off", if (Cfg.bool("lw_clip_off", false)) "0" else "1")   // إيقاف/تشغيل لقط اللينكات (الدايرة)؛ حفظ السجل مستقل
    }
}

class MusicTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_music
    override val tileLabel = "الموسيقى"
    override fun stateNow(): Int = if (MusicEngine.current != null && MusicEngine.isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    override fun onTap() { if (MusicEngine.current != null) MusicEngine.toggle() else launch(main("tab", "music")) }
    override fun onStartListening() { super.onStartListening(); if (Build.VERSION.SDK_INT >= 29) { qsTile?.subtitle = MusicEngine.current?.title ?: ""; try { qsTile?.updateTile() } catch (_: Throwable) {} } }
}

class BrowserTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_browser
    override val tileLabel = "Joyboy Browser"
    override fun onTap() = launch(Intent(this, LaunchBrowserActivity::class.java))
}

class YoutubeTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_yt
    override val tileLabel = "Joyboy YouTube"
    override fun onTap() = launch(Intent(this, LaunchYoutubeActivity::class.java))
}

class RemoteTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_remote
    override val tileLabel = "ريموت التلفزيون"
    override fun onTap() = launch(Intent(this, LaunchRemoteActivity::class.java))
}

/**
 * (v733) ماسح QR: شاشة شفافة بتفتح الكاميرا (zxing) وتقرا الكود، وبعدين بتسأل: نسخ · فتح · 🎬 افتح في Joyboy (لو لينك).
 * النتيجة بتتسجّل كمان في سجل الكليبورد (المصدر: ماسح QR).
 */
class QrScanActivity : Activity() {
    private var scanning = false
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try { Cfg.init(this) } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 71); return }
        scan()
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (rc == 71 && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) scan()
        else { Notice.show(this, "محتاج صلاحية الكاميرا عشان أقرا الكود", 2500L); finish() }
    }
    private fun scan() {
        if (scanning) return
        scanning = true
        try {
            com.google.zxing.integration.android.IntentIntegrator(this)
                .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.ALL_CODE_TYPES)
                .setPrompt("وجّه الكاميرا على الكود").setBeepEnabled(false).setOrientationLocked(false).initiateScan()
        } catch (e: Throwable) { try { LogStore.err("QrScan", e) } catch (_: Throwable) {}; finish() }
    }
    @Deprecated("zxing legacy")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        scanning = false
        val txt = try { com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(rc, res, data)?.contents } catch (_: Throwable) { null }
        if (txt.isNullOrBlank()) { finish(); return }
        try { if (!Cfg.bool("lw_hist_off", false)) ClipHistory.add(this, txt, packageName, "ماسح QR", "") } catch (_: Throwable) {}
        val isUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(txt.trim())
        val ab = AlertDialog.Builder(this).setTitle("📷 النتيجة").setMessage(txt)
            .setPositiveButton("📋 نسخ") { _, _ ->
                try { ClipHistory.ignoreText = txt.trim(); ClipHistory.ignoreUntil = System.currentTimeMillis() + 4000L; ClipWatch.markSeen(this, txt); (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("qr", txt)); Notice.show(this, "اتنسخ ✅", 1200L) } catch (_: Throwable) {}
                finish()
            }
            .setNegativeButton("إغلاق") { _, _ -> finish() }
            .setOnCancelListener { finish() }
        if (isUrl) {
            ab.setNeutralButton("🔗 فتح") { _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(txt.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}; finish() }
        }
        ab.show()
    }
}
