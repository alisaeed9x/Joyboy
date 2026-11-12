package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import java.io.File

/**
 * (v598 م3) شاشات باك الأيقونات على التلفزيون (كلها بالريموت): اختيار الباك · استيراد zip (من فولدرات معروفة أو من نظام الملفات) ·
 * تقرير الفحص (أخطاء/تحذيرات) · حذف باك مستورد · تصدير قالب للتعديل.
 * الباك مستقل عن السكِن: الاختيار بيتطبّق حي على أي سكِن (IconPacks → Icons.draw).
 */
object IconPackUi {
    /** رقم طلب اختيار ملف من نظام الملفات (onActivityResult في TvActivity) */
    const val REQ_PICK = 6101

    /** إعادة رسم كل الـViews الظاهرة (الأيقونات بتتحسب وقت الرسم، فكفاية invalidate) */
    fun repaint(a: Activity) {
        fun walk(v: View) { v.invalidate(); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        try { a.window?.decorView?.let { walk(it) } } catch (_: Throwable) {}
    }

    /** القايمة الرئيسية. onApplied بيتنده بعد أي تغيير (لتحديث صفحة المظهر). */
    fun show(a: Activity, onApplied: () -> Unit) {
        val packs = IconPacks.list()
        val cur = IconPacks.activeId()
        val rows = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        for (p in packs) {
            rows.add((if (p.id == cur) "✔ " else "") + p.name + (if (!p.builtin) "  (مستورد)" else ""))
            acts.add { apply(a, p.id, onApplied) }
        }
        rows.add("➕ استيراد باك (zip)…"); acts.add { importMenu(a, onApplied) }
        rows.add("📤 صدّر قالب للتعديل"); acts.add { exportDialog(a, null) }
        if (cur != IconPacks.DEFAULT) { rows.add("📤 صدّر الباك الحالي كملف zip"); acts.add { exportDialog(a, cur) } }
        if (packs.any { !it.builtin }) { rows.add("🗑 احذف باك مستورد…"); acts.add { deleteMenu(a, onApplied) } }
        GAlert(a).setTitle("📦 باك الأيقونات")
            .setItems(rows.toTypedArray()) { _, i -> acts.getOrNull(i)?.invoke() }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun apply(a: Activity, id: String, onApplied: () -> Unit) {
        IconPacks.select(id) { ok ->
            if (a.isFinishing || a.isDestroyed) return@select
            if (ok) { repaint(a); Notice.show(a, "✅ باك الأيقونات: " + IconPacks.nameOf(id), 2200L); onApplied() }
            else Notice.show(a, "⚠ ماقدرتش أحمّل الباك ده", 2600L)
        }
    }

    // ===== الاستيراد =====
    private fun importMenu(a: Activity, onApplied: () -> Unit) {
        val zips = IconPacks.inboxZips(a)
        val rows = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        for (f in zips) { rows.add("📦 " + f.name + "  (" + (f.length() / 1024).coerceAtLeast(1) + " ك.ب)"); acts.add { importFile(a, f, onApplied) } }
        rows.add("📂 اختار من نظام الملفات…"); acts.add { pickFromSystem(a) }
        GAlert(a).setTitle("➕ استيراد باك أيقونات")
            .setMessage(
                if (zips.isEmpty()) "مفيش ملفات zip جاهزة. حط الباك (zip فيه pack.json + ملفات svg) في Download أو Movies/Joyboy " +
                    "(أو ابعته من الموبايل بـ 📤) وافتح الشاشة دي تاني، أو اختار من نظام الملفات."
                else "ملفات zip لقيتها — اختار الباك:")
            .setItems(rows.toTypedArray()) { _, i -> acts.getOrNull(i)?.invoke() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun pickFromSystem(a: Activity) {
        try {
            a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PICK)
        } catch (_: Throwable) {
            Notice.show(a, "⚠ التلفزيون مفيهوش مختار ملفات — حط الباك في Download أو Movies/Joyboy", 4200L)
        }
    }

    /** نتيجة اختيار الملف من نظام الملفات (من onActivityResult) */
    fun onPicked(a: Activity, uri: Uri?, onApplied: () -> Unit) {
        if (uri == null) return
        Thread {
            val b = IconPacks.readUri(a, uri)
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (b == null) Notice.show(a, "⚠ الملف مش متقري أو أكبر من " + IconPackReader.MAX_ZIP_BYTES / 1024 + " ك.ب", 3600L)
                else runImport(a, b, onApplied)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun importFile(a: Activity, f: File, onApplied: () -> Unit) {
        Thread {
            val b = try { f.readBytes() } catch (_: Throwable) { null }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (b == null) Notice.show(a, "⚠ ماقدرتش أقرا " + f.name, 3000L) else runImport(a, b, onApplied)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun runImport(a: Activity, bytes: ByteArray, onApplied: () -> Unit) {
        Thread {
            val r = IconPacks.importBytes(bytes)
            a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) report(a, r, onApplied) }
        }.also { it.isDaemon = true }.start()
    }

    /** تقرير الفحص بعد الاستيراد */
    private fun report(a: Activity, r: IconPacks.ImportResult, onApplied: () -> Unit) {
        val txt = r.report.toText(30).ifEmpty { "—" }
        if (r.ok) {
            val id = r.id!!
            GAlert(a).setTitle("✅ اتستورد: " + (r.name ?: id))
                .setMessage(txt)
                .setPositiveButton("طبّقه دلوقتي") { _, _ -> apply(a, id, onApplied) }
                .setNegativeButton("تمام", null).show()
            onApplied()
        } else {
            GAlert(a).setTitle("❌ الباك اترفض")
                .setMessage(txt + "\n\nمفيش حاجة اتغيّرت.")
                .setNegativeButton("تمام", null).show()
        }
    }

    // ===== الحذف والتصدير =====
    private fun deleteMenu(a: Activity, onApplied: () -> Unit) {
        val mine = IconPacks.list().filter { !it.builtin }
        if (mine.isEmpty()) return
        GAlert(a).setTitle("🗑 احذف باك مستورد")
            .setItems(mine.map { it.name }.toTypedArray()) { _, i ->
                val p = mine.getOrNull(i) ?: return@setItems
                GAlert(a).setTitle("🗑 تحذف «" + p.name + "»؟")
                    .setMessage("الملف هيتمسح من التطبيق" + (if (IconPacks.activeId() == p.id) " والأيقونات هترجع للافتراضي." else "."))
                    .setPositiveButton("احذف") { _, _ ->
                        val ok = IconPacks.delete(p.id)
                        repaint(a); Notice.show(a, if (ok) "🗑 اتحذف" else "⚠ ماقدرتش أحذفه", 2200L); onApplied()
                    }
                    .setNegativeButton("إلغاء", null).show()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun exportDialog(a: Activity, id: String?) {
        val f = if (id == null) IconPacks.exportTemplate(a) else IconPacks.exportPack(a, id)
        if (f == null) { Notice.show(a, "⚠ ماقدرتش أعمل الملف", 3000L); return }
        GAlert(a).setTitle("📤 اتعمل ملف الباك")
            .setMessage("المكان:\n" + f.absolutePath + "\n\nخده على الكمبيوتر، عدّل ملفات svg/ (والـ pack.json: id و name)، وارجع استورده. " +
                "الأيقونة اللي تمسحها من الباك بترجع للشكل الافتراضي.")
            .setNegativeButton("تمام", null).show()
    }
}
