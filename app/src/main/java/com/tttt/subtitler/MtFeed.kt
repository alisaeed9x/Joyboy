package com.tttt.subtitler

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject

/**
 * (v929) الموبايل بيجيب قوايم الفيديوهات من MeeTube (أحدث الاشتراكات + الصفحة الرئيسية) ويبعتها لصف/صفحة يوتيوب بتاعة التلفزيون (TvLink /ytfeed).
 * التلفزيون ما بيشغّلش MeeTube ولا بيسجّل دخول — الحساب كله على الموبايل.
 *  • بيتبعت أول ما الموبايل يتصل بالتلفزيون، وبعدها كل 15 دقيقة (كل 45 ثانية لحد ما الحساب يتسجّل).
 *  • مش مسجّل دخول ← بيتبعت signed=false والتلفزيون بيعرض «سجّل الدخول من MeeTube على الموبايل»؛ دوسة عليها بتطلّع طلب للموبايل (ping.ytlogin) ← نافذة تسجيل الدخول.
 */
object MtFeed {
    @Volatile private var lastAt = 0L
    @Volatile private var lastSigned = false
    @Volatile private var busy = false
    @Volatile private var loginPending = false

    /** بيتنده من حلقة الاتصال (RemoteClient.loop) كل ~4 ثواني وهو متصل — بيتحكم في التكرار لوحده. */
    fun maybePush(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val every = if (lastSigned) 15L * 60_000L else 45_000L
        if (busy || (!force && now - lastAt < every)) return
        busy = true
        Thread {
            try { push() } catch (e: Throwable) { LogStore.err("MtFeed:push", e) } finally { busy = false }
        }.also { it.isDaemon = true }.start()
    }

    private fun arr(l: List<NpItem>): JSONArray {
        val a = JSONArray()
        for (v in l) a.put(JSONObject().put("id", v.id).put("t", v.title).put("ch", v.channel).put("th", v.thumb).put("age", v.age).put("d", v.dur))
        return a
    }

    private fun push() {
        val now = System.currentTimeMillis()
        val signed = MtYt.signedIn()
        val j = JSONObject().put("signed", signed).put("at", now)
        if (signed) {
            val subs = MtYt.subscriptions().take(40)
            val home = MtYt.homeFeed().filter { h -> subs.none { it.id == h.id } }.take(40)
            if (subs.isEmpty() && home.isEmpty()) { lastAt = now - 14L * 60_000L; return }   // فشل الجلب: ما نمسحش اللي على التلفزيون — جرّب تاني بعد دقيقة
            j.put("subs", arr(subs)).put("home", arr(home))
        }
        val (c, _) = RemoteClient.postJson("/ytfeed", j)
        if (c == 200) { lastAt = now; lastSigned = signed }
    }

    /** التلفزيون طلب تسجيل الدخول (دوسة على «سجّل الدخول» في صف يوتيوب) */
    fun onLoginRequest() {
        if (MtYt.signedIn()) { maybePush(true); return }
        loginPending = true
        resumed()?.let { a -> a.runOnUiThread { showLoginIfPending(a) } }
    }

    // (v1105 Y5) كانت جوه MtTvRoute (اتمسح): الشاشة الظاهرة دلوقتي (null = البرنامج في الخلفية)
    @Volatile private var res: java.lang.ref.WeakReference<Activity>? = null
    private var installed = false
    fun resumed(): Activity? = res?.get()
    fun install(app: android.app.Application) {
        try {
            if (installed) return
            installed = true
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) { res = java.lang.ref.WeakReference(a); try { onResumed(a) } catch (_: Throwable) {} }
                override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) { if (res?.get() === a) res = null }
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
        } catch (e: Throwable) { LogStore.err("MtFeed:install", e) }
    }

    /** من install: أي شاشة اتفتحت قدّام — لو في طلب معلّق نسأل */
    fun onResumed(a: Activity) {
        try {
            if (loginPending) showLoginIfPending(a)
            else if (!lastSigned && RemoteClient.state == RemoteClient.St.CONNECTED) maybePush()   // رجعنا من شاشة تسجيل الدخول ← جرّب بسرعة
        } catch (_: Throwable) {}
    }

    private fun showLoginIfPending(a: Activity) {
        if (!loginPending || a.isFinishing || a.isDestroyed) return
        val n = a.javaClass.name
        if (!n.startsWith("com.tttt.subtitler")) return
        loginPending = false
        try {
            GAlert(a).setTitle("🔐 تسجيل دخول يوتيوب")
                .setMessage("صف يوتيوب على التلفزيون محتاج حسابك. تسجّل الدخول دلوقتي؟\n\nهيظهر كود، تدخله في youtube.com/activate من أي جهاز وتدوس «السماح».")
                .setPositiveButton("سجّل الدخول") { _, _ -> val th0 = Themes.current(); YtAccount.login(a, Ui(a, th0), th0) { ok -> if (ok) try { maybePush(true) } catch (_: Throwable) {} } }   // (v1105 Y5) كانت MeeTubeLink.signIn
                .setNegativeButton("بعدين", null).show()
        } catch (e: Throwable) { LogStore.err("MtFeed:login", e) }
    }
}
