package com.newtube.mobile.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * MEETUBE(glass): the page a {@link GlassView} frosts. On API 31+ its children are recorded into a
 * RenderNode that the glass re-draws through its effect chain; because the glass holds a reference
 * to this node (not a copy), whatever scrolls here shows up under the glass on the RenderThread
 * with no extra invalidation. A GlassView must not sit inside the source it samples.
 */
public class GlassSource extends FrameLayout {
    static final boolean HARDWARE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;

    @Nullable
    final RenderNode node = HARDWARE ? new RenderNode("MeeTube glass source") : null;
    /**
     * Off, the children draw straight to the window and every glass on this source shows its tint
     * alone. For a SurfaceView child (the player's HDR output, #35): its hole was punched only where
     * the glass re-drew this node, so the video showed through the buttons and nowhere else.
     */
    boolean sampling = true;

    public void setSampling(boolean on) {
        if (sampling != on) {
            sampling = on;
            invalidate();
        }
    }

    public GlassSource(@NonNull Context context) {
        super(context);
    }

    public GlassSource(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (node != null && sampling && canvas.isHardwareAccelerated()) {
            node.setPosition(0, 0, getWidth(), getHeight());
            Canvas recording = node.beginRecording();
            super.dispatchDraw(recording);
            node.endRecording();
            canvas.drawRenderNode(node);
        } else {
            super.dispatchDraw(canvas);
        }
    }
}
