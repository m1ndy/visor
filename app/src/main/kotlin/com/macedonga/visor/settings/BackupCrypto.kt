package com.macedonga.visor.settings

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONException
import org.json.JSONObject

/** Wrong passphrase or tampered ciphertext when opening an encrypted backup. */
class WrongPassphraseException(cause: Throwable? = null) : Exception("wrong passphrase or damaged backup", cause)

/** The encrypted backup envelope is malformed or uses unsupported parameters. */
class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Passphrase-based encryption for backups that carry keys and credentials.
 * Uses PBKDF2-HMAC-SHA256 (600,000 iterations) and AES-256-GCM with the envelope parameters bound as AAD.
 */
internal object BackupCrypto {
    const val MIN_PASSPHRASE_LENGTH = 8

    private const val VERSION = 1
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 5_000_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8

    private val random = SecureRandom()

    fun isEncrypted(json: JSONObject): Boolean = json.optBoolean("encrypted", false)

    fun encrypt(payload: JSONObject, passphrase: CharArray): JSONObject {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "passphrase too short" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val keyBytes = deriveKey(passphrase, salt, ITERATIONS)
        val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(KDF, ITERATIONS, saltB64))
            val ciphertext = cipher.doFinal(plaintext)
            JSONObject()
                .put("visor", VERSION)
                .put("encrypted", true)
                .put("saved", payload.optString("saved"))
                .put("kdf", KDF)
                .put("iterations", ITERATIONS)
                .put("salt", saltB64)
                .put("cipher", CIPHER)
                .put("iv", ivB64)
                .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
        } finally {
            keyBytes.fill(0)
            plaintext.fill(0)
        }
    }

    fun decrypt(envelope: JSONObject, passphrase: CharArray): JSONObject {
        if (!isEncrypted(envelope)) throw InvalidBackupException("backup is not encrypted")
        val version = envelope.optInt("visor", -1)
        if (version != VERSION) throw InvalidBackupException("unsupported backup version: $version")
        val kdf = envelope.optString("kdf")
        val cipherName = envelope.optString("cipher")
        val iterations = envelope.optInt("iterations", -1)
        if (kdf != KDF) throw InvalidBackupException("unsupported KDF: $kdf")
        if (cipherName != CIPHER) throw InvalidBackupException("unsupported cipher: $cipherName")
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw InvalidBackupException("invalid KDF iterations: $iterations")

        val saltB64 = envelope.optString("salt")
        val ivB64 = envelope.optString("iv")
        val ctB64 = envelope.optString("ciphertext")
        val salt = decodeBase64(saltB64, "salt")
        val iv = decodeBase64(ivB64, "iv")
        val ciphertext = decodeBase64(ctB64, "ciphertext")
        if (salt.size != SALT_BYTES) throw InvalidBackupException("invalid salt length")
        if (iv.size != IV_BYTES) throw InvalidBackupException("invalid IV length")
        if (ciphertext.size <= TAG_BYTES) throw InvalidBackupException("invalid ciphertext length")

        val keyBytes = deriveKey(passphrase, salt, iterations)
        var plaintext: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(kdf, iterations, saltB64))
            plaintext = cipher.doFinal(ciphertext)
            val parsed = JSONObject(String(plaintext, Charsets.UTF_8))
            if (!parsed.has("visor")) throw InvalidBackupException("decrypted payload is not a Visor backup")
            parsed
        } catch (error: AEADBadTagException) {
            throw WrongPassphraseException(error)
        } catch (error: GeneralSecurityException) {
            throw WrongPassphraseException(error)
        } catch (error: JSONException) {
            throw InvalidBackupException("decrypted payload is not valid JSON", error)
        } finally {
            keyBytes.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(kdf: String, iterations: Int, saltB64: String): ByteArray =
        "visor-backup:$VERSION:$kdf:$iterations:$saltB64".toByteArray(Charsets.UTF_8)

    private fun decodeBase64(text: String, field: String): ByteArray {
        if (text.isEmpty()) throw InvalidBackupException("missing $field")
        return try {
            Base64.getDecoder().decode(text)
        } catch (error: IllegalArgumentException) {
            throw InvalidBackupException("invalid base64 in $field", error)
        }
    }
}
