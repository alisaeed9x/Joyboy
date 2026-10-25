package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/**
 * (v545 م6) «🧰 مهام التلفزيون»: بتعرض تقدّم مهام التلفزيون (من /sync) وبتتحكم فيها (إيقاف/استكمال/إلغاء/إعادة).
 * القراءة كلها من النسخة المحلية (LinkSession.mirror)؛ الأوامر async.
 */
object TvTasksUi {
    fun stateLabel(t: TaskRow): String = when (t.state) {
        0 -> "⏳ مستني"
        1 -> if (t.paused) "⏸ واقف" else "▶ شغّال " + t.pct + "%"
        2 -> "✅ خلص"
        3 -> "❌ فشل"
        4 -> "✕ اتلغى"
        5 -> "⏹ اتوقف"
        6 -> "⏸ واقف مؤقتًا"
        else -> ""
    }

    fun show(act: Activity) {
        try { LinkSession.start(act) } catch (_: Throwable) {}
        val th = Themes.byId(Cfg.str("theme", "mx"))
        val ui = Ui(act, th)
        val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(28), ui.dp(10), ui.dp(6)) }
        val title = ui.text("🧰 مهام التلفزيون", 18f, th.text, true)
        val status = ui.text("", 14f, th.muted).apply { setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6)) }
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(12)) }
        val scroll = ScrollView(act).apply { addView(body) }

        fun cmd(label: String, op: String, id: Int, extra: JSONObject? = null): View =
            ui.button(label) {
                val a = JSONObject().put("id", id)
                if (extra != null) { val ks = extra.keys(); while (ks.hasNext()) { val k = ks.next(); a.put(k, extra.get(k)) } }
                LinkSession.send(op, a)
            }

        fun render() {
            body.removeAllViews()
            val rows = LinkSession.mirror.tasks
            status.text = when {
                !LinkSession.connected -> "⚠ التلفزيون مش متصل"
                !LinkSession.mirror.owner -> "👁 قراءة فقط — متحكّم تاني ماسك التحكم"
                rows.isEmpty() -> "مفيش مهام على التلفزيون"
                else -> ""
            }
            if (LinkSession.connected && !LinkSession.mirror.owner)
                body.addView(ui.button("🎮 استلم التحكم") { LinkSession.takeControl() })
            for (t in rows) {
                val card = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(8), 0, ui.dp(8)) }
                card.addView(ui.text(t.title, 15f, th.text, true).apply { maxLines = 2 })
                val line = stateLabel(t) + (if (t.msg.isNotBlank()) " · " + t.msg else "")
                card.addView(ui.text(line, 12f, th.muted))
                val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                val lp = { LinearLayout.LayoutParams(0, -2, 1f) }
                when (t.state) {
                    0 -> { btns.addView(cmd("⏹", "task.stop", t.id), lp()); btns.addView(cmd("✕", "task.cancel", t.id), lp()) }
                    1 -> {
                        if (t.paused) btns.addView(cmd("▶", "task.resume", t.id), lp()) else btns.addView(cmd("⏸", "task.pause", t.id), lp())
                        btns.addView(cmd("⏹", "task.stop", t.id), lp()); btns.addView(cmd("✕", "task.cancel", t.id), lp())
                    }
                    6 -> { btns.addView(cmd("▶", "task.resume", t.id), lp()); btns.addView(cmd("✕", "task.remove", t.id), lp()) }
                    2 -> btns.addView(cmd("✕", "task.remove", t.id), lp())
                    else -> { btns.addView(cmd("🔁", "task.retry", t.id), lp()); btns.addView(cmd("✕", "task.remove", t.id), lp()) }
                }
                card.addView(btns)
                body.addView(card)
            }
            if (rows.any { it.state >= 2 }) body.addView(ui.button("🧹 امسح الخالصة") { LinkSession.send("task.clear") })
        }

        val lis: () -> Unit = { if (!act.isFinishing && !act.isDestroyed) render() }
        LinkSession.addListener(lis)
        dlg.setOnDismissListener { LinkSession.removeListener(lis) }

        head.addView(ui.button("✕") { dlg.dismiss() })
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
        root.addView(head)
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dlg.setContentView(root)
        render()
        dlg.show()
    }
}
