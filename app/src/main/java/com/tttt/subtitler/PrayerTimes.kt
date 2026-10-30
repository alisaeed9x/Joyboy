package com.tttt.subtitler

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// (v655) مواقيت الصلاة — وحدة مشتركة للموبايل والتلفزيون.
// المصدر: Aladhan API بنفس نمط شاشة التلفزيون، مع كاش يومي.
// لا يوجد أي وقت ثابت في الكود: كل الأوقات من الـ API أو الكاش.
object PrayerTimes {
    val NAMES = listOf(
        "Fajr" to "الفجر",
        "Sunrise" to "الشروق",
        "Dhuhr" to "الظهر",
        "Asr" to "العصر",
        "Maghrib" to "المغرب",
        "Isha" to "العشاء"
    )
    val ORDER = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

    class Data(val city: String, val times: Map<String, String>, val hijri: String)

    fun ar(key: String): String {
        return NAMES.firstOrNull { it.first == key }?.second ?: key
    }

    // طريقة الحساب مشتركة مع شاشة التلفزيون (الافتراضي 5 = الهيئة المصرية).
    fun method(): String {
        return Cfg.str("tv_pray_method", "5")
    }

    fun fmt12(t: String): String {
        val hm = t.split(":")
        val h24 = hm.getOrNull(0)?.toIntOrNull() ?: return t
        val m = hm.getOrNull(1) ?: "00"
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val ap = if (h24 < 12) " ص" else " م"
        return h12.toString() + ":" + m + ap
    }

    fun todayMillis(t: String): Long {
        val hm = t.split(":")
        val h = hm.getOrNull(0)?.toIntOrNull() ?: 0
        val m = hm.getOrNull(1)?.toIntOrNull() ?: 0
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }



    // الصلاة الجاية: المفتاح ووقتها. بعد العشاء بترجع فجر بكرة.
    fun nextPrayer(times: Map<String, String>, now: Long): Pair<String, Long> {
        for (k in ORDER) {
            val t = times[k] ?: continue
            val at = todayMillis(t)
            if (at > now) {
                return k to at
            }
        }
        val fajr = times["Fajr"] ?: "05:00"
        return "Fajr" to (todayMillis(fajr) + 24L * 3600_000L)
    }

    // الصلاة الحالية: آخر صلاة بدأ وقتها. قبل الفجر بتكون العشاء.
    fun currentPrayer(times: Map<String, String>, now: Long): String {
        var cur = "Isha"
        for (k in ORDER) {
            val t = times[k] ?: continue
            if (todayMillis(t) <= now) {
                cur = k
            }
        }
        return cur
    }


    private fun httpText(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            try {
                c.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    // الموقع: موقع الجهاز لو الإذن متاح، وإلا تقريبي من الـ IP. بيتخزّن 24 ساعة.
    // النتيجة: خط العرض وخط الطول واسم المكان.
    private fun resolveLoc(ctx: Context, force: Boolean): Triple<Double, Double, String> {
        val p = Cfg.str("pray_loc", "").split("|")
        val ts = p.getOrNull(3)?.toLongOrNull() ?: 0L
        if (!force && p.size >= 4 && System.currentTimeMillis() - ts < 24L * 3600_000L) {
            val la = p[0].toDoubleOrNull()
            val lo = p[1].toDoubleOrNull()
            if (la != null && lo != null) {
                return Triple(la, lo, p[2])
            }
        }
        var lat: Double
        var lon: Double
        var city = ""
        val dl = deviceLoc(ctx)
        if (dl != null) {
            lat = dl.first
            lon = dl.second
            city = "حسب موقع الجهاز"
        } else {
            val j = JSONObject(httpText("https://ipwho.is/"))
            lat = j.getDouble("latitude")
            lon = j.getDouble("longitude")
            val parts = listOf(j.optString("city", ""), j.optString("country", "")).filter { it.isNotBlank() }
            city = parts.joinToString("، ")
        }
        Cfg.put("pray_loc", "$lat|$lon|$city|${System.currentTimeMillis()}")
        return Triple(lat, lon, city)
    }

    private fun deviceLoc(ctx: Context): Pair<Double, Double>? {
        return try {
            if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                null
            } else {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val l = lm.getProviders(true).mapNotNull { prov ->
                    try {
                        lm.getLastKnownLocation(prov)
                    } catch (_: Throwable) {
                        null
                    }
                }.maxByOrNull { it.time }
                if (l != null) {
                    l.latitude to l.longitude
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    // تحميل مواقيت اليوم: الكاش أولًا، والـ API لو الكاش قديم أو force.
    // الـ callback بيتنادى من خيط خلفي — تحديث الـ RemoteViews مسموح من أي خيط.
    fun loadData(ctx: Context, force: Boolean, cb: (Data?) -> Unit) {
        Thread {
            val res = try {
                Cfg.ensure(ctx)
                val app = ctx.applicationContext
                val (lat, lon, city) = resolveLoc(app, force)
                val day = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
                val key = day + "|" + method() + "|" + String.format(Locale.US, "%.2f,%.2f", lat, lon)
                val cached = Cfg.str("pray_cache", "").split("\n")
                if (!force && cached.size >= 3 && cached[0] == key) {
                    val m = cached[1].split(";").mapNotNull { kv ->
                        val parts = kv.split("=")
                        if (parts.size == 2) parts[0] to parts[1] else null
                    }.toMap()
                    if (m.size < 5) null else Data(city, m, cached[2])
                } else {
                    val j = JSONObject(httpText("https://api.aladhan.com/v1/timings/$day?latitude=$lat&longitude=$lon&method=${method()}"))
                    val t = j.getJSONObject("data").getJSONObject("timings")
                    val m = NAMES.associate { it.first to t.getString(it.first).take(5) }
                    val hijri = try {
                        val hj = j.getJSONObject("data").getJSONObject("hijri")
                        hj.optString("day", "") + " " + hj.getJSONObject("month").optString("ar", "") + " " + hj.optString("year", "")
                    } catch (_: Throwable) {
                        ""
                    }
                    Cfg.put("pray_cache", key + "\n" + m.entries.joinToString(";") { it.key + "=" + it.value } + "\n" + hijri)
                    if (m.size < 5) null else Data(city, m, hijri)
                }
            } catch (e: Throwable) {
                LogStore.err("PrayerTimes:load", e)
                null
            }
            cb(res)
        }.also { it.isDaemon = true }.start()
    }
}
