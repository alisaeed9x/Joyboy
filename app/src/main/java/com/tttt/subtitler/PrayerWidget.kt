package com.tttt.subtitler

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// (v655) ويدجت مواقيت الصلاة للشاشة الرئيسية — بثلاثة مقاسات حسب عرض الويدجت.
// البيانات حقيقية من PrayerTimes (API + كاش) — لا أوقات ثابتة ولا بيانات تجريبية.
// الألوان من ثيم التطبيق الحالي، والخلفية صورة مسجد من أصول الويدجت حسب الصلاة الحالية.
class PrayerWidget : AppWidgetProvider() {
    companion object {
        const val ACT_REFRESH = "com.tttt.joyboy.PRAYER_REFRESH"
        const val ACT_ALARM = "com.tttt.joyboy.PRAYER_ALARM"

        fun renderAll(c: Context, mgr: AppWidgetManager, ids: IntArray, force: Boolean = false) {
            if (ids.isEmpty()) return
            PrayerTimes.loadData(c, force) { data ->
                val app = c.applicationContext
                for (id in ids) {
                    try {
                        renderOne(app, mgr, id, data)
                    } catch (e: Throwable) {
                        LogStore.err("PrayerWidget:render", e)
                    }
                }
            }
        }

        private fun layoutFor(mgr: AppWidgetManager, id: Int): String {
            val w = try {
                mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            } catch (_: Throwable) {
                250
            }
            if (w < 300) return "widget_prayer_small"
            if (w < 480) return "widget_prayer_medium"
            return "widget_prayer_large"
        }

        private fun bc(c: Context, action: String, code: Int): PendingIntent {
            return PendingIntent.getBroadcast(c, code, Intent(c, PrayerWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun bgBitmap(c: Context, file: String): Bitmap? {
            return try {
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                c.assets.open("widgets/prayer-times/backgrounds/" + file).use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (opts.outWidth / sample > 640 && opts.outHeight / sample > 360) {
                    sample *= 2
                }
                val opts2 = BitmapFactory.Options()
                opts2.inSampleSize = sample
                opts2.inPreferredConfig = Bitmap.Config.RGB_565
                c.assets.open("widgets/prayer-times/backgrounds/" + file).use { BitmapFactory.decodeStream(it, null, opts2) }
            } catch (_: Throwable) {
                null
            }
        }

        private fun renderOne(c: Context, mgr: AppWidgetManager, id: Int, data: PrayerTimes.Data?) {
            try {
                Cfg.ensure(c)
            } catch (_: Throwable) {
            }
            val th = try {
                Themes.byId(Cfg.str("theme", "mx"))
            } catch (_: Throwable) {
                Themes.byId("mx")
            }
            val layout = layoutFor(mgr, id)
            val rv = RemoteViews(c.packageName, WidgetHub.rid(c, "layout", layout))
            val root = WidgetHub.rid(c, "id", "w_p_root")
            rv.setOnClickPendingIntent(root, bc(c, ACT_REFRESH, 41))
            if (data == null) {
                rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_city"), "🕌 مواقيت الصلاة")
                rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_next"), "⚠ ما قدرتش أجيب المواقيت")
                rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_time"), "دوس للتحديث")
                rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_cd"), "")
                rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_strip"), "اتأكد من النت ودوس على الويدجت")
                mgr.updateAppWidget(id, rv)
                return
            }
            val now = System.currentTimeMillis()
            val next = PrayerTimes.nextPrayer(data.times, now)
            val cur = PrayerTimes.currentPrayer(data.times, now)
            val left = next.second - now
            val nextAr = PrayerTimes.ar(next.first)
            val curAr = PrayerTimes.ar(cur)
            rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_city"), "📍 " + data.city)
            rv.setTextColor(WidgetHub.rid(c, "id", "w_p_city"), th.muted)
            rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_next"), "الصلاة الجاية: " + nextAr)
            rv.setTextColor(WidgetHub.rid(c, "id", "w_p_next"), th.text)
            rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_time"), PrayerTimes.fmt12(data.times[next.first] ?: ""))
            rv.setTextColor(WidgetHub.rid(c, "id", "w_p_time"), th.primary)
            rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_cd"), "باقي " + PrayerTimes.fmtLeft(left))
            rv.setTextColor(WidgetHub.rid(c, "id", "w_p_cd"), th.text)
            val strip = PrayerTimes.NAMES.joinToString(" · ") { PrayerTimes.ar(it.first) + " " + PrayerTimes.fmt12(data.times[it.first] ?: "") }
            rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_strip"), strip)
            rv.setTextColor(WidgetHub.rid(c, "id", "w_p_strip"), th.muted)
            if (layout == "widget_prayer_large") {
                val nowTxt = PrayerTimes.fmt12(SimpleDateFormat("HH:mm", Locale.US).format(Date()))
                rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_now"), nowTxt)
                rv.setTextColor(WidgetHub.rid(c, "id", "w_p_now"), th.text)
                val greg = SimpleDateFormat("EEEE، d MMMM yyyy", Locale("ar")).format(Date())
                val dateTxt = if (data.hijri.isNotBlank()) greg + " · " + data.hijri else greg
                rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_date"), dateTxt)
                rv.setTextColor(WidgetHub.rid(c, "id", "w_p_date"), th.muted)
                rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_cur"), "الحالية: " + curAr)
                rv.setTextColor(WidgetHub.rid(c, "id", "w_p_cur"), th.primary)
                for (i in PrayerTimes.NAMES.indices) {
                    val k = PrayerTimes.NAMES[i].first
                    val isNext = k == next.first
                    rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_n" + i), PrayerTimes.ar(k))
                    rv.setTextColor(WidgetHub.rid(c, "id", "w_p_n" + i), if (isNext) th.primary else th.muted)
                    rv.setTextViewText(WidgetHub.rid(c, "id", "w_p_t" + i), PrayerTimes.fmt12(data.times[k] ?: ""))
                    rv.setTextColor(WidgetHub.rid(c, "id", "w_p_t" + i), if (isNext) th.primary else th.text)
                }
            }
            if (layout != "widget_prayer_small") {
                val bmp = bgBitmap(c, PrayerTimes.bgFor(cur))
                if (bmp != null) {
                    rv.setImageViewBitmap(WidgetHub.rid(c, "id", "w_p_bg"), bmp)
                }
                val overlay = (0x88 shl 24) or (th.bg and 0x00FFFFFF)
                rv.setInt(WidgetHub.rid(c, "id", "w_p_shade"), "setBackgroundColor", overlay)
            }
            mgr.updateAppWidget(id, rv)
        }

        fun setAlarm(c: Context, on: Boolean) {
            try {
                val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(c, 42, Intent(c, PrayerWidget::class.java).setAction(ACT_ALARM), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                if (on) {
                    am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60000L, 60000L, pi)
                } else {
                    am.cancel(pi)
                }
            } catch (e: Throwable) {
                LogStore.err("PrayerWidget:alarm", e)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAll(context, appWidgetManager, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        setAlarm(context, true)
    }

    override fun onDisabled(context: Context) {
        setAlarm(context, false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACT_REFRESH -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, PrayerWidget::class.java))
                renderAll(context, mgr, ids)
            }
            ACT_ALARM -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, PrayerWidget::class.java))
                renderAll(context, mgr, ids)
            }
            else -> super.onReceive(context, intent)
        }
    }
}
