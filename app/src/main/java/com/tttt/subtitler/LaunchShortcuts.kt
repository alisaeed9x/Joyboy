package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * (v593) أيقونتين إضافيتين في اللانشر جنب أيقونة Joyboy الرئيسية: «Joyboy Browser» و«Joyboy YouTube».
 * كل واحدة شاشة شفافة بتفتح وجهتها وتقفل نفسها فورًا، وبتفتحها في كل مرة حتى لو التطبيق شغّال في الخلفية على حاجة تانية
 * (v595: NEW_TASK + taskAffinity فاضي للأيقونة نفسها عشان أندرويد ما يرجّع التاسك القديم من غير ما يفتح الوجهة؛ CLEAR_TOP|SINGLE_TOP بيرجّع الشاشة الموجودة قدّام بدل ما يعمل نسخة جديدة؛ ولو التطبيق مقفول بيبدأ عادي).
 */
class LaunchBrowserActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try { startActivity(Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        catch (e: Throwable) { try { LogStore.err("LaunchBrowser", e) } catch (_: Throwable) {} }
        finish()
    }
}

class LaunchYoutubeActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "youtube")) }
        catch (e: Throwable) { try { LogStore.err("LaunchYoutube", e) } catch (_: Throwable) {} }
        finish()
    }
}

/** (v595) أيقونة «ريموت التلفزيون»: بتفتح RemoteActivity في كل مرة حتى لو التطبيق شغّال على صفحة تانية */
class LaunchRemoteActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try { startActivity(Intent(this, RemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        catch (e: Throwable) { try { LogStore.err("LaunchRemote", e) } catch (_: Throwable) {} }
        finish()
    }
}
