package devtests.cleantest

import com.tttt.subtitler.CareClean
import java.io.File
import java.nio.file.Files
import java.util.Random

var okN = 0; var bad = 0
fun chk(n: String, c: Boolean) { if (c) { okN++; println("PASS $n") } else { bad++; println("FAIL $n") } }
fun bytes(n: Int, seed: Long): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }
fun main() {
    val MB = 1024 * 1024
    val root = Files.createTempDirectory("careclean").toFile()
    try {
        for (d in listOf("Download", "Movies", "Documents/sub/empty", "EmptyTop", "Android", ".hidden")) File(root, d).mkdirs()
        val a = File(root, "Download/a.bin").apply { writeBytes(bytes(2 * MB, 1)) }
        val copy = File(root, "Movies/copy.bin").apply { writeBytes(bytes(2 * MB, 1)) }
        File(root, "Movies/other.bin").writeBytes(bytes(2 * MB, 2))   // نفس الحجم، محتوى مختلف
        File(root, "Download/app.apk").writeBytes(bytes(10_000, 3))
        File(root, "Download/big.mp4").writeBytes(bytes(4 * MB, 4))
        File(root, "Android/x.bin").writeBytes(bytes(2 * MB, 1))       // لازم يتخطّى
        File(root, ".hidden/y.bin").writeBytes(bytes(2 * MB, 1))       // لازم يتخطّى

        val r = CareClean.scanStorage(listOf(root), { false }, {}, 1000, 3L * MB, MB.toLong())
        val dups = r.cands.filter { it.cat == CareClean.CAT_DUP }
        chk("المكرر = نسختين بس (a و copy)", dups.size == 2 && dups.map { it.file.name }.toSet() == setOf("a.bin", "copy.bin"))
        chk("نفس الحجم بمحتوى مختلف مش مكرر", r.cands.none { it.file.name == "other.bin" })
        chk("Android/ والمخفي اتخطّوا", r.cands.none { it.file.path.contains("/Android/") || it.file.path.contains("/.hidden/") })
        chk("APK اتلقى", r.cands.any { it.cat == CareClean.CAT_APK && it.file.name == "app.apk" })
        chk("الملف الكبير اتلقى مرة واحدة", r.cands.count { it.cat == CareClean.CAT_LARGE && it.file.name == "big.mp4" } == 1)
        chk("الفولدر الفاضي العميق اتلقى", r.cands.any { it.cat == CareClean.CAT_EMPTY && it.file.name == "empty" })
        chk("الفولدر الفاضي الأساسي (مستوى 1) محمي", r.cands.none { it.file.name == "EmptyTop" })
        chk("كل ملف في قسم واحد", r.cands.map { it.file.path }.let { it.size == it.toSet().size })

        val ca = dups.first { it.file.name == "copy.bin" }
        val aa = dups.first { it.file.name == "a.bin" }
        val d1 = CareClean.delete(listOf(ca), r.cands)
        chk("حذف النسخة الأولى نجح وحرّر 2MB فعلًا", d1.deleted == 1 && d1.freed == 2L * MB && !copy.exists())
        val d2 = CareClean.delete(listOf(aa), r.cands)
        chk("آخر نسخة مش بتتحذف أبدًا", d2.deleted == 0 && d2.skipped == 1 && a.exists())

        // حماية الحذف المزدوج: لو الاتنين متحدّدين في نفس الوقت واحد بس يتحذف
        val b1 = File(root, "Download/b1.bin").apply { writeBytes(bytes(2 * MB, 9)) }
        val b2 = File(root, "Movies/b2.bin").apply { writeBytes(bytes(2 * MB, 9)) }
        val r2 = CareClean.scanStorage(listOf(root), { false }, {}, 1000, 3L * MB, MB.toLong())
        val bs = r2.cands.filter { it.cat == CareClean.CAT_DUP && it.file.name.startsWith("b") }
        val d3 = CareClean.delete(bs, r2.cands)
        chk("اختيار المجموعة كلها: واحد بس اتحذف", bs.size == 2 && d3.deleted == 1 && d3.skipped == 1 && (b1.exists() xor b2.exists()))

        // ملف اتغيّر بعد الفحص ← اتخطّى
        val apk = r.cands.first { it.cat == CareClean.CAT_APK }
        apk.file.appendBytes(byteArrayOf(1, 2, 3))
        val d4 = CareClean.delete(listOf(apk), r.cands)
        chk("ملف اتغيّر بعد الفحص بيتخطّى", d4.deleted == 0 && d4.skipped == 1 && apk.file.exists())

        // الفولدر الفاضي اتملى ← اتخطّى
        val ed = r.cands.first { it.cat == CareClean.CAT_EMPTY }
        File(ed.file, "n.txt").writeText("x")
        chk("فولدر اتملى مابيتحذفش", CareClean.delete(listOf(ed), r.cands).skipped == 1 && ed.file.exists())

        // كاش: الأقدم من يوم بس
        val cdir = File(root, "cache").apply { mkdirs() }
        val old = File(cdir, "old.tmp").apply { writeText("o"); setLastModified(System.currentTimeMillis() - 3 * 86_400_000L) }
        val fresh = File(cdir, "fresh.tmp").apply { writeText("f") }
        val cc = CareClean.scanCache(listOf(cdir), System.currentTimeMillis())
        chk("الكاش القديم بس", cc.size == 1 && cc[0].file.name == "old.tmp" && fresh.exists())
        chk("حذف الكاش بيتأكد", CareClean.delete(cc, cc).deleted == 1 && !old.exists())

        // إلغاء
        chk("الإلغاء بيرجّع cancelled", CareClean.scanStorage(listOf(root), { true }).cancelled)
    } finally { root.deleteRecursively() }
    println("الاختبارات: $okN نجح، $bad فشل")
}
