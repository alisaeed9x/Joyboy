package com.tttt.subtitler

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * (v824) بلوتوث جوه رادار نقل الملفات: بحث عن الأجهزة القريبة (موبايل · تابلت · كمبيوتر · تلفزيون · سماعة/سبيكر · ساعة · مش معروف)
 * + اقتران من جوه التطبيق (الكود بيظهر عندنا) من غير ما تفتح إعدادات أندرويد.
 * حدود أندرويد (مش من التطبيق): تأكيد الكود نفسه (setPairingConfirmation) صلاحية نظام، فلو الجهاز طلب مقارنة كود ممكن تظهر نافذة تأكيد من النظام كمان —
 * والتوصيل الصوتي (A2DP) مش API عام، فبنحاول بطريقة غير رسمية وممكن تفشل على بعض الموبايلات (السماعة غالبًا بتتوصّل لوحدها بعد الاقتران).
 */
@SuppressLint("MissingPermission")
object BtScan {
    const val REQ = 7731
    private const val ACT_PAIR = "android.bluetooth.device.action.PAIRING_REQUEST"
    private const val X_VARIANT = "android.bluetooth.device.extra.PAIRING_VARIANT"
    private const val X_KEY = "android.bluetooth.device.extra.PAIRING_KEY"

    private val h = Handler(Looper.getMainLooper())
    private val seen = ConcurrentHashMap<String, FtPeer>()
    /** (v831) آخر مرة اتشاف فيها الجهاز فعلًا في المدى — الأجهزة القديمة (المتزاوجة من زمان) مبتظهرش */
    private val seenAt = ConcurrentHashMap<String, Long>()
    const val NO_NAME = "جهاز بلوتوث"
    private const val TTL_MS = 35_000L
    fun fresh(id: String): Boolean = (seenAt[id] ?: 0L) > System.currentTimeMillis() - TTL_MS
    private var rx: BroadcastReceiver? = null
    private var app: Context? = null
    private var active = false
    private var cb: ((FtPeer) -> Unit)? = null

    fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun granted(c: Context): Boolean = perms().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun adapter(c: Context): BluetoothAdapter? = try { (c.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter } catch (_: Throwable) { null }
    fun available(c: Context): Boolean = adapter(c) != null
    fun enabled(c: Context): Boolean = try { adapter(c)?.isEnabled == true } catch (_: Throwable) { false }

    /** نوع الجهاز من كلاس البلوتوث: phone · tablet · pc · tv · audio · watch · periph · unk */
    private fun kindOf(d: BluetoothDevice): Pair<String, String> {
        val bc = (try { d.bluetoothClass } catch (_: Throwable) { null }) ?: return "unk" to ""
        return when (bc.majorDeviceClass) {
            BluetoothClass.Device.Major.PHONE -> "phone" to ""
            BluetoothClass.Device.Major.COMPUTER ->
                if (bc.deviceClass == BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA || bc.deviceClass == BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA) "tablet" to "" else "pc" to ""
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (bc.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> "tv" to ""
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "audio" to "سماعة"
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "audio" to "سبيكر"
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "audio" to "صوت عربية"
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "audio" to "هاندزفري"
                else -> "audio" to ""
            }
            BluetoothClass.Device.Major.WEARABLE -> "watch" to ""
            BluetoothClass.Device.Major.PERIPHERAL -> "periph" to ""
            else -> "unk" to ""
        }
    }

    private fun peerOf(d: BluetoothDevice): FtPeer {
        val (t, note) = kindOf(d)
        val nm = try { d.name } catch (_: Throwable) { null }
        val bonded = try { d.bondState == BluetoothDevice.BOND_BONDED } catch (_: Throwable) { false }
        return FtPeer("bt:" + d.address, if (nm.isNullOrBlank()) NO_NAME else nm, t, d.address, 0, "", "", 0, 0, false, null, false, d.address, bonded, note)
    }

    /** نسخة محدّثة من جهاز بعد الاقتران (متزاوج ✓) */
    fun peerOf(c: Context, addr: String): FtPeer? = try { adapter(c)?.getRemoteDevice(addr)?.let { peerOf(it) } } catch (_: Throwable) { null }

    /** present = الجهاز اتشاف دلوقتي فعلًا (اكتشاف/متوصّل). false = تحديث بيانات جهاز ظاهر أصلًا (اسم/نوع/اقتران) */
    private fun put(d: BluetoothDevice?, present: Boolean = true) {
        if (d == null) return
        val p = try { peerOf(d) } catch (_: Throwable) { return }
        if (present) seenAt[p.id] = System.currentTimeMillis()
        else if (seenAt[p.id] == null) return
        seen[p.id] = p
        cb?.invoke(p)
    }

    /** أجهزة متزاوجة ومتوصّلة حاليًا (سماعة شغّالة مثلًا) — isConnected مش API عام فبنجرّبه بالـreflection */
    private fun markConnected(ad: BluetoothAdapter) {
        try {
            for (d in ad.bondedDevices ?: emptySet()) {
                val on = try { d.javaClass.getMethod("isConnected").invoke(d) as? Boolean } catch (_: Throwable) { null } ?: false
                if (on) put(d)
            }
        } catch (_: Throwable) {}
    }

    private fun devOf(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** بحث مستمر طول ما الرادار مفتوح (بيتعاد تلقائيًا). onFound بيتنادى على خيط الواجهة. false = مش متاح (مقفول/من غير صلاحية) */
    fun start(ctx: Context, onFound: (FtPeer) -> Unit): Boolean {
        val a = ctx.applicationContext
        val ad = adapter(a) ?: return false
        if (!granted(a) || !ad.isEnabled) return false
        stop()
        app = a; cb = onFound; active = true; seen.clear(); seenAt.clear()
        markConnected(ad)   // (v831) كان بيضيف كل المتزاوجين القدام كأنهم قريبين — دلوقتي المتوصّلين بس
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try {
                    when (i.action) {
                        BluetoothDevice.ACTION_FOUND -> put(devOf(i))
                        BluetoothDevice.ACTION_NAME_CHANGED, BluetoothDevice.ACTION_CLASS_CHANGED, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> put(devOf(i), false)
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (active) { markConnected(ad); h.postDelayed({ if (active) try { ad.startDiscovery() } catch (_: Throwable) {} }, 1500L) }
                    }
                } catch (_: Throwable) {}
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_NAME_CHANGED); addAction(BluetoothDevice.ACTION_CLASS_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try { ContextCompat.registerReceiver(a, r, f, ContextCompat.RECEIVER_EXPORTED) } catch (e: Throwable) { LogStore.err("BtScan:reg", e); return false }
        rx = r
        try { ad.cancelDiscovery(); ad.startDiscovery() } catch (_: Throwable) {}
        return true
    }

    fun stop() {
        active = false; cb = null
        val a = app
        rx?.let { try { a?.unregisterReceiver(it) } catch (_: Throwable) {} }; rx = null
        try { a?.let { adapter(it)?.cancelDiscovery() } } catch (_: Throwable) {}
    }

    /** اقتران من جوه التطبيق. onCode(variant, code): variant 0 PIN · 1 اكتب الكود · 2 قارن الكود · 3 موافقة بس · 4/5 اكتب الكود ده على الجهاز التاني.
     *  بيرجّع دالة إلغاء. onDone بيتنادى مرة واحدة على خيط الواجهة */
    fun pair(ctx: Context, addr: String, onCode: (Int, String) -> Unit, onDone: (Boolean, String) -> Unit): () -> Unit {
        val a = ctx.applicationContext
        val ad = adapter(a)
        val dev = try { ad?.getRemoteDevice(addr) } catch (_: Throwable) { null }
        if (ad == null || dev == null || !granted(a)) { h.post { onDone(false, "البلوتوث مش متاح") }; return {} }
        var finished = false
        var pinTry = 0
        var rcv: BroadcastReceiver? = null
        fun finish(ok: Boolean, msg: String) {
            if (finished) return
            finished = true
            rcv?.let { try { a.unregisterReceiver(it) } catch (_: Throwable) {} }
            h.removeCallbacksAndMessages(addr)
            if (active) try { ad.startDiscovery() } catch (_: Throwable) {}
            onDone(ok, msg)
        }
        if (dev.bondState == BluetoothDevice.BOND_BONDED) { h.post { finish(true, "متزاوج بالفعل") }; return {} }
        rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = devOf(i) ?: return
                if (d.address != addr) return
                try {
                    when (i.action) {
                        ACT_PAIR -> {
                            val v = i.getIntExtra(X_VARIANT, -1)
                            val key = i.getIntExtra(X_KEY, Int.MIN_VALUE)
                            val code = if (key == Int.MIN_VALUE) "" else String.format(java.util.Locale.US, "%06d", key)
                            when (v) {
                                0 -> {   // PIN قديم (سماعات/سبيكرات): نجرّب 0000 ثم 1234
                                    val pin = if (pinTry++ % 2 == 0) "0000" else "1234"
                                    try { d.setPin(pin.toByteArray()) } catch (_: Throwable) {}
                                    onCode(0, pin)
                                }
                                2 -> {   // مقارنة كود: بنعرضه، والتأكيد ممكن يحتاج نافذة النظام
                                    onCode(2, code)
                                    try { d.javaClass.getMethod("setPairingConfirmation", Boolean::class.javaPrimitiveType).invoke(d, true) } catch (_: Throwable) {}
                                }
                                4, 5 -> onCode(v, code)
                                1 -> onCode(1, "")
                                else -> onCode(3, "")
                            }
                        }
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                            val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                            if (st == BluetoothDevice.BOND_BONDED) finish(true, "اتزاوج مع " + (try { d.name } catch (_: Throwable) { null } ?: "الجهاز"))
                            else if (st == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) finish(false, "الاقتران اتلغى أو اترفض")
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        val f = IntentFilter().apply { addAction(ACT_PAIR); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1 }
        try { ContextCompat.registerReceiver(a, rcv, f, ContextCompat.RECEIVER_EXPORTED) } catch (e: Throwable) { LogStore.err("BtScan:pairReg", e); h.post { finish(false, "فشل بدء الاقتران") }; return {} }
        try { ad.cancelDiscovery() } catch (_: Throwable) {}
        val ok = try { dev.createBond() } catch (_: Throwable) { false }
        if (!ok) { h.post { finish(false, "فشل بدء الاقتران") }; return {} }
        h.postAtTime({ finish(false, "انتهت المهلة — الجهاز مردّش") }, addr, android.os.SystemClock.uptimeMillis() + 60_000L)
        return { h.post { try { dev.javaClass.getMethod("cancelBondProcess").invoke(dev) } catch (_: Throwable) {}; finish(false, "اتلغى") } }
    }

    /** محاولة توصيل الصوت (A2DP + هاندزفري) لجهاز متزاوج — بطريقة غير رسمية، ممكن ترجع false على بعض الموبايلات */
    fun connectAudio(ctx: Context, addr: String, done: (Boolean) -> Unit) {
        val a = ctx.applicationContext
        val ad = adapter(a)
        val dev = try { ad?.getRemoteDevice(addr) } catch (_: Throwable) { null }
        if (ad == null || dev == null || !granted(a)) { done(false); return }
        var okAny = false; var pending = 2; var called = false
        fun fin() { h.post { pending--; if (pending <= 0 && !called) { called = true; done(okAny) } } }
        for (prof in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            val started = try {
                ad.getProfileProxy(a, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        try {
                            if (proxy.getConnectionState(dev) == BluetoothProfile.STATE_CONNECTED) okAny = true
                            else if (proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, dev) == true) okAny = true
                        } catch (_: Throwable) {}
                        try { ad.closeProfileProxy(p, proxy) } catch (_: Throwable) {}
                        fin()
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, prof)
            } catch (_: Throwable) { false }
            if (!started) fin()
        }
        h.postDelayed({ if (!called) { called = true; done(okAny) } }, 8000L)
    }

    /** إرسال ملفات لموبايل بالبلوتوث عن طريق شاشة البلوتوث الرسمية في أندرويد (مفيش API عام لإرسال ملف لجهاز محدد) */
    fun shareFiles(act: android.app.Activity, uris: ArrayList<android.net.Uri>): Boolean {
        val it = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try { act.startActivity(Intent(it).setPackage("com.android.bluetooth")); true }
        catch (_: Throwable) { try { act.startActivity(Intent.createChooser(it, "بلوتوث")); true } catch (_: Throwable) { false } }
    }
}
