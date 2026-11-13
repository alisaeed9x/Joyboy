package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * MEETUBE(seek preview): YouTube's seek-bar drag. Over the paused picture the frames around the
 * finger roll by as a filmstrip, the one under the finger enlarged with a white edge, and the time
 * flies in big at the middle. Frames come from the video's storyboard sprite sheets; without one
 * (live, or none sent) only the time shows.
 *
 * <p>ponytail: sheets stay cached per video and are never evicted (a few 100 KB each, dropped with
 * the view's next video).
 */
public final class SeekFilmstrip extends View {
    private final float mDp = getResources().getDisplayMetrics().density;
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBitmap = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final SparseArray<Bitmap> mSheets = new SparseArray<>();
    private final SparseArray<Object> mLoading = new SparseArray<>();
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();
    @Nullable private MediaItemStoryboard mBoard;
    @Nullable private MediaItemStoryboard.Size mSize;
    @Nullable private Disposable mFetch;
    @Nullable private String mVideoId;
    private long mPosition;
    private final Paint mSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String mTime = "";
    private CharSequence mTitle = "";

    public SeekFilmstrip(Context context) {
        this(context, null);
    }

    public SeekFilmstrip(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mText.setColor(0xFFFFFFFF);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mText.setTextSize(40 * mDp);
        mText.setShadowLayer(8 * mDp, 0, 0, 0xCC000000);
        mSub.setColor(0xFFFFFFFF);
        mSub.setTextAlign(Paint.Align.CENTER);
        mSub.setTextSize(15 * mDp);
        mSub.setShadowLayer(6 * mDp, 0, 0, 0xCC000000);
        mFill.setColor(0xFF1A1A1A);
        mEdge.setColor(0xFFFFFFFF);
        mEdge.setStyle(Paint.Style.STROKE);
        mEdge.setStrokeWidth(2 * mDp);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // the text shadow
        setClickable(false);
    }

    /** Loads the storyboard of this video (once) when the drag starts. */
    public void prepare(@Nullable String videoId) {
        if (videoId == null || videoId.equals(mVideoId)) {
            return;
        }
        mVideoId = videoId;
        mBoard = null;
        mSize = null;
        mSheets.clear();
        mLoading.clear();
        if (mFetch != null) {
            mFetch.dispose();
        }
        mFetch = YouTubeMediaItemService.instance().getStoryboardObserve(videoId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(board -> {
                    mBoard = board;
                    mSize = board.getGroupSize();
                    invalidate();
                }, error -> { /* no filmstrip, the time still shows */ });
    }

    public void update(long positionMs, @NonNull String time, @Nullable CharSequence title) {
        mPosition = positionMs;
        mTime = time;
        mTitle = title == null ? "" : title;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        MediaItemStoryboard.Size size = mSize;
        if (size != null && size.getDurationEachMS() > 0 && size.getHeight() > 0) {
            drawStrip(canvas, size, cx, cy + 56 * mDp);
        }
        canvas.drawText(mTime, cx, cy - 30 * mDp, mText);
        if (mTitle.length() > 0) {
            canvas.drawText(android.text.TextUtils.ellipsize(mTitle, new android.text.TextPaint(mSub),
                    getWidth() - 48 * mDp, android.text.TextUtils.TruncateAt.END).toString(), cx, cy - 4 * mDp, mSub);
        }
    }

    private void drawStrip(Canvas canvas, MediaItemStoryboard.Size size, float cx, float cy) {
        float h = 64 * mDp;
        float w = h * size.getWidth() / size.getHeight();
        long each = size.getDurationEachMS();
        int centre = (int) (mPosition / each);
        int reach = (int) (cx / w) + 2;
        for (int k = -reach; k <= reach; k++) {
            int index = centre + k;
            if (index < 0) {
                continue;
            }
            // frame i covers [i*each, (i+1)*each): its middle sits at that offset from the finger
            float x = cx + ((index + 0.5f) * each - mPosition) / each * w;
            if (k == 0) {
                continue; // drawn last, enlarged
            }
            draw(canvas, size, index, x - w / 2, cy - h / 2, w - 2 * mDp, h);
        }
        float big = 1.3f;
        float bx = cx + ((centre + 0.5f) * each - mPosition) / each * w;
        draw(canvas, size, centre, bx - w * big / 2, cy - h * big / 2, w * big, h * big);
        canvas.drawRect(mDst, mEdge);
    }

    private void draw(Canvas canvas, MediaItemStoryboard.Size size, int index, float left, float top,
            float width, float height) {
        mDst.set(left, top, left + width, top + height);
        if (mDst.right < 0 || mDst.left > getWidth()) {
            return;
        }
        int perSheet = Math.max(1, size.getColCount() * size.getRowCount());
        Bitmap sheet = sheet(index / perSheet);
        int inSheet = index % perSheet;
        if (sheet == null) {
            canvas.drawRect(mDst, mFill);
            return;
        }
        int col = inSheet % size.getColCount();
        int row = inSheet / size.getColCount();
        mSrc.set(col * size.getWidth(), row * size.getHeight(),
                (col + 1) * size.getWidth(), (row + 1) * size.getHeight());
        if (mSrc.right > sheet.getWidth() || mSrc.bottom > sheet.getHeight()) {
            canvas.drawRect(mDst, mFill); // a short last sheet
            return;
        }
        canvas.drawBitmap(sheet, mSrc, mDst, mBitmap);
    }

    @Nullable
    private Bitmap sheet(int group) {
        Bitmap bitmap = mSheets.get(group);
        if (bitmap != null || mBoard == null || mLoading.get(group) != null) {
            return bitmap;
        }
        final String videoId = mVideoId;
        final MediaItemStoryboard board = mBoard;
        String url = board.getGroupUrl(group);
        if (url == null) {
            return null;
        }
        mLoading.put(group, this);
        Glide.with(getContext().getApplicationContext()).asBitmap().load(url).into(new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> t) {
                if (board == mBoard && java.util.Objects.equals(videoId, mVideoId)) {
                    mSheets.put(group, resource);
                    invalidate();
                }
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                // leave the placeholder; a later move will not retry this sheet
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
        return null;
    }
}
