package com.tttt.subtitler

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * (v653) تشفير نقل الملفات الذكي (من غير مكتبات خارجية — JCA بس):
 *  • هوية طويلة المدى: مفتاح EC P-256 لكل جهاز (بيوقّع على عرض النقل ← تحقق من هوية الطرف التاني).
 *  • كل جلسة: ECDH مؤقت على الطرفين ← HKDF-SHA256 ← مفتاح AES-256-GCM.
 *  • كل جزء (1 ميجا) بيتشفّر ويتوثّق لوحده (AES-GCM) ← أي تعديل في الطريق بيتكشف، واستكمال النقل بيتم من حدود الأجزاء.
 *  • كود تحقق قصير (SAS) من 4 أرقام بيظهر على الجهازين — لو متطابق يبقى مفيش وسيط في النص.
 */
object FtCrypto {
    const val CHUNK = 1 shl 20
    private val rnd = SecureRandom()
    @Volatile private var idKp: KeyPair? = null

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    fun hex(b: ByteArray): String = b.joinToString("") { String.format("%02x", it) }
    fun randHex(n: Int): String { val b = ByteArray(n); rnd.nextBytes(b); return hex(b) }
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    // ───────── الهوية ─────────
    fun newKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        return g.generateKeyPair()
    }

    @Synchronized fun identity(ctx: Context): KeyPair {
        idKp?.let { return it }
        val sp = ctx.applicationContext.getSharedPreferences("ft_private", Context.MODE_PRIVATE)
        val pu = sp.getString("pub", "") ?: ""
        val pr = sp.getString("priv", "") ?: ""
        if (pu.isNotEmpty() && pr.isNotEmpty()) {
            try {
                val kf = KeyFactory.getInstance("EC")
                val kp = KeyPair(kf.generatePublic(X509EncodedKeySpec(unb64(pu))), kf.generatePrivate(PKCS8EncodedKeySpec(unb64(pr))))
                idKp = kp
                return kp
            } catch (_: Throwable) {}
        }
        val kp = newKeyPair()
        sp.edit().putString("pub", b64(kp.public.encoded)).putString("priv", b64(kp.private.encoded)).apply()
        idKp = kp
        return kp
    }

    fun pubOf(bytes: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    /** بصمة الهوية: أول 16 خانة hex من SHA-256 للمفتاح العام */
    fun fingerprint(pub: ByteArray): String = hex(sha256(pub)).take(16)
    fun fpPretty(fp: String): String = fp.uppercase().chunked(4).joinToString("-")

    fun sign(priv: PrivateKey, data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv); s.update(data)
        return s.sign()
    }

    fun verify(pub: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pub); s.update(data)
        s.verify(sig)
    } catch (_: Throwable) { false }

    // ───────── مفتاح الجلسة ─────────
    fun ecdh(priv: PrivateKey, peerPub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv); ka.doPhase(peerPub, true)
        return ka.generateSecret()
    }

    fun hkdf(secret: ByteArray, salt: ByteArray, info: String, len: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        val inf = info.toByteArray(Charsets.UTF_8)
        while (out.size() < len) {
            mac.update(t); mac.update(inf); mac.update(i.toByte())
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(len)
    }

    fun sessionKey(secret: ByteArray, sid: String): ByteArray = hkdf(secret, sid.toByteArray(Charsets.UTF_8), "joyboy-ft-v1/key")

    fun hmac(key: ByteArray, data: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(m.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    /** علامة توثيق لطلب (جزء / هاش / إشعار) — بتمنع أي حد مالوش مفتاح الجلسة من طلب أو إلغاء حاجة */
    fun tag(key: ByteArray, sid: String, op: String, f: Int, n: Long): String = hmac(key, "$sid|$op|$f|$n")

    fun safeEq(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    // ───────── تشفير الأجزاء ─────────
    private fun iv(file: Int, chunk: Long): ByteArray {
        val b = ByteArray(12)
        b[0] = (file ushr 24).toByte(); b[1] = (file ushr 16).toByte(); b[2] = (file ushr 8).toByte(); b[3] = file.toByte()
        for (i in 0 until 8) b[4 + i] = (chunk ushr (56 - 8 * i)).toByte()
        return b
    }

    fun encChunk(key: ByteArray, sid: String, file: Int, chunk: Long, plain: ByteArray, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv(file, chunk)))
        c.updateAAD("$sid:$file:$chunk".toByteArray(Charsets.UTF_8))
        return c.doFinal(plain, 0, len)
    }

    fun decChunk(key: ByteArray, sid: String, file: Int, chunk: Long, enc: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv(file, chunk)))
        c.updateAAD("$sid:$file:$chunk".toByteArray(Charsets.UTF_8))
        return c.doFinal(enc)
    }

    /** كود التحقق القصير: نفس القيمة عند الطرفين لو مفيش وسيط */
    fun sas(fpSender: String, fpReceiver: String, epubSender: ByteArray, epubReceiver: ByteArray): String {
        val bo = ByteArrayOutputStream()
        bo.write(fpSender.toByteArray(Charsets.UTF_8)); bo.write(fpReceiver.toByteArray(Charsets.UTF_8))
        bo.write(epubSender); bo.write(epubReceiver)
        val h = sha256(bo.toByteArray())
        val n = (((h[0].toInt() and 0xff) shl 16) or ((h[1].toInt() and 0xff) shl 8) or (h[2].toInt() and 0xff)) % 10000
        return String.format("%04d", n)
    }
}
