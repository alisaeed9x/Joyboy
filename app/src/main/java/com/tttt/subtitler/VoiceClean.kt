package com.tttt.subtitler

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as M3
import androidx.media3.exoplayer.ExoPlayer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * (v334) فصل صوت الكلام عن الموسيقى/الضوضاء — يدوي بس، وسريع.
 * الطريقة زي fast-music-remover: موديل صغير من عيلة DeepFilterNet (DPDFNet عن طريق sherpa-onnx اللي في التطبيق)
 * + تقطيع الصوت لقطع قصيرة (20 ثانية + ثانية سياق من كل ناحية) وتشغيلها بالتوازي على كذا خيط، وكل خيط ليه نسخة موديل.
 * الناتج WAV مونو (16k أو 48k) بيتشغّل جنب الفيديو من غير ما الفيديو يتأثر (VoiceClean.Playback).
 * ملحوظة: مزيل الضوضاء ده معمول للكلام — بيطلّع الصوت بس، مش فاصل غناء.
 */
object VoiceClean {
    class Mdl(val file: String, val label: String, val note: String, val family: String, val rate: Int)

    val models = listOf(
        Mdl("dpdfnet2.onnx", "⚡ سريع", "16kHz — الأسرع", "dpdfnet", 16000),
        Mdl("dpdfnet2_48khz_hr.onnx", "🎧 جودة أعلى", "48kHz — أنقى وأتقل شوية", "dpdfnet", 48000)
    )
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/"
    private const val MIN_BYTES = 200_000L
    private const val PK = "com.k2fsa.sherpa.onnx."

    fun model(): Mdl = models.firstOrNull { it.file == Cfg.str("vc_model", models[0].file) } ?: models[0]
    fun setModel(file: String) { try { Cfg.put("vc_model", file) } catch (_: Throwable) {} }

    fun modelFile(ctx: Context, m: Mdl): File = Offline.pick("voice/" + m.file, MIN_BYTES) ?: File(File(ctx.filesDir, "models"), "voice/" + m.file)
    fun has(ctx: Context, m: Mdl): Boolean = modelFile(ctx, m).let { it.isFile && it.length() > MIN_BYTES }

    // ===== التنزيل (بيكمّل من حيث وقف) =====
    @Volatile private var dlThread: Thread? = null
    @Volatile private var dlCancel = false
    fun downloading(): Boolean = dlThread?.isAlive == true
    fun cancelDownload() { dlCancel = true }

    /** progress(نزّل، الكلّي أو -1) · done(null = نجح، غير كده السبب) — من خيط التنزيل */
    fun download(ctx: Context, m: Mdl, progress: (Long, Long) -> Unit, done: (String?) -> Unit) {
        if (downloading()) return
        val app = ctx.applicationContext
        dlCancel = false
        dlThread = Thread {
            var err: String? = null
            try {
                val dst = modelFile(app, m)
                dst.parentFile?.mkdirs()
                val part = File(dst.path + ".part")
                var have = if (part.isFile) part.length() else 0L
                val c = URL(BASE + m.file).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 20000; c.readTimeout = 30000
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    val code = c.responseCode
                    if (code == 416) { part.delete(); throw IOException("اتعاد من الأول — جرّب تاني") }
                    if (code != 200 && code != 206) throw IOException("HTTP $code")
                    if (code == 200 && have > 0) { have = 0L; part.delete() }
                    val len = c.contentLengthLong
                    val total = if (len > 0) len + have else -1L
                    FileOutputStream(part, have > 0).use { os ->
                        c.inputStream.use { ins ->
                            val buf = ByteArray(64 * 1024); var last = 0L
                            while (true) {
                                if (dlCancel) throw IOException("اتلغى")
                                val n = ins.read(buf); if (n < 0) break
                                os.write(buf, 0, n); have += n
                                val now = System.currentTimeMillis()
                                if (now - last > 400) { last = now; progress(have, total) }
                            }
                        }
                    }
                    progress(have, total)
                } finally { try { c.disconnect() } catch (_: Throwable) {} }
                if (have < MIN_BYTES) throw IOException("التنزيل ناقص — جرّب تاني")
                if (dst.exists()) dst.delete()
                if (!part.renameTo(dst)) throw IOException("مقدرتش أحفظ الملف")
            } catch (t: Throwable) { err = (t.message ?: t.javaClass.simpleName).take(120) }
            try { done(err) } catch (_: Throwable) {}
        }.also { it.isDaemon = true; it.start() }
    }

    // ===== sherpa-onnx بالـ reflection (لو الأسماء اتغيّرت أو النسخة قديمة بتظهر رسالة واضحة بدل ما البناء يقع) =====
    private fun setProp(o: Any, name: String, v: Any?) {
        val setter = "set" + name.replaceFirstChar { it.uppercase() }
        for (mt in o.javaClass.methods) if (mt.name == setter && mt.parameterTypes.size == 1) { mt.invoke(o, v); return }
        val f = o.javaClass.getDeclaredField(name); f.isAccessible = true; f.set(o, v)
    }

    private fun newDenoiser(modelPath: String, family: String, threads: Int): Any {
        try {
            val fam = Class.forName(PK + if (family == "gtcrn") "OfflineSpeechDenoiserGtcrnModelConfig" else "OfflineSpeechDenoiserDpdfNetModelConfig").getDeclaredConstructor().newInstance()
            setProp(fam, "model", modelPath)
            val mc = Class.forName(PK + "OfflineSpeechDenoiserModelConfig").getDeclaredConstructor().newInstance()
            setProp(mc, family, fam)
            setProp(mc, "numThreads", threads)
            try { setProp(mc, "debug", false) } catch (_: Throwable) {}
            val cfg = Class.forName(PK + "OfflineSpeechDenoiserConfig").getDeclaredConstructor().newInstance()
            setProp(cfg, "model", mc)
            val cls = Class.forName(PK + "OfflineSpeechDenoiser")
            val c2 = cls.constructors.firstOrNull { it.parameterTypes.size == 2 }
            return if (c2 != null) c2.newInstance(null, cfg) else cls.constructors.first { it.parameterTypes.size == 1 }.newInstance(cfg)
        } catch (e: ClassNotFoundException) {
            throw IOException("نسخة sherpa-onnx اللي في التطبيق مفيهاش مزيل الضوضاء (${e.message?.substringAfterLast('.')}) — لازم نرفع sherpaVer")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw IOException("الموديل مفتحش: " + (e.targetException?.message ?: "؟").take(100))
        } catch (e: NoSuchMethodException) {
            throw IOException("شكل واجهة sherpa-onnx مختلف عن المتوقع: " + (e.message ?: "").take(100))
        }
    }

    private fun rateOf(d: Any, m: Mdl): Int = try {
        (d.javaClass.methods.first { it.name == "getSampleRate" && it.parameterTypes.isEmpty() }.invoke(d) as Number).toInt().let { if (it > 0) it else m.rate }
    } catch (_: Throwable) { m.rate }

    private fun denoise(d: Any, x: FloatArray, rate: Int): FloatArray {
        val run = d.javaClass.methods.first { it.name == "run" && it.parameterTypes.size == 2 }
        val r = run.invoke(d, x, rate) ?: return FloatArray(0)
        return r.javaClass.methods.first { it.name == "getSamples" && it.parameterTypes.isEmpty() }.invoke(r) as FloatArray
    }

    private fun release(d: Any) { try { d.javaClass.methods.firstOrNull { it.name == "release" && it.parameterTypes.isEmpty() }?.invoke(d) } catch (_: Throwable) {} }

    // ===== الناتج =====
    private fun dir(ctx: Context) = File(ctx.filesDir, "voiceclean").apply { mkdirs() }
    fun outFile(ctx: Context, uri: Uri, durMs: Long, m: Mdl = model()): File =
        File(dir(ctx), "v_" + Integer.toHexString((uri.toString() + "|" + durMs).hashCode()) + "_" + (m.rate / 1000) + "k.wav")
    fun ready(ctx: Context, uri: Uri, durMs: Long): File? = outFile(ctx, uri, durMs).takeIf { it.isFile && it.length() > 1000 }
    fun deleteAll(ctx: Context) { try { dir(ctx).listFiles()?.forEach { it.delete() } } catch (_: Throwable) {} }
    private fun prune(ctx: Context, keep: Int = 6) {
        try { dir(ctx).listFiles { f -> f.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() } } catch (_: Throwable) {}
    }

    @Volatile var busy = false
        private set
    @Volatile private var cancel = false
    fun cancel() { cancel = true }

    /** بيشتغل في خيط لوحده. onPct 0..100 · done(ملف الناتج، أو السبب) */
    fun run(ctx: Context, uri: Uri, durMs: Long, onPct: (Int) -> Unit, done: (File?, String?) -> Unit) {
        if (busy) return
        busy = true; cancel = false
        val app = ctx.applicationContext
        Thread {
            var out: File? = null; var err: String? = null
            try { out = process(app, uri, durMs, onPct) } catch (t: Throwable) {
                err = (t.message ?: t.javaClass.simpleName).take(160)
                if (err != "اتلغى") LogStore.err("voiceclean", t)
            }
            busy = false
            try { done(out, err) } catch (_: Throwable) {}
        }.also { it.isDaemon = true; it.start() }
    }

    private fun wavHeader(rate: Int, dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }

    private fun process(app: Context, uri: Uri, durMs: Long, onPct: (Int) -> Unit): File {
        val m = model()
        val mf = modelFile(app, m)
        if (!has(app, m)) throw IOException("موديل الفصل مش نازل")
        val final = outFile(app, uri, durMs, m)
        if (final.isFile && final.length() > 1000) return final
        val nw0 = Runtime.getRuntime().availableProcessors().let { (it - 1).coerceIn(2, 4) }
        val first = newDenoiser(mf.path, m.family, 1)            // بيتأكد من الموديل والواجهة بدري
        val rate = rateOf(first, m)
        val pcm = File(app.cacheDir, "vc_" + System.nanoTime() + ".pcm")
        val part = File(final.path + ".part")
        val dens = ArrayList<Any>(); dens.add(first)
        try {
            decodeMono(app, uri, rate, pcm, { p -> onPct(p * 25 / 100) }, { cancel })?.let { throw IOException(it) }
            val n = pcm.length() / 2
            if (n < rate) throw IOException("الصوت قصير أوي")
            val chunk = 20L * rate; val ctxN = rate.toLong()
            val total = ((n + chunk - 1) / chunk).toInt()
            val nw = Math.min(nw0, total)
            for (i in 1 until nw) dens.add(newDenoiser(mf.path, m.family, 1))
            val raf = RandomAccessFile(part, "rw")
            raf.setLength(44 + n * 2); raf.seek(0); raf.write(wavHeader(rate, n * 2))
            val lock = Any(); val next = AtomicInteger(0); val doneN = AtomicInteger(0)
            val fail = AtomicReference<String?>(null)
            val workers = (0 until nw).map { w ->
                Thread {
                    val rin = RandomAccessFile(pcm, "r")
                    try {
                        while (fail.get() == null && !cancel) {
                            val i = next.getAndIncrement(); if (i >= total) break
                            val s = i * chunk; val e = Math.min(n, s + chunk)
                            val a = Math.max(0L, s - ctxN); val b = Math.min(n, e + ctxN)
                            val inp = readFloats(rin, a, (b - a).toInt())
                            val res = denoise(dens[w], inp, rate)
                            val off = (s - a).toInt(); val len = (e - s).toInt()
                            val bytes = ByteArray(len * 2)
                            for (k in 0 until len) {
                                val v = if (off + k < res.size) res[off + k] else 0f
                                val x = (v.coerceIn(-1f, 1f) * 32767f).toInt()
                                bytes[2 * k] = (x and 255).toByte(); bytes[2 * k + 1] = ((x shr 8) and 255).toByte()
                            }
                            synchronized(lock) { raf.seek(44 + s * 2); raf.write(bytes) }
                            onPct(25 + doneN.incrementAndGet() * 75 / total)
                        }
                    } catch (t: Throwable) { fail.compareAndSet(null, (t.message ?: t.javaClass.simpleName).take(120)); LogStore.err("voiceclean:w$w", t) }
                    finally { try { rin.close() } catch (_: Throwable) {} }
                }.also { it.start() }
            }
            workers.forEach { it.join() }
            try { raf.close() } catch (_: Throwable) {}
            if (cancel) throw IOException("اتلغى")
            fail.get()?.let { throw IOException(it) }
            if (final.exists()) final.delete()
            if (!part.renameTo(final)) throw IOException("مقدرتش أحفظ الناتج")
            prune(app)
            onPct(100)
            return final
        } catch (t: Throwable) {
            try { part.delete() } catch (_: Throwable) {}
            throw t
        } finally {
            dens.forEach { release(it) }
            try { pcm.delete() } catch (_: Throwable) {}
        }
    }

    private fun readFloats(r: RandomAccessFile, startSample: Long, count: Int): FloatArray {
        val bytes = ByteArray(count * 2)
        r.seek(startSample * 2); r.readFully(bytes)
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = sb.get(i) / 32768f
        return out
    }

    /** فك صوت الفيديو لـ PCM مونو int16 على [outRate] (بيستخدم نفس الـ resampler بتاع الفصل القديم) */
    private fun decodeMono(ctx: Context, uri: Uri, outRate: Int, dst: File, onPct: (Int) -> Unit, cancelled: () -> Boolean): String? {
        var ex: MediaExtractor? = null; var codec: MediaCodec? = null
        var os: BufferedOutputStream? = null
        try {
            ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
            var ti = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) { val f = ex.getTrackFormat(i); if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ti = i; fmt = f; break } }
            if (ti < 0 || fmt == null) return "ملقيتش مسار صوت في الفيديو"
            ex.selectTrack(ti)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var enc = 2
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val bos = BufferedOutputStream(FileOutputStream(dst), 1 shl 16); os = bos
            var rs: StemResampler? = null
            var mono = FloatArray(8192)
            var bytes = ByteArray(1 shl 16)
            var started = false
            val wr: (FloatArray, FloatArray, Int) -> Unit = { a, _, n ->
                if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val x = (a[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    bytes[2 * i] = (x and 255).toByte(); bytes[2 * i + 1] = ((x shr 8) and 255).toByte()
                }
                bos.write(bytes, 0, n * 2)
            }
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            while (!outDone) {
                if (cancelled()) return "اتلغى"
                if (!inDone) {
                    val ib = codec.dequeueInputBuffer(10000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10000)
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (!started) {
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    enc = if (of.containsKey("pcm-encoding")) of.getInteger("pcm-encoding") else 2
                } else if (ob >= 0) {
                    val b = codec.getOutputBuffer(ob)
                    if (b != null && info.size > 0) {
                        if (!started) { started = true; if (sr != outRate) rs = StemResampler(sr, outRate) }
                        b.position(info.offset); b.limit(info.offset + info.size); b.order(ByteOrder.LITTLE_ENDIAN)
                        val bps = if (enc == 4) 4 else 2
                        val cc = Math.max(ch, 1)
                        val frames = info.size / (bps * cc)
                        if (mono.size < frames) mono = FloatArray(frames)
                        for (i in 0 until frames) {
                            var sum = 0f
                            for (k in 0 until cc) sum += if (enc == 4) b.float else b.short / 32768f
                            mono[i] = sum / cc
                        }
                        val rr = rs
                        if (rr == null) wr(mono, mono, frames) else rr.push(mono, mono, frames, wr)
                        if (durUs > 0) onPct(((info.presentationTimeUs * 100L) / durUs).toInt().coerceIn(0, 99))
                    }
                    codec.releaseOutputBuffer(ob, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            rs?.finish(wr)
            bos.flush()
            return null
        } catch (e: Throwable) {
            LogStore.err("voiceclean-decode", e)
            return "فشل فك صوت الفيديو: " + (e.message ?: e.javaClass.simpleName).take(80)
        } finally {
            try { os?.close() } catch (_: Throwable) {}
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ex?.release() } catch (_: Throwable) {}
        }
    }

    // ===== التشغيل: مشغّل صوت تاني متزامن مع الفيديو، وصوت الفيديو الأصلي بيتقفل لحد ما توقف =====
    class Playback(private val ctx: Context, private val main: ExoPlayer, private val wav: File) {
        private val h = Handler(Looper.getMainLooper())
        private var aux: ExoPlayer? = null
        @Volatile var active = false
            private set

        private val tick = object : Runnable {
            override fun run() {
                if (!active) return
                try { sync() } catch (t: Throwable) { LogStore.err("voiceclean-sync", t) }
                h.postDelayed(this, 250)
            }
        }

        fun start() {
            if (active) return
            val a = ExoPlayer.Builder(ctx).build()
            a.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav)))
            a.prepare(); a.volume = 1f
            aux = a
            main.trackSelectionParameters = main.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            active = true
            a.seekTo(main.currentPosition)
            h.post(tick)
        }

        private fun sync() {
            val a = aux ?: return
            if (a.playbackParameters != main.playbackParameters) a.playbackParameters = main.playbackParameters
            val want = main.playWhenReady && main.playbackState == M3.STATE_READY
            if (a.playWhenReady != want) a.playWhenReady = want
            val drift = main.currentPosition - a.currentPosition
            if (Math.abs(drift) > 250 || (main.playbackState == M3.STATE_BUFFERING && Math.abs(drift) > 80)) a.seekTo(main.currentPosition)
        }

        fun stop() {
            if (!active && aux == null) return
            active = false
            h.removeCallbacks(tick)
            try { aux?.release() } catch (_: Throwable) {}
            aux = null
            try { main.trackSelectionParameters = main.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build() } catch (_: Throwable) {}
        }
    }
}
