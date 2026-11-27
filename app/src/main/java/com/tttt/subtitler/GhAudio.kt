package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
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

/** (v1238) صوت الفيديو كله كملف واحد (m4a من غير إعادة ضغط، بنفس التوقيت) + نافذة المشاركة (واتساب وغيره) */
object FullAudio {
    fun extract(ctx: android.content.Context, uri: android.net.Uri?, url: String?, hdr: Map<String, String>, title: String, onPct: (Int) -> Unit = {}): java.io.File {
        val ex = android.media.MediaExtractor()
        try {
            if (uri != null && !uri.toString().startsWith("http")) ex.setDataSource(ctx, uri, null)
            else ex.setDataSource(url ?: uri.toString(), hdr)
            var ti = -1; var fmt: android.media.MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(android.media.MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ti = i; fmt = f; break }
            }
            if (ti < 0 || fmt == null) throw java.io.IOException("مفيش مسار صوت في الفيديو ده")
            val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
            val webm = mime.contains("opus") || mime.contains("vorbis")
            val dir = java.io.File(ctx.cacheDir, "share_audio").also { it.mkdirs() }
            dir.listFiles()?.forEach { try { it.delete() } catch (_: Throwable) {} }
            val safe = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(30).ifBlank { "audio" }
            val out = java.io.File(dir, "$safe-audio." + (if (webm) "webm" else "m4a"))
            ex.selectTrack(ti)
            val durUs = if (fmt.containsKey(android.media.MediaFormat.KEY_DURATION)) fmt.getLong(android.media.MediaFormat.KEY_DURATION) else 0L
            val muxer = android.media.MediaMuxer(out.absolutePath, if (webm) android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val mt = muxer.addTrack(fmt); muxer.start()
                val buf = java.nio.ByteBuffer.allocate(1024 * 1024); val info = android.media.MediaCodec.BufferInfo()
                var lastP = -1
                while (true) {
                    buf.clear(); val n = ex.readSampleData(buf, 0); if (n < 0) break
                    info.set(0, n, ex.sampleTime, android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(mt, buf, info)
                    if (durUs > 0) { val p = (ex.sampleTime * 100 / durUs).toInt().coerceIn(0, 100); if (p != lastP) { lastP = p; onPct(p) } }
                    ex.advance()
                }
                muxer.stop()
            } catch (e: Throwable) { try { out.delete() } catch (_: Throwable) {}; throw java.io.IOException("الصيغة دي ($mime) ما قدرتش أطلّعها لملف واحد: " + (e.message ?: "").take(60)) }
            finally { try { muxer.release() } catch (_: Throwable) {} }
            if (out.length() < 1024L) throw java.io.IOException("الصوت طلع فاضي")
            return out
        } finally { try { ex.release() } catch (_: Throwable) {} }
    }

    fun share(a: android.app.Activity, f: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(a, a.packageName + ".fmprov", f)
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType(if (f.name.endsWith(".webm")) "audio/webm" else "audio/mp4")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(android.content.Intent.createChooser(i, "ابعت الصوت"))
    }
}
