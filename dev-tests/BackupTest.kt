import com.tttt.subtitler.*
import org.json.JSONObject

private var fails = 0
private fun check(name: String, ok: Boolean) { if (ok) println("PASS $name") else { fails++; println("FAIL $name") } }

fun main() {
    val all = mapOf<String, Any?>(
        "keys" to "AIzaKEY1xxxxxxxx\nAIzaKEY2xxxxxxxx", "theme" to "mx", "kmode" to 4, "chunk" to 60, "sub_nobg" to true,
        "ts_long" to 1234567890123L, "scale" to 1.5f, "tags" to setOf("a", "ب"),
        "lock_hash" to "SECRET", "lock_salt" to "S", "lock_bio" to "1", "kv_init" to true, "nul" to null)
    val json = BackupCodec.encode(all, 42L)
    check("format + ts", JSONObject(json).getString("format") == BackupCodec.FORMAT && BackupCodec.timestamp(json) == 42L)
    check("lock/kv_init مابتتحفظش", !json.contains("SECRET") && !json.contains("lock_") && !json.contains("kv_init"))
    val items = BackupCodec.decode(json).associateBy { it.key }
    check("عدد القيم 8", items.size == 8)
    check("نص بسطرين", items["keys"]?.value == "AIzaKEY1xxxxxxxx\nAIzaKEY2xxxxxxxx" && items["keys"]?.type == "s")
    check("int", items["kmode"]?.value == 4 && items["kmode"]?.type == "i")
    check("boolean", items["sub_nobg"]?.value == true && items["sub_nobg"]?.type == "b")
    check("long", items["ts_long"]?.value == 1234567890123L)
    check("float", items["scale"]?.value == 1.5f)
    check("set عربي", items["tags"]?.value == setOf("a", "ب"))
    check("encode ثابت (من غير ts)", BackupCodec.encode(all, 0L) == BackupCodec.encode(all, 0L))
    check("ملف مش JSON بيترفض", try { BackupCodec.decode("hello"); false } catch (e: IllegalArgumentException) { true })
    check("JSON غريب بيترفض", try { BackupCodec.decode("{\"a\":1}"); false } catch (e: IllegalArgumentException) { true })
    check("نسخة بدون prefs بتترفض", try { BackupCodec.decode("{\"format\":\"subtitler-backup\"}"); false } catch (e: IllegalArgumentException) { true })
    val tampered = JSONObject(json); tampered.getJSONObject("prefs").put("lock_hash", JSONObject().put("t", "s").put("v", "X"))
    check("lock_ في ملف متعدّل بيتتجاهل", BackupCodec.decode(tampered.toString()).none { it.key == "lock_hash" })
    println(if (fails == 0) "كل اختبارات BackupTest نجحت" else "BackupTest فشل: $fails")
    if (fails > 0) System.exit(1)
}
