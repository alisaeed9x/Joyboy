package com.tttt.subtitler

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * (v1031 — المرحلة 1) «ترجمة» = تفريغ ثم ترجمة أوتوماتيك على مرحلتين متسلسلتين:
 *   ① تفريغ الصوت (Lecture.start — نفس خط التفريغ بالظبط)  ②  ترجمة التفريغ لـSRT (Studio.translateToSrt).
 * قبل كده زرار «💬 ترجمة لملف SRT» في كارت الفيديو كان بيعمل ① بس والمستخدم يفتكر إن الترجمة اشتغلت.
 *
 * الحالة (State) في الذاكرة بس — الواجهة (LectureUi.refreshStatus) بتقراها كل ثانية وبتعرض رسالة المرحلة
 * وشريط التقدّم. مفيش حاجة هنا بتتخزّن: التفريغ بيتحفظ جزء جزء بنفسه (Lecture.save)، والترجمة بتتحفظ في
 * مكتبة الترجمات (Studio.persistSrt)، فلو التطبيع اتقفل في النص الدوكيومنت بيكمّل من «إكمال الناقص».
 */
object StudioAutoTr {
    class State(val docId: String) {
        @Volatile var stage = "transcribe"   // transcribe · translate · done · error · stopped
        @Volatile var msg = ""
        @Volatile var subs = 0
        @Volatile var failed = 0
        @Volatile var cancel = false
        val active: Boolean get() = stage == "transcribe" || stage == "translate"
        /** الحالة دي لسه تستاهل تتعرض (شغّالة أو خلصت/فشلت من التشغيلة دي) */
        val ok: Boolean get() = stage == "done"
    }

    private val states = ConcurrentHashMap<String, State>()

    fun state(id: String): State? = states[id]
    fun isActive(id: String): Boolean = states[id]?.active == true

    fun stop(id: String) {
        val st = states[id] ?: return
        st.cancel = true
        Lecture.stop(id)
    }

    /** يبدأ المرحلتين على دوكيومنت. لو شغّال أصلًا بيرجّع حالته من غير ما يبدأ تاني. */
    fun start(ctx0: Context, d: Lecture.Doc): State {
        val ctx = ctx0.applicationContext
        val old = states[d.id]
        if (old != null && old.active) return old
        val st = State(d.id)
        st.msg = "① بفرّغ الصوت…"
        states[d.id] = st
        LogStore.add("💬 [ترجمة] بدأت المرحلتين (تفريغ ← ترجمة) للدوكيومنت " + d.id)
        Thread {
            try {
                // ① التفريغ — لو مكتمل أصلًا بنعدّي عليه
                val needTx = synchronized(d) { !d.complete }
                if (needTx) {
                    Lecture.start(ctx, d)
                    while (Lecture.isRunning(d.id) && !st.cancel) {
                        val r = Lecture.runOf(d.id)
                        val pct = synchronized(d) { d.pct }
                        st.msg = "① تفريغ الصوت — " + pct + "٪" + (if (r != null && r.status.isNotBlank()) " · " + r.status else "")
                        try { Thread.sleep(700) } catch (_: InterruptedException) { break }
                    }
                }
                if (st.cancel) { st.stage = "stopped"; st.msg = "⏸ اتوقفت — اللي اتفرّغ اتحفظ"; return@Thread }
                val segCount = synchronized(d) { d.segs.size }
                val runErr = Lecture.runOf(d.id)?.error ?: ""
                if (segCount == 0) {
                    st.stage = "error"
                    st.msg = "⚠ " + (runErr.ifBlank { "التفريغ ما رجّعش جمل" }).take(110)
                    return@Thread
                }
                if (runErr.isNotBlank() && synchronized(d) { d.done.isEmpty() }) {
                    st.stage = "error"; st.msg = "⚠ " + runErr.take(110); return@Thread
                }
                val missingTx = synchronized(d) { d.failed.size }
                // ② الترجمة
                st.stage = "translate"
                st.msg = "② بترجم " + segCount + " جملة…"
                val res = Studio.translateToSrt(ctx, d, report = { m -> st.msg = "② ترجمة — " + m }, cancel = { st.cancel })
                if (st.cancel) { st.stage = "stopped"; st.msg = "⏸ اتوقفت الترجمة"; return@Thread }
                st.subs = res.subs
                st.failed = res.failed
                if (!res.ok) {
                    st.stage = "error"
                    st.msg = "⚠ " + res.error.ifBlank { "الترجمة ما رجّعتش سطور" }.take(110)
                    return@Thread
                }
                val notes = ArrayList<String>()
                if (res.failed > 0) notes.add(res.failed.toString() + " سطر فضل بنصه الأصلي")
                if (missingTx > 0) notes.add(missingTx.toString() + " جزء تفريغ ناقص — دوس «إكمال الناقص»")
                st.stage = "done"
                st.msg = "✅ اتفرّغ واترجم (" + res.subs + " سطر)" + (if (notes.isEmpty()) "" else " · " + notes.joinToString(" · "))
                LogStore.add("💬 [ترجمة] خلصت: " + res.subs + " سطر، فاشل " + res.failed + "، أجزاء ناقصة " + missingTx)
            } catch (e: Throwable) {
                LogStore.err("StudioAutoTr", e)
                st.stage = "error"
                st.msg = "⚠ " + (e.message ?: e.javaClass.simpleName).take(100)
            }
        }.apply { isDaemon = true }.start()
        return st
    }
}
