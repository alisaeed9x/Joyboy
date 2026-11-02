package com.tttt.subtitler

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

// (v655) ويدجت مشغل الموسيقى للشاشة الرئيسية — مرتبط بمحرك الموسيقى الموجود MusicEngine.
// لا يوجد مشغّل ثانٍ: الأزرار بتنادي toggle و step الموجودين، وبيانات الأغنية من MusicEngine.current.
// حالة عدم وجود أغنية لها واجهة Empty State — مش صورة فنان عشوائية.
class MusicWidget : AppWidgetProvider() {
    companion object {
        const val ACT_TOGGLE = "com.tttt.joyboy.MUSIC_TOGGLE"
        const val ACT_NEXT = "com.tttt.joyboy.MUSIC_NEXT"
        const val ACT_PREV = "com.tttt.joyboy.MUSIC_PREV"

        private fun bc(c: Context, action: String, code: Int): PendingIntent {
            return PendingIntent.getBroadcast(c, code, Intent(c, MusicWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun fmt(ms: Int): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }

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
            for (id in ids) {
                try {
                    val rv = RemoteViews(c.packageName, WidgetHub.rid(c, "layout", "widget_music"))
                    val t = MusicEngine.current
                    val barId = WidgetHub.rid(c, "id", "w_m_bar")
                    if (t == null) {
                        rv.setTextViewText(WidgetHub.rid(c, "id", "w_m_title"), "مفيش أغنية شغالة")
                        rv.setTextViewText(WidgetHub.rid(c, "id", "w_m_artist"), "شغّل من مكتبة الموسيقى")
                        rv.setTextViewText(WidgetHub.rid(c, "id", "w_m_state"), "")
                        rv.setTextViewText(WidgetHub.rid(c, "id", "w_m_time"), "")
                        rv.setViewVisibility(barId, View.GONE)
                    } else {
                        val playing = MusicEngine.isPlaying
                        rv.setTextViewText(WidgetHub.rid(c, "id", "w_m_title"), t.title)
                        rv.setTextViewText(WidgetHub.rid(c, "id", "w_m_artist"), t.artist)
                        rv.setTextViewText(WidgetHub.rid(c, "id", "w_m_state"), if (playing) "▮▮ شغالة" else "▶ واقفة")
                        val dur = MusicEngine.durMs
                        val pos = MusicEngine.posMs
                        rv.setViewVisibility(barId, View.VISIBLE)
                        rv.setProgressBar(barId, dur.coerceAtLeast(1), pos.coerceIn(0, dur.coerceAtLeast(1)), false)
                        rv.setTextViewText(WidgetHub.rid(c, "id", "w_m_time"), fmt(pos) + " / " + fmt(dur))
                    }
                    rv.setTextViewText(WidgetHub.rid(c, "id", "w_m_toggle"), if (MusicEngine.isPlaying) "▮▮" else "▶")
                    rv.setOnClickPendingIntent(WidgetHub.rid(c, "id", "w_m_prev"), bc(c, ACT_PREV, 51))
                    rv.setOnClickPendingIntent(WidgetHub.rid(c, "id", "w_m_toggle"), bc(c, ACT_TOGGLE, 52))
                    rv.setOnClickPendingIntent(WidgetHub.rid(c, "id", "w_m_next"), bc(c, ACT_NEXT, 53))
                    val open = PendingIntent.getActivity(c, 54,
                        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    rv.setOnClickPendingIntent(WidgetHub.rid(c, "id", "w_m_body"), open)
                    mgr.updateAppWidget(id, rv)
                } catch (e: Throwable) {
                    LogStore.err("MusicWidget:render", e)
                }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAll(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACT_TOGGLE -> {
                try {
                    MusicEngine.toggle()
                } catch (_: Throwable) {
                }
                val mgr = AppWidgetManager.getInstance(context)
                renderAll(context, mgr, mgr.getAppWidgetIds(ComponentName(context, MusicWidget::class.java)))
            }
            ACT_NEXT -> {
                try {
                    MusicEngine.step(1)
                } catch (_: Throwable) {
                }
                val mgr = AppWidgetManager.getInstance(context)
                renderAll(context, mgr, mgr.getAppWidgetIds(ComponentName(context, MusicWidget::class.java)))
            }
            ACT_PREV -> {
                try {
                    MusicEngine.step(-1)
                } catch (_: Throwable) {
                }
                val mgr = AppWidgetManager.getInstance(context)
                renderAll(context, mgr, mgr.getAppWidgetIds(ComponentName(context, MusicWidget::class.java)))
            }
            else -> super.onReceive(context, intent)
        }
    }
}
