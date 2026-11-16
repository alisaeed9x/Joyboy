package devtests.trprofiletest

import com.tttt.subtitler.*

var pass = 0; var fail = 0
fun check(name: String, ok: Boolean) { if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name") } }

fun main() {
    check("تفريغ", TrProfile.modeOf("transcribe", "", "فصحى") == TrProfile.TX)
    check("تفريغ يغلب اللهجة", TrProfile.modeOf("transcribe", "مصري", "مصري") == TrProfile.TX)
    check("فصحى: لهجة فاضية + lang فصحى", TrProfile.modeOf("translate", "", "فصحى") == TrProfile.MSA)
    check("لهجة من conv_dialect", TrProfile.modeOf("translate", "شامي", "فصحى") == TrProfile.DIA)
    check("لهجة من lang لو conv_dialect فاضية", TrProfile.modeOf("translate", "", "مصري") == TrProfile.DIA)
    check("lang فاضي = فصحى", TrProfile.modeOf("translate", "", "") == TrProfile.MSA)
    println("pass=$pass fail=$fail")
    System.exit(if (fail == 0) 0 else 1)
}
