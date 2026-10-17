package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * (v387) تجهيز أوبن كود تلقائي من أول ما تفتح البرنامج — كله في الخلفية:
 *  فحص Termux ← تثبيت OpenCode لو ناقص ← تجربة طلب حقيقي ← لو فشل: بنبعت الخطأ لجيميناي ويقترح أمر إصلاح، بنشغّله (بعد فلتر أمان)،
 *  ونجرّب تاني (لحد [MAX_FIX] مرات) ← لما يشتغل: بنجيب قايمة الموديلات المجانية ونضيفها لقايمة التطبيق ونطلّعلك إشعار (اتضافت / ما اتضافتش).
 * اللي مش بيتأتمت: تسطيب Termux نفسه، سطر allow-external-apps (بيتنسخ ويفتح Termux)، وإذن RUN_COMMAND.
 */
object OpenCodeAuto {
    enum class St { IDLE, RUNNING, READY, FAILED, MANUAL }
    @Volatile var state = St.IDLE
    @Volatile private var running = false
    private var host: WeakReference<Activity>? = null
    private val logSb = StringBuilder()
    private const val MAX_FIX = 5
    private const val FRESH_MS = 12L * 3600_000L      // لو اشتغل من أقل من 12 ساعة مش بنعيد الفحص
    private const val RETRY_MS = 30L * 60_000L        // بعد فشل/تدخل يدوي مش بنزعجك قبل نص ساعة (إلا بالزرار)

    @Synchronized fun logText(): String = logSb.toString().ifBlank { "مفيش لوج لسه" }
    @Synchronized private fun say(s: String) { logSb.append(s).append('\n'); if (logSb.length > 12000) logSb.delete(0, logSb.length - 9000) }

    private fun notify(msg: String) {
        val a = host?.get()
        if (a != null && !a.isFinishing) a.runOnUiThread { try { Notice.show(a, msg, 5000L) } catch (_: Throwable) {} } else NoticeLog.add(msg)
    }

    private fun sd(s: String) = s.replace('§', '$')

    private val CHECK = sd("""
B="§HOME/.local/bin/opencode"
if [ -x "§B" ]; then echo "OC_INSTALLED §("§B" --version 2>&1 | head -n1)"; else echo OC_MISSING; fi
command -v curl >/dev/null 2>&1 && echo HAS_CURL || echo NO_CURL
""".trimIndent())

    private val AUTH_WRITE = sd("""
D="§HOME/.local/share/opencode"; mkdir -p "§D"; F="§D/auth.json"
if [ -f "§F" ]; then echo AUTH_EXISTS; exit 0; fi
printf '{"opencode":{"type":"api","key":"%s"},"opencode-go":{"type":"api","key":"%s"}}' "§1" "§1" > "§F"; chmod 600 "§F"; echo AUTH_WRITTEN
""".trimIndent())

    // ===== فلتر الأمان لأوامر جيميناي =====
    private val ALLOWED = setOf("pkg", "apt", "curl", "tar", "mkdir", "chmod", "mv", "cp", "rm", "ls", "echo", "test", "[", "export", "command", "uname",
        "sh", "bash", "opencode", "termux-reload-settings", "ln", "touch", "pwd", "cd", "true", "head", "tail", "cat", "df")
    private val HOSTS = setOf("github.com", "raw.githubusercontent.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "opencode.ai", "registry.npmjs.org")
    private val RM_OK = listOf("\$HOME/.local/bin/opencode", "~/.local/bin/opencode", "\$HOME/oc-work", "~/oc-work", "\$HOME/.cache", "~/.cache", "\$HOME/opencode", "~/opencode")

    /** null = الأمر سليم، غير كده سبب الرفض */
    fun safe(cmd: String): String? {
        if (cmd.isBlank()) return "فاضي"
        if (cmd.length > 1200) return "طويل أوي"
        val low = cmd.lowercase()
        for (bad in listOf("sudo", "\$(", "`", "eval ", "base64", "/dev/tcp", "auth.json", "/sdcard", "storage/", "/system", "<(", ">(", "ssh ", "scp ", "nc ", "termux-sms", "termux-call", "termux-contact", "termux-clipboard", "termux-camera", "termux-location"))
            if (low.contains(bad)) return "ممنوع: $bad"
        Regex("https?://[^\\s\"']+").findAll(cmd).forEach { m ->
            val h = m.value.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            if (h !in HOSTS) return "رابط غير مسموح: $h"
        }
        val stripped = cmd.replace("2>&1", "").replace(">/dev/null", "").replace("2>/dev/null", "")
        if (stripped.contains('>')) return "إعادة توجيه غير مسموحة"
        for (seg in cmd.split(Regex("&&|\\|\\||;|\\||\\n"))) {
            val t = seg.trim(); if (t.isEmpty()) continue
            val toks = t.split(Regex("\\s+"))
            var w = toks[0].removePrefix("\$HOME/.local/bin/").removePrefix("~/.local/bin/").removePrefix("/data/data/com.termux/files/usr/bin/")
            if (w !in ALLOWED) return "أمر غير مسموح: $w"
            if ((w == "sh" || w == "bash") && toks.any { it == "-c" }) return "sh -c ممنوع"
            if (w == "rm") {
                val targets = toks.drop(1).filter { !it.startsWith("-") }
                if (targets.isEmpty() || targets.any { tg -> tg.contains("..") || RM_OK.none { tg.startsWith(it) } }) return "rm برا المسموح"
            }
        }
        return null
    }

    // ===== أدوات =====
    /** بيشغّل سكربت في Termux وبيستنى. null = مفيش رد في المهلة */
    private fun sh(c: Context, script: String, args: List<String> = emptyList(), waitSec: Long): String? {
        val latch = CountDownLatch(1); val res = arrayOfNulls<String>(1)
        val r = TermuxRun.runScript(c, script, args) { res[0] = it; latch.countDown() }
        if (r != OmniLauncher.R.SENT) return "⚠ " + r.name
        return if (latch.await(waitSec, TimeUnit.SECONDS)) res[0] else null
    }

    /** null = شغّال، غير كده نص الخطأ */
    private fun probe(): String? = try {
        val r = OpenCodeLocal.ask("", "Reply with exactly: OK", listOf(AiApi.Msg("user", "ping")))
        if (r.isNotBlank()) null else "رد فاضي"
    } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }

    private fun gemini(prompt: String): JSONObject? {
        for (k in SongId.textKeys().take(3)) {
            try {
                val t = Api.generate(Models.DEFAULT, k, prompt, null, 700, 0.0, true, false).text
                val a = t.indexOf('{'); val b = t.lastIndexOf('}')
                if (a >= 0 && b > a) return JSONObject(t.substring(a, b + 1))
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun fixPrompt(err: String, tried: List<String>): String =
        "You are a repair agent for Termux on Android (aarch64, bionic libc, no root). Goal: make this command work:\n" +
        "`\$HOME/.local/bin/opencode run --format json \"hi\"` (OpenCode CLI v2.0.28, installed by: curl -fsSL https://github.com/Spectra010s/opencode-termux/raw/v2.0.28/install.sh | sh).\n" +
        "Latest failure output:\n<<<\n" + err.takeLast(1500) + "\n>>>\n" +
        "Fixes already tried (do NOT repeat them):\n" + (if (tried.isEmpty()) "(none)" else tried.joinToString("\n") { "- " + it.take(200) }) + "\n" +
        "Reply with ONE JSON object only, no markdown: {\"cmd\":\"<a single bash command line that fixes it>\",\"why\":\"<short reason in Egyptian Arabic>\"}\n" +
        "or {\"give_up\":true,\"why\":\"<short reason in Egyptian Arabic>\"} if it needs a human (login, no internet, no storage, unsupported device).\n" +
        "Rules: Termux commands only (pkg, curl, tar, mkdir, chmod, mv, cp, rm of opencode files only, termux-reload-settings, opencode). Download only from github.com, raw.githubusercontent.com, opencode.ai, registry.npmjs.org. Never sudo, never read credentials."

    private fun done(st: St, msg: String, ok: Boolean) {
        state = st
        if (!ok) Cfg.put("oc_fail_at", System.currentTimeMillis().toString())
        say((if (ok) "✅ " else "❌ ") + msg)
        notify((if (ok) "✅ " else "❌ ") + msg)
    }
    private fun fail(msg: String) = done(St.FAILED, "أوبن كود ما اتضافش: $msg", false)
    private fun manual(msg: String) = done(St.MANUAL, "أوبن كود محتاج خطوة منك: $msg", false)

    // ===== التشغيل =====
    private fun noTermux() {
        state = St.MANUAL
        say("📱 Termux مش متسطب — أوبن كود المجاني (وأومني روت المحلي) محتاجينه")
        val now = System.currentTimeMillis()
        if (now - (Cfg.str("oc_nt_at").toLongOrNull() ?: 0L) > 24L * 3600_000L) {
            Cfg.put("oc_nt_at", now.toString())
            notify("🆓 أوبن كود المجاني محتاج Termux (مش متسطب). افتح 🤖 ← 🩺 فحص شامل للتفاصيل")
        }
    }

    /** بيتنادى من Main عند الفتح. مش بيبلوك. [force] من زرار الكونسول: يتخطّى فترات الانتظار */
    fun start(act: Activity, force: Boolean = false) {
        host = WeakReference(act)
        if (!Cfg.isReady() || Cfg.str("oc_auto", "on") == "off") return
        val c = act.applicationContext
        if (Linux.supported(c)) { startEmbedded(act, c, force); return }   // (v394) البيئة الداخلية بدل Termux
        if (!OmniLauncher.installed(c)) { noTermux(); return }   // (v393) كان بيخرج صامت — دلوقتي لوج + إشعار مرة كل 24 ساعة
        if (running) return
        val now = System.currentTimeMillis()
        if (!force) {
            if ((Cfg.str("oc_ready_at").toLongOrNull() ?: 0L) > now - FRESH_MS && Cfg.str("oc_models").isNotBlank()) { state = St.READY; return }
            if ((Cfg.str("oc_fail_at").toLongOrNull() ?: 0L) > now - RETRY_MS) return
        }
        running = true; state = St.RUNNING
        Thread { try { work(act, c) } catch (e: Throwable) { fail("خطأ غير متوقع: " + (e.message ?: e.javaClass.simpleName)) } finally { running = false } }.apply { isDaemon = true }.start()
    }

    /** (v394) نفس الفكرة على البيئة الداخلية: ننتظر لحد ما أومني روت/Node يجهزوا، نثبّت opencode (npm)، نجرّب، ونجيب الموديلات */
    private fun startEmbedded(act: Activity, c: Context, force: Boolean) {
        if (running) return
        if (!Linux.optedIn() && !force) return
        if (Linux.busy || !(Linux.rootReady(c) && Linux.nodeReady(c))) { state = St.MANUAL; say("⏳ البيئة الداخلية لسه بتتجهّز — هكمّل أوبن كود بعدها"); return }
        val now = System.currentTimeMillis()
        if (!force) {
            if ((Cfg.str("oc_ready_at").toLongOrNull() ?: 0L) > now - FRESH_MS && Cfg.str("oc_models").isNotBlank()) { state = St.READY; return }
            if ((Cfg.str("oc_fail_at").toLongOrNull() ?: 0L) > now - RETRY_MS) return
        }
        running = true; state = St.RUNNING
        Thread { try { workEmbedded(c) } catch (e: Throwable) { fail("خطأ غير متوقع: " + (e.message ?: e.javaClass.simpleName)) } finally { running = false } }.apply { isDaemon = true }.start()
    }

    private fun workEmbedded(c: Context) {
        say("— بدء تجهيز أوبن كود (البيئة الداخلية) —")
        if (!Linux.opencodeInstalled(c)) {
            say("⬇ بثبّت OpenCode CLI عن طريق npm (دقايق)…")
            Linux.installOpenCode(c) { say(it) }
            if (!Linux.opencodeInstalled(c)) return fail("تثبيت OpenCode CLI فشل — شوف لوج التجهيز في كونسول Linux")
        }
        val err = probe()
        if (err != null) {
            say("❌ التجربة فشلت: " + err.take(300))
            return if (Regex("auth|login|unauthor|credential|401", RegexOption.IGNORE_CASE).containsMatchIn(err)) manual("OpenCode محتاج تسجيل دخول مرة واحدة: افتح كونسول Linux واكتب opencode auth login")
                   else fail("آخر خطأ: " + err.take(140))
        }
        say("✅ الاتصال شغّال — بجيب قايمة الموديلات")
        val ml = Linux.exec(c, "opencode models 2>&1", timeoutSec = 90).second
        val ids = ml.lines().map { it.trim() }.filter { it.startsWith("opencode/") }.map { it.removePrefix("opencode/") }.filter { OpenCodeFree.isFree(it) }.distinct()
        if (ids.isEmpty()) return fail("الاتصال شغّال بس قايمة الموديلات المجانية طلعت فاضية")
        Cfg.put("oc_models", ids.joinToString(","))
        Cfg.put("oc_ready_at", System.currentTimeMillis().toString())
        Cfg.put("oc_fail_at", "0")
        OpenCodeFree.resetCool()
        done(St.READY, "أوبن كود جاهز — اتضافت ${ids.size} موديل مجاني للقايمة", true)
    }

    private fun work(act: Activity, c: Context) {
        say("— بدء تجهيز أوبن كود —")
        if (!OmniLauncher.hasPerm(c)) {
            act.runOnUiThread { try { OmniLauncher.requestPerm(act) } catch (_: Throwable) {} }
            return manual("وافق على إذن RUN_COMMAND اللي ظهر وافتح البرنامج تاني")
        }
        val chk = sh(c, CHECK, waitSec = 30)
        if (chk == null) {
            act.runOnUiThread { try { OmniLauncher.copySetupAndOpenTermux(act) } catch (_: Throwable) {} }
            return manual("Termux مردّش — الصق أمر الإعداد (اتنسخ) في Termux واضغط Enter مرة واحدة وارجع")
        }
        say(chk.takeLast(300))
        if (!chk.contains("OC_INSTALLED")) {
            say("⬇ بثبّت OpenCode (3-5 دقايق أول مرة)…")
            val r = sh(c, OpenCodeLocal.INSTALL, waitSec = 900)
            say((r ?: "(مهلة التثبيت)").takeLast(400))
        }
        var err = probe()
        // تسجيل الدخول: لو الخطأ شكله مفتاح/دخول وعندك مفتاح محفوظ، نكتب auth.json (لو مش موجود) — ده تخمين لصيغة الملف
        if (err != null && Regex("auth|login|unauthor|api key|credential|401", RegexOption.IGNORE_CASE).containsMatchIn(err)) {
            val key = Cfg.str("llm_opencode_key").trim()
            if (key.isNotEmpty()) { say("🔑 " + (sh(c, AUTH_WRITE, listOf(key), 30) ?: "").takeLast(60)); err = probe() }
        }
        val tried = ArrayList<String>()
        var n = 0
        while (err != null && n < MAX_FIX) {
            if (err.contains("RUN_COMMAND") || err.contains("Termux مش متسطب")) return manual(err.removePrefix(OpenCodeLocal.FATAL).trim())
            n++
            say("❌ فشل: " + err.take(300))
            val j = gemini(fixPrompt(err, tried)) ?: return fail("جيميناي مردّش (مفيش مفتاح أو الكوتة خلصت) — آخر خطأ: " + err.take(120))
            if (j.optBoolean("give_up")) return manual(j.optString("why", "محتاج تدخل يدوي"))
            val cmd = j.optString("cmd").trim()
            val bad = safe(cmd)
            if (bad != null) { say("🚫 أمر مرفوض ($bad): " + cmd.take(160)); tried.add("REJECTED: $cmd"); continue }
            say("🔧 [$n] " + j.optString("why") + "\n   \$ " + cmd.take(300))
            val out = sh(c, cmd, waitSec = 600) ?: "(مهلة)"
            say(out.takeLast(400)); tried.add(cmd)
            err = probe()
        }
        if (err != null) return fail("اتجرّب $n إصلاحات ومنفعش — آخر خطأ: " + err.take(120))

        say("✅ الاتصال شغّال — بجيب قايمة الموديلات")
        val ml = sh(c, sd("§HOME/.local/bin/opencode models 2>&1"), waitSec = 90).orEmpty()
        val ids = ml.lines().map { it.trim() }.filter { it.startsWith("opencode/") }.map { it.removePrefix("opencode/") }.filter { OpenCodeFree.isFree(it) }.distinct()
        if (ids.isEmpty()) return fail("الاتصال شغّال بس قايمة الموديلات المجانية طلعت فاضية")
        Cfg.put("oc_models", ids.joinToString(","))
        Cfg.put("oc_ready_at", System.currentTimeMillis().toString())
        Cfg.put("oc_fail_at", "0")
        OpenCodeFree.resetCool()
        done(St.READY, "أوبن كود جاهز — اتضافت ${ids.size} موديل مجاني للقايمة (ابدأ الترجمة منه)", true)
    }
}
