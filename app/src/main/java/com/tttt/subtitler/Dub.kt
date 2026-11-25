package com.tttt.subtitler

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
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
/**
 * (v1285) توكن Sec-MS-GEC النسخة 2 لخدمة Edge TTS — منقول بالحرف من edge-tts 7.3.1 (drm.py) ومتأكد إنه بيطلع نفس الناتج.
 * النسخة 1 (SHA-256 العادي) مايكروسوفت بطّلت تقبلها فالخدمة كانت بترفض كل طلب (403) والدبلجة بتفشل.
 */
object EdgeGec {
    private val SH_INIT = longArrayOf(0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL, 0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L)
    private val SH_STATE = longArrayOf(0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL, 0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L)
    private val SH_SCHED = longArrayOf(0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL, 0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L, 0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L, 0xD258B060L, 0x72766564L)
    private val SH_K = longArrayOf(0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L, 0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L, 0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL, 0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L, 0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L, 0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L, 0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L, 0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L)
    private val CS_COEF = longArrayOf(0xE82E91B1L, 0x44DE24C6L, 0x3BEB65BCL, 0xC89C5933L, 0x8D1EC211L, 0x314EF5D5L, 0xE92491B1L, 0xF0A5B9D5L, 0x06064CF3L, 0xDA79FA60L, 0xBED20560L, 0x89F9C9F8L, 0x35FA38C6L, 0xCB49E3C6L)
    private val CS_OFF = longArrayOf(0xB32700D0L, 0x88DFF51DL, 0x29514038L, 0xB328CBDFL, 0xD405B4D5L, 0x26E55F3CL, 0x607801C6L, 0x82B2C1FBL, 0xFA51A3DAL, 0x207CC464L, 0x3C3C74F3L, 0xD2D14B61L, 0xBB04A2C1L, 0xC5626167L)
    private const val SH_ADJ = 0xC9DC06A7L
    private const val SIG0_ADJ = 0x059C0B80L
    private const val SIG1_ADJ = 0x000B92C0L
    private const val CS_FACTOR = 0x82893933L
    private const val CS_ADJ0 = 0xF2F90B1AL
    private const val CS_ADJ1 = 0x0B1A0000L

    private const val M = 0xFFFFFFFFL
    private fun rotr(x: Long, n: Int): Long = ((x ushr n) or (x shl (32 - n))) and M
    private fun rol16(x: Long): Long { val v = x and M; return ((v shl 16) or (v ushr 16)) and M }
    private fun sig0(v: Long): Long = ((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)) and M
    private fun sig1(v: Long): Long = ((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)) and M

    private fun expand(w: LongArray, from: Int) {
        for (i in from until 64) {
            val x = w[i - 2]; val y = w[i - 15]
            val s1 = ((x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)) and M
            val s0 = ((y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)) and M
            w[i] = (s1 + w[i - 7] + s0 + w[i - 16]) and M
        }
    }

    private fun rounds(st: LongArray, w: LongArray, first: Int): LongArray {
        var a = st[0]; var b = st[1]; var c = st[2]; var d = st[3]; var e = st[4]; var f = st[5]; var g = st[6]; var h = st[7]
        for (i in first until 64) {
            val k = if (first == 14 && i == 14) SH_ADJ else SH_K[i]
            val t1 = (h + (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)) + (g xor (e and (f xor g))) + k + w[i]) and M
            val t2 = ((rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)) + ((a and b) or (c and (a or b)))) and M
            h = g; g = f; f = e; e = (d + t1) and M; d = c; c = b; b = a; a = (t1 + t2) and M
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun shaInit(d: ByteArray): LongArray {
        val w = LongArray(64)
        w[14] = ((d[0].toLong() and 255) shl 16) or ((d[1].toLong() and 255) shl 8) or (d[2].toLong() and 255)
        w[15] = ((d[3].toLong() and 255) shl 24) or ((d[4].toLong() and 255) shl 16) or ((d[5].toLong() and 255) shl 8) or (d[6].toLong() and 255)
        for (i in 16 until 30) {
            var v = SH_SCHED[i - 16]
            if (i - 2 >= 14) v += if (i == 16) (sig1(w[i - 2]) xor SIG1_ADJ) else sig1(w[i - 2])
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) v += if (i == 29) (sig0(w[i - 15]) xor SIG0_ADJ) else sig0(w[i - 15])
            w[i] = v and M
        }
        w[30] = (sig1(w[28]) + w[23] + sig0(w[15]) + w[14] + SH_ADJ - SH_K[14]) and M
        expand(w, 31)
        val wk = rounds(SH_STATE, w, 14)
        return LongArray(8) { (SH_INIT[it] + wk[it]) and M }
    }

    private fun shaDigest(data: ByteArray): ByteArray {
        require(data.size >= 7)
        val length = 57 + data.size
        val padLen = Math.floorMod(55 - length, 64)
        val tail = data.size - 7
        val padded = ByteArray(tail + 1 + padLen + 8)
        System.arraycopy(data, 7, padded, 0, tail)
        padded[tail] = 0x80.toByte()
        val bits = length.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        var state = shaInit(data.copyOfRange(0, 7))
        var off = 0
        while (off < padded.size) {
            val w = LongArray(64)
            for (i in 0 until 16) {
                val p = off + i * 4
                w[i] = ((padded[p].toLong() and 255) shl 24) or ((padded[p + 1].toLong() and 255) shl 16) or ((padded[p + 2].toLong() and 255) shl 8) or (padded[p + 3].toLong() and 255)
            }
            expand(w, 16)
            val wk = rounds(state, w, 0)
            state = LongArray(8) { (state[it] + wk[it]) and M }
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (state[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    private fun le32(b: ByteArray, p: Int): Long =
        (b[p].toLong() and 255) or ((b[p + 1].toLong() and 255) shl 8) or ((b[p + 2].toLong() and 255) shl 16) or ((b[p + 3].toLong() and 255) shl 24)

    private fun cs64(data: ByteArray, digest: ByteArray): LongArray {
        val ka = le32(digest, 0) or 1L
        val kb = le32(digest, 4) or 1L
        val factors = longArrayOf((ka * CS_FACTOR) and M, (kb * CS_FACTOR) and M)
        val terms = ArrayList<Long>(); val offsets = ArrayList<Long>()
        for (i in CS_COEF.indices) { terms.add((CS_COEF[i] * factors[i % 2]) and M); offsets.add(CS_OFF[i]) }
        val src = ByteArray(1 + data.size); System.arraycopy(data, 0, src, 1, data.size)
        val padN = (4 - src.size % 4) % 4
        val padded = src.copyOf(src.size + padN)
        val words = ArrayList<Long>()
        for (i in 0 until padded.size / 4) words.add(le32(padded, i * 4))
        if (words.size % 2 == 1) words.add(0L)
        for ((idx, word) in words.withIndex()) {
            var term = word * (if (idx % 2 == 0) ka else kb)
            var off = rol16(word) * (if (idx % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (idx == 0) { term += CS_ADJ0 * factors[0]; off += CS_ADJ1 }
            terms.add(term and M); offsets.add(off and M)
        }
        var swapAcc = 0L; var swapLast = 0L; var state = 0L; var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and M)
            var q = (t * 0x76F50F34L) and M
            swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) and M)) and M
            t = rol16((offsets[i + 1] + y) and M)
            q = (t * 0x1881CE3CL) and M
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and M
            swapAcc = (swapAcc + swapLast) and M
            state = (state * ka + x) and M
            state = (rol16(state) * 0xCF98B111L) and M
            state = (rol16(state) * 0x87085B9FL) and M
            state = (rol16(state) * 0x12CEB96DL) and M
            state = (rol16(state) * 0x257E1D83L) and M
            acc = (acc + state) and M
            t = (state * kb + y) and M
            t = (rol16(t) * 0xA27416F5L) and M
            t = (rol16(t) * 0xD38396FFL) and M
            state = (rol16((rol16(t) * 0x7C932B89L) and M) * 0xBFA49F69L) and M
            acc = (acc + state) and M
            i += 2
        }
        return longArrayOf(swapLast xor state, swapAcc xor acc)
    }

    private fun hex(b: ByteArray): String { val sb = StringBuilder(); for (x in b) sb.append(String.format("%02X", x.toInt() and 255)); return sb.toString() }

    /** Sec-MS-GEC v2: "<16 hex CS64>-<64 hex SHA256>" — unixSec = الوقت بالثواني (مصحّح بفرق الساعة) */
    fun token(unixSec: Long, trustedToken: String): String {
        var t = unixSec + 11644473600L
        t -= t % 300
        val s = (t * 10_000_000L).toString() + trustedToken
        val data = s.toByteArray(Charsets.US_ASCII)
        val dg = shaDigest(data)
        val c = cs64(data, dg)
        val cb = ByteArray(8)
        for (j in 0 until 4) { cb[j] = (c[0] ushr (8 * j)).toByte(); cb[4 + j] = (c[1] ushr (8 * j)).toByte() }
        return hex(cb) + "-" + hex(dg)
    }
}

object EdgeTts {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val VER = "143.0.3650.75"
    private const val MAJOR = "143"
    @Volatile var lastErr: String = ""
    private const val WIN_EPOCH = 11644473600L
    @Volatile private var skewSec = 0L
    private val client: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    private fun gec(): String = EdgeGec.token(System.currentTimeMillis() / 1000 + skewSec, TOKEN)

    private fun stamp(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date()) + " GMT+0000 (Coordinated Universal Time)"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    /** بيرجّع بايتات MP3 أو null. [ratePct] نسبة تسريع (0 = عادي، 20 = أسرع 20%) */
    fun synth(text: String, voice: String, ratePct: Int, pitchHz: Int = 0, volPct: Int = 0): ByteArray? {
        for (attempt in 0..1) {
            val r = synthOnce(text, voice, ratePct, pitchHz, volPct)
            if (r != null) return r
        }
        return null
    }

    private fun synthOnce(text: String, voice: String, ratePct: Int, pitchHz: Int = 0, volPct: Int = 0): ByteArray? {
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TOKEN" +
            "&Sec-MS-GEC=${gec()}&Sec-MS-GEC-Version=2-$VER&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}"
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$MAJOR.0.0.0 Safari/537.36 Edg/$MAJOR.0.0.0")
            .header("Accept-Language", "en-US,en;q=0.9").header("Cookie", "muid=$muid;")
            .build()
        val rate = (if (ratePct >= 0) "+" else "") + ratePct + "%"
        val pitch = (if (pitchHz >= 0) "+" else "") + pitchHz + "Hz"
        val vol = (if (volPct >= 0) "+" else "") + volPct + "%"
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='$voice'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='$vol'>${esc(text)}</prosody></voice></speak>"
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
                lastErr = "" + (response?.code ?: 0) + " " + (t.message ?: t.javaClass.simpleName)
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
    /** (v1217) voice فاضي = الصوت الافتراضي · rate/pitch/vol = نبرة المشاعر (تسريع %، هرتز، صوت %) */
    class Seg(val start: Double, val end: Double, val text: String, val voice: String = "", val rate: Int = 0, val pitch: Int = 0, val vol: Int = 0)
    const val RATE = 24000
    private const val TOL = 1.5          // أقصى تمديد بعد نهاية الجملة (ثواني) لو الجملة اللي بعدها لسه بعيدة
    private const val MAX_FAST = 40      // أقصى تسريع % (VideoLingo: 1.4×)

    fun voice(): String = Cfg.str("dub_voice", "ar-EG-SalmaNeural")
    fun toggleVoice(): String { val n = if (voice().contains("Salma")) "ar-EG-ShakirNeural" else "ar-EG-SalmaNeural"; Cfg.put("dub_voice", n); return n }
    fun voiceLabel(v: String = voice()): String = if (v.contains("Salma")) "سلمى (أنثى)" else "شاكر (ذكر)"
    fun origVol(): Float = (Cfg.int("dub_orig_vol", 12).coerceIn(0, 100)) / 100f

    // (v1217) أصوات عربي لكل جنس (أول واحد = الأساسي). الأسماء متأكدة من قوايم Edge/Azure.
    private val FEM = listOf("ar-EG-SalmaNeural", "ar-SA-ZariyahNeural", "ar-AE-FatimaNeural", "ar-JO-SanaNeural", "ar-KW-NouraNeural", "ar-LB-LaylaNeural", "ar-QA-AmalNeural", "ar-SY-AmanyNeural")
    private val MAL = listOf("ar-EG-ShakirNeural", "ar-SA-HamedNeural", "ar-AE-HamdanNeural", "ar-JO-TaimNeural", "ar-KW-FahedNeural", "ar-LB-RamiNeural", "ar-QA-MoazNeural", "ar-SY-LaithNeural")

    /** دبلجة بأصوات متعددة (حسب جنس المتكلم واسمه) ونبرة المشاعر. dub_multi=0 يرجّع صوت واحد زي الأول */
    fun multi(): Boolean = Cfg.int("dub_multi", 1) != 0

    /** نبرة من حقل emotion/faint اللي جيميناي بيحدده من نبرة الصوت الحقيقية: (تسريع %، pitch هرتز، صوت %) */
    private fun tone(s: Sub): Triple<Int, Int, Int> {
        val t = when (s.emotion) {
            "shout" -> Triple(8, 6, 30)
            "surprise" -> Triple(5, 10, 8)
            "cry" -> Triple(-12, -4, -10)
            "whisper" -> Triple(-8, 0, -35)
            else -> Triple(0, 0, 0)
        }
        return if (s.faint) Triple(t.first, t.second, minOf(t.third, -40)) else t
    }

    private fun clean(s: String): String =
        s.replace(Regex("\\s+"), " ").replace("\u0640", "").replace("\u266A", "").replace(Regex("^[\\-\\u2013\\u2014]+\\s*"), "").trim()

    fun live(): Boolean = Cfg.int("dub_live", 1) != 0

    /** (v1232) صوت ثابت لكل شخصية في الفيديو: أول ما الشخصية تظهر بتاخد صوت من طابور جنسها والاختيار بيتخزّن ومابيتغيّرش أبدًا (بين الباتشات ولا بين الجلسات) */
    @Synchronized
    private fun fixedVoice(vk: String, k: String, g: String): Pair<String, Int> {
        val cfgKey = "dubvm:" + vk
        val m = try { JSONObject(Cfg.str(cfgKey, "{}")) } catch (_: Throwable) { JSONObject() }
        val nk = k.trim().lowercase()
        m.optJSONObject(nk)?.let { o -> return Pair(o.optString("v"), o.optInt("p")) }
        val pool = if (g == "female") FEM else MAL
        var used = 0
        val it = m.keys()
        while (it.hasNext()) { val o = m.optJSONObject(it.next()); if (o != null && o.optString("g") == g) used++ }
        val voice = pool[used % pool.size]
        val wrap = used / pool.size
        val pitch = if (wrap > 0) (if (wrap % 2 == 1) -6 else 6) * ((wrap + 1) / 2) else 0
        try { m.put(nk, JSONObject().put("v", voice).put("p", pitch).put("g", g)); Cfg.put(cfgKey, m.toString()) } catch (_: Throwable) {}
        return Pair(voice, pitch)
    }

    fun segsOf(subs: List<Sub>, vk: String = ""): List<Seg> {
        val list = subs.filter { !it.isSound && it.translated.isNotBlank() }.sortedBy { it.start }
        if (!multi()) return list.map { Seg(it.start, maxOf(it.end, it.start + 0.4), it.translated.replace(Regex("\\s+"), " ").trim()) }
        fun key(s: Sub) = s.speakerName.trim().ifBlank { s.voice.trim() }
        val fav = HashMap<String, Int>()
        for (s in list) { val k = key(s); if (k.isNotEmpty()) fav[k] = (fav[k] ?: 0) + 1 }
        // ترتيب الشخصيات لكل جنس بالأكتر كلامًا: الأكتر = الصوت الأساسي، والباقي أصوات تانية
        val rank = HashMap<String, Int>()
        for (g in listOf("female", "male")) {
            list.filter { it.gender.lowercase() == g && key(it).isNotEmpty() }.map { key(it) }.distinct()
                .sortedByDescending { fav[it] ?: 0 }.forEachIndexed { i, k -> rank[g + "|" + k] = i }
        }
        val out = ArrayList<Seg>()
        for (s in list) {
            val txt = clean(s.translated); if (txt.isBlank()) continue
            val g = s.gender.lowercase(); val k = key(s)
            var voice = ""; var pitchBase = 0
            if ((g == "female" || g == "male") && vk.isNotEmpty() && k.isNotEmpty()) {
                val fv = fixedVoice(vk, k, g); voice = fv.first; pitchBase = fv.second
            } else if (g == "female" || g == "male") {
                val pool = if (g == "female") FEM else MAL
                val idx = if (k.isEmpty()) 0 else (rank[g + "|" + k] ?: 0)
                voice = pool[idx % pool.size]
                val wrap = idx / pool.size
                if (wrap > 0) pitchBase = (if (wrap % 2 == 1) -6 else 6) * ((wrap + 1) / 2)   // لو الشخصيات أكتر من الأصوات: نفس الصوت بطبقة مختلفة
            }
            val (r, pz, v) = tone(s)
            out.add(Seg(s.start, maxOf(s.end, s.start + 0.4), txt, voice, r, pz + pitchBase, v))
        }
        return out
    }

    fun fileFor(ctx: Context, key: String): File = File(File(ctx.filesDir, "dub").also { it.mkdirs() }, key + ".wav")

    private fun hashOf(segs: List<Seg>, voice: String): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(voice.toByteArray())
        for (s in segs) md.update("${(s.start * 100).toLong()}|${(s.end * 100).toLong()}|${s.voice}|${s.rate}|${s.pitch}|${s.vol}|${s.text}\n".toByteArray())
        return md.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    /** بيبني ملف الدبلجة. بيرجّع الملف أو null لو اتلغى/فشل. [progress] (تم، الكل). بيشتغل على خيط خلفي */
    fun build(ctx: Context, key: String, segs: List<Seg>, totalSec: Double, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): File? {
        if (segs.isEmpty()) return null
        val voice = voice()
        val out = fileFor(ctx, key)
        val meta = File(out.path + ".meta")
        val h = hashOf(segs, voice) + (if (Tashkeel.enabled()) "|tsk1" else "")
        if (out.exists() && meta.exists() && meta.readText() == h) return out
        val tmp = File(ctx.cacheDir, "dub_tts").also { it.mkdirs() }
        val say = if (Tashkeel.enabled()) { try { Tashkeel.apply(ctx, segs.map { it.text }, cancelled) } catch (_: Throwable) { segs.map { it.text } } } else segs.map { it.text }   // (v1229) نص النطق (مشكّل) — الجمل المعروضة زي ما هي
        val total = maxOf(totalSec, (segs.last().end + 1.0))
        val mix = ShortArray((total * RATE).toInt() + RATE)
        var fails = 0
        for ((i, s) in segs.withIndex()) {
            if (cancelled()) return null
            progress(i, segs.size)
            val nextStart = if (i + 1 < segs.size) segs[i + 1].start else total
            val slot = minOf(s.end - s.start + TOL, nextStart - s.start).coerceAtLeast(0.7)
            val sv = s.voice.ifBlank { voice }
            val base = s.rate
            val said = say.getOrNull(i) ?: s.text
            var clip = clipFor(tmp, said, sv, base, s.pitch, s.vol)
            if (clip == null) { if (fails == 0) DubLog.add("❌ Edge TTS ماردّش: " + EdgeTts.lastErr); if (++fails >= 6 && i < 7) return null; continue }   // أول جمل كلها فاشلة = مفيش نت/الخدمة واقفة
            var dur = clip.data.size.toDouble() / clip.rate
            if (dur > slot * 1.03) {
                val pct = Math.ceil(((1.0 + base / 100.0) * (dur / slot) - 1.0) * 100.0).toInt().coerceIn(maxOf(5, base), MAX_FAST + maxOf(base, 0))
                val c2 = clipFor(tmp, said, sv, pct, s.pitch, s.vol)
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

    /** (v1232) دبلجة مجموعة جمل متتالية (باتش) في ملف WAV صغير لوحده بتوقيت نسبي لأول جملة. بيرجّع القطعة أو null لو فشل/اتلغى. الملف بيتعاد استخدامه لو نفس الجمل */
    fun buildChunk(ctx: Context, key: String, segs: List<Seg>, cancelled: () -> Boolean): DubPlay.Chunk? {
        if (segs.isEmpty()) return null
        val voice = voice()
        val dir = File(File(ctx.filesDir, "dub"), "live_" + key).also { it.mkdirs() }
        val h = hashOf(segs, voice) + (if (Tashkeel.enabled()) "|tsk1" else "")
        val f = File(dir, MessageDigest.getInstance("MD5").digest(h.toByteArray()).joinToString("") { String.format(Locale.US, "%02x", it) } + ".wav")
        val t0 = segs.first().start
        if (f.exists() && f.length() > 1000) return DubPlay.Chunk(t0, t0 + (f.length() - 44) / 2.0 / RATE, f)
        val tmp = File(ctx.cacheDir, "dub_tts").also { it.mkdirs() }
        val say = if (Tashkeel.enabled()) { try { Tashkeel.apply(ctx, segs.map { it.text }, cancelled) } catch (_: Throwable) { segs.map { it.text } } } else segs.map { it.text }
        val mix = ShortArray(((segs.last().end - t0 + 6.0) * RATE).toInt() + RATE)
        var fails = 0; var ok = 0; var endIdx = 0
        for ((i, s) in segs.withIndex()) {
            if (cancelled()) return null
            val nextStart = if (i + 1 < segs.size) segs[i + 1].start else s.end + TOL
            val slot = minOf(s.end - s.start + TOL, nextStart - s.start).coerceAtLeast(0.7)
            val sv = s.voice.ifBlank { voice }
            val base = s.rate
            val said = say.getOrNull(i) ?: s.text
            var clip = clipFor(tmp, said, sv, base, s.pitch, s.vol)
            if (clip == null) { if (fails == 0) DubLog.add("❌ Edge TTS ماردّش: " + EdgeTts.lastErr); fails++; if (fails >= 4 && ok == 0) return null; continue }
            ok++
            var dur = clip.data.size.toDouble() / clip.rate
            if (dur > slot * 1.03) {
                val pct = Math.ceil(((1.0 + base / 100.0) * (dur / slot) - 1.0) * 100.0).toInt().coerceIn(maxOf(5, base), MAX_FAST + maxOf(base, 0))
                val c2 = clipFor(tmp, said, sv, pct, s.pitch, s.vol)
                if (c2 != null) { clip = c2; dur = clip.data.size.toDouble() / clip.rate }
            }
            val pcm = Pcm.resample(clip, RATE)
            val at = ((s.start - t0) * RATE).toInt().coerceAtLeast(0)
            val limit = if (i + 1 < segs.size) ((nextStart - t0) * RATE).toInt() else mix.size
            val n = minOf(pcm.size, (limit - at).coerceAtLeast(0), mix.size - at)
            for (k in 0 until n) {
                var v = mix[at + k] + pcm[k]
                if (k >= n - 480 && n < pcm.size) v = mix[at + k] + (pcm[k] * ((n - k) / 480.0)).toInt()
                mix[at + k] = v.coerceIn(-32768, 32767).toShort()
            }
            if (at + n > endIdx) endIdx = at + n
        }
        if (ok == 0 || endIdx <= 0) return null
        Pcm.writeWav(f, mix.copyOf(endIdx), RATE)
        return DubPlay.Chunk(t0, t0 + endIdx.toDouble() / RATE, f)
    }

    private fun clipFor(dir: File, text: String, voice: String, pct: Int, pitch: Int = 0, vol: Int = 0): Pcm.Clip? {
        val name = MessageDigest.getInstance("MD5").digest("$voice|$pct|$pitch|$vol|$text".toByteArray()).joinToString("") { String.format(Locale.US, "%02x", it) } + ".mp3"
        val f = File(dir, name)
        if (!f.exists() || f.length() < 200) {
            val b = EdgeTts.synth(text, voice, pct, pitch, vol) ?: return null
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
    class Chunk(val t0: Double, val t1: Double, val file: File)
    private val chunks = java.util.concurrent.CopyOnWriteArrayList<Chunk>()
    @Volatile var live = false
    private var curChunk: Chunk? = null
    val active: Boolean get() = mp != null || live

    /** (v1232) تشغيل مباشر: قطع دبلجة صغيرة (كل باتش لوحده) بتتضاف أول ما تجهز، والتشغيل بيختار القطعة اللي عند مكان الفيديو */
    private val liveTick = object : Runnable {
        override fun run() {
            if (!live) return
            try {
                val posMs = p.currentPosition + offsetMs
                val posS = posMs / 1000.0
                var c: Chunk? = null
                for (x in chunks) if (posS >= x.t0 && posS < x.t1 && (c == null || x.t0 >= c.t0)) c = x
                if (c !== curChunk) {
                    DubLog.add(if (c != null) "▶ قطعة دبلجة " + c.t0.toInt() + "–" + c.t1.toInt() + "ث اشتغلت (الأصلي اتكتم)" else "⏸ مفيش قطعة عند " + posS.toInt() + "ث — الأصلي")
                    try { mp?.release() } catch (_: Throwable) {}
                    mp = null; curChunk = c
                    if (c != null) { val m = MediaPlayer(); m.setDataSource(c.file.absolutePath); m.prepare(); mp = m }
                }
                val m = mp; val cc = curChunk
                if (Design.isNew()) p.volume = if (cc != null) 0f else savedVol
                if (m != null && cc != null) {
                    val want = (posMs - (cc.t0 * 1000).toLong()).toInt().coerceAtLeast(0)
                    if (p.isPlaying) {
                        if (want < m.duration) {
                            val sp = p.playbackParameters.speed
                            if (!m.isPlaying) { m.seekTo(want); try { m.playbackParams = PlaybackParams().setSpeed(sp) } catch (_: Throwable) {}; m.start() }
                            else if (Math.abs(m.currentPosition - want) > 350) m.seekTo(want)
                        }
                    } else if (m.isPlaying) m.pause()
                }
            } catch (_: Throwable) {}
            h.postDelayed(this, 300)
        }
    }

    fun startLive(vidOffsetMs: Long = 0L) {
        stop()
        chunks.clear(); curChunk = null
        live = true; offsetMs = vidOffsetMs
        DubLog.add("🎙 بدأ التشغيل المباشر")
        savedVol = p.volume; if (!Design.isNew()) p.volume = Dub.origVol()
        h.post(liveTick)
    }

    fun addChunk(c: Chunk) { chunks.add(c); DubLog.add("➕ قطعة جاهزة " + c.t0.toInt() + "–" + c.t1.toInt() + "ث (" + chunks.size + " قطعة)") }

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
        h.removeCallbacks(tick); h.removeCallbacks(liveTick)
        try { mp?.release() } catch (_: Throwable) {}
        if (mp != null || live) try { p.volume = savedVol } catch (_: Throwable) {}
        mp = null; ready = false; live = false; curChunk = null; chunks.clear()
    }
}

/**
 * (v1229) تشكيل الجمل العربي قبل النطق (Edge TTS بيقرا أحسن بكتير مع الحركات). الجمل المعروضة ما بتتغيّرش — التشكيل للصوت بس.
 * بيتعمل بجيميناي (مفاتيحك الحقيقية) على دفعات، وبيتخزّن لكل جملة عشان ما يتعادش. لو فشل/مفيش مفتاح: بيتقري من غير تشكيل.
 * بيتأكد إن الحروف ما اتغيّرتش (التشكيل بس)، وإلا بيرفض الرد. dub_tashkeel=0 بيقفله.
 */
object Tashkeel {
    fun enabled(): Boolean = Cfg.int("dub_tashkeel", 1) != 0
    private const val BATCH = 40
    private val MARKS = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private fun plain(s: String): String = s.replace(MARKS, "").replace(Regex("\\s+"), "")
    private fun hasArabic(s: String): Boolean = s.any { it in '\u0621'..'\u064A' }
    private fun already(s: String): Boolean {
        val d = s.count { it in '\u064B'..'\u0652' }; val a = s.count { it in '\u0621'..'\u064A' }
        return a == 0 || d * 3 >= a
    }
    private fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { String.format(Locale.US, "%02x", it) }

    fun apply(ctx: Context, texts: List<String>, cancelled: () -> Boolean): List<String> {
        val out = texts.toMutableList()
        val dir = File(ctx.cacheDir, "dub_tsk").also { it.mkdirs() }
        val todo = ArrayList<Int>()
        for ((i, t) in texts.withIndex()) {
            if (!hasArabic(t) || already(t)) continue
            val f = File(dir, md5(t) + ".txt")
            if (f.exists() && f.length() > 0) { try { out[i] = f.readText(Charsets.UTF_8); continue } catch (_: Throwable) {} }
            todo.add(i)
        }
        if (todo.isEmpty()) return out
        val keys = try { SubFileTr.realGemKeys() } catch (_: Throwable) { emptyList() }
        if (keys.isEmpty()) return out
        val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
        var kStart = 0
        for (chunk in todo.chunked(BATCH)) {
            if (cancelled()) break
            val sb = StringBuilder()
            sb.append("شكّل النصوص العربية التالية تشكيلًا كاملًا وصحيحًا (فتحة وضمة وكسرة وسكون وشدة وتنوين) عشان تتنطق بالفصحى نطقًا سليمًا.\n")
            sb.append("- ممنوع تغيّر أي حرف أو كلمة أو تضيف أو تحذف أو تعيد ترتيب — الحركات بس.\n")
            sb.append("- سيب الأرقام وعلامات الترقيم والرموز زي ما هي.\n")
            sb.append("- ارجع JSON بس بالشكل {\"items\":[{\"i\":0,\"t\":\"...\"}]} بنفس أرقام السطور.\n\nالسطور:\n")
            for ((n, gi) in chunk.withIndex()) sb.append(n).append(": ").append(texts[gi].replace("\n", " ")).append("\n")
            var done = false
            var attempt = 0
            while (!done && attempt < minOf(3, keys.size * 2) && !cancelled()) {
                val key = keys[(kStart + attempt) % keys.size]; attempt++
                try {
                    val r = Api.generate(model, key, sb.toString(), null, 8192, 0.1, true, false)
                    val raw = r.text.trim()
                    val arr = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("items")
                    if (arr != null) {
                        for (k in 0 until arr.length()) {
                            val o = arr.optJSONObject(k) ?: continue
                            val n = o.optInt("i", -1); val t = o.optString("t", "").trim()
                            if (n !in chunk.indices || t.isEmpty()) continue
                            val gi = chunk[n]
                            if (plain(t) != plain(texts[gi])) continue   // لو غيّر كلمات نرفضه ونسيب الأصل
                            out[gi] = t
                            try { File(dir, md5(texts[gi]) + ".txt").writeText(t, Charsets.UTF_8) } catch (_: Throwable) {}
                        }
                        done = true
                    }
                } catch (_: Throwable) {}
            }
            kStart++
        }
        return out
    }
}

/** (v1237) لوج الدبلجة لوحده: آخر ٣٠٠ سطر بتتعرض من زرار 🎙 جنب اللوج، ومتسجّلة كمان في اللوج العادي */
object DubLog {
    private val lines = java.util.ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    fun add(s: String) {
        synchronized(lines) { lines.addLast(fmt.format(java.util.Date()) + "  " + s); while (lines.size > 300) lines.removeFirst() }
        try { LogStore.add("🎙D " + s) } catch (_: Throwable) {}
    }
    fun text(): String = synchronized(lines) { if (lines.isEmpty()) "لسه مفيش أحداث دبلجة — دوس دبلج وهتظهر هنا" else lines.toList().asReversed().joinToString("\n") }
}
