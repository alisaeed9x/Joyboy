package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.appcompat.widget.AppCompatImageButton;

/**
 * MEETUBE(watch-look): a player button the person can hide in Customization. Hidden, it stays
 * gone whatever the player code asks (cast found, a live stream's chat...), so that code needs no
 * check of its own. Read once per inflation: a change shows the next time the player opens.
 */
public class PlayerButton extends AppCompatImageButton {
    private final boolean mHidden;

    public PlayerButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mHidden = getId() != View.NO_ID
                && !WatchLook.isShown(context, getResources().getResourceEntryName(getId()));
        if (mHidden) {
            super.setVisibility(GONE);
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(mHidden ? GONE : visibility);
    }
}
