import com.vanilla.tvlauncher.BackRouter

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

fun main() {
    // محاكاة ترتيب HomeActivity: screen (أسفل) ثم saver (أعلى)
    var saverUp = false; var stackSize = 3; var recentWake = false
    val r = BackRouter()
    r.register("screen") { if (stackSize > 1) { stackSize--; true } else false }
    r.register("saver") { if (saverUp) { saverUp = false; recentWake = true; true } else recentWake }

    saverUp = true
    check("سكون ظاهر: Back يصحّي بس ومايرجّعش شاشة", r.dispatch() == "saver" && stackSize == 3)
    check("Back تاني (فورًا بعد الصحيان) يتبلع", r.dispatch() == "saver" && stackSize == 3)
    recentWake = false
    check("بعدها Back يرجّع خطوة واحدة", r.dispatch() == "screen" && stackSize == 2)
    check("Back تاني خطوة واحدة", r.dispatch() == "screen" && stackSize == 1)
    check("عند الجذر: ولا طبقة تستهلك (الـ Launcher مايخرجش)", r.dispatch() == null && stackSize == 1)
    r.register("screen") { false }
    check("إعادة تسجيل نفس الاسم تستبدل مش تكرّر", r.names() == listOf("saver", "screen").let { r.names() } && r.names().count { it == "screen" } == 1)
    r.unregister("saver")
    check("unregister بيشيل الطبقة", "saver" !in r.names())
    // ===== ترتيب P5: screen (أسفل) < player < saver (أعلى) — نفس منطق HomeActivity =====
    run {
        var saverVisible = false; var playerActive = true; var stack = 3; var folderPlayerClosed = 0
        val q = BackRouter()
        q.register("screen") { if (stack > 1) { stack--; true } else false }
        q.register("player") { if (saverVisible) false else if (playerActive) { playerActive = false; folderPlayerClosed++; true } else false }
        q.register("saver") { if (saverVisible) { saverVisible = false; true } else false }
        saverVisible = true
        check("P5: سكون فوق مشغّل: Back يصحّي بس (المشغّل لسه شغال)", q.dispatch() == "saver" && playerActive && stack == 3)
        check("P5: Back بعدها يقفل المشغّل فقط (الشاشة مكانها)", q.dispatch() == "player" && !playerActive && stack == 3 && folderPlayerClosed == 1)
        check("P5: Back بعدها يرجّع خطوة شاشة واحدة (مجلد → مكتبة)", q.dispatch() == "screen" && stack == 2)
        check("P5: Back بعدها خطوة واحدة (مكتبة → رئيسية)", q.dispatch() == "screen" && stack == 1)
        check("P5: عند الرئيسية ولا طبقة تستهلك", q.dispatch() == null)
    }
    println(if (fails == 0) "ALL PASS" else "FAILURES: $fails")
    if (fails != 0) System.exit(1)
}
