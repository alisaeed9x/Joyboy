package com.newtube.mobile.ui.playback;

import android.view.View;

import com.newtube.mobile.ui.common.Motion;

/**
 * MEETUBE(mini-controls): the mini card's buttons fade out a few seconds after the card shows,
 * leaving a bare picture; a finger on the card brings them back. A tap on the video still expands
 * at once: a first tap that only revealed the buttons read as "the card won't open" (issue #34).
 * Faded buttons are INVISIBLE, so a tap where one was reaches the video instead of pausing.
 */
public final class MiniControls {
    private static final long SHOWN_MS = 3000;

    private final View[] mButtons;
    private final Runnable mHide = this::hide;

    public MiniControls(View... buttons) {
        mButtons = buttons;
    }

    /** Up (faded back in, if they had gone), and down again SHOWN_MS from now. */
    public void show() {
        android.content.Context context = mButtons[0].getContext();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
                && context instanceof android.app.Activity
                && ((android.app.Activity) context).isInPictureInPictureMode()) {
            return; // a PiP window is a bare picture (MiniPip hides the buttons)
        }
        for (View button : mButtons) {
            if (button.getVisibility() != View.VISIBLE) {
                button.setAlpha(0f);
                button.setVisibility(View.VISIBLE);
            }
            if (button.getAlpha() < 1f) { // faded, or fading out (a hide's end action is cancelled)
                button.animate().cancel();
                button.animate().alpha(1f).setDuration(Motion.FADE_IN_MS).setInterpolator(Motion.STANDARD).start();
            }
        }
        mButtons[0].removeCallbacks(mHide);
        mButtons[0].postDelayed(mHide, SHOWN_MS);
    }

    private void hide() {
        if (MiniSize.type(mButtons[0].getContext()) != MiniSize.DEFAULT) {
            return; // a bar keeps its pause and close up
        }
        for (View button : mButtons) {
            button.animate().alpha(0f).setDuration(Motion.FADE_OUT_MS).setInterpolator(Motion.STANDARD)
                    .withEndAction(() -> button.setVisibility(View.INVISIBLE)).start();
        }
    }

    /** The card went: no fade pending, and it comes back with its buttons up. */
    public void reset() {
        mButtons[0].removeCallbacks(mHide);
        for (View button : mButtons) {
            button.animate().cancel();
            button.setAlpha(1f);
            button.setVisibility(View.VISIBLE);
        }
    }
}
