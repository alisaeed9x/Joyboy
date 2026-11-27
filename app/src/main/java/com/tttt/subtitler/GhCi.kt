package com.tttt.subtitler

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.ZipFile

/**
 * (v856) مراقبة GitHub Actions + تحديث من جوه البرنامج:
 *  كل ورك فلو في الريبو بيفشل ← إشعار أندرويد + سؤال جوه البرنامج «📥 تحميل اللوج» (واللوج بيتنزّل مسبقًا لو التوكن موجود)
 * (v1357) مسار التحديث القديم (artifact + الفحص الدوري + التنزيل التلقائي وإعداداته) اتحذف بالكامل — التحديث كله من مسار الريليزات (Upd) بس (بطلب علي)
 * ملاحظة: GitHub بيطلب توكن لتنزيل اللوجات حتى في الريبو العام.
 * التوكن: Fine-grained PAT على الريبو ده بصلاحية Actions = Read-only (أو Classic بصلاحية repo).
 * الفحص: JobScheduler كل 30 دقيقة (GhCiJob) + كل ما تفتح البرنامج (بحد أدنى 3 دقايق بين فحص والتاني).
 */
object GhCi {
    const val DEF_REPO = "alisaeed9x/Joyboy"
    private const val CH = "gh_ci"
    private const val JOB = 32710
    private const val N_FAIL = 32711
    private const val UA = "Joyboy-App"
    private const val DAY = 24L * 3600L * 1000L

    // ───────── الإعدادات ─────────
    /** (v859) الريبو ثابت جوه البرنامج، والتوكن متشفّر جوه البرنامج: مفيش شاشة إدخال ولا حفظ. لو الخانة فاضية بيرجع للتوكن القديم المحفوظ (لو موجود) */
    fun repo(): String = DEF_REPO
    fun token(): String = GhSecret.open().ifBlank { Cfg.str("gh_token", "").trim() }

    /** (v859) التوكن مخزّن كـ hex متشفّر (XOR مع تيار SHA-256) ومقسّم، مش نص ظاهر في الـAPK ولا في الريبو. الشفرة بتتولّد بسكريبت خارجي */
    private object GhSecret {
        private val K = arrayOf("jb", "-gh-", "ci", "#", "859")
        private val D = arrayOf("41e4d930eaf47ccf2bb9", "6735b905644ef715d526", "f55c77589ff1852e3b6b", "8d759cbbbec328914d03")
        fun open(): String {
            val hex = D.joinToString("")
            if (hex.length < 2) return ""
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
    }
    fun watchOn(): Boolean = Cfg.bool("gh_watch", true)

    class Run(val id: Long, val name: String, val num: Int, val conclusion: String, val status: String, val branch: String, val startedMs: Long, val url: String)
    class GhErr(val code: Int, msg: String) : Exception(msg)

    // ───────── HTTP ─────────
    private fun s(j: JSONObject, k: String): String = if (j.isNull(k)) "" else j.optString(k, "")
    private fun ms(iso: String): Long = try { Instant.parse(iso).toEpochMilli() } catch (_: Throwable) { 0L }

    private fun open(url: String, tok: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (tok.isNotBlank()) c.setRequestProperty("Authorization", "Bearer " + tok)
        return c
    }

    private fun errText(c: HttpURLConnection): String = try {
        JSONObject(c.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}").optString("message", "")
    } catch (_: Throwable) { "" }

    private fun getJson(path: String): JSONObject {
        val c = open("https://api.github.com" + path, token())
        try {
            val code = c.responseCode
            if (code !in 200..299) throw GhErr(code, errText(c))
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** تنزيل ملف من GitHub (لوج / artifact): الطلب الأول بالتوكن ← 302 ← الرابط الموقّع بيتحمّل من غير توكن */
    private fun fetchTo(path: String, dest: File, progress: ((Long, Long) -> Unit)? = null) {
        var c = open("https://api.github.com" + path, token())
        try {
            var code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw GhErr(code, "مفيش رابط تنزيل")
                c.disconnect()
                c = URL(loc).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 60000
                c.setRequestProperty("User-Agent", UA)
                code = c.responseCode
            }
            if (code !in 200..299) throw GhErr(code, errText(c))
            val total = c.contentLengthLong
            val tmp = File(dest.path + ".part")
            c.inputStream.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastP = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (progress != null) {
                            val now = System.currentTimeMillis()
                            if (now - lastP > 400L) { lastP = now; progress(done, total) }
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("مقدرتش أحفظ الملف")
        } finally { c.disconnect() }
    }

    fun explain(e: Throwable): String = when {
        e is GhErr && e.code == 401 -> "التوكن غلط أو منتهي"
        e is GhErr && e.code == 403 -> "مفيش صلاحية أو الحد خلص (" + (e.message ?: "") + ")"
        e is GhErr && e.code == 404 -> "الريبو مش لاقيه — لو خاص لازم توكن بصلاحية Actions: Read"
        else -> (e.message ?: e.javaClass.simpleName).take(140)
    }

    // ───────── ورك فلوهات ─────────
    fun runs(): List<Run> {
        val a = getJson("/repos/" + repo() + "/actions/runs?per_page=30").optJSONArray("workflow_runs") ?: return emptyList()
        val out = ArrayList<Run>()
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            val st = s(r, "run_started_at").ifBlank { s(r, "created_at") }
            out.add(Run(r.optLong("id"), s(r, "name"), r.optInt("run_number"), s(r, "conclusion"), s(r, "status"), s(r, "head_branch"), ms(st), s(r, "html_url")))
        }
        return out
    }

    fun isFailed(r: Run): Boolean = r.status == "completed" && (r.conclusion == "failure" || r.conclusion == "timed_out" || r.conclusion == "startup_failure")

    /** بيلاقي ورك فلو فشل جديد (آخر 3 أيام ومتنبّهش عليه قبل كده) ← بيسجّله، بينزّل لوجه مسبقًا، وبيبعت إشعار */
    fun checkFailures(c: Context): Run? {
        val seen = LinkedHashSet<String>(Cfg.str("gh_notified", "").split(",").filter { it.isNotBlank() })
        val now = System.currentTimeMillis()
        var newest: Run? = null
        for (r in runs()) {
            if (!isFailed(r)) continue
            if (now - r.startedMs > 3L * DAY) continue
            if (!seen.add(r.id.toString())) continue
            if (newest == null) newest = r
        }
        val f = newest ?: return null
        Cfg.put("gh_notified", seen.toList().takeLast(60).joinToString(","))
        Cfg.put("gh_fail_id", f.id.toString()); Cfg.put("gh_fail_name", f.name + " #" + f.num); Cfg.put("gh_fail_url", f.url)
        var sum = ""
        if (token().isNotBlank() && Cfg.bool("gh_prelog", true)) {
            try { val lf = fetchLog(c, f.id); sum = summary(lf); Cfg.put("gh_fail_sum", sum) } catch (e: Throwable) { LogStore.add("GhCi: تنزيل اللوج مسبقًا فشل — " + explain(e)) }
        } else Cfg.put("gh_fail_sum", "")
        val body = if (sum.isNotBlank()) sum else if (token().isBlank()) "افتح «مركز التحديث» وحمّل اللوج" else "افتح البرنامج وحمّل اللوج"
        notify(c, N_FAIL, "⚠ ورك فلو فشل: " + f.name + " #" + f.num, body.lines().firstOrNull() ?: body, body, true)
        return f
    }

    // ───────── اللوج ─────────
    fun logFile(c: Context, id: Long): File = File(c.cacheDir, "gh_log_" + id + ".zip")

    fun fetchLog(c: Context, id: Long): File {
        val f = logFile(c, id)
        fetchTo("/repos/" + repo() + "/actions/runs/" + id + "/logs", f)
        try { c.cacheDir.listFiles()?.forEach { x -> if (x.name.startsWith("gh_log_") && x.name != f.name && System.currentTimeMillis() - x.lastModified() > 7L * DAY) x.delete() } } catch (_: Throwable) {}
        return f
    }

    /** أهم سطور الأخطاء من اللوج (e: / error: / What went wrong …) عشان تبان في الإشعار والنافذة */
    fun summary(f: File): String {
        val re = Regex("""(^|\s)e: |error:|What went wrong|Execution failed|FAILURE: |Process completed with exit code""")
        val ts = Regex("""^\uFEFF?\d{4}-\S+Z """)
        val out = LinkedHashSet<String>()
        try {
            ZipFile(f).use { z ->
                val en = z.entries()
                while (en.hasMoreElements() && out.size < 8) {
                    val e = en.nextElement()
                    if (e.isDirectory) continue
                    z.getInputStream(e).bufferedReader().useLines { seq ->
                        for (l in seq) {
                            if (out.size >= 8) break
                            if (!re.containsMatchIn(l)) continue
                            val t = l.replace(ts, "").replace("file:///home/runner/work/", "").replace("##[error]", "").trim()
                            if (t.isNotEmpty()) out.add(t.take(180))
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return out.joinToString("\n")
    }

    /** بيحفظ اللوج في التنزيلات (Download/Joyboy) ويرجّع المسار للعرض */
    fun saveToDownloads(c: Context, src: File, name: String, mime: String = "application/zip"): String {   // (v1361) mime اختياري: باتش الصوت بيحفظ audio/* و text/plain
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Joyboy")
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("مقدرتش أكتب في التنزيلات")
            c.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
            return "Download/Joyboy/" + name
        }
        val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Joyboy")
        d.mkdirs()
        src.copyTo(File(d, name), true)
        return "Download/Joyboy/" + name
    }

    fun share(a: Activity, f: File) {
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fmprov", f)
        val i = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, "ابعت اللوج"))
    }

    /** تنزيل اللوج من جوه البرنامج: يتحفظ في التنزيلات + نافذة فيها أهم الأخطاء وزرار مشاركة */
    fun downloadLogUi(a: Activity, runId: Long) {
        if (token().isBlank()) {
            Notice.show(a, "⚠ لازم توكن GitHub عشان تنزّل اللوج — ضيفه هنا", 4500L)
            try { GhCiUi.show(a) } catch (_: Throwable) {}
            return
        }
        Notice.show(a, "⏳ بنزّل اللوج…", 2000L)
        Thread {
            try {
                val f = logFile(a, runId)
                if (!f.isFile || f.length() < 100L) fetchLog(a, runId)
                val where = try { saveToDownloads(a, f, "joyboy_ci_log_" + runId + ".zip") } catch (e: Throwable) { LogStore.err("GhCi:save", e); "" }
                val sum = summary(f)
                try { (a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(N_FAIL) } catch (_: Throwable) {}
                a.runOnUiThread {
                    try {
                        val msg = (if (where.isNotEmpty()) "اتحفظ في: " + where + "\n\n" else "ما اتحفظش في التنزيلات — استخدم المشاركة\n\n") + sum
                        GAlert(a).setTitle("📥 اللوج جاهز").setMessage(msg.trim())
                            .setPositiveButton("📤 مشاركة") { _, _ -> try { share(a, f) } catch (e: Throwable) { LogStore.err("GhCi:share", e) } }
                            .setNegativeButton("تمام", null).show()
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                LogStore.err("GhCi:log", e)
                a.runOnUiThread { Notice.show(a, "⚠ تنزيل اللوج فشل: " + explain(e), 5000L) }
            }
        }.apply { isDaemon = true; start() }
    }

    // ───────── الفحص (Job + عند فتح البرنامج) ─────────
    fun tick(c: Context) {
        Cfg.ensure(c)
        if (DeviceUi.isTv(c)) return
        if (watchOn()) {
            try { checkFailures(c) } catch (e: Throwable) { LogStore.add("GhCi: فحص الورك فلو — " + explain(e)) }
        }
    }

    /** (v864) كل كام دقيقة يفحص في الخلفية (الأدنى 15 دقيقة — حد JobScheduler) */
    fun everyMin(): Long = (Cfg.str("gh_every", "30").toLongOrNull() ?: 30L).coerceIn(15L, 720L)
    /** (v864) بعد تغيير التكرار: يلغي المهمة القديمة ويجدولها بالتكرار الجديد */
    fun reschedule(c: Context) { try { (c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB) } catch (_: Throwable) {}; schedule(c) }
    fun schedule(c: Context) {
        try {
            val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (DeviceUi.isTv(c) || !watchOn()) { js.cancel(JOB); return }
            if (js.getPendingJob(JOB) != null) return
            js.schedule(JobInfo.Builder(JOB, ComponentName(c, GhCiJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(everyMin() * 60L * 1000L).build())
        } catch (e: Throwable) { try { LogStore.err("GhCi:schedule", e) } catch (_: Throwable) {} }
    }

    @Volatile private var lastFg = 0L

    /** من Main.onResume: يظبط الجدولة، يسأل عن المعلّق (فشل/تحديث جاهز)، ويعمل فحص خفيف في الخلفية */
    fun onForeground(a: Activity) {
        try {
            Cfg.ensure(a)
            if (DeviceUi.isTv(a)) return
            schedule(a)
            // (v1311) الإشعار العائم القديم (ورك فلو فشل / في تحديث جديد + أزرار) اتشال بطلب علي — مركز التحديث والجرس هما البديل
            val now = System.currentTimeMillis()
            if (now - lastFg < 180_000L || !watchOn()) return
            lastFg = now
            val app = a.applicationContext
            Thread {
                try { tick(app) } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
        } catch (e: Throwable) { LogStore.err("GhCi:fg", e) }
    }

    private fun notify(c: Context, id: Int, title: String, text: String, big: String, error: Boolean) {
        if (!Cfg.bool("gh_notify", true)) return   // (v864) من إعدادات مركز التحديث
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "فشل بيلد GitHub / تحديث البرنامج", NotificationManager.IMPORTANCE_HIGH))
            val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH) else @Suppress("DEPRECATION") Notification.Builder(c)
            nb.setSmallIcon(if (error) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true)
            if (big.isNotBlank()) nb.setStyle(Notification.BigTextStyle().bigText(big))
            nm.notify(id, nb.build())
        } catch (e: Throwable) { LogStore.err("GhCi:notify", e) }
    }
}

class GhCiJob : JobService() {
    @Volatile private var t: Thread? = null
    override fun onStartJob(p: JobParameters): Boolean {
        t = Thread {
            try { GhCi.tick(applicationContext) } catch (e: Throwable) { try { LogStore.err("GhCiJob", e) } catch (_: Throwable) {} }
            try { jobFinished(p, false) } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        return true
    }
    override fun onStopJob(p: JobParameters): Boolean { t?.interrupt(); return true }
}
