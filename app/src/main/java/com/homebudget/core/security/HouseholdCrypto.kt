package com.homebudget.core.security

import com.homebudget.data.remote.KeyEnvelope
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class Sealed(val nonce: ByteArray, val ciphertext: ByteArray)

/**
 * AES-256-GCM for cloud payloads. A fresh 96-bit random nonce is generated for every encryption
 * (never reused with the same key). Household key distribution uses a recovery passphrase:
 * PBKDF2-HMAC-SHA256 derives a key-encryption key that wraps the 256-bit household key.
 */
@Singleton
class HouseholdCrypto @Inject constructor() {
    private val random = SecureRandom()

    fun generateHouseholdKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): Sealed {
        require(key.size == 32) { "Household key must be 256-bit" }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return Sealed(nonce, cipher.doFinal(plaintext))
    }

    fun decrypt(key: ByteArray, sealed: Sealed, aad: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(sealed.ciphertext)
        } catch (e: AEADBadTagException) {
            throw CryptoException("Authentication failed (wrong key or tampered data)", e)
        } catch (e: Exception) {
            throw CryptoException("Decryption failed", e)
        }
    }

    fun wrapWithPassphrase(householdId: String, keyVersion: Int, householdKey: ByteArray, passphrase: CharArray): KeyEnvelope {
        validatePassphrase(passphrase)
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val kek = deriveKek(passphrase, salt, PBKDF2_ITERATIONS)
        val sealed = encrypt(kek, householdKey, envelopeAad(householdId, keyVersion))
        kek.fill(0)
        return KeyEnvelope(
            householdId = householdId,
            keyVersion = keyVersion,
            iterations = PBKDF2_ITERATIONS,
            salt = b64(salt),
            nonce = b64(sealed.nonce),
            wrappedKey = b64(sealed.ciphertext),
        )
    }

    /** @throws CryptoException when the passphrase is wrong or the envelope was modified. */
    fun unwrapWithPassphrase(envelope: KeyEnvelope, passphrase: CharArray): ByteArray {
        if (envelope.kdf != "PBKDF2WithHmacSHA256") throw CryptoException("Unsupported KDF")
        val kek = deriveKek(passphrase, unb64(envelope.salt), envelope.iterations)
        try {
            return decrypt(kek, Sealed(unb64(envelope.nonce), unb64(envelope.wrappedKey)), envelopeAad(envelope.householdId, envelope.keyVersion))
        } catch (e: CryptoException) {
            throw CryptoException("Incorrect recovery passphrase", e)
        } finally {
            kek.fill(0)
        }
    }

    private fun deriveKek(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun envelopeAad(householdId: String, keyVersion: Int) = "homebudget-key|$householdId|$keyVersion".toByteArray()

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        const val PBKDF2_ITERATIONS = 210_000
        const val MIN_PASSPHRASE_LENGTH = 8

        fun validatePassphrase(p: CharArray) {
            if (p.size < MIN_PASSPHRASE_LENGTH) throw CryptoException("Recovery passphrase must be at least $MIN_PASSPHRASE_LENGTH characters")
        }

        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}
