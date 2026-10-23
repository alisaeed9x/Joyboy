import com.vanilla.tvlauncher.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

fun main() {
    check("shouldShow: قبل الوقت", !SaverLogic.shouldShow(299, 5, false))
    check("shouldShow: عند الوقت", SaverLogic.shouldShow(300, 5, false))
    check("shouldShow: فيديو شغّال = لا", !SaverLogic.shouldShow(999, 5, true))
    check("shouldShow: معطّلة (0) = لا", !SaverLogic.shouldShow(9999, 0, false))
    check("shouldMove: كل 60 ثانية", SaverLogic.shouldMove(0) && !SaverLogic.shouldMove(59) && SaverLogic.shouldMove(60) && SaverLogic.shouldMove(120))
    check("offset: الحدود", SaverLogic.offset(400, 0) == -100 && SaverLogic.offset(400, 100) == 0 && SaverLogic.offset(400, 200) == 100 && SaverLogic.offset(400, 201) == -100)
    check("offset: سالب", SaverLogic.offset(400, -1) == 100)
    var inRange = true
    for (r in -1000..1000) { val o = SaverLogic.offset(400, r); if (o < -100 || o > 100) inRange = false }
    check("offset: دايمًا جوه ±size/4", inRange)
    check("offset: مقاس صغير = 0", SaverLogic.offset(3, 5) == 0 && SaverLogic.offset(0, 9) == 0)
    check("styles", SaverLogic.STYLES.size == 3 && SaverLogic.styleLabel(SaverLogic.STYLE_BLACK) == "شاشة سودا" && SaverLogic.styleLabel(0) == "ساعة + تاريخ")
    println(if (fails == 0) "ALL PASS" else "FAILURES: $fails")
    if (fails != 0) System.exit(1)
}
