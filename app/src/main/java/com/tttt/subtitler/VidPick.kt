package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * (v1052) اختيار فيديو واحد أو أكتر من الموبايل لمكتبة الترجمات (إضافة للترجمة في الخلفية).
 * نفس فكرة SubPick: Fragment من غير واجهة، من غير ما نلمس onActivityResult بتاع أي Activity.
 */
@Suppress("DEPRECATION")
class VidPickFrag : android.app.Fragment() {
    var cb: ((List<Uri>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        if (savedInstanceState != null) { finishWith(emptyList()); return }
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, REQ)
        } catch (e: Throwable) { LogStore.err("VidPick:start", e); finishWith(emptyList()) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ) return
        val out = ArrayList<Uri>()
        if (resultCode == Activity.RESULT_OK && data != null) {
            data.data?.let { out.add(it) }
            val cd = data.clipData
            if (cd != null) for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { if (it !in out) out.add(it) }
        }
        finishWith(out)
    }

    private fun finishWith(l: List<Uri>) {
        val f = cb; cb = null
        try { fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss() } catch (_: Throwable) {}
        try { f?.invoke(l) } catch (e: Throwable) { LogStore.err("VidPick:cb", e) }
    }

    companion object { const val REQ = 4178 }
}

object VidPick {
    @Suppress("DEPRECATION")
    fun pick(act: Activity, cb: (List<Uri>) -> Unit) {
        try {
            val f = VidPickFrag(); f.cb = cb
            act.fragmentManager.beginTransaction().add(f, "vidpick").commitAllowingStateLoss()
        } catch (e: Throwable) { LogStore.err("VidPick:pick", e); cb(emptyList()) }
    }
}
