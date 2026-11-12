package com.liskovsoft.sharedutils.okhttp;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OkHttpManager {
    private static final String TAG = OkHttpManager.class.getSimpleName();
    private static OkHttpManager sInstance;
    private OkHttpClient mClient;
    private OkHttpClient mStreamingClient;
    private final boolean mEnableProfiler;

    private OkHttpManager(boolean enableProfiler) {
        mEnableProfiler = enableProfiler;
    }

    public static OkHttpManager instance() {
        // Profiler disabled by default (see OkHttpCommons.enableProfiler): it only feeds an
        // Android-Studio plugin while flooding debug logcat. Pass true explicitly to re-enable.
        return instance(false);
    }

    // NEWTUBE: synchronized for the same preconnect-vs-first-API-call race as getClient().
    public static synchronized OkHttpManager instance(boolean enableProfiler) {
        if (sInstance == null) {
            sInstance = new OkHttpManager(enableProfiler);
        }

        return sInstance;
    }

    public static void unhold() {
        sInstance = null;
    }

    /** NEWTUBE(mobile): see {@link OkHttpCommons#preferHttp2}. Call before the first client is built. */
    public static synchronized void setPreferHttp2(boolean preferHttp2) {
        OkHttpCommons.preferHttp2 = preferHttp2;
        // A late call is silently a no-op (the built client and every newBuilder() copy keep the
        // old protocol list). That happened once and pinned all InnerTube traffic to HTTP/1.1 for
        // a whole round; say so loudly instead.
        OkHttpManager instance = sInstance;
        if (instance != null && instance.mClient != null) {
            android.util.Log.w("NetPath", "api-client prefer-http2=" + preferHttp2
                    + " IGNORED: client already built with protocols=" + instance.mClient.protocols());
        }
    }

    /**
     * NEWTUBE(mobile): drop every pooled connection.
     *
     * <p>Call when the DEFAULT NETWORK IS REPLACED (Wi-Fi to cellular and back). The sockets in the
     * pool are bound to the old network: they are not reset, they are half-open, so the next API
     * call picks one from the pool and waits out the full read timeout before OkHttp retries on a
     * fresh connection - and with HTTP/2 every InnerTube call shares that one connection, so the
     * whole UI stalls together. {@link okhttp3.ConnectionPool#evictAll()} closes idle connections
     * and marks the in-flight ones "no new exchanges", so nothing in progress is cancelled.
     *
     * <p>One pool covers the app: RetrofitOkHttpHelper (InnerTube) and the other consumers derive
     * their clients from this one with {@code newBuilder()}, which copies the pool REFERENCE.
     *
     * <p>Never builds a client: with no client yet there is nothing pooled to evict.
     */
    public static synchronized void evictConnections() {
        OkHttpManager instance = sInstance;
        if (instance != null) {
            instance.evictPooledConnections();
        }
    }

    public Response doRequest(String url) {
        return doRequest(url, getClient());
    }

    public Response doRequest(String url, Map<String, String> headers, @Nullable String postBody, @Nullable String contentType) {
        return postBody == null ? doGetRequest(url, headers) : doPostRequest(url, headers, postBody, contentType);
    }

    public Response doGetRequest(String url) {
        return doGetRequest(url, getClient());
    }

    public Response doGetRequest(String url, Map<String, String> headers) {
        if (headers == null) {
            Log.d(TAG, "Headers are null... doing regular request...");
            return doGetRequest(url, getClient());
        }

        return doGetRequest(url, getClient(), headers);
    }

    public Response doPostRequest(String url, Map<String, String> headers, String postBody, @Nullable String contentType) {
        return doPostRequest(url, getClient(), headers, postBody, contentType);
    }

    public Response doHeadRequest(String url) {
        return doHeadRequest(url, getClient());
    }

    /**
     * NEWTUBE(mobile): fire a HEAD purely to establish the pooled DNS+TCP+TLS connection and
     * release it straight back (void so callers without okhttp on their classpath can use it).
     * Throws like the other do*Request helpers on network failure.
     */
    public void warmUpConnection(String url) {
        Response response = doHeadRequest(url);
        if (response != null) {
            response.close();
        }
    }

    /**
     * NOTE: default method is GET
     */
    public Response doRequest(String url, OkHttpClient client) {
        Request okHttpRequest = new Request.Builder()
                .url(url)
                .build();

        return doRequest(client, okHttpRequest);
    }

    /**
     * NOTE: default method is GET
     */
    public Response doRequest(String url, OkHttpClient client, Map<String, String> headers) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        
        Request okHttpRequest = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .build();

        return doRequest(client, okHttpRequest);
    }

    private Response doPostRequest(String url, OkHttpClient client, Map<String, String> headers, String body, @Nullable String contentType) {
        if (headers == null) {
            headers = new HashMap<>();
        }

        Request okHttpRequest = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .post(RequestBody.create(contentType != null ? MediaType.parse(contentType) : null, body))
                .build();

        return doRequest(client, okHttpRequest);
    }

    private Response doGetRequest(String url, OkHttpClient client, Map<String, String> headers) {
        Request okHttpRequest = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .get()
                .build();

        return doRequest(client, okHttpRequest);
    }

    private Response doGetRequest(String url, OkHttpClient client) {
        Request okHttpRequest = new Request.Builder()
                .url(url)
                .get()
                .build();

        return doRequest(client, okHttpRequest);
    }

    private Response doHeadRequest(String url, OkHttpClient client) {
        Request okHttpRequest = new Request.Builder()
                .url(url)
                .head()
                .build();

        return doRequest(client, okHttpRequest);
    }

    private Response doRequest(OkHttpClient client, Request okHttpRequest) {
        try {
            return client.newCall(okHttpRequest).execute();
        } catch (IOException ex) {
            Log.e(TAG, ex.getMessage()); // network error
            throw new IllegalStateException("Interrupted OkHttp request to " + okHttpRequest.url(), ex);
        }
    }

    // NEWTUBE: synchronized — the app-start preconnect thread (MobileMainApplication) races the
    // first API call here; an unsynchronized double-build would give them separate connection
    // pools, so the warmed connection would never be the one the API client reuses.
    public synchronized OkHttpClient getClient() {
        if (mClient == null) {
            OkHttpCommons.enableProfiler = mEnableProfiler;
            mClient = OkHttpCommons.setupBuilder(new OkHttpClient.Builder()).build();
        }

        return mClient;
    }

    /**
     * NEWTUBE(mobile): the shared client MINUS the total-call bound, for transfers whose duration
     * is set by the payload rather than by the server's responsiveness - the in-app APK download
     * and the cast proxy's upstream media fetches. Everything else (pool, timeouts, interceptors,
     * DNS, TLS) is identical, and the connection pool is shared, so
     * {@link #evictConnections()} still covers it.
     *
     * <p>Without this, {@link OkHttpCommons#CALL_TIMEOUT_MS} would kill any transfer that legitimately
     * runs past 45s - a ~60MB update APK does that on any ordinary mobile link.
     */
    public synchronized OkHttpClient getStreamingClient() {
        if (mStreamingClient == null) {
            mStreamingClient = getClient().newBuilder()
                    .callTimeout(0, TimeUnit.MILLISECONDS) // 0 = no total bound
                    .build();
        }

        return mStreamingClient;
    }

    private synchronized void evictPooledConnections() {
        if (mClient == null) {
            return;
        }

        int pooled = mClient.connectionPool().connectionCount();
        mClient.connectionPool().evictAll();
        Log.d(TAG, "Evicted %s pooled connection(s) after a network change", pooled);
    }

    public static long getConnectTimeoutMs() {
        return OkHttpCommons.CONNECT_TIMEOUT_MS;
    }

    public static long getReadTimeoutMs() {
        return OkHttpCommons.READ_TIMEOUT_MS;
    }
    
    public static long getWriteTimeoutMs() {
        return OkHttpCommons.WRITE_TIMEOUT_MS;
    }
}
