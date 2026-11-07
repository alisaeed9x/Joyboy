package com.tttt.joyboy.desk

import org.json.JSONObject
import java.io.File

/** انضمام تلقائي لهوت سبوت الموبايل (ويندوز: netsh wlan). بيتحفظ الاسم والباسورد مرة واحدة. */
object Hotspot {
    private val file = File(Store.home, "hotspot.json")
    @Volatile var ssid = ""
    @Volatile var pass = ""
    @Volatile var auto = true
    @Volatile var status = ""
    var onStatus: (String) -> Unit = {}
    @Volatile private var running = false

    init { try { val j = JSONObject(file.readText()); ssid = j.optString("ssid"); pass = j.optString("pass"); auto = j.optBoolean("auto", true) } catch (_: Throwable) {} }

    fun save(s: String, p: String, a: Boolean) {
        ssid = s.trim(); pass = p; auto = a
        try { file.writeText(JSONObject().put("ssid", ssid).put("pass", pass).put("auto", auto).toString()) } catch (_: Throwable) {}
    }

    /** «WIFI:T:WPA;S:name;P:pass;;» (اللي الموبايل بينسخه) */
    fun parseWifiString(t: String): Pair<String, String>? {
        val s = t.trim(); if (!s.startsWith("WIFI:", true)) return null
        fun field(k: String): String { val m = Regex("(?:^|;)$k:((?:\\\\.|[^;])*)").find(s.substring(5)) ?: return ""; return m.groupValues[1].replace(Regex("\\\\(.)"), "$1") }
        val n = field("S"); if (n.isEmpty()) return null
        return Pair(n, field("P"))
    }

    private fun esc(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun run(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val o = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS); o
    } catch (_: Throwable) { "" }

    private fun isWin() = System.getProperty("os.name", "").lowercase().contains("win")

    private fun currentSsid(): String {
        val o = run("netsh", "wlan", "show", "interfaces")
        val m = Regex("(?m)^\\s*SSID\\s*:\\s*(.+)$").find(o); return m?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun visible(name: String): Boolean = run("netsh", "wlan", "show", "networks").lines().any { Regex("^\\s*SSID\\s+\\d+\\s*:\\s*(.+)$").find(it)?.groupValues?.get(1)?.trim() == name }

    /** يعمل بروفايل ويندوز ويتوصل بيه */
    fun join(): Boolean {
        if (!isWin()) { set("⚠ الانضمام التلقائي للويندوز بس"); return false }
        if (ssid.isBlank()) { set("اكتب اسم الهوت سبوت الأول"); return false }
        val sec = if (pass.isEmpty()) "<authEncryption><authentication>open</authentication><encryption>none</encryption><useOneX>false</useOneX></authEncryption>"
        else "<authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption><sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>${esc(pass)}</keyMaterial></sharedKey>"
        val xml = """<?xml version="1.0"?><WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1"><name>${esc(ssid)}</name><SSIDConfig><SSID><name>${esc(ssid)}</name></SSID></SSIDConfig><connectionType>ESS</connectionType><connectionMode>auto</connectionMode><MSM><security>$sec</security></MSM></WLANProfile>"""
        val f = File.createTempFile("jbhs", ".xml")
        return try {
            f.writeText(xml, Charsets.UTF_8)
            run("netsh", "wlan", "add", "profile", "filename=${f.absolutePath}", "user=current")
            val o = run("netsh", "wlan", "connect", "name=$ssid", "ssid=$ssid")
            val ok = o.contains("successfully", true) || o.contains("بنجاح")
            set(if (ok) "📶 بتوصل بـ«$ssid»…" else "⚠ ما قدرتش أتوصل بـ«$ssid» — اتأكد إن الهوت سبوت شغّال على الموبايل والباسورد صح")
            ok
        } finally { f.delete() }
    }

    private fun set(s: String) { status = s; onStatus(s) }

    /** كل 15 ثانية: لو مفيش موبايل متصل والهوت سبوت ظاهر وإحنا مش عليه ← ينضم */
    fun startAuto() {
        if (running) return; running = true
        Thread {
            while (true) {
                try {
                    Thread.sleep(15_000)
                    if (!auto || ssid.isBlank() || !isWin()) continue
                    if (Store.remotes.values.any { it.alive() }) continue
                    if (currentSsid() == ssid) continue
                    if (visible(ssid)) join()
                } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true }.start()
    }
}
