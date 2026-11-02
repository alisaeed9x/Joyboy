package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * (v542) م1 — منطق نقي (من غير Android) لنظام «التلفزيون = المخ».
 * متفصل في ملف لوحده عشان يتختبر على JVM (SubCodecTest / SyncDeltaTest).
 */

/** Sub ↔ JSON بكل الـ22 حقل (نفس ترتيب data class Sub في Core.kt) */
object SubCodec {
    private fun strs(l: List<String>): JSONArray { val a = JSONArray(); for (s in l) a.put(s); return a }
    private fun strsOf(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) out.add(a.optString(i))
        return out
    }

    fun toJson(s: Sub): JSONObject = JSONObject()
        .put("start", s.start).put("end", s.end)
        .put("original", s.original).put("translated", s.translated)
        .put("gender", s.gender).put("addressee", s.addressee).put("topicGender", s.topicGender)
        .put("people", strs(s.people)).put("places", strs(s.places))
        .put("isSong", s.isSong).put("lowConf", s.lowConf)
        .put("chunk", s.chunk)
        .put("emotion", s.emotion).put("overlap", s.overlap).put("speakerTag", s.speakerTag)
        .put("isContinuation", s.isContinuation).put("pivot", s.pivot)
        .put("faint", s.faint).put("conv", s.conv).put("isSound", s.isSound)
        .put("voice", s.voice).put("speakerName", s.speakerName)

    fun fromJson(o: JSONObject): Sub = Sub(
        start = o.optDouble("start", 0.0), end = o.optDouble("end", 0.0),
        original = o.optString("original", ""), translated = o.optString("translated", ""),
        gender = o.optString("gender", ""), addressee = o.optString("addressee", ""), topicGender = o.optString("topicGender", ""),
        people = strsOf(o.optJSONArray("people")), places = strsOf(o.optJSONArray("places")),
        isSong = o.optBoolean("isSong", false), lowConf = o.optBoolean("lowConf", false),
        chunk = o.optInt("chunk", -1),
        emotion = o.optString("emotion", ""), overlap = o.optBoolean("overlap", false), speakerTag = o.optString("speakerTag", ""),
        isContinuation = o.optBoolean("isContinuation", false), pivot = o.optString("pivot", ""),
        faint = o.optBoolean("faint", false), conv = o.optBoolean("conv", false), isSound = o.optBoolean("isSound", false),
        voice = o.optString("voice", ""), speakerName = o.optString("speakerName", "")
    )

    fun arrayToJson(l: List<Sub>): JSONArray { val a = JSONArray(); for (s in l) a.put(toJson(s)); return a }
    fun arrayFromJson(a: JSONArray?): List<Sub> {
        if (a == null) return emptyList()
        val out = ArrayList<Sub>(a.length())
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; out.add(fromJson(o)) }
        return out
    }
}

/** gzip + base64 للـsnapshot الكامل (أول اتصال) */
object BrainZip {
    fun pack(s: String): String {
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(bo.toByteArray())
    }
    fun unpack(b64: String): String {
        val raw = Base64.getDecoder().decode(b64)
        return GZIPInputStream(ByteArrayInputStream(raw)).use { String(it.readBytes(), Charsets.UTF_8) }
    }
}

/**
 * جانب التلفزيون: بيتتبّع التغيير في قايمة الترجمة بالباتش (chunk).
 * كل باتش اتغيّر بياخد رقم نسخة (ver)؛ الباتش اللي اختفى بيتسجّل في removed.
 */
class SubDelta {
    private val chunkJson = HashMap<Int, String>()
    private val chunkVer = HashMap<Int, Long>()
    private val removed = HashMap<Int, Long>()

    /** @return true لو فيه أي تغيير. nextVer بتتنادى مرة واحدة بس (ولو فيه تغيير فعلًا) */
    @Synchronized
    fun update(subs: List<Sub>, nextVer: () -> Long): Boolean {
        val groups = LinkedHashMap<Int, ArrayList<Sub>>()
        for (s in subs) groups.getOrPut(s.chunk) { ArrayList() }.add(s)
        val fresh = HashMap<Int, String>()
        for ((c, l) in groups) fresh[c] = SubCodec.arrayToJson(l).toString()
        val changed = ArrayList<Int>()
        for ((c, js) in fresh) if (chunkJson[c] != js) changed.add(c)
        val gone = chunkJson.keys.filter { it !in fresh }
        if (changed.isEmpty() && gone.isEmpty()) return false
        val v = nextVer()
        for (c in changed) { chunkJson[c] = fresh[c]!!; chunkVer[c] = v; removed.remove(c) }
        for (c in gone) { chunkJson.remove(c); chunkVer.remove(c); removed[c] = v }
        if (removed.size > 500) {
            val keep = removed.entries.sortedByDescending { it.value }.take(300)
            removed.clear(); for (e in keep) removed[e.key] = e.value
        }
        return true
    }

    /** since = 0 ← snapshot كامل (full=true). غير كده ← الباتشات اللي نسختها أحدث + المحذوفة */
    @Synchronized
    fun since(since: Long): JSONObject {
        val full = since <= 0L
        val chunks = JSONObject()
        for ((c, js) in chunkJson) if (full || (chunkVer[c] ?: 0L) > since) chunks.put(c.toString(), JSONArray(js))
        val rem = JSONArray()
        if (!full) for ((c, v) in removed) if (v > since) rem.put(c)
        return JSONObject().put("full", full).put("chunks", chunks).put("removed", rem)
    }

    @Synchronized
    fun reset() { chunkJson.clear(); chunkVer.clear(); removed.clear() }
}

/** جانب الموبايل: بيطبّق delta على نسخة محلية ويرجّع القايمة النهائية (مرتبة بالبداية) */
object SubSync {
    fun apply(state: MutableMap<Int, List<Sub>>, delta: JSONObject): List<Sub> {
        if (delta.optBoolean("full", false)) state.clear()
        val rem = delta.optJSONArray("removed")
        if (rem != null) for (i in 0 until rem.length()) state.remove(rem.optInt(i))
        val ch = delta.optJSONObject("chunks")
        if (ch != null) {
            val it = ch.keys()
            while (it.hasNext()) { val k = it.next(); state[k.toInt()] = SubCodec.arrayFromJson(ch.optJSONArray(k)) }
        }
        val out = ArrayList<Sub>()
        for (k in state.keys.sorted()) out.addAll(state[k]!!)
        return out.sortedBy { it.start }
    }
}

/**
 * ناقل الأحداث + رقم النسخة (ver) + آخر حالة للمشغّل.
 * /sync بيستنى هنا (long-poll) من غير ما يلمس الـUI thread.
 */
object BrainBus {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val events = ArrayDeque<Pair<Long, JSONObject>>()

    @Volatile var ver: Long = 0L
        private set
    @Volatile var stateJson: String = "{}"
        private set
    @Volatile var lastSync: Long = 0L
    val subs = SubDelta()

    /** (v545) قيد الريموتات: أول متحكّم يملك التحكم */
    val owner = BrainOwner()

    /** (v544 م6) لقطة المهام (TaskCenter) كـJSON array. مستقلة عن الفيديو فـreset() مابتمسحهاش */
    @Volatile var tasksJson: String = "[]"
        private set

    /** (v544 م5) لقطة حالة المحرك (batches/problems/coverage…) من BrainEngineJson */
    @Volatile var engineJson: String = "{}"
        private set

    /** @return true لو المهام اتغيّرت (ونسخة جديدة اتعلنت) */
    fun setTasks(js: String): Boolean {
        if (js == tasksJson) return false
        tasksJson = js
        nextVer(); wake()
        return true
    }

    /** @return true لو لقطة المحرك اتغيّرت (ونسخة جديدة اتعلنت) */
    fun setEngine(js: String): Boolean {
        if (js == engineJson) return false
        engineJson = js
        nextVer(); wake()
        return true
    }

    /** فيه ريموت بيسأل دلوقتي؟ (لو لأ، المشغّل مايضيّعش وقت في تجهيز الحالة) */
    fun active(now: Long = System.currentTimeMillis()): Boolean = now - lastSync < 30000L

    private fun nextVer(): Long {
        lock.lock()
        try { ver += 1; return ver } finally { lock.unlock() }
    }

    private fun wake() { lock.lock(); try { cond.signalAll() } finally { lock.unlock() } }

    /** حدث: t = log|status|notice|… */
    fun post(t: String, s: String) {
        val v = nextVer()
        lock.lock()
        try {
            events.addLast(v to JSONObject().put("t", t).put("s", s).put("v", v))
            while (events.size > 300) events.removeFirst()
        } finally { lock.unlock() }
        wake()
    }

    /** @return true لو الحالة اتغيّرت (ونسخة جديدة اتعلنت) */
    fun setState(js: String): Boolean {
        if (js == stateJson) return false
        stateJson = js
        nextVer(); wake()
        return true
    }

    fun updateSubs(l: List<Sub>): Boolean {
        val ch = subs.update(l) { nextVer() }
        if (ch) wake()
        return ch
    }

    fun eventsSince(since: Long): JSONArray {
        val a = JSONArray()
        lock.lock()
        try { for ((v, o) in events) if (v > since) a.put(o) } finally { lock.unlock() }
        return a
    }

    /** يستنى لحد ما ver > since أو المهلة تخلص. بيرجّع ver الحالي */
    fun await(since: Long, waitMs: Long): Long {
        var left = TimeUnit.MILLISECONDS.toNanos(waitMs.coerceIn(0L, 25000L))
        lock.lock()
        try {
            while (ver <= since && left > 0L) left = cond.awaitNanos(left)
            return ver
        } catch (_: InterruptedException) { return ver } finally { lock.unlock() }
    }

    /** للاختبارات / بداية فيديو جديد */
    fun reset() {
        lock.lock()
        try { events.clear(); stateJson = "{}"; engineJson = "{}" } finally { lock.unlock() }
        subs.reset()
        nextVer(); wake()
    }
}

/** (v544 م6) صف مهمة واحدة زي ما الموبايل بيعرضه */
data class TaskRow(
    val id: Int, val kind: String, val title: String, val state: Int,
    val pct: Int, val msg: String, val paused: Boolean, val outSize: Long
)

/** (v544 م6) TaskItem ← JSON ← TaskRow (منطق نقي عشان يتختبر على JVM) */
object BrainTasksJson {
    fun encode(rows: List<TaskRow>): String {
        val a = JSONArray()
        for (t in rows) a.put(
            JSONObject().put("id", t.id).put("kind", t.kind).put("title", t.title).put("state", t.state)
                .put("pct", t.pct).put("msg", t.msg).put("paused", t.paused).put("outSize", t.outSize)
        )
        return a.toString()
    }

    fun decode(js: String): List<TaskRow> {
        val a = try { JSONArray(js) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<TaskRow>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(TaskRow(
                o.optInt("id"), o.optString("kind"), o.optString("title"), o.optInt("state"),
                o.optInt("pct"), o.optString("msg"), o.optBoolean("paused"), o.optLong("outSize")
            ))
        }
        return out
    }
}

/**
 * (v545) قيد الريموتات (الجزء د-7): أول متحكّم (token) يملك التحكم؛ التاني قراءة فقط لحد ما يعمل control.take.
 * الملكية بتنتهي لو المالك ما بعتش أمر لمدة ttl. token فاضي = مسموح (مسارات قديمة/محلية).
 */
class BrainOwner(private val ttlMs: Long = 45000L) {
    private var owner = ""
    private var at = 0L

    /** @return true لو الطلب مسموح (وبيجدّد الملكية) */
    @Synchronized
    fun claim(who: String, now: Long): Boolean {
        if (who.isEmpty()) return true
        if (owner.isEmpty() || owner == who || now - at > ttlMs) { owner = who; at = now; return true }
        return false
    }

    /** «استلم التحكم» */
    @Synchronized
    fun take(who: String, now: Long) { if (who.isNotEmpty()) { owner = who; at = now } }

    @Synchronized
    fun isOwner(who: String, now: Long): Boolean = who.isEmpty() || owner.isEmpty() || owner == who || now - at > ttlMs

    @Synchronized
    fun reset() { owner = ""; at = 0L }
}

/** (v545) Chr ↔ JSON (شخصيات الفيديو) */
object BrainChars {
    fun encode(l: List<Chr>): JSONArray {
        val a = JSONArray()
        for (c in l) a.put(JSONObject().put("n", c.name).put("g", c.gender).put("r", c.role))
        return a
    }

    fun decode(a: JSONArray?): List<Chr> {
        if (a == null) return emptyList()
        val out = ArrayList<Chr>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Chr(o.optString("n"), o.optString("g"), o.optString("r")))
        }
        return out
    }
}

/** (v545) نسخة محفوظة (الاسم/اللهجة/عدد الجمل) من غير الجمل نفسها */
data class VerRow(val name: String, val dialect: String, val count: Int)

/**
 * (v544 م5 + v545) لقطة حالة المحرك اللي الموبايل بيعرضها (batches/problems/coverage/الأعلام/الإصدارات/الشخصيات).
 * جانب التلفزيون بيبنيها من EngineBackend على خيط خلفي؛ جانب الموبايل (RemoteEngine) بيفكّها.
 */
object BrainEngineJson {
    class Snap(
        val coverage: List<DoubleArray>, val pending: List<DoubleArray>, val refining: List<DoubleArray>,
        val batches: List<Engine.BatchInfo>, val problems: List<Engine.BatchInfo>,
        val rows: List<Pair<String, Boolean>>, val failed: Int, val fullyDone: Boolean,
        val offBrief: String?, val offDetail: String?,
        val covered: Double = 0.0, val pendingCount: Int = 0,
        val userPaused: Boolean = false, val paused: Boolean = false, val transOnly: Boolean = false, val keepGoing: Boolean = false,
        val convDialect: String = "", val toneStyle: String = "", val toneStrength: String = "", val titleHint: String = "",
        val skipIfBaseLang: Boolean = false, val baseSkipped: Boolean = false, val baseLangCode: String = "",
        val versions: List<VerRow> = emptyList(), val chars: List<Chr> = emptyList()
    )

    private fun segs(l: List<DoubleArray>): JSONArray {
        val a = JSONArray()
        for (s in l) { val p = JSONArray(); for (x in s) p.put(x); a.put(p) }
        return a
    }

    private fun segsOf(a: JSONArray?): List<DoubleArray> {
        if (a == null) return emptyList()
        val out = ArrayList<DoubleArray>(a.length())
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            out.add(DoubleArray(p.length()) { k -> p.optDouble(k, 0.0) })
        }
        return out
    }

    private fun bis(l: List<Engine.BatchInfo>): JSONArray {
        val a = JSONArray()
        for (b in l) a.put(JSONObject().put("i", b.idx).put("s", b.start).put("e", b.end).put("m", b.mark).put("c", b.count))
        return a
    }

    private fun bisOf(a: JSONArray?): List<Engine.BatchInfo> {
        if (a == null) return emptyList()
        val out = ArrayList<Engine.BatchInfo>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Engine.BatchInfo(o.optInt("i"), o.optDouble("s", 0.0), o.optDouble("e", 0.0), o.optString("m"), o.optInt("c")))
        }
        return out
    }

    fun encode(s: Snap): String {
        val rows = JSONArray()
        for ((t, f) in s.rows) rows.put(JSONObject().put("t", t).put("f", f))
        val vs = JSONArray()
        for (v in s.versions) vs.put(JSONObject().put("n", v.name).put("d", v.dialect).put("c", v.count))
        val o = JSONObject().put("cov", segs(s.coverage)).put("pend", segs(s.pending)).put("ref", segs(s.refining))
            .put("batches", bis(s.batches)).put("problems", bis(s.problems)).put("rows", rows)
            .put("failed", s.failed).put("done", s.fullyDone)
            .put("covered", s.covered).put("pc", s.pendingCount)
            .put("up", s.userPaused).put("pz", s.paused).put("to", s.transOnly).put("kg", s.keepGoing)
            .put("cd", s.convDialect).put("ts", s.toneStyle).put("tn", s.toneStrength).put("th", s.titleHint)
            .put("sb", s.skipIfBaseLang).put("bs", s.baseSkipped).put("bl", s.baseLangCode)
            .put("vers", vs).put("chars", BrainChars.encode(s.chars))
        if (s.offBrief != null) o.put("offBrief", s.offBrief)
        if (s.offDetail != null) o.put("offDetail", s.offDetail)
        return o.toString()
    }

    fun decode(js: String): Snap {
        val o = try { JSONObject(js) } catch (_: Exception) { JSONObject() }
        val rows = ArrayList<Pair<String, Boolean>>()
        val ra = o.optJSONArray("rows")
        if (ra != null) for (i in 0 until ra.length()) {
            val r = ra.optJSONObject(i) ?: continue
            rows.add(r.optString("t") to r.optBoolean("f"))
        }
        val vers = ArrayList<VerRow>()
        val va = o.optJSONArray("vers")
        if (va != null) for (i in 0 until va.length()) {
            val v = va.optJSONObject(i) ?: continue
            vers.add(VerRow(v.optString("n"), v.optString("d"), v.optInt("c")))
        }
        return Snap(
            segsOf(o.optJSONArray("cov")), segsOf(o.optJSONArray("pend")), segsOf(o.optJSONArray("ref")),
            bisOf(o.optJSONArray("batches")), bisOf(o.optJSONArray("problems")), rows,
            o.optInt("failed"), o.optBoolean("done"),
            if (o.has("offBrief")) o.optString("offBrief") else null,
            if (o.has("offDetail")) o.optString("offDetail") else null,
            o.optDouble("covered", 0.0), o.optInt("pc"),
            o.optBoolean("up"), o.optBoolean("pz"), o.optBoolean("to"), o.optBoolean("kg"),
            o.optString("cd"), o.optString("ts"), o.optString("tn"), o.optString("th"),
            o.optBoolean("sb"), o.optBoolean("bs"), o.optString("bl"),
            vers, BrainChars.decode(o.optJSONArray("chars"))
        )
    }
}
