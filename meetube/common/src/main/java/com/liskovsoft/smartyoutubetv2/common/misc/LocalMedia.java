package com.liskovsoft.smartyoutubetv2.common.misc;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.Collections;
import java.util.List;

/**
 * JOYBOY(media tab): the seam between the shared Browse presenter and the phone's local media
 * library (same shape as {@link VideoDownloads}). The phone installs the handler; the presenter
 * only asks it for the cards of the Media section, so :common never touches MediaStore.
 */
public final class LocalMedia {
    /** Section id of the Media tab (Downloads is 200). */
    public static final int SECTION_ID = 300;

    public interface Handler {
        /** The local videos as ordinary cards (Video.localUri set, so the player opens the file). */
        List<Video> listSectionVideos();

        /** True for a folder card of the Media tab (it opens a folder instead of playing). */
        boolean isFolder(Video video);

        /** Enters the folder the card stands for; the next listSectionVideos() returns its videos. */
        void openFolder(Video folder);

        /** Leaves the open folder (back to the folders list). False when none was open. */
        boolean closeFolder();

        /** Name of the open folder, or null at the top level. */
        String openFolderName();
    }

    @Nullable
    private static Handler sHandler;

    private LocalMedia() {
    }

    public static void setHandler(@Nullable Handler handler) {
        sHandler = handler;
    }

    public static boolean isAvailable() {
        return sHandler != null;
    }

    public static boolean isFolder(@Nullable Video video) {
        Handler handler = sHandler;
        return handler != null && video != null && handler.isFolder(video);
    }

    public static void openFolder(Video folder) {
        Handler handler = sHandler;
        if (handler != null) {
            handler.openFolder(folder);
        }
    }

    public static boolean closeFolder() {
        Handler handler = sHandler;
        return handler != null && handler.closeFolder();
    }

    @Nullable
    public static String openFolderName() {
        Handler handler = sHandler;
        return handler != null ? handler.openFolderName() : null;
    }

    public static List<Video> listSectionVideos() {
        Handler handler = sHandler;
        return handler != null ? handler.listSectionVideos() : Collections.<Video>emptyList();
    }
}
