package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * (v1103) Y1 — تسجيل دخول يوتيوب بكود التفعيل (OAuth2 device flow) من محرك MeeTube (MediaServiceCore) — بدل WebView جوجل وQR.
 * تدوس «دخول» ← يظهر كود + youtube.com/activate ← توافق من أي جهاز ← التوكن بيتحفظ في MediaServiceCore نفسه (بيفضل بعد إعادة التشغيل).
 * شاشة Views بسيطة في app (مش بننقل :mobile). الـAPI: signInService.signInObserve() بيطلّع الكود ويخلّص (onComplete) لما تتم الموافقة.
 * لم يتم compile.
 */
object YtCodeLogin {
    const val URL_SHOW = "youtube.com/activate"
    private const val URL_OPEN = "https://www.youtube.com/activate"
    private const val URL_QR = "https://youtube.com/qr/activate/"   // الكود مملّي في اللينك (نفس اللي MeeTube بيفتحه)

    /** مسجّل دخول بكود التفعيل؟ (من جوه محرك MeeTube) */
    fun signedIn(): Boolean = MtYt.signedIn()

    /** onDone(true) لما الدخول يتم. من الـUI thread. */
    fun show(act: Activity, ui: Ui, th: Theme, onDone: (Boolean) -> Unit = {}) {
        try { MtYt.ensureBoot() } catch (e: Throwable) { LogStore.err("YtCodeLogin:boot", e) }
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var disp: Disposable? = null
        var finished = false
        var code = ""
        val wasSigned = signedIn()   // (v1106) لو مسجّل بالفعل (وبتسجّل بحساب تاني) مبنعتمدش على فحص «مسجّل ولا لأ» عشان ما يقفلش بالغلط
        var inBg = false
        var cbReg: Application.ActivityLifecycleCallbacks? = null
        val watching = java.util.concurrent.atomic.AtomicBoolean(false)

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(14))
            background = GradientDrawable().apply { setColor(th.surface); cornerRadius = ui.dp(18).toFloat() }
        }
        val title = ui.text("🔐 دخول يوتيوب بالكود", 16f, th.text, true)
        val state = ui.text("", 13f, th.muted, false).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(6)) }
        val codeV = ui.text("", 30f, th.text, true).apply {
            gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR
            letterSpacing = 0.12f; setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
            background = GradientDrawable().apply { setColor(th.card); cornerRadius = ui.dp(12).toFloat(); setStroke(ui.dp(1), th.border) }
        }
        val urlV = ui.text(URL_SHOW, 14f, th.primary, true).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(0, ui.dp(8), 0, ui.dp(4)) }
        val hint = ui.text("افتح الصفحة دي من أي جهاز، اكتب الكود، ووافق على حسابك، وارجع للتطبيق. الشاشة دي هتقفل لوحدها لما الدخول يتم.", 12f, th.muted, false).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(8)) }
        val btnCopy = ui.button("📋 انسخ الكود") {
            if (code.isNotBlank()) try { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", code)); Notice.show(act, "اتنسخ الكود", 1500L) } catch (_: Throwable) {}
        }
        val btnOpen = ui.button("🌐 افتح صفحة التفعيل", true) {
            try {
                val u = if (code.isNotBlank()) URL_QR + code.replace(" ", "-") else URL_OPEN
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
            } catch (e: Throwable) { Notice.show(act, "مفيش متصفح يفتح اللينك — افتح $URL_SHOW يدوي", 3000L) }
        }
        val btnRetry = ui.button("🔄 كود جديد") { }
        val btnClose = ui.button("✕ إغلاق") { d.dismiss() }

        fun lp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) }
        box.addView(title)
        box.addView(state, LinearLayout.LayoutParams(-1, -2))
        box.addView(codeV, LinearLayout.LayoutParams(-1, -2))
        box.addView(urlV, LinearLayout.LayoutParams(-1, -2))
        box.addView(hint, LinearLayout.LayoutParams(-1, -2))
        box.addView(btnCopy, lp()); box.addView(btnOpen, lp()); box.addView(btnRetry, lp()); box.addView(btnClose, lp())

        fun setLoading(msg: String) { state.text = msg; codeV.visibility = View.GONE; urlV.visibility = View.GONE; hint.visibility = View.GONE; btnCopy.visibility = View.GONE; btnOpen.visibility = View.GONE; btnRetry.visibility = View.GONE }
        fun setCode(c: String) { code = c; state.text = "الكود (بيتجدّد لوحده لو خلص وقته):"; codeV.text = c; codeV.visibility = View.VISIBLE; urlV.visibility = View.VISIBLE; hint.visibility = View.VISIBLE; btnCopy.visibility = View.VISIBLE; btnOpen.visibility = View.VISIBLE; btnRetry.visibility = View.GONE }
        fun setError(msg: String) { state.text = "⚠ $msg"; codeV.visibility = View.GONE; urlV.visibility = View.GONE; hint.visibility = View.GONE; btnCopy.visibility = View.GONE; btnOpen.visibility = View.GONE; btnRetry.visibility = View.VISIBLE }

        fun unregisterCb() { try { cbReg?.let { act.application.unregisterActivityLifecycleCallbacks(it) } } catch (_: Throwable) {}; cbReg = null }

        /** (v1106) إنهاء الدخول — من onComplete بتاع المحرك، أو من فحص الرجوع للتطبيق (أيهما الأول). مرة واحدة بس. */
        fun complete() {
            if (finished) return
            finished = true
            try { YtMtFeed.markSigned(true) } catch (_: Throwable) {}
            unregisterCb()
            // لو الدخول خلص والتطبيق في الخلفية (إنت لسه في المتصفح/يوتيوب): نحاول نرجّعه قدّام (أندرويد ممكن يمنع ده — وقتها بيتحدّث أول ما تفتحه)
            if (inBg) try { act.startActivity(Intent(act, act.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (_: Throwable) {}
            Notice.show(act, "✅ اتسجّل الدخول", 2500L)
            try { d.dismiss() } catch (_: Throwable) {}
            onDone(true)
        }

        /** (v1106) أول ما ترجع للتطبيق: بنتأكد فورًا إن الموافقة تمت (من غير ما نستنى دورة الاستعلام الجاية) */
        fun watch() {
            if (wasSigned || finished || !watching.compareAndSet(false, true)) return
            Thread {
                try {
                    var n = 0
                    while (!finished && d.isShowing && n < 24) {
                        val ok = try { MtYt.signedIn() } catch (_: Throwable) { false }
                        if (ok) { act.runOnUiThread { complete() }; break }
                        n++; try { Thread.sleep(750L) } catch (_: InterruptedException) { break }
                    }
                } finally { watching.set(false) }
            }.also { it.isDaemon = true; it.start() }
        }
        cbReg = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { inBg = false; if (!finished) watch() }
            override fun onActivityPaused(a: Activity) { inBg = true }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        try { act.application.registerActivityLifecycleCallbacks(cbReg) } catch (_: Throwable) {}

        fun start() {
            disp?.dispose(); code = ""; finished = false
            setLoading("جاري طلب الكود…")
            try {
                disp = YouTubeServiceManager.instance().signInService.signInObserve()
                    .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { c -> if (!finished) setCode(c) },
                        { e -> if (!finished) { LogStore.err("YtCodeLogin:signIn", e); setError(e.message ?: e.javaClass.simpleName) } },
                        { complete() })
            } catch (e: Throwable) { LogStore.err("YtCodeLogin:start", e); setError(e.message ?: e.javaClass.simpleName) }
        }
        btnRetry.setOnClickListener { start() }
        d.setOnDismissListener { try { disp?.dispose() } catch (_: Throwable) {}; unregisterCb(); if (!finished) onDone(false) }

        d.setContentView(box)
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((act.resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(ui.dp(440)), -2) }
        d.show()
        if (signedIn()) { setLoading("أنت مسجّل دخول بالفعل ✅"); btnRetry.text = "🔁 سجّل بحساب تاني"; btnRetry.visibility = View.VISIBLE; btnRetry.setOnClickListener { start() } } else start()
    }

    /** خروج من حساب كود التفعيل */
    fun logout() {
        try { MtYt.ensureBoot(); val s = YouTubeServiceManager.instance().signInService; s.selectedAccount?.let { s.removeAccount(it) } } catch (e: Throwable) { LogStore.err("YtCodeLogin:logout", e) }
    }
}
