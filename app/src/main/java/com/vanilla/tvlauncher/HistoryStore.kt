package com.vanilla.tvlauncher

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/** سجل المشاهدة المحلي: نسخة واحدة في الذاكرة + ملف يتكتب بشكل ذرّي في خيط خلفي (يفضل بعد إعادة تشغيل التلفزيون). */
object HistoryStore {
    @Volatile var entries: List<WatchEntry> = emptyList()
    private var loaded = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "history.v1.txt")

    @Synchronized fun load(ctx: Context) {
        if (loaded) return; loaded = true
        try { val f = file(ctx); if (f.isFile) entries = HistoryLogic.decode(f.readText()) } catch (_: Throwable) { }
    }

    @Synchronized fun entryFor(v: VideoItem): WatchEntry? = entries.firstOrNull { it.key == v.key }

    @Synchronized fun record(ctx: Context, v: VideoItem, pos: Long, dur: Long, ended: Boolean = false) {
        val n = HistoryLogic.record(entries, v, pos, dur, System.currentTimeMillis(), ended)
        if (n !== entries) { entries = n; persist(ctx) }
    }
    @Synchronized fun remove(ctx: Context, key: String) { entries = HistoryLogic.remove(entries, key); persist(ctx) }
    @Synchronized fun clear(ctx: Context) { entries = emptyList(); persist(ctx) }

    private fun persist(ctx: Context) {
        val text = HistoryLogic.encode(entries); val f = file(ctx)
        io.execute {
            try {
                val tmp = File(f.path + ".tmp"); tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
            } catch (_: Throwable) { }
        }
    }
}
