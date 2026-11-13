package com.tttt.joyboy.desk

import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.*

fun main() {
    try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Throwable) {}
    try { Link.start() } catch (e: Throwable) {
        JOptionPane.showMessageDialog(null, "مقدرش يفتح البورت ${Link.PORT} — يمكن نسخة تانية شغّالة؟\n${e.message}", "Joyboy Desk", JOptionPane.ERROR_MESSAGE); return
    }
    Hotspot.startAuto()
    SwingUtilities.invokeLater { MainWin().show() }
}

class MainWin {
    private val f = JFrame("Joyboy Desk")
    private val status = JLabel(" ")
    private val remotesModel = DefaultListModel<String>()
    private val tasksPanel = JPanel().also { it.layout = BoxLayout(it, BoxLayout.Y_AXIS) }
    private val ipLbl = JLabel()
    private val codeLbl = JLabel("—")

    fun show() {
        f.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        f.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
        val tabs = JTabbedPane()
        tabs.addTab("🔗 الاتصال", connectTab())
        tabs.addTab("📁 نقل الملفات", transferTab())
        tabs.addTab("⬇ تحميل لينك", downloadTab())
        tabs.addTab("▶ تشغيل", playTab())
        tabs.addTab("📶 هوت سبوت", hotspotTab())
        f.add(tabs, BorderLayout.CENTER)
        status.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        f.add(status, BorderLayout.SOUTH)
        f.size = Dimension(640, 520); f.setLocationRelativeTo(null)
        Link.onNotice = { m -> SwingUtilities.invokeLater { status.text = m } }
        Link.onRemotesChanged = { SwingUtilities.invokeLater { refreshRemotes() } }
        Tasks.onChange = { SwingUtilities.invokeLater { refreshTasks() } }
        refreshRemotes(); refreshTasks()
        f.isVisible = true
        if (!Player.vlcProbe()) status.text = "⚠ VLC مش متسطّب — الفيديو هيتفتح بالمشغّل الافتراضي. سطّب VLC (64-bit) من videolan.org"
    }

    private fun connectTab(): JComponent {
        val p = JPanel(); p.layout = BoxLayout(p, BoxLayout.Y_AXIS); p.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        val nameF = JTextField(Store.name, 18)
        val save = JButton("حفظ الاسم"); save.addActionListener { Store.name = nameF.text.trim().ifBlank { Store.name }; status.text = "اتحفظ الاسم" }
        val row = JPanel(FlowLayout(FlowLayout.RIGHT)); row.add(JLabel("اسم الجهاز:")); row.add(nameF); row.add(save)
        p.add(row)
        ipLbl.text = "<html>العنوان: " + (Store.localIps().joinToString("  |  ") { "$it:${Link.PORT}" }.ifBlank { "مفيش شبكة — وصّل الواي فاي أو شغّل هوت سبوت من الموبايل" }) + "</html>"
        p.add(ipLbl)
        p.add(Box.createVerticalStrut(8))
        p.add(JLabel("<html>على الموبايل: افتح <b>ريموت</b> في Joyboy ← هتلاقي اللابتوب في القايمة (زي التلفزيون). أول موبايل بيتقرن لوحده.</html>"))
        p.add(Box.createVerticalStrut(8))
        val codeRow = JPanel(FlowLayout(FlowLayout.RIGHT))
        val gen = JButton("كود إقران لموبايل تاني"); gen.addActionListener { codeLbl.text = Link.newCode() }
        codeLbl.font = codeLbl.font.deriveFont(Font.BOLD, 22f)
        codeRow.add(gen); codeRow.add(codeLbl); p.add(codeRow)
        p.add(Box.createVerticalStrut(8))
        p.add(JLabel("الموبايلات المقترنة:"))
        val l = JList(remotesModel); p.add(JScrollPane(l))
        val forget = JButton("نسيان المحدد"); forget.addActionListener {
            val i = l.selectedIndex; if (i >= 0) { val r = Store.remotes.values.sortedBy { it.name }[i]; Store.remotes.remove(r.id); Store.saveRemotes(); refreshRemotes() }
        }
        p.add(forget)
        return p
    }

    private fun refreshRemotes() {
        remotesModel.clear()
        for (r in Store.remotes.values.sortedBy { it.name }) remotesModel.addElement((if (r.alive()) "🟢 " else "⚪ ") + r.name + (if (r.ip.isNotEmpty()) "  (${r.ip})" else ""))
    }

    private fun transferTab(): JComponent {
        val p = JPanel(BorderLayout(6, 6)); p.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        val top = JPanel(); top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.add(JLabel("<html><b>استقبال:</b> أي ملف تبعته من الموبايل بيتحمّل هنا تلقائي في <code>" + Store.inbox.absolutePath + "</code><br>" +
            "<b>إرسال للموبايل:</b> الموبايل بيتصفّح ملفات اللابتوب من «مركز النقل» ويحمّل اللي عايزه (فيديوهات · صوتيات · صور). الفولدرات المشاركة: Joyboy · Videos · Downloads + اللي تضيفه.</html>"))
        val b = JPanel(FlowLayout(FlowLayout.RIGHT))
        val add = JButton("➕ شارك فولدر"); add.addActionListener {
            val c = JFileChooser(); c.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            if (c.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) { Store.addShared(c.selectedFile); status.text = "اتشارك: ${c.selectedFile.name}" }
        }
        val open = JButton("📂 فتح فولدر الوارد"); open.addActionListener { try { Desktop.getDesktop().open(Store.inbox) } catch (_: Throwable) {} }
        val send = JButton("📤 ابعت للموبايل"); send.addActionListener {
            val c = JFileChooser(); c.isMultiSelectionEnabled = true
            if (c.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) sendFiles(c.selectedFiles.toList())
        }
        b.add(send); b.add(add); b.add(open); top.add(b)
        val dz = JLabel("<html><center>📤 اسحب ملفات وسيبها هنا — تتبعت للموبايل المتصل</center></html>", SwingConstants.CENTER)
        dz.border = BorderFactory.createDashedBorder(Color.GRAY); dz.preferredSize = Dimension(200, 46); dz.maximumSize = Dimension(Int.MAX_VALUE, 46)
        dz.dropTarget = DropTarget(dz, DnDConstants.ACTION_COPY, object : java.awt.dnd.DropTargetAdapter() {
            override fun drop(e: DropTargetDropEvent) {
                try { e.acceptDrop(DnDConstants.ACTION_COPY)
                    @Suppress("UNCHECKED_CAST") sendFiles(e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>) } catch (_: Throwable) {}
            }
        })
        top.add(dz)
        p.add(top, BorderLayout.NORTH)
        p.add(JScrollPane(tasksPanel), BorderLayout.CENTER)
        return p
    }

    private fun sendFiles(fs: List<File>) {
        if (Store.remotes.values.none { it.alive() }) { status.text = "⚠ مفيش موبايل متصل دلوقتي"; return }
        var n = 0; for (x in fs) if (x.isFile) { Push.send(x); n++ }
        if (n > 0) status.text = "📤 بعتّ $n ملف — الموبايل هيبدأ يحمّلهم خلال ثواني"
    }

    private fun hotspotTab(): JComponent {
        val p = JPanel(); p.layout = BoxLayout(p, BoxLayout.Y_AXIS); p.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        val ss = JTextField(Hotspot.ssid, 20); val pw = JPasswordField(Hotspot.pass, 20)
        val auto = JCheckBox("انضم تلقائي لما الهوت سبوت يظهر ومفيش موبايل متصل", Hotspot.auto)
        val st = JLabel(" "); Hotspot.onStatus = { m -> SwingUtilities.invokeLater { st.text = m } }
        fun r(l: String, c: JComponent) = JPanel(FlowLayout(FlowLayout.RIGHT)).also { it.add(JLabel(l)); it.add(c) }
        p.add(JLabel("<html>لو الموبايل واللابتوب مش على نفس الواي فاي: شغّل الهوت سبوت على الموبايل (ضغطة طويلة على 📶 في الريموت بتفتح الإعدادات) واكتب بياناته هنا مرة واحدة.</html>"))
        p.add(r("اسم الهوت سبوت:", ss)); p.add(r("الباسورد:", pw)); p.add(auto)
        val paste = JButton("📋 لصق بيانات منسوخة من الموبايل"); paste.addActionListener {
            try { val t = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
                val x = Hotspot.parseWifiString(t); if (x != null) { ss.text = x.first; pw.text = x.second; st.text = "اتلصقت — دوس «حفظ وانضم»" } else st.text = "الكليب بورد مفيهوش بيانات واي فاي" } catch (_: Throwable) { st.text = "الكليب بورد فاضي" }
        }
        val go = JButton("💾 حفظ وانضم"); go.addActionListener {
            Hotspot.save(ss.text, String(pw.password), auto.isSelected)
            Thread { Hotspot.join() }.also { it.isDaemon = true }.start()
        }
        val br = JPanel(FlowLayout(FlowLayout.RIGHT)); br.add(paste); br.add(go); p.add(br); p.add(st)
        return p
    }

    private fun refreshTasks() {
        tasksPanel.removeAll()
        for (t in Tasks.list.take(40)) {
            val row = JPanel(BorderLayout(6, 0)); row.maximumSize = Dimension(Int.MAX_VALUE, 54); row.border = BorderFactory.createEmptyBorder(3, 4, 3, 4)
            val pb = JProgressBar(0, 100); pb.value = t.pct; pb.isStringPainted = true; pb.string = t.msg
            row.add(JLabel(t.name), BorderLayout.NORTH); row.add(pb, BorderLayout.CENTER)
            val btn = JButton(if (t.done) "▶" else "✖")
            btn.addActionListener { if (t.done) t.file?.let { Player.play(it.toURI().toString(), it.name, 0, "", "", "") } else t.cancelled = true }
            if (!t.failed) row.add(btn, BorderLayout.EAST)
            tasksPanel.add(row)
        }
        tasksPanel.revalidate(); tasksPanel.repaint()
    }

    private fun downloadTab(): JComponent {
        val p = JPanel(BorderLayout(6, 6)); p.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        val u = JTextField()
        val go = JButton("⬇ حمّل")
        go.addActionListener { val s = u.text.trim(); if (s.isNotEmpty()) { Xfer.downloadUrl(s); u.text = ""; status.text = "بدأ التحميل — شوف تاب النقل" } }
        u.addActionListener { go.doClick() }
        val row = JPanel(BorderLayout(6, 0)); row.add(JLabel("اللينك:"), BorderLayout.WEST); row.add(u, BorderLayout.CENTER); row.add(go, BorderLayout.EAST)
        p.add(row, BorderLayout.NORTH)
        p.add(JLabel("<html>لينك مباشر لفيديو/ملف: بيتحمّل بالاستكمال. صفحة فيديو (يوتيوب وغيره): محتاج <code>yt-dlp.exe</code> جنب البرنامج أو في " + Store.home.absolutePath + "</html>"), BorderLayout.CENTER)
        return p
    }

    private fun playTab(): JComponent {
        val p = JPanel(BorderLayout(6, 6)); p.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        val pick = JButton("📂 اختار فيديو من اللابتوب"); pick.addActionListener {
            val c = JFileChooser(); if (c.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) Player.play(c.selectedFile.toURI().toString(), c.selectedFile.name, 0, "", "", "")
        }
        val drop = JLabel("<html><center>أو اسحب فيديو وسيبه هنا</center></html>", SwingConstants.CENTER); drop.border = BorderFactory.createDashedBorder(Color.GRAY)
        drop.dropTarget = DropTarget(drop, DnDConstants.ACTION_COPY, object : java.awt.dnd.DropTargetAdapter() {
            override fun drop(e: DropTargetDropEvent) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY)
                    @Suppress("UNCHECKED_CAST") val fs = e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    fs.firstOrNull()?.let { Player.play(it.toURI().toString(), it.name, 0, "", "", "") }
                } catch (_: Throwable) {}
            }
        })
        p.add(pick, BorderLayout.NORTH); p.add(drop, BorderLayout.CENTER)
        p.add(JLabel("<html>فيديو الموبايل بيتشغّل هنا مباشرة من الموبايل (بث) لما تدوس «شغّل على الجهاز» من الريموت. الترجمة بتتعمل على الموبايل وتتعرض هنا.</html>"), BorderLayout.SOUTH)
        return p
    }
}
