package com.tttt.subtitler

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

// (v655) ويدجت مركز النقل للشاشة الرئيسية — حالة حقيقية فقط، بدون نسب وهمية.
// الموبايل: التلفزيون المتصل أو المقترن. التلفزيون: عدد الملفات المعروضة للموبايل.
// الدوسة بتفتح مركز النقل.
class TransferWidget : AppWidgetProvider() {
    companion object {
        fun renderAll(c: Context, mgr: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            try {
                Cfg.ensure(c)
            } catch (_: Throwable) {
            }
            val th = try {
                Themes.byId(Cfg.str("theme", "mx"))
            } catch (_: Throwable) {
                Themes.byId("mx")
            }
            val tv = try {
                DeviceUi.isTv(c)
            } catch (_: Throwable) {
                false
            }
            val status = if (tv) {
                val n = try {
                    TvOutbox.all().size
                } catch (_: Throwable) {
                    0
                }
                if (n > 0) "📺 التلفزيون — " + n + " ملف معروض للموبايل" else "📺 التلفزيون — مفيش ملفات معروضة"
            } else {
                val cur = try {
                    RemoteClient.current
                } catch (_: Throwable) {
                    null
                }
                val last = try {
                    RemoteClient.lastTv()
                } catch (_: Throwable) {
                    null
                }
                if (cur != null) {
                    "📺 متصل: " + cur.name
                } else if (last != null) {
                    "📺 المقترن: " + last.name + " (مش متصل)"
                } else {
                    "مفيش تلفزيون مقترن"
                }
            }
            for (id in ids) {
                try {
                    val rv = RemoteViews(c.packageName, WidgetHub.rid(c, "layout", "widget_transfer"))
                    rv.setTextViewText(WidgetHub.rid(c, "id", "w_x_title"), "📤 مركز النقل")
                    rv.setTextColor(WidgetHub.rid(c, "id", "w_x_title"), th.text)
                    rv.setTextViewText(WidgetHub.rid(c, "id", "w_x_status"), status)
                    rv.setTextColor(WidgetHub.rid(c, "id", "w_x_status"), th.muted)
                    rv.setTextViewText(WidgetHub.rid(c, "id", "w_x_hint"), "دوس لفتح مركز النقل")
                    rv.setTextColor(WidgetHub.rid(c, "id", "w_x_hint"), th.muted)
                    val open = PendingIntent.getActivity(c, 61,
                        Intent(c, FileTransferActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    rv.setOnClickPendingIntent(WidgetHub.rid(c, "id", "w_x_root"), open)
                    mgr.updateAppWidget(id, rv)
                } catch (e: Throwable) {
                    LogStore.err("TransferWidget:render", e)
                }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAll(context, appWidgetManager, appWidgetIds)
    }
}
