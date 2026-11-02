package devtests.braincoretest

import com.tttt.subtitler.*
import org.json.JSONObject

var fails = 0
fun check(name: String, ok: Boolean, extra: String = "") {
    if (ok) println("PASS $name") else { fails++; println("FAIL $name $extra") }
}

fun sub(start: Double, chunk: Int, tr: String = "ترجمة$start") = Sub(
    start, start + 1.5, "orig $start", tr, "male", "unknown", "none",
    listOf("أحمد", "سارة"), listOf("القاهرة"), false, true, chunk,
    "happy", true, "S1", true, "pv", true, true, true, "V2", "هاوس"
)

fun main() {
    // 1) Sub -> JSON -> Sub متطابق بكل الحقول (22)
    val s = sub(3.25, 4)
    val back = SubCodec.fromJson(JSONObject(SubCodec.toJson(s).toString()))
    check("SubCodec round-trip (22 حقل)", back == s, "back=$back")
    val d = Sub(0.0, 1.0, "a", "b", "male", "unknown", "none", emptyList(), emptyList(), false, false)
    check("SubCodec بالقيم الافتراضية", SubCodec.fromJson(SubCodec.toJson(d)) == d)
    check("SubCodec قايمة كاملة", SubCodec.arrayFromJson(SubCodec.arrayToJson(listOf(s, d))) == listOf(s, d))
    check("BrainZip round-trip", BrainZip.unpack(BrainZip.pack("نص طويل ".repeat(500))) == "نص طويل ".repeat(500))

    // 2) delta بالباتش: أول اتصال كامل، بعده تغييرات صغيرة، حذف باتش
    val server = SubDelta()
    var ver = 0L
    val nv = { ++ver }
    val client = HashMap<Int, List<Sub>>()
    var since = 0L

    var list = listOf(sub(1.0, 0), sub(2.0, 0), sub(61.0, 1), sub(62.0, 1), sub(121.0, 2))
    check("update أول مرة بيرجّع تغيّر", server.update(list, nv))
    var got = SubSync.apply(client, server.since(since)); since = ver
    check("snapshot كامل = القايمة", got == list.sortedBy { it.start })

    check("نفس القايمة = مفيش تغيير ومفيش ver جديد", !server.update(list, nv) && ver == 1L)

    list = list.map { if (it.chunk == 1 && it.start == 62.0) it.copy(translated = "معدّلة") else it }
    check("تعديل جملة في باتش", server.update(list, nv))
    val dl = server.since(since)
    check("delta فيه الباتش 1 بس", !dl.getBoolean("full") && dl.getJSONObject("chunks").length() == 1 && dl.getJSONObject("chunks").has("1"), dl.toString())
    got = SubSync.apply(client, dl); since = ver
    check("بعد delta القايمة متطابقة", got == list.sortedBy { it.start })

    list = list.filter { it.chunk != 0 }
    check("حذف باتش كامل", server.update(list, nv))
    val dr = server.since(since)
    check("removed فيه 0", dr.getJSONArray("removed").length() == 1 && dr.getJSONArray("removed").getInt(0) == 0, dr.toString())
    got = SubSync.apply(client, dr); since = ver
    check("بعد الحذف القايمة متطابقة", got == list.sortedBy { it.start })

    list = list + sub(3.0, 0)
    check("رجوع الباتش 0", server.update(list, nv))
    got = SubSync.apply(client, server.since(since)); since = ver
    check("بعد الرجوع القايمة متطابقة", got == list.sortedBy { it.start })

    // عميل جديد بـsince=0 بياخد الحالة الحالية كاملة
    val fresh = HashMap<Int, List<Sub>>()
    check("عميل جديد (since=0) = نفس القايمة", SubSync.apply(fresh, server.since(0)) == list.sortedBy { it.start })

    // 3) BrainBus: await بيصحى على حدث، وبيرجع بعد المهلة من غير حدث
    BrainBus.reset()
    val v0 = BrainBus.ver
    val t0 = System.currentTimeMillis()
    check("await من غير حدث بيرجع بعد المهلة", BrainBus.await(v0, 200) == v0 && System.currentTimeMillis() - t0 >= 150)
    val th = Thread { Thread.sleep(100); BrainBus.post("log", "hello") }
    th.start()
    val v1 = BrainBus.await(v0, 3000)
    check("await بيصحى على post", v1 > v0)
    val ev = BrainBus.eventsSince(v0)
    check("eventsSince بيرجّع الحدث", ev.length() == 1 && ev.getJSONObject(0).getString("s") == "hello")
    check("eventsSince بعد آخر ver فاضي", BrainBus.eventsSince(v1).length() == 0)
    check("setState بنفس القيمة مايعملش ver", BrainBus.setState("{\"a\":1}") && !BrainBus.setState("{\"a\":1}"))

    // 4) (v544 م6) المهام
    val rows = listOf(
        TaskRow(1, "trim", "قص", 1, 40, "شغّال", false, 0L),
        TaskRow(2, "download", "تحميل", 2, 100, "", true, 123456789012L)
    )
    check("TaskRow round-trip", BrainTasksJson.decode(BrainTasksJson.encode(rows)) == rows)
    check("TaskRow قايمة فاضية", BrainTasksJson.decode(BrainTasksJson.encode(emptyList())).isEmpty())
    check("decode نص بايظ = قايمة فاضية", BrainTasksJson.decode("{bad").isEmpty())
    BrainBus.reset()
    val vt = BrainBus.ver
    check("setTasks أول مرة = تغيّر + ver", BrainBus.setTasks(BrainTasksJson.encode(rows)) && BrainBus.ver > vt)
    val vt2 = BrainBus.ver
    check("setTasks بنفس القيمة = مفيش ver", !BrainBus.setTasks(BrainTasksJson.encode(rows)) && BrainBus.ver == vt2)

    // 5) (v544 م5) لقطة المحرك
    fun bi(l: List<Engine.BatchInfo>) = l.map { listOf(it.idx.toString(), it.start.toString(), it.end.toString(), it.mark, it.count.toString()) }
    val snap = BrainEngineJson.Snap(
        listOf(doubleArrayOf(0.0, 60.5)), listOf(doubleArrayOf(61.0, 70.0)), emptyList(),
        listOf(Engine.BatchInfo(0, 0.0, 60.0, "✅", 12), Engine.BatchInfo(1, 60.0, 120.0, "⏳")),
        listOf(Engine.BatchInfo(2, 120.0, 180.0, "❌", 0)),
        listOf("📝 باتش" to true, "تاني" to false), 1, false, "ملخص", null
    )
    val sb = BrainEngineJson.decode(BrainEngineJson.encode(snap))
    check("Snap coverage/pending/refining", sb.coverage.size == 1 && sb.coverage[0].contentEquals(doubleArrayOf(0.0, 60.5)) &&
        sb.pending[0].contentEquals(doubleArrayOf(61.0, 70.0)) && sb.refining.isEmpty())
    check("Snap batches", bi(sb.batches) == bi(snap.batches))
    check("Snap problems", bi(sb.problems) == bi(snap.problems))
    check("Snap rows/failed/done", sb.rows == snap.rows && sb.failed == 1 && !sb.fullyDone)
    check("Snap offBrief موجود وoffDetail null", sb.offBrief == "ملخص" && sb.offDetail == null)
    check("Snap decode نص بايظ = لقطة فاضية", BrainEngineJson.decode("{bad").let { it.batches.isEmpty() && it.rows.isEmpty() && it.offBrief == null })
    BrainBus.setEngine(BrainEngineJson.encode(snap))
    check("setEngine بنفس القيمة = مفيش ver", BrainBus.engineJson.isNotEmpty() && !BrainBus.setEngine(BrainBus.engineJson))
    BrainBus.reset()
    check("reset بيمسح لقطة المحرك ويسيب المهام", BrainBus.engineJson == "{}" && BrainBus.tasksJson != "[]")

    // 6) (v545) قيد الريموتات
    val ow = BrainOwner(1000L)
    check("owner: الأول بيمسك", ow.claim("A", 0L) && ow.isOwner("A", 10L))
    check("owner: التاني قراءة فقط", !ow.claim("B", 100L) && !ow.isOwner("B", 100L))
    check("owner: المالك بيجدّد", ow.claim("A", 900L) && !ow.claim("B", 1700L))
    check("owner: بينتهي بعد ttl", ow.claim("B", 2500L) && ow.isOwner("B", 2600L))
    ow.take("A", 2700L)
    check("owner: take", ow.isOwner("A", 2800L) && !ow.isOwner("B", 2800L))
    check("owner: token فاضي مسموح", ow.claim("", 2800L) && ow.isOwner("", 2800L))

    // 7) (v545) شخصيات + لقطة موسّعة
    val chs = listOf(Chr("أحمد", "male", "بطل"), Chr("سارة", "female", ""))
    check("BrainChars round-trip", BrainChars.decode(BrainChars.encode(chs)) == chs)
    val snap2 = BrainEngineJson.Snap(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, true, null, null,
        covered = 123.5, pendingCount = 3, userPaused = true, paused = false, transOnly = true, keepGoing = true,
        convDialect = "مصري", toneStyle = "ودّي", toneStrength = "قوية", titleHint = "فيلم", skipIfBaseLang = true,
        baseSkipped = false, baseLangCode = "ar", versions = listOf(VerRow("نسخة 1", "مصري", 42)), chars = chs
    )
    val s2 = BrainEngineJson.decode(BrainEngineJson.encode(snap2))
    check("Snap موسّعة: الأعلام", s2.covered == 123.5 && s2.pendingCount == 3 && s2.userPaused && !s2.paused && s2.transOnly && s2.keepGoing &&
        s2.convDialect == "مصري" && s2.toneStyle == "ودّي" && s2.toneStrength == "قوية" && s2.titleHint == "فيلم" &&
        s2.skipIfBaseLang && !s2.baseSkipped && s2.baseLangCode == "ar" && s2.fullyDone)
    check("Snap موسّعة: إصدارات وشخصيات", s2.versions == listOf(VerRow("نسخة 1", "مصري", 42)) && s2.chars == chs)

    // 8) (v545) LinkMirror + PosExtrap
    fun resp(ver: Long, vid: String, subs: JSONObject, tasks: String = "[]", live: Boolean = true, ctl: Boolean = true) =
        JSONObject().put("ver", ver).put("st", JSONObject().put("vid", vid).put("pos", 1000L).put("dur", 60000L).put("playing", true).put("speed", "2x"))
            .put("subs", subs).put("tasks", org.json.JSONArray(tasks)).put("eng", JSONObject()).put("live", live).put("ctl", ctl).put("events", org.json.JSONArray())
    val srv = SubDelta()
    var sv = 0L
    val lst = listOf(sub(1.0, 0), sub(61.0, 1))
    srv.update(lst) { ++sv }
    val mir = LinkMirror()
    val c1 = mir.apply(resp(sv, "v1", srv.since(mir.since)), 5000L)
    check("mirror: أول sync كامل", c1.subsChanged && !c1.reset && mir.subs == lst.sortedBy { it.start } && mir.gotFirst && mir.since == sv)
    val ref1 = mir.subs
    val c2 = mir.apply(resp(sv, "v1", srv.since(mir.since)), 5100L)
    check("mirror: مفيش تغيير = نفس المرجع", !c2.subsChanged && mir.subs === ref1)
    val lst2 = lst.map { if (it.chunk == 1) it.copy(translated = "معدّلة") else it }
    srv.update(lst2) { ++sv }
    val c3 = mir.apply(resp(sv, "v1", srv.since(mir.since)), 5200L)
    check("mirror: delta = قائمة جديدة بالتعديل", c3.subsChanged && mir.subs !== ref1 && mir.subs == lst2.sortedBy { it.start })
    val c4 = mir.apply(resp(sv, "v2", srv.since(mir.since)), 5300L)
    check("mirror: فيديو جديد (vid) = reset", c4.reset && mir.since == 0L && mir.subs.isEmpty() && !mir.gotFirst)
    val c5 = mir.apply(resp(sv, "v2", srv.since(0)), 5400L)
    check("mirror: بعد reset snapshot كامل", !c5.reset && mir.subs == lst2.sortedBy { it.start })
    val c6 = mir.apply(resp(1L, "v2", srv.since(0)), 5500L)
    check("mirror: ver رجع لورا (التلفزيون restart) = reset", c6.reset)
    val tk = BrainTasksJson.encode(listOf(TaskRow(7, "trim", "قص", 1, 10, "", false, 0L)))
    mir.apply(resp(sv, "v2", srv.since(0), tasks = tk, ctl = false), 6000L)
    check("mirror: مهام + owner=false", mir.tasks.size == 1 && mir.tasks[0].id == 7 && !mir.owner)
    check("PosExtrap شغّال", PosExtrap.pos(1000L, 0L, 1000L, 2.0, true, 60000L) == 3000L)
    check("PosExtrap واقف/حد المدة", PosExtrap.pos(1000L, 0L, 1000L, 2.0, false, 60000L) == 1000L && PosExtrap.pos(59000L, 0L, 5000L, 1.0, true, 60000L) == 60000L)
    check("PosExtrap.speedOf", PosExtrap.speedOf("1.25x") == 1.25 && PosExtrap.speedOf("") == 1.0 && PosExtrap.speedOf("9x") == 4.0)
    check("PosExtrap.splitOp", PosExtrap.splitOp("engine.recap|نص|فيه") == Pair("engine.recap", "نص|فيه") && PosExtrap.splitOp("x") == Pair("x", ""))
    check("mirror.position من الذاكرة", mir.position(mir.stStamp + 1000L) == 3000L)

    println(if (fails == 0) "\nكل الاختبارات نجحت" else "\nفشل $fails اختبار")
    System.exit(if (fails == 0) 0 else 1)
}
