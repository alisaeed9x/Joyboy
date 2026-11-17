package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/** (v1038) عنصر واحد في «مركز التحميل» (تحميل فيديو/صوت/متصفح/نقل) */
class TaskItem(val id: Int, val kind: String, val title: String) {
    /** 0 مستني · 1 شغّال · 2 خلص · 3 فشل · 4 اتلغى · 5 اتوقف (⏹) · 6 واقف مؤقتًا من غير ما يكون شغّال (هيبدأ من الأول لما يكمّل) */
    @Volatile var state = 0
    @Volatile var pct = 0
    @Volatile var msg = ""
    @Volatile var outUri: Uri? = null
    @Volatile var outPath = ""
    @Volatile var outSize = 0L
    @Volatile var mime = ""
    @Volatile var cancelled = false
    @Volatile var onCancel: (() -> Unit)? = null
    @Volatile var work: ((TaskItem) -> Unit)? = null
    /** (v137) نسخة من الشغل للإعادة بعد فشل/إلغاء، وإيقاف مؤقت للتحميلات */
    @Volatile var workKeep: ((TaskItem) -> Unit)? = null
    @Volatile var dlDone = 0L; @Volatile var dlTotal = 0L; @Volatile var dlSpeed = 0L   // (v723) حجم/سرعة التحميل الحي
    @Volatile var partPath = ""   // (v723) الملف الجزئي (للتشغيل أثناء التحميل)
    @Volatile var paused = false
    @Volatile var onPause: ((Boolean) -> Unit)? = null
    /** (v147) إيقاف ⏹ (بيفضل في القايمة ويتعاد بعدين) · إيقاف مؤقت للي مالوش استكمال حقيقي · إلغاء بيشيله · إعادة وهو شغّال */
    @Volatile var stopReq = false
    @Volatile var pauseRestart = false
    @Volatile var removeOnEnd = false
    @Volatile var retryAfter = false
    /** (v457) وصف المهمة بالـ JSON عشان تتحفظ على القرص وتتكمّل لو العملية اتقتلت */
    @Volatile var spec = ""
    @Volatile var tries = 0
    /** (v736) المهمة ماسكة خانة من خانات التحميل الـ3 (الواقفة مؤقتًا بتسيب خانتها للي مستني) */
    @Volatile var holdsSlot = false
    /** (v819) ✕ إلغاء حقيقي: الملفات الجزئية بتتمسح. (⏹ إيقاف / الفشل بيسيبوها عشان ▶ استكمال يكمّل من نفس المكان) */
    @Volatile var wipe = false
    /** (v819) ملفات التحميل الجزئي بتاعة المهمة دي (بتتمسح مع الإلغاء/الحذف) */
    val partFiles = java.util.concurrent.CopyOnWriteArraySet<String>()
}

class TaskCancelled : RuntimeException("اتلغت")

/** مركز التحميل: التحميلات بتتنفّذ هنا (3 خانات متوازية) وبتتابع تقدّمها */
object TaskCenter {
    /** (v723) المهمة اللي الخيط الحالي بينفّذها (عشان Downloader يبلّغ بالحجم والسرعة) */
    val cur = ThreadLocal<TaskItem?>()
    val items = CopyOnWriteArrayList<TaskItem>()
    @Volatile var listener: (() -> Unit)? = null
    /** (v188) مستمعين إضافيين (عداد زرار المهام في المشغّل + صفحة المهام المفتوحة جواه) من غير ما نلمس listener بتاع الشاشة الرئيسية */
    val extra = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val q = LinkedBlockingQueue<TaskItem>()
    private var worker: Thread? = null
    /** (v736) 3 خانات تحميل متوازية بالدور (FIFO). كل تحميل في خيط لوحده بيستنى خانة؛ لما واحد يخلص/يقف مؤقتًا/يفشل خانته بتروح للي بعده فورًا */
    private val dlSlots = java.util.concurrent.Semaphore(3, true)
    private var appCtx: Context? = null
    private var seq = 0

    fun appContext(): Context? = appCtx
    /** (v819) مسح الملفات الجزئية بتاعة مهمة (إلغاء / حذف / إعادة من الأول) */
    fun discard(t: TaskItem) {
        for (p in t.partFiles.toList()) { try { File(p).delete(); File("$p.meta").delete() } catch (_: Throwable) {} }
        t.partFiles.clear()
    }
    fun changed() { try { appCtx?.let { TaskStore.save(it); TaskService.sync(it) } } catch (_: Throwable) {}; main.post { try { listener?.invoke() } catch (_: Throwable) {}; for (l in extra) { try { l() } catch (_: Throwable) {} } }; WidgetHub.poke(appCtx); try { if (BrainBus.active()) BrainBus.setTasks(brainJson()) } catch (_: Throwable) {} }
    /** (v544 م6) لقطة المهام للريموت (بتتبعت في /sync) */
    fun brainJson(): String = BrainTasksJson.encode(items.map { TaskRow(it.id, it.kind, it.title, it.state, it.pct, it.msg, it.paused, it.outSize) })
    fun active(): Int = items.count { it.state == 0 || it.state == 1 }
    /** (v324) أي حاجة في صفحة المهام (شغّالة أو خالصة) — الزرار العايم بيفضل ظاهر طول ما فيه حاجة تتفتح */
    fun total(): Int = items.size

    @Synchronized fun add(app: Context, kind: String, title: String, spec: String = "", work: (TaskItem) -> Unit): TaskItem {
        val t = TaskItem(++seq, kind, title); t.work = work; t.workKeep = work; t.spec = spec
        appCtx = app.applicationContext
        items.add(0, t)
        if (kind == "dm") {
            // (v324) تحميلات المتصفح (DownloadManager): بتتابع تقدّم التحميل بس، فكل واحدة في خيط لوحدها من غير طابور (التحميل نفسه شغّال في النظام بالتوازي)
            val ctx = app.applicationContext
            Thread { runOne(ctx, t) }.apply { isDaemon = true; start() }
        } else {
            if (kind == "download") {
                val ctx = app.applicationContext
                Thread { runDownload(ctx, t) }.apply { isDaemon = true; start() }
            } else { q.add(t); ensureWorker(app.applicationContext) }
        }
        changed()
        return t
    }

    private fun ensureWorker(app: Context) {
        if (worker?.isAlive == true) return; worker = Thread { loop(app, q) }.apply { isDaemon = true; start() }
    }

    /** (v736) تحميل: يستنى خانة (state 0 «في الانتظار») وبعدين يشتغل؛ الخانة بترجع في أي حالة (خلص/فشل/اتلغى) */
    private fun runDownload(app: Context, t: TaskItem) {
        while (true) {
            if (t.cancelled) { aborted(t); afterEnd(t); changed(); return }
            val ok = try { dlSlots.tryAcquire(300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return }
            if (ok) break
        }
        synchronized(t) { t.holdsSlot = true }
        try { runOne(app, t) } finally { releaseSlot(t) }
    }
    fun releaseSlot(t: TaskItem) { synchronized(t) { if (t.holdsSlot) { t.holdsSlot = false; dlSlots.release() } } }
    /** (v736) بعد الإيقاف المؤقت: ارجع خانة (لو كلها مشغولة استنى دورك). false = اتلغت وانت مستني */
    fun reacquireSlot(t: TaskItem): Boolean {
        var shown = false
        while (true) {
            if (t.cancelled) return false
            val ok = try { dlSlots.tryAcquire(300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return false }
            if (ok) { synchronized(t) { t.holdsSlot = true }; if (shown) { t.msg = ""; changed() }; return true }
            if (!shown) { shown = true; t.msg = "⏳ مستني دوره (3 تحميلات شغّالة)"; changed() }
        }
    }

    private fun aborted(t: TaskItem) {
        t.state = when { t.pauseRestart -> 6; t.stopReq -> 5; else -> 4 }
        t.msg = when (t.state) { 6 -> "واقفة مؤقتًا — لما تكمّل هتبدأ من الأول"; 5 -> "اتوقفت — دوس ▶ استكمال تكمّل، أو 🔁 تبدأ من الأول"; else -> "اتلغت — دوس 🔁 إعادة تبدأها من الأول" }
        if (t.wipe) discard(t)   // (v819) الإلغاء بيمسح الكاش/الجزئي فعلًا
    }

    /** (v147) بعد ما المهمة تقف فعلاً: لو كانت «إلغاء» تتشال، ولو «إعادة محاولة» وهي شغّالة تتضاف من جديد */
    private fun afterEnd(t: TaskItem) {
        if (t.removeOnEnd) { items.remove(t); return }
        if (t.retryAfter) {
            t.retryAfter = false
            val w = t.workKeep; val a = appCtx
            if (w != null && a != null) { items.remove(t); add(a, t.kind, t.title, t.spec, w) }
        }
    }

    private fun loop(app: Context, q: LinkedBlockingQueue<TaskItem>) {
        while (true) {
            val t = try { q.take() } catch (_: InterruptedException) { return }
            runOne(app, t)
        }
    }

    /** (v324) تنفيذ مهمة واحدة (اتفصلت من loop عشان مهام «dm» تشتغل من غير طابور) */
    private fun runOne(app: Context, t: TaskItem) {
        if (t.cancelled) { aborted(t); afterEnd(t); changed(); return }
        t.state = 1; changed()
        try {
            t.partPath = ""; t.dlDone = 0L; t.dlTotal = 0L; t.dlSpeed = 0L
            cur.set(t)
            try { t.work?.invoke(t) } finally { cur.set(null) }
            if (t.cancelled) aborted(t)
            else {
                t.state = 2; t.pct = 100
                main.post { try { Toast.makeText(app, "✅ خلصت: " + t.title, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} }
            }
        } catch (_: TaskCancelled) { aborted(t)
        } catch (e: Throwable) {
            if (t.cancelled) aborted(t) else {
                t.state = 3
                t.msg = (e.message ?: e.javaClass.simpleName).take(160)
                LogStore.err("Tasks:" + t.kind, e)
            }
        }
        t.work = null; t.onCancel = null; t.onPause = null; t.paused = false; afterEnd(t); changed()
    }

    /** (v819) ✕ إلغاء: بيوقف المهمة وبيمسح الملفات الجزئية، وبتفضل في القايمة «اتلغت» عشان تقدر تعيدها بـ 🔁 (ولو دوست ✕ على مهمة منتهية/واقفة بتتشال) */
    fun cancel(t: TaskItem) {
        when (t.state) {
            1 -> { t.stopReq = false; t.pauseRestart = false; t.removeOnEnd = false; t.wipe = true; t.cancelled = true; t.onPause?.invoke(false); t.onCancel?.invoke() }
            0, 6 -> { t.wipe = true; t.cancelled = true; t.stopReq = false; t.pauseRestart = false; t.state = 4; t.msg = "اتلغت — دوس 🔁 إعادة تبدأها من الأول"; discard(t); changed() }
            else -> { discard(t); items.remove(t); changed() }
        }
    }
    /** (v147) ⏹ إيقاف: بيوقف المهمة وبتفضل في القايمة «اتوقفت» (التحميل بيحتفظ باللي اتحمّل) وتقدر تكمّلها بـ ▶ */
    fun stop(t: TaskItem) {
        when (t.state) {
            1 -> { t.stopReq = true; t.pauseRestart = false; t.wipe = false; t.cancelled = true; t.onPause?.invoke(false); t.onCancel?.invoke() }
            0, 6 -> { t.cancelled = true; t.stopReq = true; t.pauseRestart = false; t.state = 5; t.msg = "اتوقفت — دوس ▶ استكمال تكمّل، أو 🔁 تبدأ من الأول"; changed() }
        }
    }
    /** (v147) ⏸ إيقاف مؤقت / ▶ استكمال: التحميل بيستكمل من نفس المكان؛ باقي المهام (ترميز) مفيهاش استكمال فبتتوقف وتبدأ من الأول لما تكمّل.
     *  (v819) ▶ على مهمة فاشلة/موقوفة (⏹) = كمّل من الملف الجزئي */
    fun pause(t: TaskItem, p: Boolean) {
        if (t.state == 6) { if (!p) resumeSoft(t); return }
        if ((t.state == 3 || t.state == 5) && !p) { retry(t, false); return }
        if (t.state == 1 && t.onPause != null) { t.paused = p; t.onPause?.invoke(p); changed(); return }
        if (!p) return
        if (t.state == 1) { t.pauseRestart = true; t.stopReq = false; t.cancelled = true; t.onCancel?.invoke() }
        else if (t.state == 0) { t.cancelled = true; t.pauseRestart = true; t.state = 6; t.msg = "واقفة مؤقتًا — لما تكمّل هتبدأ من الأول"; changed() }
    }
    private fun resumeSoft(t: TaskItem) {
        val w = t.workKeep ?: return; val app = appCtx ?: return
        items.remove(t); add(app, t.kind, t.title, t.spec, w)
    }
    /** (v147) 🔁 إعادة محاولة: في أي حالة — لو شغّالة بتتوقف وتتبدي من الأول، ولو واقفة/فاشلة/خالصة بتتضاف من جديد.
     *  (v819) fresh=false = كمّل من الملف الجزئي (مفيش مسح) */
    fun retry(t: TaskItem, fresh: Boolean = true) {
        val w = t.workKeep ?: return; val app = appCtx ?: return
        when (t.state) {
            1 -> { t.retryAfter = true; t.stopReq = false; t.pauseRestart = false; t.wipe = fresh; t.cancelled = true; t.onPause?.invoke(false); t.onCancel?.invoke() }
            0 -> { t.cancelled = true; if (fresh) discard(t); items.remove(t); add(app, t.kind, t.title, t.spec, w) }
            else -> { if (fresh) discard(t); items.remove(t); add(app, t.kind, t.title, t.spec, w) }
        }
    }
    /** (v819) أزرار «الكل» */
    fun pauseAll() { for (t in items.toList()) if ((t.state == 1 && !t.paused) || t.state == 0) pause(t, true) }
    fun resumeAll() { for (t in items.toList()) if (t.state == 6 || (t.state == 1 && t.paused) || t.state == 3 || t.state == 5) pause(t, false) }
    fun retryFailed() { for (t in items.toList()) if (t.state == 3 || t.state == 4) retry(t, t.state == 4) }
    fun speedSum(): Long = items.filter { it.state == 1 && !it.paused }.sumOf { it.dlSpeed }
    fun remove(t: TaskItem) { if (t.state == 0 || t.state == 1) cancel(t) else { discard(t); items.remove(t) }; changed() }
    fun clearFinished() { for (t in items.toList()) if (t.state >= 2) { discard(t); items.remove(t) }; changed() }
}

/** (v457) حفظ المهام الشغّالة/المنتظرة على القرص: لو الأندرويد قتل العملية بتتكمّل من الأول لما التطبيق أو الخدمة يرجعوا */
object TaskStore {
    @Volatile private var resumed = false
    private var lastSig = ""
    private fun file(ctx: Context) = File(ctx.filesDir, "tasks_pending.json")

    fun save(ctx: Context) {
        if (!resumed) return   // قبل ما نقرا القديم مانمسحوش الملف
        try {
            val pend = TaskCenter.items.filter { it.spec.isNotBlank() && (it.state == 0 || it.state == 1) }.sortedBy { it.id }
            val sig = pend.joinToString("|") { it.id.toString() + ":" + it.tries }
            if (sig == lastSig) return
            lastSig = sig
            val f = file(ctx)
            if (pend.isEmpty()) { f.delete(); return }
            val ar = JSONArray()
            for (t in pend) ar.put(JSONObject().put("kind", t.kind).put("spec", t.spec).put("tries", t.tries))
            f.writeText(ar.toString())
        } catch (e: Throwable) { LogStore.err("TaskStore:save", e) }
    }

    @Synchronized fun resume(ctx: Context) {
        if (resumed) return
        resumed = true
        val app = ctx.applicationContext
        SegDl.sweep(app)   // (v819) مخلفات الكاش القديمة + تحميلات ناقصة عدّى عليها 3 أيام
        // (v1038) مفيش مهام ترميز بتتكمّل تاني (القص/الـGIF/الضغط اتشالوا) — ملف المهام القديم بيتمسح بس
        try { file(app).delete() } catch (_: Throwable) {}
        lastSig = ""
    }
}
