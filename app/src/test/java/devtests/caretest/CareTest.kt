package devtests.caretest

import com.tttt.subtitler.CareScore
var okN = 0; var bad = 0
fun chk(n: String, c: Boolean) { if (c) { okN++; println("PASS $n") } else { bad++; println("FAIL $n") } }
fun main() {
    chk("مفيش قياسات = مفيش درجة", CareScore.compute(null, null, null, false, null).score == null)
    chk("كله طبيعي = 100", CareScore.compute(false, 0.5f, 80, false, 30f).score == 100)
    chk("تخزين فاضي 10% = −10", CareScore.compute(false, 0.10f, 80, false, 30f).score == 90)
    chk("تخزين فاضي 5% = −20", CareScore.compute(false, 0.05f, 80, false, 30f).score == 80)
    chk("lowMemory = −15", CareScore.compute(true, 0.5f, 80, false, 30f).score == 85)
    chk("رام قليلة بدون lowMemory مش بتتخصم", CareScore.compute(false, 0.5f, 80, false, 30f).items.isEmpty())
    chk("بطارية 25% من غير شحن = −4", CareScore.compute(false, 0.5f, 25, false, 30f).score == 96)
    chk("بطارية 10% من غير شحن = −10", CareScore.compute(false, 0.5f, 10, false, 30f).score == 90)
    chk("بطارية 10% على الشاحن = مفيش خصم", CareScore.compute(false, 0.5f, 10, true, 30f).score == 100)
    chk("حرارة 41 = −10", CareScore.compute(false, 0.5f, 80, false, 41f).score == 90)
    chk("حرارة 46 = −20", CareScore.compute(false, 0.5f, 80, false, 46f).score == 80)
    chk("قياس ناقص بيتخطّى (حرارة null)", CareScore.compute(false, 0.5f, 80, false, null).score == 100)
    chk("كل العيوب مع بعض ما بتنزلش عن 0", (CareScore.compute(true, 0.01f, 5, false, 50f).score ?: -1) in 0..100)
    println("الاختبارات: $okN نجح، $bad فشل")
}
