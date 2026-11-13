package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * MEETUBE(seek thumb): the player seek bar's dot can be a picture the user picks (a GIF or an
 * animated WebP moves). The picked file is copied to the app's files dir; no file = the dot.
 * Also the invisible host for the system image picker, like {@code DownloadFolderActivity}.
 */
public final class SeekThumb extends Activity {
    private static final int REQUEST_IMAGE = 1;
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int DECODE_PX = 192;

    public static final int DOT = 0;
    public static final int PICTURE = 1;

    private static File file(Context context) {
        return new File(context.getFilesDir(), "seek_thumb");
    }

    public static int type(Context context) {
        return file(context).exists() ? PICTURE : DOT;
    }

    public static void setType(Context context, int type) {
        if (type == PICTURE) {
            context.startActivity(new Intent(context, SeekThumb.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            //noinspection ResultOfMethodCallIgnored
            file(context).delete();
        }
    }

    /** A new drawable each call (an animated one keeps a single callback, so one per bar). */
    @Nullable
    static Drawable load(Context context) {
        File f = file(context);
        if (!f.exists()) {
            return null;
        }
        try {
            Drawable d;
            if (Build.VERSION.SDK_INT >= 28) {
                d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f), (decoder, info, source) -> {
                    int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (max > DECODE_PX) {
                        decoder.setTargetSampleSize(Math.max(1, max / DECODE_PX));
                    }
                });
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / DECODE_PX);
                o.inJustDecodeBounds = false;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                d = b != null ? new BitmapDrawable(context.getResources(), b) : null;
            }
            if (d instanceof Animatable) {
                ((Animatable) d).start();
            }
            return d;
        } catch (Exception | OutOfMemoryError e) {
            return null; // a broken file: the dot
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            return; // the picker is already up
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(pick, REQUEST_IMAGE);
        } catch (ActivityNotFoundException e) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (uri != null) {
            File tmp = new File(getFilesDir(), "seek_thumb.tmp");
            long total = 0;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while (in != null && (n = in.read(buf)) > 0 && (total += n) <= MAX_BYTES) {
                    out.write(buf, 0, n);
                }
            } catch (Exception e) {
                total = -1;
            }
            // A too big or unreadable pick leaves the previous picture (or the dot) as it was.
            File old = new File(getFilesDir(), "seek_thumb.old");
            boolean hadOld = file(this).renameTo(old);
            if (total > 0 && total <= MAX_BYTES && tmp.renameTo(file(this)) && load(this) != null) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            } else {
                //noinspection ResultOfMethodCallIgnored
                file(this).delete();
                if (hadOld) {
                    //noinspection ResultOfMethodCallIgnored
                    old.renameTo(file(this));
                }
                com.newtube.mobile.ui.common.MobileSnackbar.show(this,
                        com.liskovsoft.smartyoutubetv2.tv.R.string.mobile_settings_seek_thumb_failed);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        finish();
    }
}
