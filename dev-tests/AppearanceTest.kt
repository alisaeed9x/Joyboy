import com.vanilla.tvlauncher.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

fun main() {
    val base = Themes.byId("default")
    check("(0,0) = نفس الثيم بالظبط", Appearance.apply(base, 0, 0) === base)
    check("فهارس برّه المدى = بدون تغيير", Appearance.apply(base, 99, -3) === base)
    val a = Appearance.apply(base, 2, 0)   // أزرق على الثيم الغامق
    check("لون رئيسي بس: الخلفية زي ما هي", a.bg == base.bg && a.card == base.card && a.text == base.text)
    check("لون رئيسي بس: primary اتغيّر", a.primary == Appearance.ACCENTS[2].color && a.accent == a.primary)
    val b = Appearance.apply(base, 0, 7)   // كريمي فاتح
    check("خلفية فاتحة = نص أسود", b.text == 0xFF000000.toInt() && b.isLight)
    check("خلفية فاتحة: الكارت أغمق شوية من الخلفية", Theme.lum(b.card) < Theme.lum(b.bg))
    val c = Appearance.apply(base, 0, 1)   // أسود نقي
    check("خلفية سودا = نص أبيض + كارت أفتح", c.text == 0xFFFFFFFF.toInt() && Theme.lum(c.card) > Theme.lum(c.bg))
    check("احتفاظ بـ id والاسم", c.id == base.id && c.name == base.name)
    check("danger/success ما اتغيّروش", c.danger == base.danger && c.success == base.success)
    // وضوح اللون الرئيسي على الخلفية
    val dark = Appearance.apply(base, 6, 1)  // سماوي على أسود = واضح أصلًا
    check("لون واضح ما بيتغيّرش", dark.primary == Appearance.ACCENTS[6].color)
    val clash = Appearance.apply(base, 8, 7)  // ذهبي على كريمي فاتح → بيتغمّق
    check("لون قريب من الخلفية بيتصلّح (فرق سطوع ≥ 0.30)", Math.abs(Theme.lum(clash.primary) - Theme.lum(clash.bg)) >= 0.30)
    check("shade 0 = نفس اللون", Appearance.shade(0xFF123456.toInt(), 0.0) == 0xFF123456.toInt())
    check("shade 1 = أبيض / -1 = أسود", Appearance.shade(0xFF123456.toInt(), 1.0) == 0xFFFFFFFF.toInt() && Appearance.shade(0xFF123456.toInt(), -1.0) == 0xFF000000.toInt())
    check("كل الخلفيات: top/bottom معرّفين + المتدرّجة gradient", Appearance.BGS.all { it.id == Appearance.BGS.indexOf(it) } && Appearance.BGS[2].gradient && !Appearance.BGS[1].gradient)
    check("bgOf(0) = null (من الثيم)", Appearance.bgOf(0) == null && Appearance.bgOf(2) != null && Appearance.bgOf(50) == null)
    check("كل الثيمات × كل الألوان × كل الخلفيات: نص واضح (تباين ≥ 0.5)", Themes.all.all { t -> Appearance.ACCENTS.indices.all { i -> Appearance.BGS.indices.all { j ->
        val x = Appearance.apply(t, i, j); Math.abs(Theme.lum(x.text) - Theme.lum(x.bg)) >= 0.5 } } })
    // خطوط
    check("خط 0 = نظام ('' )", Appearance.fontFamily(0) == "" && Appearance.fontName(0) == "الافتراضي")
    check("خط برّه المدى = الافتراضي", Appearance.fontFamily(77) == "" && Appearance.fontName(-1) == "الافتراضي")
    check("next دايري + قيمة غلط = 0", Appearance.next(0, 3) == 1 && Appearance.next(2, 3) == 0 && Appearance.next(9, 3) == 0 && Appearance.next(-1, 3) == 0 && Appearance.next(0, 0) == 0)
    // الاسم الظاهر
    check("اسم فاضي/null = الافتراضي", Appearance.displayName("") == "Vanilla TV" && Appearance.displayName(null) == "Vanilla TV" && Appearance.displayName("   ") == "Vanilla TV")
    check("اسم عادي + مسافات مضغوطة", Appearance.displayName("  تلفزيون   البيت ") == "تلفزيون البيت")
    check("اسم طويل بيتقص عند 24", Appearance.displayName("ا".repeat(40)).length == 24)
    check("القص ما بيكسرش إيموجي (surrogate)", Appearance.displayName("a".repeat(23) + "😀").let { it.length <= 24 && !Character.isHighSurrogate(it.last()) })
    check("سطور جديدة = مسافة", Appearance.displayName("a\nb") == "a b")
    println(if (fails == 0) "ALL PASS" else "FAILS=$fails"); if (fails > 0) System.exit(1)
}
