package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * (v365) تشغيل OmniRoute تلقائي من جوه التطبيق عن طريق Termux (RUN_COMMAND) — من غير ما تفتح Termux وتكتب سكربت.
 *  - أول مرة بس: خطوة إعداد واحدة (allow-external-apps) + إذن RUN_COMMAND (بيتطلب من التطبيق).
 *  - بعدها: لو OmniRoute مش شغّال، التطبيق يبعت لـ Termux أمر يثبّته (أول مرة بس) ويشغّله، ويستنى لحد ما البورت 20128 يرد.
 */
object OmniLauncher {
    const val PERM = "com.termux.permission.RUN_COMMAND"
    private const val PKG = "com.termux"
    private const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /** الأمر اللي بيتنفّذ جوه Termux: idempotent — لو شغّال يخرج، لو مش متثبّت يثبّته، وبعدين يشغّله (exec = الجلسة نفسها هي السيرفر) */
    private val SCRIPT = """
export PATH=/data/data/com.termux/files/usr/bin:${'$'}PATH
export REQUIRE_API_KEY=false PORT=20128
LOG=${'$'}HOME/omniroute.log
if curl -s -m 2 -o /dev/null http://127.0.0.1:20128/v1/models; then exit 0; fi
find ${'$'}HOME/.omni.lock -mmin +30 -exec rm -rf {} \; 2>/dev/null
mkdir ${'$'}HOME/.omni.lock 2>/dev/null || exit 0
mkdir -p ${'$'}HOME/.cache
termux-wake-lock 2>/dev/null
if ! command -v omniroute >/dev/null 2>&1; then
  echo "== تثبيت OmniRoute أول مرة (5-10 دقايق) ==" | tee -a ${'$'}LOG
  yes | pkg update -y >> ${'$'}LOG 2>&1
  pkg install -y nodejs python build-essential git curl >> ${'$'}LOG 2>&1
  npm install -g omniroute@3.8.52 >> ${'$'}LOG 2>&1 || npm install -g omniroute@latest >> ${'$'}LOG 2>&1
fi
rm -rf ${'$'}HOME/.omni.lock
exec omniroute >> ${'$'}LOG 2>&1
""".trimIndent()

    /** تتلصق في Termux مرة واحدة */
    const val SETUP_CMD = "mkdir -p ~/.termux && sed -i '/allow-external-apps/d' ~/.termux/termux.properties 2>/dev/null; echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings && echo تمام"

    enum class R { NO_TERMUX, NO_PERM, SENT, ERROR }

    fun installed(c: Context): Boolean = try { c.packageManager.getPackageInfo(PKG, 0); true } catch (_: Throwable) { false }
    fun hasPerm(c: Context): Boolean = c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    fun start(c: Context): R {
        if (!installed(c)) return R.NO_TERMUX
        if (!hasPerm(c)) return R.NO_PERM
        return try {
            val i = Intent("com.termux.RUN_COMMAND").setClassName(PKG, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", SCRIPT))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "3")   // جلسة في الخلفية من غير ما تفتح Termux
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            Cfg.put("omni_try_at", System.currentTimeMillis().toString())
            R.SENT
        } catch (_: SecurityException) { R.NO_PERM } catch (_: Throwable) { R.ERROR }
    }

    fun triedRecently(ms: Long = 240000L): Boolean = (Cfg.str("omni_try_at").toLongOrNull() ?: 0L) > System.currentTimeMillis() - ms

    fun requestPerm(a: Activity) { a.requestPermissions(arrayOf(PERM), 365) }

    /** بيانسخ أمر الإعداد ويفتح Termux عشان تلصقه (مرة واحدة في العمر) */
    fun copySetupAndOpenTermux(a: Activity) {
        (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("omni", SETUP_CMD))
        a.packageManager.getLaunchIntentForPackage(PKG)?.let { a.startActivity(it) }
    }

    /** بيستنى لحد ما OmniRoute يرد (لحد 12 دقيقة عشان التثبيت أول مرة) — [onUp] على thread الخلفية */
    fun waitUntilUp(maxMs: Long = 720000L, onUp: () -> Unit) {
        Thread {
            val end = System.currentTimeMillis() + maxMs
            while (System.currentTimeMillis() < end) {
                try { Thread.sleep(4000) } catch (_: Throwable) { return@Thread }
                val r = try { OmniAuto.probe() } catch (_: Throwable) { OmniAuto.St.DOWN }
                if (r != OmniAuto.St.DOWN) { OmniAuto.last = r; onUp(); return@Thread }
            }
        }.start()
    }
}
