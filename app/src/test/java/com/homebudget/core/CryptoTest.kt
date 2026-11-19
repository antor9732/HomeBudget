package com.homebudget.core

import com.homebudget.core.security.CryptoException
import com.homebudget.core.security.HouseholdCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CryptoTest {
    private val c = HouseholdCrypto()

    @Test fun `round trip with aad`() {
        val k = c.generateHouseholdKey()
        val s = c.encrypt(k, "hello".toByteArray(), "aad".toByteArray())
        assertArrayEquals("hello".toByteArray(), c.decrypt(k, s, "aad".toByteArray()))
    }

    @Test fun `tampered metadata is rejected`() {
        val k = c.generateHouseholdKey()
        val s = c.encrypt(k, "x".toByteArray(), "aad".toByteArray())
        assertThrows(CryptoException::class.java) { c.decrypt(k, s, "other".toByteArray()) }
    }

    @Test fun `nonces are unique`() {
        val k = c.generateHouseholdKey()
        val nonces = (1..500).map { c.encrypt(k, byteArrayOf(1), byteArrayOf()).nonce.toList() }.toSet()
        assert(nonces.size == 500)
    }

    @Test fun `passphrase wrap and unwrap`() {
        val k = c.generateHouseholdKey()
        val env = c.wrapWithPassphrase("hh", 1, k, "correct horse".toCharArray())
        assertArrayEquals(k, c.unwrapWithPassphrase(env, "correct horse".toCharArray()))
        assertThrows(CryptoException::class.java) { c.unwrapWithPassphrase(env, "wrong horse".toCharArray()) }
        assertFalse(env.wrappedKey.contains(HouseholdCrypto.b64(k)))
    }

    @Test fun `short passphrase rejected`() {
        assertThrows(CryptoException::class.java) { c.wrapWithPassphrase("hh", 1, c.generateHouseholdKey(), "short".toCharArray()) }
    }
}
