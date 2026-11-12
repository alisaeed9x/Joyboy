package devtests.irunifiedtest

import com.tttt.subtitler.*
import java.io.File

var fails = 0
fun check(name: String, ok: Boolean, extra: String = "") {
    if (ok) println("PASS $name") else { fails++; println("FAIL $name $extra") }
}

private fun ctx(kind: String = "tv", linked: Boolean = true, conn: Boolean = true, bad: Boolean = false, exec: Boolean = true, hw: Boolean = true,
                prof: Boolean = true, key: Boolean = true, mode: String = "auto", dual: Set<String> = emptySet()) =
    RouteCtx(kind, linked, conn, bad, exec, hw, prof, key, mode, dual)

fun main() {
    // ---------- الأوامر المنطقية: زرار واحد لكل وظيفة
    val ids = Cmds.all.map { it.id }
    check("13 أمر منطقي + ok_long بدون تكرار", ids.size == ids.toSet().size && ids.containsAll(listOf("power","back","home_app","home_tv","menu","up","down","left","right","ok","vol_up","vol_down","mute")))
    check("رئيسية البرنامج غير رئيسية التلفزيون", Cmds.of("home_app")!!.wifiKey == "home" && Cmds.of("home_tv")!!.wifiKey == "tvhome" && Cmds.of("home_app")!!.irKeys.isEmpty())
    check("fromWifiKey: back/home/ok_long", Cmds.fromWifiKey("back")!!.id == "back" && Cmds.fromWifiKey("home")!!.id == "home_app" && Cmds.fromWifiKey("ok_long")!!.id == "ok_long" && Cmds.fromWifiKey("ff") == null)

    // ---------- الراوتر
    fun plan(id: String, c: RouteCtx) = RouterCore.plan(Cmds.of(id)!!, c)
    check("واي فاي سليم + IR محفوظ ← واي فاي ثم IR احتياطي", plan("back", ctx()).transport == Transport.WIFI_THEN_IR)
    check("واي فاي سليم من غير بروفايل ← واي فاي بس", plan("back", ctx(prof = false, key = false)).transport == Transport.WIFI)
    check("واي فاي مقطوع + IR ← IR", plan("back", ctx(conn = false)).transport == Transport.IR)
    check("واي فاي فاشل حديثًا + IR ← IR", plan("up", ctx(bad = true)).transport == Transport.IR)
    check("واي فاي مقطوع ومفيش بلاستر ← لا شيء", plan("back", ctx(conn = false, hw = false)).transport == Transport.NONE)
    check("واي فاي مقطوع ومفيش بروفايل ← لا شيء (NO_PROFILE)", plan("back", ctx(conn = false, prof = false, key = false)).why == Why.NO_PROFILE)
    check("الزرار ناقص من الريموت ← KEY_MISSING", plan("home_tv", ctx(conn = false, key = false)).why == Why.KEY_MISSING)
    check("باور = IR بس (مفيش واي فاي) حتى والواي فاي سليم", plan("power", ctx()).transport == Transport.IR)
    check("باور من غير IR ← مش مبعوت", plan("power", ctx(hw = false)).transport == Transport.NONE)
    check("رئيسية البرنامج = واي فاي بس", plan("home_app", ctx()).transport == Transport.WIFI)
    check("رئيسية البرنامج والواي فاي واقع ← مش بتروح IR", plan("home_app", ctx(conn = false)).transport == Transport.NONE)
    check("ساتلايت ← IR بس على بروفايله", plan("up", ctx(kind = "sat", linked = false)).transport == Transport.IR)
    check("ساتلايت من غير زرار ← مش بيروح للتلفزيون", plan("vol_up", ctx(kind = "sat", linked = false, key = false)).transport == Transport.NONE)
    check("ساتلايت + رئيسية البرنامج ← جهاز غلط", plan("home_app", ctx(kind = "sat", linked = false, key = false)).why == Why.WRONG_DEVICE)
    check("وضع واي فاي بس", plan("up", ctx(mode = "wifi")).transport == Transport.WIFI && plan("up", ctx(mode = "wifi", conn = false)).transport == Transport.NONE)
    check("وضع IR أولًا", plan("up", ctx(mode = "ir")).transport == Transport.IR && plan("home_app", ctx(mode = "ir")).transport == Transport.WIFI)
    check("وضع v820 irfirst: أسهم IR والباقي تلقائي", plan("up", ctx(mode = "irfirst")).transport == Transport.IR && plan("back", ctx(mode = "irfirst")).transport == Transport.WIFI_THEN_IR)
    check("واي فاي مش هينفّذ (مفيش accessibility على تطبيق تاني) ← IR", plan("back", ctx(exec = false)).transport == Transport.IR)
    check("مزدوج: بس لو متفعّل صراحةً", plan("ok", ctx(dual = setOf("ok"))).transport == Transport.BOTH && plan("ok", ctx()).transport != Transport.BOTH)
    check("مزدوج مرفوض لـ كتم/أسهم/صوت", plan("mute", ctx(dual = setOf("mute"))).transport != Transport.BOTH && plan("up", ctx(dual = setOf("up"))).transport != Transport.BOTH && plan("vol_up", ctx(dual = setOf("vol_up"))).transport != Transport.BOTH)
    check("رسائل الخطأ مقروءة", RouterCore.explain(Why.NO_PROFILE, null).isNotEmpty() && RouterCore.explain(Why.DUPLICATE, null).isEmpty())

    // ---------- منع التكرار
    val g = Gate(); val up = Cmds.of("up")!!; val pw = Cmds.of("power")!!
    check("Gate: ارتداد اللمس يتفلتر", g.allow(up, 1000) && !g.allow(up, 1030) && g.allow(up, 1100))
    check("Gate: الباور مرتين بسرعة ← الأولى بس", g.allow(pw, 5000) && !g.allow(pw, 5800) && g.allow(pw, 6600))
    check("Gate: أوامر مختلفة مش بتأثّر على بعض", g.allow(Cmds.of("down")!!, 1101))

    // ---------- الصحة والرجوع للواي فاي
    val h = Health(12_000L)
    h.fail(1000); check("Health: فشل ← وحش", h.isBad(2000))
    check("Health: بعد المهلة بيجرّب واي فاي تاني", !h.isBad(13_500))
    h.fail(20_000); h.onConnected(); check("Health: رجوع الاتصال بيرجّع الواي فاي أساسي فورًا", !h.isBad(20_100))

    // ---------- الحالة
    fun st(kind: String = "tv", linked: Boolean = true, state: String = "CONNECTED", bad: Boolean = false, hw: Boolean = true, prof: Boolean = true, mode: String = "auto") = StatusCore.of(kind, linked, state, bad, hw, prof, mode)
    check("حالة: Wi-Fi Active", st() == IrStatus.WIFI_ACTIVE)
    check("حالة: IR Active بعد انقطاع الواي فاي", st(state = "LOST") == IrStatus.IR_ACTIVE && st(bad = true) == IrStatus.IR_ACTIVE)
    check("حالة: Connecting", st(state = "CONNECTING", hw = false) == IrStatus.CONNECTING)
    check("حالة: Device Unavailable", st(state = "LOST", hw = false) == IrStatus.UNAVAILABLE && st(state = "IDLE", prof = false) == IrStatus.UNAVAILABLE)
    check("حالة: IR Unsupported (ساتلايت من غير بلاستر)", st(kind = "sat", linked = false, hw = false) == IrStatus.IR_UNSUPPORTED)
    check("حالة: واي فاي سليم حتى من غير بلاستر", st(hw = false) == IrStatus.WIFI_ACTIVE)

    // ---------- البروفايلات
    val db = ProfileDb()
    val a = db.add(IrProfile(db.newId(1), "TV صالة", "tv", "Samsung", "Samsung_AA59-00443A", "dev1", 1, "tv"))
    val b = db.add(IrProfile(db.newId(1), "ريسيفر", "sat", "Dish TV", "Dish TV #1 (36)", "", 2, "sat"))
    val c2 = db.add(IrProfile(db.newId(1), "TV أوضة", "tv", "LG", "LG_32LF650V", "", 3, "tv"))
    check("ids فريدة", setOf(a.id, b.id, c2.id).size == 3)
    check("أول بروفايل من كل نوع = الافتراضي", db.defaultFor("tv")!!.id == a.id && db.defaultFor("sat")!!.id == b.id)
    a.remap["BACK"] = "EXIT"; a.custom["POWER"] = "NX|57088|58140"
    db.setDefault(c2.id); db.target = b.id
    db.settings.mode = "irfirst"; db.settings.volFollowsTv = true; db.settings.dual.add("ok")
    val js = db.toJson(); val r = ProfileDb.fromJson(js)
    check("JSON ذهاب وعودة: العدد والأسماء", r.profiles.map { it.name } == db.profiles.map { it.name })
    check("JSON: الأكواد والربط وارتباط الجهاز محفوظين", r.byId(a.id)!!.remap["BACK"] == "EXIT" && r.byId(a.id)!!.custom["POWER"] == "NX|57088|58140" && r.byId(a.id)!!.deviceId == "dev1" && r.byId(a.id)!!.src == "tv")
    check("JSON: الافتراضي والهدف والإعدادات", r.defaultFor("tv")!!.id == c2.id && r.target == b.id && r.settings.mode == "irfirst" && r.settings.volFollowsTv && "ok" in r.settings.dual)
    check("rename", db.rename(a.id, "TV جديد") && db.byId(a.id)!!.name == "TV جديد" && !db.rename(a.id, "  "))
    check("uniqueName", db.uniqueName("TV جديد") == "TV جديد 2")
    check("replace: بيحافظ على الاسم والجهاز ويمسح التعديلات", db.replace(a.id, "Sony", "Sony_X") && db.byId(a.id)!!.name == "TV جديد" && db.byId(a.id)!!.deviceId == "dev1" && db.byId(a.id)!!.remap.isEmpty() && db.byId(a.id)!!.custom.isEmpty())
    check("delete الافتراضي ← التالي من نفس النوع يبقى افتراضي", db.delete(c2.id) && db.defaultFor("tv")!!.id == a.id)
    check("delete الهدف ← الهدف يرجع للتلفزيون الأساسي", db.delete(b.id) && db.target == "" && db.defaultFor("sat") == null)
    check("JSON متسامح: نص بايظ/فاضي", ProfileDb.fromJson("{{{").profiles.isEmpty() && ProfileDb.fromJson(null).profiles.isEmpty() && ProfileDb.fromJson("").profiles.isEmpty())
    val future = """{"v":9,"newField":1,"profiles":[{"id":"x","name":"N","kind":"tv","brand":"B","remote":"R","extra":5},{"nope":1}],"settings":{"mode":"weird","dual":["bogus","ok"]}}"""
    val fr = ProfileDb.fromJson(future)
    check("JSON نسخة أحدث: بيتجاهل المجهول ويكمل", fr.profiles.size == 1 && fr.settings.mode == "auto" && fr.settings.dual == setOf("ok") && fr.defaultFor("tv")!!.id == "x")
    check("SigText", ProfileDb.SigText.valid("NX|57088|58140") && ProfileDb.SigText.valid("RAW|38000|9000 4500 560") && !ProfileDb.SigText.valid("XX|1|2") && !ProfileDb.SigText.valid("RAW|5|9000 4500") && !ProfileDb.SigText.valid("N|a|b"))

    // ---------- قاعدة IR الحقيقية + الكتالوج
    val dir = File(System.getenv("SUBTITLER_ASSETS") ?: "src/main/assets")
    val remotes = ArrayList<IrTv.Remote>()
    for ((f, k) in listOf("irdb_tv.txt" to "tv", "irdb_tv_mi.txt" to "tv", "irdb_sat.txt" to "sat")) File(dir, f).bufferedReader(Charsets.UTF_8).useLines { IrTv.parseInto(it, k, remotes) }
    check("القاعدة اتقرت (آلاف الريموتات)", remotes.size > 1500, remotes.size.toString())
    val idx = IrTv.catalogOf(remotes)
    check("الـ10 ماركات الشهيرة كلها موجودة بالترتيب", idx.popular().map { it.name } == CatIndex.POPULAR, idx.popular().map { it.name }.toString())
    check("ماركات تانية: مفيهاش الشهيرة", idx.otherBrands().none { it.name.lowercase() in CatIndex.POPULAR.map { p -> p.lowercase() } } && idx.otherBrands().isNotEmpty())
    check("كل الماركات + الساتلايت", idx.brands("tv").size > 300 && idx.brands("sat").size >= 30)
    val sm = idx.models("tv", "Samsung")
    check("موديلات سامسونج: فيها رقم ريموت", sm.size > 5 && sm.any { it.label == "AA59-00443A" })
    check("الأكواد العامة آخر القايمة", sm.indexOfFirst { it.key == "generic" }.let { it == -1 || it == sm.size - 1 })
    val mdl = sm.first { it.label == "AA59-00443A" }
    val pf = idx.profilesFor(mdl)
    check("بروفايلات الموديل: بتبدأ بريموت الموديل نفسه وبعدها بدائل من نفس الماركة", pf[0].name == "Samsung_AA59-00443A" && pf.size > 1 && pf.all { it.brand == "Samsung" })
    check("مفيش ريموت متكرر في البروفايلات", pf.map { it.name }.let { it.size == it.toSet().size })
    check("بحث: ماركة", idx.search("samsung").let { it.isNotEmpty() && it[0].model.brand == "Samsung" })
    check("بحث: رقم ريموت بدون شرطات وحروف صغيرة", idx.search("aa5900443a").any { it.model.label == "AA59-00443A" })
    check("بحث: ماركة + موديل (AND)", idx.search("lg 32lf650v").any { it.model.brand == "LG" && it.model.label.contains("32LF650V") })
    check("بحث: ريسيفر ساتلايت", idx.search("tata").any { it.model.kind == "sat" } && idx.search("dish").any { it.model.kind == "sat" })
    check("بحث: شيء مش موجود ← فاضي", idx.search("zzzxxyy").isEmpty() && idx.search("   ").isEmpty())
    check("بحث: alias", idx.search("redmi").isNotEmpty() || idx.search("xiaomi").isNotEmpty())
    check("الأولوية للتطابق التام", idx.search("sony").first().model.brand.equals("Sony", true))
    check("modelPart", CatIndex.modelPart("Samsung", "Samsung_AA59-00443A") == "AA59-00443A" && CatIndex.modelPart("Airtel", "Airtel #1 (36)") == "" && CatIndex.modelPart("10moons", "10moons (Mi) #1") == "")

    // ---------- التشفير والكود اليدوي
    var okAll = 0; var total = 0; val bad = HashSet<String>()
    for (rm in remotes) for ((_, s) in rm.keys) { total++; if (IrTv.encode(s) != null) okAll++ else bad.add(s.proto) }
    check("كل إشارات القاعدة بتتشفّر (البروتوكولات المدعومة)", okAll == total, "$okAll/$total bad=$bad")
    val s1 = IrTv.parseSig("NX|57088|58140")!!
    check("parseSig/sigText ذهاب وعودة", IrTv.sigText(s1) == "NX|57088|58140" && IrTv.parseSig("RAW|38000|9000 4500 560 560")!!.raw!!.size == 4 && IrTv.parseSig("junk") == null)
    check("NEC: 34 نبضة (بداية + 32 بت + ختام)", IrTv.encode(s1)!!.second.size.let { it in 67..69 }, IrTv.encode(s1)!!.second.size.toString())

    // ---------- أمر كل البروفايلات بيحل أزرار الريموت الموحّد
    val samsungTv = remotes.first { it.brand == "Samsung" && it.name == "Samsung_AA59-00443A" }
    check("ريموت سامسونج فيه الأزرار الأساسية", listOf("POWER", "UP", "DOWN", "LEFT", "RIGHT", "OK", "VOLUP", "VOLDN", "MUTE").all { samsungTv.keys.containsKey(it) })

    println(if (fails == 0) "الاختبارات: كلها نجحت" else "الاختبارات: فشل $fails")
    if (fails > 0) System.exit(1)
}
