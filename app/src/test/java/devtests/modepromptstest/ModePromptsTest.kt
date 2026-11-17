package devtests.modepromptstest

import com.tttt.subtitler.*
import org.json.JSONObject
import java.io.File

private var fails = 0
private fun check(name: String, ok: Boolean) { if (ok) println("PASS $name") else { fails++; println("FAIL $name") } }

fun main() {
    val pb = PromptBuilder { File(System.getenv("SUBTITLER_ASSETS") ?: "app/src/main/assets", it).readText(Charsets.UTF_8) }
    val langs = listOf("مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني", "فصحى")
    val styles = listOf("حرفي", "شعبي", "جرئ", "+18")
    val chars = listOf(Chr("منى", "female", "البطلة"), Chr("علي", "male", ""))
    val gloss = listOf(Gloss("سينسي", "معلم"))
    var n = 0; var bad = 0
    for (l in langs) for (s in styles) for (mode in listOf("normal", "hole", "holeNear")) {
        val c = Conf(listOf("k"), emptyList(), "m", l, s, 60, 3, 1, emptyList(), "ليلى = Layla", true, true, true, keyMode = 2)
        val hole = mode != "normal"
        val p = ModePrompts.english(pb, c, 61.5, if (hole) "" else "- [متكلم:أنثى] مرحبا", chars, gloss, false, hole, mode == "holeNear")
        val t = ModePrompts.transcribe(pb, c, 61.5, if (hole) "" else "- [متكلم:أنثى] مرحبا", chars, gloss, false, hole, mode == "holeNear")
        for (x in listOf(p, t)) {
            n++
            val prob = listOf("§", "{{", "\u0001", "undefined", "[object").filter { x.contains(it) }
            if (prob.isNotEmpty() || !x.contains("61.5") || !x.contains("منى") || !x.contains("سينسي") || !x.contains("ليلى = Layla")) { bad++; println("BAD $l|$s|$mode $prob") }
        }
        if (!hole && !p.contains("مرحبا")) { bad++; println("BAD ctx missing $l|$s") }
        if (!p.contains("\"translated\":\"...\"")) { bad++; println("BAD english schema $l|$s") }
        if (t.contains("\"translated\":\"...\"")) { bad++; println("BAD transcribe schema has translated $l|$s") }
        if (!p.contains(ModePrompts.dialectEn(l))) { bad++; println("BAD target missing $l") }
    }
    check("prompts built clean ($n)", bad == 0)

    // ===== TParse.transcript =====
    val tj = JSONObject("""{"subtitles":[{"start":1.5,"end":3.0,"original":"Hello there","gender":"female","addressee":"male","speaker_tag":"","is_song":false},
        {"start":3.2,"end":3.2,"original":"What?","gender":"male"},{"start":5.0,"end":6.0,"original":""}],"detected_source_language":"إنجليزية"}""")
    val ts = TParse.transcript(tj, 60.0, 30.0)
    check("transcript: 2 kept (empty original dropped)", ts.size == 2)
    check("transcript: translated empty", ts.all { it.translated.isEmpty() })
    check("transcript: original kept + offset", ts[0].original == "Hello there" && Math.abs(ts[0].start - 61.5) < 1e-6 && ts[0].gender == "female")
    check("transcript: zero-length gets duration", ts[1].end > ts[1].start)

    // ===== TParse.translations =====
    val r1 = TParse.translations(JSONObject("""{"translations":[{"idx":0,"translated":"أ"},{"idx":1,"translated":"ب"},{"idx":2,"translated":"ج"}]}"""), 3)
    check("translations: all 3", r1.size == 3 && r1[1]?.first == "ب" && TParse.missing(r1, 3).isEmpty())
    val r2 = TParse.translations(JSONObject("""{"translations":[{"idx":0,"translated":"أ"},{"idx":2,"translated":"  "},{"idx":7,"translated":"x"},{"idx":0,"translated":"dup"}]}"""), 3)
    check("translations: empty / out-of-range / duplicate ignored", r2.size == 1 && r2[0]?.first == "أ" && TParse.missing(r2, 3) == listOf(1, 2))
    val r3 = TParse.translations(JSONObject("""{"translations":[{"translated":"أ"},{"translated":"ب"}]}"""), 2)
    check("translations: positional fallback when idx missing", r3.size == 2 && r3[1]?.first == "ب")
    val r4 = TParse.translations(JSONObject("""{"translations":[{"translated":"أ"}]}"""), 2)
    check("translations: no positional guess when count differs", r4.isEmpty())

    // ===== translate prompt =====
    val c = Conf(listOf("k"), emptyList(), "m", "مصري", "حرفي", 60, 3, 1, emptyList(), "", true, true, true, keyMode = 3)
    val items = ts.map { it.copy(translated = "") }
    val tp = ModePrompts.translate(pb, c, items, listOf(items[0].copy(translated = "أهلا")), emptyList(), chars, gloss)
    check("translate prompt: idx json + count", tp.contains("\"idx\":0") && tp.contains("\"idx\":1") && tp.contains("2 sentences") && tp.contains("Hello there"))
    check("translate prompt: no leftovers", listOf("§", "{{", "\u0001").none { tp.contains(it) })

    // ===== (v210) تقسيم المفاتيح للوضعين 3 و4 =====
    check("split mode3: 1 key does both", KeyMode.txTrCounts(1, KeyMode.SPLIT_A) == (1 to 1))
    check("split mode3: 2 keys = 1+1", KeyMode.txTrCounts(2, KeyMode.SPLIT_A) == (1 to 1))
    check("split mode3: 4 keys = 3+1", KeyMode.txTrCounts(4, KeyMode.SPLIT_A) == (3 to 1))
    check("split mode3: 8 keys = 6+2", KeyMode.txTrCounts(8, KeyMode.SPLIT_A) == (6 to 2))
    check("split mode4: 4 keys = 3+1", KeyMode.txTrCounts(4, KeyMode.SPLIT_B) == (3 to 1))
    check("split mode4: 8 keys = 6+2", KeyMode.txTrCounts(8, KeyMode.SPLIT_B) == (6 to 2))
    check("split: always at least one of each when n>=2", (2..20).all { n -> listOf(KeyMode.SPLIT_A, KeyMode.SPLIT_B).all { m -> val (a, b) = KeyMode.txTrCounts(n, m); a >= 1 && b >= 1 && a + b == n } })
    check("withKeys keeps the rest of the conf", c.withKeys(listOf("x", "y")).let { it.keys == listOf("x", "y") && it.keyMode == 3 && it.lang == "مصري" && it.style == "حرفي" })

    println(if (fails == 0) "ModePromptsTest: كله تمام" else "ModePromptsTest: فشل $fails")
    System.exit(if (fails == 0) 0 else 1)
}
