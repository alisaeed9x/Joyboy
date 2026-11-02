package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import org.json.JSONObject
import java.util.UUID

/**
 * (v684) اختيار ملف ترجمة من الموبايل (SRT / ASS / VTT) وبعته للتلفزيون عشان يتترجم ويتحط على الفيديو الشغّال.
 * الاختيار بيتم عن طريق Fragment من غير واجهة (من غير ما نلمس onActivityResult بتاع أي Activity ولا الـManifest)،
 * لأن مشغّل الريموت بيتفتح من أكتر من شاشة.
 */
@Suppress("DEPRECATION")
class SubPickFrag : android.app.Fragment() {
    var cb: ((Uri?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        if (savedInstanceState != null) { finishWith(null); return }   // الشاشة اتعملها إعادة إنشاء وضاع الـcallback
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ)
        } catch (e: Throwable) { LogStore.err("SubPick:start", e); finishWith(null) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ) return
        finishWith(if (resultCode == Activity.RESULT_OK) data?.data else null)
    }

    private fun finishWith(u: Uri?) {
        val f = cb; cb = null
        try { fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss() } catch (_: Throwable) {}
        try { f?.invoke(u) } catch (e: Throwable) { LogStore.err("SubPick:cb", e) }
    }

    companion object { const val REQ = 4177 }
}

object SubPick {
    class Picked(val name: String, val text: String, val cues: Int)

    @Suppress("DEPRECATION")
    fun pick(act: Activity, cb: (Uri?) -> Unit) {
        try {
            val f = SubPickFrag(); f.cb = cb
            act.fragmentManager.beginTransaction().add(f, "subpick").commitAllowingStateLoss()
        } catch (e: Throwable) { LogStore.err("SubPick:pick", e); cb(null) }
    }

    /** قراءة الملف + التأكد إنه ترجمة صالحة. null = مش صالح / كبير زيادة. blocking — من خيط خلفي */
    fun read(ctx: Context, u: Uri): Picked? {
        return try {
            var name = "subtitle.srt"
            try { ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) name = it } } } catch (_: Throwable) {}
            val b = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return null
            if (b.isEmpty() || b.size > 6_000_000) return null
            val txt = SubFileTr.decode(b)
            val n = SubFileTr.parse(txt).size
            if (n == 0) null else Picked(name, txt, n)
        } catch (e: Throwable) { LogStore.err("SubPick:read", e); null }
    }

    /** رفع النص للتلفزيون على قطع. بيرجّع "" لو نجح، وإلا رسالة الخطأ. blocking — من خيط خلفي */
    fun upload(name: String, text: String): String {
        if (text.length > 2_800_000) return "الملف كبير زيادة"
        val id = UUID.randomUUID().toString()
        val parts = ArrayList<String>()
        var s = 0
        while (s < text.length) {
            var e = minOf(s + 50000, text.length)
            if (e < text.length && Character.isHighSurrogate(text[e - 1])) e--   // ماننقسمش زوج سيرجيت (إيموجي)
            parts.add(text.substring(s, e)); s = e
        }
        if (parts.isEmpty()) return "الملف فاضي"
        var done = false
        for ((i, p) in parts.withIndex()) {
            val (c, t) = try { RemoteClient.postJson("/subup", JSONObject().put("id", id).put("i", i).put("t", parts.size).put("n", name).put("d", p)) } catch (e: Throwable) { Pair(-1, e.message ?: "") }
            if (c == -1) return "مش متصل بالتلفزيون"
            if (c == 422) return "التلفزيون شاف الملف مش ترجمة صالحة"
            if (c != 200) return "فشل الإرسال للتلفزيون (كود $c)"
            val j = try { JSONObject(t) } catch (_: Throwable) { JSONObject() }
            if (j.optBoolean("none", false)) return "مفيش فيديو شغّال على التلفزيون — شغّل فيديو الأول"
            if (j.optBoolean("done", false)) { done = true; if (!j.optBoolean("player", true)) return "مفيش فيديو شغّال على التلفزيون — شغّل فيديو الأول" }
        }
        return if (done) "" else "التلفزيون ما كمّلش استلام الملف — جرّب تاني"
    }
}
