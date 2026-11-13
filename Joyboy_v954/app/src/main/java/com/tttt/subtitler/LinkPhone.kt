package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * (v820) تحديث لينك الفيديو بتاع التلفزيون من الموبايل.
 * لما لينك فيديو شغّال على التلفزيون يخلص، التلفزيون (لو الموبايل متوصّل بيه ومتابعه) بيبعت حدث «linkreq» بدل ما يفتح التحديث على شاشته.
 * الموبايل بيرد «link.ack» فورًا، وبيجيب اللينك الجديد من الصفحة (في الخلفية، وبيسألك لو محتاج تختار أو تصطاد بإيدك)، وبيبعته للتلفزيون «link.apply».
 * لو الموبايل مش قدّامه شاشة (التطبيق في الخلفية) بيبعت «link.fail» والتلفزيون يكمّل بنفسه زي زمان.
 */
object LinkPhone {
    private var top: WeakReference<Activity>? = null
    private var hooked = false

    fun hook(app: Application) {
        if (hooked) return
        hooked = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { top = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (top?.get() === a) top = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun front(): Activity? = top?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** s = JSON الحدث من التلفزيون: vid · page · old · ua · title */
    fun handle(s: String) {
        val j = try { JSONObject(s) } catch (_: Throwable) { return }
        val vid = j.optString("vid"); val page = j.optString("page"); val old = j.optString("old")
        val act = front()
        if (act == null || DeviceUi.isTv(act) || vid.isEmpty() || !page.startsWith("http")) { LinkSession.send("link.fail", JSONObject().put("vid", vid).put("page", page)); return }
        LinkSession.send("link.ack", JSONObject().put("vid", vid))
        Notice.show(act, "📺 لينك فيديو التلفزيون خلص — بجيب الجديد من الصفحة…", 4000L)
        LinkRefresh.run(act, page, old, j.optString("ua")) { r ->
            val a = front() ?: act
            if (r != null && r.same) { apply(a, vid, r); return@run }
            val opts = r?.all?.distinct().orEmpty()
            if (r != null && opts.isNotEmpty()) {
                val labels = opts.mapIndexed { i, u -> val hh = Sniff.heightOf(u); (if (hh > 0) "${hh}p" else "لينك ${i + 1}") + " · " + Sniff.nameOf(u).take(40) }.toTypedArray()
                GAlert(a).setTitle("اختار لينك الفيديو للتلفزيون (" + opts.size + ")")
                    .setItems(labels) { _, w -> apply(a, vid, LinkRefresh.Res(opts[w], true, r.cookie, r.ua)) }
                    .setNegativeButton("إلغاء") { _, _ -> cancel(vid) }.show()
            } else {
                GAlert(a).setTitle("⚠ مالقيتش لينك الفيديو").setMessage("الصفحة ماطلّعتش أي لينك فيديو (غالبًا محمية أو محتاجة ضغطة).\nتفتحها في متصفح الموبايل تصطاد بنفسك وأبعته للتلفزيون؟")
                    .setPositiveButton("نعم") { _, _ ->
                        a.startActivity(Intent(a, BrowserActivity::class.java).putExtra("start", page).putExtra("noauto", true)
                            .putExtra("repair_id", vid).putExtra("repair_url", old).putExtra("repair_title", j.optString("title")).putExtra("repair_tv", true))
                    }
                    .setNegativeButton("لا") { _, _ -> cancel(vid) }.show()
            }
        }
    }

    private fun apply(a: Activity, vid: String, r: LinkRefresh.Res) {
        LinkSession.send("link.apply", JSONObject().put("vid", vid).put("url", r.url).put("cookie", r.cookie).put("ua", r.ua))
        Notice.show(a, "✓ اتبعت لينك الفيديو الجديد للتلفزيون", 2500L)
    }
    private fun cancel(vid: String) { LinkSession.send("link.cancel", JSONObject().put("vid", vid)) }

    /** من المتصفح (وضع الإصلاح على التلفزيون): اللينك اللي اتصاد يتبعت للتلفزيون */
    fun applyFromBrowser(a: Activity, vid: String, url: String, cookie: String, ua: String) {
        LinkSession.send("link.apply", JSONObject().put("vid", vid).put("url", url).put("cookie", cookie).put("ua", ua))
        Notice.show(a, "✓ اتبعت لينك الفيديو الجديد للتلفزيون", 2500L)
    }
}
