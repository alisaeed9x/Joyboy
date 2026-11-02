package com.tttt.subtitler

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * (v352) «أوضاع الحركة والتفاعل» العامة — مستقلة عن تخطيطات تلفزيون TvSkins (Hub / Original S / Glass / الشركات).
 * بتأثر على استدارة الزراير والكروت · حركة الأنيميشنز وسرعتها · ضغطة الزرار · شكل الفولدرات · أصوات اللمس.
 * الاسم الداخلي Skin محفوظ للتوافق؛ للمستخدم تظهر كـ«نمط الحركة» حتى لا تختلط بتخطيط التلفزيون (TvSkin) أو Theme.
 */
class Skin(val id: String, val name: String,
           val radius: Float,        // مقياس استدارة الزوايا (1 = الأصلي)
           val anim: Float,          // مقياس مدة الأنيميشن (1 = الأصلي)
           val overshoot: Float,     // 0 = من غير ارتداد، 1 = الأصلي، >1 أقوى
           val press: Float,         // مقياس الضغطة (0.95 = الأصلي)
           val sound: String,        // none / fluent / pop / tick / soft
           val folder: Int)          // 0 = الأصلي، 1 = فولدر أصفر ويندوز 11

object Skins {
    val all: List<Skin> = listOf(
        Skin("classic", "الأصلي", 1f, 1f, 1f, 0.95f, "none", 0),
        Skin("win11", "ويندوز 11 (Fluent)", 0.6f, 1.15f, 0f, 0.97f, "fluent", 1),
        Skin("bubble", "فقاعي مرح", 1.6f, 1.1f, 1.8f, 0.9f, "pop", 0),
        Skin("sharp", "حاد وسريع", 0.2f, 0.7f, 0f, 0.98f, "tick", 0),
        Skin("calm", "هادئ ناعم", 1.2f, 1.5f, 0f, 0.98f, "soft", 0)
    )
    @Volatile private var curSkin: Skin? = null
    fun cur(): Skin {
        curSkin?.let { return it }
        val s = try { all.firstOrNull { it.id == Cfg.str("skin", "classic") } ?: all[0] } catch (_: Throwable) { return all[0] }
        curSkin = s; return s
    }
    fun set(id: String) { curSkin = all.firstOrNull { it.id == id } ?: all[0] }
    fun soundOn(): Boolean = try { Cfg.str("skin_sound", "1") != "0" } catch (_: Throwable) { false }
    /** استدارة بعد تطبيق الوضع (الدواير والكبسولات r ≥ 40 بتفضل زي ما هي) */
    fun rad(r: Int): Int { if (r <= 0 || r >= 40) return r; return (r * cur().radius).roundToInt().coerceAtLeast(1) }
    fun radF(px: Float, density: Float): Float { val r = px / density; return if (r <= 0f || r >= 40f) px else px * cur().radius }
    fun dur(ms: Long): Long = (ms * cur().anim).toLong().coerceAtLeast(60L)
    fun interp(tension: Float): android.view.animation.Interpolator {
        val o = cur().overshoot
        return if (o <= 0f) android.view.animation.DecelerateInterpolator(1.8f) else android.view.animation.OvershootInterpolator(tension * o)
    }
    /** اسم الأيقونة بعد تطبيق الوضع (فولدرات ويندوز 11) */
    fun icon(name: String): String = if (cur().folder == 1 && (name == "folder" || name == "folder_open")) "$name@w11" else name
}

/** أصوات لمس قصيرة بتتولّد بالكود (من غير ملفات) وبتتشغّل بـ SoundPool — مابتاخدش audio focus فمابتوقّفش الموسيقى */
object SkinSound {
    private var sp: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private var last = 0L

    /** event: 0 = ضغطة · 1 = سويتش · 2 = تبديل تبويب */
    fun event(ctx: Context, event: Int) {
        try {
            val s = Skins.cur(); if (s.sound == "none" || !Skins.soundOn()) return
            val now = System.currentTimeMillis(); if (now - last < 45L) return; last = now
            val app = ctx.applicationContext
            val pool = sp ?: SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().also {
                it.setOnLoadCompleteListener { p, id, st -> if (st == 0) synchronized(ready) { ready.add(id) } }
                sp = it
            }
            val key = s.sound + event
            val id = ids.getOrPut(key) {
                val f = File(app.cacheDir, "skin_$key.wav")
                if (!f.exists()) FileOutputStream(f).use { it.write(wav(gen(s.sound, event))) }
                pool.load(f.absolutePath, 1)
            }
            if (synchronized(ready) { id in ready }) pool.play(id, 0.35f, 0.35f, 1, 0, 1f)
        } catch (_: Throwable) {}
    }

    private const val SR = 22050
    private fun gen(kind: String, event: Int): ShortArray {
        val f = when (event) { 1 -> 1.25; 2 -> 0.85; else -> 1.0 }
        fun tone(ms: Int, fn: (t: Double, p: Double) -> Double): ShortArray {
            val n = SR * ms / 1000
            return ShortArray(n) { i -> val t = i.toDouble() / SR; val p = i.toDouble() / n; (fn(t, p).coerceIn(-1.0, 1.0) * 30000).toInt().toShort() }
        }
        return when (kind) {
            "fluent" -> tone(95) { t, p -> (sin(2 * PI * 880 * f * t) * 0.55 + sin(2 * PI * 1318 * f * t) * 0.35) * exp(-p * 5.5) * minOf(1.0, p * 40) }
            "pop" -> tone(85) { t, p -> sin(2 * PI * (320 + 700 * p) * f * t) * exp(-p * 4.0) * minOf(1.0, p * 60) }
            "tick" -> tone(24) { t, p -> (sin(2 * PI * 2100 * f * t) * 0.8 + (Math.random() - 0.5) * 0.25) * exp(-p * 7.0) }
            else -> tone(110) { t, p -> sin(2 * PI * 560 * f * t) * 0.8 * exp(-p * 4.5) * minOf(1.0, p * 25) }   // soft
        }
    }
    private fun wav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2; val out = java.io.ByteArrayOutputStream(44 + dataLen)
        fun i32(v: Int) { out.write(v and 255); out.write((v shr 8) and 255); out.write((v shr 16) and 255); out.write((v shr 24) and 255) }
        fun i16(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }
        out.write("RIFF".toByteArray()); i32(36 + dataLen); out.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(SR); i32(SR * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(dataLen); for (s in pcm) i16(s.toInt())
        return out.toByteArray()
    }
}
