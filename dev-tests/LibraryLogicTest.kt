import com.vanilla.tvlauncher.LibraryLogic
import com.vanilla.tvlauncher.VideoItem

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }
fun v(id: Long, name: String, fk: String, fn: String, d: Long = 0, dur: Long = 60000, size: Long = 100) =
    VideoItem(id, "content://v/$id", name, fk, fn, d, dur, size)

fun main() {
    val items = listOf(
        v(1, "Ep 10.mkv", "A", "Series", 300), v(2, "Ep 2.mkv", "A", "Series", 100), v(3, "movie.mp4", "B", "Movies", 500),
        v(4, "clip.mp4", "C", "DCIM", 200), v(2, "Ep 2.mkv", "A", "Series", 100))
    check("dedupe بالـ id", LibraryLogic.dedupeById(items).size == 4)
    val u = LibraryLogic.dedupeById(items)
    val f = LibraryLogic.folders(u, emptySet())
    check("تجميع المجلدات + العدّ", f.size == 3 && f.first { it.key == "A" }.count == 2)
    check("استبعاد مجلد", LibraryLogic.folders(u, setOf("A")).none { it.key == "A" } && LibraryLogic.visible(u, setOf("A")).size == 2)
    check("ترتيب طبيعي: Ep 2 قبل Ep 10", LibraryLogic.sortItems(u, "name").map { it.name }.indexOf("Ep 2.mkv") < LibraryLogic.sortItems(u, "name").map { it.name }.indexOf("Ep 10.mkv"))
    check("ترتيب بالأحدث", LibraryLogic.sortItems(u, "date").first().id == 3L)
    check("ترتيب بالمكان (مجلد ثم اسم)", LibraryLogic.sortItems(u, "place").map { it.folderName }.first() == "DCIM")
    check("ترتيب المجلدات بالأحدث", LibraryLogic.sortFolders(f, "date").first().key == "B")
    check("inFolder", LibraryLogic.inFolder(u, emptySet(), "A").size == 2 && LibraryLogic.inFolder(u, setOf("A"), LibraryLogic.ALL).size == 2)
    check("naturalCompare حروف كبيرة/صغيرة", LibraryLogic.naturalCompare("abc", "ABD") < 0 && LibraryLogic.naturalCompare("a01", "a1") == 0)
    check("formatDur", LibraryLogic.formatDur(65000) == "1:05" && LibraryLogic.formatDur(3725000) == "1:02:05" && LibraryLogic.formatDur(0) == "--:--")
    val nasty = v(9, "اسم\twith\ttabs\nand\\slash.mp4", "K\t1", "مجلد\nغريب", 5, 6, 7)
    val rt = LibraryLogic.decode(LibraryLogic.encode(listOf(nasty, items[0])))
    check("ترميز/فك الكاش (محارف خاصة)", rt == listOf(nasty, items[0]))
    check("كاش تالف → فاضي", LibraryLogic.decode("garbage").isEmpty() && LibraryLogic.decode("").isEmpty())
    check("سطر تالف يتخطى", LibraryLogic.decode("VTL-LIB-1\nbad\tline\n" + LibraryLogic.encode(listOf(items[2])).substringAfter("\n")).size == 1)
    check("key مستقر بعد نقل (id مختلف)", v(1, "a", "X", "x").key == v(99, "a", "Y", "y").key)
    val ex = LibraryLogic.excludedFolders(u, setOf("A", "GONE"))
    check("excludedFolders (موجود + مجلد اختفى)", ex.size == 2 && ex.first { it.key == "A" }.count == 2 && ex.first { it.key == "GONE" }.count == 0)
    println(if (fails == 0) "ALL PASS" else "FAILURES: $fails")
    if (fails != 0) System.exit(1)
}
