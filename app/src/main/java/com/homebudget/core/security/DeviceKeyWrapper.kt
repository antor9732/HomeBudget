package com.homebudget.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Protects device-local key material (the household key) at rest. */
interface DeviceKeyWrapper {
    fun wrap(plain: ByteArray): String
    fun unwrap(wrapped: String): ByteArray
}

/** Android Keystore-backed AES-256-GCM key that never leaves secure hardware/OS keystore. */
@Singleton
class AndroidKeystoreKeyWrapper @Inject constructor() : DeviceKeyWrapper {
    private val alias = "homebudget_device_wrap_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()) // Keystore generates a unique IV
        val ct = cipher.doFinal(plain)
        return HouseholdCrypto.b64(cipher.iv) + ":" + HouseholdCrypto.b64(ct)
    }

    override fun unwrap(wrapped: String): ByteArray {
        val (iv, ct) = wrapped.split(":").let { HouseholdCrypto.unb64(it[0]) to HouseholdCrypto.unb64(it[1]) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }
}
