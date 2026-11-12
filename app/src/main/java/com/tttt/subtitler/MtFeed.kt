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
        MtTvRoute.resumed()?.let { a -> a.runOnUiThread { showLoginIfPending(a) } }
    }

    /** من MtTvRoute: أي شاشة اتفتحت قدّام — لو في طلب معلّق نسأل */
    fun onResumed(a: Activity) {
        try {
            if (loginPending) showLoginIfPending(a)
            else if (!lastSigned && RemoteClient.state == RemoteClient.St.CONNECTED) maybePush()   // رجعنا من شاشة تسجيل الدخول ← جرّب بسرعة
        } catch (_: Throwable) {}
    }

    private fun showLoginIfPending(a: Activity) {
        if (!loginPending || a.isFinishing || a.isDestroyed) return
        val n = a.javaClass.name
        if (!(n.startsWith("com.tttt.subtitler") || n.startsWith("com.newtube") || n.startsWith("com.liskovsoft"))) return
        if (n.contains("SignIn")) return
        loginPending = false
        try {
            GAlert(a).setTitle("🔐 تسجيل دخول يوتيوب")
                .setMessage("صف يوتيوب على التلفزيون محتاج حسابك من MeeTube. تسجّل الدخول دلوقتي؟\n\nهتفتح صفحة جوجل، تدوس «السماح»، وترجع.")
                .setPositiveButton("سجّل الدخول") { _, _ -> MeeTubeLink.signIn(a) }
                .setNegativeButton("بعدين", null).show()
        } catch (e: Throwable) { LogStore.err("MtFeed:login", e) }
    }
}
