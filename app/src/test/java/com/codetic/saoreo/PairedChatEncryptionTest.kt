package com.codetic.saoreo

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.crypto.DirectDecrypter
import org.junit.Assert.*
import org.junit.Test

class PairedChatEncryptionTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun exchangesJweWithoutExposingThePayloadAndUsesFreshNonces() {
        val text = """{"id":"example","text":"Private message"}"""
        val first = encryptChatEnvelope(key, text)
        val second = encryptChatEnvelope(key, text)
        assertFalse(first.contains("Private message"))
        assertNotEquals(first, second)
        val parts = first.split('.')
        assertEquals(5, parts.size)
        assertEquals("", parts[1])
        assertEquals(16, parts[2].length)
        assertEquals(22, parts[4].length)
        assertEquals(text, decryptChatEnvelope(DirectDecrypter(key), first))
    }

    @Test(expected = JOSEException::class)
    fun rejectsCiphertextWithAChangedAuthenticationTag() {
        val pieces = encryptChatEnvelope(key, "message").split('.').toMutableList()
        val tag = pieces[4]
        pieces[4] = (if (tag.first() == 'A') 'B' else 'A') + tag.drop(1)
        decryptChatEnvelope(DirectDecrypter(key), pieces.joinToString("."))
    }

    @Test(expected = JOSEException::class)
    fun rejectsOtherPairingKeys() {
        decryptChatEnvelope(DirectDecrypter(ByteArray(32)), encryptChatEnvelope(key, "message"))
    }
}
