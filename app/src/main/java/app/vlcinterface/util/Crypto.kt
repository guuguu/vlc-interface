package app.vlcinterface.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passwords at rest are encrypted with a key that never leaves the Android Keystore ("ks:" prefix).
 * Passwords inside an export file are encrypted with a key derived from the user's passphrase ("pp:" prefix).
 */
object Crypto {
    private const val ALIAS = "vlc-interface-secrets"
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, keystoreKey())
            "ks:" + b64(c.iv) + ":" + b64(c.doFinal(plain.toByteArray()))
        } catch (e: Exception) {
            plain
        }
    }

    fun open(stored: String): String {
        if (!stored.startsWith("ks:")) return stored
        return try {
            val parts = stored.split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, unb64(parts[1])))
            String(c.doFinal(unb64(parts[2])))
        } catch (e: Exception) {
            ""
        }
    }

    private fun passKey(pass: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pass.toCharArray(), salt, 120_000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun sealWithPassphrase(plain: String, pass: String): String {
        if (plain.isEmpty()) return ""
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, passKey(pass, salt), GCMParameterSpec(128, iv))
        return "pp:" + b64(salt) + ":" + b64(iv) + ":" + b64(c.doFinal(plain.toByteArray()))
    }

    /** Returns null when the passphrase is wrong. */
    fun openWithPassphrase(sealed: String, pass: String): String? {
        if (!sealed.startsWith("pp:")) return sealed
        return try {
            val p = sealed.split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, passKey(pass, unb64(p[1])), GCMParameterSpec(128, unb64(p[2])))
            String(c.doFinal(unb64(p[3])))
        } catch (e: Exception) {
            null
        }
    }
}
