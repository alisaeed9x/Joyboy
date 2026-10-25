package me.prince.launcher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

private val INPUT_GLOW = Color(0xFF4A5568)

// HDMI inputs with something plugged in, as tiles. CEC child inputs carry the device name ("Apple TV").
fun loadInputs(context: Context): List<TvApp> {
    val tvInputs = context.getSystemService(TvInputManager::class.java)
    val all = tvInputs.tvInputList
    val icon = ContextCompat.getDrawable(context, R.drawable.ic_input)!!.toBitmap(128, 128).asImageBitmap()
    return all
        .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
        .mapNotNull { port ->
            val device = all.firstOrNull { it.parentId == port.id }
            val connected = device != null || tvInputs.getInputState(port.id) == TvInputManager.INPUT_STATE_CONNECTED
            if (!connected) return@mapNotNull null
            val portLabel = port.loadCustomLabel(context)?.toString() ?: port.loadLabel(context).toString()
            TvApp(
                packageName = port.id,
                label = device?.loadLabel(context)?.toString() ?: portLabel,
                image = icon,
                hasBanner = false,
                glow = INPUT_GLOW,
                launchIntent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(port.id)),
                isInput = true,
                detail = portLabel.takeIf { device != null },
            )
        }
        .sortedBy { it.label }
}
