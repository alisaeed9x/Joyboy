package com.tttt.subtitler

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * (v307) فصل الصوت عن الموسيقى بالذكاء الصناعي (MDX-Net من UVR على ONNX Runtime) — الجزء اللي بيربط StemCore.kt بالتطبيق:
 * كتالوج الموديلات + التنزيل (بيكمّل من حيث وقف) + فك الأغنية لـ PCM ستيريو 44.1kHz + تشغيل الفاصل + كتابة ملفين WAV
 * (Karaoke.file(…,1) = الصوت · Karaoke.file(…,2) = الموسيقى) فالمشغّل (MusicEngine.stem) بيشغّلهم زي ما كان.
 * موسيقى = الأغنية الأصلية − الصوت، يعني صوت + موسيقى = الأغنية بالظبط.
 */
object StemAi {
    /** (v331) فصل الصوت عن الموسيقى متوقّف مؤقتًا (النتيجة مش كويسة). غيّرها لـ false عشان يرجع (الكود كله موجود زي ما هو) */
    const val DISABLED = true
    class Mdl(val file: String, val label: String, val mb: Int, val spec: MdxSpec)
    class Eng(val id: String, val label: String, val note: String, val models: List<Mdl>, val overlap: Double)

    private const val BASE = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/"
    val VOC_FT = Mdl("UVR-MDX-NET-Voc_FT.onnx", "UVR-MDX-NET Voc FT", 67, MdxSpec(7680, 3072, 256, 1.021f, true))
    val KIM2 = Mdl("Kim_Vocal_2.onnx", "Kim Vocal 2", 67, MdxSpec(7680, 3072, 256, 1.009f, true))
    val engines = listOf(
        Eng("fast", "⚡ سريع", "موديل واحد (Voc FT) — نضيف وخفيف — الافتراضي", listOf(VOC_FT), 0.1),
        Eng("best", "🥇 الأقوى", "موديلين مع بعض (Voc FT + Kim Vocal 2) وتداخل أكبر — أنضف صوت وأقل تسريب، وأبطأ حوالي ٢× وأتقل على الرام", listOf(VOC_FT, KIM2), 0.25)
    )
    fun engine(): Eng = engines.firstOrNull { it.id == Cfg.str("stem_eng", "fast") } ?: engines[0]
    fun setEngine(id: String) { try { Cfg.put("stem_eng", id) } catch (_: Throwable) {} }

    // ===== مكان الموديلات (نفس المكان الدائم بتاع Whisper: Android/media/<الحزمة>/models/stem) =====
    private fun minBytes(m: Mdl) = m.mb * 600_000L
    fun modelFile(ctx: Context, m: Mdl): File = Offline.pick("stem/" + m.file, minBytes(m)) ?: File(File(ctx.filesDir, "models"), "stem/" + m.file)
    fun has(ctx: Context, m: Mdl): Boolean = modelFile(ctx, m).let { it.isFile && it.length() > minBytes(m) }
    fun ready(ctx: Context, e: Eng = engine()): Boolean = e.models.all { has(ctx, it) }
    fun needMb(ctx: Context, e: Eng = engine()): Int = e.models.filter { !has(ctx, it) }.sumOf { it.mb }
    /** هل المكتبة النيتيف (substem + onnxruntime) اتحمّلت — false = الجهاز مش arm64 أو البناء من غيرها */
    fun libOk(): Boolean = StemOrt.ok()
    fun libErr(): String = StemOrt.loadErr

    fun deleteModels(ctx: Context) {
        for (m in listOf(VOC_FT, KIM2)) for (r in Offline.roots().ifEmpty { listOf(File(ctx.filesDir, "models")) }) File(File(r, "stem"), m.file).let { it.delete(); File(it.path + ".part").delete() }
        File(File(ctx.filesDir, "models"), "stem").deleteRecursively()
    }
    fun deleteStems(ctx: Context) { try { File(File(ctx.filesDir, "stems"), "work").deleteRecursively() } catch (_: Throwable) {}; try { File(ctx.filesDir, "stems").listFiles { f -> f.name.endsWith(".wav") || f.name.endsWith(".tmp") }?.forEach { it.delete() } } catch (_: Throwable) {} }

    // ===== تنزيل الموديلات =====
    @Volatile private var dlThread: Thread? = null
    @Volatile private var dlCancel = false
    fun downloading(): Boolean = dlThread?.isAlive == true
    fun cancelDownload() { dlCancel = true }

    private fun fetch(url: String, dst: File, minB: Long, progress: (Long) -> Unit) {
        dst.parentFile?.mkdirs()
        val part = File(dst.path + ".part")
        var have = if (part.isFile) part.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000; c.readTimeout = 30000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code == 416) { part.delete(); throw IOException("اتعاد من الأول — جرّب تاني") }
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            if (code == 200 && have > 0) { have = 0L; part.delete() }
            FileOutputStream(part, have > 0).use { os ->
                c.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024); var last = 0L
                    while (true) {
                        if (dlCancel) throw IOException("اتلغى")
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); have += n
                        val now = System.currentTimeMillis()
                        if (now - last > 400) { last = now; progress(have) }
                    }
                }
            }
            progress(have)
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
        if (have < minB) throw IOException("التنزيل ناقص — جرّب تاني")
        if (dst.exists()) dst.delete()
        if (!part.renameTo(dst)) throw IOException("مقدرتش أحفظ الملف")
    }

    /** بينزّل الموديلات الناقصة للمحرك [e]. progress(نزّل، الكلّي) بالبايت (تقريبي) من خيط التنزيل · done(null = نجح، غير كده السبب) */
    fun download(ctx: Context, e: Eng, progress: (Long, Long) -> Unit, done: (String?) -> Unit) {
        if (downloading()) return
        val app = ctx.applicationContext
        dlCancel = false
        dlThread = Thread {
            var err: String? = null
            try {
                val todo = e.models.filter { !has(app, it) }
                val total = todo.sumOf { it.mb * 1_000_000L }
                var before = 0L
                for (m in todo) {
                    val b0 = before
                    fetch(BASE + m.file, modelFile(app, m), minBytes(m)) { h -> progress(b0 + h, total) }
                    before += m.mb * 1_000_000L
                }
            } catch (t: Throwable) { err = (t.message ?: t.javaClass.simpleName).take(120) }
            try { done(err) } catch (_: Throwable) {}
        }.also { it.isDaemon = true; it.start() }
    }

    // ===== فك الأغنية لـ PCM ستيريو int16 على 44.1kHz =====
    private fun decodePcm(ctx: Context, t: Track, dst: File, onPct: (Int) -> Unit, cancelled: () -> Boolean): String? {
        var ex: MediaExtractor? = null; var codec: MediaCodec? = null
        var os: BufferedOutputStream? = null
        try {
            ex = MediaExtractor(); ex.setDataSource(ctx, Uri.parse(t.uri), null)
            var ti = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) { val f = ex.getTrackFormat(i); if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ti = i; fmt = f; break } }
            if (ti < 0 || fmt == null) return "ملقيتش مسار صوت في الملف"
            ex.selectTrack(ti)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var enc = 2
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else t.durMs * 1000L
            val bos = BufferedOutputStream(FileOutputStream(dst), 1 shl 16); os = bos
            var rs: StemResampler? = null
            var l = FloatArray(8192); var r = FloatArray(8192)
            var bytes = ByteArray(1 shl 16)
            var started = false
            val wr: (FloatArray, FloatArray, Int) -> Unit = { a, b, n ->
                if (bytes.size < n * 4) bytes = ByteArray(n * 4)
                var p = 0
                for (i in 0 until n) {
                    val x = (a[i].coerceIn(-1f, 1f) * 32767f).toInt(); val y = (b[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    bytes[p] = (x and 255).toByte(); bytes[p + 1] = ((x shr 8) and 255).toByte()
                    bytes[p + 2] = (y and 255).toByte(); bytes[p + 3] = ((y shr 8) and 255).toByte()
                    p += 4
                }
                bos.write(bytes, 0, n * 4)
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
                        if (!started) { started = true; if (sr != 44100) rs = StemResampler(sr, 44100) }
                        b.position(info.offset); b.limit(info.offset + info.size); b.order(ByteOrder.LITTLE_ENDIAN)
                        val bps = if (enc == 4) 4 else 2
                        val frames = info.size / (bps * Math.max(ch, 1))
                        if (l.size < frames) { l = FloatArray(frames); r = FloatArray(frames) }
                        for (i in 0 until frames) {
                            val x: Float; val y: Float
                            if (enc == 4) { x = b.float; y = if (ch >= 2) b.float else x; for (k in 2 until ch) b.float }
                            else { x = b.short / 32768f; y = if (ch >= 2) b.short / 32768f else x; for (k in 2 until ch) b.short }
                            l[i] = x; r[i] = y
                        }
                        val rr = rs
                        if (rr == null) wr(l, r, frames) else rr.push(l, r, frames, wr)
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
            LogStore.err("stem-decode", e)
            return "فشل فك الأغنية: " + (e.message ?: e.javaClass.simpleName).take(80)
        } finally {
            try { os?.close() } catch (_: Throwable) {}
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ex?.release() } catch (_: Throwable) {}
        }
    }

    // ===== التشغيل =====
    private val running = ConcurrentHashMap<String, Boolean>()
    private val lock = ReentrantLock(true)          // فصل واحد في المرة (الرام والمعالج)
    private fun key(t: Track) = "t${t.id}_${t.durMs}"
    fun isRunning(t: Track) = running.containsKey(key(t))

    /** (v319) بيحتفظ بالأغاني المفصولة لحد ~٢ جيجا (الأحدث الأول) بدل ٥ أغاني بس، ويمسح شغل ناقص عدّى عليه أكتر من ٧ أيام */
    private fun prune(ctx: Context, capBytes: Long = 2_000_000_000L) {
        try {
            val l = File(ctx.filesDir, "stems").listFiles { f -> f.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() } ?: return
            var sum = 0L
            for ((i, f) in l.withIndex()) { sum += f.length(); if (i >= 2 && sum > capBytes) f.delete() }
        } catch (_: Throwable) {}
        try {
            val old = System.currentTimeMillis() - 7L * 86_400_000L
            File(File(ctx.filesDir, "stems"), "work").listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
            File(ctx.filesDir, "stems").listFiles { f -> f.name.endsWith(".tmp") && f.lastModified() < old }?.forEach { it.delete() }
        } catch (_: Throwable) {}
    }

    // ===== (v319) نقطة الاستكمال: بتتحفظ كل ~٦ قطع، فلو غيّرت الأغنية أو قفلت التطبيق يكمّل من مكانه =====
    private const val CK_MAGIC = 0x53544D31
    private fun workDir(ctx: Context) = File(File(ctx.filesDir, "stems"), "work").apply { mkdirs() }
    private fun saveCk(f: File, e: Eng, frames: Long, i: Long, done: Int, vb: Long, mb: Long, aL: FloatArray, aR: FloatArray, dv: FloatArray) {
        val tmp = File(f.path + ".new")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { o ->
            o.writeInt(CK_MAGIC); o.writeUTF(e.id); o.writeDouble(e.overlap); o.writeLong(frames)
            o.writeLong(i); o.writeInt(done); o.writeLong(vb); o.writeLong(mb); o.writeInt(aL.size)
            val bb = ByteBuffer.allocate(aL.size * 4 * 3)
            val fb = bb.asFloatBuffer(); fb.put(aL); fb.put(aR); fb.put(dv)
            o.write(bb.array())
        }
        f.delete(); tmp.renameTo(f)
    }
    private class Ck(val st: StemState, val vb: Long, val mb: Long)
    private fun loadCk(f: File, e: Eng, frames: Long, chunk: Int): Ck? {
        if (!f.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { d ->
                if (d.readInt() != CK_MAGIC) return null
                if (d.readUTF() != e.id || d.readDouble() != e.overlap || d.readLong() != frames) return null
                val i = d.readLong(); val done = d.readInt(); val vb = d.readLong(); val mb = d.readLong(); val n = d.readInt()
                if (n != chunk || i <= 0L) return null
                val raw = ByteArray(n * 4 * 3); d.readFully(raw)
                val fb = ByteBuffer.wrap(raw).asFloatBuffer()
                val aL = FloatArray(n); val aR = FloatArray(n); val dv = FloatArray(n)
                fb.get(aL); fb.get(aR); fb.get(dv)
                Ck(StemState(i, done, aL, aR, dv), vb, mb)
            }
        } catch (_: Throwable) { null }
    }

    private fun threads(): Int = Runtime.getRuntime().availableProcessors().let { (it / 2).coerceIn(2, 3) }

    /**
     * بيفصل [t] لملفين WAV (صوت/موسيقى) في Karaoke.file. بيتنادى من خيط خلفية. null = تمام، غير كده رسالة الخطأ.
     * [onPct] 0..99 · [cancelled] بيتسأل كل قطعة (~٦ ثواني صوت).
     */
    fun separate(ctx: Context, t: Track, onPct: (Int) -> Unit, cancelled: () -> Boolean): String? {
        val app = ctx.applicationContext
        val k = key(t)
        if (Karaoke.hasStem(app, t, 1) && Karaoke.hasStem(app, t, 2)) return null       // (v319) مفصولة قبل كده — مفيش شغل
        if (running.putIfAbsent(k, true) != null) return "الأغنية دي بتتفصل دلوقتي"
        val e = engine()
        val wd = workDir(app)
        val pcm = File(wd, "stem_$k.pcm"); val pcmPart = File(wd, "stem_$k.pcm.part"); val stf = File(wd, "stem_$k.st")
        val runners = ArrayList<OrtRunner>()
        var src: StemPcmFile? = null
        var vw: StemWav? = null; var mw: StemWav? = null
        var ok = false
        try {
            if (!StemOrt.ok()) return "مكتبة الفصل مش موجودة على الجهاز ده (محتاج موبايل arm64): " + StemOrt.loadErr
            if (!ready(app, e)) return "موديلات الفصل مش نازلة — نزّلها الأول"
            if (t.durMs > 20 * 60_000L) return "الملف طويل (أكتر من ٢٠ دقيقة) — الفصل للأغاني"
            val need = (Math.max(t.durMs, 1000L) / 1000L) * 44100L * 4L * 3L       // PCM مؤقت + ملفين WAV
            if (app.filesDir.usableSpace < need + 50_000_000L) return "مساحة التخزين مش كفاية للفصل (محتاج حوالي " + (need / 1_000_000L) + " ميجا)"
            lock.lock()
            try {
                if (cancelled()) return "اتلغى"
                onPct(0)
                if (!(pcm.isFile && pcm.length() > 0L)) {                              // (v319) الفك اتعمل قبل كده؟ نكمّل عليه
                    pcmPart.delete()
                    val err = decodePcm(app, t, pcmPart, { p -> onPct(p * 5 / 100) }, cancelled)
                    if (err != null) { pcmPart.delete(); return err }
                    pcm.delete(); if (!pcmPart.renameTo(pcm)) return "مقدرتش أحفظ الصوت المفكوك"
                }
                val s = StemPcmFile(pcm); src = s
                if (s.frames < 2048L) { pcm.delete(); return "الملف قصير أوي" }
                val dimT = e.models[0].spec.dimT
                val chunk = StemSeparator.chunkLen(dimT)
                val ck = loadCk(stf, e, s.frames, chunk)
                val pairs = ArrayList<Pair<MdxSpec, StemRunner>>()
                for (m in e.models) {
                    val rn = OrtRunner(modelFile(app, m).path, threads(), m.spec.dimF, m.spec.dimT)
                    if (rn.error != null) { rn.close(); return rn.error }
                    runners.add(rn); pairs.add(Pair(m.spec, rn))
                }
                val vt = File(Karaoke.file(app, t, 1).path + ".tmp"); val mt = File(Karaoke.file(app, t, 2).path + ".tmp")
                val useCk = ck != null && vt.length() >= 44L + ck.vb && mt.length() >= 44L + ck.mb       // الملفين الجزئيين لسه موجودين وبحجم كافي
                if (!useCk) stf.delete()
                val v = StemWav(Karaoke.file(app, t, 1), 44100, false, if (useCk) ck!!.vb else -1L); vw = v
                val m2 = StemWav(Karaoke.file(app, t, 2), 44100, false, if (useCk) ck!!.mb else -1L); mw = m2
                val start = if (useCk) ck!!.st else null
                val save = { i: Long, done: Int, aL: FloatArray, aR: FloatArray, dv: FloatArray ->
                    try { v.flush(); m2.flush(); saveCk(stf, e, s.frames, i, done, v.written, m2.written, aL, aR, dv) } catch (_: Throwable) {}
                }
                val res = StemSeparator.run(s, pairs, e.overlap, v, m2, { p -> onPct(5 + p * 94 / 100) }, cancelled, start, 6, save)
                if (res != null) return res
                ok = true
                return null
            } finally { lock.unlock() }
        } catch (ex: Throwable) {
            LogStore.err("stem-ai", ex)
            return "فشل الفصل: " + (ex.message ?: ex.javaClass.simpleName).take(80)
        } finally {
            for (r in runners) { try { r.close() } catch (_: Throwable) {} }
            try { src?.close() } catch (_: Throwable) {}
            if (ok) {
                pcm.delete(); stf.delete(); pcmPart.delete()
                val a = vw?.finish(true) ?: false; val b = mw?.finish(true) ?: false
                if (!a || !b) { Karaoke.file(app, t, 1).delete(); Karaoke.file(app, t, 2).delete() }
                prune(app)
            } else {
                // (v319) اتلغى/وقف: نسيب الـPCM والـ.tmp ونقطة الاستكمال عشان يكمّل بعدين. من غير نقطة استكمال ولا حاجة تتحفظ
                vw?.pause(); mw?.pause()
                if (!stf.isFile) { vw?.let { File(it.file.path + ".tmp").delete() }; mw?.let { File(it.file.path + ".tmp").delete() }; pcm.delete() }
            }
            running.remove(k)
        }
    }
}
