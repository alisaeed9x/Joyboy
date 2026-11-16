package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * (v1072) تسجيل دخول يوتيوب بطريقة MeeTube (device-code) — الكود اللي كان بيظهر في MeeTube، من غير برنامج MeeTube.
 *  • signInObserve() بيطلّع الكود (~9 حروف) أولًا، وبعدين بيستنى تأكيدك في تطبيق يوتيوب (polling) لحد ما التوكن يوصل.
 *  • «متابعة مع جوجل» بيفتح youtube.com/qr/activate/<الكود> — تطبيق يوتيوب على موبايلك بيلقط الرابط وبيطلب تأكيد («تم»)،
 *    والتوكن بيتسجّل في نفس مخزن الإعدادات (GlobalPreferences) فبيفضل شغّال بعد قفل البرنامج.
 *  • فشل الدخول = كود جديد (مفيش استئناف لكود قديم) — الدايلوج بيفضل مفتوح للتجربة تاني.
 *  • ديالوج بس — من غير Activity جديدة ولا سطر في الـmanifest.
 */
object YtMtSignIn {
    @Volatile private var active: Disposable? = null

    /** a = الشاشة اللي الدايلوج فوقها؛ after(true) = تم الدخول، after(false) = اتقفل من غير دخول */
    fun signIn(a: Activity, after: ((Boolean) -> Unit)? = null) {
        try { GlobalPreferences.instance(a.applicationContext) } catch (_: Throwable) {}
        if (MtYt.signedIn()) { try { after?.invoke(true) } catch (_: Throwable) {}; return }
        if (a.isFinishing || a.isDestroyed) { try { after?.invoke(false) } catch (_: Throwable) {}; return }
        if (active != null) return
        val th = Glass.theme()
        val ui = Ui(a, th)
        val dm = a.resources.displayMetrics
        val tvk = TvFocus.onCtx(a)
        var code = ""
        var done = false
        var reported = false
        fun finish(ok: Boolean) {
            if (reported) return
            reported = true
            try { active?.dispose() } catch (_: Throwable) {}
            active = null
            try { after?.invoke(ok) } catch (_: Throwable) {}
        }
        val codeTv = ui.text("…", if (tvk) 30f else 26f, th.primary, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(14), 0, ui.dp(6))
            setTextIsSelectable(true)
        }
        val statusTv = ui.text("⏳ بجيب الكود…", if (tvk) 17f else 14f, th.text).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(4), 0, ui.dp(4))
            setLineSpacing(0f, 1.25f)
        }
        val hintTv = ui.text("دوس «متابعة مع جوجل» — هيفتح تطبيق يوتيوب على موبايلك، دوس «تم» هناك، وارجع هنا.\nلو التطبيق ما فتحش: اكتب الكود في youtube.com/activate", if (tvk) 16f else 13f, th.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(8), 0, ui.dp(4))
            setLineSpacing(0f, 1.25f)
        }
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(14))
            background = ui.box(th.card, th.border, 22)
        }
        root.addView(ui.text("🔐 تسجيل الدخول بحساب جوجل", if (tvk) 22f else 18f, th.primary, true), LinearLayout.LayoutParams(-1, -2))
        root.addView(codeTv, LinearLayout.LayoutParams(-1, -2))
        root.addView(statusTv, LinearLayout.LayoutParams(-1, -2))
        root.addView(hintTv, LinearLayout.LayoutParams(-1, -2))
        val d = GDialog(a)
        val row = LinearLayout(a).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        fun btn(t: String, main: Boolean): TextView = IconTextView(a).apply {
            text = t
            textSize = if (tvk) 17f else 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
            setTextColor(if (main) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text)
            background = if (main) Glass.primaryFill(th, ui.dp(Skins.rad(12)).toFloat()) else ui.box(th.surface, th.border, 12)
        }.also { Glass.pressable(it) }
        var startFlow: (() -> Unit)? = null
        val goBtn = btn("🌐 متابعة مع جوجل", true)
        val copyBtn = btn("📋 نسخ الكود", false)
        val cancelBtn = btn("إلغاء", false)
        goBtn.setOnClickListener {
            if (code.isBlank()) {
                if (goBtn.text.toString().contains("كود جديد")) {
                    goBtn.text = "🌐 متابعة مع جوجل"
                    startFlow?.invoke()
                } else {
                    Notice.show(a, "⏳ لسه بجيب الكود…", 1800L)
                }
                return@setOnClickListener
            }
            val url = "https://youtube.com/qr/activate/" + code.replace(" ", "-")
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
                try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yt.be/activate")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
            }
            statusTv.text = "⏳ مستني تأكيدك في تطبيق يوتيوب…"
        }
        copyBtn.setOnClickListener {
            if (code.isBlank()) { Notice.show(a, "⏳ لسه بجيب الكود…", 1800L); return@setOnClickListener }
            try {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("YouTube", code))
                Notice.show(a, "📋 اتنسخ الكود", 1800L)
            } catch (_: Throwable) {}
        }
        cancelBtn.setOnClickListener { d.dismiss() }
        row.addView(goBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        row.addView(copyBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        row.addView(cancelBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14) })
        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf((dm.widthPixels * 0.9f).toInt(), ui.dp(if (tvk) 560 else 420)), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        d.setCancelable(true)
        d.setCanceledOnTouchOutside(true)
        d.setOnDismissListener { if (!done) finish(false) }
        startFlow = {
            try { active?.dispose() } catch (_: Throwable) {}
            code = ""
            codeTv.text = "…"
            statusTv.text = "⏳ بجيب الكود…"
            val disp = try {
                YouTubeSignInService.instance().signInObserve()
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { c ->
                            code = c.trim()
                            codeTv.text = code
                            statusTv.text = "⏳ مستني تأكيدك في تطبيق يوتيوب…"
                        },
                        { e ->
                            code = ""
                            codeTv.text = "⚠"
                            statusTv.text = "⚠ فشل: " + (e.message ?: "خطأ").take(120) + "\nدوس «كود جديد» عشان تجرّب تاني."
                            goBtn.text = "🔄 كود جديد"
                        },
                        {
                            done = true
                            finish(true)
                            Notice.show(a, "✅ اتسجّل الدخول", 2200L)
                            d.dismiss()
                        }
                    )
            } catch (e: Throwable) {
                statusTv.text = "⚠ " + (e.message ?: "فشل").take(120)
                null
            }
            active = disp
        }
        d.show()
        startFlow()
    }
}
