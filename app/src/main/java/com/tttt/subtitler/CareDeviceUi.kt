package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.text.format.Formatter
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt

/**
 * (v830 · Joyboy Care) «معلومات الجهاز» — زي CPU-Z: الجهاز · النظام · المعالج (التجمّعات والتردد) · كارت الشاشة (GPU من EGL) ·
 * الذاكرة والتخزين · الشاشة (الدقة/الكثافة/معدل التحديث/الأوضاع) · الكاميرات (ميجابكسل/فتحة/بُعد بؤري) · الحساسات · البطارية.
 * كله قراءة فقط من واجهات النظام الرسمية (Build/Display/Camera2/SensorManager/EGL/proc)؛ أي قيمة الجهاز مش بيكشفها بتتخفي.
 * مفيش أي صلاحيات إضافية. دوسة طويلة على أي سطر = نسخ قيمته، وزرار 📋 فوق = نسخ التقرير كله.
 */
class CareDeviceUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val k = CareKit(act, ui, th)
    private val pal = k.pal
    private lateinit var page: CarePage
    @Volatile private var closed = false

    private class Sec(val emoji: String, val color: Int, val title: String, val rows: List<Pair<String, String>>)

    private var secs: List<Sec> = emptyList()
    private var gpu: List<Pair<String, String>>? = null
    private val collapsed = HashSet<String>().apply { add("الحساسات"); add("الكاميرات") }

    private fun rd(p: String): String? = try { File(p).readText().trim().ifEmpty { null } } catch (_: Throwable) { null }
    private fun sz(b: Long): String = try { Formatter.formatFileSize(act, b) } catch (_: Throwable) { "${b / 1048576} MB" }
    private fun fmtDate(ms: Long): String = try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms)) } catch (_: Throwable) { "" }

    private fun add(l: ArrayList<Pair<String, String>>, key: String, v: String?) { if (!v.isNullOrBlank() && v != "unknown") l.add(key to v) }

    // ---------------- الأقسام ----------------
    private fun deviceSec(): Sec {
        val l = ArrayList<Pair<String, String>>()
        add(l, "الشركة", Build.MANUFACTURER); add(l, "العلامة", Build.BRAND); add(l, "الموديل", Build.MODEL)
        add(l, "الاسم الكودي", Build.DEVICE); add(l, "المنتج", Build.PRODUCT); add(l, "اللوحة (Board)", Build.BOARD); add(l, "العتاد (Hardware)", Build.HARDWARE)
        try { if (act.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")) l.add("نوع الجهاز" to "قابل للطي (حساس زاوية المفصلة موجود)") } catch (_: Throwable) {}
        return Sec("📱", CareColors.OLIVE, "الجهاز", l)
    }

    private fun systemSec(): Sec {
        val l = ArrayList<Pair<String, String>>()
        add(l, "أندرويد", Build.VERSION.RELEASE + " (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= 23) add(l, "تحديث الأمان", Build.VERSION.SECURITY_PATCH)
        add(l, "رقم البناء", Build.DISPLAY); add(l, "Build ID", Build.ID)
        add(l, "النواة (Kernel)", System.getProperty("os.version"))
        add(l, "Bootloader", Build.BOOTLOADER)
        add(l, "نوع البناء", Build.TYPE + (if (Build.TAGS.isNullOrBlank()) "" else " · ${Build.TAGS}"))
        add(l, "تاريخ البناء", fmtDate(Build.TIME))
        add(l, "Fingerprint", Build.FINGERPRINT)
        add(l, "اللغة", Locale.getDefault().toString()); add(l, "المنطقة الزمنية", TimeZone.getDefault().id)
        try {
            val up = SystemClock.elapsedRealtime() / 1000L
            l.add("شغّال بقاله" to ((if (up >= 86400) "${up / 86400} يوم " else "") + "${(up % 86400) / 3600} س ${(up % 3600) / 60} د"))
        } catch (_: Throwable) {}
        return Sec("⚙️", CareColors.SKY, "النظام", l)
    }

    private fun armPart(impl: String, part: String): String {
        if (impl.lowercase(Locale.US) != "0x41") return part
        return when (part.lowercase(Locale.US)) {
            "0xd03" -> "Cortex-A53"; "0xd04" -> "Cortex-A35"; "0xd05" -> "Cortex-A55"; "0xd07" -> "Cortex-A57"; "0xd08" -> "Cortex-A72"
            "0xd09" -> "Cortex-A73"; "0xd0a" -> "Cortex-A75"; "0xd0b" -> "Cortex-A76"; "0xd0d" -> "Cortex-A77"; "0xd41" -> "Cortex-A78"
            "0xd44" -> "Cortex-X1"; "0xd46" -> "Cortex-A510"; "0xd47" -> "Cortex-A710"; "0xd48" -> "Cortex-X2"; "0xd4d" -> "Cortex-A715"
            "0xd4e" -> "Cortex-X3"; "0xd80" -> "Cortex-A520"; "0xd81" -> "Cortex-A720"; "0xd82" -> "Cortex-X4"; "0xd85" -> "Cortex-X925"; "0xd87" -> "Cortex-A725"
            else -> part
        }
    }

    private fun cpuSec(): Sec {
        val l = ArrayList<Pair<String, String>>()
        if (Build.VERSION.SDK_INT >= 31) { add(l, "الشريحة (SoC)", listOfNotNull(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != "unknown" }.joinToString(" ")) }
        val info = rd("/proc/cpuinfo")
        var hw: String? = null; var model: String? = null
        val parts = ArrayList<String>()
        var impl = ""
        if (info != null) {
            for (line in info.lines()) {
                val i = line.indexOf(':'); if (i < 0) continue
                val key = line.substring(0, i).trim().lowercase(Locale.US); val v = line.substring(i + 1).trim()
                when (key) {
                    "hardware" -> if (hw == null) hw = v
                    "model name" -> if (model == null) model = v
                    "cpu implementer" -> impl = v
                    "cpu part" -> parts.add(armPart(impl, v))
                }
            }
        }
        add(l, "العتاد حسب النواة", hw); add(l, "الموديل", model)
        val n = Runtime.getRuntime().availableProcessors()
        l.add("عدد الأنوية" to "$n")
        if (parts.isNotEmpty()) {
            val grouped = parts.groupingBy { it }.eachCount()
            l.add("أنواع الأنوية" to grouped.entries.joinToString(" + ") { "${it.value}× ${it.key}" })
        }
        // التردد من sysfs (ممكن يتمنع على بعض الأجهزة)
        val maxes = ArrayList<Long>(); val curs = ArrayList<Long>()
        for (i in 0 until n) {
            rd("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.toLongOrNull()?.let { maxes.add(it) }
            rd("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.toLongOrNull()?.let { curs.add(it) }
        }
        if (maxes.isNotEmpty()) {
            val g = maxes.groupingBy { it }.eachCount().entries.sortedByDescending { it.key }
            l.add("أقصى تردد" to g.joinToString(" · ") { String.format(Locale.US, "%d× %.2f GHz", it.value, it.key / 1_000_000.0) })
        }
        if (curs.isNotEmpty()) l.add("التردد الحالي" to curs.joinToString(" · ") { "${it / 1000}" } + " MHz")
        rd("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")?.let { l.add("الـGovernor" to it) }
        add(l, "المعمارية (ABI)", Build.SUPPORTED_ABIS.joinToString(", "))
        return Sec("🧠", CareColors.TERRA, "المعالج", l)
    }

    private fun gpuSec(): Sec? {
        val g = gpu ?: return Sec("🎮", CareColors.PLUM, "كارت الشاشة (GPU)", listOf("جاري القراءة…" to ""))
        if (g.isEmpty()) return null
        return Sec("🎮", CareColors.PLUM, "كارت الشاشة (GPU)", g)
    }

    private fun memSec(): Sec {
        val l = ArrayList<Pair<String, String>>()
        val s = CareMetrics.read(act)
        if (s.ramTotal > 0) { l.add("الرام الإجمالي" to sz(s.ramTotal)); l.add("الرام المتاح" to sz(s.ramAvail)) }
        val mi = rd("/proc/meminfo")
        if (mi != null) {
            fun kb(name: String): Long? = mi.lines().firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()?.split(" ")?.firstOrNull()?.toLongOrNull()
            val st = kb("SwapTotal"); val sf = kb("SwapFree")
            if (st != null && st > 0) l.add("الذاكرة الافتراضية (Swap)" to "${sz(st * 1024)} · فاضي ${sz((sf ?: 0L) * 1024)}")
        }
        if (s.stoTotal > 0) { l.add("التخزين الداخلي" to sz(s.stoTotal)); l.add("المتاح" to sz(s.stoFree)) }
        return Sec("💾", CareColors.CARAMEL, "الذاكرة والتخزين", l)
    }

    @Suppress("DEPRECATION")
    private fun displaySec(): Sec {
        val l = ArrayList<Pair<String, String>>()
        try {
            val dm = DisplayMetrics()
            val d = act.windowManager.defaultDisplay
            d.getRealMetrics(dm)
            l.add("الدقة" to "${dm.widthPixels} × ${dm.heightPixels}")
            l.add("الكثافة" to "${dm.densityDpi} dpi (×${String.format(Locale.US, "%.2f", dm.density)})")
            if (dm.xdpi > 0 && dm.ydpi > 0) {
                val inch = sqrt(((dm.widthPixels / dm.xdpi) * (dm.widthPixels / dm.xdpi) + (dm.heightPixels / dm.ydpi) * (dm.heightPixels / dm.ydpi)).toDouble())
                l.add("الحجم التقريبي" to String.format(Locale.US, "%.1f بوصة", inch))
            }
            l.add("معدل التحديث الحالي" to String.format(Locale.US, "%.0f Hz", d.refreshRate))
            try {
                val rates = d.supportedModes.map { Math.round(it.refreshRate) }.distinct().sorted()
                if (rates.size > 1) l.add("معدلات التحديث المدعومة" to rates.joinToString(" · ") { "$it Hz" })
            } catch (_: Throwable) {}
            try { if (d.isWideColorGamut) l.add("نطاق الألوان" to "واسع (Wide Color Gamut)") } catch (_: Throwable) {}
            try {
                val hdr = d.hdrCapabilities?.supportedHdrTypes?.map { when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "HDR" } }
                if (!hdr.isNullOrEmpty()) l.add("HDR" to hdr.joinToString(" · "))
            } catch (_: Throwable) {}
            l.add("أصغر عرض (dp)" to "${act.resources.configuration.smallestScreenWidthDp}")
        } catch (_: Throwable) {}
        return Sec("🖥", CareColors.SAGE, "الشاشة", l)
    }

    private fun camSec(): Sec? {
        val l = ArrayList<Pair<String, String>>()
        try {
            val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                try {
                    val ch = cm.getCameraCharacteristics(id)
                    val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) { CameraCharacteristics.LENS_FACING_FRONT -> "أمامية"; CameraCharacteristics.LENS_FACING_BACK -> "خلفية"; else -> "خارجية" }
                    val parts = ArrayList<String>()
                    ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { parts.add(String.format(Locale.US, "%.1f MP", it.width * it.height / 1_000_000.0)) }
                    ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.let { parts.add("f/$it") }
                    ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.let { parts.add(String.format(Locale.US, "%.1f mm", it)) }
                    ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { parts.add(String.format(Locale.US, "حساس %.1f×%.1f mm", it.width, it.height)) }
                    if (ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(1) == true) parts.add("OIS")
                    if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) parts.add("فلاش")
                    l.add("كاميرا $id ($facing)" to parts.joinToString(" · "))
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return if (l.isEmpty()) null else Sec("📷", CareColors.ROSE, "الكاميرات", l)
    }

    private fun sensorSec(): Sec? {
        val l = ArrayList<Pair<String, String>>()
        try {
            val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            for (s in sm.getSensorList(Sensor.TYPE_ALL).sortedBy { it.type }) {
                val short = if (Build.VERSION.SDK_INT >= 20) s.stringType.substringAfterLast('.') else "type ${s.type}"
                l.add(s.name to (s.vendor + " · " + short))
            }
        } catch (_: Throwable) {}
        return if (l.isEmpty()) null else Sec("📡", CareColors.COFFEE, "الحساسات", l)
    }

    private fun batSec(): Sec {
        val l = ArrayList<Pair<String, String>>()
        val s = CareMetrics.read(act)
        s.bat.value?.let { l.add("المستوى" to "$it%" + (if (s.charging) " (بتشحن)" else "")) }
        s.temp.value?.let { l.add("الحرارة" to String.format(Locale.US, "%.1f °م", it)) }
        s.volt.value?.let { l.add("الفولت" to String.format(Locale.US, "%.2f V", it / 1000f)) }
        try {
            val i: Intent? = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }?.let { l.add("النوع" to it) }
        } catch (_: Throwable) {}
        return Sec("🔋", CareColors.OLIVE, "البطارية", l)
    }

    private fun buildAll() {
        val out = ArrayList<Sec>()
        out.add(deviceSec()); out.add(systemSec()); out.add(cpuSec()); gpuSec()?.let { out.add(it) }
        out.add(memSec()); out.add(displaySec()); camSec()?.let { out.add(it) }; sensorSec()?.let { out.add(it) }; out.add(batSec())
        secs = out.filter { it.rows.isNotEmpty() }
    }

    // ---------------- GPU عن طريق EGL (على خيط خلفي) ----------------
    private fun eglInfo(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return out
        val cfgAttr = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0)
        val cfg = cfgs[0] ?: return out
        val ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        try {
            if (EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
                GLES20.glGetString(GLES20.GL_RENDERER)?.let { out.add("الكارت (Renderer)" to it) }
                GLES20.glGetString(GLES20.GL_VENDOR)?.let { out.add("الشركة" to it) }
                GLES20.glGetString(GLES20.GL_VERSION)?.let { out.add("إصدار OpenGL" to it) }
                GLES20.glGetString(GLES20.GL_EXTENSIONS)?.let { e -> out.add("عدد الامتدادات" to "${e.trim().split(" ").size}") }
            }
        } finally {
            try { EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Throwable) {}
            try { EGL14.eglDestroySurface(dpy, surf) } catch (_: Throwable) {}
            try { EGL14.eglDestroyContext(dpy, ctx) } catch (_: Throwable) {}
        }
        return out
    }

    private fun loadGpu() {
        Thread {
            val r = try { eglInfo() } catch (e: Throwable) { emptyList() }
            try { act.runOnUiThread { if (!closed) { gpu = r; buildAll(); page.redraw() } } } catch (_: Throwable) {}
        }.start()
    }

    // ---------------- الرسم ----------------
    private fun reportText(): String {
        val sb = StringBuilder()
        for (s in secs) {
            sb.append("== ").append(s.title).append(" ==\n")
            for ((a, b) in s.rows) sb.append(a).append(": ").append(b).append('\n')
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    private fun kv(key: String, v: String): View {
        val r = k.rowH().apply { gravity = Gravity.TOP; setPadding(0, k.dp(7), 0, k.dp(7)) }
        r.addView(k.t(key, 12f, pal.muted), LinearLayout.LayoutParams(0, -2, 0.42f).apply { marginEnd = k.dp(8) })
        if (v.isNotEmpty()) r.addView(k.t(v, 13f, pal.text, true), LinearLayout.LayoutParams(0, -2, 0.58f))
        r.isLongClickable = true
        r.setOnLongClickListener { k.copy(key, v.ifEmpty { key }); true }
        return r
    }

    private fun render() {
        val b = page.body
        if (secs.isEmpty()) buildAll()
        for ((si, s) in secs.withIndex()) {
            val isClosed = s.title in collapsed
            val head = k.rowH().apply { background = k.fill(pal.surface, if (isClosed) 20 else 20); setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12)); isClickable = true; isFocusable = true }
            head.addView(k.badge(s.emoji, s.color, 38))
            head.addView(k.t(s.title, 16f, pal.text, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = k.dp(12) })
            head.addView(k.t("${s.rows.size}", 12f, pal.muted).apply { setPadding(k.dp(6), 0, k.dp(8), 0) })
            head.addView(k.t(if (isClosed) "▾" else "▴", 16f, pal.muted))
            head.contentDescription = s.title + (if (isClosed) " (مطوي)" else "")
            head.setOnClickListener { if (isClosed) collapsed.remove(s.title) else collapsed.add(s.title); page.redraw() }
            k.add(b, head, if (si == 0) 4 else 12)
            if (!isClosed) {
                val body = k.col().apply { setPadding(k.dp(14), k.dp(4), k.dp(14), k.dp(4)) }
                for ((a, v) in s.rows) body.addView(kv(a, v))
                k.add(b, body, 2)
            }
        }
        k.add(b, k.t("دوسة طويلة على أي سطر = نسخ القيمة · 📋 فوق = نسخ التقرير كله", 10f, pal.muted).apply { gravity = Gravity.CENTER }, 16)
    }

    fun show() {
        page = k.page("معلومات الجهاز")
        page.action("📋", "نسخ التقرير كله") { k.copy("Device info", reportText()) }
        page.onClose = { closed = true }
        buildAll()
        page.render { render() }
        page.show()
        loadGpu()
    }
}
