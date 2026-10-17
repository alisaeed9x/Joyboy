package com.tttt.subtitler

import android.content.Context
import android.os.Build
import android.system.Os
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * (v394) 🐧 بيئة Linux داخل التطبيق نفسه — من غير Termux.
 *  - proot (مكتبة native جاهزة في jniLibs) بيشغّل Ubuntu 24.04 arm64 rootfs جوه مجلد بيانات التطبيق (من غير root).
 *  - جواها Node.js 22 الرسمي + OmniRoute (npm) → السيرفر بيشتغل على 127.0.0.1:20128.
 *  - بعد التشغيل: التطبيق بيسجّل دخول للوحة بكلمة سر عشوائية، يعمل Endpoint key، ويحفظه (llm_omniroute_key) — من غير أي تدخل منك.
 *  - طرفية كاملة (TerminalView من Termux) بتفتح نفس البيئة: [newShell].
 * كل خطوة idempotent: لو اتقطعت في النص بتكمّل من حيث وقفت.
 */
object Linux {
    const val PORT = 20128
    private const val UBUNTU_DIR = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
    private const val NODE_DIR = "https://nodejs.org/dist/latest-v22.x/"
    private const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    @Volatile var step = ""
    @Volatile var pct = -1
    @Volatile var busy = false
    @Volatile var lastError = ""
    @Volatile private var proc: Process? = null
    private val lock = Any()
    private val logSb = StringBuilder()
    private val srvSb = StringBuilder()
    @Volatile private var shell: TerminalSession? = null

    // ===== لوج =====
    @Synchronized fun logText(): String = logSb.toString().ifBlank { "مفيش لوج لسه" }
    @Synchronized private fun say(s: String) {
        logSb.append(s).append('\n'); if (logSb.length > 16000) logSb.delete(0, logSb.length - 12000)
    }
    @Synchronized fun serverLog(): String = srvSb.toString().ifBlank { "السيرفر لسه ما اشتغلش في الجلسة دي" }
    @Synchronized private fun srvSay(s: String) {
        srvSb.append(s).append('\n'); if (srvSb.length > 30000) srvSb.delete(0, srvSb.length - 22000)
    }
    private fun stepSet(s: String, p: Int = -1) { step = s; pct = p }

    // ===== مسارات =====
    fun baseDir(c: Context) = File(c.filesDir, "linux")
    fun rootfs(c: Context) = File(baseDir(c), "rootfs")
    private fun nativeDir(c: Context) = c.applicationInfo.nativeLibraryDir
    private fun proot(c: Context) = File(nativeDir(c), "libproot.so")
    private fun isArm64() = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

    /** الجهاز يقدر يشغّل البيئة (فيه proot مناسب للمعمارية) */
    fun supported(c: Context): Boolean = try { proot(c).exists() && Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" } } catch (_: Throwable) { false }
    fun rootReady(c: Context): Boolean = File(rootfs(c), "bin/bash").exists() && File(rootfs(c), "usr/bin/env").exists()
    fun nodeReady(c: Context): Boolean = File(rootfs(c), "usr/local/bin/node").exists()
    fun omniInstalled(c: Context): Boolean = File(rootfs(c), "usr/local/bin/omniroute").exists()
    fun opencodeInstalled(c: Context): Boolean = File(rootfs(c), "usr/local/bin/opencode").exists()
    /** كل اللازم لتشغيل أومني روت جاهز */
    fun ready(c: Context): Boolean = supported(c) && rootReady(c) && nodeReady(c) && omniInstalled(c)
    fun optedIn(): Boolean = Cfg.isReady() && Cfg.str("linux_optin") == "1"
    fun optIn() { if (Cfg.isReady()) Cfg.put("linux_optin", "1") }

    // ===== proot =====
    private fun fakeProc(c: Context): File = File(baseDir(c), "procfake").also { d ->
        if (!File(d, "stat").exists()) {
            d.mkdirs()
            File(d, "stat").writeText("cpu  1000 0 1000 10000 0 0 0 0 0 0\ncpu0 1000 0 1000 10000 0 0 0 0 0 0\nintr 0\nctxt 0\nbtime 1700000000\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
            File(d, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")
            File(d, "uptime").writeText("284684.56 513853.46\n")
            File(d, "version").writeText("Linux version 6.2.1 (proot@android) (gcc 12) #1 SMP PREEMPT\n")
        }
    }

    private fun prootEnv(c: Context): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["PATH"] = "/system/bin:/system/xbin"
        m["PROOT_TMP_DIR"] = File(baseDir(c), "tmp").also { it.mkdirs() }.path
        m["PROOT_LOADER"] = File(nativeDir(c), "libproot-loader.so").path
        File(nativeDir(c), "libproot-loader32.so").takeIf { it.exists() }?.let { m["PROOT_LOADER_32"] = it.path }
        m["PROOT_IGNORE_MISSING_BINDINGS"] = "1"
        if (Cfg.isReady() && Cfg.str("linux_noseccomp") == "1") m["PROOT_NO_SECCOMP"] = "1"
        return m
    }

    /** argv كامل لـ proot (أول عنصر = مسار proot) + أمر الضيف. [env] متغيرات إضافية جوه الضيف */
    private fun prootArgv(c: Context, guest: List<String>, env: List<String> = emptyList()): List<String> {
        val fp = fakeProc(c)
        val a = ArrayList<String>()
        a += proot(c).path; a += "--kill-on-exit"; a += "-0"; a += "--link2symlink"
        a += "-r"; a += rootfs(c).path
        for (b in listOf("/dev", "/proc", "/sys")) { a += "-b"; a += b }
        for (n in listOf("stat", "loadavg", "uptime", "version")) { a += "-b"; a += "${File(fp, n).path}:/proc/$n" }
        a += "-w"; a += "/root"
        a += "/usr/bin/env"; a += "-i"
        a += "HOME=/root"; a += "USER=root"; a += "LOGNAME=root"; a += "LANG=C.UTF-8"; a += "TERM=xterm-256color"; a += "COLORTERM=truecolor"
        a += "TMPDIR=/tmp"; a += "SHELL=/bin/bash"; a += "PATH=$GUEST_PATH"
        a += env
        a += guest
        return a
    }

    private fun pb(c: Context, guest: List<String>, env: List<String> = emptyList()): ProcessBuilder {
        val p = ProcessBuilder(prootArgv(c, guest, env))
        p.directory(baseDir(c).also { it.mkdirs() })
        p.environment().clear(); p.environment().putAll(prootEnv(c))
        p.redirectErrorStream(true)
        return p
    }

    /** بيشغّل سكربت bash جوه البيئة ويستنى. بيرجّع (exit, output). [onLine] لكل سطر. exit=-1 مهلة، -2 فشل التشغيل */
    fun exec(c: Context, script: String, args: List<String> = emptyList(), timeoutSec: Long = 600, env: List<String> = emptyList(), onLine: ((String) -> Unit)? = null): Pair<Int, String> {
        val out = StringBuilder()
        return try {
            val p = pb(c, listOf("/bin/bash", "-c", script, "lx") + args, env).start()
            val rd = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { ln ->
                        synchronized(out) { out.append(ln).append('\n'); if (out.length > 200_000) out.delete(0, out.length - 150_000) }
                        try { onLine?.invoke(ln) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!done) { p.destroyForcibly(); rd.join(2000); return Pair(-1, synchronized(out) { out.toString() }) }
            rd.join(3000)
            Pair(p.exitValue(), synchronized(out) { out.toString() })
        } catch (e: Throwable) { Pair(-2, "❌ " + (e.message ?: e.javaClass.simpleName)) }
    }

    /** نسخة async بنفس شكل TermuxRun (النتيجة على الـ main thread) — لأوبن كود وغيره */
    fun execAsync(c: Context, script: String, args: List<String>, timeoutSec: Long, onDone: (String) -> Unit) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val (code, text) = exec(c, script, args, timeoutSec)
            val res = text.trimEnd() + "\n[exit $code]"
            h.post { onDone(res) }
        }.apply { isDaemon = true }.start()
    }

    // ===== تحميل / فك =====
    private fun httpGet(url: String): HttpURLConnection {
        var u = url
        for (i in 0 until 6) {
            val cn = URL(u).openConnection() as HttpURLConnection
            cn.instanceFollowRedirects = false; cn.connectTimeout = 20000; cn.readTimeout = 45000
            cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Subtitler")
            val code = cn.responseCode
            if (code in 300..399) { u = URL(URL(u), cn.getHeaderField("Location") ?: break).toString(); cn.disconnect(); continue }
            if (code !in 200..299) { cn.disconnect(); throw java.io.IOException("HTTP $code من ${URL(u).host}") }
            return cn
        }
        throw java.io.IOException("تحويلات كتير")
    }

    private fun fetchText(url: String): String {
        val cn = httpGet(url)
        return try { cn.inputStream.bufferedReader().readText() } finally { cn.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins -> val b = ByteArray(65536); while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(url: String, dest: File, label: String, sha: String?) {
        var err: Throwable? = null
        for (attempt in 1..3) {
            try {
                dest.parentFile?.mkdirs()
                val part = File(dest.path + ".part"); part.delete()
                val cn = httpGet(url)
                val total = cn.contentLengthLong
                var got = 0L; var lastPct = -1
                cn.inputStream.use { ins -> FileOutputStream(part).use { os ->
                    val b = ByteArray(65536)
                    while (true) {
                        val n = ins.read(b); if (n < 0) break
                        os.write(b, 0, n); got += n
                        if (total > 0) {
                            val p = (got * 100 / total).toInt()
                            if (p != lastPct) { lastPct = p; stepSet("⬇ $label — ${got / 1048576}MB / ${total / 1048576}MB", p) }
                        } else stepSet("⬇ $label — ${got / 1048576}MB")
                    }
                } }
                cn.disconnect()
                if (sha != null) { stepSet("🔎 فحص سلامة $label…"); val h = sha256(part); if (!h.equals(sha, true)) throw java.io.IOException("الملف اتحمّل بس ناقص/تالف (checksum)") }
                dest.delete(); part.renameTo(dest)
                return
            } catch (e: Throwable) { err = e; say("⚠ محاولة $attempt فشلت ($label): " + (e.message ?: e.javaClass.simpleName)); Thread.sleep(2500) }
        }
        throw java.io.IOException("تحميل $label فشل: " + (err?.message ?: "؟"))
    }

    private class CountIn(i: InputStream) : FilterInputStream(i) {
        @Volatile var count = 0L
        override fun read(): Int { val r = super.read(); if (r >= 0) count++; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) count += r; return r }
    }

    private fun readFully(i: InputStream, b: ByteArray): Boolean {
        var o = 0
        while (o < b.size) { val n = i.read(b, o, b.size - o); if (n < 0) return false; o += n }
        return true
    }
    private fun skipFully(i: InputStream, n0: Long) { var n = n0; val buf = ByteArray(8192); while (n > 0) { val r = i.read(buf, 0, minOf(n, 8192L).toInt()); if (r < 0) return; n -= r } }
    private fun cstr(b: ByteArray, off: Int, len: Int): String { var e = off; while (e < off + len && b[e] != 0.toByte()) e++; return String(b, off, e - off, Charsets.UTF_8) }
    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) { var v = 0L; for (k in 1 until len) v = (v shl 8) or (b[off + k].toLong() and 0xff); return v }   // base-256
        var v = 0L
        for (k in off until off + len) { val ch = b[k].toInt(); if (ch in 48..55) v = v * 8 + (ch - 48) else if (v != 0L) break }
        return v
    }

    /** فك tar.gz بكود Java (من غير الاعتماد على tar بتاع أندرويد): ملفات + مجلدات + symlink + hardlink. [strip] عدد المستويات المحذوفة من الأول */
    private fun untar(tgz: File, dest: File, strip: Int, label: String) {
        dest.mkdirs()
        val total = tgz.length().coerceAtLeast(1)
        val cin = CountIn(FileInputStream(tgz))
        val ins = BufferedInputStream(GZIPInputStream(cin, 65536), 65536)
        val hdr = ByteArray(512)
        var longName: String? = null; var longLink: String? = null
        var paxPath: String? = null; var paxLink: String? = null
        var lastPct = -1; var zeros = 0; var files = 0
        fun resolve(raw: String): File? {
            var parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) return null
            if (strip > 0) parts = parts.drop(strip)
            if (parts.isEmpty()) return null
            return File(dest, parts.joinToString("/"))
        }
        while (true) {
            if (!readFully(ins, hdr)) break
            if (hdr.all { it == 0.toByte() }) { if (++zeros >= 2) break; continue }
            zeros = 0
            val pr = (cin.count * 100 / total).toInt()
            if (pr != lastPct) { lastPct = pr; stepSet("📦 فك $label — $pr% ($files ملف)", pr) }
            val name0 = cstr(hdr, 0, 100)
            val magic = cstr(hdr, 257, 6)
            val prefix = if (magic.startsWith("ustar")) cstr(hdr, 345, 155) else ""
            var name = if (prefix.isNotEmpty()) "$prefix/$name0" else name0
            val size = octal(hdr, 124, 12)
            val mode = octal(hdr, 100, 8).toInt()
            val type = hdr[156].toInt().toChar()
            var link = cstr(hdr, 157, 100)
            val padded = (size + 511) / 512 * 512
            when (type) {
                'L' -> { val b = ByteArray(size.toInt()); readExact(ins, b); longName = cstr(b, 0, b.size); skipFully(ins, padded - size); continue }
                'K' -> { val b = ByteArray(size.toInt()); readExact(ins, b); longLink = cstr(b, 0, b.size); skipFully(ins, padded - size); continue }
                'x' -> {
                    val b = ByteArray(size.toInt()); readExact(ins, b); skipFully(ins, padded - size)
                    val s = String(b, Charsets.UTF_8)
                    for (ln in s.split('\n')) { val kv = ln.substringAfter(' ', ""); if (kv.startsWith("path=")) paxPath = kv.substring(5) else if (kv.startsWith("linkpath=")) paxLink = kv.substring(9) }
                    continue
                }
                'g' -> { skipFully(ins, padded); continue }
            }
            if (longName != null) { name = longName!!; longName = null }
            if (paxPath != null) { name = paxPath!!; paxPath = null }
            if (longLink != null) { link = longLink!!; longLink = null }
            if (paxLink != null) { link = paxLink!!; paxLink = null }
            val out = resolve(name)
            try {
                when (type) {
                    '5' -> { if (out != null) { out.mkdirs(); try { Os.chmod(out.path, (mode and 0xFFF) or 0x1C0) } catch (_: Throwable) {} } }
                    '2' -> { if (out != null) { out.parentFile?.mkdirs(); try { out.delete() } catch (_: Throwable) {}; Os.symlink(link, out.path) } }
                    '1' -> {
                        if (out != null) {
                            out.parentFile?.mkdirs(); try { out.delete() } catch (_: Throwable) {}
                            val tgt = resolve(link)
                            if (tgt != null && tgt.exists()) { try { Os.link(tgt.path, out.path) } catch (_: Throwable) { tgt.copyTo(out, true) } }
                        }
                    }
                    '0', 0.toChar(), '7' -> {
                        if (out == null) { skipFully(ins, padded); continue }
                        out.parentFile?.mkdirs()
                        if (out.exists()) { try { out.delete() } catch (_: Throwable) {} }
                        var left = size
                        FileOutputStream(out).use { os -> val buf = ByteArray(65536); while (left > 0) { val n = ins.read(buf, 0, minOf(left, 65536L).toInt()); if (n < 0) break; os.write(buf, 0, n); left -= n } }
                        skipFully(ins, padded - size)
                        try { Os.chmod(out.path, (mode and 0xFFF) or 0x180) } catch (_: Throwable) {}
                        files++
                        continue
                    }
                }
            } catch (e: Throwable) { say("⚠ تخطّيت ${name.take(80)}: " + (e.message ?: e.javaClass.simpleName)) }
            if (type != '0' && type != 0.toChar() && type != '7') skipFully(ins, padded)
        }
        ins.close()
    }
    private fun readExact(i: InputStream, b: ByteArray) { var o = 0; while (o < b.size) { val n = i.read(b, o, b.size - o); if (n < 0) return; o += n } }

    // ===== خطوات التجهيز =====
    private fun stepRootfs(c: Context) {
        if (rootReady(c)) return
        val arch = if (isArm64()) "arm64" else "armhf"
        stepSet("🔎 بدوّر على Ubuntu rootfs…")
        val sums = fetchText(UBUNTU_DIR + "SHA256SUMS")
        val m = Regex("([0-9a-f]{64}) \\*?(ubuntu-base-[0-9.]+-base-$arch\\.tar\\.gz)").find(sums) ?: throw java.io.IOException("مالقيتش ملف ubuntu-base ($arch) في القايمة")
        val f = File(baseDir(c), m.groupValues[2])
        say("⬇ ${m.groupValues[2]}")
        download(UBUNTU_DIR + m.groupValues[2], f, "Ubuntu", m.groupValues[1])
        rootfs(c).deleteRecursively()
        untar(f, rootfs(c), 0, "Ubuntu")
        f.delete()
        if (!rootReady(c)) throw java.io.IOException("فك Ubuntu خلص بس bash مش موجود")
        say("✅ Ubuntu اتفك")
    }

    private fun stepConfig(c: Context) {
        val r = rootfs(c)
        File(r, "etc").mkdirs()
        File(r, "etc/resolv.conf").delete(); File(r, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(r, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(r, "etc/apt/apt.conf.d").mkdirs()
        File(r, "etc/apt/apt.conf.d/99proot").writeText("APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDpkg::Options { \"--force-unsafe-io\"; };\n")
        File(r, "usr/sbin").mkdirs()
        File(r, "usr/sbin/policy-rc.d").apply { writeText("#!/bin/sh\nexit 101\n"); setExecutable(true, false) }
        File(r, "tmp").mkdirs(); File(r, "root").mkdirs()
        try { Os.chmod(File(r, "tmp").path, 0x1FF) } catch (_: Throwable) {}
    }

    private fun stepSelfTest(c: Context) {
        stepSet("🧪 بجرّب proot…")
        var (code, out) = exec(c, "echo LX_OK; uname -m; id -u", timeoutSec = 90)
        if (!out.contains("LX_OK") && Cfg.isReady() && Cfg.str("linux_noseccomp") != "1") {
            say("⚠ proot فشل بالوضع السريع — بجرّب من غير seccomp (أبطأ بس أضمن)\n" + out.takeLast(300))
            Cfg.put("linux_noseccomp", "1")
            val r2 = exec(c, "echo LX_OK; uname -m; id -u", timeoutSec = 90); code = r2.first; out = r2.second
        }
        if (!out.contains("LX_OK")) throw java.io.IOException("proot مش شغّال على الجهاز ده (exit $code): " + out.takeLast(300))
        say("✅ proot شغّال: " + out.lines().filter { it.isNotBlank() && it != "LX_OK" }.joinToString(" / "))
    }

    private fun stepNode(c: Context) {
        if (nodeReady(c)) return
        val arch = if (isArm64()) "arm64" else "armv7l"
        stepSet("🔎 بدوّر على Node.js 22…")
        val sums = fetchText(NODE_DIR + "SHASUMS256.txt")
        val m = Regex("([0-9a-f]{64})\\s+(node-v[0-9.]+-linux-$arch\\.tar\\.gz)").find(sums) ?: throw java.io.IOException("مالقيتش Node لمعمارية $arch")
        val f = File(baseDir(c), m.groupValues[2])
        say("⬇ ${m.groupValues[2]}")
        download(NODE_DIR + m.groupValues[2], f, "Node.js", m.groupValues[1])
        untar(f, File(rootfs(c), "usr/local"), 1, "Node.js")
        f.delete()
        if (!nodeReady(c)) throw java.io.IOException("فك Node خلص بس node مش موجود")
        val v = exec(c, "node --version && npm --version", timeoutSec = 90).second.trim()
        say("✅ Node: " + v.replace('\n', ' '))
    }

    private fun stepApt(c: Context) {
        if (File(baseDir(c), "apt.ok").exists()) return
        stepSet("📦 أدوات النظام (curl, git, شهادات)…")
        val (code, out) = exec(c, "export DEBIAN_FRONTEND=noninteractive; apt-get update -y 2>&1 | tail -n 3; apt-get install -y --no-install-recommends ca-certificates curl git 2>&1 | tail -n 4", timeoutSec = 900) { ln -> if (ln.isNotBlank()) stepSet("📦 apt: " + ln.take(70)) }
        say(out.takeLast(300))
        if (code == 0) File(baseDir(c), "apt.ok").writeText("1") else say("⚠ apt ما خلصش (مش ضروري لأومني روت) — هكمّل")
    }

    private fun npmInstall(c: Context, pkg: String, label: String) {
        val t0 = System.currentTimeMillis()
        val tick = Thread {
            try { while (true) { Thread.sleep(15000); stepSet("⬇ تثبيت $label — ${(System.currentTimeMillis() - t0) / 60000} دقيقة (ممكن 10-25 دقيقة أول مرة)") } } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        try {
            stepSet("⬇ تثبيت $label…")
            val (code, out) = exec(c, "npm install -g $pkg --omit=optional --no-audit --no-fund --loglevel=error 2>&1", timeoutSec = 3600)
            say("npm ($label) exit $code\n" + out.takeLast(600))
        } finally { tick.interrupt() }
    }

    private fun stepOmni(c: Context) {
        if (omniInstalled(c)) return
        npmInstall(c, "omniroute@latest", "OmniRoute")
        if (!omniInstalled(c)) throw java.io.IOException("تثبيت OmniRoute ما نجحش — شوف لوج التجهيز")
        say("✅ OmniRoute اتثبّت")
    }

    // ===== السيرفر =====
    @Volatile private var upCache = false
    fun health(): Int {
        val code = try {
            val cn = URL("http://127.0.0.1:$PORT/api/health").openConnection() as HttpURLConnection
            try { cn.connectTimeout = 1500; cn.readTimeout = 3000; cn.responseCode } finally { cn.disconnect() }
        } catch (_: Throwable) { 0 }
        upCache = code == 200
        return code
    }
    fun isUp(): Boolean = health() == 200
    fun isUpCached(): Boolean = upCache
    fun serverRunning(): Boolean = proc?.isAlive == true

    private fun dashPw(): String {
        var p = if (Cfg.isReady()) Cfg.str("omni_pw") else ""
        if (p.isBlank()) { p = java.util.UUID.randomUUID().toString().replace("-", "").take(20); if (Cfg.isReady()) Cfg.put("omni_pw", p) }
        return p
    }

    private fun stepServer(c: Context) {
        if (isUp()) return
        synchronized(lock) {
            if (proc?.isAlive != true) {
                stepSet("▶ بشغّل OmniRoute…")
                val logF = File(rootfs(c), "root/omniroute.log")
                if (logF.length() > 2_000_000) logF.delete()
                val env = listOf("REQUIRE_API_KEY=false", "PORT=$PORT", "OMNIROUTE_SERVER_HOST=127.0.0.1", "INITIAL_PASSWORD=${dashPw()}", "NODE_ENV=production")
                val p = pb(c, listOf("/bin/bash", "-c", "cd /root && exec omniroute"), env).start()
                proc = p
                Thread {
                    try {
                        p.inputStream.bufferedReader().forEachLine { ln -> srvSay(ln.replace(Regex("\u001B\\[[0-9;]*m"), "")); try { logF.appendText(ln + "\n") } catch (_: Throwable) {} }
                    } catch (_: Throwable) {}
                    val code = try { p.waitFor() } catch (_: Throwable) { -1 }
                    srvSay("— السيرفر وقف (exit $code) —"); say("⏹ OmniRoute وقف (exit $code)")
                    if (proc === p) proc = null
                }.apply { isDaemon = true; start() }
            }
        }
        val end = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < end) {
            if (isUp()) { say("✅ OmniRoute شغّال على 127.0.0.1:$PORT"); OmniAuto.last = OmniAuto.St.NEED_KEY; return }
            if (proc?.isAlive != true) throw java.io.IOException("OmniRoute وقع أول ما اشتغل — شوف «لوج السيرفر»:\n" + serverLog().takeLast(500))
            Thread.sleep(1500)
        }
        throw java.io.IOException("OmniRoute ما ردّش خلال 3 دقايق — شوف «لوج السيرفر»")
    }

    fun stopServer() {
        synchronized(lock) { try { proc?.destroy(); Thread.sleep(300); proc?.destroyForcibly() } catch (_: Throwable) {}; proc = null }
        OmniAuto.last = null
    }

    // ===== المفتاح (تلقائي) =====
    private fun modelsCode(key: String): Int = try {
        val cn = URL("http://127.0.0.1:$PORT/v1/models").openConnection() as HttpURLConnection
        try { cn.connectTimeout = 2000; cn.readTimeout = 8000; if (key.isNotEmpty()) cn.setRequestProperty("Authorization", "Bearer $key"); cn.responseCode } finally { cn.disconnect() }
    } catch (_: Throwable) { 0 }

    private fun post(path: String, body: String, cookie: String?): Triple<Int, String, String?> {
        val cn = URL("http://127.0.0.1:$PORT$path").openConnection() as HttpURLConnection
        try {
            cn.requestMethod = "POST"; cn.connectTimeout = 3000; cn.readTimeout = 15000; cn.doOutput = true
            cn.setRequestProperty("Content-Type", "application/json")
            if (cookie != null) cn.setRequestProperty("Cookie", cookie)
            cn.outputStream.use { it.write(body.toByteArray()) }
            val code = cn.responseCode
            val txt = (if (code in 200..299) cn.inputStream else cn.errorStream)?.bufferedReader()?.readText().orEmpty()
            val ck = cn.headerFields["Set-Cookie"]?.firstOrNull { it.startsWith("auth_token=") }?.substringBefore(';')
            return Triple(code, txt, ck)
        } finally { cn.disconnect() }
    }

    /** بيعمل Endpoint key لو مفيش مفتاح شغّال محفوظ. بيرجّع رسالة النتيجة (null = تمام) */
    fun ensureKey(): String? {
        val cur = Cfg.str("llm_omniroute_key").trim()
        if (cur.isNotEmpty() && modelsCode(cur) == 200) return null
        if (modelsCode("") == 200) return null   // السيرفر مش بيطلب مفتاح أصلاً
        var cookie: String? = null
        for (pw in listOf(dashPw(), "CHANGEME")) {
            val r = try { post("/api/auth/login", JSONObject().put("password", pw).toString(), null) } catch (e: Throwable) { return "تسجيل الدخول للوحة فشل: " + (e.message ?: "") }
            if (r.first in 200..299 && r.third != null) { cookie = r.third; break }
        }
        if (cookie == null) return "كلمة سر لوحة OmniRoute مش معروفة (الداتا قديمة). جرّب في الطرفية: omniroute-reset-password"
        val k = try { post("/api/keys", JSONObject().put("name", "subtitler-" + (System.currentTimeMillis() / 1000)).toString(), cookie) } catch (e: Throwable) { return "إنشاء المفتاح فشل: " + (e.message ?: "") }
        val key = try { JSONObject(k.second).optString("key") } catch (_: Throwable) { "" }
        if (k.first !in 200..299 || key.isBlank()) return "إنشاء المفتاح رفضه السيرفر (HTTP ${k.first}): " + k.second.take(150)
        Cfg.put("llm_omniroute_key", key)
        val mc = modelsCode(key)
        if (mc != 200) return "المفتاح اتعمل بس القايمة رجّعت HTTP $mc"
        OmniAuto.last = OmniAuto.St.UP
        return null
    }

    /** اختبار حقيقي: عدد الموديلات + طلب شات لموديل oc/ (أوبن كود المجاني عن طريق أومني روت) */
    fun selfCheck(say: (String) -> Unit) {
        val key = Cfg.str("llm_omniroute_key").trim()
        say("🩺 health: HTTP " + health())
        val cn = try { URL("http://127.0.0.1:$PORT/v1/models").openConnection() as HttpURLConnection } catch (_: Throwable) { return }
        var ids = emptyList<String>()
        try {
            cn.connectTimeout = 2000; cn.readTimeout = 10000
            if (key.isNotEmpty()) cn.setRequestProperty("Authorization", "Bearer $key")
            val code = cn.responseCode
            if (code == 200) { val arr = JSONObject(cn.inputStream.bufferedReader().readText()).optJSONArray("data"); if (arr != null) ids = (0 until arr.length()).map { arr.getJSONObject(it).optString("id") } }
            say("📚 /v1/models: HTTP $code · ${ids.size} موديل")
        } catch (e: Throwable) { say("❌ /v1/models: " + (e.message ?: "")) } finally { cn.disconnect() }
        val oc = ids.filter { it.startsWith("oc/") }
        say("🆓 موديلات أوبن كود (oc/): " + (if (oc.isEmpty()) "مفيش" else oc.joinToString(", ")))
        val pick = oc.firstOrNull { it.contains("free") } ?: oc.firstOrNull()
        if (pick != null) {
            val t0 = System.currentTimeMillis()
            try {
                val r = post("/v1/chat/completions", JSONObject().put("model", pick).put("max_tokens", 20).put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "Say hi in one short sentence."))).toString(), null)
                val sec = (System.currentTimeMillis() - t0) / 1000.0
                say(if (r.first in 200..299) "✅ شات $pick: " + (try { JSONObject(r.second).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").take(120) } catch (_: Throwable) { r.second.take(120) }) + " (${"%.1f".format(sec)}ث)" else "❌ شات $pick: HTTP ${r.first} — " + r.second.take(260))
            } catch (e: Throwable) { say("❌ شات $pick: " + (e.message ?: "")) }
        }
    }

    // ===== OpenCode CLI (اختياري) =====
    fun installOpenCode(c: Context, onLine: (String) -> Unit) {
        if (opencodeInstalled(c)) { onLine("OpenCode CLI متثبّت"); return }
        npmInstall(c, "opencode-ai@latest", "OpenCode CLI")
        onLine(if (opencodeInstalled(c)) "✅ OpenCode CLI اتثبّت" else "❌ OpenCode CLI ما اتثبّتش — شوف لوج التجهيز")
    }

    // ===== التشغيل الكامل =====
    /** تجهيز كل حاجة (idempotent). بيتنادى من thread خلفية فقط. */
    fun ensureAll(c0: Context): Boolean {
        val c = c0.applicationContext
        if (!supported(c)) { lastError = "الجهاز مش مدعوم (مفيش proot لمعماريته)"; return false }
        synchronized(this) { if (busy) return false; busy = true }
        lastError = ""
        try {
            LinuxService.start(c)
            if (!ready(c)) say("— بدء تجهيز البيئة الداخلية —")
            stepRootfs(c); stepConfig(c); stepSelfTest(c); stepNode(c); stepApt(c); stepOmni(c)
            stepServer(c)
            stepSet("🔑 بعمل مفتاح OmniRoute…")
            val ke = ensureKey()
            if (ke != null) { lastError = ke; say("⚠ $ke") } else say("✅ المفتاح جاهز ومحفوظ — OmniRoute جاهز للترجمة")
            stepSet(if (ke == null) "✅ جاهز" else "⚠ شغّال بس: $ke", if (ke == null) 100 else -1)
            return ke == null
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            say("❌ $lastError"); stepSet("❌ $lastError")
            NoticeLog.add("🐧 تجهيز البيئة الداخلية فشل: " + lastError.take(120))
            return false
        } finally { busy = false; LinuxService.refresh(c) }
    }

    fun setupAsync(c: Context, then: ((Boolean) -> Unit)? = null) {
        optIn()
        val a = c.applicationContext
        Thread { val ok = ensureAll(a); then?.invoke(ok) }.apply { isDaemon = true }.start()
    }

    /** بيتنادى لما التطبيق يفتح: لو المستخدم وافق قبل كده، يكمّل/يشغّل السيرفر لوحده */
    fun onAppStart(c: Context) {
        if (!supported(c) || !optedIn() || busy) return
        if (Cfg.str("omni_auto", "on") == "off") return
        setupAsync(c)
    }

    /** للـ AiHub/AiDiag: بيشغّل السيرفر لو واقف. بيرجّع true لو بدأ شغل في الخلفية */
    fun autoStart(c: Context): Boolean {
        if (!supported(c) || busy) return false
        if (!ready(c) && !optedIn()) return false
        if (isUp() && ready(c)) return false
        setupAsync(c); return true
    }

    fun summary(c: Context): String {
        val sb = StringBuilder()
        sb.append(if (supported(c)) "🐧 البيئة الداخلية: مدعومة ✅" else "🐧 البيئة الداخلية: غير مدعومة ❌ (مفيش proot للمعمارية)").append('\n')
        sb.append("  • Ubuntu: ").append(if (rootReady(c)) "✅" else "❌ لسه").append(" · Node: ").append(if (nodeReady(c)) "✅" else "❌").append(" · OmniRoute: ").append(if (omniInstalled(c)) "✅" else "❌").append(" · OpenCode CLI: ").append(if (opencodeInstalled(c)) "✅" else "—").append('\n')
        sb.append("  • السيرفر: ").append(if (isUp()) "شغّال ✅" else if (serverRunning()) "بيشتغل…" else "واقف ❌")
        if (busy) sb.append("\n  • شغّال دلوقتي: ").append(step)
        if (lastError.isNotBlank()) sb.append("\n  • آخر خطأ: ").append(lastError.take(200))
        return sb.toString()
    }

    // ===== طرفية تفاعلية =====
    fun shellEnv(c: Context): Array<String> = prootEnv(c).map { "${it.key}=${it.value}" }.toTypedArray()

    /** بيرجّع جلسة الطرفية الحالية لو لسه شغّالة، وإلا بيعمل واحدة جديدة */
    @Synchronized fun shellSession(c: Context, client: TerminalSessionClient): TerminalSession {
        val old = shell
        if (old != null && old.isRunning) { old.updateTerminalSessionClient(client); return old }
        val argv = prootArgv(c, listOf("/bin/bash", "-l"))
        val s = TerminalSession(argv[0], baseDir(c).path, argv.toTypedArray(), shellEnv(c), 4000, client)
        shell = s
        return s
    }
    @Synchronized fun killShell() { try { shell?.finishIfRunning() } catch (_: Throwable) {}; shell = null }

    /** أمر يتكتب في الطرفية (مثلاً من زرار) */
    fun typeInShell(cmd: String) {
        val s = shell ?: return
        if (!s.isRunning) return
        val b = (cmd + "\n").toByteArray()
        s.write(b, 0, b.size)
    }

    fun wipe(c: Context) { stopServer(); killShell(); baseDir(c).deleteRecursively(); if (Cfg.isReady()) { Cfg.put("llm_omniroute_key", ""); Cfg.put("linux_optin", "0") } }
}
