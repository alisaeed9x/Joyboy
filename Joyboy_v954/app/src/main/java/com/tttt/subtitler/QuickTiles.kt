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
    /** (v734) اسم وسطر تحته بيتغيّروا (عدّاد تنازلي مثلًا). تحت أندرويد 10 مفيش سطر تاني فبيتضم للاسم */
    protected open fun labelNow(): String = tileLabel
    protected open fun subtitleNow(): String? = null
    protected val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** لو true: البلاطة بتتحدّث كل ثانية وهي ظاهرة (عدّاد تنازلي) */
    protected open val ticking = false
    private val tickR = object : Runnable { override fun run() { refreshTile(); if (ticking) handler.postDelayed(this, 1000L) } }
    protected abstract fun onTap()

    override fun onStartListening() { refreshTile(); handler.removeCallbacks(tickR); if (ticking) handler.postDelayed(tickR, 1000L) }
    override fun onStopListening() { handler.removeCallbacks(tickR) }
    override fun onTileAdded() { refreshTile() }
    override fun onClick() { try { onTap() } catch (e: Throwable) { try { LogStore.err("Tile:" + tileLabel, e) } catch (_: Throwable) {} }; refreshTile() }

    protected fun refreshTile() {
        val t = qsTile ?: return
        val sub = try { subtitleNow() } catch (_: Throwable) { null }
        t.label = if (Build.VERSION.SDK_INT >= 29 || sub.isNullOrEmpty()) labelNow() else labelNow() + " · " + sub
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = sub
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
    override fun subtitleNow(): String? = try { ClipHistory.list(this).firstOrNull()?.text?.replace('\n', ' ')?.take(26) } catch (_: Throwable) { null }
    override fun onTap() = launch(main("clip_history"))
}

class LinkWatchTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_link
    override val tileLabel = "مراقب اللينكات"
    override fun stateNow(): Int {
        try { Cfg.ensure(applicationContext) } catch (_: Throwable) {}
        return if (!LinkWatch.enabled(this) && !LinkHub.overlayOk(this)) Tile.STATE_UNAVAILABLE else if (Cfg.bool("lw_clip_off", false)) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
    }
    override fun onTap() {
        try { Cfg.ensure(applicationContext) } catch (_: Throwable) {}
        if (!LinkWatch.enabled(this) && !LinkHub.overlayOk(this)) { launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))); return }   // (v735) الإذن الأساسي = العرض فوق التطبيقات
        Cfg.put("lw_clip_off", if (Cfg.bool("lw_clip_off", false)) "0" else "1")   // إيقاف/تشغيل لقط اللينكات (الدايرة)؛ حفظ السجل مستقل
    }
}

class MusicTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_music
    override val tileLabel = "الموسيقى"
    override fun stateNow(): Int = if (MusicEngine.current != null && MusicEngine.isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    override fun onTap() { if (MusicEngine.current != null) MusicEngine.toggle() else launch(main("tab", "music")) }
    override fun subtitleNow(): String? = MusicEngine.current?.title
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
        val ab = GAlert(this).setTitle("📷 النتيجة").setMessage(txt)
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

/** (v734) 🕌 الصلاة الجاية: اسمها + عدّاد تنازلي بالثانية (بيتحدّث وهي ظاهرة). بتنوّر لما يفضل ربع ساعة أو أقل. دوسة = تحديث المواقيت */
class PrayerTile : JoyTile() {
    companion object { @Volatile var data: PrayerTimes.Data? = null }
    override val iconRes = R.drawable.ic_tile_prayer
    override val tileLabel = "الصلاة الجاية"
    override val ticking = true
    private fun next(): Pair<String, Long>? { val d = data ?: return null; return try { PrayerTimes.nextPrayer(d.times, System.currentTimeMillis()) } catch (_: Throwable) { null } }
    override fun labelNow(): String { val n = next() ?: return "الصلاة الجاية"; return PrayerTimes.ar(n.first) }
    override fun subtitleNow(): String? {
        val n = next() ?: return "بحمّل المواقيت…"
        val left = ((n.second - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        return "%d:%02d:%02d".format(left / 3600, (left % 3600) / 60, left % 60)
    }
    override fun stateNow(): Int { val n = next() ?: return Tile.STATE_INACTIVE; return if (n.second - System.currentTimeMillis() <= 15 * 60_000L) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE }
    private fun load(force: Boolean) { PrayerTimes.loadData(applicationContext, force) { d -> if (d != null) { data = d; handler.post { refreshTile() } } } }
    override fun onStartListening() { super.onStartListening(); load(false) }
    override fun onTap() { load(true) }
}

/** (v734) ⏲ مؤقت نوم الموسيقى: كل دوسة بتغيّر (15 د ← 30 د ← ساعة ← آخر الأغنية ← إيقاف) والوقت المتبقي بيعدّ تنازلي تحت الاسم */
class SleepTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_sleep
    override val tileLabel = "مؤقت نوم الموسيقى"
    override val ticking = true
    private fun on() = MusicEngine.sleepLeftMs() > 0L || MusicEngine.sleepEndOfTrack
    override fun stateNow(): Int = if (on()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    override fun subtitleNow(): String? {
        val l = MusicEngine.sleepLeftMs()
        return if (l > 0L) "%d:%02d".format(l / 60000L, (l / 1000L) % 60L) else if (MusicEngine.sleepEndOfTrack) "آخر الأغنية" else "مقفول"
    }
    override fun onTap() {
        if (MusicEngine.current == null) { android.widget.Toast.makeText(this, "مفيش موسيقى شغّالة", android.widget.Toast.LENGTH_SHORT).show(); return }
        try { Cfg.ensure(applicationContext) } catch (_: Throwable) {}
        val steps = intArrayOf(15, 30, 60, -1, 0)
        val i = if (!on()) 0 else ((Cfg.str("tile_sleep_i", "0").toIntOrNull() ?: 0) + 1) % steps.size
        Cfg.put("tile_sleep_i", i.toString())
        MusicEngine.setSleep(steps[i])
    }
}

/** (v734) 📺 وضع التلفزيون: تشغيل/خروج (منوّرة = متصل) */
class TvModeTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_tv
    override val tileLabel = "وضع التلفزيون"
    override fun stateNow(): Int { try { Cfg.ensure(applicationContext) } catch (_: Throwable) {}; return if (PlayTarget.enabled()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE }
    override fun subtitleNow(): String? = if (stateNow() == Tile.STATE_ACTIVE) "متصل" else "مقفول"
    override fun onTap() = launch(main("tv_toggle"))
}

/** (v734) 📋 المهام: عدد المهام الشغّالة (ترجمة · تحميل · ترميز…) · دوسة = صفحة المهام */
class TasksTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_tasks
    override val tileLabel = "المهام"
    private fun n(): Int = try { TaskCenter.active() } catch (_: Throwable) { 0 }
    override fun stateNow(): Int = if (n() > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    override fun subtitleNow(): String? = n().let { if (it > 0) "$it شغّالة" else "مفيش" }
    override fun onTap() = launch(main("tab", "tasks"))
}

/** (v734) ▶ آخر لينك نسخته: بيفتحه في Joyboy بنفس منطق دايرة الكليبورد (يلاقي الفيديو ويسألك تعمل إيه) */
class LastLinkTile : JoyTile() {
    override val iconRes = R.drawable.ic_tile_play
    override val tileLabel = "آخر لينك نسخته"
    private fun last(): String? = try { ClipHistory.list(this).firstNotNullOfOrNull { ClipWatch.extract(it.text).firstOrNull() } } catch (_: Throwable) { null }
    override fun subtitleNow(): String? = last()?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.")?.take(26) ?: "مفيش"
    override fun onTap() {
        val u = last()
        if (u == null) { android.widget.Toast.makeText(this, "مفيش لينك في السجل", android.widget.Toast.LENGTH_SHORT).show(); return }
        launch(main().putExtra("clip_act", "orb").putExtra("clip_url", u))
    }
}
