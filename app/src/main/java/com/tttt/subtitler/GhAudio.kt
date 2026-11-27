package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * (v1361 — بطلب علي) باتش الصوت للترجمة برّه التطبيق: بقى محلي على الموبايل بدل الرفع على الريبو.
 *  زرار «🎧 ترجمة عن طريق تحميل باتش ملف الصوت» في مركز التحميل: بتختار فيديو ← مهمة بشريط تقدّم والوقت الفاضل
 *  بتفصل الصوت في ملف واحد، وبعدها بيتكتب ملف TXT فيه برومبت الترجمة (بيتبنى من إعدادات الترجمة الحالية — زرار
 *  «⚙ إعدادات الترجمة» جنبه بيفتح نفس الإعدادات اللي بتأثر في البرومبت)، والاتنين بيتحفظوا في Download/Joyboy
 *  ويتشاركوا لأي تطبيق (كلود/شات جي بي تي…).
 *  (كان v1202: رفع patches/audio/ على الريبو بتوكن — اتشال خالص: مفيش رفع أو تنزيل من الريبو، ولا توكن في البرومبت)
 */
object GhAudio {

    /** نقطة الدخول من مركز التحميل: اختيار فيديو من مكتبة الموبايل */
    fun pickAndStart(act: Activity) {
        try {
            VideoPicker.show(act, "🎧 اختار الفيديو اللي هنفصل صوته", { v ->
                start(act, Uri.parse(v.uri), null, emptyMap(), v.title, v.durMs)
            }, { Notice.show(act, "الفيديوهات بتظهر هنا من مكتبة الموبايل", 2400L) })
        } catch (e: Throwable) { LogStore.err("GhAudio:pick", e); Notice.show(act, "⚠ ما قدرتش أفتح اختيار الفيديو", 2400L) }
    }

    /** يبدأ مهمة فصل الصوت في مركز التحميل (شريط تقدّم + الوقت الفاضل) */
    fun start(act: Activity, uri: Uri?, url: String?, hdr: Map<String, String>, title: String, durMs: Long) {
        if (TaskCenter.items.any { it.kind == "ghup" && (it.state == 0 || it.state == 1) }) { Notice.show(act, "⏳ في باتش صوت لسه بيتجهّز — تابعه من «المهام»", 2200L); return }
        LogStore.add("🎧 باتش الصوت: بدأ — " + title)
        Notice.show(act, "🎧 اتضاف لمركز التحميل — فصل الصوت بيظهر بشريط تقدّم", 2600L)
        TaskCenter.add(act, "ghup", "🎧 باتش الصوت: " + BatchExport.safe(title)) { task -> work(act, uri, url, hdr, title, durMs, task) }
    }

    private fun fmtEta(sec: Long): String = if (sec < 90L) sec.toString() + " ثانية" else ((sec + 30L) / 60L).toString() + " دقيقة"

    private fun work(act: Activity, uri: Uri?, url: String?, hdr: Map<String, String>, title: String, durMs: Long, task: TaskItem) {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
        val ascii = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(30).ifBlank { "video" }
        val id = stamp + "_" + ascii
        val ptxt = File(act.cacheDir, "ghup_" + id + ".prompt.txt")
        try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            var lastP = -1
            val audio = FullAudio.extract(act, uri, url, hdr, title) { p ->
                if (task.cancelled) throw TaskCancelled()
                if (p != lastP) {
                    lastP = p
                    task.pct = (p * 80 / 100).coerceIn(0, 80)
                    val el = (android.os.SystemClock.elapsedRealtime() - t0) / 1000.0
                    val eta = if (p >= 3) (el * (100.0 - p) / p).toLong() else -1L
                    task.msg = "🎧 بفصل الصوت… " + p + "%" + (if (eta >= 0L) " — فاضل ~" + fmtEta(eta) else "")
                    TaskCenter.changed()
                }
            }
            task.pct = 85; task.msg = "📝 بكتب ملف البرومبت…"; TaskCenter.changed()
            val prompt = promptFor(act, title, audio.name, audio.length(), durMs)
            ptxt.writeText(prompt, Charsets.UTF_8)
            Cfg.put("gh_last_prompt", prompt)
            task.pct = 92; task.msg = "💾 بحفظ في التنزيلات…"; TaskCenter.changed()
            val aName = "joyboy_audio_" + id + "." + audio.extension.ifBlank { "m4a" }
            val pName = "joyboy_audio_" + id + ".prompt.txt"
            val whereA = GhCi.saveToDownloads(act, audio, aName, "audio/*")
            val whereP = GhCi.saveToDownloads(act, ptxt, pName, "text/plain")
            task.outPath = whereA
            task.msg = "✅ باتش الصوت جاهز — محفوظ في التنزيلات"
            LogStore.add("🎧 باتش الصوت: خلص ← " + whereA + " + " + whereP)
            act.runOnUiThread {
                copy(act, prompt)
                readyDialog(act, whereA, whereP, audio, ptxt)
            }
        } catch (e: TaskCancelled) {
            throw e
        } catch (e: Throwable) {
            if (e !is IOException) LogStore.err("GhAudio", e)
            LogStore.add("🎧 باتش الصوت: فشل — " + (e.message ?: e.javaClass.simpleName))
            throw e
        }
    }

    /** برومبت الترجمة اللي بيتبعت للـAI مع ملف الصوت — بيتبنى من إعدادات الترجمة الحالية، من غير ريبو ولا توكن */
    private fun promptFor(act: Activity, title: String, audioName: String, bytes: Long, durMs: Long): String {
        val sb = StringBuilder()
        sb.append("ترجملي صوت الفيديو ده وابعتلي ملف ترجمة SRT بالتوقيتات.\n")
        sb.append("الفيديو: ").append(title).append("\n")
        if (durMs > 0L) sb.append("مدة الفيديو: ").append(BatchExport.hms(durMs / 1000.0)).append("\n")
        sb.append("ملف الصوت المرفق: ").append(audioName).append(" (").append(bytes / 1048576L).append("MB) — ده صوت الفيديو كله من أوله لآخره.\n\n")
        sb.append("اسمع الملف، ترجمه حسب التعليمات تحت، وارجعلي ملف SRT واحد توقيته على توقيت الفيديو من الصفر.\n\n")
        sb.append("===== تعليمات الترجمة =====\n").append(promptInstructions(act, durMs))
        return sb.toString()
    }

    /** «تعليمات الترجمة» نفسها اللي المحرك بيولّدها من الإعدادات الحالية (لهجة/أسلوب/شدة/برومبتات) — من غير ما نشغّل صوت */
    private fun promptInstructions(act: Activity, durMs: Long): String {
        return try {
            Cfg.init(act)
            val conf = Cfg.snapshot()
            val store = Store(File(act.filesDir, "progress"), Store.keyFor("audio-patch"))
            val pb = PromptBuilder { p -> act.assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() } }
            val durSec = if (durMs > 0L) durMs / 1000.0 else 0.0
            val host = object : Host {
                override fun log(s: String) {}
                override fun status(s: String) {}
                override fun changed() {}
                override fun position() = 0.0
                override fun playerDuration() = durSec
            }
            val eng = Engine(conf, { throw IOException("prompt-only") }, store, host, pb)
            eng.headless = true
            eng.promptText().ifBlank { "(ترجم لعربي طبيعي ومناسب للمشاهدة)" }
        } catch (e: Throwable) { LogStore.err("GhAudio:prompt", e); "(ترجم لعربي طبيعي ومناسب للمشاهدة)" }
    }

    private fun copy(ctx: Context, text: String) {
        try { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("audio-patch-prompt", text)) } catch (_: Throwable) {}
    }

    /** نسخ آخر برومبت اتعمل */
    fun copyLast(act: Activity) {
        val p = Cfg.str("gh_last_prompt", "")
        if (p.isBlank()) { Notice.show(act, "مفيش برومبت لسه — جهّز باتش الصوت الأول من مركز التحميل", 2400L); return }
        copy(act, p); Notice.show(act, "📋 اتنسخ البرومبت", 1800L)
    }

    private fun readyDialog(act: Activity, whereA: String, whereP: String, audio: File, ptxt: File) {
        try {
            val msg = "الصوت اتفصل والبرومبت اتكتب، والاتنين محفوظين:\n\n🎧 " + whereA + "\n📝 " + whereP + "\n\nالبرومبت اتنسخ تلقائيًا — الصقه في كلود/شات جي بي تي وابعت معاه ملف الصوت."
            GAlert(act).setTitle("🎧 باتش الصوت جاهز").setMessage(msg)
                .setPositiveButton("📤 مشاركة الاتنين") { _, _ -> shareBoth(act, audio, ptxt) }
                .setNegativeButton("تمام", null).show()
        } catch (_: Throwable) {}
    }

    /** مشاركة ملف الصوت + ملف البرومبت مع بعض لأي تطبيق */
    private fun shareBoth(act: Activity, audio: File, ptxt: File) {
        try {
            val au = androidx.core.content.FileProvider.getUriForFile(act, act.packageName + ".fmprov", audio)
            val pu = androidx.core.content.FileProvider.getUriForFile(act, act.packageName + ".fmprov", ptxt)
            val i = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, arrayListOf(au, pu))
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(android.content.Intent.createChooser(i, "📤 باتش الصوت + البرومبت"))
        } catch (e: Throwable) { LogStore.err("GhAudio:share", e); Notice.show(act, "⚠ المشاركة فشلت — الملفات محفوظة في التنزيلات", 2600L) }
    }
}

/** (v1238) صوت الفيديو كله كملف واحد */
object FullAudio {
    private const val OUT_RATE = 48000
    private const val WIN_SEC = 30.0
    private const val OPUS_BR = 24000
    private const val AAC_BR = 32000

    /** (v1363 — بطلب علي: الفصل كان بطيء جدًا) الافتراضي بقى السرعة: فك + ترميز AAC 32k مونو 16kHz بكوديك الجهاز
     *  (هاردوير — أسرع من الوقت الحقيقي بمرات) في ملف m4a صغير (~4 مرات أصغر من الأصل). تفعيل «✨ تحسين الصوت»
     *  من إعدادات الترجمة يرمّز Opus 24k (أدق للترجمة وأصغر، لكن أبطأ — كوديك سوفتوير). لو أي ترميز فشل:
     *  رجوع تلقائي للنسخ الخام القديم (remux) — الميزة مستحيل تبوظ.
     *  (v1362 — بطلب علي: «الصوت طالع m4a وحجمه كبير») كان أول ترميز Opus. */
    fun extract(ctx: Context, uri: Uri?, url: String?, hdr: Map<String, String>, title: String, onPct: (Int) -> Unit = {}): File {
        val dir = File(ctx.cacheDir, "share_audio").also { it.mkdirs() }
        dir.listFiles()?.forEach { try { it.delete() } catch (_: Throwable) {} }
        val safe = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(30).ifBlank { "audio" }
        if (Cfg.bool("gh_audio_enhance", false)) {
            try {
                val f = File(dir, safe + "-audio.opus")
                transcode(ctx, uri, url, hdr, f, true, onPct)
                if (f.length() >= 1024L && headIs(f)) return f
                throw IOException("ملف Opus طلع غير سليم")
            } catch (e: Throwable) { if (e is TaskCancelled) throw e; LogStore.err("FullAudio:opus", e) }
        }
        try {
            val f = File(dir, safe + "-audio.m4a")
            transcode(ctx, uri, url, hdr, f, false, onPct)
            if (f.length() >= 1024L) return f
            throw IOException("ملف AAC طلع فاضي")
        } catch (e: Throwable) { if (e is TaskCancelled) throw e; LogStore.err("FullAudio:aac", e) }
        return remux(ctx, uri, url, hdr, title, onPct)
    }

    private fun headIs(f: File): Boolean = try {
        val b = ByteArray(4); java.io.FileInputStream(f).use { it.read(b) }
        b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() && b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()
    } catch (_: Throwable) { false }

    /** فك الصوت كله لـ PCM مونو 48kHz على شبابيك (ذاكرة ثابتة ~12MB مهما طال الفيديو) + ترميزه. */
    private fun transcode(ctx: Context, uri: Uri?, url: String?, hdr: Map<String, String>, out: File, opus: Boolean, onPct: (Int) -> Unit) {
        val rate = if (opus) OUT_RATE else 16000
        val ex = MediaExtractor()
        var writer: PcmOut? = null
        try {
            if (uri != null && !uri.toString().startsWith("http")) ex.setDataSource(ctx, uri, null)
            else ex.setDataSource(url ?: uri.toString(), hdr)
            val ti = Tracks.pick(ex, 0) {}
            val fmt = ex.getTrackFormat(ti)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durUs <= 0L) throw IOException("مدة الصوت غير معروفة")
            ex.selectTrack(ti)
            val w = if (opus) OggOpus(out) else AacM4a(out, rate)
            writer = w
            val winUs = (WIN_SEC * 1_000_000).toLong()
            var start = 0L; var lastP = -1
            while (start < durUs) {
                val end = minOf(start + winUs, durUs)
                ex.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val sink = PcmSink(WIN_SEC + 2.0, rate)
                Decoder.decode(ex, fmt, start, end, sink)
                val wav = sink.finish(start / 1_000_000.0).bytes   // WAV 48k مونو (44 بايت هيدر + PCM)
                w.offer(wav, 44, wav.size - 44, end >= durUs)
                val p = (end * 100 / durUs).toInt().coerceIn(0, 100)
                if (p != lastP) { lastP = p; onPct(p) }
                start = end
            }
        } finally {
            try { writer?.close() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    /** بفر ترميز: ياخد PCM16 مونو 48k على دفعات، وlast=true مع آخر دفعة. */
    private interface PcmOut { fun offer(pcm: ByteArray, off: Int, len: Int, last: Boolean); fun close() }

    /** حلقة ترميز MediaCodec مشتركة: تغذية PCM + سحب الحزم. onFmt تتنده عند أول تغيير صيغة، onPkt لكل حزمة (pts µs, flags). */
    private fun pump(enc: MediaCodec, pcm: ByteArray, off0: Int, len0: Int, last: Boolean, onFmt: (MediaFormat) -> Unit, onPkt: (ByteArray, Long, Int) -> Unit) {
        var off = off0; var rem = len0; var eos = false; var stall = 0
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (!eos) {
                val ii = enc.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = enc.getInputBuffer(ii)!!
                    ib.clear()
                    val n = minOf(rem, ib.capacity())
                    if (n > 0) { ib.put(pcm, off, n); off += n; rem -= n }
                    val fin = last && rem == 0
                    enc.queueInputBuffer(ii, 0, n, 0, if (fin) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (fin) eos = true
                }
            }
            if (!eos && rem == 0) {
                val oi0 = enc.dequeueOutputBuffer(info, 0)
                if (oi0 == MediaCodec.INFO_TRY_AGAIN_LATER) return
                handleOut(enc, oi0, info, onFmt, onPkt)?.let { if (it) return }
            } else {
                val oi = enc.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) { if (++stall > 3000) throw IOException("الترميز أخد وقت طويل"); continue }
                handleOut(enc, oi, info, onFmt, onPkt)?.let { if (it) return }
            }
        }
    }

    /** @return true لو دي حزمة EOS (الترميز خلص) */
    private fun handleOut(enc: MediaCodec, oi: Int, info: MediaCodec.BufferInfo, onFmt: (MediaFormat) -> Unit, onPkt: (ByteArray, Long, Int) -> Unit): Boolean? {
        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { onFmt(enc.outputFormat); return null }
        if (oi < 0) return null
        val ob = enc.getOutputBuffer(oi)
        if (info.size > 0 && ob != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            val p = ByteArray(info.size); ob.position(info.offset); ob.get(p)
            onPkt(p, info.presentationTimeUs, info.flags)
        }
        val fin = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        enc.releaseOutputBuffer(oi, false)
        return fin
    }

    /** ترميز Opus 24k مونو + كتابة حاوية Ogg (RFC 7845) يدويًا — MediaMuxer مبيعرفش Ogg. */
    private class OggOpus(out: File) : PcmOut {
        private val enc: MediaCodec
        private val fos = FileOutputStream(out)
        private val serial = java.util.Random().nextInt()
        private var seq = 0
        private var preskip = 312L
        private var headDone = false
        private var closed = false
        private val segs = ArrayList<Int>()
        private val payload = java.io.ByteArrayOutputStream()
        private var pagePackets = 0
        private var granule = 0L

        init {
            val f = MediaFormat.createAudioFormat("audio/opus", OUT_RATE, 1)
            f.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BR)
            val c = MediaCodec.createEncoderByType("audio/opus")   // بيرمي لو الجهاز مفيهوش (أندرويد < 10 غالبًا) ← fallback AAC
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            enc = c
        }

        override fun offer(pcm: ByteArray, off: Int, len: Int, last: Boolean) {
            if (len <= 0 && !last) return
            pump(enc, pcm, off, len, last,
                { f -> if (!headDone) { writeHead(f); headDone = true } },
                { p, pts, flags ->
                    granule = preskip + pts * OUT_RATE / 1_000_000L
                    addPacket(p)
                    if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) flushPage(4)
                })
        }

        private fun addPacket(p: ByteArray) {
            var l = p.size
            while (l >= 255) { segs.add(255); l -= 255 }
            segs.add(l)
            payload.write(p); pagePackets++
            if (segs.size >= 200 || pagePackets >= 50) flushPage(0)
        }

        private fun flushPage(type: Int) {
            if (segs.isEmpty() && type == 0) return
            writeRaw(type, granule, segs.toList(), payload.toByteArray())
            segs.clear(); payload.reset(); pagePackets = 0
        }

        private fun writeHead(f: MediaFormat) {
            var head: ByteArray? = null
            try {
                val c = f.getByteBuffer("csd-0")
                if (c != null) { val b = ByteArray(c.remaining()); c.get(b); if (b.size >= 19 && String(b, 0, 8) == "OpusHead") head = b }
            } catch (_: Throwable) {}
            if (head == null) {
                val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                b.put("OpusHead".toByteArray()); b.put(1.toByte()); b.put(1.toByte()); b.putShort(312.toShort()); b.putInt(OUT_RATE); b.putShort(0.toShort()); b.put(0.toByte())
                head = b.array()
            }
            preskip = ((head[11].toInt() and 255) shl 8 or (head[10].toInt() and 255)).toLong()
            writeRaw(2, 0L, lacing(head.size), head)   // صفحة BOS: OpusHead لوحدها
            val v = "Joyboy".toByteArray()
            val t = ByteBuffer.allocate(8 + 4 + v.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            t.put("OpusTags".toByteArray()); t.putInt(v.size); t.put(v); t.putInt(0)
            writeRaw(0, 0L, lacing(t.array().size), t.array())
        }

        private fun lacing(len: Int): List<Int> {
            val r = ArrayList<Int>(); var l = len
            while (l >= 255) { r.add(255); l -= 255 }
            r.add(l); return r
        }

        private val crcTable = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if ((r and Int.MIN_VALUE) != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
            r
        }

        private fun writeRaw(type: Int, gran: Long, lacing: List<Int>, data: ByteArray) {
            val h = ByteBuffer.allocate(27 + lacing.size).order(ByteOrder.LITTLE_ENDIAN)
            h.put("OggS".toByteArray()); h.put(0.toByte()); h.put(type.toByte()); h.putLong(gran); h.putInt(serial); h.putInt(seq++); h.putInt(0)
            h.put(lacing.size.toByte()); lacing.forEach { h.put(it.toByte()) }
            val page = h.array() + data
            var c = 0
            for (x in page) c = (c shl 8) xor crcTable[((c ushr 24) xor (x.toInt() and 255)) and 255]
            page[22] = (c and 255).toByte(); page[23] = ((c ushr 8) and 255).toByte(); page[24] = ((c ushr 16) and 255).toByte(); page[25] = ((c ushr 24) and 255).toByte()
            fos.write(page)
        }

        override fun close() {
            if (closed) return; closed = true
            try { enc.stop() } catch (_: Throwable) {}
            try { enc.release() } catch (_: Throwable) {}
            try { fos.close() } catch (_: Throwable) {}
        }
    }

    /** بديل أضمن: AAC-LC 32k مونو في m4a (MediaMuxer) — كوديك إجباري في أي أندرويد. */
    private class AacM4a(out: File, rate: Int) : PcmOut {
        private val enc: MediaCodec
        private val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var track = -1
        private var started = false
        private var closed = false

        init {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BR)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            enc = c
        }

        override fun offer(pcm: ByteArray, off: Int, len: Int, last: Boolean) {
            if (len <= 0 && !last) return
            pump(enc, pcm, off, len, last,
                { f -> track = mux.addTrack(f); mux.start(); started = true },
                { p, pts, flags ->
                    if (started) {
                        val bi = MediaCodec.BufferInfo(); bi.set(0, p.size, pts, flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        mux.writeSampleData(track, ByteBuffer.wrap(p), bi)
                    }
                })
        }

        override fun close() {
            if (closed) return; closed = true
            try { enc.stop() } catch (_: Throwable) {}
            try { enc.release() } catch (_: Throwable) {}
            if (started) { try { mux.stop() } catch (_: Throwable) {} }
            try { mux.release() } catch (_: Throwable) {}
        }
    }

    /** السلوك القديم (v1238): نسخ مسار الصوت الخام كما هو — ملاذ أخير لو الترميز كله فشل (صيغة مفيش لها decoder مثلًا). */
    private fun remux(ctx: Context, uri: Uri?, url: String?, hdr: Map<String, String>, title: String, onPct: (Int) -> Unit = {}): File {
        val ex = MediaExtractor()
        try {
            if (uri != null && !uri.toString().startsWith("http")) ex.setDataSource(ctx, uri, null)
            else ex.setDataSource(url ?: uri.toString(), hdr)
            var ti = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ti = i; fmt = f; break }
            }
            if (ti < 0 || fmt == null) throw IOException("مفيش مسار صوت في الفيديو ده")
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            val webm = mime.contains("opus") || mime.contains("vorbis")
            val dir = File(ctx.cacheDir, "share_audio").also { it.mkdirs() }
            val safe = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(30).ifBlank { "audio" }
            val out = File(dir, "$safe-audio." + (if (webm) "webm" else "m4a"))
            ex.selectTrack(ti)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val muxer = MediaMuxer(out.absolutePath, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val mt = muxer.addTrack(fmt); muxer.start()
                val buf = ByteBuffer.allocate(1024 * 1024); val info = MediaCodec.BufferInfo()
                var lastP = -1
                while (true) {
                    buf.clear(); val n = ex.readSampleData(buf, 0); if (n < 0) break
                    info.set(0, n, ex.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(mt, buf, info)
                    if (durUs > 0) { val p = (ex.sampleTime * 100 / durUs).toInt().coerceIn(0, 100); if (p != lastP) { lastP = p; onPct(p) } }
                    ex.advance()
                }
                muxer.stop()
            } catch (e: Throwable) { try { out.delete() } catch (_: Throwable) {}; throw IOException("الصيغة دي ($mime) ما قدرتش أطلّعها لملف واحد: " + (e.message ?: ""), e) }
            finally { try { muxer.release() } catch (_: Throwable) {} }
            if (out.length() < 1024L) throw IOException("الصوت طلع فاضي")
            return out
        } finally { try { ex.release() } catch (_: Throwable) {} }
    }

    fun share(a: android.app.Activity, f: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(a, a.packageName + ".fmprov", f)
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType(if (f.name.endsWith(".webm")) "audio/webm" else if (f.name.endsWith(".opus")) "audio/ogg" else "audio/mp4")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(android.content.Intent.createChooser(i, "ابعت الصوت"))
    }
}
