package ai.deepmost.triage.integrity

import ai.deepmost.triage.data.FileStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Persists the on-screen driver signature (PNG) and provides a device-bound HMAC over the
 * record manifest. The HMAC key lives in the Android Keystore and never leaves the device, so
 * a finalized manifest carries a signature that a different device cannot forge.
 */
class SignatureStore(private val fileStore: FileStore) {

    fun saveSignature(inspectionId: String, pngBytes: ByteArray): String {
        val file = fileStore.signatureFile(inspectionId)
        file.writeBytes(pngBytes)
        return file.absolutePath
    }

    fun signatureSha(inspectionId: String): String? {
        val file = fileStore.signatureFile(inspectionId)
        return if (file.exists()) Hashing.sha256File(file) else null
    }

    /** HMAC-SHA256 of the canonical manifest string with the device-bound key (hex). */
    fun deviceSign(manifestString: String): String? = try {
        val key = getOrCreateKey()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        mac.doFinal(manifestString.toByteArray(Charsets.UTF_8)).toHex()
    } catch (t: Throwable) {
        Timber.e(t, "Device HMAC unavailable")
        null
    }

    fun verifyDeviceSign(manifestString: String, signatureHex: String): Boolean =
        deviceSign(manifestString)?.equals(signatureHex, ignoreCase = true) == true

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .build()
        )
        return gen.generateKey()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]); sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "triage_record_hmac"
    }
}
