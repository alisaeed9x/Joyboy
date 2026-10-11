package com.tttt.subtitler

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.File

/** (v215) نسخة احتياطية كاملة: كل إعدادات البرنامج (مفاتيح · ثيم · إعدادات المشغّل والترجمة · …) في ملف بيفضل بعد مسح البرنامج */
object Backup {
    private const val AUTO_NAME = "settings_backup.json"
    private val h = Handler(Looper.getMainLooper())
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var lastAuto = ""
    private val QUIET = setOf("stats", "quota", "lock_fails", "lock_until")   // بتتغيّر كتير — ما بتعملش نسخة لوحدها

    fun toJson(): String = BackupCodec.encode(Cfg.p.all, System.currentTimeMillis())

    /** بيطبّق النسخة على الإعدادات. بيرجّع عدد القيم اللي اتسترجعت. المفاتيح الفاضية في النسخة مابتمسحش مفاتيح موجودة */
    fun apply(json: String): Int {
        val items = BackupCodec.decode(json)
        if (items.isEmpty()) throw IllegalArgumentException("النسخة فاضية")
        val e = Cfg.p.edit(); var n = 0
        for (x in items) {
            if (x.key in BackupCodec.SECRET_FIELDS && (x.value as? String).isNullOrBlank() && Cfg.str(x.key).isNotBlank()) continue
            when (x.type) {
                "b" -> e.putBoolean(x.key, x.value as Boolean)
                "i" -> e.putInt(x.key, x.value as Int)
                "l" -> e.putLong(x.key, x.value as Long)
                "f" -> e.putFloat(x.key, x.value as Float)
                "s" -> e.putString(x.key, x.value as String)
                "set" -> { @Suppress("UNCHECKED_CAST") e.putStringSet(x.key, x.value as Set<String>) }
                else -> continue
            }
            n++
        }
        e.putBoolean("kv_init", true).commit()
        return n
    }

    /** مكان النسخة التلقائية: Android/media/<الحزمة>/ — بيفضل بعد مسح التطبيق وبيتشاف من «الملفات» */
    fun autoFile(ctx: Context): File? =
        try { ctx.externalMediaDirs?.firstOrNull { it != null }?.let { File(it, AUTO_NAME) } } catch (_: Exception) { null }

    /** (v215) النسخة التلقائية مشفّرة بنفس تشفير KeyVault (فيها مفاتيح Gemini) — الملف نفسه ما ينفعش يتقري من تطبيق تاني */
    fun readAuto(ctx: Context): String? = try {
        autoFile(ctx)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.let { raw -> if (raw.trimStart().startsWith("{")) raw else KeyVault.dec(ctx, raw) }
    } catch (_: Exception) { null }

    fun autoTime(ctx: Context): Long = readAuto(ctx)?.let { BackupCodec.timestamp(it) } ?: 0L

    private fun hasKeys() = Cfg.keys("keys").isNotEmpty() || Cfg.keys("backup").isNotEmpty() || Cfg.keys("extra").isNotEmpty()

    /** بيحفظ نسخة تلقائية (آخر واحدة + اللي قبلها). تثبيت جديد من غير مفاتيح ما بيكتبش فوق نسخة موجودة */
    @Synchronized fun autoSave(ctx: Context) {
        try {
            val f = autoFile(ctx) ?: return
            if (f.exists() && !hasKeys()) return
            val body = BackupCodec.encode(Cfg.p.all, 0L)
            if (body == lastAuto && f.exists()) return
            f.parentFile?.mkdirs()
            if (f.exists()) try { f.copyTo(File(f.parentFile, "settings_backup.prev.json"), true) } catch (_: Exception) {}
            f.writeText(KeyVault.enc(ctx, BackupCodec.encode(Cfg.p.all, System.currentTimeMillis())), Charsets.UTF_8)
            lastAuto = body
        } catch (_: Exception) {}
    }

    /** بيراقب أي تغيير في الإعدادات ويحفظ نسخة تلقائية بعد 4 ثواني من آخر تغيير */
    fun attach(ctx: Context) {
        val app = ctx.applicationContext
        if (listener != null) return
        // تثبيت جديد (أو مسح بيانات): نرجّع آخر نسخة تلقائية مرة واحدة قبل ما أي حاجة تتكتب فوقها (استرجاع المفاتيح بيغيّر الإعدادات وكان هيعمل نسخة جديدة فاضية فوق القديمة)
        if (!Cfg.p.getBoolean("bk_gate", false)) {
            try { readAuto(app)?.let { Backup.apply(it) } } catch (_: Throwable) {}
            Cfg.p.edit().putBoolean("bk_gate", true).commit()
        }
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == null || k in QUIET) return@OnSharedPreferenceChangeListener
            h.removeCallbacksAndMessages(null)
            h.postDelayed({ Thread { autoSave(app) }.also { it.isDaemon = true }.start() }, 4000)
        }
        listener = l; Cfg.p.registerOnSharedPreferenceChangeListener(l)
        Thread { autoSave(app) }.also { it.isDaemon = true }.start()
    }
}
