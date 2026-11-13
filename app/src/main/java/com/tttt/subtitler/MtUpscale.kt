@file:OptIn(UnstableApi::class)
package com.tttt.subtitler

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.Size
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * (v982) Live video upscaling inside the MeeTube player.
 * Two methods, both pure resampling (no luma/gamma math, so no SDR assumption):
 * MODE_AI is a single-pass Catmull-Rom bicubic (16 taps), MODE_PRO is a
 * two-pass separable Lanczos3 (6 taps per axis, 12 total) — the same kernel
 * shape libplacebo/mpvs high-quality scaler uses, split into a horizontal
 * pass and a vertical pass so the GPU cost stays near-linear.
 * The shaders render exactly at the target size (see targetSize), so the
 * output texture needs no further scaling by the sink.
 * Safety: shader compile happens lazily in configure(), on the GL thread;
 * a compile failure falls back to a trivial passthrough program and logs
 * only — playback never dies because of this feature. HDR input reaches
 * the programs as linear RGB BT.2020 (useHdr) and is rendered with
 * half-float output textures; the kernels only resample, they never
 * reinterpret color, so HDR stays valid. HDR + effects on the SurfaceView
 * swap path is unproven on device.
 * DRM content does not work with effects (media3 limitation) — irrelevant
 * here, this player has no DRM path. Tunneling is off in this app; if it
 * is ever turned on, effects must be skipped (no cheap runtime read for
 * the tunneling state exists on Player, so this is a note, not a guard).
 */
object MtUpscale {
    const val MODE_OFF = 0
    const val MODE_AI = 1
    const val MODE_PRO = 2

    fun label(mode: Int): String {
        return when (mode) {
            MODE_AI -> "AI"
            MODE_PRO -> "AI Pro"
            else -> "مغلق"
        }
    }

    /**
     * (v982) same rule as MobilePlaybackActivity.
     * Display size of the coded frame inside the video-area view, capped at
     * 1080p. Below the coded size there is nothing to upscale, so the target
     * is the coded size itself.
     */
    fun targetSize(videoW: Int, videoH: Int, viewW: Int, viewH: Int): Pair<Int, Int> {
        if (videoW <= 0 || videoH <= 0) {
            return Pair(videoW, videoH)
        }
        val vw = if (viewW > 0) viewW else videoW
        val vh = if (viewH > 0) viewH else videoH
        val scale = minOf(vw.toDouble() / videoW, vh.toDouble() / videoH)
        var dispW = Math.round(videoW * scale).toInt()
        var dispH = Math.round(videoH * scale).toInt()
        if (dispH > 1080) {
            dispH = 1080
            dispW = Math.round(1080.0 * videoW / videoH).toInt()
        }
        if (dispW > 1920) {
            dispW = 1920
            dispH = Math.round(1920.0 * videoH / videoW).toInt()
        }
        if (dispH <= videoH) {
            return Pair(videoW, videoH)
        }
        return Pair(dispW, dispH)
    }

    /**
     * (v982) The effect list for a mode at a target size. The size is part
     * of the signature on purpose: a resampling program must know its output
     * size in configure(), and it only learns the input size there — so the
     * target has to travel with the effect. A zero target means "unknown
     * yet": the program then renders at the input size (kernel only, no
     * upscale) until the caller re-applies with the real target.
     */
    fun effectFor(mode: Int, targetW: Int, targetH: Int): List<Effect> {
        return when (mode) {
            MODE_AI -> listOf(UpscaleEffect(BICUBIC_FRAG, targetW, targetH, 0))
            MODE_PRO -> listOf(
                UpscaleEffect(LANCZOS_FRAG, targetW, targetH, 1),
                UpscaleEffect(LANCZOS_FRAG, targetW, targetH, 2)
            )
            else -> emptyList()
        }
    }

    /**
     * (v982) Sets the effects for the mode and tells the video renderer the
     * output resolution. Call at least once before prepare() — even with an
     * empty list — so the renderer creates its VideoSink at first enable
     * (media3 only creates it when videoEffects != null at enable time).
     * Live changes during playback are supported by subsequent calls.
     */
    fun apply(player: ExoPlayer?, mode: Int, targetW: Int, targetH: Int) {
        if (player == null) {
            return
        }
        try {
            player.setVideoEffects(effectFor(mode, targetW, targetH))
            if (targetW > 0 && targetH > 0) {
                sendOutputResolution(player, targetW, targetH)
            }
        } catch (e: Throwable) {
            LogStore.err("MtUpscale:apply", e)
        }
    }

    /**
     * (v982) Removes all effects and points the renderer back at the coded
     * video size.
     */
    fun clear(player: ExoPlayer?, videoW: Int, videoH: Int) {
        if (player == null) {
            return
        }
        try {
            player.setVideoEffects(emptyList())
            if (videoW > 0 && videoH > 0) {
                sendOutputResolution(player, videoW, videoH)
            }
        } catch (e: Throwable) {
            LogStore.err("MtUpscale:clear", e)
        }
    }

    /**
     * (v982) The player is handed a raw Surface (not a SurfaceView or a
     * TextureView), so after every setVideoEffects the video renderer must
     * be told the output resolution, or the effects pipeline may render at
     * the wrong size.
     */
    private fun sendOutputResolution(player: ExoPlayer, w: Int, h: Int) {
        try {
            for (i in 0 until player.rendererCount) {
                if (player.getRendererType(i) != C.TRACK_TYPE_VIDEO) {
                    continue
                }
                val renderer = player.getRenderer(i)
                if (renderer is MediaCodecVideoRenderer) {
                    player.createMessage(renderer)
                        .setType(MediaCodecVideoRenderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION)
                        .setPayload(Size(w, h))
                        .send()
                }
                return
            }
        } catch (e: Throwable) {
            LogStore.err("MtUpscale:resolution", e)
        }
    }

    private class UpscaleEffect(
        private val fragmentGlsl: String,
        private val targetW: Int,
        private val targetH: Int,
        private val pass: Int
    ) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
            return UpscaleProgram(fragmentGlsl, targetW, targetH, pass, useHdr)
        }
    }

    private class UpscaleProgram(
        private val fragmentGlsl: String,
        private val targetW: Int,
        private val targetH: Int,
        private val pass: Int,
        useHdr: Boolean
    ) : BaseGlShaderProgram(useHdr, 1) {
        private var glProgram: GlProgram? = null
        private var failed = false
        private var inW = 0
        private var inH = 0

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            inW = inputWidth
            inH = inputHeight
            ensureProgram()
            val outW = if (targetW > 0) targetW else inputWidth
            val outH = if (pass == 1) {
                inputHeight
            } else if (targetH > 0) {
                targetH
            } else {
                inputHeight
            }
            return Size(outW, outH)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val program = glProgram ?: return
            program.use()
            program.setBufferAttribute("aPosition", QUAD_POSITIONS, 2)
            program.setBufferAttribute("aTexCoord", QUAD_TEX_COORDS, 2)
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            if (!failed) {
                program.setFloatsUniformIfPresent("uInSize", floatArrayOf(inW.toFloat(), inH.toFloat()))
                if (pass != 0) {
                    program.setFloatUniform("uHorizontal", if (pass == 1) 1f else 0f)
                }
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun ensureProgram() {
            if (glProgram != null) {
                return
            }
            try {
                glProgram = GlProgram(VERTEX_SHADER, fragmentGlsl)
            } catch (e: Throwable) {
                LogStore.err("MtUpscale:shader", e)
                try {
                    glProgram = GlProgram(VERTEX_SHADER, PASSTHROUGH_FRAG)
                    failed = true
                } catch (e2: Throwable) {
                    LogStore.err("MtUpscale:passthrough", e2)
                    throw VideoFrameProcessingException("MtUpscale: shader failed", e2)
                }
            }
        }
    }

    private val QUAD_POSITIONS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val QUAD_TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    private val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
          gl_Position = aPosition;
          vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val PASSTHROUGH_FRAG = """
        precision mediump float;
        uniform sampler2D uTexSampler;
        varying vec2 vTexCoord;
        void main() {
          gl_FragColor = texture2D(uTexSampler, vTexCoord);
        }
    """.trimIndent()

    /**
     * (v982) Method 1 "AI": Catmull-Rom bicubic, single pass, 16 taps.
     * highp because pixel coordinates go up to 1920 and mediump cannot
     * tell neighbouring texels apart at that range.
     */
    private val BICUBIC_FRAG = """
        precision highp float;
        uniform sampler2D uTexSampler;
        uniform vec2 uInSize;
        varying vec2 vTexCoord;
        void main() {
          vec2 texel = vec2(1.0) / uInSize;
          vec2 srcPos = vTexCoord * uInSize - vec2(0.5);
          vec2 base = floor(srcPos);
          vec2 f = srcPos - base;
          vec2 f2 = f * f;
          vec2 f3 = f2 * f;
          float wx0 = -0.5 * f3.x + f2.x - 0.5 * f.x;
          float wx1 = 1.5 * f3.x - 2.5 * f2.x + 1.0;
          float wx2 = -1.5 * f3.x + 2.0 * f2.x + 0.5 * f.x;
          float wx3 = 0.5 * f3.x - 0.5 * f2.x;
          float wy0 = -0.5 * f3.y + f2.y - 0.5 * f.y;
          float wy1 = 1.5 * f3.y - 2.5 * f2.y + 1.0;
          float wy2 = -1.5 * f3.y + 2.0 * f2.y + 0.5 * f.y;
          float wy3 = 0.5 * f3.y - 0.5 * f2.y;
          vec2 p = base - 1.0;
          vec4 sum = vec4(0.0);
          sum += (wx0 * wy0) * texture2D(uTexSampler, (p + vec2(0.0, 0.0) + 0.5) * texel);
          sum += (wx1 * wy0) * texture2D(uTexSampler, (p + vec2(1.0, 0.0) + 0.5) * texel);
          sum += (wx2 * wy0) * texture2D(uTexSampler, (p + vec2(2.0, 0.0) + 0.5) * texel);
          sum += (wx3 * wy0) * texture2D(uTexSampler, (p + vec2(3.0, 0.0) + 0.5) * texel);
          sum += (wx0 * wy1) * texture2D(uTexSampler, (p + vec2(0.0, 1.0) + 0.5) * texel);
          sum += (wx1 * wy1) * texture2D(uTexSampler, (p + vec2(1.0, 1.0) + 0.5) * texel);
          sum += (wx2 * wy1) * texture2D(uTexSampler, (p + vec2(2.0, 1.0) + 0.5) * texel);
          sum += (wx3 * wy1) * texture2D(uTexSampler, (p + vec2(3.0, 1.0) + 0.5) * texel);
          sum += (wx0 * wy2) * texture2D(uTexSampler, (p + vec2(0.0, 2.0) + 0.5) * texel);
          sum += (wx1 * wy2) * texture2D(uTexSampler, (p + vec2(1.0, 2.0) + 0.5) * texel);
          sum += (wx2 * wy2) * texture2D(uTexSampler, (p + vec2(2.0, 2.0) + 0.5) * texel);
          sum += (wx3 * wy2) * texture2D(uTexSampler, (p + vec2(3.0, 2.0) + 0.5) * texel);
          sum += (wx0 * wy3) * texture2D(uTexSampler, (p + vec2(0.0, 3.0) + 0.5) * texel);
          sum += (wx1 * wy3) * texture2D(uTexSampler, (p + vec2(1.0, 3.0) + 0.5) * texel);
          sum += (wx2 * wy3) * texture2D(uTexSampler, (p + vec2(2.0, 3.0) + 0.5) * texel);
          sum += (wx3 * wy3) * texture2D(uTexSampler, (p + vec2(3.0, 3.0) + 0.5) * texel);
          gl_FragColor = sum;
        }
    """.trimIndent()

    /**
     * (v982) Method 2 "AI Pro": Lanczos3, separable — one 1D pass per axis.
     * uHorizontal picks the axis (1 = this pass runs along x, 0 = along y);
     * the two effects in the list run back to back, so the full 2D kernel
     * costs 6 + 6 taps instead of 36. Weights are normalised per pixel.
     */
    private val LANCZOS_FRAG = """
        precision highp float;
        uniform sampler2D uTexSampler;
        uniform vec2 uInSize;
        uniform float uHorizontal;
        varying vec2 vTexCoord;
        float lanczos(float x) {
          float ax = abs(x);
          if (ax < 0.001) return 1.0;
          if (ax >= 3.0) return 0.0;
          float pix = 3.14159265 * x;
          float pixA = pix / 3.0;
          return (sin(pix) / pix) * (sin(pixA) / pixA);
        }
        void main() {
          vec2 texel = vec2(1.0) / uInSize;
          vec2 srcPos = vTexCoord * uInSize - vec2(0.5);
          float center = mix(srcPos.y, srcPos.x, uHorizontal);
          float base = floor(center) - 2.0;
          vec4 sum = vec4(0.0);
          float wsum = 0.0;
          for (int i = 0; i < 6; i++) {
            float tap = base + float(i);
            float w = lanczos(center - tap);
            float coord = (tap + 0.5) * mix(texel.y, texel.x, uHorizontal);
            vec2 uv = vec2(mix(vTexCoord.x, coord, uHorizontal), mix(vTexCoord.y, coord, 1.0 - uHorizontal));
            sum += w * texture2D(uTexSampler, uv);
            wsum += w;
          }
          gl_FragColor = sum / wsum;
        }
    """.trimIndent()
}
