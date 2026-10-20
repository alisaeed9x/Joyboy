package com.tttt.subtitler

import android.content.Intent
import android.net.Uri

/** (v349) عنصر في السجل العام: kind = v فيديو / a صوت · src = browser / youtube / local / music */
class PubItem(val kind: String, val src: String, val title: String, val ref: String, val ts: Long)

/** (v349) السجل العام للفيديوهات والمقاطع الصوتية المتشافة/المتسمّعة، مقسّم بالمصدر (متصفح · يوتيوب · الجهاز · مشغّل الموسيقى). محفوظ في Cfg، حد أقصى 150 لكل قسم */
object PubHistory {
    private const val KEY = "pub_hist"
    private const val SEP = "\u0001"
    private const val CAP = 150
    fun on() = Cfg.str("pub_hist_on", "1") != "0"

    private fun read(): MutableList<PubItem> = Cfg.str(KEY, "").lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
        val p = l.split(SEP)
        if (p.size < 5) null else PubItem(p[0], p[1], p[2], p[3], p[4].toLongOrNull() ?: 0L)
    }.toMutableList()

    private fun clean(s: String) = s.replace('\n', ' ').replace(SEP, " ")
    private fun write(l: List<PubItem>) {
        Cfg.put(KEY, l.joinToString("\n") { listOf(it.kind, it.src, clean(it.title), clean(it.ref), it.ts.toString()).joinToString(SEP) })
    }

    @Synchronized fun add(kind: String, src: String, title: String, ref: String) {
        if (ref.isBlank() || !on()) return
        val l = read()
        l.removeAll { it.kind == kind && it.src == src && it.ref == ref }
        l.add(0, PubItem(kind, src, title.ifBlank { ref.take(80) }, ref, System.currentTimeMillis()))
        val out = ArrayList<PubItem>(); val cnt = HashMap<String, Int>()
        for (x in l) { val k = x.kind + x.src; val n = (cnt[k] ?: 0) + 1; cnt[k] = n; if (n <= CAP) out.add(x) }
        write(out)
    }

    /** بيتنده من PlayerActivity.onCreate: يوتيوب (ytid) · ملف من الجهاز (data) · لينك متصفح (url) */
    fun addVideoFrom(i: Intent) {
        val title = i.getStringExtra("title") ?: ""
        val yt = i.getStringExtra("ytid")
        val d: Uri? = i.data
        if (yt != null && yt.length == 11) { add("v", "youtube", title, yt); return }
        if (d != null) { val u = d.toString(); add("v", "local", title.ifBlank { Uri.decode(u.substringAfterLast('/')).substringBeforeLast('.') }, u); return }
        val u = i.getStringExtra("url") ?: return
        add("v", "browser", title, u)
    }

    @Synchronized fun list(kind: String, src: String): List<PubItem> = read().filter { it.kind == kind && it.src == src }
    @Synchronized fun remove(x: PubItem) { val l = read(); if (l.removeAll { it.kind == x.kind && it.src == x.src && it.ref == x.ref }) write(l) }
    /** src = null: كل أقسام النوع ده */
    @Synchronized fun clear(kind: String, src: String?) { write(read().filter { !(it.kind == kind && (src == null || it.src == src)) }) }
    @Synchronized fun clearAll() { Cfg.put(KEY, "") }
}
