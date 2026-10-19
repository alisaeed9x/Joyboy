package com.tttt.subtitler

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * (v418) دبلجة عربي على طريقة VideoLingo: كل جملة مترجمة تتحوّل لصوت (Edge TTS — الافتراضي ar-EG-SalmaNeural)،
 * السرعة بتتظبط عشان الجملة تدخل في مكانها (حد أقصى +40%)، والكل بيتجمّع في ملف WAV واحد بنفس توقيت الفيديو.
 * الإعدادات: dub_voice (ar-EG-SalmaNeural | ar-EG-ShakirNeural) · dub_orig_vol (صوت الفيديو الأصلي وقت الدبلجة، 0–100، الافتراضي 12)
 */
object EdgeTts {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val VER = "130.0.2849.68"
    private const val WIN_EPOCH = 11644473600L
    @Volatile private var skewSec = 0L
    private val client: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    private fun gec(): String {
        var t = System.currentTimeMillis() / 1000 + skewSec + WIN_EPOCH
        t -= t % 300
        val h = MessageDigest.getInstance("SHA-256").digest(("${t * 10_000_000L}$TOKEN").toByteArray(Charsets.US_ASCII))
        return h.joinToString("") { String.format(Locale.US, "%02X", it) }
    }

    private fun stamp(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date()) + " GMT+0000 (Coordinated Universal Time)"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    /** بيرجّع بايتات MP3 أو null. [ratePct] نسبة تسريع (0 = عادي، 20 = أسرع 20%) */
    fun synth(text: String, voice: String, ratePct: Int): ByteArray? {
        for (attempt in 0..1) {
            val r = synthOnce(text, voice, ratePct)
            if (r != null) return r
        }
        return null
    }

    private fun synthOnce(text: String, voice: String, ratePct: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TOKEN" +
            "&Sec-MS-GEC=${gec()}&Sec-MS-GEC-Version=1-$VER&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}"
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
            .header("Cookie", "muid=$muid;")
            .build()
        val rate = (if (ratePct >= 0) "+" else "") + ratePct + "%"
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ar-EG'><voice name='$voice'>" +
            "<prosody pitch='+0Hz' rate='$rate' volume='+0%'>${esc(text)}</prosody></voice></speak>"
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ts = stamp()
                webSocket.send("X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                webSocket.send("X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml")
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (text.contains("Path:turn.end")) done.countDown() }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val hl = ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)
                if (b.size < 2 + hl) return
                val head = String(b, 2, hl, Charsets.UTF_8)
                if (head.contains("Path:audio")) out.write(b, 2 + hl, b.size - 2 - hl)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                try {   // ساعة الجهاز مختلفة عن السيرفر: بنظبط الفرق من هيدر Date ونعيد
                    val d = response?.header("Date")
                    if (d != null) { val sv = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(d); if (sv != null) skewSec = (sv.time - System.currentTimeMillis()) / 1000 }
                } catch (_: Throwable) {}
                failed.set(true); done.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
        })
        val ok = try { done.await(40, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        try { ws.close(1000, null) } catch (_: Throwable) {}
        if (!ok || failed.get() || out.size() < 200) return null
        return out.toByteArray()
    }
}

object Pcm {
    class Clip(val rate: Int, val data: ShortArray)

    /** فك MP3/AAC لـ PCM مونو 16-bit */
    fun decode(f: File): Clip? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(f.absolutePath)
            var idx = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) { val tf = ex.getTrackFormat(i); if ((tf.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { idx = i; fmt = tf; break } }
            if (idx < 0 || fmt == null) return null
            ex.selectTrack(idx)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val raw = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            val t0 = System.currentTimeMillis()
            while (!outDone && System.currentTimeMillis() - t0 < 30_000L) {
                if (!inDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10_000)
                if (ob >= 0) {
                    if (info.size > 0) {
                        val b = codec.getOutputBuffer(ob)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val arr = ByteArray(info.size); b.get(arr); raw.write(arr)
                    }
                    codec.releaseOutputBuffer(ob, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            val bytes = raw.toByteArray()
            val n = bytes.size / 2
            if (n <= 0) return null
            val all = ShortArray(n) { ((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() }
            if (ch <= 1) return Clip(rate, all)
            val mono = ShortArray(n / ch) { i -> var s = 0; for (c in 0 until ch) s += all[i * ch + c].toInt(); (s / ch).toShort() }
            return Clip(rate, mono)
        } catch (_: Throwable) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    fun resample(c: Clip, to: Int): ShortArray {
        if (c.rate == to) return c.data
        val n = (c.data.size.toLong() * to / c.rate).toInt()
        val out = ShortArray(n)
        val ratio = c.rate.toDouble() / to
        for (i in 0 until n) {
            val p = i * ratio; val a = p.toInt(); val fr = p - a
            val x = c.data[a.coerceAtMost(c.data.size - 1)].toInt(); val y = c.data[(a + 1).coerceAtMost(c.data.size - 1)].toInt()
            out[i] = (x + (y - x) * fr).toInt().toShort()
        }
        return out
    }

    fun writeWav(f: File, data: ShortArray, rate: Int) {
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            val len = data.size * 2
            fun w32(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
            fun w16(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte())) }
            r.writeBytes("RIFF"); w32(36 + len); r.writeBytes("WAVEfmt "); w32(16); w16(1); w16(1); w32(rate); w32(rate * 2); w16(2); w16(16)
            r.writeBytes("data"); w32(len)
            val bb = ByteArray(len)
            for (i in data.indices) { bb[i * 2] = data[i].toByte(); bb[i * 2 + 1] = (data[i].toInt() shr 8).toByte() }
            r.write(bb)
        }
    }
}

object Dub {
    class Seg(val start: Double, val end: Double, val text: String)
    const val RATE = 24000
    private const val TOL = 1.5          // أقصى تمديد بعد نهاية الجملة (ثواني) لو الجملة اللي بعدها لسه بعيدة
    private const val MAX_FAST = 40      // أقصى تسريع % (VideoLingo: 1.4×)

    fun voice(): String = Cfg.str("dub_voice", "ar-EG-SalmaNeural")
    fun toggleVoice(): String { val n = if (voice().contains("Salma")) "ar-EG-ShakirNeural" else "ar-EG-SalmaNeural"; Cfg.put("dub_voice", n); return n }
    fun voiceLabel(v: String = voice()): String = if (v.contains("Salma")) "سلمى (أنثى)" else "شاكر (ذكر)"
    fun origVol(): Float = (Cfg.int("dub_orig_vol", 12).coerceIn(0, 100)) / 100f

    fun segsOf(subs: List<Sub>): List<Seg> =
        subs.filter { !it.isSound && it.translated.isNotBlank() }.sortedBy { it.start }
            .map { Seg(it.start, maxOf(it.end, it.start + 0.4), it.translated.replace(Regex("\\s+"), " ").trim()) }

    fun fileFor(ctx: Context, key: String): File = File(File(ctx.filesDir, "dub").also { it.mkdirs() }, key + ".wav")

    private fun hashOf(segs: List<Seg>, voice: String): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(voice.toByteArray())
        for (s in segs) md.update("${(s.start * 100).toLong()}|${(s.end * 100).toLong()}|${s.text}\n".toByteArray())
        return md.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    /** بيبني ملف الدبلجة. بيرجّع الملف أو null لو اتلغى/فشل. [progress] (تم، الكل). بيشتغل على خيط خلفي */
    fun build(ctx: Context, key: String, segs: List<Seg>, totalSec: Double, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): File? {
        if (segs.isEmpty()) return null
        val voice = voice()
        val out = fileFor(ctx, key)
        val meta = File(out.path + ".meta")
        val h = hashOf(segs, voice)
        if (out.exists() && meta.exists() && meta.readText() == h) return out
        val tmp = File(ctx.cacheDir, "dub_tts").also { it.mkdirs() }
        val total = maxOf(totalSec, (segs.last().end + 1.0))
        val mix = ShortArray((total * RATE).toInt() + RATE)
        var fails = 0
        for ((i, s) in segs.withIndex()) {
            if (cancelled()) return null
            progress(i, segs.size)
            val nextStart = if (i + 1 < segs.size) segs[i + 1].start else total
            val slot = minOf(s.end - s.start + TOL, nextStart - s.start).coerceAtLeast(0.7)
            var clip = clipFor(tmp, s.text, voice, 0)
            if (clip == null) { if (++fails >= 6 && i < 7) return null; continue }   // أول جمل كلها فاشلة = مفيش نت/الخدمة واقفة
            var dur = clip.data.size.toDouble() / clip.rate
            if (dur > slot * 1.03) {
                val pct = Math.ceil((dur / slot - 1.0) * 100.0).toInt().coerceIn(5, MAX_FAST)
                val c2 = clipFor(tmp, s.text, voice, pct)
                if (c2 != null) { clip = c2; dur = clip.data.size.toDouble() / clip.rate }
            }
            val pcm = Pcm.resample(clip, RATE)
            val at = (s.start * RATE).toInt().coerceAtLeast(0)
            val limit = if (i + 1 < segs.size) (nextStart * RATE).toInt() else mix.size
            val n = minOf(pcm.size, (limit - at).coerceAtLeast(0), mix.size - at)
            for (k in 0 until n) {
                var v = mix[at + k] + pcm[k]
                if (k >= n - 480 && n < pcm.size) v = mix[at + k] + (pcm[k] * ((n - k) / 480.0)).toInt()   // فيد قصير لو اتقطعت عند الجملة اللي بعدها
                mix[at + k] = v.coerceIn(-32768, 32767).toShort()
            }
        }
        progress(segs.size, segs.size)
        Pcm.writeWav(out, mix, RATE)
        meta.writeText(h)
        return out
    }

    private fun clipFor(dir: File, text: String, voice: String, pct: Int): Pcm.Clip? {
        val name = MessageDigest.getInstance("MD5").digest("$voice|$pct|$text".toByteArray()).joinToString("") { String.format(Locale.US, "%02x", it) } + ".mp3"
        val f = File(dir, name)
        if (!f.exists() || f.length() < 200) {
            val b = EdgeTts.synth(text, voice, pct) ?: return null
            f.writeBytes(b)
        }
        return Pcm.decode(f)
    }
}

/** تشغيل ملف الدبلجة متزامن مع ExoPlayer: بيوطّي صوت الفيديو الأصلي، ويتابع تشغيل/إيقاف/سرعة/قفز */
class DubPlay(private val p: androidx.media3.exoplayer.ExoPlayer) {
    private val h = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var savedVol = 1f
    private var ready = false
    private var offsetMs = 0L
    val active: Boolean get() = mp != null

    private val tick = object : Runnable {
        override fun run() {
            val m = mp ?: return
            try {
                val want = (p.currentPosition + offsetMs).toInt().coerceAtLeast(0)
                if (p.isPlaying) {
                    val sp = p.playbackParameters.speed
                    if (!m.isPlaying) { m.seekTo(want); try { m.playbackParams = PlaybackParams().setSpeed(sp) } catch (_: Throwable) {}; m.start() }
                    else if (Math.abs(m.currentPosition - want) > 350) m.seekTo(want)
                } else if (m.isPlaying) m.pause()
            } catch (_: Throwable) {}
            h.postDelayed(this, 600)
        }
    }

    fun start(f: File, vidOffsetMs: Long = 0L): Boolean {
        stop()
        return try {
            val m = MediaPlayer(); m.setDataSource(f.absolutePath); m.prepare()
            mp = m; ready = true; offsetMs = vidOffsetMs
            savedVol = p.volume; p.volume = Dub.origVol()
            h.post(tick); true
        } catch (_: Throwable) { mp = null; false }
    }

    fun stop() {
        h.removeCallbacks(tick)
        try { mp?.release() } catch (_: Throwable) {}
        if (mp != null) try { p.volume = savedVol } catch (_: Throwable) {}
        mp = null; ready = false
    }
}
