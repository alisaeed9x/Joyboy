package com.liskovsoft.appupdatechecker2.other.downloadmanager;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.SystemClock;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Progress listener example:
 * <pre>{@code
 * final ProgressListener progressListener = new ProgressListener() {
 *     @Override
 *     public void update(long bytesRead, long contentLength, boolean done) {
 *         System.out.println(bytesRead);
 *         System.out.println(contentLength);
 *         System.out.println(done);
 *         System.out.format("%d%% done\n", (100 * bytesRead) / contentLength);
 *     }
 * };
 * MyRequest.setProgressListener(progressListener);
 * }</pre>
 */
public final class DownloadManager {
    private static final String TAG = DownloadManager.class.getSimpleName();
    private static final int NUM_TRIES = 10;
    private final Context mContext;
    private final OkHttpClient mClient;
    private MyRequest mRequest;
    private long mRequestId;
    private InputStream mResponseStream;
    private int mTotalLen = 0;
    private Uri mFileUri;
    // NEWTUBE(update-stall): a download is abandoned when a whole window passes with (almost) no
    // bytes, NOT after a fixed wall-clock budget. The old 60 s cap zeroed any APK download slower
    // than ~9 Mbps and the caller deleted it, so a slow link re-downloaded the first 60 s of the
    // APK on every check and never finished. A fully silent socket still fails on the read timeout.
    static final long STALL_WINDOW_MS = 30 * 1_000;
    static final long STALL_MIN_BYTES = 32 * 1024; // ~1 KB/s over the window
    // NEWTUBE(update-metered): how often an automatic download re-checks for a metered network
    static final long METERED_CHECK_INTERVAL_MS = 2_000;
    public static final String METERED_MESSAGE = "Automatic update download skipped: metered network";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36";
    private static final String ACCEPT_CONTENT = "*/*";
    private final Map<String, String> mHeaders = new HashMap<>();

    public DownloadManager(Context context) {
        mContext = context;
        mClient = createOkHttpClient();
        mHeaders.put("User-Agent", USER_AGENT);
        mHeaders.put("Accept", ACCEPT_CONTENT);
    }

    private void doDownload() {
        if (!isNetworkAvailable()) {
            MessageHelpers.showMessage(mContext, "Internet connection not available!");
        }

        String url = mRequest.mDownloadUri.toString();

        // NEWTUBE(update-metered): a download nobody asked for doesn't start on a metered network
        if (!mRequest.isMeteredAllowed() && isActiveNetworkMetered(mContext)) {
            android.util.Log.d("NetPath", "update-download skip reason=metered");
            throw new IllegalStateException(METERED_MESSAGE);
        }

        Log.d(TAG, "Starting download %s...", url);

        // NEWTUBE(update-flow): the Call is kept on the request so the user's Cancel aborts the
        // transfer at once (OkHttpManager.doRequest, used before, never exposed it).
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(mHeaders))
                .build();
        Call call = mClient.newCall(request);
        mRequest.attach(call);

        Response response;

        try {
            response = call.execute();
        } catch (IOException ex) {
            Log.e(TAG, ex.getMessage()); // network error
            throw new IllegalStateException("Interrupted OkHttp request to " + url, ex);
        }

        if (response == null || response.body() == null) {
            throw new IllegalStateException("Error: bad response");
        }

        mResponseStream = new BufferedInputStream(response.body().byteStream());

        if (mRequest.mDestinationUri != null) {
            // NOTE: actual downloading is happen when reading the stream to file
            mFileUri = streamToFile(mResponseStream, getDestination());
        }
    }

    //private void doDownload() {
    //    if (!isNetworkAvailable()) {
    //        MessageHelpers.showMessage(mContext, "Internet connection not available!");
    //    }
    //
    //    String url = mRequest.mDownloadUri.toString();
    //
    //    Response response = OkHttpHelpers.doOkHttpRequest(url, mClient);
    //
    //    if (response == null || response.body() == null) {
    //        throw new IllegalStateException("Error: bad response");
    //    }
    //
    //    try {
    //        // NOTE: actual downloading is going here (while reading a stream)
    //        mResponseStream = new ByteArrayInputStream(response.body().bytes());
    //    } catch (IOException ex) {
    //        throw new IllegalStateException(ex);
    //    }
    //}

    private void doDownload2() {
        if (!isNetworkAvailable()) {
            MessageHelpers.showMessage(mContext, "Internet connection not available!");
        }

        String url = mRequest.mDownloadUri.toString();

        Request request = new Request.Builder()
                .url(url)
                .build();

        for (int tries = NUM_TRIES; tries > 0; tries--) {
            try {
                Response response = mClient.newCall(request).execute();
                if (!response.isSuccessful()) throw new IllegalStateException("Unexpected code " + response);

                // NOTE: actual downloading is going here (while reading a stream)
                mResponseStream = new ByteArrayInputStream(response.body().bytes());
                break; // no exception is thrown - job is done
            } catch (SocketTimeoutException | UnknownHostException ex) {
                if (tries == 1) // swallow num times
                    throw new IllegalStateException(ex);
            } catch (IOException | RuntimeException ex) {
                throw new IllegalStateException(ex);
            }
        }

    }

    private OkHttpClient createOkHttpClient() {
        Interceptor intercept = new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                Response originalResponse = chain.proceed(chain.request());
                return originalResponse.newBuilder().body(new ProgressResponseBody(originalResponse.body(), mRequest.mProgressListener)).build();
            }
        };

        //Builder builder = new OkHttpClient.Builder()
        //        .addNetworkInterceptor(intercept);
        //
        //OkHttpCommons.setupBuilder(builder);

        // Streaming variant: the shared client now carries a 45s callTimeout (a total bound, so a
        // dribbling link can no longer hang a request forever). An APK is tens of MB and legitimately
        // takes longer than that on any ordinary mobile link, so the download takes the exempt
        // client - same pool and config, no total bound.
        Builder builder = OkHttpManager.instance().getStreamingClient().newBuilder()
                .addNetworkInterceptor(intercept);

        return builder.build();
    }

    private Uri streamToFile(InputStream is, Uri destination) {
        if (destination == null) {
            return null;
        }

        FileOutputStream fos = null;

        try {
            fos = new FileOutputStream(destination.getPath());

            // The probe is re-read on every check: a user-initiated re-check can lift the restriction
            // mid-download, and the binder call is skipped entirely while metered is allowed.
            Transfer transfer = copyGuarded(is, fos, SystemClock::elapsedRealtime,
                    () -> !mRequest.isMeteredAllowed() && isActiveNetworkMetered(mContext));

            android.util.Log.d("NetPath", transfer.mAbortReason == null ?
                    "update-download done bytes=" + transfer.mBytes + " ms=" + transfer.mElapsedMs :
                    "update-download " + transfer.mAbortReason + " bytes=" + transfer.mBytes + " ms=" + transfer.mElapsedMs);

            // zero length = the caller discards the file
            mTotalLen = transfer.mAbortReason == null ? (int) transfer.mBytes : 0;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } finally {
            FileHelpers.closeStream(fos);
            FileHelpers.closeStream(is);
        }

        return destination;
    }

    //private Uri streamToFile(InputStream is, Uri destination) {
    //    if (destination == null) {
    //        return null;
    //    }
    //
    //    FileOutputStream fos = null;
    //
    //    try {
    //        fos = new FileOutputStream(destination.getPath());
    //
    //        byte[] buffer = new byte[1024];
    //        int len1;
    //        int totalLen = 0;
    //        while ((len1 = is.read(buffer)) != -1) {
    //            totalLen += len1;
    //            fos.write(buffer, 0, len1);
    //        }
    //
    //        mTotalLen = totalLen;
    //    } catch (IOException ex) {
    //        throw new IllegalStateException(ex);
    //    } finally {
    //        FileHelpers.closeStream(fos);
    //        FileHelpers.closeStream(is);
    //    }
    //
    //    return destination;
    //}

    private Uri getDestination() {
        if (mRequest.mDestinationUri != null) {
            return mRequest.mDestinationUri;
        }

        File cacheDir = FileHelpers.getCacheDir(mContext);

        if (cacheDir == null) {
            return null;
        }

        File outputFile = new File(cacheDir, "tmp_file");
        return Uri.fromFile(outputFile);
    }

    public long enqueue(MyRequest request) {
        mFileUri = null;
        mRequest = request;
        mRequestId = new Random().nextLong();

        doDownload();

        return mRequestId;
    }

    //public long enqueue(MyRequest request) {
    //    mFileUri = null;
    //    mRequest = request;
    //    mRequestId = new Random().nextLong();
    //
    //    doDownload();
    //
    //    if (mRequest.mDestinationUri != null) {
    //        mFileUri = streamToFile(mResponseStream, getDestination());
    //    }
    //
    //    return mRequestId;
    //}

    public void remove(long downloadId) {
        mClient.dispatcher().cancelAll();
    }

    public Uri getUriForDownloadedFile(long requestId) {
        if (mFileUri == null) {
            mFileUri = streamToFile(mResponseStream, getDestination());
        }

        return mFileUri;
    }

    /**
     * Length in bytes of the file that obtained via {@link #getUriForDownloadedFile(long)}
     * @param requestId unique request id
     * @return length of the file in bytes
     */
    public int getSizeForDownloadedFile(long requestId) {
        return mTotalLen;
    }

    public InputStream getStreamForDownloadedFile(long requestId) {
        return mResponseStream;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    /**
     * NEWTUBE(update-metered): cellular, a metered hotspot, or a Wi-Fi the user marked metered.
     * Unknown (no service, no permission) counts as unmetered, i.e. the old behavior.
     */
    public static boolean isActiveNetworkMetered(Context context) {
        try {
            ConnectivityManager connectivityManager = context != null ?
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE) : null;
            return connectivityManager != null && connectivityManager.isActiveNetworkMetered();
        } catch (RuntimeException e) { // SecurityException without ACCESS_NETWORK_STATE
            Log.e(TAG, "isActiveNetworkMetered: %s", e.getMessage());
            return false;
        }
    }

    interface Clock {
        long nowMs();
    }

    interface MeteredProbe {
        /** @return true if the transfer must stop because it would continue on a metered network */
        boolean isMeteredBlocked();
    }

    /** Outcome of {@link #copyGuarded}. */
    static final class Transfer {
        static final String STALL = "stall";
        static final String METERED = "abort reason=metered";
        static final String METERED_AT_END = "reject reason=metered-at-end";
        final long mBytes;
        final long mElapsedMs;
        /** null = complete */
        final String mAbortReason;

        Transfer(long bytes, long elapsedMs, String abortReason) {
            mBytes = bytes;
            mElapsedMs = elapsedMs;
            mAbortReason = abortReason;
        }
    }

    /**
     * NEWTUBE(update-stall/update-metered): copies the body to the file under two guards - the
     * progress watchdog, and (for a download nobody asked for) a metered check every
     * {@link #METERED_CHECK_INTERVAL_MS} of transfer plus once more at the end, so a Wi-Fi ->
     * cellular handover can't slip the rest of the APK through on the metered network.
     */
    static Transfer copyGuarded(InputStream is, OutputStream os, Clock clock, MeteredProbe meteredProbe) throws IOException {
        long startMs = clock.nowMs();
        StallWatch stallWatch = new StallWatch(STALL_WINDOW_MS, STALL_MIN_BYTES, startMs);
        long nextMeteredCheckMs = startMs + METERED_CHECK_INTERVAL_MS;

        byte[] buffer = new byte[1024];
        int len;
        long total = 0;

        while ((len = is.read(buffer)) != -1) {
            total += len;
            os.write(buffer, 0, len);

            long nowMs = clock.nowMs();

            if (stallWatch.onBytes(len, nowMs) == StallWatch.STALLED) {
                // Oops. Downloading has stalled. Cancelling...
                return new Transfer(total, nowMs - startMs, Transfer.STALL);
            }

            // Wi-Fi -> cellular handover mid-download (a binder call: time-bounded, not per chunk)
            if (nowMs >= nextMeteredCheckMs) {
                nextMeteredCheckMs = nowMs + METERED_CHECK_INTERVAL_MS;

                if (meteredProbe.isMeteredBlocked()) {
                    return new Transfer(total, nowMs - startMs, Transfer.METERED);
                }
            }
        }

        long endMs = clock.nowMs();

        // The tail may have arrived within one check interval of a handover
        if (meteredProbe.isMeteredBlocked()) {
            return new Transfer(total, endMs - startMs, Transfer.METERED_AT_END);
        }

        return new Transfer(total, endMs - startMs, null);
    }

    /**
     * NEWTUBE(update-stall): progress watchdog. The transfer is stalled when a full window closes
     * with fewer than {@code minBytes} received in it.
     */
    static final class StallWatch {
        static final int OK = 0;
        /** A window just closed and the transfer made enough progress in it. */
        static final int WINDOW = 1;
        static final int STALLED = 2;
        private final long mWindowMs;
        private final long mMinBytes;
        private long mWindowStartMs;
        private long mWindowBytes;

        StallWatch(long windowMs, long minBytes, long nowMs) {
            mWindowMs = windowMs;
            mMinBytes = minBytes;
            mWindowStartMs = nowMs;
        }

        int onBytes(long bytes, long nowMs) {
            mWindowBytes += bytes;

            if (nowMs - mWindowStartMs < mWindowMs) {
                return OK;
            }

            boolean stalled = mWindowBytes < mMinBytes;
            mWindowStartMs = nowMs;
            mWindowBytes = 0;

            return stalled ? STALLED : WINDOW;
        }
    }

    private static class ProgressResponseBody extends ResponseBody {

        private final ResponseBody responseBody;
        private final ProgressListener progressListener;
        private BufferedSource bufferedSource;

        ProgressResponseBody(ResponseBody responseBody, ProgressListener progressListener) {
            this.responseBody = responseBody;
            this.progressListener = progressListener;
        }

        @Override
        public MediaType contentType() {
            return responseBody.contentType();
        }

        @Override
        public long contentLength() {
            return responseBody.contentLength();
        }

        @Override
        public BufferedSource source() {
            if (bufferedSource == null) {
                bufferedSource = Okio.buffer(source(responseBody.source()));
            }
            return bufferedSource;
        }

        private Source source(Source source) {
            return new ForwardingSource(source) {
                long totalBytesRead = 0L;

                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    long bytesRead = super.read(sink, byteCount);

                    if (progressListener == null) {
                        return bytesRead;
                    }

                    // read() returns the number of bytes read, or -1 if this source is exhausted.
                    totalBytesRead += bytesRead != -1 ? bytesRead : 0;
                    progressListener.update(totalBytesRead, responseBody.contentLength(), bytesRead == -1);
                    return bytesRead;
                }
            };
        }
    }

    public interface ProgressListener {
        void update(long bytesRead, long contentLength, boolean done);
    }

    public static class MyRequest {
        private final Uri mDownloadUri;
        private Uri mDestinationUri;
        private ProgressListener mProgressListener;
        private volatile boolean mAllowMetered = true;
        private volatile Call mCall;
        private volatile boolean mCancelled;

        public MyRequest(Uri uri) {
            mDownloadUri = uri;
        }

        /**
         * NEWTUBE(update-metered): false = refuse to start, and stop at the next progress window,
         * while the active network is metered. Can be flipped to true mid-download (the user asked).
         */
        public void setAllowMetered(boolean allow) {
            mAllowMetered = allow;
        }

        public boolean isMeteredAllowed() {
            return mAllowMetered;
        }

        public void setDestinationUri(Uri uri) {
            mDestinationUri = uri;
        }

        public void setProgressListener(ProgressListener listener) {
            mProgressListener = listener;
        }

        /** NEWTUBE(update-flow): aborts the transfer from any thread; reads then fail. */
        public void cancel() {
            mCancelled = true;
            Call call = mCall;
            if (call != null) {
                call.cancel();
            }
        }

        void attach(Call call) {
            mCall = call;
            if (mCancelled) {
                call.cancel();
            }
        }
    }
}
