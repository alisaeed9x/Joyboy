package com.liskovsoft.sharedutils.cronet

import android.content.Context
import com.liskovsoft.sharedutils.BuildConfig
import com.liskovsoft.sharedutils.mylogger.Log
import java.io.File
import java.util.Date
import java.util.concurrent.Executors
import org.chromium.net.CronetEngine
import org.chromium.net.RequestFinishedInfo
import org.chromium.net.impl.NativeCronetProvider

object CronetManager {
    private val TAG = CronetManager::class.java.simpleName

    /** Logcat tag shared with the app-level open-path milestones (common NetPath). */
    private const val NETPATH_TAG = "NetPath"

    /**
     * Dedicated subdir for Cronet's disk prefs. With HTTP_CACHE_DISK_NO_HTTP this stores ONLY
     * QUIC server configs + network-quality estimates - NOT HTTP response bodies - so the first
     * media request after a cold start can attempt QUIC 0-RTT instead of paying the full
     * TCP+TLS(+QUIC discovery) handshake. This is a different mechanism from the media/subtitle
     * response caching that SharedModules PR#11 rejected (that concern was about body caching;
     * media bodies stay in ExoPlayer's CacheDataSource tier).
     */
    private const val STORAGE_DIR_NAME = "cronet-prefs"
    private const val DISK_PREFS_BYTES = 1024L * 1024 // 1 MB - server configs are tiny

    @Volatile
    private var engine: CronetEngine? = null
    @Volatile
    private var initializationAttempted = false

    @JvmStatic
    @Synchronized
    fun getEngine(context: Context): CronetEngine? {
        if (initializationAttempted) {
            return engine
        }
        // Exactly one native-engine build per process. Concurrent player/preconnect creation used
        // to race two builders, and a non-linker RuntimeException retried on every later video.
        initializationAttempted = true

        try {
            val builder = NativeCronetProvider(context.applicationContext).createBuilder()
            val quicEnabled = !BuildConfig.DEBUG || debugProperty("debug.arc.cronet_quic") != "off"

            builder
                .enableQuic(quicEnabled)
                .enableHttp2(true)
                .enableBrotli(true)

            // QUIC config persistence (see STORAGE_DIR_NAME doc). The storage path must exist
            // before build(); failure to create it just skips persistence.
            try {
                val storageDir = File(context.cacheDir, STORAGE_DIR_NAME)
                storageDir.mkdirs()
                if (storageDir.isDirectory) {
                    builder
                        .setStoragePath(storageDir.absolutePath)
                        .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, DISK_PREFS_BYTES)
                }
            } catch (e: Exception) {
                Log.e(TAG, "cronet storage path setup failed: ${safeMessage(e)}")
            }

            val built = builder.build()

            // Debug-only transport observability: one dense line per finished request under
            // the NetPath tag - negotiated protocol (h3/h2/http1.1), status, ttfb, total,
            // bytes, socket reuse. Answers "is googlevideo actually on QUIC / are connections
            // reused across videos / did the cold start pay a handshake" without a debugger.
            // Same debug detection as OkHttpCommons.debugSetup (sharedutils BuildConfig).
            if (BuildConfig.DEBUG) {
                val listenerExecutor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "CronetNetPath").apply { isDaemon = true }
                }
                built.addRequestFinishedListener(object : RequestFinishedInfo.Listener(listenerExecutor) {
                    override fun onRequestFinished(info: RequestFinishedInfo) {
                        try {
                            val response = info.responseInfo
                            val metrics = info.metrics
                            val url = info.url
                            // Never log the signed query. Host + first path segment distinguishes
                            // media/innertube/warm requests without exposing GVS credentials.
                            val parsed = android.net.Uri.parse(url)
                            val firstSegment = parsed.pathSegments.firstOrNull() ?: ""
                            val shortUrl = (parsed.host ?: "?") + "/" + firstSegment.take(32)
                            android.util.Log.d(NETPATH_TAG, "cronet " +
                                    (response?.negotiatedProtocol ?: "?") + " " +
                                    (response?.httpStatusCode ?: -1) +
                                    " ttfb=" + (metrics?.ttfbMs ?: -1) +
                                    "ms total=" + (metrics?.totalTimeMs ?: -1) +
                                    "ms rx=" + (metrics?.receivedByteCount ?: -1) +
                                    " reused=" + (if (metrics?.socketReused == true) "y" else "n") +
                                    " dns=" + elapsed(metrics?.dnsStart, metrics?.dnsEnd) +
                                    // connect includes TLS where both phases are reported; these
                                    // intervals overlap and must not be added together.
                                    " connect=" + elapsed(metrics?.connectStart, metrics?.connectEnd) +
                                    " tls=" + elapsed(metrics?.sslStart, metrics?.sslEnd) +
                                    " requestWait=" + elapsed(metrics?.sendingEnd, metrics?.responseStart) +
                                    " preDns=" + elapsed(metrics?.requestStart, metrics?.dnsStart) +
                                    // googlevideo PO-token forensics: pot-less media URLs die
                                    // at a ~60s-of-served-media grace wall on pot-enforcing
                                    // (carrier CGNAT) networks - one glance tells whether the
                                    // dying request even carried the token. Query form (pot=)
                                    // on VOD format URLs; path form (/pot/) on live manifest +
                                    // segment URLs.
                                    " pot=" + (if (url.contains("pot=") || url.contains("/pot/")) "y" else "n") +
                                    " " + shortUrl)
                        } catch (_: Exception) {
                            // observability must never break the transport
                        }
                    }
                })
            }

            engine = built
            android.util.Log.d(NETPATH_TAG, "cronet-init ready provider=native quic=" +
                    (if (quicEnabled) "y" else "n") + " h2=y impl=" + built.javaClass.simpleName)
        } catch (error: Throwable) {
            // LinkageError is the historical failure, but provider construction/build can also
            // throw RuntimeException on ABI/OEM/storage problems. Fail closed exactly once and let
            // every caller use its normal OkHttp/DefaultHttp fallback for the rest of the process.
            if (error is VirtualMachineError || error is ThreadDeath) {
                throw error
            }
            engine = null
            val detail = "${error.javaClass.simpleName}:${safeMessage(error)}"
            Log.e(TAG, "cronet init failed; using HTTP fallback: $detail")
            android.util.Log.w(NETPATH_TAG, "cronet-init fallback=http error=$detail")
        }

        return engine
    }

    private fun elapsed(start: Date?, end: Date?): Long =
        if (start == null || end == null || end.before(start)) -1 else end.time - start.time

    /** Debug-only transport comparison; never changes release behavior. Read at engine creation. */
    private fun debugProperty(key: String): String = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, "") as? String ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun safeMessage(error: Throwable): String {
        val printable = (error.message ?: "none")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return printable.take(140)
    }
}
