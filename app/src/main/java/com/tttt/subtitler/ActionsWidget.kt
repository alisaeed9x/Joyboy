package com.tttt.subtitler

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

// (v1113) ويدجت «⚙ أكشنز GitHub»: آخر 3 تشغيلات من GitHub Actions (النسخة + النتيجة + الوقت).
// الدوس على التشغيل بيفتح صفحته على GitHub، والدوس على الهيدر بيحدّث. التحديث الدوري من updatePeriodMillis في الـ info.
// نفس نمط PrayerWidget: رسم فوري من الكاش + جلب الشبكة في خيط خلفي مع goAsync.
class ActionsWidget : AppWidgetProvider() {
    companion object {
        const val ACT_REFRESH = "com.tttt.joyboy.ACTIONS_REFRESH"
        private const val RUNS_URL = "https://api.github.com/repos/alisaeed9x/Joyboy/actions/runs?per_page=3"
        private const val CACHE_MAX_AGE = 30L * 60L * 1000L

        private class Run(val ver: String, val st: String, val time: String, val url: String)

        private fun prefs(c: Context) = c.getSharedPreferences("actions_widget", Context.MODE_PRIVATE)

        private fun readCache(c: Context): String? {
            val j = prefs(c).getString("runs_json", null)
            if (j.isNullOrBlank()) return null
            return j
        }

        private fun cacheAge(c: Context): Long {
            val at = prefs(c).getLong("runs_at", 0L)
            if (at <= 0L) return Long.MAX_VALUE
            return System.currentTimeMillis() - at
        }

        private fun saveCache(c: Context, json: String) {
            prefs(c).edit().putString("runs_json", json).putLong("runs_at", System.currentTimeMillis()).apply()
        }

        private fun bc(c: Context, action: String, code: Int): PendingIntent {
            return PendingIntent.getBroadcast(c, code, Intent(c, ActionsWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        // (v1113) وقت التشغيل من ISO UTC لصيغة يوم/شهر ساعة:دقيقة بتوقيت الجهاز
        private fun fmtTime(iso: String): String {
            if (iso.isBlank()) return ""
            return try {
                val p = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                p.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val d = p.parse(iso) ?: return ""
                val f = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
                f.format(d)
            } catch (_: Throwable) {
                ""
            }
        }

        private fun parseRuns(json: String): List<Run> {
            val out = ArrayList<Run>()
            try {
                val arr = org.json.JSONObject(json).optJSONArray("workflow_runs") ?: return out
                for (i in 0 until minOf(arr.length(), 3)) {
                    val o = arr.getJSONObject(i)
                    val title = o.optString("display_title").ifBlank { o.optString("name") }
                    val ver = Regex("v(\\d+)").find(title)?.value ?: title.ifBlank { "تشغيل" }
                    val status = o.optString("status")
                    val concl = o.optString("conclusion")
                    val st = when {
                        status == "completed" && concl == "success" -> "✅ نجح"
                        status == "completed" && concl == "failure" -> "❌ فشل"
                        status == "completed" && concl == "cancelled" -> "⚪ اتلغى"
                        status == "completed" -> "✅ خلص"
                        else -> "⏳ شغّال"
                    }
                    val time = fmtTime(o.optString("created_at"))
                    val url = o.optString("html_url")
                    out.add(Run(ver, st, time, url))
                }
            } catch (_: Throwable) {}
            return out
        }

        private fun fetchRuns(): String? {
            return try {
                val conn = java.net.URL(RUNS_URL).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Joyboy")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connect()
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    null
                } else {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    body
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun renderRuns(c: Context, mgr: AppWidgetManager, ids: IntArray, runs: List<Run>) {
            val app = c.applicationContext
            for (id in ids) {
                try {
                    val rv = RemoteViews(app.packageName, WidgetHub.rid(app, "layout", "widget_actions"))
                    rv.setOnClickPendingIntent(WidgetHub.rid(app, "id", "w_a_head"), bc(app, ACT_REFRESH, 30))
                    for (i in 0 until 3) {
                        val row = WidgetHub.rid(app, "id", "w_a_row" + i)
                        val run = runs.getOrNull(i)
                        if (run == null) {
                            rv.setViewVisibility(row, View.GONE)
                        } else {
                            rv.setViewVisibility(row, View.VISIBLE)
                            rv.setTextViewText(WidgetHub.rid(app, "id", "w_a_v" + i), run.ver)
                            rv.setTextViewText(WidgetHub.rid(app, "id", "w_a_s" + i), run.st)
                            rv.setTextViewText(WidgetHub.rid(app, "id", "w_a_t" + i), run.time)
                            if (run.url.isNotBlank()) {
                                val pi = PendingIntent.getActivity(app, 31 + i, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(run.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                                rv.setOnClickPendingIntent(row, pi)
                            } else {
                                rv.setOnClickPendingIntent(row, bc(app, ACT_REFRESH, 30))
                            }
                        }
                    }
                    mgr.updateAppWidget(id, rv)
                } catch (e: Throwable) {
                    LogStore.err("ActionsWidget:render", e)
                }
            }
        }

        fun renderAll(c: Context, mgr: AppWidgetManager, ids: IntArray, force: Boolean = false, done: (() -> Unit)? = null) {
            if (ids.isEmpty()) {
                done?.invoke()
                return
            }
            val cached = readCache(c)
            if (cached != null) renderRuns(c, mgr, ids, parseRuns(cached))
            if (!force && cached != null && cacheAge(c) < CACHE_MAX_AGE) {
                done?.invoke()
                return
            }
            Thread {
                val fresh = fetchRuns()
                if (fresh != null) {
                    saveCache(c, fresh)
                    renderRuns(c, mgr, ids, parseRuns(fresh))
                } else if (cached == null) {
                    renderRuns(c, mgr, ids, listOf(Run("⚠ ما قدرتش أجيب الأكشنز", "دوس للتحديث", "", "")))
                }
                done?.invoke()
            }.apply { isDaemon = true }.start()
        }
    }

    private fun renderAsync(context: Context, ids: IntArray, force: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(context)
        val pr = try { goAsync() } catch (_: Throwable) { null }
        renderAll(context.applicationContext, mgr, ids, force) { try { pr?.finish() } catch (_: Throwable) {} }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAsync(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACT_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ActionsWidget::class.java))
            if (ids.isNotEmpty()) renderAsync(context, ids, true)
        } else {
            super.onReceive(context, intent)
        }
    }
}
