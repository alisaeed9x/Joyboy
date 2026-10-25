package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject

/**
 * (v545 م2-أ) منطق نقي (من غير Android) لجانب الموبايل: تطبيق رد /sync على نسخة محلية (mirror).
 * متفصل في ملف لوحده عشان يتختبر على JVM.
 */
class SyncChange(
    val ver: Long,
    val stateChanged: Boolean,
    val subsChanged: Boolean,
    val engChanged: Boolean,
    val tasksChanged: Boolean,
    val events: List<JSONObject>,
    /** true = النسخة المحلية اتمسحت (فيديو جديد أو التلفزيون اتعمله restart): كرّر الطلب بـsince=0 فورًا */
    val reset: Boolean
) {
    fun any(): Boolean = stateChanged || subsChanged || engChanged || tasksChanged || events.isNotEmpty()
}

object PosExtrap {
    /** الموضع التقديري بين تحديثين: lastPos + (now - stamp) * speed لو شغّال */
    fun pos(lastPos: Long, stampMs: Long, nowMs: Long, speed: Double, playing: Boolean, durMs: Long): Long {
        var p = lastPos
        if (playing && nowMs > stampMs) p += ((nowMs - stampMs) * speed).toLong()
        if (durMs > 0 && p > durMs) p = durMs
        return if (p < 0L) 0L else p
    }

    /** السرعة من اللابل (مثلًا "1.25x") */
    fun speedOf(label: String): Double {
        val m = Regex("[0-9]+(\\.[0-9]+)?").find(label) ?: return 1.0
        val v = m.value.toDoubleOrNull() ?: return 1.0
        return if (v < 0.1) 1.0 else if (v > 4.0) 4.0 else v
    }

    /** "opdone" / "opreport": النص بيبقى "اسم_الأمر|الرسالة" */
    fun splitOp(s: String): Pair<String, String> {
        val i = s.indexOf('|')
        return if (i < 0) Pair(s, "") else Pair(s.substring(0, i), s.substring(i + 1))
    }
}

class LinkMirror {
    @Volatile var since: Long = 0L
        private set
    /** قائمة جديدة عند كل تغيير (الـtick بيقارن بالمرجع) */
    @Volatile var subs: List<Sub> = emptyList()
        private set
    @Volatile var st: JSONObject = JSONObject()
        private set
    @Volatile var eng: BrainEngineJson.Snap = BrainEngineJson.decode("{}")
        private set
    @Volatile var tasks: List<TaskRow> = emptyList()
        private set
    @Volatile var live: Boolean = false
        private set
    /** false = متحكّم تاني ماسك التحكم (قراءة فقط) */
    @Volatile var owner: Boolean = true
        private set
    @Volatile var gotFirst: Boolean = false
        private set
    @Volatile var stStamp: Long = 0L
        private set

    private val chunks = HashMap<Int, List<Sub>>()
    private var lastVid = ""
    private var lastSt = ""
    private var lastEng = ""
    private var lastTasks = ""

    private fun clear() {
        since = 0L; chunks.clear(); subs = emptyList(); gotFirst = false
        lastVid = ""; lastSt = ""; lastEng = ""; lastTasks = ""
        eng = BrainEngineJson.decode("{}")
    }

    @Synchronized
    fun apply(resp: JSONObject, now: Long): SyncChange {
        val ver = resp.optLong("ver", 0L)
        val stJs = resp.optJSONObject("st") ?: JSONObject()
        val vid = stJs.optString("vid", "")
        if (ver < since || (lastVid.isNotEmpty() && vid.isNotEmpty() && vid != lastVid)) {
            clear()
            return SyncChange(0L, false, false, false, false, emptyList(), true)
        }
        if (vid.isNotEmpty()) lastVid = vid
        val s = stJs.toString()
        val stCh = s != lastSt
        if (stCh) { lastSt = s; st = stJs; stStamp = now }
        live = resp.optBoolean("live", false)
        owner = resp.optBoolean("ctl", true)

        var subsCh = false
        val gz = resp.optString("subsGz", "")
        val d: JSONObject? = if (gz.isNotEmpty()) {
            try { JSONObject(BrainZip.unpack(gz)) } catch (_: Exception) { null }
        } else resp.optJSONObject("subs")
        if (d != null) {
            val full = d.optBoolean("full", false)
            val nCh = d.optJSONObject("chunks")?.length() ?: 0
            val nRem = d.optJSONArray("removed")?.length() ?: 0
            if (full || nCh > 0 || nRem > 0) { subs = SubSync.apply(chunks, d); subsCh = true }
        }

        var engCh = false
        val eJs = (resp.optJSONObject("eng") ?: JSONObject()).toString()
        if (eJs != lastEng) { lastEng = eJs; eng = BrainEngineJson.decode(eJs); engCh = true }

        var tkCh = false
        val tJs = (resp.optJSONArray("tasks") ?: JSONArray()).toString()
        if (tJs != lastTasks) { lastTasks = tJs; tasks = BrainTasksJson.decode(tJs); tkCh = true }

        val evs = ArrayList<JSONObject>()
        val ea = resp.optJSONArray("events")
        if (ea != null) for (i in 0 until ea.length()) { val o = ea.optJSONObject(i) ?: continue; evs.add(o) }

        since = ver
        gotFirst = true
        return SyncChange(ver, stCh, subsCh, engCh, tkCh, evs, false)
    }

    /** الموضع الحالي بالـextrapolation (من الذاكرة بس، من غير شبكة) */
    fun position(now: Long): Long {
        val j = st
        return PosExtrap.pos(
            j.optLong("pos", 0L), stStamp, now, PosExtrap.speedOf(j.optString("speed", "1")),
            j.optBoolean("playing", false), j.optLong("dur", 0L)
        )
    }
}
