package com.tttt.subtitler

import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * (v386) 🆓 أوبن كود المحلي — الفري بيشتغل بس من جوه أداة OpenCode نفسها (FreeTierError لو الطلب جاي من تطبيق خارجي)،
 * فالتطبيق بيبعت كل طلب لـ Termux: `opencode run --format json -m opencode/<model> "<prompt>"` ويقرأ الرد من سطور الـ JSON.
 *  - التثبيت (مرة واحدة): [install] — نفس أمر curl اللي اشتغل يدويًا (Spectra010s/opencode-termux v2.0.28).
 *  - تسجيل الدخول `opencode auth login` بتعمله إنت مرة واحدة في Termux (مش بنأتمته).
 *  - مفيش PATH: المسار الكامل لـ opencode.
 */
object OpenCodeLocal {
    private const val HOME = "/data/data/com.termux/files/home"
    const val BIN = "$HOME/.local/bin/opencode"
    private const val INSTALL_URL = "https://github.com/Spectra010s/opencode-termux/raw/v2.0.28/install.sh"
    const val FATAL = "⛔"   // رسالة تبدأ بالرمز ده = إعداد ناقص، مفيش فايدة نجرّب موديل تاني

    /** طلبين بالتوازي بالكتير — كل طلب بيشغّل برنامج bun تقيل */
    private val gate = Semaphore(2)

    private val RUN = """
mkdir -p "${'$'}HOME/oc-work" && cd "${'$'}HOME/oc-work" || exit 98
[ -x "${'$'}HOME/.local/bin/opencode" ] || { echo OC_NOT_INSTALLED; exit 97; }
if [ -n "${'$'}1" ]; then exec timeout 105 "${'$'}HOME/.local/bin/opencode" run --format json -m "${'$'}1" "${'$'}2"; fi
exec timeout 105 "${'$'}HOME/.local/bin/opencode" run --format json "${'$'}2"
""".trimIndent()

    val INSTALL = """
if [ -x "${'$'}HOME/.local/bin/opencode" ]; then echo OC_OK; exit 0; fi
pkg install -y curl tar 2>&1
curl -fsSL $INSTALL_URL | sh 2>&1
if [ -x "${'$'}HOME/.local/bin/opencode" ]; then echo OC_OK; else echo OC_FAIL; fi
""".trimIndent()

    // (v394) نسخة البيئة الداخلية (Ubuntu جوه التطبيق): opencode من npm في /usr/local/bin، والمنزل /root
    private val RUN_G = """
mkdir -p /root/oc-work && cd /root/oc-work || exit 98
OC=${'$'}(command -v opencode) || { echo OC_NOT_INSTALLED; exit 97; }
if [ -n "${'$'}1" ]; then exec timeout 105 "${'$'}OC" run --format json -m "${'$'}1" "${'$'}2"; fi
exec timeout 105 "${'$'}OC" run --format json "${'$'}2"
""".trimIndent()

    private fun embeddedReady(c: android.content.Context) = Linux.supported(c) && Linux.rootReady(c) && Linux.nodeReady(c)

    fun enabled(): Boolean {
        if (Cfg.isReady() && Cfg.str("oc_local", "on") == "off") return false
        val c = TrWizard.appCtx ?: return false
        return embeddedReady(c) || (OmniLauncher.termuxInstalled(c) && OmniLauncher.termuxHasPerm(c))
    }

    /** تثبيت OpenCode في Termux (3-5 دقايق أول مرة). [onDone] فيها النص + OC_OK أو OC_FAIL */
    fun install(c: android.content.Context, onDone: (String) -> Unit): OmniLauncher.R {
        if (embeddedReady(c)) { Thread { Linux.installOpenCode(c) { }; val ok = Linux.opencodeInstalled(c); android.os.Handler(Looper.getMainLooper()).post { onDone(if (ok) "OC_OK" else "OC_FAIL") } }.start(); return OmniLauncher.R.SENT }
        return TermuxRun.runScript(c, INSTALL, emptyList(), onDone)
    }

    private fun prompt(system: String, msgs: List<AiApi.Msg>): String {
        val sb = StringBuilder()
        sb.append("INSTRUCTIONS (follow exactly):\n").append(system).append("\n\n")
        for (m in msgs) sb.append(if (m.role == "assistant") "ASSISTANT:\n" else "INPUT:\n").append(m.text).append("\n\n")
        sb.append("Reply with the final answer only, in the requested format. Do not use tools, do not read or write files, do not explain.")
        return sb.toString()
    }

    /** يطلّع نص الرد من سطور {"type":"text","part":{"text":...}} */
    fun parse(out: String): String {
        val sb = StringBuilder()
        for (ln in out.lines()) {
            val t = ln.trim()
            if (!t.startsWith("{")) continue
            val j = try { JSONObject(t) } catch (_: Throwable) { continue }
            if (j.optString("type") == "text") sb.append(j.optJSONObject("part")?.optString("text", "") ?: "")
        }
        return sb.toString().trim()
    }

    /** طلب واحد (blocking) — لازم يتنادى من thread خلفية. [model] id من غير بادئة (مثلاً glm-5.3-flash-free) */
    fun ask(model: String, system: String, msgs: List<AiApi.Msg>): String {
        val c = TrWizard.appCtx ?: throw ExtLlm.LlmErr(401, "$FATAL التطبيق لسه بيبدأ — جرّب تاني")
        if (Looper.myLooper() == Looper.getMainLooper()) throw ExtLlm.LlmErr(500, "أوبن كود المحلي لازم يتنادى من thread خلفية")
        val latch = CountDownLatch(1)
        val res = arrayOfNulls<String>(1)
        gate.acquire()
        try {
            val margs = listOf(if (model.isBlank()) "" else "opencode/$model", prompt(system, msgs))
            val r = if (embeddedReady(c)) { Linux.execAsync(c, RUN_G, margs, 110) { res[0] = it; latch.countDown() }; OmniLauncher.R.SENT }
                    else TermuxRun.runScript(c, RUN, margs) { res[0] = it; latch.countDown() }
            when (r) {
                OmniLauncher.R.NO_TERMUX -> throw ExtLlm.LlmErr(401, "$FATAL Termux مش متسطب")
                OmniLauncher.R.NO_PERM -> throw ExtLlm.LlmErr(401, "$FATAL محتاج تجهيز البيئة الداخلية (كونسول Linux ← 🚀 جهّز) أو إذن RUN_COMMAND لو بتستخدم Termux")
                OmniLauncher.R.ERROR -> throw ExtLlm.LlmErr(500, "مقدرتش أكلّم Termux")
                else -> {}
            }
            if (!latch.await(115, TimeUnit.SECONDS)) throw java.io.IOException("مهلة أوبن كود المحلي")
        } finally { gate.release() }
        val out = res[0].orEmpty()
        if (out.contains("OC_NOT_INSTALLED")) throw ExtLlm.LlmErr(401, "$FATAL أوبن كود CLI مش متسطب — افتح كونسول Linux واضغط «⬇ OpenCode CLI»")
        val txt = parse(out)
        if (txt.isNotBlank()) return txt
        throw ExtLlm.LlmErr(502, "أوبن كود المحلي: " + out.lines().filter { it.isNotBlank() && !it.trim().startsWith("{") }.takeLast(3).joinToString(" | ").take(200).ifBlank { "رد فاضي" })
    }
}
