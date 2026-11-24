package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * (v1202) رفع صوت الفيديو (باتشات AAC مترقمة في ZIP واحد) على الريبو عشان يتترجم بره التطبيق.
 *  الملفات بتتحط في patches/audio/ (من غير ما تشغّل بيلد ولا Apply Patch)، وبيتعمل معاها ملف برومبت فيه تعليمات الترجمة الحالية.
 *  البرومبت بيتنسخ تلقائي بعد الرفع، و«🗑 مسح» بيشيل كل اللي في patches/audio/.
 */
object GhAudio {
    private const val REPO = "alisaeed9x/Joyboy"
    private const val DIR = "patches/audio"
    private const val UA = "Joyboy-App"
    private const val MAX_BYTES = 70L * 1024L * 1024L

    private val K = arrayOf("jb", "-up-", "audio", "#", "1202")
    private val D = arrayOf("9043bc811f81397550599caebf1a0902bc9a46188eae6a5", "723cdaa94ab4b703e7b21bfc14132fcd67cca4d7a41d926", "419706fd5f39d69f8a49bad9453900fc4c21592d01f1d62", "ebb40ccd37ef30c92b48eafb870346062576b29456452")
    private fun token(): String {
        val hex = D.joinToString("")
        if (hex.length < 2) return Cfg.str("gh_up_token", "").trim()
        return try {
            val key = K.joinToString("")
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val n = hex.length / 2
            val out = ByteArray(n)
            var blk = ByteArray(32)
            for (j in 0 until n) {
                if (j % 32 == 0) blk = md.digest((key + (j / 32)).toByteArray(Charsets.UTF_8))
                out[j] = (hex.substring(j * 2, j * 2 + 2).toInt(16) xor (blk[j % 32].toInt() and 0xff)).toByte()
            }
            String(out, Charsets.UTF_8).trim()
        } catch (_: Throwable) { "" }
    }

    private fun api(method: String, path: String): HttpURLConnection {
        val c = URL("https://api.github.com" + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 20000; c.readTimeout = 180000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("Authorization", "Bearer " + token())
        return c
    }

    private fun errOf(c: HttpURLConnection): String = try {
        JSONObject(c.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}").optString("message", "")
    } catch (_: Throwable) { "" }

    private fun why(code: Int, msg: String): String = when (code) {
        401 -> "التوكن غلط أو منتهي"
        403 -> "مفيش صلاحية كتابة على الريبو (" + msg + ")"
        404 -> "الريبو أو المسار مش موجود — التوكن محتاج صلاحية Contents: Read and write"
        413, 422 -> "الملف كبير على GitHub (" + msg + ")"
        else -> "GitHub " + code + ": " + msg
    }

    private fun readFull(ins: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) { val r = ins.read(buf, off, buf.size - off); if (r < 0) break; off += r }
        return off
    }

    /** رفع ملف (بيتبعت base64 على دفعات من غير ما يتحمّل كله في الذاكرة) */
    private fun putFile(path: String, src: File, msg: String, onPct: (Int) -> Unit) {
        val n = src.length()
        val pre = ("{\"message\":" + JSONObject.quote(msg) + ",\"branch\":\"main\",\"content\":\"").toByteArray(Charsets.UTF_8)
        val suf = "\"}".toByteArray(Charsets.UTF_8)
        val b64 = 4L * ((n + 2L) / 3L)
        val c = api("PUT", "/repos/" + REPO + "/contents/" + path)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(pre.size.toLong() + b64 + suf.size.toLong())
            c.outputStream.use { os ->
                os.write(pre)
                FileInputStream(src).use { ins ->
                    val buf = ByteArray(3 * 16384)
                    var done = 0L
                    while (true) {
                        val r = readFull(ins, buf)
                        if (r <= 0) break
                        os.write(Base64.encode(buf, 0, r, Base64.NO_WRAP))
                        done += r
                        onPct(if (n > 0) ((done * 100L) / n).toInt().coerceIn(0, 100) else 100)
                        if (r < buf.size) break
                    }
                }
                os.write(suf)
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException(why(code, errOf(c)))
        } finally { c.disconnect() }
    }

    fun upload(act: Activity, engine: Engine, title: String) {
        if (token().isBlank()) { Notice.show(act, "⚠ التوكن مش متظبّط", 2500L); return }
        if (TaskCenter.items.any { it.kind == "ghup" && (it.state == 0 || it.state == 1) }) { Notice.show(act, "⏳ رفع الصوت شغّال لسه — تابعه من «المهام»", 2200L); return }
        LogStore.add("☁ رفع الصوت للريبو: بدأ — " + title)
        Notice.show(act, "☁ اتضاف للمهام — رفع الصوت للريبو", 2400L)
        TaskCenter.add(act, "ghup", "☁ رفع الصوت للريبو: " + BatchExport.safe(title)) { task -> work(act, engine, title, task) }
    }

    private fun work(act: Activity, engine: Engine, title: String, task: TaskItem) {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
        val ascii = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(30).ifBlank { "video" }
        val id = stamp + "_" + ascii
        val zip = File(act.cacheDir, "ghup_" + id + ".zip")
        val ptxt = File(act.cacheDir, "ghup_" + id + ".prompt.txt")
        try {
            val index = StringBuilder("الفيديو: ").append(title).append("\nكل ملف صوت (غير الأول) بيبدأ قبل حدود الباتش بـ ").append(Engine.OVERLAP.toInt()).append(" ثواني (تداخل للسياق)، والاسم بيدل على حدود الباتش نفسه (ساعة-دقيقة-ثانية).\n\n")
            val fails = ArrayList<String>()
            var n = 0
            java.util.zip.ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zo ->
                n = engine.exportBatchAudio({ i, w -> fails.add("باتش " + (i + 1) + " — " + w) }) { i, total, st, en, bytes, mime ->
                    if (task.cancelled) throw TaskCancelled()
                    val ext = if (mime.contains("wav")) "wav" else "aac"
                    val name = String.format(Locale.US, "%03d_%s_%s.%s", i + 1, BatchExport.hms(st), BatchExport.hms(en), ext)
                    zo.putNextEntry(java.util.zip.ZipEntry(name)); zo.write(bytes); zo.closeEntry()
                    index.append("باتش ").append(i + 1).append(" — من ").append(BatchExport.hms(st)).append(" إلى ").append(BatchExport.hms(en)).append(" — ").append(name).append("\n")
                    task.pct = ((i + 1) * 40 / maxOf(1, total)).coerceIn(0, 40)
                    task.msg = "🎧 تجهيز الباتش " + (i + 1) + " من " + total
                    TaskCenter.changed()
                }
                if (n > 0) {
                    if (fails.isNotEmpty()) { index.append("\n⚠ باتشات ما طلعش صوتها (").append(fails.size).append("):\n"); fails.forEach { index.append("- ").append(it).append("\n") } }
                    zo.putNextEntry(java.util.zip.ZipEntry("batches.txt")); zo.write(index.toString().toByteArray(Charsets.UTF_8)); zo.closeEntry()
                }
            }
            if (n <= 0) throw IOException("مفيش صوت أطلعه من الفيديو ده" + (if (fails.isNotEmpty()) " — " + fails.first() else ""))
            if (zip.length() > MAX_BYTES) throw IOException("الملف كبير (" + (zip.length() / 1048576L) + "MB) — أكبر من حد GitHub (حوالي 70MB)")
            val sizeMb = zip.length() / 1048576L
            var last = -1
            putFile(DIR + "/" + id + ".zip", zip, "audio " + id) { p ->
                if (task.cancelled) throw TaskCancelled()
                if (p / 5 != last) { last = p / 5; task.pct = (40 + p * 55 / 100).coerceIn(40, 95); task.msg = "☁ برفع الصوت… " + p + "% (" + sizeMb + "MB)"; TaskCenter.changed() }
            }
            val prompt = promptFor(title, id, n, try { engine.promptText() } catch (_: Throwable) { "" })
            ptxt.writeText(prompt, Charsets.UTF_8)
            task.pct = 97; task.msg = "☁ برفع البرومبت…"; TaskCenter.changed()
            putFile(DIR + "/" + id + ".prompt.txt", ptxt, "prompt " + id) { }
            Cfg.put("gh_last_prompt", prompt)
            task.outPath = REPO + "/" + DIR + "/" + id + ".zip"
            task.msg = "✅ تم رفع الصوت بنجاح (" + n + " باتش · " + sizeMb + "MB) — البرومبت اتنسخ"
            LogStore.add("☁ رفع الصوت للريبو: خلص — " + n + " باتش (فشل " + fails.size + ") ← " + DIR + "/" + id + ".zip")
            act.runOnUiThread { copy(act, prompt); Notice.show(act, "✅ تم رفع الصوت بنجاح — البرومبت اتنسخ، ابعته للـAI", 5000L) }
        } catch (e: TaskCancelled) {
            throw e
        } catch (e: Throwable) {
            if (e !is IOException) LogStore.err("GhAudio", e)
            LogStore.add("☁ رفع الصوت للريبو: فشل — " + (e.message ?: e.javaClass.simpleName))
            throw e
        } finally {
            try { zip.delete() } catch (_: Throwable) {}
            try { ptxt.delete() } catch (_: Throwable) {}
        }
    }

    private fun promptFor(title: String, id: String, n: Int, eng: String): String {
        val sb = StringBuilder()
        sb.append("ترجملي صوت الفيديو ده وابعتلي ملف ترجمة SRT بالتوقيتات.\n")
        sb.append("الفيديو: ").append(title).append("\n")
        sb.append("الريبو: ").append(REPO).append(" (فرع main)\n")
        sb.append("ملف الصوت: ").append(DIR).append("/").append(id).append(".zip\n")
        sb.append("التوكن: ").append(token()).append("\n\n")
        sb.append("الـZIP فيه ").append(n).append(" باتش صوت مترقّم (مثلًا 001_00-00-00_00-01-00.aac)، والاسم بيدل على حدود الباتش في الفيديو. ")
        sb.append("كل ملف صوت غير الأول بيبدأ قبل حدود الباتش بـ ").append(Engine.OVERLAP.toInt()).append(" ثواني للسياق بس (ماتترجمش الجزء ده تاني). وفيه batches.txt بالفهرس.\n")
        sb.append("نزّل الملف من الريبو (من غير ما تحط التوكن في رابط الـremote ولا تحفظه)، اسمعه، ترجمه حسب التعليمات تحت، وارجعلي ملف SRT واحد توقيته على توقيت الفيديو كله (توقيت الجملة = بداية الباتش + مكانها جوه الباتش).\n\n")
        sb.append("===== تعليمات الترجمة =====\n").append(if (eng.isBlank()) "(مفيش تعليمات اتولّدت — ترجم لعربي طبيعي ومناسب للمشاهدة)" else eng)
        return sb.toString()
    }

    private fun copy(ctx: Context, text: String) {
        try { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("repo-prompt", text)) } catch (_: Throwable) {}
    }

    /** نسخ آخر برومبت اتعمل */
    fun copyLast(act: Activity) {
        val p = Cfg.str("gh_last_prompt", "")
        if (p.isBlank()) { Notice.show(act, "مفيش برومبت لسه — ارفع الصوت الأول", 2200L); return }
        copy(act, p); Notice.show(act, "📋 اتنسخ برومبت الريبو", 1800L)
    }

    /** مسح كل اللي في patches/audio/ من الريبو */
    fun clear(act: Activity) {
        if (token().isBlank()) { Notice.show(act, "⚠ التوكن مش متظبّط", 2500L); return }
        Notice.show(act, "🗑 بمسح الصوت من الريبو…", 1800L)
        Thread {
            var msg: String
            try {
                val c = api("GET", "/repos/" + REPO + "/contents/" + DIR + "?ref=main")
                val files = ArrayList<Pair<String, String>>()
                try {
                    val code = c.responseCode
                    if (code == 404) { /* مفيش فولدر */ }
                    else if (code !in 200..299) throw IOException(why(code, errOf(c)))
                    else {
                        val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("type") == "file") files.add(Pair(o.optString("path"), o.optString("sha"))) }
                    }
                } finally { c.disconnect() }
                var ok = 0
                for ((p, sha) in files) {
                    val d = api("DELETE", "/repos/" + REPO + "/contents/" + p)
                    try {
                        d.doOutput = true; d.setRequestProperty("Content-Type", "application/json")
                        d.outputStream.use { it.write(JSONObject().put("message", "clear audio").put("sha", sha).put("branch", "main").toString().toByteArray(Charsets.UTF_8)) }
                        val code = d.responseCode
                        if (code !in 200..299) throw IOException(why(code, errOf(d)))
                        ok++
                    } finally { d.disconnect() }
                }
                msg = if (files.isEmpty()) "مفيش صوت على الريبو" else "🗑 اتمسح " + ok + " ملف من الريبو"
                LogStore.add("☁ مسح الصوت من الريبو: " + msg)
            } catch (e: Throwable) {
                LogStore.err("GhAudio:clear", e)
                msg = "⚠ المسح فشل: " + (e.message ?: e.javaClass.simpleName)
            }
            act.runOnUiThread { Notice.show(act, msg, 4000L) }
        }.also { it.isDaemon = true }.start()
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
