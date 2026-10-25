package me.prince.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

// Shared bits for the Videos and YouTube screens (same look as the home screen: black, white focus)
val ScreenBg = Color.Black
val CardBg = Color(0xFF1C1C1E)
val Dim = Color(0xFFAAAAAA)

object Img {
    private val cache = object : LruCache<String, ImageBitmap>(80) {}
    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = 2 }
                val c = URL(url).openConnection().apply { connectTimeout = 8000; readTimeout = 12000 }
                c.getInputStream().use { BitmapFactory.decodeStream(it, null, o) }?.asImageBitmap()?.also { cache.put(url, it) }
            } catch (_: Throwable) { null }
        }
    }
    fun put(key: String, b: Bitmap) { cache.put(key, b.asImageBitmap()) }
    fun get(key: String): ImageBitmap? = cache.get(key)
}

@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val bmp by produceState<ImageBitmap?>(Img.get(url), url) { value = Img.load(url) ?: value }
    Box(modifier.background(Color(0xFF2A2A2C))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = scale) }
    }
}

/** Card that lifts and turns white when focused. [thumb] fills a 16:9 area; title/sub under it. */
@Composable
fun TvCard(
    title: String, sub: String, modifier: Modifier = Modifier,
    badge: String? = null, progress: Float = 0f,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
    thumb: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CardBg, contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(Color(0xFF2A2A2C))) {
                thumb()
                if (badge != null) Text(
                    badge, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Color(0xFFE53935)))
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = if (focused) Color(0xFF444444) else Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun TvPill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFF3A3A3C) else Color(0xFF1C1C1E), contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
}

fun fmtDur(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%d:%02d", m, ss)
}
