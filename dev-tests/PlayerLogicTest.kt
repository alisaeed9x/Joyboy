import com.vanilla.tvlauncher.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

fun main() {
    // ----- ملاءمة الصورة (1920x1080 حاوية) -----
    check("احتواء 4:3 جوه 16:9 = 1440x1080", PlayerLogic.fitSize(1920, 1080, 640, 480, 0) == (1440 to 1080))
    check("احتواء 21:9 جوه 16:9 = عرض كامل", PlayerLogic.fitSize(1920, 1080, 2560, 1080, 0) == (1920 to 810))
    check("ملء بالقص 4:3 = 1920x1440", PlayerLogic.fitSize(1920, 1080, 640, 480, 1) == (1920 to 1440))
    check("تمديد = الحاوية", PlayerLogic.fitSize(1920, 1080, 640, 480, 2) == (1920 to 1080))
    check("مقاس فيديو مجهول = الحاوية", PlayerLogic.fitSize(1920, 1080, 0, 0, 0) == (1920 to 1080))
    // ----- نط بالأرقام -----
    check("رقم واحد = ثواني", PlayerLogic.digitsToSec("5") == 5)
    check("رقمين = ثواني (45)", PlayerLogic.digitsToSec("45") == 45)
    check("3 أرقام: 130 = 1:30", PlayerLogic.digitsToSec("130") == 90)
    check("4 أرقام: 1230 = 12:30", PlayerLogic.digitsToSec("1230") == 750)
    check("فاضي/غير رقمي = 0", PlayerLogic.digitsToSec("") == 0 && PlayerLogic.digitsToSec("1a") == 0)
    // ----- خطوة التقديم -----
    check("ضغطة عادية 10ث", PlayerLogic.seekStep(0) == 10_000L && PlayerLogic.seekStep(4) == 10_000L)
    check("ضغط متتابع 30ث", PlayerLogic.seekStep(5) == 30_000L)
    // ----- cycle -----
    check("cycle للأمام باللف", PlayerLogic.cycle(listOf(1, 2, 3), 3, 1) == 1)
    check("cycle للخلف باللف", PlayerLogic.cycle(listOf(1, 2, 3), 1, -1) == 3)
    check("cycle قيمة مش موجودة = أول عنصر ثم يتحرك", PlayerLogic.cycle(listOf(1, 2, 3), 9, 1) == 2)
    check("cycle قايمة فاضية = نفس القيمة", PlayerLogic.cycle(emptyList<Int>(), 7, 1) == 7)
    // ----- حجم الترجمة -----
    check("حجم الترجمة بيتقيّد 50..250", PlayerLogic.clampSub(10) == 50 && PlayerLogic.clampSub(999) == 250 && PlayerLogic.clampSub(120) == 120)
    // ----- حلقة A-B -----
    var ab = PlayerLogic.AB()
    check("البداية غير نشطة", !ab.active && PlayerLogic.abJumpTarget(ab, 5000) == -1L)
    ab = PlayerLogic.abPress(ab, 10_000L)
    check("ضغطة 1 = نقطة A فقط", ab.a == 10_000L && ab.b == -1L && !ab.active)
    ab = PlayerLogic.abPress(ab, 10_200L)
    check("B قريبة جدًا من A (≤0.5ث) = إعادة تحديد A", ab.a == 10_200L && ab.b == -1L)
    ab = PlayerLogic.abPress(ab, 20_000L)
    check("ضغطة 2 = نقطة B والحلقة نشطة", ab.active && ab.a == 10_200L && ab.b == 20_000L)
    check("قبل B مفيش قفز", PlayerLogic.abJumpTarget(ab, 19_999L) == -1L)
    check("عند B بيرجع لـ A", PlayerLogic.abJumpTarget(ab, 20_000L) == 10_200L)
    ab = PlayerLogic.abPress(ab, 25_000L)
    check("ضغطة 3 = إلغاء", !ab.active && ab.a == -1L && ab.b == -1L)
    // ----- بطاقة التالي -----
    check("5 ثواني في الأول", PlayerLogic.nextUpSecs(5000, 0) == 5)
    check("بتتقرّب لفوق", PlayerLogic.nextUpSecs(5000, 1500) == 4)
    check("مابتنزلش عن 0", PlayerLogic.nextUpSecs(5000, 9000) == 0 && PlayerLogic.nextUpSecs(5000, 5000) == 0)
    // ----- ترجمة مصوّرة -----
    check("PGS/DVB/VobSub = مصوّرة", PlayerLogic.isBitmapSub("application/pgs") && PlayerLogic.isBitmapSub("APPLICATION/DVBSUBS") && PlayerLogic.isBitmapSub("application/vobsub"))
    check("SRT/ASS/WebVTT = نصية", !PlayerLogic.isBitmapSub("application/x-subrip") && !PlayerLogic.isBitmapSub("text/x-ssa") && !PlayerLogic.isBitmapSub("text/vtt") && !PlayerLogic.isBitmapSub(null))
    check("تسميات", PlayerLogic.speedLabel(1.5f) == "1.5x" && PlayerLogic.speedLabel(2f) == "2x" && PlayerLogic.sleepLabel(-1) == "آخر الفيديو" && PlayerLogic.sleepLabel(30) == "30 دقيقة")

    // ----- P15: إعدادات المشغّل -----
    check("seekStep بقاعدة 5/10/20/30", PlayerLogic.seekStep(0, 5) == 5_000L && PlayerLogic.seekStep(0, 20) == 20_000L && PlayerLogic.seekStep(5, 20) == 60_000L && PlayerLogic.seekStep(5) == 30_000L)
    check("SEEK_BASES فيها 10 (الافتراضي)", 10 in PlayerLogic.SEEK_BASES)
    check("panelStart: لوحة أقصر من النافذة = 0", PlayerLogic.panelStart(6, 5, 9) == 0)
    check("panelStart: الصف المختار دايمًا جوه النافذة", (0 until 16).all { sel -> val st = PlayerLogic.panelStart(16, sel, 9); sel in st until st + 9 && st in 0..7 })
    check("panelStart: أول/آخر صف", PlayerLogic.panelStart(16, 0, 9) == 0 && PlayerLogic.panelStart(16, 15, 9) == 7)
    check("langLabel", PlayerLogic.langLabel("ar") == "العربية" && PlayerLogic.langLabel("zz") == "تلقائي")
    check("idxOf قيمة مجهولة = 0", PlayerLogic.idxOf(PlayerLogic.SUB_BGS, 0x77000000) == 1 && PlayerLogic.idxOf(PlayerLogic.SUB_BGS, 123) == 0)
    check("ألوان الترجمة 4 وأولها أبيض", PlayerLogic.SUB_COLORS.size == 4 && PlayerLogic.SUB_COLORS[0].first == 0xFFFFFFFF.toInt())
    println(if (fails == 0) "ALL PASS" else "FAILS: $fails")
    if (fails > 0) System.exit(1)
}
