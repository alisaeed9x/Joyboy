// Stubs لفحص شكل الاستدعاءات فقط — الواجهات دي رسمية في media3 (onCues(CueGroup) / Cue.text / Cue.bitmap...). مش مشحونة في الـAPK.
package androidx.media3.common.text
import android.graphics.Bitmap

class Cue {
    val text: CharSequence? = null; val bitmap: Bitmap? = null
    val position: Float = 0f; val positionAnchor: Int = 0; val line: Float = 0f; val lineAnchor: Int = 0; val size: Float = 0f; val bitmapHeight: Float = 0f
}
class CueGroup { val cues: List<Cue> = emptyList() }
