package com.tttt.subtitler.phonehome

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tttt.subtitler.AppWall
import com.tttt.subtitler.LogStore
import com.tttt.subtitler.WallItem
import com.tttt.subtitler.WallSources
import java.io.File
import java.util.concurrent.Executors

/**
 * (v776) متجر الخلفيات: أحدث الخلفيات (Bing اليومية + Wallhaven) في شبكة تحت بعض + بحث فوق (ذئب، ثعلب، فضاء…).
 * دوس على صورة ← معاينة كاملة ← «خلفية افتراضية» (بتبقى خلفية Joyboy الثابتة، وخلفية الثيم لو الثيم الشغّال له خلفية) أو «تحميل» (للمعرض).
 * [prefs] لازم يتبعت من اللانشر (عشان خلفية الثيم)، ولو null (من إعدادات التطبيق) بيتخطّى خلفية الثيم.
 * [onApplied] بتتنادى على الـUI thread بعد ما الخلفية اتثبّتت.
 */
class WallStore(
    private val act: Activity,
    private val prefs: SharedPreferences?,
    private val onApplied: () -> Unit
) {
    private var dlg: Dialog? = null
    private var gridBox: LinearLayout? = null
    private var status: TextView? = null
    private var moreBtn: TextView? = null
    private var input: EditText? = null
    private var lastRow: LinearLayout? = null

    private var query = ""
    private var page = 1
    private var loading = false
    private var token = 0
    private var exhausted = false
    private val seen = HashSet<String>()

    private val pool = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun dp(v: Int) = act.dp(v)
    private val portrait: Boolean get() = act.resources.displayMetrics.let { it.heightPixels >= it.widthPixels }

    private fun round(color: Int, r: Int) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(color) }

    private fun btn(label: String, bg: Int, click: () -> Unit) = TextView(act).apply {
        text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(12), dp(13), dp(12), dp(13)); background = round(bg, 16)
        pressEffect(); setOnClickListener { click() }
    }

    // ───────── الشاشة الرئيسية للمتجر ─────────
    fun open() {
        dlg?.dismiss()
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF101014.toInt()); setPadding(dp(14), dp(38), dp(14), dp(10))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(act).apply {
            text = "🖼 متجر الخلفيات"; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(act).apply {
            text = "✕"; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { d.dismiss() }
        }, LinearLayout.LayoutParams(-2, -2))
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        // البحث
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(act).apply {
            hint = "ابحث: ذئب · ثعلب · فضاء · wolf…"; setHintTextColor(0x88FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 15f
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = round(0xFF22222A.toInt(), 14)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false }
        }
        input = et
        bar.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn("🔍", 0xFF7C4DFF.toInt()) { doSearch() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val st = TextView(act).apply { textSize = 12f; setTextColor(0xAAFFFFFF.toInt()); setPadding(dp(4), dp(8), dp(4), dp(4)) }
        status = st
        col.addView(st, LinearLayout.LayoutParams(-1, -2))

        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        gridBox = box
        val more = btn("⬇ حمّل كمان", 0xFF2A2A33.toInt()) { loadMore() }
        moreBtn = more
        val sv = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val inner = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(box, LinearLayout.LayoutParams(-1, -2))
        inner.addView(more, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(6), dp(4), dp(20)) })
        sv.addView(inner, FrameLayout.LayoutParams(-1, -2))
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        d.setContentView(col)
        d.setOnDismissListener { token++; if (dlg === d) dlg = null }
        d.show()
        dlg = d
        reset("")
    }

    private fun doSearch() {
        val q = input?.text?.toString()?.trim() ?: ""
        try { (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input?.windowToken, 0) } catch (_: Throwable) {}
        reset(q)
    }

    private fun reset(q: String) {
        token++
        query = q; page = 1; exhausted = false; loading = false
        seen.clear(); lastRow = null
        gridBox?.removeAllViews()
        moreBtn?.visibility = View.GONE
        fetchPage()
    }

    private fun loadMore() { if (!loading && !exhausted) { page++; fetchPage() } }

    private fun fetchPage() {
        if (loading) return
        loading = true
        val my = token
        val q = query
        val pg = page
        val por = portrait
        status?.text = if (q.isBlank()) "⏳ بجيب أحدث الخلفيات…" else "⏳ بدوّر على «$q»…"
        moreBtn?.visibility = View.GONE
        Thread {
            val list: List<WallItem> = try {
                if (q.isBlank()) WallSources.latest(pg, por) else WallSources.search(q, pg, por)
            } catch (e: Throwable) { LogStore.err("WallStore:fetch", e); emptyList() }
            act.runOnUiThread {
                if (act.isDestroyed || my != token) return@runOnUiThread
                loading = false
                val fresh = list.filter { seen.add(it.fulls.firstOrNull() ?: it.thumbs.firstOrNull() ?: "") }
                for (it in fresh) addCell(it)
                if (fresh.isEmpty()) {
                    exhausted = true
                    status?.text = if (seen.isEmpty()) "مفيش نتايج — جرّب كلمة تانية أو اتأكد من النت" else "خلصت النتايج"
                    moreBtn?.visibility = View.GONE
                } else {
                    status?.text = if (q.isBlank()) "أحدث الخلفيات — دوس على أي صورة" else "نتايج «$q» — دوس على أي صورة"
                    moreBtn?.visibility = View.VISIBLE
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ───────── الشبكة ─────────
    private fun addCell(item: WallItem) {
        val box = gridBox ?: return
        var row = lastRow
        if (row == null || row.childCount >= 2) {
            row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
            lastRow = row
        }
        val h = if (portrait) dp(250) else dp(150)
        val cell = FrameLayout(act).apply { background = round(0xFF1C1C22.toInt(), 14); clipToOutline = true }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        cell.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val src = TextView(act).apply {
            text = item.source; textSize = 10f; setTextColor(Color.WHITE); setPadding(dp(8), dp(3), dp(8), dp(3))
            background = round(0x99000000.toInt(), 10)
        }
        cell.addView(src, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6), 0, 0, dp(6)) })
        cell.setOnClickListener { preview(item) }
        cell.pressEffect()
        row.addView(cell, LinearLayout.LayoutParams(0, h, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        loadThumb(item, iv)
    }

    private fun loadThumb(item: WallItem, iv: ImageView) {
        val key = item.thumbs.firstOrNull() ?: return
        cache.get(key)?.let { iv.setImageBitmap(it); return }
        val my = token
        pool.execute {
            val bmp = fetchBitmap(item.thumbs, 520, 3_000_000)
            if (bmp != null) cache.put(key, bmp)
            act.runOnUiThread { if (!act.isDestroyed && my == token && bmp != null) iv.setImageBitmap(bmp) }
        }
    }

    private fun fetchBitmap(urls: List<String>, maxSide: Int, maxBytes: Int): Bitmap? {
        for (u in urls) {
            val data = try { WallSources.http(u, maxBytes) } catch (_: Throwable) { null } ?: continue
            val b = decode(data, maxSide)
            if (b != null) return b
        }
        return null
    }

    private fun decode(data: ByteArray, maxSide: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        var ss = 1
        while (o.outWidth > 0 && maxOf(o.outWidth, o.outHeight) / ss > maxSide) ss *= 2
        BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = ss })
    } catch (_: Throwable) { null }

    // ───────── المعاينة الكاملة ─────────
    private fun preview(item: WallItem) {
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(iv, FrameLayout.LayoutParams(-1, -1))
        cache.get(item.thumbs.firstOrNull() ?: "")?.let { iv.setImageBitmap(it) }   // الصورة الصغيرة لحد ما الكبيرة تنزل

        val msg = TextView(act).apply {
            text = "⏳ بنزّل الصورة بجودة كاملة…"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6)); background = round(0xAA000000.toInt(), 12)
        }
        frame.addView(msg, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(48) })

        var full: Bitmap? = null
        val bar = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(18)); setBackgroundColor(0xB0000000.toInt()) }
        val setBtn = btn("✅ خليها الخلفية الافتراضية", 0xFF7C4DFF.toInt()) {
            val b = full
            if (b == null) Toast.makeText(act, "استنى لحد ما الصورة تنزل", Toast.LENGTH_SHORT).show() else { applyDefault(b); d.dismiss() }
        }
        val dlBtn = btn("⬇ تحميل (للمعرض)", 0xFF2A2A33.toInt()) {
            val b = full
            if (b == null) Toast.makeText(act, "استنى لحد ما الصورة تنزل", Toast.LENGTH_SHORT).show() else saveGallery(b)
        }
        val closeBtn = btn("رجوع", 0x00000000) { d.dismiss() }
        bar.addView(setBtn, LinearLayout.LayoutParams(-1, -2))
        bar.addView(dlBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        bar.addView(closeBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        frame.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setBtn.alpha = .5f; dlBtn.alpha = .5f

        var closed = false
        d.setContentView(frame)
        d.setOnDismissListener { closed = true }
        d.show()

        Thread {
            val b = fetchBitmap(item.fulls, 2560, 14_000_000)
            act.runOnUiThread {
                if (closed || act.isDestroyed) return@runOnUiThread
                if (b == null) { msg.text = "مقدرتش أنزّل الصورة دي — جرّب غيرها"; return@runOnUiThread }
                full = b
                iv.setImageBitmap(b)
                msg.visibility = View.GONE
                setBtn.alpha = 1f; dlBtn.alpha = 1f
            }
        }.apply { isDaemon = true }.start()
    }

    // ───────── تثبيت كخلفية افتراضية ─────────
    private fun applyDefault(b: Bitmap) {
        Toast.makeText(act, "بثبّت الخلفية…", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = AppWall.setFixed(act, b)
            try {
                val p = prefs
                if (p != null && HomeThemeStore.cur?.hasWall == true) {
                    if (HomeThemeStore.setWall(act, p, b) != null) ok = true
                }
            } catch (e: Throwable) { LogStore.err("WallStore:themeWall", e) }
            act.runOnUiThread {
                if (act.isDestroyed) return@runOnUiThread
                Toast.makeText(act, if (ok) "✅ الخلفية اتثبّتت" else "مقدرتش أثبّت الخلفية", Toast.LENGTH_LONG).show()
                if (ok) { try { onApplied() } catch (e: Throwable) { LogStore.err("WallStore:applied", e) } }
            }
        }.apply { isDaemon = true }.start()
    }

    // ───────── تحميل للمعرض ─────────
    private fun saveGallery(b: Bitmap) {
        Thread {
            val name = "Joyboy_" + System.currentTimeMillis()
            val ok = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Joyboy")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = act.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                    if (uri == null) false else {
                        val wrote = act.contentResolver.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: false
                        v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0)
                        act.contentResolver.update(uri, v, null, null)
                        wrote
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Joyboy").apply { mkdirs() }
                    val f = File(dir, "$name.jpg")
                    val w = f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    MediaScannerConnection.scanFile(act, arrayOf(f.path), arrayOf("image/jpeg"), null)
                    w
                }
            } catch (e: Throwable) { LogStore.err("WallStore:save", e); false }
            act.runOnUiThread {
                if (!act.isDestroyed) Toast.makeText(act, if (ok) "✅ اتحمّلت في المعرض (Pictures/Joyboy)" else "مقدرتش أحفظ الصورة (صلاحية التخزين؟)", Toast.LENGTH_LONG).show()
            }
        }.apply { isDaemon = true }.start()
    }
}
