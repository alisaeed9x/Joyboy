package devtests.fastmodetest

import com.tttt.subtitler.*

var pass = 0; var fail = 0
fun check(name: String, ok: Boolean) { if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name") } }

fun main() {
    // ===== StreamSubs: الجمل بتتطلّع أول ما قوسها يتقفل =====
    val full = "```json\n{\"detected_source_language\":\"إنجليزية\",\"subtitles\":[" +
        "{\"start\":0.5,\"end\":2.0,\"original\":\"Hello {world}\",\"translated\":\"أهلا\",\"people\":[]}," +
        "{\"start\":2.5,\"end\":4.0,\"original\":\"He said \\\"hi\\\"\",\"translated\":\"قال مرحبا\"}," +
        "{\"start\":5.0,\"end\":6.0,\"original\":\"Third\",\"translated\":\"التالت\"}]}\n```"
    val sc = StreamSubs()
    var total = 0
    var i = 0
    while (i < full.length) { i = minOf(full.length, i + 7); total += sc.feed(full.substring(0, i)) }
    check("StreamSubs: 3 جمل بالتقطيع على أجزاء صغيرة", total == 3 && sc.all.size == 3)
    check("StreamSubs: قوس جوه نص ما بيكسرش", sc.all[0].getString("original") == "Hello {world}")
    check("StreamSubs: علامة اقتباس متهربة", sc.all[1].getString("original") == "He said \"hi\"")
    val sc2 = StreamSubs()
    val cut = full.substring(0, full.indexOf("\"Third\"") - 25)
    check("StreamSubs: جملة ناقصة ما تتطلّعش", sc2.feed(cut) == 2)
    val sc3 = StreamSubs()
    check("StreamSubs: كائن من غير start/original بيتجاهل", sc3.feed("{\"subtitles\":[],\"prev_corrections\":[{\"original_snippet\":\"x\",\"translated\":\"y\"}]}") == 0)

    // ===== Ranges.nextGap / gapEnd =====
    val r = Ranges()
    r.add(10.0, 20.0); r.add(20.0, 30.0); r.add(50.0, 60.0)
    check("nextGap: جوه مجال مدموج", Math.abs(r.nextGap(12.0, 100.0) - 30.0) < 0.01)
    check("nextGap: نقطة فاضية بترجع نفسها", Math.abs(r.nextGap(35.0, 100.0) - 35.0) < 0.01)
    check("nextGap: كله مغطّى لحد to", Math.abs(r.nextGap(52.0, 58.0) - 58.0) < 0.01)
    check("gapEnd: لحد أول مجال بعدها", Math.abs(r.gapEnd(30.0, 100.0) - 50.0) < 0.01)
    check("gapEnd: مفيش مجال بعدها = to", Math.abs(r.gapEnd(60.0, 90.0) - 90.0) < 0.01)
    check("gapEnd: بيتقصّ على to", Math.abs(r.gapEnd(30.0, 40.0) - 40.0) < 0.01)

    // ===== (v1066) برومبت الوضع السريع الخفيف =====
    val pb = PromptBuilder { java.io.File(System.getenv("SUBTITLER_ASSETS") ?: "app/src/main/assets", it).readText(Charsets.UTF_8) }
    val cf = Conf(listOf("k"), emptyList(), "m", "مصري", "تلقائي", 60, 3, 1, emptyList(), "", true, true, true, keyMode = 2)
    val fp = ModePrompts.fast(pb, cf, 10.0, listOf(Gloss("سينسي", "معلم")), "يابانية", false)
    check("fast: مفيش placeholders", !fp.contains("{{") && !fp.contains("§") && !fp.contains("undefined"))
    check("fast: بيحتوي اللهجة والـ JSON المختصر", fp.contains("Egyptian") && fp.contains("\"translated\"") && fp.contains("detected_source_language"))
    check("fast: من غير حقول الجنس/المخاطَب", !fp.contains("addressee") && !fp.contains("topic_gender") && !fp.contains("emotion"))
    check("fast: أقصر بكتير من برومبت الوضع 2", fp.length * 3 < ModePrompts.english(pb, cf, 10.0, "", emptyList(), emptyList()).length)

    // ===== (v1067) أدوات الصوت: تسريع/قص صمت/قص جزء =====
    fun mkWav(sil1: Double, tone: Double, sil2: Double): ByteArray {
        val r = 16000; val n1 = (sil1 * r).toInt(); val n2 = (tone * r).toInt(); val n3 = (sil2 * r).toInt()
        val total = n1 + n2 + n3; val pcm = ByteArray(total * 2)
        for (k in 0 until total) {
            val v = if (k in n1 until n1 + n2) { val t = k / r.toDouble(); (9000 * (Math.sin(2 * Math.PI * 200 * t) + 0.5 * Math.sin(2 * Math.PI * 410 * t))).toInt() } else 0
            pcm[2 * k] = (v and 255).toByte(); pcm[2 * k + 1] = ((v shr 8) and 255).toByte()
        }
        return Wav.header(pcm.size, r) + pcm
    }
    val aw = mkWav(3.0, 6.0, 2.0)
    val sped = AudioTools.speedUp(aw, 1.25)
    val ratio = ((aw.size - 44) / 2).toDouble() / ((sped.size - 44) / 2)
    check("speedUp: أقصر بـ1.25 مرة تقريبًا", Math.abs(ratio - 1.25) < 0.04)
    check("speedUp: هيدر WAV سليم", String(sped, 0, 4) == "RIFF" && sped.size > 44)
    check("speedUp: k=1 بيرجّع نفس الملف", AudioTools.speedUp(aw, 1.0) === aw)
    val tr = AudioTools.trimSilence(aw)
    check("trimSilence: بيشيل صمت الأطراف بهامش", tr != null && Math.abs(tr.leadSec - 2.6) < 0.15 && Math.abs(tr.durSec - 6.8) < 0.3)
    check("trimSilence: صمت قليل = null", AudioTools.trimSilence(mkWav(0.2, 6.0, 0.2)) == null)
    check("cut: ثانية = 16000 عينة", (AudioTools.cut(aw, 2.0, 3.0).size - 44) / 2 == 16000)

    println("PASS=$pass FAIL=$fail")
    System.exit(if (fail == 0) 0 else 1)
}
