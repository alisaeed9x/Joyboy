import com.vanilla.tvlauncher.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }
fun v(id: Long, name: String, fk: String = "A", dur: Long = 3_600_000L, size: Long = 100) =
    VideoItem(id, "content://v/$id", name, fk, "F", 0, dur, size)

fun main() {
    val a = v(1, "a.mp4"); val b = v(2, "b.mp4"); val short = v(3, "s.mp4", dur = 20_000L)
    // isDone
    check("isDone: 95% = خلص", HistoryLogic.isDone(3_420_000, 3_600_000))
    check("isDone: نص الفيديو = لسه", !HistoryLogic.isDone(1_800_000, 3_600_000))
    check("isDone: آخر 15ث لفيديو طويل = خلص", HistoryLogic.isDone(3_590_000, 3_600_000))
    check("isDone: فيديو قصير 20ث عند 6ث ≠ خلص", !HistoryLogic.isDone(6_000, 20_000))
    check("isDone: مدة مجهولة = لسه", !HistoryLogic.isDone(999_999, 0))

    // record
    var h = emptyList<WatchEntry>()
    h = HistoryLogic.record(h, a, 2_000, 0, 10)
    check("أقل من 5ث ما يتسجّلش", h.isEmpty())
    h = HistoryLogic.record(h, a, 60_000, 0, 20)
    check("تسجيل أول مرة (المدة من الفيديو)", h.size == 1 && h[0].posMs == 60_000L && h[0].durMs == 3_600_000L && !h[0].completed)
    h = HistoryLogic.record(h, b, 30_000, 0, 30)
    h = HistoryLogic.record(h, a, 90_000, 0, 40)
    check("نفس key = سطر واحد وبيتنقل للأول", h.size == 2 && h[0].key == a.key && h[0].posMs == 90_000L && h[1].key == b.key)
    val keep = HistoryLogic.record(h, a, 1_000, 0, 50)
    check("فتح بالغلط (<5ث) ما يمسحش التقدّم", keep == h)
    // إغلاق مفاجئ: آخر موضع دوري محفوظ وغير مكتمل
    check("إغلاق مفاجئ ≠ مكتمل", !h[0].completed)
    val done = HistoryLogic.record(h, a, 0, 0, 60, ended = true)
    check("ended → completed + pos=0", done[0].completed && done[0].posMs == 0L)
    check("وصل 96% بدون ended → completed", HistoryLogic.record(emptyList(), a, 3_456_000, 0, 1)[0].completed)
    check("فيديو قصير 8ث يتسجّل ويتستكمل", HistoryLogic.record(emptyList(), short, 8_000, 0, 1)[0].let { !it.completed && it.posMs == 8_000L })
    var big = emptyList<WatchEntry>()
    for (i in 1..(HistoryLogic.MAX_ENTRIES + 20)) big = HistoryLogic.record(big, v(i.toLong(), "n$i"), 10_000, 0, i.toLong())
    check("سقف السجل", big.size == HistoryLogic.MAX_ENTRIES && big[0].name == "n${HistoryLogic.MAX_ENTRIES + 20}")

    // resume
    check("resumePos: يرجع 3ث", HistoryLogic.resumePos(h[0]) == 87_000L)
    check("resumePos: مكتمل/null = 0", HistoryLogic.resumePos(done[0]) == 0L && HistoryLogic.resumePos(null) == 0L)

    // continue / remove / search
    val h2 = HistoryLogic.record(HistoryLogic.record(h, b, 0, 0, 70, ended = true), v(4, "Movie X"), 100_000, 0, 5)
    val cont = HistoryLogic.continueList(h2, 10)
    check("continueList: غير مكتمل، الأحدث أولًا", cont.map { it.name } == listOf("a.mp4", "Movie X"))
    check("continueList بيستبعد المكتمل", cont.none { it.key == b.key })
    check("remove", HistoryLogic.remove(h, a.key).size == 1)
    check("search (حروف كبيرة/صغيرة)", HistoryLogic.search(h2, "movie").size == 1 && HistoryLogic.search(h2, "  ").size == h2.size)

    // find: ملف اتنقل (id/uri اتغيّر) لكن نفس الاسم/الحجم/المدة
    val moved = VideoItem(99, "content://v/99", "a.mp4", "Z", "F", 0, 3_600_000L, 100)
    check("find بالـ key بعد النقل", HistoryLogic.find(h[0], listOf(moved)) == moved)
    check("find: محذوف = null", HistoryLogic.find(h[0], listOf(v(7, "other.mp4"))) == null)

    // encode/decode
    val nasty = WatchEntry("k\t|1", "content://x\\y", "اسم\twith\nnewline", "F\t1", 5, 6, 7, true)
    check("ترميز/فك (محارف خاصة)", HistoryLogic.decode(HistoryLogic.encode(listOf(nasty) + h)) == listOf(nasty) + h)
    check("ملف تالف → فاضي", HistoryLogic.decode("junk").isEmpty() && HistoryLogic.decode("").isEmpty())
    check("سطر تالف يتخطى", HistoryLogic.decode("VTL-HIST-1\nbad\tline\n" + HistoryLogic.encode(h).substringAfter("\n")).size == h.size)

    // PlayQueue (منفصل عن السجل)
    val q = PlayQueue(listOf(a, b, short), 1)
    check("queue: current/prev/next", q.current == b && q.prev() == a && q.prev() == null && q.index == 0 && q.next() == b && q.next() == short && q.next() == null)
    check("queue فاضية آمنة", PlayQueue(emptyList(), 5).current == null)

    println(if (fails == 0) "ALL PASS" else "FAILURES: $fails")
    if (fails != 0) System.exit(1)
}
