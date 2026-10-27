package devtests

import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * (v589 T4) بيشغّل اختبارات المنطق القديمة (كانت في dev-tests/ بتتشغّل بسكريبت لوحده وقديم).
 * كل ملف اختبار بيقفل بـ System.exit، فبنشغّله في JVM مستقلة بنفس classpath بتاع Gradle
 * ونعتبره فاشل لو الخروج مش 0 أو في سطر بيبدأ بـ FAIL.
 */
class LogicTestsRunner {
    private val mains = listOf(
        "devtests.audiohlstest.AudioHlsTestKt",
        "devtests.backuptest.BackupTestKt",
        "devtests.blurtest.BlurTestKt",
        "devtests.braincoretest.BrainCoreTestKt",
        "devtests.coveragetest.CoverageTestKt",
        "devtests.enginetest.EngineTestKt",
        "devtests.giftest.GifTestKt",
        "devtests.livetest.LiveTestKt",
        "devtests.locktest.LockTestKt",
        "devtests.modepromptstest.ModePromptsTestKt",
        "devtests.moretest.MoreTestKt",
        "devtests.nolosstest.NoLossTestKt",
        "devtests.phaseetest.PhaseETestKt",
        "devtests.playerlogictest.PlayerLogicTestKt",
        "devtests.prompttest.PromptTestKt",
        "devtests.remotelibtest.RemoteLibTestKt",
        "devtests.soundtest.SoundTestKt",
        "devtests.speechtest.SpeechTestKt",
        "devtests.substyletest.SubStyleTestKt"
    )

    @Test fun allLogicTests() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cp = System.getProperty("java.class.path")
        val assets = System.getenv("SUBTITLER_ASSETS") ?: "src/main/assets"
        val bad = ArrayList<String>()
        for (m in mains) {
            val out = File.createTempFile("lt", ".log")
            try {
                val pb = ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", cp, m)
                    .redirectErrorStream(true).redirectOutput(out)
                pb.environment()["SUBTITLER_ASSETS"] = assets
                val p = pb.start()
                if (!p.waitFor(240, TimeUnit.SECONDS)) { p.destroyForcibly(); bad.add(m + " : timeout"); continue }
                val txt = out.readText(Charsets.UTF_8)
                val fails = txt.lines().filter { it.trimStart().startsWith("FAIL") }
                println("== " + m + " exit=" + p.exitValue() + " fails=" + fails.size)
                if (p.exitValue() != 0 || fails.isNotEmpty()) {
                    bad.add(m + " : exit=" + p.exitValue() + (if (fails.isNotEmpty()) " | " + fails.take(5).joinToString(" ; ") else " | " + txt.lines().takeLast(4).joinToString(" / ")))
                }
            } finally { out.delete() }
        }
        if (bad.isNotEmpty()) fail(bad.size.toString() + " من " + mains.size + " اختبار فشلوا:\n" + bad.joinToString("\n"))
    }
}
