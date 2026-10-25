package me.prince.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Vid(val uri: Uri, val name: String, val folder: String, val usb: Boolean, val dur: Long, val size: Long, val added: Long)

/** Local video library: folders (internal + USB, from MediaStore) → videos with resume progress → PlayerActivity. */
class VideosActivity : ComponentActivity() {
    private var videos by mutableStateOf<List<Vid>?>(null)
    private var denied by mutableStateOf(false)

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) refresh() else denied = true
    }

    private fun needed() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { VideosScreen() } }
        if (checkSelfPermission(needed()) == PackageManager.PERMISSION_GRANTED) refresh() else perm.launch(needed())
    }

    override fun onResume() { super.onResume(); if (checkSelfPermission(needed()) == PackageManager.PERMISSION_GRANTED && videos != null) refresh() }

    private fun refresh() {
        Thread { val l = query(this); runOnUiThread { videos = l } }.also { it.isDaemon = true }.start()
    }

    private fun query(c: Context): List<Vid> {
        val out = ArrayList<Vid>()
        val base = MediaStore.Video.Media.getContentUri(if (Build.VERSION.SDK_INT >= 29) MediaStore.VOLUME_EXTERNAL else "external")
        val cols = ArrayList(listOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.BUCKET_DISPLAY_NAME))
        if (Build.VERSION.SDK_INT >= 29) cols.add(MediaStore.MediaColumns.VOLUME_NAME)
        try {
            c.contentResolver.query(base, cols.toTypedArray(), null, null, MediaStore.Video.Media.DISPLAY_NAME + " COLLATE NOCASE ASC")?.use { q ->
                while (q.moveToNext()) {
                    val id = q.getLong(0)
                    val vol = if (Build.VERSION.SDK_INT >= 29) q.getString(6) ?: "" else ""
                    out.add(Vid(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        (q.getString(1) ?: "").substringBeforeLast('.'), q.getString(5) ?: "—", vol.isNotEmpty() && vol != "external_primary",
                        q.getLong(2), q.getLong(3), q.getLong(4)))
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    @Composable
    private fun VideosScreen() {
        val all = videos
        var folder by remember { mutableStateOf<String?>(null) }
        BackHandler(enabled = folder != null) { folder = null }
        val ctx = LocalContext.current
        Box(Modifier.fillMaxSize().background(ScreenBg)) {
            when {
                denied -> Text("لازم تسمح للانشر بقراءة الفيديوهات (إعدادات التطبيق ← الأذونات)", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(48.dp))
                all == null -> Text("⏳", color = Color.White, fontSize = 28.sp, modifier = Modifier.padding(48.dp))
                all.isEmpty() -> Text("مفيش فيديوهات اتلاقت", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(48.dp))
                folder == null -> Folders(all) { folder = it }
                else -> FolderVideos(all.filter { key(it) == folder }, folder!!.substringAfter('|'))
            }
        }
    }

    private fun key(v: Vid) = (if (v.usb) "USB" else "الجهاز") + "|" + v.folder

    private fun resumePos(v: Vid): Long = getSharedPreferences("resume", Context.MODE_PRIVATE).getLong(v.uri.toString(), 0L)

    private fun play(list: List<Vid>, v: Vid) {
        startActivity(Intent(this, PlayerActivity::class.java).setAction(Intent.ACTION_VIEW).setData(v.uri)
            .putStringArrayListExtra("list", ArrayList(list.map { it.uri.toString() }))
            .putStringArrayListExtra("names", ArrayList(list.map { it.name })))
    }

    @Composable
    private fun Folders(all: List<Vid>, open: (String) -> Unit) {
        val groups = all.groupBy { key(it) }.toList().sortedWith(compareBy({ !it.first.startsWith("USB") }, { it.first.lowercase() }))
        val cont = all.filter { resumePos(it) > 0 }.sortedByDescending { resumePos(it) }.take(8)
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(48.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("الفيديوهات", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light) }
            if (cont.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("كمّل من حيث وقفت", color = Dim, fontSize = 18.sp) }
                items(cont) { v ->
                    TvCard(v.name, fmtDur(v.dur), badge = fmtDur(v.dur), progress = if (v.dur > 0) resumePos(v).toFloat() / v.dur else 0f, onClick = { play(cont, v) }) { Thumb(v) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Text("الفولدرات", color = Dim, fontSize = 18.sp) }
            }
            items(groups) { (k, l) ->
                TvCard(k.substringAfter('|'), k.substringBefore('|') + " · " + l.size + " فيديو", onClick = { open(k) }) { Thumb(l.first()) }
            }
        }
    }

    @Composable
    private fun FolderVideos(list: List<Vid>, name: String) {
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(48.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text(name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light) }
            items(list) { v ->
                val p = resumePos(v)
                TvCard(v.name, (v.size / 1048576L).toString() + " MB", badge = fmtDur(v.dur), progress = if (v.dur > 0 && p > 0) p.toFloat() / v.dur else 0f, onClick = { play(list, v) }) { Thumb(v) }
            }
        }
    }

    @Composable
    private fun Thumb(v: Vid) {
        val key = "vid:" + v.uri
        val bmp by produceState<ImageBitmap?>(Img.get(key), key) {
            if (value != null || Build.VERSION.SDK_INT < 29) return@produceState
            value = withContext(Dispatchers.IO) {
                try { contentResolver.loadThumbnail(v.uri, Size(480, 270), null).also { Img.put(key, it) }.asImageBitmap() } catch (_: Throwable) { null }
            }
        }
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
